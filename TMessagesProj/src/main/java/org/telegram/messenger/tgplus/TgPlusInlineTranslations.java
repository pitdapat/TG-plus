package org.telegram.messenger.tgplus;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.CharacterStyle;
import android.text.style.LineBackgroundSpan;
import android.text.style.LineHeightSpan;
import android.text.style.UpdateAppearance;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;

/**
 * Gemini translations shown inside the message bubble, under the original text:
 *
 * <pre>
 * Original text
 * ─────────────
 * Translated text
 * </pre>
 *
 * Translations are requested only when the user taps Translate, and are stored on the
 * device (private prefs, excluded from backup) so they are still shown after reopening
 * the chat. A stored translation is dropped if the message is edited.
 */
public final class TgPlusInlineTranslations {

    private static final String PREFS = "tgplus_inline_translations";
    private static final int MAX_ENTRIES = 3000;
    private static final int EVICT_COUNT = 500;

    /** Placeholder for the divider line; drawn by {@link DividerSpan}. */
    public static final char DIVIDER_CHAR = '\u200B';
    public static final String SEPARATOR = "\n" + DIVIDER_CHAR + "\n";

    private static final HashSet<String> pending = new HashSet<>();

    private TgPlusInlineTranslations() {}

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Inline translation is offered only while Gemini is the active provider. */
    public static boolean isAvailable() {
        return TgPlusSettings.isGeminiTranslationActive();
    }

    private static String key(MessageObject m) {
        if (m == null || m.messageOwner == null || m.getId() <= 0 || m.scheduled) return null;
        return m.currentAccount + "_" + m.getDialogId() + "_" + m.getId();
    }

    /** True if the whole text of this message can be translated inline. */
    public static boolean canTranslate(MessageObject m) {
        if (key(m) == null) return false;
        if (m.isSponsored() || m.isPoll() || m.type == MessageObject.TYPE_ARTICLE || m.isVoiceTranscriptionOpen() || m.isRestrictedMessage) {
            return false;
        }
        String text = m.messageOwner.message;
        return !TextUtils.isEmpty(text) && !TextUtils.isEmpty(text.trim()) && !Emoji.fullyConsistsOfEmojis(text);
    }

    /** In an album the caption belongs to one message; find it by id. */
    public static MessageObject findTarget(MessageObject message, MessageObject.GroupedMessages group, int messageId) {
        if (group != null && group.messages != null) {
            for (MessageObject m : group.messages) {
                if (m != null && m.getId() == messageId) return canTranslate(m) ? m : null;
            }
        }
        return canTranslate(message) ? message : null;
    }

    public static boolean isPending(MessageObject m) {
        String key = key(m);
        return key != null && pending.contains(key);
    }

    public static boolean isShown(MessageObject m) {
        return getDisplay(m) != null;
    }

    /** Text to show under the original: the translation, "Translating…", or null for none. */
    public static String getDisplay(MessageObject m) {
        String key = key(m);
        if (key == null) return null;
        if (pending.contains(key)) return LocaleController.getString(R.string.TgPlusTranslating);
        String stored = prefs().getString(key, null);
        if (stored == null) return null;
        // editDate|toLang|savedAt|text
        String[] parts = stored.split("\\|", 4);
        if (parts.length != 4) {
            prefs().edit().remove(key).apply();
            return null;
        }
        int editDate = Utilities.parseInt(parts[0]);
        if (editDate != m.messageOwner.edit_date) {
            // The message was edited after it was translated.
            prefs().edit().remove(key).apply();
            return null;
        }
        return TextUtils.isEmpty(parts[3]) ? null : parts[3];
    }

    public static void translate(MessageObject m, String toLanguage, Utilities.Callback<String> onError) {
        String key = key(m);
        if (key == null || pending.contains(key)) return;
        String to = toLanguage == null ? "en" : toLanguage.split("_")[0];
        if ("nb".equals(to)) to = "no";
        final String target = to;
        final int editDate = m.messageOwner.edit_date;
        pending.add(key);
        TgPlusUiSpeed.translateTapped(key);
        notifyChanged(m);
        GeminiTranslator.translate(m.messageOwner.message, m.messageOwner.originalLanguage, target, "inline", result -> {
            pending.remove(key);
            if (result.ok()) {
                TgPlusUiSpeed.translateResult(key, result.text.length());
                save(key, editDate, target, result.text);
            } else {
                TgPlusUiSpeed.translateEnded(key);
                if (onError != null) onError.run(result.error);
            }
            notifyChanged(m);
        });
    }

    /**
     * Called by the chat after it handled a message-translated notification that began at
     * {@code handlerStartedAt}; completes the tap-to-visible measurement (UI speed diagnostics).
     */
    public static void onApplied(MessageObject m, long handlerStartedAt) {
        String key = key(m);
        if (key != null) TgPlusUiSpeed.translationApplied(key, handlerStartedAt);
    }

    public static void hide(MessageObject m) {
        String key = key(m);
        if (key == null) return;
        pending.remove(key);
        TgPlusUiSpeed.translateEnded(key);
        prefs().edit().remove(key).apply();
        TgPlusDiagnostics.log(DiagnosticsLog.event("translation", "inline_hide").ok().build());
        notifyChanged(m);
    }

    private static void save(String key, int editDate, String toLanguage, String text) {
        SharedPreferences p = prefs();
        SharedPreferences.Editor editor = p.edit();
        Map<String, ?> all = p.getAll();
        if (all.size() >= MAX_ENTRIES) {
            // Drop the oldest translations.
            ArrayList<long[]> times = new ArrayList<>();
            ArrayList<String> keys = new ArrayList<>();
            for (Map.Entry<String, ?> e : all.entrySet()) {
                String[] parts = String.valueOf(e.getValue()).split("\\|", 4);
                long savedAt = parts.length == 4 ? Utilities.parseLong(parts[2]) : 0;
                times.add(new long[]{savedAt, keys.size()});
                keys.add(e.getKey());
            }
            Collections.sort(times, (a, b) -> Long.compare(a[0], b[0]));
            for (int i = 0; i < Math.min(EVICT_COUNT, times.size()); i++) {
                editor.remove(keys.get((int) times.get(i)[1]));
            }
        }
        editor.putString(key, editDate + "|" + toLanguage + "|" + System.currentTimeMillis() + "|" + text).apply();
    }

    /** For diagnostics: how many translations are stored (no content). */
    public static int storedCount() {
        return prefs().getAll().size();
    }

    private static void notifyChanged(MessageObject m) {
        NotificationCenter.getInstance(m.currentAccount).postNotificationName(NotificationCenter.messageTranslated, m);
    }

    /** Original + separator + translation, for {@link MessageObject} to lay out. */
    public static String compose(String original, String display) {
        return original + SEPARATOR + display;
    }

    /** Adds the divider line and the lighter translation style. Safe to call on any text. */
    public static CharSequence decorate(CharSequence text, int originalLength) {
        if (text == null || originalLength < 0) return text;
        int dividerAt = originalLength + 1;
        int translationStart = originalLength + SEPARATOR.length();
        if (text.length() <= translationStart || text.charAt(dividerAt) != DIVIDER_CHAR) return text;
        Spannable spannable = text instanceof Spannable ? (Spannable) text : new SpannableString(text);
        // Paragraph spans must cover the whole paragraph: the placeholder and its newline.
        spannable.setSpan(new DividerSpan(), dividerAt, dividerAt + 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        spannable.setSpan(new TranslationStyleSpan(), translationStart, spannable.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return spannable;
    }

    /** Thin line between the original and the translation. */
    public static final class DividerSpan implements LineBackgroundSpan, LineHeightSpan {
        private float cachedLayoutWidth = -1;
        private CharSequence cachedText;
        private float cachedLineWidth;

        @Override
        public void chooseHeight(CharSequence text, int start, int end, int spanstartv, int lineHeight, Paint.FontMetricsInt fm) {
            int height = AndroidUtilities.dp(13);
            fm.top = fm.ascent = -height;
            fm.bottom = fm.descent = 0;
        }

        @Override
        public void drawBackground(Canvas canvas, Paint paint, int left, int right, int top, int baseline, int bottom, CharSequence text, int start, int end, int lineNumber) {
            float available = right - left;
            if (available <= 0) return;
            float width = lineWidth(paint, text, available);
            int oldColor = paint.getColor();
            Paint.Style oldStyle = paint.getStyle();
            paint.setStyle(Paint.Style.FILL);
            paint.setAlpha((int) (paint.getAlpha() * 0.25f));
            float thickness = Math.max(1, AndroidUtilities.dp(1));
            float y = (top + bottom) / 2f - thickness / 2f;
            canvas.drawRect(left, y, left + width, y + thickness, paint);
            paint.setColor(oldColor);
            paint.setStyle(oldStyle);
        }

        /** As wide as the widest line of the message, capped at the layout width. */
        private float lineWidth(Paint paint, CharSequence text, float available) {
            if (text == cachedText && available == cachedLayoutWidth) return cachedLineWidth;
            float widest = AndroidUtilities.dp(48);
            String s = text.toString();
            int from = 0;
            while (from <= s.length()) {
                int to = s.indexOf('\n', from);
                if (to < 0) to = s.length();
                if (to > from) {
                    widest = Math.max(widest, paint.measureText(s, from, to));
                    if (widest >= available) break;
                }
                from = to + 1;
            }
            cachedText = text;
            cachedLayoutWidth = available;
            cachedLineWidth = Math.min(widest, available);
            return cachedLineWidth;
        }
    }

    /** Draws the translation slightly lighter than the original. */
    public static final class TranslationStyleSpan extends CharacterStyle implements UpdateAppearance {
        @Override
        public void updateDrawState(TextPaint tp) {
            tp.setAlpha((int) (tp.getAlpha() * 0.78f));
        }
    }
}
