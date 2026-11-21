# Developing CallVault

For contributors on any machine. `BUILDING.md` is the short build recipe; this file is the wider picture.
Status: written 2026-11-21 (review item F12). The steps were written from the build files, not yet followed on a
clean machine (the check for this item is a fresh checkout built by following only this page).

## Toolchain

| Need | Version | Why |
|---|---|---|
| JDK | 17 | Gradle and `jvmTarget` 17. Newer JDKs are not used. |
| Android SDK | platform 36 (`compileSdk 36`), build-tools 36.0.0 | `minSdk` is 30. |
| NDK | 27.2.12479018 | builds `libaudiohandoff.so`, `whisper.cpp` and `llama.cpp` natives |
| CMake | 3.22.1 | same native build |
| Git submodules | `third_party/whisper.cpp`, `third_party/llama.cpp` | `git submodule update --init --recursive` after cloning; without them the native build fails |

Install the SDK pieces with
`sdkmanager "platforms;android-36" "build-tools;36.0.0" "ndk;27.2.12479018" "cmake;3.22.1"`.

## Environment variables

Nothing needs a private file. Set these in your shell (paths are examples; use your own):

| Variable | Meaning | Example |
|---|---|---|
| `JAVA_HOME` | a JDK 17 | `/usr/lib/jvm/java-17-openjdk` |
| `ANDROID_HOME` | the Android SDK | `$HOME/Android/Sdk` |
| `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` | release signing only; debug builds do not need them | set in CI from secrets, never commit |

Gradle properties (`-Pname=value`): `versionName`, `versionCode`, `ciBuildNumber` (CI sets these),
`isolateTestApp` (see below), `agentAbi=<abi>` (limit native ABIs for faster local test builds; refused for release
builds unless `agentAbiAllowRelease=true`).

## Build and test

```
./gradlew assembleDebug                      # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest             # JVM unit and Robolectric tests, no device
./gradlew :app:testDebugUnitTest --tests '*ThreadingPolicyGuardTest*'   # one class
```

Instrumented tests (`app/src/androidTest`) need a device or emulator. They replace the installed app unless you
pass `-PisolateTestApp`, so never run them on a phone you use without it.

A minified release build breaks the app (hidden Android APIs and a daemon launched by class name); leave
`minifyEnabled` off.

## Emulator matrix

Daemon, background-limit and notification behaviour are checked on one emulator per Android version, named
`cv-a11` ... `cv-a17`. Run emulators headless (`emulator -avd <name> -no-window`) and take screenshots with
`adb shell screencap`. Android 17 images need `target=android-37.x` in the AVD `config.ini`/`.ini`, or they start
without hardware acceleration. Scripted phone checks live in `tools/device-checks/` (for example
`mode-switch-check.sh <adb-serial>`); an emulator proves the daemon and notices, not real carrier audio.

## Package map (`app/src/main/java/com/baba/callvault`)

| Package | What lives there |
|---|---|
| `server` | the privileged recorder daemon (runs as the shell user, launched by class name) and capture sessions |
| `services/recording`, `services/call`, `services/boot` | foreground services, call detection, app-call (VoIP) recording, keep-alive, boot start |
| `integrations/adb`, `integrations/scrcpy` | the on-device ADB client, pairing, Shizuku support, scrcpy-based capture |
| `system` | permissions, storage and Drive copy, updates, diagnostics, health checks |
| `data` | preferences, recording catalog (Room), transcripts, merge, clips, health state |
| `transcription`, `summary` | on-device Whisper transcription, cloud transcription (BYOK), llama.cpp summaries |
| `ui` | Compose screens, view models, theme |
| `utils` | logging (`AppLogger`), file naming, device info, helpers |

Native code: `app/src/main/cpp` and `third_party/`.

## Policies

- Threading and scopes: `docs/dev-notes/threading-policy.md` (guarded by `ThreadingPolicyGuardTest`).
- Phone-specific behaviour: `docs/dev-notes/device-quirks.md`. Read it before touching call detection, capture,
  the recorder lifecycle or transport; never remove a quirk path on one device's evidence.
- Error handling policy (F2): not written yet.

## Guard tests worth knowing

These fail on a past mistake, with the reason in each class comment:
`ViewModelInitOrderGuardTest` (properties declared below `init` crashed Home), `ThreadingPolicyGuardTest` (new
unowned scopes/threads), `ForegroundStartGuardTest`, `KeepAliveAdbGuardTest`, `RecorderBringUpGuardsTest`,
`NotificationTextLocaleGuardTest`, `RegexIcuBraceGuardTest`.

## Contributing

See `CONTRIBUTING.md`. Strings go in `values/strings.xml` and each translated `values-*/strings.xml`.
