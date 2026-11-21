import org.telegram.messenger.utils.FormattingMarkers;
public class FormattingMarkersTest {
    static int checks;

    static void check(String expected, String text, int... styles) {
        String actual = FormattingMarkers.render(text, styles);
        if (!expected.equals(actual)) throw new AssertionError(actual + " != " + expected);
        checks++;
    }

    /** Styles for {@code text} given as (start, end, mask) triples. */
    static int[] styles(String text, int... ranges) {
        int[] result = new int[text.length()];
        for (int r = 0; r < ranges.length; r += 3) {
            for (int i = ranges[r]; i < ranges[r + 1]; i++) result[i] |= ranges[r + 2];
        }
        return result;
    }

    static void checkRanges(String expected, String text, int... ranges) {
        check(expected, text, styles(text, ranges));
    }

    public static void main(String[] args) {
        check("**hello**", "hello", 1,1,1,1,1);
        check("<u>hello</u>", "hello", 4,4,4,4,4);
        check("a **b** c", "a b c", 0,0,1,0,0);
        check("***hi***", "hi", 3,3);
        check("**a<u>b</u>c**", "abc", 1,5,1);
        check("~~gone~~", "gone", 8,8,8,8);
        check("plain **already**", "plain **already**", new int[17]);
        check("**😀**", "😀", 1,1);
        check("", "", new int[0]);
        check("**a**b**c**", "abc", 1,0,1);

        // No style crosses a line break.
        check("**a**\n**b**", "a\nb", 1,1,1);

        // Bold with italic inside, at each position.
        check("**ab *cd* ef**", "ab cd ef", 1,1,1,3,3,1,1,1);
        check("***ab* cd ef**", "ab cd ef", 3,3,1,1,1,1,1,1);
        check("**ab cd *ef***", "ab cd ef", 1,1,1,1,1,1,3,3);
        // Italic with bold inside keeps italic outermost instead of closing and reopening.
        check("*ab **cd** ef*", "ab cd ef", 2,2,2,3,3,2,2,2);
        check("***ab** cd ef*", "ab cd ef", 3,3,2,2,2,2,2,2);

        // Markers wrap words, not the spaces around them.
        check("a **b** c", "a b c", 0,1,1,1,0);
        // A plain space between two bold words looks the same as a bold one: one pair.
        check("**a b**", "a b", 1,0,1);
        checkRanges("**a b** c", "a b c", 0, 4, 1);
        // Underline and strikethrough are visible on spaces, so they are not added there.
        check("<u>a</u> <u>b</u>", "a b", 4,0,4);

        // Unstyled emoji inside a styled run do not split it.
        String pray = "can\uD83D\uDE4F\uD83C\uDFFC)";
        checkRanges("***can\uD83D\uDE4F\uD83C\uDFFC)***", pray, 0, 3, 3, 7, 8, 3);
        checkRanges("**a 👍 b**", "a 👍 b", 0, 1, 1, 5, 6, 1);
        // An emoji at the edge of a run stays outside it.
        checkRanges("**ok**👍", "ok👍", 0, 2, 1);

        // The reported message: bold lines, one bold-italic line with an unstyled emoji.
        String line1 = "Lmk your attendance accordingly:";
        String line2 = "Emerge Service at 1215pm";
        String line3 = "(There will be pre-service PM so pls arrange to come as early as you can"
                + "\uD83D\uDE4F\uD83C\uDFFC)";
        String line4 = "Sun CGM @ 1130am";
        String message = line1 + "\n\n" + line2 + "\n" + line3 + "\n\n\n" + line4;
        int l2 = line1.length() + 2;
        int l3 = l2 + line2.length() + 1;
        int emoji = l3 + line3.length() - 5;
        int l4 = l3 + line3.length() + 3;
        checkRanges(
                line1 + "\n\n**" + line2 + "**\n***" + line3 + "***\n\n\n**" + line4 + "**",
                message,
                l2, l3 - 1, 1,
                l3, emoji, 3,
                emoji + 4, l3 + line3.length(), 3,
                l4, message.length(), 1
        );

        boolean rejected = false;
        try { FormattingMarkers.render("x", new int[0]); }
        catch (IllegalArgumentException expected) { rejected = true; }
        if (!rejected) throw new AssertionError("Length mismatch accepted");
        checks++;
        System.out.println(checks + " formatting marker checks passed");
    }
}
