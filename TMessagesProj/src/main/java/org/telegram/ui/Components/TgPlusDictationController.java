package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.tgplus.TgPlusDictationRecorder;
import org.telegram.messenger.tgplus.TgPlusVoiceTranscriber;
import org.telegram.messenger.tgplus.asr.ParakeetEngine;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.TgPlusSpeechErrors;

/**
 * Hold-to-dictate for the message field: hold the button and speak, release to put the text into the
 * field at the cursor. Sliding left cancels. Nothing is sent and no audio is kept.
 */
public class TgPlusDictationController {

    public interface Host {
        Activity activity();

        BaseFragment fragment();

        Theme.ResourcesProvider resourcesProvider();

        /** The container that holds the message field; the strip is laid over it. */
        FrameLayout stripContainer();

        /** Hides (true) or restores (false) the message field and emoji button under the strip. */
        void setFieldCovered(boolean covered);

        /** Puts text into the field at the cursor, replacing a selection. */
        void insertDictatedText(String text);
    }

    private static final int MIN_SAMPLES = TgPlusDictationRecorder.SAMPLE_RATE / 2;
    private static final int PERMISSION_REQUEST = 3; // the app's existing microphone request

    private enum State {IDLE, RECORDING, TRANSCRIBING}

    private final Host host;
    private final View button;
    private final TgPlusDictationRecorder recorder = new TgPlusDictationRecorder();
    private TgPlusDictationStrip strip;
    private State state = State.IDLE;
    private float downX;
    private boolean cancelled;
    private boolean destroyed;

    public TgPlusDictationController(Host host, View button) {
        this.host = host;
        this.button = button;
    }

    public boolean isBusy() {
        return state != State.IDLE;
    }

    public boolean onTouch(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (state != State.IDLE || button.getAlpha() < 0.5f) return false;
                downX = event.getRawX();
                cancelled = false;
                if (button.getParent() != null) button.getParent().requestDisallowInterceptTouchEvent(true);
                start();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (state == State.RECORDING && !cancelled) {
                    float moved = downX - event.getRawX();
                    float progress = moved / dp(110);
                    if (strip != null) strip.setSlide(progress);
                    if (progress >= 1f) {
                        cancelled = true;
                        button.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                        cancelRecording();
                    }
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (state == State.RECORDING) finishRecording();
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (state == State.RECORDING) cancelRecording();
                return true;
            default:
                return true;
        }
    }

    private void start() {
        Activity activity = host.activity();
        if (activity == null) return;
        if (!ParakeetEngine.getInstance().getStatus().isReady()) {
            showError("MODEL_MISSING");
            return;
        }
        if (Build.VERSION.SDK_INT >= 23 && activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSION_REQUEST);
            return;
        }
        String error = recorder.start(new TgPlusDictationRecorder.Listener() {
            @Override
            public void onLevel(float level) {
                if (strip != null && state == State.RECORDING) strip.addLevel(level);
            }

            @Override
            public void onLimit() {
                if (state == State.RECORDING && !cancelled) finishRecording();
            }
        });
        if (error != null) {
            showError(error);
            return;
        }
        state = State.RECORDING;
        button.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        showStrip();
    }

    private void showStrip() {
        FrameLayout container = host.stripContainer();
        if (strip == null) {
            strip = new TgPlusDictationStrip(container.getContext(), host.resourcesProvider());
        }
        strip.startRecording();
        if (strip.getParent() == null) {
            // Start where the dictation button ends, and draw under the button so it stays visible.
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            params.leftMargin = button.getRight();
            container.addView(strip, Math.max(0, container.indexOfChild(button)), params);
        }
        host.setFieldCovered(true);
    }

    private void hideStrip() {
        if (strip != null && strip.getParent() instanceof ViewGroup) {
            ((ViewGroup) strip.getParent()).removeView(strip);
        }
        host.setFieldCovered(false);
    }

    private void cancelRecording() {
        recorder.cancel();
        state = State.IDLE;
        hideStrip();
    }

    private void finishRecording() {
        final short[] pcm = recorder.stop();
        if (pcm.length < MIN_SAMPLES) {
            state = State.IDLE;
            hideStrip();
            BaseFragment fragment = host.fragment();
            if (fragment != null) {
                BulletinFactory.of(fragment).createSimpleBulletin(R.raw.chats_infotip, LocaleController.getString(R.string.TgPlusDictationHold)).show();
            }
            return;
        }
        state = State.TRANSCRIBING;
        if (strip != null) strip.showTranscribing();
        TgPlusVoiceTranscriber.transcribeDictation(pcm, pcm.length, (text, error) -> {
            state = State.IDLE;
            hideStrip();
            if (destroyed) return;
            if (error != null || text == null) {
                showError(error);
            } else if (text.trim().isEmpty()) {
                BaseFragment fragment = host.fragment();
                if (fragment != null) {
                    BulletinFactory.of(fragment).createSimpleBulletin(R.raw.chats_infotip, LocaleController.getString(R.string.TgPlusDictationNothing)).show();
                }
            } else {
                host.insertDictatedText(text.trim());
            }
        });
    }

    private void showError(String code) {
        BaseFragment fragment = host.fragment();
        if (fragment != null) {
            BulletinFactory.of(fragment).createErrorBulletin(TgPlusSpeechErrors.text(code)).show();
        }
    }

    /** Stops everything when the chat goes away. */
    public void destroy() {
        destroyed = true;
        if (state == State.RECORDING) {
            recorder.cancel();
            state = State.IDLE;
            hideStrip();
        }
    }
}
