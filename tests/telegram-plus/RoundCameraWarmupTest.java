import org.telegram.messenger.camera.RoundCameraWarmup;
public class RoundCameraWarmupTest {
    static final class Camera {
        int closes;
        void close() { if (++closes > 1) throw new AssertionError("Double close"); }
    }
    static void check(boolean condition) { if (!condition) throw new AssertionError(); }
    public static void main(String[] args) {
        RoundCameraWarmup<Camera> warmup = new RoundCameraWarmup<>(Camera::close);
        Camera first = new Camera();
        int[] opens = {0};
        warmup.prepare(() -> { opens[0]++; return first; });
        warmup.prepare(() -> { throw new AssertionError("Duplicate opening"); });
        check(opens[0] == 1);
        warmup.cancel();
        check(first.closes == 1);
        warmup.cancel();
        check(warmup.take() == null);
        Camera adopted = new Camera();
        warmup.prepare(() -> adopted);
        check(warmup.take() == adopted);
        warmup.cancel();
        check(adopted.closes == 0);
        Camera next = new Camera();
        warmup.prepare(() -> next);
        warmup.cancel();
        check(next.closes == 1 && adopted.closes == 0);
        warmup.prepare(() -> null);
        Camera retry = new Camera();
        warmup.prepare(() -> retry);
        check(warmup.take() == retry);
        check(warmup.take() == null);
        System.out.println("Warmup ownership checks passed: prepare once, quick-cancel, repeated-cancel, adoption, next gesture, failed preparation retry");
    }
}
