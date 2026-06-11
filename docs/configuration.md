[Go back to the home documentation page](./SUPPORT.md)
# Configuration Guide

> [!NOTE]
> This guide assumes you have read the [disclaimer](../README.md#disclaimer) and are complying with the law in your jurisdiction.

## Why call recording needs privileged access

Since Android 6 (and tightened ever since), third-party call recording through normal APIs has been [deliberately removed](https://issuetracker.google.com/issues/37127141) for [privacy reasons](https://issuetracker.google.com/issues/137210607#comment8). The `ACCESS_CALL_AUDIO` permission introduced in an Android 11 dev build was [quickly reverted](https://issuetracker.google.com/issues/158923887). Meanwhile, the privileged `CAPTURE_AUDIO_OUTPUT` permission remains reserved for the system dialer and OEM apps.

The shell user (UID 2000, the account ADB runs as) holds an [advanced set of permissions](https://android.googlesource.com/platform/frameworks/base/+/android16-release/packages/Shell/AndroidManifest.xml) that includes what is needed to capture call audio via `scrcpy-server`. CallVault reaches that capability **on-device**, with no PC and no root.

## How CallVault works (no Shizuku)

Unlike the upstream project, CallVault does **not** use Shizuku. Instead it runs an **embedded ADB client** ([libadb-android](https://github.com/MuntashirAkon/libadb-android)) that talks to the device's own **Wireless Debugging** endpoint, and launches a **persistent privileged daemon** (a detached `app_process` under the shell UID) that survives Wireless Debugging being turned off. Recording then flows to the daemon over **binder IPC**.

The result is fully hands-free:

- **One-time pairing.** During onboarding you pair once with Wireless Debugging (via the in-app pairing notification — enter the pairing code and port shown under *Developer Options → Wireless debugging → Pair device with pairing code*).
- **Automatic, transient Wireless Debugging.** After pairing, CallVault turns Wireless Debugging **on only when it needs to (re)launch the daemon, then turns it back off**. You never toggle it manually.
- **Always-on daemon.** The privileged daemon stays available between calls, so recording can start without re-establishing an ADB connection.

## Setup steps

1. Enable **Developer Options**, then **Wireless Debugging**, on your device.
2. Install and open CallVault and follow the onboarding:
   - Grant **notifications** (the pairing prompt is delivered as a notification).
   - Complete the **one-time Wireless Debugging pairing** when prompted.
   - Grant the remaining permissions (phone state, call log, contacts, battery exemption).
3. On OEMs that throttle background startup (e.g. OnePlus/OxygenOS), allow CallVault in **Auto-launch / Startup Manager** so it can come up after a reboot.

That's it — there is no Shizuku app to install and no manual debugging toggle to manage.

> [!NOTE]
> After a reboot there can be a short delay before the daemon is ready (the OS may throttle the app's cold start). The first call placed immediately after a reboot may be missed until the daemon has relaunched.
