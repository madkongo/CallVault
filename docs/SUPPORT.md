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
