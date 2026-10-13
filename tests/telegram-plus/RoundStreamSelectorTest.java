import org.telegram.messenger.camera.RoundStreamSelector;
public class RoundStreamSelectorTest {
    private static void check(int expected, int[][] sizes, int edge, float aspect) {
        int actual = RoundStreamSelector.choose(sizes, edge, aspect);
        if (actual != expected) throw new AssertionError("Expected " + expected + " but got " + actual);
    }
    public static void main(String[] args) {
        check(1, new int[][]{{640,360},{640,480},{1280,720}}, 360, 4f/3);
        check(0, new int[][]{{640,480},{1280,960}}, 384, 4f/3);
        check(1, new int[][]{{640,480},{1280,960}}, 720, 4f/3);
        check(0, new int[][]{{480,640},{720,1280}}, 384, 3f/4);
        check(1, new int[][]{{640,480},{1280,720}}, 384, 16f/9);
        check(-1, new int[][]{{1920,1080},{320,240},null,{-1,480}}, 384, 4f/3);
        check(-1, null, 384, 4f/3);
        check(-1, new int[][]{}, 384, 4f/3);
        check(0, new int[][]{{640,480},{1280,720}}, 384, Float.NaN);
        check(0, new int[][]{{640,480},{1280,720}}, 384, Float.POSITIVE_INFINITY);
        System.out.println("10 round stream checks passed");
    }
}
