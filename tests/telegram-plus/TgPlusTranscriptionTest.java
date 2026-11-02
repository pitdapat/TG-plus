import org.telegram.messenger.tgplus.asr.AudioChunker;
import org.telegram.messenger.tgplus.asr.PcmBuffer;
import org.telegram.messenger.tgplus.asr.PcmConverter;
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

        // PCM conversion
        short[] stereo = {1000, 3000, -2000, -4000, 500, 501};
        short[] mono = new short[3];
        PcmConverter.downmix(stereo, 0, 3, 2, mono, 0);
        check(mono[0] == 2000 && mono[1] == -3000 && mono[2] == 500, "stereo averaged to mono");
        short[] passthrough = new short[2];
        PcmConverter.downmix(new short[]{7, 9}, 0, 2, 1, passthrough, 0);
        check(passthrough[0] == 7 && passthrough[1] == 9, "mono copied");
        short[] six = new short[6];
        PcmConverter.downmix(new short[]{0, 0, 0, 6, 6, 6, 3, 3, 3, 0, 0, 0}, 0, 2, 6, six, 0);
        check(six[0] == 3 && six[1] == 1, "six channels averaged");

        short[] dc48 = new short[48000];
        Arrays.fill(dc48, (short) 1234);
        short[] r48 = PcmConverter.toMono16k(dc48, dc48.length, 48000);
        check(r48.length == 16000, "48 kHz -> 16000 samples, got " + r48.length);
        check(r48[0] == 1234 && r48[8000] == 1234 && r48[15999] == 1234, "constant level kept at 48 kHz");
        short[] dc44 = new short[44100];
        Arrays.fill(dc44, (short) -800);
        short[] r44 = PcmConverter.toMono16k(dc44, dc44.length, 44100);
        check(r44.length == 16000, "44.1 kHz -> 16000 samples, got " + r44.length);
        check(r44[0] == -800 && r44[7000] == -800 && r44[15999] == -800, "constant level kept at 44.1 kHz");
        short[] same = PcmConverter.toMono16k(new short[]{1, 2, 3}, 3, 16000);
        check(same.length == 3 && same[2] == 3, "16 kHz unchanged");
        check(PcmConverter.toMono16k(new short[0], 0, 48000).length == 0, "empty input");
        check(PcmConverter.toMono16k(new short[]{1}, 1, 0).length == 0, "bad rate");
        // A 1 kHz tone keeps its frequency: count zero crossings over one second.
        short[] tone = new short[48000];
        for (int i = 0; i < tone.length; i++) tone[i] = (short) (10000 * Math.sin(2 * Math.PI * 1000 * i / 48000.0));
        short[] t16 = PcmConverter.toMono16k(tone, tone.length, 48000);
        int crossings = 0;
        for (int i = 1; i < t16.length; i++) if ((t16[i - 1] < 0) != (t16[i] < 0)) crossings++;
        check(Math.abs(crossings - 2000) <= 4, "1 kHz tone survives resampling, crossings " + crossings);

        // Dictation buffer
        PcmBuffer buf = new PcmBuffer(100);
        check(buf.append(new short[]{1, 2, 3}, 3) == 3 && buf.size() == 3, "append");
        check(buf.append(new short[]{4, 5}, 1) == 1 && buf.size() == 4, "append count respected");
        short[] big = new short[200];
        Arrays.fill(big, (short) 9);
        check(buf.append(big, 200) == 96 && buf.size() == 100 && buf.isFull(), "append stops at the limit");
        check(buf.append(big, 10) == 0, "full buffer takes nothing");
        short[] all = buf.toArray();
        check(all.length == 100 && all[0] == 1 && all[2] == 3 && all[3] == 4 && all[99] == 9, "contents kept in order");
        all[0] = 77;
        check(buf.toArray()[0] == 1, "toArray returns a copy");
        check(new PcmBuffer(0).append(new short[]{1}, 1) == 0, "zero-size buffer");
        PcmBuffer growing = new PcmBuffer(16000 * 120);
        for (int i = 0; i < 40; i++) growing.append(new short[16000], 16000);
        check(growing.size() == 640000, "grows past its starting size");
        check(PcmBuffer.level(new short[100], 100) == 0f, "silence has level 0");
        short[] veryLoud = new short[100];
        Arrays.fill(veryLoud, (short) 30000);
        check(PcmBuffer.level(veryLoud, 100) == 1f, "very loud caps at 1");
        short[] speech = new short[100];
        Arrays.fill(speech, (short) 3000);
        check(Math.abs(PcmBuffer.level(speech, 100) - 0.5f) < 0.01f, "speech-level block is about 0.5");
        check(PcmBuffer.level(speech, 0) == 0f, "empty block");

        System.out.println(checks + " Telegram Plus transcription checks passed");
    }
}
