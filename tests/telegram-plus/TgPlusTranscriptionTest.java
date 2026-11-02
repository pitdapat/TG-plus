import org.telegram.messenger.tgplus.asr.AudioChunker;
import org.telegram.messenger.tgplus.asr.TranscriptionRouting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class TgPlusTranscriptionTest {
    static int checks;

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }

    public static void main(String[] args) {
        // Routing
        check(!TranscriptionRouting.useOnDevice(TranscriptionRouting.AUTOMATIC, true), "automatic + premium -> telegram");
        check(TranscriptionRouting.useOnDevice(TranscriptionRouting.AUTOMATIC, false), "automatic + free -> device");
        check(TranscriptionRouting.useOnDevice(TranscriptionRouting.ON_DEVICE, true), "device + premium -> device");
        check(TranscriptionRouting.useOnDevice(TranscriptionRouting.ON_DEVICE, false), "device + free -> device");
        check(!TranscriptionRouting.useOnDevice(TranscriptionRouting.TELEGRAM, true), "telegram + premium -> telegram");
        check(!TranscriptionRouting.useOnDevice(TranscriptionRouting.TELEGRAM, false), "telegram + free -> telegram");
        check(TranscriptionRouting.useOnDevice(99, false), "unknown route behaves like automatic (free)");
        check(!TranscriptionRouting.useOnDevice(-1, true), "unknown route behaves like automatic (premium)");

        // Chunker
        int rate = 16000;
        check(AudioChunker.split(null, rate).isEmpty(), "null audio");
        check(AudioChunker.split(new float[0], rate).isEmpty(), "empty audio");
        List<int[]> one = AudioChunker.split(new float[rate * 10], rate);
        check(one.size() == 1 && one.get(0)[0] == 0 && one.get(0)[1] == rate * 10, "short audio is one chunk");
        List<int[]> exact = AudioChunker.split(new float[rate * 30], rate);
        check(exact.size() == 1, "exactly the maximum is one chunk");

        // 75 s of loud noise with a silent gap at 27.0-27.5 s and 56.0-56.5 s: cuts must land in the gaps.
        float[] audio = new float[rate * 75];
        Arrays.fill(audio, 0.5f);
        Arrays.fill(audio, (int) (27.0 * rate), (int) (27.5 * rate), 0f);
        Arrays.fill(audio, (int) (56.0 * rate), (int) (56.5 * rate), 0f);
        List<int[]> chunks = AudioChunker.split(audio, rate);
        check(chunks.size() == 3, "75 s makes 3 chunks, got " + chunks.size());
        check(chunks.get(0)[0] == 0, "first starts at 0");
        check(chunks.get(0)[1] >= (int) (27.0 * rate) && chunks.get(0)[1] <= (int) (27.5 * rate), "first cut in the gap");
        check(chunks.get(1)[0] == chunks.get(0)[1], "second starts where first ends");
        check(chunks.get(1)[1] >= (int) (56.0 * rate) && chunks.get(1)[1] <= (int) (56.5 * rate), "second cut in the gap");
        check(chunks.get(2)[1] == audio.length, "last ends at the end");
        for (int[] c : chunks) check(c[1] - c[0] <= 30 * rate, "no chunk over 30 s");

        // Coverage and no overlap for varied lengths, no gaps.
        for (int seconds : new int[]{31, 59, 60, 61, 90, 600, 1200}) {
            List<int[]> list = AudioChunker.split(new float[rate * seconds], rate);
            int pos = 0;
            for (int[] c : list) {
                check(c[0] == pos && c[1] > c[0], seconds + " s: contiguous at " + pos);
                check(c[1] - c[0] <= 31 * rate, seconds + " s: chunk length");
                pos = c[1];
            }
            check(pos == rate * seconds, seconds + " s: covers everything");
        }

        // A tail under one second joins the previous chunk instead of standing alone.
        float[] loud = new float[rate * 30 + rate / 2];
        Arrays.fill(loud, 0.5f);
        Arrays.fill(loud, (int) (29.85 * rate), (int) (30.0 * rate), 0f);
        List<int[]> tail = AudioChunker.split(loud, rate);
        check(tail.size() == 1 && tail.get(0)[1] == loud.length, "short tail merged");

        // Joining text
        check(AudioChunker.join(Arrays.asList("hello", " world ", "", null, "again")).equals("hello world again"), "join skips empty");
        check(AudioChunker.join(new ArrayList<>()).isEmpty(), "join of nothing");

        System.out.println(checks + " Telegram Plus transcription checks passed");
    }
}
