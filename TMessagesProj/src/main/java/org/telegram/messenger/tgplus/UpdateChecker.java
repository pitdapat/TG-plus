package org.telegram.messenger.tgplus;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.SystemClock;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.Utilities;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Checks the repository's GitHub Releases for a newer signed build and downloads it.
 * The downloaded APK is checked against the reported size and SHA-256, and must have the
 * same package name and signing certificate as the installed app before it is offered for install.
 */
public final class UpdateChecker {

    public static final long AUTO_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000;
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 30000;
    private static final int MAX_API_BYTES = 1024 * 1024;
    private static final long MAX_APK_BYTES = 300L * 1024 * 1024;
    private static final String DEFAULT_REPO = "pitdapat/TG-plus";

    public static final String ERROR_NETWORK = "NETWORK";
    public static final String ERROR_TIMEOUT = "TIMEOUT";
    public static final String ERROR_HTTP = "HTTP";
    public static final String ERROR_RATE_LIMIT = "RATE_LIMIT";
    public static final String ERROR_PARSE = "PARSE";
    public static final String ERROR_SIZE = "SIZE_MISMATCH";
    public static final String ERROR_DIGEST = "DIGEST_MISMATCH";
    public static final String ERROR_PACKAGE = "PACKAGE_MISMATCH";
    public static final String ERROR_SIGNATURE = "SIGNATURE_MISMATCH";
    public static final String ERROR_STORAGE = "STORAGE";
    public static final String ERROR_CANCELLED = "CANCELLED";
    public static final String ERROR_OTHER = "OTHER";

    private UpdateChecker() {}

    public static int installedBuild() {
        return BuildConfig.TELEGRAM_PLUS_BUILD_NUMBER;
    }

    public static String installedBranch() {
        return BuildConfig.TELEGRAM_PLUS_BRANCH;
    }

    public static String installedSha() {
        String sha = BuildConfig.TELEGRAM_PLUS_GIT_SHA;
        return sha == null || sha.length() < 7 ? "" : sha.substring(0, 7);
    }

    public static String repository() {
        String repo = BuildConfig.TELEGRAM_PLUS_REPOSITORY;
        return repo != null && repo.matches("[A-Za-z0-9._-]+/[A-Za-z0-9._-]+") ? repo : DEFAULT_REPO;
    }

    /** The selected channel, falling back to this build's branch, then "master". */
    public static String effectiveChannel() {
        String channel = TgPlusSettings.getUpdateChannel();
        if (!TextUtils.isEmpty(channel)) return channel;
        String branch = installedBranch();
        return TextUtils.isEmpty(branch) ? "master" : branch;
    }

    public static final class CheckResult {
        public final UpdatePolicy.Status status;
        public final UpdatePolicy.Candidate candidate;
        public final List<String> channels;
        public final String channel;
        public final String error;
        public final int httpStatus;
        public final long durationMs;

        CheckResult(UpdatePolicy.Status status, UpdatePolicy.Candidate candidate, List<String> channels, String channel, String error, int httpStatus, long durationMs) {
            this.status = status;
            this.candidate = candidate;
            this.channels = channels;
            this.channel = channel;
            this.error = error;
            this.httpStatus = httpStatus;
            this.durationMs = durationMs;
        }

        public boolean ok() {
            return error == null;
        }
    }

    public static void check(String reason, Utilities.Callback<CheckResult> done) {
        Utilities.globalQueue.postRunnable(() -> {
            CheckResult result = checkBlocking(reason);
            AndroidUtilities.runOnUIThread(() -> {
                if (done != null) done.run(result);
            });
        });
    }

    public static CheckResult checkBlocking(String reason) {
        long start = SystemClock.elapsedRealtime();
        String channel = effectiveChannel();
        HttpURLConnection connection = null;
        int status = 0;
        CheckResult result;
        try {
            connection = (HttpURLConnection) new URL("https://api.github.com/repos/" + repository() + "/releases?per_page=50").openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            connection.setRequestProperty("User-Agent", "TelegramPlus-Updater");
            status = connection.getResponseCode();
            long duration = SystemClock.elapsedRealtime() - start;
            if (status == 403 || status == 429) {
                result = new CheckResult(UpdatePolicy.Status.NO_RELEASE, null, new ArrayList<>(), channel, ERROR_RATE_LIMIT, status, duration);
            } else if (status != 200) {
                result = new CheckResult(UpdatePolicy.Status.NO_RELEASE, null, new ArrayList<>(), channel, ERROR_HTTP, status, duration);
            } else {
                List<UpdatePolicy.Release> releases = parseReleases(readLimited(connection.getInputStream(), MAX_API_BYTES));
                UpdatePolicy.Candidate candidate = UpdatePolicy.pick(releases, channel);
                result = new CheckResult(UpdatePolicy.compare(installedBuild(), candidate), candidate, UpdatePolicy.channels(releases), channel, null, status,
                    SystemClock.elapsedRealtime() - start);
            }
        } catch (SocketTimeoutException e) {
            result = new CheckResult(UpdatePolicy.Status.NO_RELEASE, null, new ArrayList<>(), channel, ERROR_TIMEOUT, status, SystemClock.elapsedRealtime() - start);
        } catch (UnknownHostException e) {
            result = new CheckResult(UpdatePolicy.Status.NO_RELEASE, null, new ArrayList<>(), channel, ERROR_NETWORK, status, SystemClock.elapsedRealtime() - start);
        } catch (java.io.IOException e) {
            result = new CheckResult(UpdatePolicy.Status.NO_RELEASE, null, new ArrayList<>(), channel, ERROR_NETWORK, status, SystemClock.elapsedRealtime() - start);
        } catch (Exception e) {
            result = new CheckResult(UpdatePolicy.Status.NO_RELEASE, null, new ArrayList<>(), channel, ERROR_PARSE, status, SystemClock.elapsedRealtime() - start);
        } finally {
            if (connection != null) connection.disconnect();
        }
        TgPlusSettings.setLastUpdateCheck(System.currentTimeMillis());
        DiagnosticsLog.Event.Builder event = DiagnosticsLog.event("update", "check")
            .duration(result.durationMs)
            .tag("reason", reason)
            .tag("channel", UpdatePolicy.safeBranch(channel))
            .num("http", result.httpStatus)
            .num("installed_build", installedBuild())
            .num("release_build", result.candidate == null ? 0 : result.candidate.buildNumber)
            .tag("status", result.status.name().toLowerCase(Locale.US));
        TgPlusDiagnostics.log(result.ok() ? event.ok().build() : event.fail(result.error).build());
        return result;
    }

    static List<UpdatePolicy.Release> parseReleases(String json) throws Exception {
        JSONArray array = new JSONArray(json);
        ArrayList<UpdatePolicy.Release> releases = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject r = array.getJSONObject(i);
            ArrayList<UpdatePolicy.Asset> assets = new ArrayList<>();
            JSONArray a = r.optJSONArray("assets");
            if (a != null) {
                for (int j = 0; j < a.length(); j++) {
                    JSONObject asset = a.getJSONObject(j);
                    assets.add(new UpdatePolicy.Asset(
                        asset.optString("name", null),
                        asset.optString("browser_download_url", null),
                        asset.optLong("size", -1),
                        asset.optString("content_type", null),
                        asset.isNull("digest") ? null : asset.optString("digest", null)));
                }
            }
            releases.add(new UpdatePolicy.Release(
                r.optString("tag_name", null),
                r.optString("name", ""),
                r.optString("target_commitish", ""),
                r.optString("published_at", ""),
                r.optBoolean("prerelease", false),
                r.optBoolean("draft", false),
                assets));
        }
        return releases;
    }

    public static final class Download {
        private final AtomicBoolean cancelled = new AtomicBoolean();

        public void cancel() {
            cancelled.set(true);
        }
    }

    public interface DownloadListener {
        /** Progress 0..100, on the UI thread. */
        void onProgress(int percent);

        /** Exactly one of file / error is set, on the UI thread. */
        void onDone(File file, String error);
    }

    public static File updateDir() {
        // Matches <files-path name="cache" path="/cache/"/> in provider_paths.xml for the install intent.
        return new File(new File(ApplicationLoader.applicationContext.getFilesDir(), "cache"), "tgplus-update");
    }

    public static Download download(UpdatePolicy.Candidate candidate, DownloadListener listener) {
        Download download = new Download();
        Utilities.globalQueue.postRunnable(() -> {
            long start = SystemClock.elapsedRealtime();
            String[] error = new String[1];
            File file = downloadBlocking(candidate, download, listener, error);
            DiagnosticsLog.Event.Builder event = DiagnosticsLog.event("update", "download")
                .duration(SystemClock.elapsedRealtime() - start)
                .num("release_build", candidate.buildNumber)
                .num("bytes", file == null ? 0 : file.length())
                .flag("digest_checked", candidate.apk.sha256 != null);
            TgPlusDiagnostics.log(file != null ? event.ok().build() : event.fail(error[0]).build());
            AndroidUtilities.runOnUIThread(() -> listener.onDone(file, file == null ? error[0] : null));
        });
        return download;
    }

    private static File downloadBlocking(UpdatePolicy.Candidate candidate, Download download, DownloadListener listener, String[] error) {
        File dir = updateDir();
        if (!dir.exists() && !dir.mkdirs()) {
            error[0] = ERROR_STORAGE;
            return null;
        }
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) f.delete();
        File target = new File(dir, candidate.apk.name);
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(candidate.apk.url).openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("Accept", "application/octet-stream");
            connection.setRequestProperty("User-Agent", "TelegramPlus-Updater");
            int status = connection.getResponseCode();
            if (status != 200) {
                error[0] = ERROR_HTTP;
                return null;
            }
            // Redirects must stay on HTTPS.
            if (!"https".equals(connection.getURL().getProtocol())) {
                error[0] = ERROR_HTTP;
                return null;
            }
            long expected = candidate.apk.size > 0 ? candidate.apk.size : connection.getContentLength();
            if (expected > MAX_APK_BYTES) {
                error[0] = ERROR_SIZE;
                return null;
            }
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            long total = 0;
            int lastPercent = -1;
            try (InputStream in = connection.getInputStream(); OutputStream out = new FileOutputStream(target)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    if (download.cancelled.get()) {
                        error[0] = ERROR_CANCELLED;
                        return null;
                    }
                    out.write(buffer, 0, read);
                    sha.update(buffer, 0, read);
                    total += read;
                    if (total > MAX_APK_BYTES) {
                        error[0] = ERROR_SIZE;
                        return null;
                    }
                    if (expected > 0) {
                        int percent = (int) Math.min(100, total * 100 / expected);
                        if (percent != lastPercent) {
                            lastPercent = percent;
                            AndroidUtilities.runOnUIThread(() -> listener.onProgress(percent));
                        }
                    }
                }
            }
            if (candidate.apk.size > 0 && total != candidate.apk.size) {
                error[0] = ERROR_SIZE;
                return null;
            }
            if (candidate.apk.sha256 != null && !candidate.apk.sha256.equals(hex(sha.digest()))) {
                error[0] = ERROR_DIGEST;
                return null;
            }
            String verify = verifyApk(ApplicationLoader.applicationContext, target);
            if (verify != null) {
                error[0] = verify;
                return null;
            }
            return target;
        } catch (SocketTimeoutException e) {
            error[0] = ERROR_TIMEOUT;
            return null;
        } catch (java.io.IOException e) {
            error[0] = ERROR_NETWORK;
            return null;
        } catch (Exception e) {
            error[0] = ERROR_OTHER;
            return null;
        } finally {
            if (connection != null) connection.disconnect();
            if (error[0] != null) target.delete();
        }
    }

    /** Returns null if the APK has this app's package name and signing certificate, else an error code. */
    @SuppressWarnings("deprecation")
    static String verifyApk(Context context, File apk) {
        try {
            PackageManager pm = context.getPackageManager();
            int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
            PackageInfo archive = pm.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
            if (archive == null) return ERROR_PACKAGE;
            if (archive.applicationInfo != null) {
                archive.applicationInfo.sourceDir = apk.getAbsolutePath();
                archive.applicationInfo.publicSourceDir = apk.getAbsolutePath();
            }
            if (!context.getPackageName().equals(archive.packageName)) return ERROR_PACKAGE;
            PackageInfo installed = pm.getPackageInfo(context.getPackageName(), flags);
            Set<String> archiveCerts = certificates(archive);
            Set<String> installedCerts = certificates(installed);
            if (archiveCerts.isEmpty() || installedCerts.isEmpty()) return ERROR_SIGNATURE;
            archiveCerts.retainAll(installedCerts);
            return archiveCerts.isEmpty() ? ERROR_SIGNATURE : null;
        } catch (Exception e) {
            return ERROR_SIGNATURE;
        }
    }

    @SuppressWarnings("deprecation")
    private static Set<String> certificates(PackageInfo info) throws Exception {
        HashSet<String> out = new HashSet<>();
        Signature[] signatures = null;
        if (Build.VERSION.SDK_INT >= 28 && info.signingInfo != null) {
            signatures = info.signingInfo.hasMultipleSigners() ? info.signingInfo.getApkContentsSigners() : info.signingInfo.getSigningCertificateHistory();
        } else if (info.signatures != null) {
            signatures = info.signatures;
        }
        if (signatures == null) return out;
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        for (Signature signature : signatures) {
            out.add(hex(sha.digest(signature.toByteArray())));
        }
        return out;
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format(Locale.US, "%02x", b & 255));
        return out.toString();
    }

    private static String readLimited(InputStream stream, int limit) throws java.io.IOException {
        try (InputStream in = stream) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                if (out.size() > limit) throw new java.io.IOException("response too large");
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
