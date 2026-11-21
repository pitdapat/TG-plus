package org.telegram.ui;

import android.app.ActivityManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.usage.UsageStatsManager;
import android.net.ConnectivityManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationManagerCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.RemoteMessage;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.PushDiagnostics;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Visible only on request; no background polling or analytics. */
public class PushDiagnosticsActivity extends BaseFragment {
    /** Upstream's public sample API ID; Telegram sends its pushes through Telegram's own Firebase sender. */
    private static final int SAMPLE_API_ID = 4;
    private static final int SHOWN_EVENTS = 12;
    // Rows: 0..7 details, 8..11 buttons, 12 footer.
    private static final int DETAIL_ROWS = 8, ROW_SELF_TEST = 8, ROW_APP_SETTINGS = 9, ROW_COPY = 10,
            ROW_CLEAR = 11, ROW_COUNT = 13;
    private final String[] details = new String[DETAIL_ROWS];
    private String fullEventLog = "";
    private ListAdapter adapter;
    private boolean resumed;
    private long lastRetry;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            updateDetails();
            AndroidUtilities.runOnUIThread(this, 2000);
        }
    };

    private String text(int resource) { return LocaleController.getString(resource); }
    private String line(int resource, String value) { return text(resource) + ": " + value; }
    private String yes(boolean value) { return text(value ? R.string.PushDiagYes : R.string.PushDiagNo); }
    private String when(long time) {
        return time <= 0 ? text(R.string.PushDiagNever)
                : DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(new Date(time));
    }
    private String priority(int value) {
        return text(value == RemoteMessage.PRIORITY_HIGH ? R.string.PushDiagHigh
                : value == RemoteMessage.PRIORITY_NORMAL ? R.string.PushDiagNormal : R.string.PushDiagUnknown);
    }
    private String buildLabel(Context context) {
        try {
            android.content.pm.PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            long version = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
            return BuildVars.BUILD_VERSION_STRING + " (" + version + ")";
        } catch (Exception e) { return BuildVars.BUILD_VERSION_STRING; }
    }
    private String state(String value) {
        switch (value) {
            case "requesting": return text(R.string.PushDiagRequesting);
            case "ready": return text(R.string.PushDiagReady);
            case "accepted": return text(R.string.PushDiagAccepted);
            case "failed": return text(R.string.PushDiagFailed);
            case "no_play_services": return text(R.string.PushDiagNoPlayServices);
            case "stale": return text(R.string.PushDiagStale);
            default: return text(R.string.PushDiagNotChecked);
        }
    }

    @Override public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(text(R.string.PushDiagTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) { if (id == -1) finishFragment(); }
        });
        FrameLayout frame = new FrameLayout(context);
        frame.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        fragmentView = frame;
        RecyclerListView list = new RecyclerListView(context);
        list.setLayoutManager(new LinearLayoutManager(context));
        list.setVerticalScrollBarEnabled(false);
        list.setItemAnimator(null);
        list.setAdapter(adapter = new ListAdapter(context));
        list.setOnItemClickListener((view, position) -> onRowClicked(position));
        actionBar.setAdaptiveBackground(list);
        frame.addView(list, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        updateDetails();
        return fragmentView;
    }

    private void updateDetails() {
        Context context = getContext();
        if (context == null) return;
        UserConfig account = UserConfig.getInstance(currentAccount);
        PushDiagnostics.Snapshot s = PushDiagnostics.snapshot(currentAccount, account.getClientUserId());
        details[0] = text(R.string.PushDiagPrivacy) + "\n\n" + buildLabel(context)
                + " • " + ApplicationLoader.getApplicationId();
        details[1] = configSection(context, s);
        details[2] = selfTestSection(s);
        details[3] = line(R.string.PushDiagToken, state(s.tokenState))
                + (s.tokenError.isEmpty() ? "" : "\n" + line(R.string.PushDiagError, s.tokenError))
                + "\n" + line(R.string.PushDiagLastRequest, when(s.tokenRequestAt))
                + "\n" + line(R.string.PushDiagLastResult, when(s.tokenResultAt));
        details[4] = registrationSection();
        details[5] = line(R.string.PushDiagReceivedCount, Long.toString(s.receivedCount))
                + "\n" + line(R.string.PushDiagLastReceived, when(s.receivedAt))
                + "\n" + line(R.string.PushDiagSenderTime, when(s.sentAt))
                + "\n" + line(R.string.PushDiagCallbackFinished, when(s.finishedAt))
                + "\n" + line(R.string.PushDiagLastOutcome, s.lastOutcome.isEmpty() ? text(R.string.PushDiagNever) : s.lastOutcome)
                + "\n" + line(R.string.PushDiagTimeouts, Long.toString(s.timeoutCount))
                + "\n" + line(R.string.PushDiagPriority, priority(s.priority))
                + "\n" + line(R.string.PushDiagOriginalPriority, priority(s.originalPriority))
                + "\n\n" + text(R.string.PushDiagReceiptInfo);
        details[6] = deviceSection(context);
        String[] events = PushDiagnostics.events();
        StringBuilder all = new StringBuilder(text(R.string.PushDiagEvents));
        StringBuilder recent = new StringBuilder(text(R.string.PushDiagEvents));
        if (events.length == 0) {
            all.append("\n").append(text(R.string.PushDiagNever));
            recent.append("\n").append(text(R.string.PushDiagNever));
        }
        // Newest first.
        for (int i = events.length - 1; i >= 0; i--) {
            String formatted = formatEvent(events[i]);
            all.append("\n").append(formatted);
            if (events.length - i <= SHOWN_EVENTS) recent.append("\n").append(formatted);
        }
        fullEventLog = all.toString();
        details[7] = recent.toString();
        if (adapter != null) adapter.notifyItemRangeChanged(0, DETAIL_ROWS);
    }

    private String formatEvent(String event) {
        String[] parts = event.split("\\|", 3);
        if (parts.length < 2) return event;
        String time;
        try {
            time = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date(Long.parseLong(parts[0])));
        } catch (Exception e) {
            time = parts[0];
        }
        return time + " " + parts[1] + (parts.length > 2 && !parts[2].isEmpty() ? " " + parts[2] : "");
    }

    private String configSection(Context context, PushDiagnostics.Snapshot s) {
        boolean sampleApi = BuildVars.APP_ID == SAMPLE_API_ID;
        String project = text(R.string.PushDiagUnknown);
        String sender = text(R.string.PushDiagUnknown);
        try {
            if (FirebaseApp.getApps(context).isEmpty()) FirebaseApp.initializeApp(context);
            FirebaseOptions options = FirebaseApp.getInstance().getOptions();
            if (options.getProjectId() != null) project = options.getProjectId();
            if (options.getGcmSenderId() != null) sender = options.getGcmSenderId();
        } catch (Exception ignore) {}
        String verdict;
        if (sampleApi) {
            verdict = text(R.string.PushDiagVerdictSampleApi);
        } else if (!"ready".equals(s.tokenState)) {
            verdict = text(R.string.PushDiagVerdictNoToken);
        } else if (s.receivedCount == 0) {
            verdict = text(R.string.PushDiagVerdictNoPush);
        } else {
            verdict = text(R.string.PushDiagVerdictReceiving);
        }
        return text(R.string.PushDiagVerdict) + ": " + verdict
                + "\n\n" + line(R.string.PushDiagApiId, sampleApi
                    ? SAMPLE_API_ID + " (" + text(R.string.PushDiagApiSample) + ")"
                    : text(R.string.PushDiagApiOwn))
                + "\n" + line(R.string.PushDiagFirebaseProject, project)
                + "\n" + line(R.string.PushDiagFirebaseSender, sender);
    }

    private String selfTestSection(PushDiagnostics.Snapshot s) {
        String state;
        switch (s.selfTestState) {
            case "waiting": {
                long left = Math.max(0, PushDiagnostics.SELF_TEST_WINDOW_MS - (System.currentTimeMillis() - s.selfTestStartedAt)) / 1000;
                state = LocaleController.formatString(R.string.PushDiagSelfTestWaiting, left / 60, String.format(Locale.US, "%02d", left % 60));
                break;
            }
            case "push_received":
                state = LocaleController.formatString(R.string.PushDiagSelfTestPassed, (s.selfTestPushAt - s.selfTestStartedAt) / 1000)
                        + (s.selfTestOutcome.isEmpty() ? "" : "\n" + line(R.string.PushDiagLastOutcome, s.selfTestOutcome));
                break;
            case "no_push":
                state = text(R.string.PushDiagSelfTestFailed);
                break;
            default:
                state = text(R.string.PushDiagSelfTestNotRun);
        }
        return text(R.string.PushDiagSelfTestTitle) + ": " + state
                + (s.selfTestStartedAt > 0 ? "\n" + line(R.string.PushDiagLastRequest, when(s.selfTestStartedAt)) : "");
    }

    private String registrationSection() {
        StringBuilder out = new StringBuilder();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            UserConfig config = UserConfig.getInstance(a);
            if (!config.isClientActivated()) continue;
            PushDiagnostics.Snapshot s = PushDiagnostics.snapshot(a, config.getClientUserId());
            if (out.length() > 0) out.append("\n\n");
            out.append(LocaleController.formatString(R.string.PushDiagAccount, a + 1))
                    .append(a == currentAccount ? " *" : "")
                    .append("\n").append(line(R.string.PushDiagTelegram, state(s.registrationState)))
                    .append("\n").append(line(R.string.PushDiagStoredRegistration, yes(config.registeredForPush)))
                    .append("\n").append(line(R.string.PushDiagLastResult, when(s.registrationAt)));
            if (!s.registrationError.isEmpty()) out.append("\n").append(line(R.string.PushDiagError, s.registrationError));
        }
        if (out.length() == 0) out.append(line(R.string.PushDiagLoggedIn, yes(false)));
        return out.toString();
    }

    private String deviceSection(Context context) {
        String play;
        try {
            int code = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context);
            play = code == ConnectionResult.SUCCESS ? text(R.string.PushDiagReady) : text(R.string.PushDiagFailed) + " (" + code + ")";
        } catch (Exception e) { play = text(R.string.PushDiagUnknown); }
        String restricted = text(R.string.PushDiagUnknown);
        String exempt = text(R.string.PushDiagUnknown);
        String bucket = text(R.string.PushDiagUnknown);
        String dnd = text(R.string.PushDiagUnknown);
        String blocked = text(R.string.PushDiagUnknown);
        String dataSaver = text(R.string.PushDiagUnknown);
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (Build.VERSION.SDK_INT >= 28 && am != null) restricted = yes(am.isBackgroundRestricted());
        } catch (Exception ignore) {}
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (Build.VERSION.SDK_INT >= 23 && pm != null) exempt = yes(pm.isIgnoringBatteryOptimizations(context.getPackageName()));
        } catch (Exception ignore) {}
        try {
            UsageStatsManager usm = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
            if (Build.VERSION.SDK_INT >= 28 && usm != null) bucket = standbyBucket(usm.getAppStandbyBucket());
        } catch (Exception ignore) {}
        try {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                if (Build.VERSION.SDK_INT >= 23) dnd = interruptionFilter(nm.getCurrentInterruptionFilter());
                if (Build.VERSION.SDK_INT >= 26) {
                    List<NotificationChannel> channels = nm.getNotificationChannels();
                    int off = 0;
                    for (NotificationChannel channel : channels) {
                        if (channel.getImportance() == NotificationManager.IMPORTANCE_NONE) off++;
                    }
                    blocked = off + " / " + channels.size();
                }
            }
        } catch (Exception ignore) {}
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (Build.VERSION.SDK_INT >= 24 && cm != null) {
                dataSaver = yes(cm.getRestrictBackgroundStatus() == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED);
            }
        } catch (Exception ignore) {}
        return line(R.string.PushDiagDevice, Build.MANUFACTURER + " " + Build.MODEL + ", Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")")
                + "\n" + line(R.string.PushDiagPlayServices, play)
                + "\n" + line(R.string.PushDiagNotificationsEnabled, yes(NotificationManagerCompat.from(context).areNotificationsEnabled()))
                + "\n" + line(R.string.PushDiagBlockedChannels, blocked)
                + "\n" + line(R.string.PushDiagDoNotDisturb, dnd)
                + "\n" + line(R.string.PushDiagBackgroundRestricted, restricted)
                + "\n" + line(R.string.PushDiagBatteryExempt, exempt)
                + "\n" + line(R.string.PushDiagStandbyBucket, bucket)
                + "\n" + line(R.string.PushDiagDataSaver, dataSaver);
    }

    private static String standbyBucket(int bucket) {
        switch (bucket) {
            case 5: return "exempted";
            case 10: return "active";
            case 20: return "working set";
            case 30: return "frequent";
            case 40: return "rare";
            case 45: return "restricted";
            default: return Integer.toString(bucket);
        }
    }

    private static String interruptionFilter(int filter) {
        switch (filter) {
            case 1: return "off (all allowed)";
            case 2: return "priority only";
            case 3: return "total silence";
            case 4: return "alarms only";
            default: return "unknown (" + filter + ")";
        }
    }

    private void onRowClicked(int position) {
        if (position == ROW_SELF_TEST) {
            long now = android.os.SystemClock.elapsedRealtime();
            if (lastRetry != 0 && now - lastRetry < 5000) return;
            lastRetry = now;
            PushDiagnostics.selfTestStarted();
            // A fresh token request re-registers every signed-in account with Telegram.
            if (ApplicationLoader.getPushProvider().hasServices()) {
                ApplicationLoader.getPushProvider().onRequestPushToken();
            } else {
                PushDiagnostics.noPlayServices();
            }
            updateDetails();
        } else if (position == ROW_APP_SETTINGS && getParentActivity() != null) {
            try {
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + ApplicationLoader.getApplicationId()));
                getParentActivity().startActivity(intent);
            } catch (Exception ignore) {}
        } else if (position == ROW_COPY) {
            updateDetails();
            StringBuilder summary = new StringBuilder(text(R.string.PushDiagTitle));
            // Skip the privacy note; the full event history replaces the shortened on-screen one.
            for (int i = 1; i < DETAIL_ROWS - 1; i++) summary.append("\n\n").append(details[i]);
            summary.append("\n\n").append(fullEventLog);
            AndroidUtilities.addToClipboard(summary.toString());
            Toast.makeText(getContext(), text(R.string.TextCopied), Toast.LENGTH_SHORT).show();
        } else if (position == ROW_CLEAR) {
            PushDiagnostics.clear();
            updateDetails();
        }
    }

    @Override public void onResume() {
        super.onResume();
        resumed = true;
        AndroidUtilities.cancelRunOnUIThread(refresh);
        refresh.run();
    }
    @Override public void onPause() {
        resumed = false;
        AndroidUtilities.cancelRunOnUIThread(refresh);
        super.onPause();
    }
    @Override public void onFragmentDestroy() {
        resumed = false;
        AndroidUtilities.cancelRunOnUIThread(refresh);
        super.onFragmentDestroy();
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context context;
        ListAdapter(Context context) { this.context = context; }
        @Override public int getItemCount() { return ROW_COUNT; }
        @Override public int getItemViewType(int position) { return position < DETAIL_ROWS || position == ROW_COUNT - 1 ? 0 : 1; }
        @Override public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int position = holder.getAdapterPosition();
            return position >= ROW_SELF_TEST && position <= ROW_CLEAR;
        }
        @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            View view = type == 0 ? new TextInfoPrivacyCell(context) : new TextSettingsCell(context);
            if (type == 1) view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }
        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (holder.getItemViewType() == 0) {
                ((TextInfoPrivacyCell) holder.itemView).setText(position < DETAIL_ROWS ? details[position] : text(R.string.PushDiagTestInfo));
            } else {
                int title = position == ROW_SELF_TEST ? R.string.PushDiagSelfTest : position == ROW_APP_SETTINGS ? R.string.PushDiagAppSettings
                        : position == ROW_COPY ? R.string.PushDiagCopy : R.string.PushDiagClear;
                ((TextSettingsCell) holder.itemView).setText(text(title), position < ROW_CLEAR);
            }
        }
    }
}
