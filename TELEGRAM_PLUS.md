# Telegram Plus baseline

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
Firebase push was not configured in the original APK.
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
