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

### vivo and iQOO phones — turn on vivo's "In-app call recording" to record the other person's voice in app calls

On **vivo** and **iQOO** phones (Funtouch OS / OriginOS), app (VoIP) calls — WhatsApp, Telegram, Signal
and the like — may at first record **your side only**: your own voice is captured, and normal carrier
phone calls record both sides, but the **other party's voice in an app call comes out silent**.

The fix is a vivo setting. vivo's audio system blocks apps from capturing internal call audio until you
turn on vivo's own in-app recording feature; once it is on, that block is lifted and CallVault records
both sides of app calls normally.

**How to enable it:**

1. Open the built-in **Recorder** app (vivo's own, called *Recorder*).
2. Open its settings and find **In-app call recording**.
3. Turn on **In-app call recording**, and also turn on **Auto-start recording**.

That's all — you do **not** need your app (WhatsApp/Telegram/etc.) to appear in vivo's "Enable apps" list;
that list is only for vivo's own recorder. Turning the master toggle on is what lets CallVault capture the
other party. After enabling it, place an app call and check that both sides are recorded.

(This was reported by a vivo user and matches how vivo's audio system works; if it does not resolve it on
your vivo/iQOO model, please open an issue and say which model and OriginOS/Funtouch version you are on.)
