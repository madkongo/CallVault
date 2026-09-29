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

### vivo and iQOO phones — the other person's voice in app calls cannot be recorded

On **vivo** and **iQOO** phones (Funtouch OS / OriginOS), app (VoIP) calls — WhatsApp, Telegram, Signal
and the like — record **your side only**. Your own voice is captured, and normal carrier phone calls
record both sides, but the **other party's voice in an app call comes out silent**.

This is not something CallVault can fix, and it is not a bug in your setup. vivo's own audio system
blocks the internal call audio for every app that is not on a vivo-controlled allow-list (that list holds
only a few vivo and streaming apps, and cannot be changed without modifying the phone's system software /
rooting it). The block sits below the level any normal Android app can reach: CallVault's recording is
correctly connected and Android does not report it as blocked, yet vivo replaces the audio with silence.
No third-party app can capture the far side of an app call on a stock vivo/iQOO phone — this was
confirmed against vivo's firmware and matches every other call recorder and screen recorder on these
phones.

After an app call where only your side was captured, CallVault tells you so rather than leaving a
silent recording. If recording the other party matters to you on a vivo/iQOO phone, the only options are
a non-vivo device, or recording carrier phone calls instead of app calls.

If vivo changes this in a future update, or you have information about it, please open an issue.
