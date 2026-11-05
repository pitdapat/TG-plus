# Telegram Plus

Telegram Plus is an **unofficial, personal Android client** based on the [official open-source Telegram for Android](https://github.com/DrKLO/Telegram). It retains Telegram's core messaging experience while adding the features and changes described below.

This is a source-level feature inventory, not a promise that every feature has been tested on every device. Availability depends on the build, settings, hardware and services involved.

## Telegram Plus features

### Speech and dictation

- **Voice and round-video transcription:** Transcribe supported messages on-device using the Parakeet speech model, or use Telegram's server transcription according to your account and the chosen routing setting. The on-device model must be downloaded separately.
- **Transcribe again:** Re-run on-device transcription for an existing voice message or round video when the on-device route is selected.
- **Hold-to-dictate:** Record speech using the dictation control and insert the recognized text into the message composer for editing. Releasing the control does not send the message.
- **Speech model management:** Download or remove the model and manage related options from Telegram Plus settings. Model size, available memory and device performance affect usability.

### Translation

- **Optional Gemini translation:** Choose Telegram or Gemini as the translation provider. Gemini requires your own API key and an internet connection; the app sends selected text to Google when you request a Gemini translation. Model and provider settings are available in Telegram Plus settings.
- **Privacy:** Consider the sensitivity of text before sending it to a third-party service, and check the provider's current terms. No Gemini key is shipped with the app.

### Round-video camera

- **Faster startup approach:** The standard Camera2 recorder can prepare the front camera on touch-down and reveal the preview when a frame is ready.
- **Camera switching:** Switches use cuts rather than the stock blending transition, with the previous frame held while the new camera becomes ready.
- **Recording choices:** Prefer a fixed 30 FPS range when supported, use at least 2 Mbps where applicable, and request supported stabilization. Wider default zoom is used only where the device advertises support.
- Actual startup speed, frame rate, image quality, stabilization and behavior vary by device and lighting; some camera changes need real-device validation. See the [original baseline notes](docs/baseline/README.md) for details and limitations.

### Clipboard formatting

Copy and paste supported bold, italic, underline and strikethrough formatting as visible text markers. Formatting can only be preserved when the source supplies the relevant styles; plain-text-only clipboard data cannot recreate missing formatting. Telegram's protected-content restrictions still apply.

### Updates and diagnostics

- **In-app updates:** Check the project's GitHub Releases for builds and select an update channel. The update flow checks the package and signing identity before installation; Android's installer also enforces signing compatibility.
- **Feature diagnostics:** Inspect feature status and run available self-tests; reports can be copied or exported. UI-speed measurements help investigate performance.
- **Notification diagnostics:** Inspect push-notification diagnostics from the app's settings. Firebase setup alone does not guarantee notification delivery; test on your own device.

## Installation and compatibility

- This is a personal, unofficial client; it is not an official Telegram release.
- The standalone package is `org.patrick.tgplus.web`, allowing side-by-side installation with the older `org.patrick.roundcamera.web` build. A separate installation requires logging in again; secret chats and local-only data do not automatically transfer.
- The documented standalone configuration targets ARM64 and Android 6.0 (API 23) or newer. See the build configuration for current requirements.
- Android updates must have the same application ID and signing certificate as the installed app. Keep the signing key private and backed up; an APK signed with a different key cannot update the existing installation in place.

## Building and contributing

The authoritative agent and project workflow is in [AGENTS.md](AGENTS.md). In particular:

- GitHub APK builds are **manual**. Do not start one unless requested. Run the workflow from `master` and specify the branch to build in the `ref` input.
- Create a feature branch from current `master`, use a pull request, and run the documented local checks before merging. Do not commit directly to `master`.
- See [TELEGRAM_PLUS.md](TELEGRAM_PLUS.md) for signing, Firebase, API credentials, packaging and integration notes. Never commit private keys, signing passwords or service-account credentials.
- Telegram's API and distribution guidance is available in the [official upstream repository](https://github.com/DrKLO/Telegram) and [API documentation](https://core.telegram.org/api). Use your own Telegram API credentials before distributing a client, identify the app as unofficial, and comply with the applicable license.

## Documentation map

- [Agent instructions and build workflow](AGENTS.md) — local checks, branches, builds and release format.
- [Telegram Plus baseline and integration notes](TELEGRAM_PLUS.md) — upstream integrations, installation identity, signing and notification setup.
- [Original baseline change list](docs/baseline/README.md) — detailed camera and clipboard changes; its original package, signing and Firebase notes are historical and may be superseded by the integration notes.
- [Speech and translation design notes](docs/ASR_TRANSLATE_PLAN.md) — design, implementation notes and future ideas. Its opening status is stale: parts are implemented in the current source, while other sections remain proposals. Do not treat every item as an implemented feature.

When adding, changing or removing a feature, update this inventory and the relevant detailed document. Distinguish source implementation from device-tested behavior, and label proposed work clearly.

## License

Telegram for Android and this fork are distributed under the [GNU GPL version 2 or later](LICENSE). Preserve the applicable copyright and license notices and provide corresponding source when distributing the app.
