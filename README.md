# CallVault

**A non-root, FOSS call recorder for Android that records both sides of a phone call — no root, no Shizuku, no PC.**

> CallVault is a fork of [ShizuCallRecorder](https://github.com/kitsumed/ShizuCallRecorder) (Copyright © kitsumed (Med)), re-architected to run **self-contained over embedded ADB** instead of Shizuku. It is a modified, independent version — not endorsed by or affiliated with the original author. See [NOTICE.md](./NOTICE.md).

CallVault drives a privileged shell session entirely **on-device** using an embedded ADB client ([libadb-android](https://github.com/MuntashirAkon/libadb-android)) over Android's own **Wireless Debugging**, with [scrcpy-server](https://github.com/genymobile/scrcpy) as the audio-capture engine. You pair **once**; after that it's hands-free.

---

## How it works

After a one-time pairing, CallVault runs a **persistent privileged daemon** (a detached `app_process` under the shell user, in the spirit of Shizuku) that survives Wireless Debugging being turned off. Recording commands flow to it over **binder IPC** — no ADB connection is needed at record time.

- **Wireless Debugging is fully automatic and transient** — CallVault turns it on only long enough to (re)launch the daemon, then turns it back off. You never toggle it manually.
- Call audio is captured via scrcpy and saved (as **Opus** or **AAC**) to a folder you choose — on the device and/or a **cloud folder** (e.g. a Google Drive folder picked through the system file picker).

## Features

- 🎙️ Records **both sides** of incoming & outgoing calls (incl. Bluetooth / headset).
- 🤖 **Automatic** recording with per-call rules (ignore anonymous / specific contacts).
- ☁️ Save to **device, a cloud folder, or both**, with optional **scheduled sync** (immediate / daily / weekly).
- ▶️ In-app **recordings list** with playback, **contact-name** resolution, and **delete**.
- 🔒 **No root, no Shizuku, no companion PC** — everything runs on-device.

## Requirements

- **Android 11 or newer** (best on Android 12+; on Android 11 the screen must be unlocked during a call).
- **Wireless Debugging** available in Developer Options.
- That's it — no PC, no root, no Shizuku app.

> [!IMPORTANT]
> CallVault relies on hidden internal Android APIs and scrcpy-server, so it can break on new Android releases or specific OEM builds. Behavior is **non-deterministic** across devices — read the Disclaimer below.

---

## Install

1. Download the latest **`CallVault.apk`** from the [**Releases**](https://github.com/madkongo/CallVault/releases) page.
2. Open it and allow installing from unknown sources if prompted.

> CallVault is sideloaded only — it **cannot** be on the Google Play Store (Play prohibits both call recording and the embedded-ADB privilege mechanism it depends on).

## How to use

**One-time setup (in-app):**

1. **Enable Wireless Debugging** on your phone: *Settings → System → Developer options → Wireless debugging → On.*
   (If Developer options aren't visible: *Settings → About phone → tap "Build number" 7 times.*)
2. **Open CallVault** and accept the disclaimer.
3. On the **Permissions** screen, grant **Notifications**, then tap **Authorize** (Wireless debugging / ADB).
4. A **pairing notification** appears. On your phone open *Wireless debugging → **Pair device with pairing code***, then type that **6-digit code** into the notification's reply field and send it. (CallVault finds the port automatically.)
5. After a few seconds you'll get a **"Paired ✓ — tap to continue"** notification → **tap it** to return to CallVault.
6. Grant the remaining permissions (**Contacts, Phone State, Call Log, Battery optimization**).
7. Complete the **Setup Wizard**: where to save recordings (device / cloud folder / both), sync schedule, auto-record incoming/outgoing, audio quality, and file-name format.

**Day-to-day:**

- The **Home** screen shows app status and your recordings — tap one to **play** it, or delete it. **Settings** is reachable from the button on Home.
- Calls are recorded **automatically** per your auto-record rules. Saved files use a [BCR](https://github.com/chenxiaolong/BCR)-compatible name format.

> [!TIP]
> On OEMs that aggressively kill background apps (OnePlus/OxygenOS, Xiaomi, etc.), allow CallVault in **Auto-launch / Startup Manager** and exclude it from **battery optimization** so it can record reliably and start after a reboot. See [dontkillmyapp.com](https://dontkillmyapp.com/).

## Building from source

The Android project is under [`app-src/`](./app-src). See [BUILDING.md](./BUILDING.md). In short:

```bash
cd app-src
./gradlew :app:assembleDebug   # outputs app/build/outputs/apk/debug/app-debug.apk
```

Requires a JDK 17 and the Android SDK. CallVault is **reflection-heavy** (hidden APIs, the daemon is launched by class name) — a minified release build will break it unless minification is disabled.

## Attribution

CallVault is a modified fork of **ShizuCallRecorder**, available at <https://github.com/kitsumed/ShizuCallRecorder>. The original project, its name, trademarks, and logos are the property of **kitsumed (Med)** and are used here only for this required attribution.

Built on: [ShizuCallRecorder](https://github.com/kitsumed/ShizuCallRecorder) (upstream), [scrcpy](https://github.com/genymobile/scrcpy), [libadb-android](https://github.com/MuntashirAkon/libadb-android).

## License

Licensed under the [GNU General Public License v3.0](./LICENSE). ⚠️ **Additional Terms** under GPLv3 Section 7 apply (at the end of the license file), including trademark protection and the mandatory fork-attribution requirements that this project complies with.

## Disclaimer

**Recording phone calls may be subject to complex and varying laws in different countries and jurisdictions.** You may need consent from all parties before recording. The developers and contributors are **not responsible** for any misuse or legal consequences. Learn more: [Telephone call recording laws](https://en.wikipedia.org/wiki/Telephone_call_recording_laws). **This is not legal advice** — consult a legal professional for your situation.

It is **your responsibility** to verify that CallVault's behavior on your device complies with your local laws, and to stop immediately any activity that would constitute a legal infraction.
