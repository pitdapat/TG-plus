package org.telegram.messenger.utils;

import android.graphics.Typeface;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;
import android.text.style.StrikethroughSpan;
import org.telegram.ui.Components.TextStyleSpan;

/** Convert supplied rich-text styles to visible markers; never infer missing styles. */
public final class ClipboardFormatting {
    private ClipboardFormatting() {}

    public static CharSequence fromHtml(CharSequence plain, String html) {
        CharSequence parsed = html == null ? null : CopyUtilities.fromHTML(html);
        if (parsed == null) return plain;
        CharSequence marked = withMarkers(parsed);
        return marked == parsed ? plain : marked;
    }

    public static CharSequence withMarkers(CharSequence text) {
        if (!(text instanceof Spanned)) return text;
        Spanned rich = (Spanned) text;
        int[] styles = new int[text.length()];
        boolean formatted = false;
        for (Object span : rich.getSpans(0, rich.length(), Object.class)) {
            int mask = 0;
            if (span instanceof TextStyleSpan) {
                int flags = ((TextStyleSpan) span).getTextStyleRun().flags;
                if ((flags & TextStyleSpan.FLAG_STYLE_BOLD) != 0) mask |= FormattingMarkers.BOLD;
                if ((flags & TextStyleSpan.FLAG_STYLE_ITALIC) != 0) mask |= FormattingMarkers.ITALIC;
                if ((flags & TextStyleSpan.FLAG_STYLE_UNDERLINE) != 0) mask |= FormattingMarkers.UNDERLINE;
                if ((flags & TextStyleSpan.FLAG_STYLE_STRIKE) != 0) mask |= FormattingMarkers.STRIKE;
            } else if (span instanceof StyleSpan) {
                int style = ((StyleSpan) span).getStyle();
                if ((style & Typeface.BOLD) != 0) mask |= FormattingMarkers.BOLD;
                if ((style & Typeface.ITALIC) != 0) mask |= FormattingMarkers.ITALIC;
            } else if (span instanceof UnderlineSpan) {
                mask = FormattingMarkers.UNDERLINE;
            } else if (span instanceof StrikethroughSpan) {
                mask = FormattingMarkers.STRIKE;
            }
            if (mask == 0) continue;
            int start = Math.max(0, rich.getSpanStart(span));
            int end = Math.min(styles.length, rich.getSpanEnd(span));
            for (int i = start; i < end; i++) styles[i] |= mask;
            formatted |= start < end;
        }
        return formatted ? FormattingMarkers.render(text.toString(), styles) : text;
    }
}
