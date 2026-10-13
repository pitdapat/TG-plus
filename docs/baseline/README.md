# Telegram Plus — stock Telegram UI, camera backend changes

This project changes the real Telegram Android round-video recorder. The recording
screens, gesture, camera switch, preview, trimming and sending UI remain upstream.
There is no HQ toggle, custom camera panel, or extra API setup screen.

## Clipboard formatting

- Copy rich text through Telegram's clipboard helper with visible formatting in
  the plain-text representation: bold `**text**`, italic `*text*`, underline
  `<u>text</u>` and strikethrough `~~text~~`. Converted copies use a plain-text
  clipboard item so other rich-text apps cannot override the visible markers.
  Copies without these styles preserve the upstream HTML behavior.
- Paste HTML or styled clipboard text into the message/caption composer as
  visible markers. Plain text is unchanged; missing clipboard styles cannot be
  reconstructed. Explicit paste-as-plain-text remains Android's normal action.
- Supports Android bold/italic/underline/strike spans and Telegram TextStyleSpan.
  Other clipboard routes, including keyboards that provide only plain text,
  cannot recover styles that the source app omitted. No automatic clipboard reads
  outside user-triggered copy/paste are introduced.

## Backend changes

- Standard Camera2 recorder: open the front camera on video-button touch-down,
  only with existing camera/audio permissions. Adopt that session on hold; quick
  taps, ACTION_CANCEL, pause/destroy, and a 2-second safety timeout cancel unadopted
  preparation. No microphone or encoder is started during preparation. The camera
  stays off while merely viewing a chat. This overlaps camera opening with the
  existing 150 ms tap/hold window; it does not pre-create the GL capture session.
  Camera1 and the explicitly selected experimental recorder retain their normal
  startup (the latter has no shared Camera2Session to adopt).
- Cancellation while Camera2 is still opening waits for the state callback to
  close the arriving device, rather than terminating its callback thread early.
- Keep a memory-only frozen preview during camera replacement, reject stale
  renderer generations/surface callbacks, and uncover only a timestamped frame
  from the new camera. The cover is removed by the TextureView update callback,
  after the consumer displays a queued frame, rather than by eglSwapBuffers alone.
  Remove edge-on 3D camera flipping in both adapters; retain
  the flip button and its icon animation. Camera switching still needs real hardware
  validation, especially interruption and low-light startup.
- Encode camera switches as cuts, without the standard recorder's 200 ms blend
  or the experimental recorder's crossfade/unblur. Hold the previous frame while
  awaiting the new camera. Reject queued encoder frames from an old camera generation.
- Match the production recorder default: the debug build no longer selects the
  experimental standalone recorder just because it is a debug APK. Explicitly
  saved experimental-recorder preferences remain respected. Both adapters now
  start with the front camera, remove opening transitions and the initial preview
  fade/loading shimmer, use at least 2 Mbps, and request supported stabilization.
- Remove the host chat background opening transition as well. Closing animations remain upstream. The experimental adapter receives a dedicated
  first-live-preview callback instead of waiting for RECORDING to hide its cover.
- Every new round recording starts with the front-facing camera. Resuming a
  paused recording preserves the selected camera.
- Round Camera2 opens only the selected camera initially, using Telegram's existing
  single-camera switching path. The rear camera opens when switching to it.
  This avoids concurrent camera initialization but may slow camera switching.
- Reveal the preview after a successful GL frame is consumed by TextureView, independently
  of encoder readiness, with no preview fade. Remove the 180 ms opening
  transition and animated loading shimmer. A static placeholder still covers
  the camera until its first frame; actual camera initialization is not removed.
  Preserve close animations, tap/hold recognition, recording-ready guards,
  and 200 ms encoder frame warmup for audio/video timing compatibility.
- Log elapsed milliseconds to first presented preview and recording readiness in
  the existing debug log. No setup screen or new camera UI is added.
- Camera2 round-video sessions configure just the video/preview stream. They do
  not allocate or configure the JPEG ImageReader used by photo sessions.
  This removes unnecessary camera-session work; actual startup speed needs device measurement.
- Record at a minimum of 2 Mbps, preserving a higher server-configured bitrate.
- Retain higher bitrate through trimming/conversion instead of reverting to 1 Mbps.
- Populate video metadata from the encoder's actual dimensions and nominal frame
  rate instead of hard-coded 360-by-360 and 25 FPS values.
- On Android 9+ standard round Camera2 sessions, pass the chosen FPS range and
  supported stabilization controls as initial session parameters when FPS is
  advertised as a session key. This avoids changing these controls from template
  defaults after session initialization. Recorder mode is established before
  camera callbacks. Older devices, missing session keys and synchronous rejection
  use the prior setup. The repeating request still carries the chosen FPS range.
  Improvement to the transient rear-camera FPS dip needs phone testing.
- Select device-advertised FPS ranges. Round video prefers a fixed 30–30 FPS range
  to avoid requesting long exposures and a 15 FPS drop in dim light. If unavailable,
  choose the closest advertised range with a lower bound nearest 30. This may
  reduce low-light brightness or increase noise; actual hardware cadence is unverified. Other Camera2 video sessions preserve a preference
  for the previous 30–60 target when it is advertised.
- Round Camera2 streams prefer the sensor active-array aspect ratio, then the
  smallest sufficient frame, bounded to a 1440-pixel long edge. This avoids
  selecting a narrow aspect ratio just because it has fewer pixels. Stock stream
  selection remains the fallback if no bounded candidate is available.
- Round Camera2 uses Android 11+ CONTROL_ZOOM_RATIO when advertised. A camera
  exposing zoom below 1x starts at max(minimum supported zoom, 0.8x); otherwise
  it starts at 1x. Pinch zoom uses the supported range and returns to that default.
  No hidden lens IDs or vendor tags are assumed. Front and rear may differ.
- Request supported continuous video autofocus and one supported stabilization
  mode. Prefer optical stabilization; restore electronic video stabilization when
  optical stabilization is unavailable. Electronic stabilization may add cropping.
  Synchronous request rejection retries the baseline and disables wider zoom.
  Asynchronous device errors still use Telegram's existing handling.
- Preserve Telegram's existing Camera2 choice, including its existing setting
  and server/device configuration. Camera1 remains available as upstream provides it.

No stock-OPPO processing, Hasselblad pipeline, proprietary HDR/night video,
additional lenses, new camera UI, or higher output resolution is claimed. Higher
bitrate produces larger files. End-to-end quality and speed are unverified on hardware.

## Platforms and dependencies

Use Android's official Camera2, MediaCodec and OpenGL SDK APIs and Telegram's
existing AndroidX/media stack. No third-party camera wrapper or OPPO CameraUnit
SDK is added. Compile/target SDK 36, NDK 27.2.12479018, Gradle 8.13 and Java 17
follow upstream. Media submodules also require SDK platform 35.

## Base and source reproduction

Repository: https://github.com/DrKLO/Telegram
Commit: f2908b14133bbffbf7ab04f641ecb5bfaf533242
App version at this source revision: 12.10.6

Clone that commit with its pinned recursive submodules. From the repository,
run `git apply --check /path/to/round-camera.patch`, then apply the same patch.
The patch contains all source and packaging changes in this personal test build.
Follow upstream's README for prerequisites and your own Telegram application
credentials from https://my.telegram.org/apps before distributing a client.
The local test build retains upstream's bundled sample API configuration; login
and server acceptance have not been verified.

## Personal test packaging

- Android 6.0 (API 23) or newer; ARM64 only, intended for an Android ARM64 phone such as the user's OPPO.
- Separate application package `org.patrick.roundcamera.web`.
- Display name: Telegram Plus (personal unofficial client); the approved blue paper-plane/plus launcher logo in five Android density sizes.
- Custom test signing key; the private keystore is NOT included in this package.
- Upstream auto-update checks and official-app-only passkey support disabled.
- Firebase push configuration is not supplied, so FCM notifications aren't configured.
  This test APK is for evaluating the round camera, not replacing the primary client.
- Native build compile jobs capped at four, link jobs at one to fit build memory.

For rebuilding, supply your own signing key, adjust the signing path/passwords
in `TMessagesProj_AppStandalone/build.gradle` and `gradle.properties`, and set
`local.properties` to your Android SDK path. The test signing path in this patch
is `../../build-tools-cache/roundcamera-test.keystore` relative to the module.

Build command: `gradle :TMessagesProj_AppStandalone:assembleAfatDebug`.

## Device validation required

Compare the standard client and this test build in identical lighting: first and
repeated start times, front/rear, camera switching, zoom, focus, shake, dim scenes,
pause/resume, trimming, interruption, and permission denial. Send a trial round
video to Saved Messages and verify playback from another client. No account,
phone camera, or real message was used during development. Compare the first
test APK with this updated APK to assess framing, stabilization and startup; this
is an on-device comparison, not a switch added to Telegram's UI.

## License

Telegram source and these modifications are GPL-2.0-or-later. LICENSE is included.
Retain upstream copyright notices and provide corresponding source when distributing.
