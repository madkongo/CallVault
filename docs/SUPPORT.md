# CallVault — Documentation & Support

This is the main documentation page. It covers installation, configuration, and support.

> CallVault is a free, FOSS project and a fork of [ShizuCallRecorder](https://github.com/kitsumed/ShizuCallRecorder). It may work on your device, or it may not — Android call recording depends heavily on OEM and OS specifics.

## Installation & Configuration

CallVault is sideloaded (GitHub Releases / Obtainium; F-Droid intended) — it is **not** on the Google Play Store. All setup happens **in-app** during onboarding; there is nothing to configure from a PC. See the [configuration guide](./configuration.md) for how it works and what to expect.

> [!TIP]
> CallVault only saves recording files. If you want a visual interface to browse files and see contact names, you can use an app like [bcr-gui](https://github.com/nicorac/bcr-gui) (unrelated to this project) — CallVault replicates the [BCR](https://github.com/chenxiaolong/BCR) file-name format.

## Issues, bugs, and support

If CallVault is getting killed in the background or not starting when a call comes in, check [dontkillmyapp](https://dontkillmyapp.com/) for OEM-specific instructions, and make sure the app is excluded from battery optimization. On some OEMs (e.g. OnePlus/OxygenOS) you must also allow the app in **Auto-launch / Startup Manager** so it can start after boot.

For a reproducible bug — a crash, wrong behavior, or unexpected error — please open an issue with detailed steps, logs (Settings → generate report), and your device/Android version.

## Transcribing with your own key (cloud)

By default calls are transcribed **on the phone** and nothing is sent anywhere. In **Settings → Where to
transcribe** you can instead pick **Groq** (recommended), **OpenRouter** or **Custom (advanced)** — any
OpenAI-compatible server that returns `verbose_json` with segment timings — and paste your own key from that
service. Press **Test** to check it.

- When a cloud service is chosen, the **call audio is sent to that service** to be written out (as one file for
  ordinary calls; very large files in pieces); the words come back to the phone. That service's own privacy terms apply. CallVault
  asks before the first use.
- Calls are sent over **Wi-Fi only** unless you turn on **Also use mobile data**.
- Summaries still run on the phone.
- The key is stored encrypted on the phone and is never included in the settings export or in a bug report.
- "The key was refused": the key is wrong or revoked — paste it again. "Answered without timings": that
  model cannot be used, because CallVault needs the timings to line words up with the call; pick another.
- A transcription that fails because the service is busy or the network dropped is retried by itself a few
  times before it is marked failed.

## Device-specific limitations

### vivo and iQOO phones — app calls may record your side only

On **vivo** and **iQOO** phones, app (VoIP) calls — WhatsApp, Telegram, Signal and the like — can record
**your side only**: the other person's voice comes out silent. vivo's audio system blocks apps from
recording it unless vivo's own **In-app call recording** option is turned on. Normal carrier phone calls
are not affected: they record both sides.

**That option exists only on China-market vivo/iQOO firmware** (models whose number ends in "A", such as
V2507A, running OriginOS 4 or later). Overseas and global phones (Funtouch OS or global OriginOS — for
example India, Bangladesh, Europe) do not have it at all. On those phones the other person in app calls
**cannot be recorded by any app without root**; there is no setting, code or region change that adds it.

**On a China-firmware phone, turn it on like this:**

1. Open vivo's built-in **Recorder** app.
2. Open its settings and find **In-app call recording**.
3. Turn on **In-app call recording**, and also **Auto-start recording**.

You do **not** need to add WhatsApp/Telegram/etc. to vivo's "Enable apps" list. After turning it on, make
an app call and check that both sides are recorded.

**If your Recorder settings show no "In-app call recording"** (often just *About* and *Check for updates*),
your phone has overseas firmware and the other side of app calls cannot be recorded. Your own voice, and
normal phone calls, still record. The dialer codes (e.g. `*#*#556688#*#*`) and "change region" guides online
turn on the dialer's recording of *carrier* calls — which CallVault already does — not this option.

To check which kind of firmware you have, over ADB: `adb shell getprop persist.vivo.audio.feature.voip_bbkrecord.enable`
prints `true` when the option exists. If yours says otherwise but you see the option, please open an issue
with your model and OS version.
