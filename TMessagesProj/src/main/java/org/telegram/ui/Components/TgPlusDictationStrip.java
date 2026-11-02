package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.text.TextPaint;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;

import java.util.Locale;

/** Replaces the message field while dictating: red dot, timer, live waveform and the cancel hint. */
public class TgPlusDictationStrip extends View {

    private static final int BARS = 48;

    private final Theme.ResourcesProvider resourcesProvider;
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint timePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint hintPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bar = new RectF();
    private final float[] levels = new float[BARS];
    private int levelIndex;
    private boolean transcribing;
    private boolean live;
    private long startTime;
    private float slide; // 0 .. 1, how far the finger has moved toward cancel

    public TgPlusDictationStrip(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        timePaint.setTextSize(dp(16));
        timePaint.setTypeface(AndroidUtilities.bold());
        hintPaint.setTextSize(dp(15));
        setWillNotDraw(false);
    }

    public void startRecording() {
        transcribing = false;
        live = false;
        java.util.Arrays.fill(levels, 0f);
        levelIndex = 0;
        slide = 0f;
        startTime = SystemClock.elapsedRealtime();
        invalidate();
    }

    /** The microphone is delivering audio: the timer starts and the dot turns red. */
    public void goLive() {
        live = true;
        startTime = SystemClock.elapsedRealtime();
        invalidate();
    }

    public void showTranscribing() {
        transcribing = true;
        invalidate();
    }

    public void addLevel(float level) {
        levels[levelIndex % BARS] = level;
        levelIndex++;
        invalidate();
    }

    public void setSlide(float value) {
        slide = Math.max(0f, Math.min(1f, value));
        invalidate();
    }

    private int color(int key) {
        return Theme.getColor(key, resourcesProvider);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        float cy = h / 2f;
        hintPaint.setColor(color(Theme.key_chat_messagePanelHint));
        if (transcribing) {
            String text = LocaleController.getString(R.string.TgPlusDictationTranscribing);
            canvas.drawText(text, dp(16), cy + dp(5), hintPaint);
            // three dots that light up in turn
            long phase = (SystemClock.elapsedRealtime() / 300) % 3;
            float x = dp(16) + hintPaint.measureText(text) + dp(14);
            for (int i = 0; i < 3; i++) {
                dotPaint.setColor(color(Theme.key_chat_messagePanelHint));
                dotPaint.setAlpha(i == phase ? 255 : 90);
                canvas.drawCircle(x + i * dp(9), cy, dp(2.5f), dotPaint);
            }
            postInvalidateDelayed(150);
            return;
        }
        long elapsed = live ? SystemClock.elapsedRealtime() - startTime : 0;
        // dot: grey while the microphone starts, then red and pulsing once a second
        float pulse = live ? 0.6f + 0.4f * (float) Math.abs(Math.sin(elapsed / 1000.0 * Math.PI)) : 1f;
        dotPaint.setColor(color(live ? Theme.key_chat_recordedVoiceDot : Theme.key_chat_messagePanelHint));
        dotPaint.setAlpha((int) (255 * pulse));
        canvas.drawCircle(dp(20), cy, dp(5), dotPaint);
        long seconds = elapsed / 1000;
        String time = String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60);
        timePaint.setColor(color(Theme.key_chat_recordTime));
        canvas.drawText(time, dp(34), cy + dp(5.5f), timePaint);

        // hint on the right, fading as the finger slides toward cancel
        String hint = "‹ " + LocaleController.getString(R.string.TgPlusDictationSlideToCancel);
        float hintWidth = hintPaint.measureText(hint);
        hintPaint.setAlpha((int) (255 * (1f - slide)));
        float hintX = w - dp(12) - hintWidth - slide * dp(40);
        canvas.drawText(hint, hintX, cy + dp(5), hintPaint);

        // waveform between the timer and the hint
        float left = dp(34) + timePaint.measureText("0:00") + dp(14);
        float right = hintX - dp(12);
        float step = dp(4);
        int count = (int) Math.max(0, Math.min(BARS, (right - left) / step));
        barPaint.setColor(color(Theme.key_chat_messagePanelHint));
        barPaint.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 0; i < count; i++) {
            int index = levelIndex - count + i;
            float level = index >= 0 ? levels[((index % BARS) + BARS) % BARS] : 0f;
            float half = dp(1.5f) + level * dp(14);
            float x = left + i * step;
            bar.set(x, cy - half, x + dp(2), cy + half);
            canvas.drawRoundRect(bar, dp(1), dp(1), barPaint);
        }
        postInvalidateDelayed(50);
    }
}
