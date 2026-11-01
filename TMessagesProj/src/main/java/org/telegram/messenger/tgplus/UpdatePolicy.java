package org.telegram.messenger.tgplus;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides which GitHub release to offer as an update. Pure Java for tests.
 *
 * Matches the release layout published by the build workflow: one rolling release per
 * branch, tagged {@code build-<branch>}, carrying one asset named
 * {@code TelegramPlus-<version>-<branch>-build<run>-<sha7>.apk}. The workflow run number
 * increases across all branches, so a larger build number is a newer build.
 */
public final class UpdatePolicy {

    private UpdatePolicy() {}

    static final Pattern APK_NAME = Pattern.compile("^TelegramPlus-.+-build(\\d{1,9})-([0-9a-f]{7,40})\\.apk$");
    static final Pattern SHA256 = Pattern.compile("^sha256:([0-9a-f]{64})$");
    public static final String TAG_PREFIX = "build-";

    public enum Status {
        /** No release for the selected channel, or it has no usable APK. */
        NO_RELEASE,
        /** This app was not built by the release workflow, so versions cannot be compared. */
        LOCAL_BUILD,
        UPDATE_AVAILABLE,
        UP_TO_DATE,
        /** The installed build is newer than the channel's release. */
        INSTALLED_NEWER
    }

    public static final class Asset {
        public final String name, url, contentType;
        public final long size;
        /** Lower-case hex SHA-256 when GitHub reports one, otherwise null. */
        public final String sha256;

        public Asset(String name, String url, long size, String contentType, String digest) {
            this.name = name;
            this.url = url;
            this.size = size;
            this.contentType = contentType;
            String sha = null;
            if (digest != null) {
                Matcher m = SHA256.matcher(digest.toLowerCase(Locale.US));
                if (m.matches()) sha = m.group(1);
            }
            this.sha256 = sha;
        }
    }

    public static final class Release {
        public final String tag, title, commit, publishedAt;
        public final boolean prerelease, draft;
        public final List<Asset> assets;

        public Release(String tag, String title, String commit, String publishedAt, boolean prerelease, boolean draft, List<Asset> assets) {
            this.tag = tag;
            this.title = title;
            this.commit = commit;
            this.publishedAt = publishedAt;
            this.prerelease = prerelease;
            this.draft = draft;
            this.assets = assets == null ? new ArrayList<>() : assets;
        }

        public String channel() {
            return tag != null && tag.startsWith(TAG_PREFIX) ? tag.substring(TAG_PREFIX.length()) : tag;
        }
    }

    public static final class Candidate {
        public final Release release;
        public final Asset apk;
        public final int buildNumber;
        public final String shortSha;

        Candidate(Release release, Asset apk, int buildNumber, String shortSha) {
            this.release = release;
            this.apk = apk;
            this.buildNumber = buildNumber;
            this.shortSha = shortSha;
        }
    }

    /** Same transformation as the workflow: every character outside [A-Za-z0-9._-] becomes '-'. */
    public static String safeBranch(String branch) {
        if (branch == null) return "";
        return branch.replaceAll("[^A-Za-z0-9._-]", "-");
    }

    public static String tagForBranch(String branch) {
        return TAG_PREFIX + safeBranch(branch);
    }

    /** The APK of a release, or null if the release is a draft or has no matching asset. */
    public static Candidate candidate(Release release) {
        if (release == null || release.draft) return null;
        Candidate best = null;
        for (Asset asset : release.assets) {
            if (asset == null || asset.name == null || asset.url == null || !asset.url.startsWith("https://")) continue;
            Matcher m = APK_NAME.matcher(asset.name);
            if (!m.matches()) continue;
            int build;
            try {
                build = Integer.parseInt(m.group(1));
            } catch (NumberFormatException e) {
                continue;
            }
            if (best == null || build > best.buildNumber) {
                best = new Candidate(release, asset, build, m.group(2).substring(0, 7));
            }
        }
        return best;
    }

    /** Channels that currently have an installable APK, in the order GitHub returned them. */
    public static List<String> channels(List<Release> releases) {
        ArrayList<String> out = new ArrayList<>();
        if (releases == null) return out;
        for (Release release : releases) {
            if (candidate(release) != null && release.tag != null && release.tag.startsWith(TAG_PREFIX)) {
                String channel = release.channel();
                if (!out.contains(channel)) out.add(channel);
            }
        }
        return out;
    }

    public static Candidate pick(List<Release> releases, String channel) {
        if (releases == null || channel == null || channel.isEmpty()) return null;
        String tag = tagForBranch(channel);
        for (Release release : releases) {
            if (tag.equals(release.tag)) return candidate(release);
        }
        return null;
    }

    public static Status compare(int installedBuild, Candidate candidate) {
        if (candidate == null) return Status.NO_RELEASE;
        if (installedBuild <= 0) return Status.LOCAL_BUILD;
        if (candidate.buildNumber > installedBuild) return Status.UPDATE_AVAILABLE;
        if (candidate.buildNumber == installedBuild) return Status.UP_TO_DATE;
        return Status.INSTALLED_NEWER;
    }
}
