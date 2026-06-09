# CallVault — Design Spec

**Date:** 2026-06-09
**Status:** Approved (design); pending spec review
**Author:** kfir + Claude

## Summary

Fork the FOSS app [`kitsumed/ShizuCallRecorder`](https://github.com/kitsumed/ShizuCallRecorder)
(Kotlin, GPL-3.0) into a renamed app, **CallVault**, and extend it so call
recording survives reboots with **zero manual steps** on a non-rooted
OnePlus 12 running OxygenOS 16 (Android 16).

The upstream app already records both sides of a call by wrapping
**scrcpy-server**'s audio capture through the shell-level permissions that
**Shizuku** exposes. It also already supports automatic call recording with
exclusion rules and Opus/AAC output. Its one operational gap for our use case:
Shizuku (and therefore the recorder) does **not** auto-start after a reboot —
the user must re-run the wireless-debugging → Start dance each boot. CallVault
closes that gap.

## Goals

- A free, FOSS replacement for the paid SKVALEX recorder.
- Both-sides call recording confirmed working on **OnePlus 12 (CPH2581),
  OxygenOS 16, Android 16**.
- After Phase 2, recording works **hands-free across reboots**, with the only
  standing requirement being that Android's **Wireless debugging** toggle stays
  ON.

## Non-Goals

- Root, unlocked bootloader, or modified firmware (explicitly avoided).
- Restoring the OnePlus stock dialer / OnePlus Messages (that needs root; out of
  scope).
- Third-party app (VoIP) recording — carrier calls only, matching upstream.
- Cloud upload / transcription (possible later; not in this spec).

## Target Environment

| Item | Value |
|------|-------|
| Device | OnePlus 12, model CPH2581 (Global/ROW) |
| OS | OxygenOS 16 / Android 16 |
| Root | No (non-root, bootloader locked) |
| Privilege source | Shizuku (already configured and working) |
| Dev host | macOS (Apple Silicon), Android SDK at `~/Library/Android/sdk`, `adb` via Homebrew, **no JDK yet** |
| Dev connection | Wireless adb (Wi-Fi), same channel used for Shizuku |

## Licensing Constraint

Upstream is **GPL-3.0 with Section 7 additional terms**: the name
`ShizuCallRecorder`, the package `com.kitsumed.shizucallrecorder`, and the logos
are the copyright holder's property and may not be reused. Therefore CallVault
**must**:

- Rename the application to **CallVault**.
- Rename the package to **`com.kfir.callvault`**.
- Replace/remove upstream branding (name strings, app icon).
- Remain GPL-3.0 and retain the upstream `LICENSE` and copyright notices.

## Phased Approach

### Phase 1 — MVP (prove recording on the real device)

Build a behavior-unchanged fork (only renamed) and validate the actual
recording pipeline on the OnePlus 12 before investing in automation, because
upstream explicitly warns that OEM builds can break scrcpy capture
non-deterministically.

Steps:
1. Install JDK 17 on the Mac (Homebrew).
2. Clone the fork into this repo; confirm it builds via the Gradle wrapper
   (mirroring the upstream `.github/workflows/build-app.yml`).
3. Rename package/app to CallVault (`com.kfir.callvault`).
4. Build a debug APK.
5. Install over wireless adb; complete the app's one-time Shizuku setup.
6. Place test calls (incoming + outgoing) and confirm **both sides** are
   captured cleanly. Use `adb logcat` to diagnose.
7. If audio is one-sided or fails: try the recommended **thedjchi** Shizuku
   fork, and adjust the capture configuration. Record findings.

**Exit criterion:** a saved recording with both parties audible on the OnePlus
12 / OOS16.

### Phase 2 — Reboot-survival automation

Add an embedded ADB-over-wireless-debugging self-start so the shell channel is
re-established automatically on every boot, removing the manual Shizuku restart.

New components:

1. **PairingStore** — persists the adb TLS keypair generated during a one-time
   in-app wireless-debugging pairing, so future boots reconnect without
   re-pairing.
2. **AdbConnector** — on demand, discovers the current adb-tls port via **mDNS**
   (`_adb-tls-connect._tcp` advertised on localhost — solves the per-reboot port
   randomization) and opens a TLS connection using the stored keypair.
3. **BootBridge** — a `BOOT_COMPLETED` receiver that starts a brief foreground
   service, invokes `AdbConnector` to bring the shell up, then hands off to the
   existing recording pipeline and stops.
4. **Keepalive / setup flow** — guides the user to grant battery-optimization
   exemption and OnePlus auto-launch whitelisting so OxygenOS 16 does not kill
   the receiver/service; includes a watchdog that re-establishes the shell if
   the connection drops.

**Exit criterion:** after a full reboot with no user interaction (Wireless
debugging left ON), an incoming/outgoing call is auto-recorded with both sides.

### Phase 2 fallback

If the embedded-ADB self-start proves unreliable on OOS16, fall back to a
**Quick Settings tile / notification action** that re-establishes the shell in a
single tap per reboot. (Captured here so the fallback is pre-agreed; not built
unless needed.)

## Data Flow

```
Boot
  └─ BootBridge (BOOT_COMPLETED)
       └─ Foreground service (brief)
            └─ AdbConnector
                 ├─ mDNS: discover _adb-tls-connect._tcp port
                 └─ TLS connect using PairingStore keypair  ──► shell ready
                                                                   │
Phone call event (existing phone-state listener)                   │
  └─ auto-record rules pass ──► scrcpy-server audio capture ◄──────┘
       └─ Opus/AAC encode ──► file saved (number, direction, timestamp)
```

## Architecture Boundaries

- **Recording core** (upstream, reused as-is where possible): call detection,
  scrcpy capture, encoding, storage, settings. Treated as a black box with the
  existing interfaces; we change branding, not internals, in Phase 1.
- **Automation layer** (new, Phase 2): PairingStore, AdbConnector, BootBridge,
  Keepalive. Each is independently testable: PairingStore (persist/load keys),
  AdbConnector (find port + connect, mockable transport), BootBridge (receiver
  wiring), Keepalive (OS-exemption prompts). The automation layer depends on the
  recording core only through "ensure shell is available, then trigger the
  existing pipeline."

## Build & Test Loop

- **Toolchain:** JDK 17 (Homebrew `openjdk@17`), existing Android SDK, Gradle
  wrapper from the repo.
- **Install:** `adb connect <phone-ip:port>` then `adb install -r app-debug.apk`.
- **Diagnose:** `adb logcat` filtered to the app + telephony.
- **Manual test matrix:** outgoing call, incoming call, both-sides audibility,
  Bluetooth headset; Phase 2 adds: reboot → call → confirm hands-free record.

## Risks & Mitigations

| Risk | Mitigation |
|------|-----------|
| OnePlus OOS16 OEM build breaks scrcpy capture | Phase-1 device test before automation work |
| Stock Shizuku incompatible vs recommended thedjchi fork | Swap to thedjchi fork if capture fails |
| Wireless debugging gets turned off → no auto-start | Keepalive setup + QS-tile fallback to re-arm in one tap |
| adb-tls port randomization across reboots | mDNS discovery instead of hardcoded port |
| Android 16 ADB changes / future OS updates | Pin scrcpy-server + Shizuku versions known to work; document |
| GPL-3.0 Section 7 (name/package/logo) | Mandatory rename to CallVault / `com.kfir.callvault`; keep license |

## Open Questions

- Does stock Shizuku work, or is the thedjchi fork required on OOS16? (Resolved
  empirically in Phase 1.)
- Exact scrcpy-server version bundled by upstream vs. what OOS16 accepts.
  (Checked during Phase 1 build.)
