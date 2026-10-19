import org.telegram.messenger.camera.RoundCameraStartupGate;

public class RoundCameraStartupGateTest {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        RoundCameraStartupGate stable = new RoundCameraStartupGate();
        check(!stable.isReleased(), "A composed preview must stay covered before release");
        check(!stable.allowFrame(0, 0), "An empty texture must not arm startup");
        stable.onCaptureResult(10, 2, 2);
        check(!stable.allowFrame(10, 1000), "One good result is not enough");
        stable.onCaptureResult(20, 2, 2);
        check(!stable.allowFrame(20, 1119), "Do not expose transient early values");
        check(!stable.allowFrame(10, 1120), "Do not expose a texture older than settled metadata");
        check(stable.allowFrame(20, 1120), "Two settled results release a matching frame");
        check(stable.isReleased(), "A composed preview may reveal frames rendered after release");
        check(!stable.allowFrame(19, 1121), "A lagging consumer must not expose an older frame");
        stable.onCaptureResult(30, 1, 1);
        check(stable.allowFrame(30, 1122), "Later lighting changes must not freeze recording");

        RoundCameraStartupGate searching = new RoundCameraStartupGate();
        searching.allowFrame(1, 0);
        searching.onCaptureResult(2, 2, 2);
        searching.onCaptureResult(3, 2, 2);
        searching.onCaptureResult(4, 1, 1);
        searching.onCaptureResult(2, 2, 2);
        check(!searching.allowFrame(4, 300), "A renewed search invalidates readiness; ignore stale results");
        check(!searching.allowFrame(5, 699), "Known unsettled metadata waits until the bound");
        check(searching.allowFrame(6, 700), "A device that never converges cannot block startup");

        RoundCameraStartupGate missing = new RoundCameraStartupGate();
        missing.allowFrame(1, 5000);
        missing.onCaptureResult(1, null, null);
        check(!missing.allowFrame(2, 5299), "Missing optional metadata needs a short warmup");
        check(missing.allowFrame(3, 5300), "Camera1 and missing-metadata devices fall back");

        RoundCameraStartupGate partial = new RoundCameraStartupGate();
        partial.allowFrame(1, 0);
        partial.onCaptureResult(2, 4, null);
        partial.onCaptureResult(3, 4, null);
        check(partial.allowFrame(3, 120), "Converged but dark AE and absent AWB are supported");

        RoundCameraStartupGate tint = new RoundCameraStartupGate();
        tint.allowFrame(1, 0);
        tint.onCaptureResult(2, 2, 1);
        tint.onCaptureResult(3, 2, 1);
        check(!tint.allowFrame(3, 300), "Stable exposure alone must not reveal searching white balance");
        tint.onCaptureResult(4, 2, 3);
        tint.onCaptureResult(5, 3, 3);
        check(tint.allowFrame(5, 301), "Locked metering is ready");
        check(!new RoundCameraStartupGate().allowFrame(100, 10000), "A new recording gets a fresh guard");
        System.out.println("Round-camera startup checks passed: metadata, texture ordering, colour settling, fallback, timeout and fresh sessions");
    }
}
