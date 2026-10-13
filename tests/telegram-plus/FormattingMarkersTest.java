import org.telegram.messenger.utils.FormattingMarkers;
public class FormattingMarkersTest {
    static void check(String expected, String text, int... styles) {
        String actual = FormattingMarkers.render(text, styles);
        if (!expected.equals(actual)) throw new AssertionError(actual + " != " + expected);
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
        check("**a\nb**", "a\nb", 1,1,1);
        check("", "", new int[0]);
        check("**a**b**c**", "abc", 1,0,1);
        boolean rejected = false;
        try { FormattingMarkers.render("x", new int[0]); }
        catch (IllegalArgumentException expected) { rejected = true; }
        if (!rejected) throw new AssertionError("Length mismatch accepted");
        System.out.println("12 formatting marker checks passed");
    }
}
