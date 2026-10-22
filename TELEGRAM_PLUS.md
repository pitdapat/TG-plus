# Telegram Plus baseline

## Current upstream integration

The `telegram-plus` branch merges official Telegram 13.0.3 (7183), commit
`0aae959806c57be8ad457980f2a5c6277ff18635`, into the pre-embedding checkpoint.
The embedding implementation is preserved separately on `embedding-search`.
Camera conflicts retain single-camera startup, touch-down warmup, fixed 30 FPS
preference, stabilization fallback and immediate camera-switch cuts. Upstream
camera session/first-frame readiness checks and device-specific standby controls
are retained. Clipboard formatting, permanent signing, personal API configuration,
ARM64 packaging and compiler caching remain in place.

The 13.0.3 integration includes the upstream voice-message recorder and RNNoise
support, call-reflector candidate validation, bot API compatibility, external
sharing fixes, persistent bot-keyboard Back handling, browser preferences and
login error improvements. Its pinned `tgcalls` and media submodules move together
with the source update. The round-video package move to `utils.recorder.round`
retains our camera changes, including first-frame startup without the removed
metering gate or gray settling cover. Upstream's final 13.0.3 gift-reordering
behavior is retained; the earlier PR #2177 is not reapplied independently.
Open upstream PRs are excluded from this integration.

## Original source recovery

Recovered from the original Round_Camera_Prototype.zip source archive (version 11).
Based on official Telegram f2908b14133bbffbf7ab04f641ecb5bfaf533242, version 12.10.6.
The archive validation records APK SHA-256:
3593d699b8e61602e1a7d232e20c926b5d5c5672ee16bd3308fe15e17941a011
This exactly matches the supplied Telegram_Plus_30FPS_Start.apk.

All 41 patched paths were restored, including clipboard copy/paste formatting,
standard and experimental round camera changes, fixed 30 FPS session setup,
front-camera startup, warmup, camera-switch cuts, bitrate and metadata fixes,
Telegram Plus branding, ARM64 packaging and native build limits.
See docs/baseline/README.md for the complete original change list and
VALIDATION.txt for historical build validation. Tests are in tests/telegram-plus.

This commit restores the APK's source baseline. The foreground-service patch
is separate and is not included in this baseline.

## Signing and rebuilds

The intentional configuration changes from the recovered patch concern signing and credentials:
local signing paths/passwords are replaced by environment variables:
TELEGRAM_PLUS_KEYSTORE, TELEGRAM_PLUS_STORE_PASSWORD,
TELEGRAM_PLUS_KEY_ALIAS and TELEGRAM_PLUS_KEY_PASSWORD.
Legacy signing password properties are removed. No private keystore is committed. The original test key is not available here;
a different key cannot update the installed APK in place.

For personal testing, leaving TELEGRAM_PLUS_API_ID and TELEGRAM_PLUS_API_HASH unset now uses the public sample credentials shipped with upstream Telegram at this source revision, matching the earlier APK. Supply both environment variables to override them with your own developer application. Partial, empty or invalid overrides are rejected. Telegram limits the bundled sample credentials to testing; use your own before distributing a client. TELEGRAM_PLUS_MAPS_API_KEY is optional. Bundled Maps, SafetyNet, OAuth and Huawei identifiers remain removed or replaced by environment configuration. Camera and clipboard behavior remains the recovered baseline.
Firebase push was not configured in the original APK. The standalone build now
uses the client configuration for Firebase project `telegram-plus-bb466` and
`org.patrick.tgplus.web`. Analytics remains disabled. The Google Services Gradle
plugin generates the Firebase resources, and the existing Telegram encrypted
push handler and token registration are retained. The messaging service is not
exported. Server-side sending credentials must also be configured for the same
personal Telegram API application at my.telegram.org; a successful APK build or
FCM token alone does not prove end-to-end message delivery. Service-account
private keys must never be included in this repository or APK.

Validate on the phone with actual Telegram messages while the app is backgrounded,
the screen is locked, and the device has been idle. Check Wi-Fi/mobile transitions
and incoming calls, along with multi-account registration if used.

Each incoming push now owns its completion latch. Repeated or overlapping
callbacks cannot reuse an already completed latch or release another push's
waiter. Invalid OAuth push payloads release their own waiter before returning;
decryption recovery releases it even if network fallback throws. Interrupted
callback waits restore the thread's interrupt flag. These changes preserve the
existing encrypted-push format and notification queue. They do not control
Telegram's outgoing FCM priority or establish real-device delivery latency.
The original README's local signing path is historical; use the variables above.

The GitHub ARM64 debug build has passed full Android compilation, recovered Java checks, signature/package verification and artifact upload. Login configuration was subsequently restored to the original upstream public test defaults; phone login must be tested with the rebuilt APK.
The pure Java tests and XML/patch checks are repeatable locally.

## New installation identity

Standalone builds now use org.patrick.tgplus.web so they install alongside
the older org.patrick.roundcamera.web APK without deleting its local data.
A new permanent RSA-4096 signing key has been created and privately backed up.
The public certificate SHA-256 is:
a11bf05dd7c3d8ca457a5fa1204eea3fd0870d2c47853ffc200d3db7a0afad93
Use the same key for all future updates. The key/passwords are never stored here.
A new installation requires logging in; secret chats and local-only content
remain in the old installation. A signed side-by-side APK has been built; subsequent builds use this same key and package to update it in place.
