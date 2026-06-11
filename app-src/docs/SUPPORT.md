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
