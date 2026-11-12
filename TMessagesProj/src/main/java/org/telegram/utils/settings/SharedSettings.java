package org.telegram.utils.settings;

import org.telegram.messenger.BuildConfig;
import org.telegram.ui.ActionBar.SimpleActionBarDoneButton;
import org.telegram.utils.recorder.round.RoundVideoSession;
import org.telegram.utils.recorder.audio.AudioRecordingSession;

public final class SharedSettings {

    public static final BooleanSetting experimentalSettingsAllowed =
        BooleanSetting.of("experimental_settings_allowed", BuildConfig.DEBUG_VERSION);

    public static final BooleanSetting audioRecorderV2Enabled =
        BooleanSetting.of("audio_recorder_v2_enabled", BuildConfig.DEBUG_VERSION);

    public static final EnumSetting<AudioRecordingSession.NoiseSuppression> audioRecorderNoiseSuppression =
        EnumSetting.of("audio_recorder_noise_suppression", AudioRecordingSession.NoiseSuppression.DISABLED);

    public static final BooleanSetting roundVideoCamera2Enabled =
        BooleanSetting.of("round_video_camera2_enabled", false);

    public static final EnumSetting<RoundVideoSession.OutputResolution> roundVideoOutputResolution =
        EnumSetting.of("round_video_output_resolution", RoundVideoSession.OutputResolution.P480);

    public static final EnumSetting<RoundVideoSession.CameraResolution> roundVideoCameraResolution =
        EnumSetting.of("round_video_camera_resolution", RoundVideoSession.CameraResolution.MEDIUM);

    public static final EnumSetting<RoundVideoSession.FrameRate> roundVideoFrameRate =
        EnumSetting.of("round_video_frame_rate", RoundVideoSession.FrameRate.FPS_30);

    public static final BooleanSetting roundVideoStabilization =
        BooleanSetting.of("round_video_stabilization", false);

    public static final EnumSetting<RoundVideoSession.MotionPriority> roundVideoMotionPriorityLevel =
        EnumSetting.of("round_video_motion_priority_level", RoundVideoSession.MotionPriority.BALANCED);

    public static final BooleanSetting roundVideoLowLightBoost =
        BooleanSetting.of("round_video_low_light_boost", true);

    public static final BooleanSetting roundVideoComposition =
        BooleanSetting.of("round_video_composition", true);

    public static final EnumSetting<RoundVideoSession.CameraFacing> roundVideoLastCamera =
        EnumSetting.of("round_video_last_camera", RoundVideoSession.CameraFacing.FRONT);

    /**
     * Restores every round-video option to its default. The recorder choice
     * ("Use Camera2 recorder") and the last-used camera are kept.
     */
    public static void resetRoundVideoSettings() {
        roundVideoOutputResolution.reset();
        roundVideoCameraResolution.reset();
        roundVideoFrameRate.reset();
        roundVideoMotionPriorityLevel.reset();
        roundVideoStabilization.reset();
        roundVideoLowLightBoost.reset();
        roundVideoComposition.reset();
    }

    public static final EnumSetting<SimpleActionBarDoneButton.Style> headerDoneButtonStyle =
        EnumSetting.of("header_done_button_style", SimpleActionBarDoneButton.Style.GLASS);

    public static final EnumSetting<SimpleActionBarDoneButton.Shape> headerDoneButtonShape =
        EnumSetting.of("header_done_button_shape", SimpleActionBarDoneButton.Shape.TEXT);

    private SharedSettings() {
    }
}
