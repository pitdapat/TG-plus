package org.telegram.messenger.tgplus.asr;

/** Decides who transcribes a voice note: the Telegram server or this phone. */
public final class TranscriptionRouting {

    public static final int AUTOMATIC = 0;
    public static final int ON_DEVICE = 1;
    public static final int TELEGRAM = 2;

    /** Longest voice note transcribed on the phone. */
    public static final int MAX_AUDIO_SECONDS = 20 * 60;

    private TranscriptionRouting() {}

    /**
     * Automatic: Premium goes to Telegram, everyone else to the phone. Always on-device: the phone.
     * Telegram only: stock behavior for everyone.
     */
    public static boolean useOnDevice(int route, boolean premium) {
        switch (route) {
            case ON_DEVICE:
                return true;
            case TELEGRAM:
                return false;
            case AUTOMATIC:
            default:
                return !premium;
        }
    }
}
