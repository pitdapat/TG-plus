import org.telegram.messenger.camera.RoundFpsSelector;
public class RoundFpsSelectorTest {
    private static void check(int expected, int[][] ranges) {
        if (RoundFpsSelector.choose(ranges) != expected) throw new AssertionError();
    }
    public static void main(String[] args) {
        check(-1, null);
        check(-1, new int[][]{});
        check(2, new int[][]{{30,60},{15,30},{30,30}});
        check(0, new int[][]{{30,30},{30,60}});
        check(0, new int[][]{{10,24},{30,60}});
        check(0, new int[][]{{30,60}});
        check(-1, new int[][]{{0,30},{30,15},null});
        check(1, new int[][]{{30,15},{7,30}});
        check(1, new int[][]{{7,30},{15,30}});
        check(0, new int[][]{{30,30},{15,30}});
        check(1, new int[][]{{15,30},{30,30}});
        check(1, new int[][]{{30,60},{15,30}});
        if (RoundFpsSelector.choose(new int[][]{{30,30},{30,60}}, 60, 30) != 1) {
            throw new AssertionError("Non-round 60 FPS policy changed");
        }
        if (RoundFpsSelector.choose(new int[][]{{30,60},{60,60}}, 60, 60) != 1) {
            throw new AssertionError("Fixed 60 FPS policy changed");
        }
        System.out.println("14 FPS policy checks passed");
    }
}
