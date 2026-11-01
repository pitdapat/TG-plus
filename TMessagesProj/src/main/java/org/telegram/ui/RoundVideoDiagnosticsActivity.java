package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.utils.recorder.round.RoundVideoCameraProbe;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Runs the round-video camera diagnostic and lets the user share the report. */
public class RoundVideoDiagnosticsActivity extends BaseFragment {

    private static final int CAMERA_PERMISSION_REQUEST = 4721;

    private TextView runButton;
    private TextView shareButton;
    private TextView copyButton;
    private TextView statusView;
    private TextView reportView;
    private RoundVideoCameraProbe probe;
    private String report;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.RoundVideoDiagnostics));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
            }
        });

        ScrollView scrollView = new ScrollView(context);
        scrollView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(16), dp(16), dp(24));
        scrollView.addView(content);

        TextView instructions = new TextView(context);
        instructions.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        instructions.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        instructions.setText(LocaleController.getString(R.string.RoundVideoDiagnosticsInstructions));
        content.addView(instructions, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        runButton = createButton(context, LocaleController.getString(R.string.RoundVideoDiagnosticsRun));
        runButton.setOnClickListener(v -> startProbe());
        content.addView(runButton, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, 48, 0, 16, 0, 0));

        statusView = new TextView(context);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        statusView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        statusView.setGravity(Gravity.CENTER_HORIZONTAL);
        content.addView(statusView, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 0));

        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        shareButton = createButton(context, LocaleController.getString(R.string.RoundVideoDiagnosticsShare));
        shareButton.setOnClickListener(v -> shareReport());
        copyButton = createButton(context, LocaleController.getString(R.string.RoundVideoDiagnosticsCopy));
        copyButton.setOnClickListener(v -> {
            if (report != null && AndroidUtilities.addToClipboard(report)) {
                Toast.makeText(getParentActivity(),
                        LocaleController.getString(R.string.RoundVideoDiagnosticsCopied),
                        Toast.LENGTH_SHORT).show();
            }
        });
        actions.addView(shareButton, LayoutHelper.createLinear(0, 48, 1f, 0, 0, 6, 0));
        actions.addView(copyButton, LayoutHelper.createLinear(0, 48, 1f, 6, 0, 0, 0));
        content.addView(actions, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 0));
        setActionsEnabled(false);

        reportView = new TextView(context);
        reportView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        reportView.setTypeface(Typeface.MONOSPACE);
        reportView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        reportView.setTextIsSelectable(true);
        content.addView(reportView, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 16, 0, 0));

        fragmentView = scrollView;
        return fragmentView;
    }

    private TextView createButton(Context context, String text) {
        TextView button = new TextView(context);
        button.setText(text);
        button.setGravity(Gravity.CENTER);
        button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        button.setTypeface(AndroidUtilities.bold());
        button.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText));
        button.setBackground(Theme.createSimpleSelectorRoundRectDrawable(
                dp(8),
                Theme.getColor(Theme.key_featuredStickers_addButton),
                Theme.getColor(Theme.key_featuredStickers_addButtonPressed)
        ));
        return button;
    }

    private void setActionsEnabled(boolean enabled) {
        shareButton.setEnabled(enabled);
        copyButton.setEnabled(enabled);
        shareButton.setAlpha(enabled ? 1f : 0.4f);
        copyButton.setAlpha(enabled ? 1f : 0.4f);
    }

    private void startProbe() {
        Activity activity = getParentActivity();
        if (activity == null || probe != null) return;
        if (Build.VERSION.SDK_INT >= 23
                && activity.checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
            return;
        }
        runButton.setEnabled(false);
        runButton.setAlpha(0.4f);
        setActionsEnabled(false);
        reportView.setText("");
        statusView.setText(LocaleController.getString(R.string.RoundVideoDiagnosticsRunning));
        activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        probe = new RoundVideoCameraProbe(activity, new RoundVideoCameraProbe.Listener() {
            @Override
            public void onProgress(String status) {
                if (statusView != null) statusView.setText(status);
            }

            @Override
            public void onFinished(String text) {
                probe = null;
                report = text;
                Activity a = getParentActivity();
                if (a != null) a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                if (fragmentView == null) return;
                runButton.setEnabled(true);
                runButton.setAlpha(1f);
                setActionsEnabled(true);
                statusView.setText(LocaleController.getString(R.string.RoundVideoDiagnosticsDone));
                reportView.setText(text);
            }
        });
        probe.start();
    }

    @Override
    public void onRequestPermissionsResultFragment(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode != CAMERA_PERMISSION_REQUEST) return;
        if (grantResults != null && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startProbe();
        } else if (statusView != null) {
            statusView.setText(LocaleController.getString(R.string.RoundVideoDiagnosticsNoPermission));
        }
    }

    private void shareReport() {
        Activity activity = getParentActivity();
        if (activity == null || TextUtils.isEmpty(report)) return;
        try {
            File dir = new File(ApplicationLoader.getFilesDirFixed(), "cache");
            if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("cannot create " + dir);
            File file = new File(dir, "round_video_camera_report.txt");
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(report.getBytes(StandardCharsets.UTF_8));
            }
            Uri uri = FileProvider.getUriForFile(activity, ApplicationLoader.getApplicationId() + ".provider", file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.putExtra(Intent.EXTRA_SUBJECT, "Round video camera report");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(Intent.createChooser(intent,
                    LocaleController.getString(R.string.RoundVideoDiagnosticsShare)));
        } catch (Exception e) {
            FileLog.e(e);
            Toast.makeText(activity, String.valueOf(e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onFragmentDestroy() {
        if (probe != null) probe.cancel();
        Activity activity = getParentActivity();
        if (activity != null) activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onFragmentDestroy();
    }
}
