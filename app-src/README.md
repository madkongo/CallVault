# CallVault

> **CallVault is a fork of [ShizuCallRecorder](https://github.com/kitsumed/ShizuCallRecorder)** (Copyright © kitsumed (Med)), re-architected to run **self-contained over embedded ADB** instead of Shizuku. This is a modified, independent version — not endorsed by or affiliated with the original author. See [NOTICE.md](./NOTICE.md).

A **non-root, FOSS call recorder for Android** that records **both sides** of a phone call. CallVault drives a privileged shell session entirely **on-device** — no Shizuku, no root, no companion PC — using an **embedded ADB client** ([libadb-android](https://github.com/MuntashirAkon/libadb-android)) over Android's own **Wireless Debugging**, with [scrcpy-server](https://github.com/genymobile/scrcpy) as the audio-capture engine.

## How it works

CallVault pairs once with Wireless Debugging, then runs a **persistent privileged daemon** (a `setsid`-detached `app_process` under the shell UID, in the spirit of Shizuku) that survives Wireless Debugging being turned off. Recording commands flow to it over **binder IPC** — no ADB connection is needed at record time.

- **Wireless Debugging is fully automatic and transient** — CallVault turns it on only long enough to (re)launch the daemon, then turns it back off. No manual toggling.
- The daemon connects to `scrcpy-server` and muxes the call audio (**Opus** or **AAC**) into a file you own via the Storage Access Framework.

## Features

- Records **both sides of phone calls** (incoming and outgoing), including over Bluetooth / headsets.
- **Always-on privileged daemon** — no per-call setup, no persistent foreground requirement.
- **Automatic, transient Wireless Debugging** — enabled only when needed, disabled otherwise.
- **Automatic call recording** with exclusion rules: ignore anonymous calls, specific contacts, or all contacts.
- Saves recordings with the **Opus** or **AAC** codec, using a [BCR](https://github.com/chenxiaolong/BCR)-compatible file-name format.

## Requirements

- **Android 12 or newer** (Android 11 works with limitations; the screen must be unlocked).
- **Wireless Debugging** available in Developer Options (no PC, no root, no Shizuku).

> [!IMPORTANT]
> CallVault relies on hidden internal Android APIs and `scrcpy-server`. It is inherently prone to breaking on new Android releases or OEM modifications. Behavior is **non-deterministic** across devices — see the Disclaimer below.

## Installation

CallVault is distributed as a sideloaded APK via **GitHub Releases** / **Obtainium**, and is intended for **F-Droid**. It **cannot** be published to the Google Play Store — Play prohibits both call recording and the embedded-ADB privilege mechanism CallVault depends on.

Setup (permissions + one-time Wireless Debugging pairing) is handled **in-app** during onboarding. There is nothing to configure on a PC.

## Attribution

CallVault is a modified fork of **ShizuCallRecorder**, available at <https://github.com/kitsumed/ShizuCallRecorder>. The original project, its name, trademarks, and logos are the property of **kitsumed (Med)**. CallVault uses the name only for this required attribution.

Built on the work of:
- [ShizuCallRecorder](https://github.com/kitsumed/ShizuCallRecorder) — the upstream project this is forked from.
- [scrcpy](https://github.com/genymobile/scrcpy) — the audio-capture server.
- [libadb-android](https://github.com/MuntashirAkon/libadb-android) — the embedded ADB client.

## License

Licensed under the [GNU General Public License v3.0](LICENSE). ⚠️ **Additional Terms** under GPLv3 Section 7 apply (at the end of the license file), including trademark protection and mandatory fork-attribution requirements that this project complies with.

## Disclaimer

**Recording phone calls may be subject to complex and varying laws in different countries and jurisdictions.** You may need to **ensure you have consent** from all parties before recording. The **developers and contributors are not responsible** for any misuse or legal consequences. Learn more: [Telephone call recording laws](https://en.wikipedia.org/wiki/Telephone_call_recording_laws). **This is not legal advice** — consult a legal professional for your situation.

> [!CAUTION]
> **Non-deterministic behavior:** Due to the evolving Android ecosystem and OEM variation, CallVault may fail to detect call transitions (concurrent/held calls may record into a single file), may misclassify a caller as "anonymous" when the number arrives late, and may start, continue, or fail to record unexpectedly after OS updates. It is **your responsibility** to verify recording behavior on your device complies with your local laws, and to **cease immediately** any activity that would constitute a legal infraction.
