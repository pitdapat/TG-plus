# Speech + Translation Feature Plan

Status: **Plan only. Not implemented.**
Scope: add on-device voice-message transcription, push-to-talk dictation into the message input, and on-demand cloud translation to the TG-plus Android client.

---

## 1. Overview

| # | Feature | Solution | Runs | Trigger |
|---|---------|----------|------|---------|
| 1 | Transcribe a voice/round message | Telegram Premium server **or** on-device **Parakeet TDT 0.6B v3**, chosen by the routing rule (Section 5.4) | Server or on-device | Existing transcribe button on the bubble |
| 2 | Dictation into the message input | On-device **Parakeet TDT 0.6B v3** | On-device | Hold the dictation button; a waveform shows in the input field; on release the clip is transcribed and the text is inserted for editing |
| 3 | Translate a message or selected text | **Gemini 3.5 Flash-Lite** (`gemini-3.5-flash-lite`) | Cloud | User taps translate |

### Decisions
- **ASR model, phase 1:** `nvidia/parakeet-tdt-0.6b-v3`, int8 ONNX export (about 640 MB), run with **sherpa-onnx** on Android. Parakeet only processes complete clips, so dictation text appears **after the user lets go**, not while speaking. This is the intended behavior for phase 1.
- **Later option:** `nvidia/nemotron-3.5-asr-streaming-0.6b` for live (word-by-word) dictation once a multilingual Android runtime exists (sherpa-onnx issue k2-fsa/sherpa-onnx#3664). Not in phase 1.
- **Transcription routing:** Premium users default to Telegram's server transcription; non-Premium users default to on-device. A setting overrides this (Section 5.4).
- **Translation:** cloud call only on explicit tap, user-supplied Gemini API key, no shipped key, no proxy.

---

## 2. Repo facts

- `minSdkVersion 23`, `targetSdkVersion 36`, `compileSdkVersion 36`; NDK `27.2.12479018`; `arm64-v8a` only.
- Native layer in `TMessagesProj/jni/` (CMake + `Application.mk`); Opus/FFmpeg in-tree.
- Flavors: App, AppHuawei, AppStandalone, AppHockeyApp. Translation uses plain HTTPS (no Play services).
- No dedicated TG-plus settings screen exists yet. The only TG-plus settings entry today is **Notification diagnostics**: `ui/PushDiagnosticsActivity.java`, opened from a top-level row in `ui/SettingsActivity.java` (`SettingCell.Factory.of(1001, ...)`, handled in `case 1001`). Its data lives in `messenger/PushDiagnostics.java` (private prefs `tgplus_push_diagnostics`, excluded from backup via `res/xml/tgplus_backup_rules.xml` and `tgplus_data_extraction_rules.xml`), with a test in `tools/test_push_diagnostics.py`.
- `RoundVideoSettingsActivity` and `AudioRecorderSettingsActivity` (rows 24 and 26 in `SettingsActivity`) are upstream Telegram screens, not TG-plus additions, so they stay where they are.
- Other TG-plus changes (clipboard formatting, round-camera fixes) have no settings.

---

## 3. How the current UI works (as found in the code)

### 3.1 Voice-message transcription
Files: `ui/Cells/ChatMessageCell.java`, `ui/Components/TranscribeButton.java`, `messenger/MessageObject.java`.

- **When the button shows:** `ChatMessageCell` sets `useTranscribeButton` (around line 12661). It is true when the message is not a quick reply/repost preview, is sent (if outgoing), **and** one of:
  - the user is Premium (`UserConfig.getInstance(account).isPremium()`);
  - the chat allows free transcription (`TranscribeButton.isFreeTranscribeInChat`);
  - a weekly free trial exists (`transcribeAudioTrialWeeklyNumber > 0`) and the message is within the trial length limit;
  - a Premium "teaser" case (no trial, not blocked, incoming, long or forced message).
- **Lock icon:** `transcribeButton.setLock(TranscribeButton.showTranscribeLock(...))` shows a lock for non-Premium users.
- **On tap** (`TranscribeButton.onTap`):
  - Premium → `transcribePressed(...)`.
  - Non-Premium → `transcribePressed(...)` if a trial is available or a transcript already exists; otherwise shows a Premium upsell (`needShowPremiumBulletin`).
- **Server request** (`transcribePressed`): sends `TL_messages_transcribeAudio`, tracks it in `transcribeOperationsByDialogPosition` / `transcribeOperationsById` (drives the loading state), saves results with `MessagesStorage.updateMessageVoiceTranscription(...)`, sets `messageOwner.voiceTranscription`, `voiceTranscriptionOpen`, `voiceTranscriptionFinal`, and refreshes the bubble via `NotificationCenter.voiceTranscriptionUpdate`.
- **Completion:** `TranscribeButton.finishTranscription(messageObject, transcriptionId, text)` is public and static; it stores the text and posts the UI update. On-device results can be delivered through it, so the existing transcript display is reused unchanged.

### 3.2 Voice recording in the input bar
Files: `ui/Components/ChatActivityEnterView.java` (touch handler around line 2998), `messenger/MediaController.java`.

- **Press and hold** the mic button → `recordAudioVideoRunnable` → `MediaController.startRecording(...)`. The recording UI appears: `RecordCircle` with animated waves, `RecordDot`, timer, and "slide to cancel" text.
- **Release** (`ACTION_UP`) → `MediaController.stopRecording(1, ...)` → the voice message is **sent immediately**.
- **Slide left** past the threshold → `stopRecording(0, ...)` → cancelled.
- **Slide up** to the lock → hands-free recording; the circle becomes a send button. Tapping it sends; stopping instead shows `recordedAudioPanel`, a preview with waveform, play, and delete.
- **Quick tap** (no hold) switches between voice and round-video mode.
- **Audio capture:** `MediaController` reads PCM with `AudioRecord` at **48 kHz** (`sampleRate = 48000`) and encodes Opus through `AudioRecorderV2Controller`. Live amplitude feeds the waves (`recordSamples`, `NotificationCenter.recordProgressChanged`). When a recording is kept for preview, `NotificationCenter.audioDidSent` carries the file path (`sendAfterDone == 2`).

---

## 4. Feature 3: Translation (build first; lowest risk)

### 4.1 Provider integration
- Add Gemini as a provider behind the existing `messenger/TranslateController.java` and `ui/Components/TranslateAlert2.java` flow instead of new UI.
- Setting: "Translation provider: Telegram / Gemini".

### 4.2 Settings
- Gemini API key: masked, never logged, stored encrypted with an Android Keystore-backed key (Telegram's SharedPreferences are not encrypted).
- Default target language.
- Gemini translation off until a key is entered.
- Help text: the key is the user's; text goes to Google only when they tap translate.

### 4.3 Network call
- `POST https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent`, header `x-goog-api-key: <user key>`.
- Instructions in the system instruction; message text as separate user content, so a message cannot override the instructions.
- Existing HTTP stack, off the main thread, timeout and cancellation.
- Approximate cost (Oct 2026): $0.30 per 1M input tokens, $2.50 per 1M output tokens. Re-check at implementation time.

### 4.4 Errors
- Missing/invalid key (HTTP 400/403) → point to settings. No network → "requires internet". HTTP 429 → quota message. Empty input → no call; very long input → guard or split.

### 4.5 Privacy
- Opt-in, disclosed. On Google's free API tier, Google may use submitted content to improve its products; say so.

---

## 5. Features 1 & 2: On-device ASR (Parakeet)

### 5.1 Runtime and model
- sherpa-onnx Android libraries (offline transducer recognizer) with the int8 Parakeet TDT v3 export.
- Model downloaded on first use from a pinned URL, stored app-private, SHA-256 checked, versioned. Settings show status (not downloaded / downloading / ready), Wi-Fi-only download, delete.
- Minimum-RAM check: hide or disable on-device speech on devices below a threshold measured in the spike (model needs roughly 600 MB+ RAM).
- New `.so` files must use 16 KB page alignment (`targetSdk 36`, Android 15+ devices).

### 5.2 Spike (gate before UI work)
On an arm64 device: load the model with sherpa-onnx, transcribe a fixed voice note and a 30 s mic clip; record download size, peak RAM, and time per second of audio. Go/no-go; fallback is Whisper via whisper.cpp.

### 5.3 Audio input
- Parakeet expects **16 kHz mono float PCM**.
- Voice notes: decode the Opus file with the in-tree Opus code, resample to 16 kHz. The file must be downloaded first; if not, download it, then transcribe.
- Dictation: separate `AudioRecord` at 16 kHz mono (Section 5.5). Do not reuse `MediaController`'s recorder, which is tied to sending voice messages.

### 5.4 Feature 1: Transcription routing
Setting **"Voice transcription"** (3 options):
| Option | Premium user | Non-Premium user |
|---|---|---|
| **Automatic** (default) | Telegram server | On-device |
| **Always on-device** | On-device | On-device |
| **Telegram only** | Telegram server | Stock behavior (trial / Premium upsell) |

Changes:
- `ChatMessageCell.useTranscribeButton`: also true when the selected route is on-device for this user, so non-Premium users always see the button.
- `TranscribeButton.showTranscribeLock`: no lock when the route is on-device.
- `TranscribeButton.onTap` / `transcribePressed`: if the route is on-device, skip the trial/upsell checks and the `TL_messages_transcribeAudio` request; instead run local transcription in the background and deliver the result with `finishTranscription(...)` so the existing loading state and transcript display are reused.
  - Register the message in `transcribeOperationsByDialogPosition` while running so `isTranscribing` shows the spinner.
  - Use a local transcription id (for example 0 or a negative id) that cannot collide with server ids.
- If the model is not downloaded: tapping the button offers the download (with size).
- On-device can also transcribe the user's own unsent/outgoing voice notes; keep the existing "sent" condition only for the server route.
- Results persist through `MessagesStorage.updateMessageVoiceTranscription`, so reopening the chat does not re-run.

### 5.5 Feature 2: Dictation UX
Goal: hold to dictate, see a small waveform in the input field, release to get editable text.

- **Entry point:** a separate dictation button placed **immediately to the left of the emoji button** in the input field. It is kept apart from Telegram's voice-message mic button, because releasing that button sends a voice message. Placement may be revisited after use.
- **While holding:**
  - Text field area is replaced by a compact strip: red dot, timer, live waveform drawn from microphone amplitude, and "slide to cancel".
  - Audio is captured at 16 kHz mono into memory.
- **On release:** strip shows "Transcribing…"; Parakeet runs on the captured clip in the background; the text is **inserted at the cursor** in the input field (existing draft kept). Nothing is sent.
- **Slide left while holding:** cancel; discard audio.
- **Optional (later):** slide-up lock for long dictation, then tap to stop. Same transcription on stop.
- **Limits:** maximum clip length (for example 2 minutes) to bound memory and wait time.
- **Errors:** model not downloaded → offer download; no mic permission → request it; empty result → short hint.
- Mic permission: reuse the existing `RECORD_AUDIO` request flow from `ChatActivityEnterView`.

### 5.6 Behavior when the server route fails
No fallback. If a Premium server request fails under "Automatic", show Telegram's normal error. (Single-user build; revisit if others use it.)

---

## 6. Telegram Plus settings screen

New screen `ui/TelegramPlusSettingsActivity.java`, opened from **one** top-level row in `SettingsActivity` ("Telegram Plus"). All TG-plus settings live here.

```
Telegram Plus
├─ Speech
│   ├─ Voice transcription: Automatic / Always on-device / Telegram only
│   ├─ Dictation button (on/off)
│   └─ Speech model: status, size, Download / Delete, Wi-Fi only
├─ Translation
│   ├─ Provider: Telegram / Gemini
│   ├─ Gemini API key (masked)
│   ├─ Gemini model id (default gemini-3.5-flash-lite)
│   ├─ Translate to (shared with Telegram's translate sheet)
│   └─ Test translation
├─ App updates
│   ├─ Check for updates (GitHub Releases)
│   ├─ Update channel (branch release to follow)
│   └─ Check automatically once a day
└─ Diagnostics
    ├─ Feature diagnostics (Section 7)
    └─ Notification diagnostics (existing PushDiagnosticsActivity, moved here)
```

- **Move existing setting:** remove the top-level `1001` "Notification diagnostics" row and its `case 1001` from `SettingsActivity`; open `PushDiagnosticsActivity` from the Diagnostics group instead. `PushDiagnosticsActivity` and `PushDiagnostics` themselves are unchanged.
- Settings values stored in one private prefs file (for example `tgplus_settings`); the Gemini key stored separately, Keystore-encrypted (Section 4.2). Add both to the TG-plus backup-exclusion rules.
- All strings in `res/values/strings.xml` with a `TgPlus` prefix, following the existing `PushDiag*` naming.

---

### 6.1 In-app updates
- Source: `GET https://api.github.com/repos/<owner>/<repo>/releases`. The build workflow on `ci-build-any-branch` (PR #2) publishes one rolling release per branch, tagged `build-<branch>`, with one asset `TelegramPlus-<version>-<branch>-build<run>-<sha7>.apk`.
- The installed build number, branch, commit and repository are compiled into `BuildConfig` from `GITHUB_RUN_NUMBER`, `GITHUB_REF_NAME`, `GITHUB_SHA` and `GITHUB_REPOSITORY`. Local builds have build number 0 and are never auto-prompted.
- A release is newer when its run number is larger. The channel defaults to the branch the installed build came from.
- Before install: size and SHA-256 (when GitHub reports a digest) must match, and the APK must have this app's package name and signing certificate. Android's installer then enforces the same signature.
- Install uses the existing FileProvider and `REQUEST_INSTALL_PACKAGES`; if installing unknown apps is not allowed, the user is sent to the system setting first.

## 7. Feature diagnostics

Goal: one screen that shows whether each feature works, with two report outputs: a short report to copy, and a full report file to download that holds everything. Follows the existing `PushDiagnostics` pattern: private storage, excluded from backup, explicit Copy and Clear buttons.

### 7.1 What is recorded (`messenger/TgPlusDiagnostics.java`)
- **Environment:** app version/build, device model, Android version, ABI, page size (4 KB / 16 KB), total RAM, free storage, Premium status, current setting values.
- **Speech model:** downloaded yes/no, model version, file size, SHA-256 match, sherpa-onnx version, last load time and load duration, last load error.
- **Per-event log**, each event with timestamp, feature, route, outcome, duration, and error code/message. Stored as an append-only file in app-private storage (not SharedPreferences, which suits only small data), capped at 2,000 events or 2 MB, oldest dropped first. Events include:
  - Transcription: route chosen (server / on-device) and why, audio length, decode time, inference time, result length in characters.
  - Dictation: clip length, time from release to text, result length, cancel/permission/error outcomes.
  - Translation: provider, source/target language, HTTP status, latency, input/output length.
  - Model download: start, progress failures, completion, checksum result.
- **Never recorded:** transcript text, translated text, message content, audio, or the API key (only "key set: yes/no" and the last 4 characters if useful). A test like `tools/test_push_diagnostics.py` checks that content and the key never reach storage.

### 7.2 Self-tests (buttons on the Feature diagnostics screen)
- **Speech model test:** loads the model and transcribes a short bundled sample clip (a few seconds, shipped in assets with its expected text). Shows pass/fail, the recognized text, load time, and inference time.
- **Microphone test:** records 3 seconds at 16 kHz and shows the peak level, to separate mic/permission problems from model problems.
- **Dictation round trip:** records 3 seconds and runs the full dictation path (capture → model → text), showing the result.
- **Translation test:** sends a fixed sentence ("Good morning, how are you?") to Gemini in the default target language. Shows HTTP status, latency, and the result, or the exact error (invalid key, quota, no network).
- **Run all:** runs the tests in order and shows a pass/fail summary.

### 7.3 Reports
Two buttons, because a copied report has to stay short enough to paste into a chat, while a full report may be large.

- **Copy short report** (clipboard, plain text, kept under about 4,000 characters):
  - environment, settings, model status;
  - last result of each self-test;
  - per-feature counts of successes and failures, and the most recent error for each;
  - last 20 events;
  - notification diagnostics summary;
  - a final line saying the full report has more detail.
- **Download full report** (a `.txt` file, no size limit beyond the log cap):
  - everything in the short report;
  - the complete event log (all stored events);
  - full self-test output, including the recognized text from the bundled sample clip and the Gemini test sentence (fixed test content, not user content);
  - model file details (path, size, SHA-256, download history);
  - the full notification diagnostics snapshot from `PushDiagnostics`.
  - File name: `tgplus-diagnostics-<yyyyMMdd-HHmmss>.txt`.
  - Saving: Android's "save as" picker (`ACTION_CREATE_DOCUMENT`, works on API 23+), so the user chooses the folder, for example Downloads. A **Share** option sends the same file through the Android share sheet (for example into a Telegram chat).
  - The file is written only when the user taps the button. No copy is kept outside app-private storage.
- **Clear:** wipes the event log and self-test results.
- Recording is always on (metadata only) and bounded by the log cap. Neither report ever contains user content or the API key (Section 7.1); the privacy test covers both report outputs.

---

### 7.4 UI speed
Shows whether translations make the app slower. Timings only, never message content. On by default; switch in Settings > Telegram Plus > Diagnostics > Measure UI speed.

- **Open chat:** time from creating the chat screen to the frame that shows its first messages, split by whether any loaded message shows a translation. The first chat after app start is reported separately because it is slower while the app warms up.
- **Translate, tap to visible:** total, split into Gemini wait, saving and queueing, message layout, and the frame that draws it. "Excluding Gemini" is the part this app controls.
- **Scrolling:** moving frames and how many took longer than 24 ms, with and without a translation on screen. This sees stalls of the main thread, not GPU time.
- Summaries use medians. Both reports and the Feature diagnostics screen show them; the full report also lists every event.
- Implementation: `TgPlusUiSpeed` (measuring), `UiSpeedSummary` (pure Java, tested in `TgPlusUiSpeedTest`), hooks in `ChatActivity` (chat creation, first bound message cell, scroll listener, message-translated handler).

## 8. Build order

1. Telegram Plus settings screen; move Notification diagnostics into it.
2. Diagnostics core (`TgPlusDiagnostics`, event log file, short report Copy, full report Download/Share, Clear), so every later feature logs from day one.
3. Feature 3: Gemini provider behind `TranslateController`, settings, key storage, translation self-test.
4. Parakeet + sherpa-onnx spike: go/no-go; speech model self-test.
5. Native libraries, Java wrapper, audio decode/resample, model download.
6. Feature 1: routing in `ChatMessageCell` / `TranscribeButton`.
7. Feature 2: dictation button left of the emoji button, waveform strip, transcribe-on-release; mic and dictation self-tests.

---

## 9. Open items

- Minimum-RAM threshold (from the spike).
- Licensing: Parakeet TDT v3 is CC-BY-4.0 (attribution in the licenses screen); verify at implementation time. Gemini pricing and free-tier terms likewise.

---

## 10. Out of scope

- Live word-by-word dictation (possible later with Nemotron streaming).
- Visual description of video content.
- Automatic translation of incoming messages.
- Shipping or proxying a Gemini API key.
- Offline translation fallback.
