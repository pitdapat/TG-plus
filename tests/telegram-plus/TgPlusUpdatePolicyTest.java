import org.telegram.messenger.tgplus.UpdatePolicy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class TgPlusUpdatePolicyTest {
    static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    static UpdatePolicy.Asset apk(String name) {
        return new UpdatePolicy.Asset(name, "https://github.com/pitdapat/TG-plus/releases/download/x/" + name, 66729295,
            "application/vnd.android.package-archive", "sha256:" + "ab".repeat(32));
    }

    public static void main(String[] args) {
        int checks = 0;
        // Real asset name format published by the workflow.
        UpdatePolicy.Release fixes = new UpdatePolicy.Release("build-round-video-camera-fixes", "Telegram Plus (round-video-camera-fixes) - build #33",
            "2a7c1b4bc454894528903fec32d9bc4831000620", "2026-11-01T14:38:10Z", true, false,
            Arrays.asList(apk("TelegramPlus-13.1.1-round-video-camera-fixes-build33-2a7c1b4.apk")));
        UpdatePolicy.Release ci = new UpdatePolicy.Release("build-ci-build-any-branch", "Telegram Plus (ci-build-any-branch) - build #28",
            "b2b04cb2c94d6858abecd01f799e0eb4f9f7ee39", "2026-11-01T09:50:43Z", true, false,
            Arrays.asList(apk("TelegramPlus-13.1.1-ci-build-any-branch-build28-b2b04cb.apk")));
        UpdatePolicy.Release noApk = new UpdatePolicy.Release("build-master", "x", "c", "t", false, false,
            Arrays.asList(new UpdatePolicy.Asset("notes.txt", "https://example.com/notes.txt", 10, "text/plain", null)));
        UpdatePolicy.Release draft = new UpdatePolicy.Release("build-draft", "x", "c", "t", false, true,
            Arrays.asList(apk("TelegramPlus-13.1.1-draft-build99-abcdef1.apk")));
        UpdatePolicy.Release insecure = new UpdatePolicy.Release("build-insecure", "x", "c", "t", false, false,
            Arrays.asList(new UpdatePolicy.Asset("TelegramPlus-13.1.1-insecure-build50-abcdef1.apk", "http://example.com/a.apk", 1, null, null)));
        List<UpdatePolicy.Release> releases = Arrays.asList(fixes, ci, noApk, draft, insecure);

        UpdatePolicy.Candidate c = UpdatePolicy.candidate(fixes);
        check(c != null && c.buildNumber == 33 && "2a7c1b4".equals(c.shortSha) && ("ab".repeat(32)).equals(c.apk.sha256), "asset parsing failed");
        checks++;

        check(UpdatePolicy.candidate(noApk) == null && UpdatePolicy.candidate(draft) == null && UpdatePolicy.candidate(insecure) == null,
            "unusable releases accepted");
        checks++;

        check(UpdatePolicy.channels(releases).equals(Arrays.asList("round-video-camera-fixes", "ci-build-any-branch")), "channels: " + UpdatePolicy.channels(releases));
        checks++;

        // Branch names map to tags the same way as the workflow's tr -c 'A-Za-z0-9._-' '-'.
        check("build-feature-tgplus-settings-translation".equals(UpdatePolicy.tagForBranch("feature/tgplus-settings-translation")), "tag mapping");
        check(UpdatePolicy.pick(releases, "round-video-camera-fixes") == c || UpdatePolicy.pick(releases, "round-video-camera-fixes").buildNumber == 33, "pick by channel");
        check(UpdatePolicy.pick(releases, "master") == null && UpdatePolicy.pick(releases, "missing") == null, "missing channel should give null");
        checks++;

        check(UpdatePolicy.compare(28, c) == UpdatePolicy.Status.UPDATE_AVAILABLE, "newer build");
        check(UpdatePolicy.compare(33, c) == UpdatePolicy.Status.UP_TO_DATE, "same build");
        check(UpdatePolicy.compare(40, c) == UpdatePolicy.Status.INSTALLED_NEWER, "installed newer");
        check(UpdatePolicy.compare(0, c) == UpdatePolicy.Status.LOCAL_BUILD, "local build");
        check(UpdatePolicy.compare(28, null) == UpdatePolicy.Status.NO_RELEASE, "no release");
        checks++;

        // Malformed digests are ignored rather than trusted.
        check(new UpdatePolicy.Asset("a", "https://x", 1, null, "sha256:xyz").sha256 == null
            && new UpdatePolicy.Asset("a", "https://x", 1, null, null).sha256 == null, "bad digest accepted");
        checks++;

        // If a release somehow carries two APKs, the higher build wins.
        UpdatePolicy.Release two = new UpdatePolicy.Release("build-two", "x", "c", "t", false, false, new ArrayList<>(Arrays.asList(
            apk("TelegramPlus-13.1.1-two-build7-aaaaaaa.apk"), apk("TelegramPlus-13.1.1-two-build9-bbbbbbb.apk"))));
        check(UpdatePolicy.candidate(two).buildNumber == 9, "highest build not chosen");
        checks++;

        System.out.println(checks + " Telegram Plus update policy checks passed");
    }
}
