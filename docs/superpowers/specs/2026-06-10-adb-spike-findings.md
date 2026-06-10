# Embedded-ADB Spike — Findings & Go/No-Go

**Date:** 2026-06-10
**Plan:** `docs/superpowers/plans/2026-06-09-callvault-unified-spike.md`
**Device:** OnePlus 12 (CPH2581), OxygenOS 16 / Android 16, non-root
**Branch:** `feat/callvault-adb-spike`

## Decision: 🟢 GREEN — proceed to Plan 2

CallVault can act as its **own privileged-shell provider** over Android Wireless
debugging — no Shizuku, no root — on the target device. Every gate in the spike
exit criterion passed on real hardware.

## What was validated (on-device evidence)

| Check | Result |
|-------|--------|
| In-app pairing (Shizuku-style notification, code-only) | ✅ `Paired ✓` |
| mDNS discovery of pairing + connect ports | ✅ ports found automatically |
| TLS pairing handshake | ✅ after bundling Conscrypt |
| Authenticated connect with persisted key | ✅ `connect(127.0.0.1:34311) → true` |
| Shell identity | ✅ `uid=2000(shell) gid=2000(shell) … context=u:r:shell:s0` |
| Long-lived forwarded stream | ✅ `cat /proc/uptime` streamed back |
| **Reboot → reconnect, no re-pairing** | ✅ new port `39261` rediscovered, key reconnected, `uid=2000` again |

`uid=2000(shell)` is the decisive result: it is the exact privilege scrcpy-server
needs to capture call audio, obtained without Shizuku.

## The three fixes that made it work (don't lose these)

1. **mDNS service-type string.** libadb-android's bundled `AdbMdns` exposes the
   bare constants `"adb-tls-pairing"` / `"adb-tls-connect"`, which `NsdManager`
   never matches → discovery found nothing. **Fix:** ported Shizuku's `AdbMdns`
   (Apache-2.0) which uses the full `"_adb-tls-pairing._tcp"` /
   `"_adb-tls-connect._tcp"`. (`SpikeAdbMdns` in `spike/AdbMdns.kt`.)

2. **Discovery must run continuously, not on a button press.** A one-shot
   discovery fired when the user tapped "Pair" missed the service window.
   **Fix:** run discovery in a foreground service (Shizuku's model) that is
   already searching when the pairing dialog appears, and post a notification
   with a `RemoteInput` inline reply so the user only types the 6-digit code.
   (`spike/AdbPairingService.kt`.)

3. **Conscrypt is required for the TLS pairing handshake.** Without it, pairing
   failed with
   `NoSuchMethodException: com.android.org.conscrypt.Conscrypt.exportKeyingMaterial`.
   libadb's `SslUtils` prefers `org.conscrypt.OpenSSLProvider` (TLSv1.3 +
   `exportKeyingMaterial`) and only falls back to the platform Conscrypt, which
   fails on Android 14+/OEM builds. **Fix:**
   `implementation("org.conscrypt:conscrypt-android:2.5.2")`.

## Confirmed library API (libadb-android 3.1.1)

- `AbsAdbConnectionManager.pair(String host, int port, String code): boolean`
- `AbsAdbConnectionManager.connect(String host, int port): boolean`
- `AbsAdbConnectionManager.openStream(String): AdbStream`; `AdbStream.openInputStream(): AdbInputStream`
- `autoConnect(Context, long)` exists but relies on the broken bare-string mDNS — **do not use**; use the ported `AdbMdns` for discovery instead.
- Manager subclass must provide `getPrivateKey()`, `getCertificate()`, `getDeviceName()` (RSA-2048 + self-signed cert, persisted to `filesDir`). (`spike/SpikeAdbManager.kt`.)

## Dependencies added

- `com.github.MuntashirAkon:libadb-android:3.1.1` (JitPack)
- `org.conscrypt:conscrypt-android:2.5.2` (required — see fix #3)
- `compileOnly org.bouncycastle:bcprov-jdk15to18:1.81` (cert generation; runtime-provided by libadb)

## Caveats / open questions for later plans

- **Reboot persistence of Wireless debugging:** in this test WD stayed ON after
  one reboot and the device re-advertised `_adb-tls-connect._tcp` on its own, so
  reconnect was zero-touch. The hands-free-boot guarantee (Plan 4) still needs
  testing across **multiple reboots / cold boot**, since some OxygenOS configs
  disable WD after a reboot. If WD does turn off, that becomes the central
  Plan 4 risk (and the QS-tile fallback applies).
- **scrcpy audio over the forwarded socket is NOT yet proven.** Only a generic
  long-lived shell stream was validated. Plan 2's `ScrcpyLauncher` must push +
  run scrcpy-server and stream the audio socket into the existing `ScrcpyClient`
  — this is the next real integration risk.
- `NsdManager.resolveService` is deprecated (API 34); fine for now, consider
  `registerServiceInfoCallback` later.
- Conscrypt adds native libraries (APK size); acceptable.

## Spike artifacts (on branch `feat/callvault-adb-spike`)

- `spike/SpikeAdbManager.kt` — persisted ADB identity (key+cert)
- `spike/AdbMdns.kt` — ported Shizuku mDNS discovery (+ `discoverPort` helper)
- `spike/AdbPairingService.kt` — foreground pairing service with RemoteInput notification
- `spike/AdbSpikeActivity.kt` — debug driver screen (remove before production)
- Manifest: `AdbSpikeActivity` + `AdbPairingService` entries marked "DEBUG SPIKE: remove before production"

Plan 2 should reuse `SpikeAdbManager` + `AdbMdns` + `AdbPairingService` as the
basis of the production **AdbShell** layer, then build `ScrcpyLauncher` and
remove Shizuku.
