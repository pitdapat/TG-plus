package org.telegram.ui;

import android.app.ActivityManager;
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
import java.util.Date;

/** Visible only on request; no background polling or analytics. */
public class PushDiagnosticsActivity extends BaseFragment {
    private final String[] details = new String[5];
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
        details[1] = line(R.string.PushDiagToken, state(s.tokenState))
                + (s.tokenError.isEmpty() ? "" : "\n" + line(R.string.PushDiagError, s.tokenError))
                + "\n" + line(R.string.PushDiagLastRequest, when(s.tokenRequestAt))
                + "\n" + line(R.string.PushDiagLastResult, when(s.tokenResultAt));
        details[2] = line(R.string.PushDiagTelegram, state(s.registrationState))
                + "\n" + line(R.string.PushDiagLoggedIn, yes(account.isClientActivated()))
                + "\n" + line(R.string.PushDiagStoredRegistration, yes(account.registeredForPush))
                + "\n" + line(R.string.PushDiagLastResult, when(s.registrationAt))
                + (s.registrationError.isEmpty() ? "" : "\n" + line(R.string.PushDiagError, s.registrationError));
        details[3] = line(R.string.PushDiagReceivedCount, Long.toString(s.receivedCount))
                + "\n" + line(R.string.PushDiagLastReceived, when(s.receivedAt))
                + "\n" + line(R.string.PushDiagSenderTime, when(s.sentAt))
                + "\n" + line(R.string.PushDiagCallbackFinished, when(s.finishedAt))
                + "\n" + line(R.string.PushDiagPriority, priority(s.priority))
                + "\n" + line(R.string.PushDiagOriginalPriority, priority(s.originalPriority))
                + "\n\n" + text(R.string.PushDiagReceiptInfo);
        String play;
        try {
            int code = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context);
            play = code == ConnectionResult.SUCCESS ? text(R.string.PushDiagReady) : text(R.string.PushDiagFailed) + " (" + code + ")";
        } catch (Exception e) { play = text(R.string.PushDiagUnknown); }
        String restricted = text(R.string.PushDiagUnknown);
        String exempt = text(R.string.PushDiagUnknown);
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (Build.VERSION.SDK_INT >= 28 && am != null) restricted = yes(am.isBackgroundRestricted());
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (Build.VERSION.SDK_INT >= 23 && pm != null) exempt = yes(pm.isIgnoringBatteryOptimizations(context.getPackageName()));
        } catch (Exception ignore) {}
        details[4] = line(R.string.PushDiagPlayServices, play)
                + "\n" + line(R.string.PushDiagNotificationsEnabled, yes(NotificationManagerCompat.from(context).areNotificationsEnabled()))
                + "\n" + line(R.string.PushDiagBackgroundRestricted, restricted)
                + "\n" + line(R.string.PushDiagBatteryExempt, exempt);
        if (adapter != null) adapter.notifyItemRangeChanged(0, details.length);
    }

    private void onRowClicked(int position) {
        if (position == 5) {
            long now = android.os.SystemClock.elapsedRealtime();
            if (lastRetry != 0 && now - lastRetry < 5000) return;
            lastRetry = now;
            if (ApplicationLoader.getPushProvider().hasServices()) {
                ApplicationLoader.getPushProvider().onRequestPushToken();
            } else {
                PushDiagnostics.noPlayServices();
            }
            updateDetails();
        } else if (position == 6 && getParentActivity() != null) {
            try {
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + ApplicationLoader.getApplicationId()));
                getParentActivity().startActivity(intent);
            } catch (Exception ignore) {}
        } else if (position == 7) {
            updateDetails();
            StringBuilder summary = new StringBuilder(text(R.string.PushDiagTitle));
            for (String detail : details) summary.append("\n\n").append(detail);
            AndroidUtilities.addToClipboard(summary.toString());
            Toast.makeText(getContext(), text(R.string.TextCopied), Toast.LENGTH_SHORT).show();
        } else if (position == 8) {
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
        @Override public int getItemCount() { return 10; }
        @Override public int getItemViewType(int position) { return position < 5 || position == 9 ? 0 : 1; }
        @Override public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int position = holder.getAdapterPosition();
            return position >= 5 && position <= 8;
        }
        @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            View view = type == 0 ? new TextInfoPrivacyCell(context) : new TextSettingsCell(context);
            if (type == 1) view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }
        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (holder.getItemViewType() == 0) {
                ((TextInfoPrivacyCell) holder.itemView).setText(position < 5 ? details[position] : text(R.string.PushDiagTestInfo));
            } else {
                int title = position == 5 ? R.string.PushDiagRetry : position == 6 ? R.string.PushDiagAppSettings
                        : position == 7 ? R.string.PushDiagCopy : R.string.PushDiagClear;
                ((TextSettingsCell) holder.itemView).setText(text(title), position < 8);
            }
        }
    }
}
