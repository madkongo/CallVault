# CallVault Unified — Design Spec (Option B)

**Date:** 2026-06-09
**Status:** Approved (design); pending spec review
**Author:** kfir + Claude
**Supersedes scope of:** the "Cloud upload" non-goal in
`2026-06-09-callvault-fork-design.md`; extends that doc's Phase 2 vision
(PairingStore / AdbConnector / BootBridge) into the primary architecture.

## Summary

Evolve **CallVault** (our GPL-3.0 fork of
[`kitsumed/ShizuCallRecorder`](https://github.com/kitsumed/ShizuCallRecorder))
into a **single, self-contained app** that no longer depends on the external
**Shizuku** app. CallVault becomes its **own privileged-shell provider** over
Android **Wireless debugging (ADB)**, runs the existing call-recording pipeline
through that shell, recovers **hands-free after a reboot**, and routes finished
recordings to **Local / Google Drive / Both** via a user-selectable setting.

The recorder's UI, codecs, call detection, auto-record rules, and scrcpy audio
parsing are **kept as-is**. What changes: the *privilege transport* (Shizuku →
embedded ADB) is replaced, and a *storage-routing* feature is added.

## Motivation

- Today recording requires **two apps** (Shizuku + the recorder) and a manual
  Shizuku restart after every reboot.
- Saving directly to a Google Drive (SAF cloud) folder is impossible because the
  audio muxer needs a seekable `rw` file descriptor, which cloud
  DocumentsProviders do not provide. The failure currently surfaces as a
  **misleading "Shizuku failed to start"** error (root cause: an exception from
  `openFileDescriptor(uri, "rw")` in `SafHelper.createAudioFile` escapes into a
  generic catch in `RecordingForegroundService` that blames Shizuku).
- Goal: one app that bootstraps and keeps its own shell alive, records both
  sides of a call, and reliably gets recordings into Google Drive.

## Goals

- **One app**, no Shizuku install required.
- CallVault owns the ADB **pairing + connect + keep-alive** lifecycle.
- Both-sides call recording on **OnePlus 12 (CPH2581), OxygenOS 16, Android 16**.
- **Hands-free across reboots**: with Android's *Wireless debugging* left ON, a
  call is auto-recorded after a full reboot with **zero taps**.
- Recordings route to **Local only / Drive only / Both**, user-selectable, with
  verify-before-delete and automatic retry.
- Honest error reporting (no mislabeled failures).

## Non-Goals

- Root, unlocked bootloader, or modified firmware.
- Google Drive **REST API / OAuth** integration (we use the SAF folder picker
  instead — no Google sign-in, no GCP project).
- Third-party / VoIP call recording — carrier calls only, matching upstream.
- Transcription, multi-account Drive, or arbitrary cloud providers (Drive via
  SAF only for MVP; other SAF providers may work incidentally).

## Target Environment

| Item | Value |
|------|-------|
| Device | OnePlus 12, model CPH2581 (Global/ROW) |
| OS | OxygenOS 16 / Android 16 |
| Root | No (non-root, bootloader locked) |
| Privilege source | **Embedded ADB over Wireless debugging** (no Shizuku) |
| Cloud target | Google Drive via **SAF** (system folder picker) |
| Dev host | macOS (Apple Silicon), Android SDK `~/Library/Android/sdk`, `adb` via Homebrew, JDK 17 (brew openjdk@17) |
| Dev connection | Wireless adb (Wi-Fi) |

## Licensing

All components are GPL-3.0-compatible:

- **CallVault** stays **GPL-3.0** (retains upstream `LICENSE` + copyright, keeps
  the `CallVault` / `com.kfir.callvault` rebrand from the prior spec).
- **LADB** (in-app ADB over wireless debugging) — GPL-3.0. Used as the proven
  reference / fallback for the pairing+transport piece.
- **dadb** (Cash App / mobile.dev pure-Kotlin ADB client) — Apache-2.0.
- **scrcpy-server** — Apache-2.0 (already vendored at build time by upstream).

## Architecture

### Reused unchanged (from the CallVault fork)
Call detection (`PhoneStateReceiver`, `CallSessionManager`), `AudioRecordingEngine`,
`ScrcpyClient` + codec/`ScrcpyAudioMuxer` parsing, `RecordingNotificationHelper`,
settings UI scaffolding, and SAF file creation (`SafHelper`).

### Replaced
The entire Shizuku layer:
- `integrations/shizuku/ShizukuConnectionManager.kt`
- `services/ShellService.kt` + `IShellService.aidl` (Shizuku UserService binder)
- Shizuku auth-key / auto-manage settings

…are replaced by a new **AdbShell** layer.

### New components

1. **AdbPairing / AdbConnector**
   - One-time in-app **Wireless debugging pairing** (Android-11 PIN flow), guiding
     the user through Settings → Developer options → Wireless debugging → Pair.
   - **PairingStore**: persists the ADB TLS keypair so future connections (incl.
     post-reboot) need no re-pairing.
   - On demand: discover the current `_adb-tls-connect._tcp` port via **mDNS**
     (the connect port is randomized each boot) and open a TLS connection to
     `127.0.0.1`.

2. **ScrcpyLauncher** (replacement for `IShellService.startRecording()`)
   - Over the ADB shell: push `scrcpy-server.jar` to `/data/local/tmp`, launch it
     via `app_process` audio-only (`audio=true video=false control=false
     tunnel_forward=true`), and forward its audio socket back into the app.
   - Feeds the **existing** `ScrcpyClient` an `InputStream` from the forwarded
     socket instead of the old `ParcelFileDescriptor` pipe.

3. **BootBridge**
   - `BOOT_COMPLETED` receiver → short **foreground service** → `AdbConnector`
     reconnects the shell → hands off to the recording pipeline.
   - Onboarding for **battery-optimization exemption** + **OnePlus auto-launch**
     whitelisting; a **watchdog** that re-establishes the shell if it drops.
   - **Fallback:** a **Quick-Settings tile / notification action** that
     re-establishes the shell in one tap, used if OOS16 kills the auto path.

4. **StorageRouter**
   - Runs after a recording finalizes in the local working folder.
   - Applies the **Storage Target** setting; copies to the Drive SAF folder with a
     **write-only** stream; verifies (byte size) the Drive copy; deletes the local
     temp file only when safe; enqueues a **WorkManager** retry when a destination
     is unreachable.

### Recording always writes locally first
The muxer requires a seekable `rw` descriptor, so every recording is written to a
**local working folder** first. Google Drive is **only ever a post-finalize
copy** (write-only stream, which cloud providers support). This is also the
correct fix for the original mislabeled-error bug.

## Data Flow

```
Boot (Wireless debugging ON)
  └─ BOOT_COMPLETED → FGS → AdbConnector (stored key + mDNS port) → shell ready
                                                   │  (watchdog keeps it alive)
Incoming/Outgoing call detected
  └─ RecordingForegroundService
       └─ ScrcpyLauncher (over ADB shell) → audio socket
            └─ ScrcpyClient → ScrcpyAudioMuxer → LOCAL working file (rw)
Call ends → finalize local file
  └─ StorageRouter (per Storage Target)
       ├─ Local only  → keep local
       ├─ Drive only  → copy(write-only) → verify → delete local
       └─ Both        → copy(write-only) → verify → keep local
            └─ on failure → WorkManager retry queue (next connectivity/boot)
```

## Settings (new / changed)

- **Storage Target**: `Local only` · `Drive only` · `Both`.
- **Local working folder** (SAF tree URI — must be a real local provider).
- **Drive (permanent) folder** (SAF tree URI — the Google Drive folder).
- **Wireless-debugging setup / status** (pairing entry point, connection state,
  battery/auto-launch onboarding) — replaces the old Shizuku settings.
- Removed: Shizuku auto-manage / auth-key fields.

## Error Handling

- Distinct, honest messages for: not paired, pairing failed, shell connect
  failed (mDNS/port), scrcpy launch failed, local write failed, Drive copy
  failed (queued for retry). No more generic "Shizuku failed to start" catch-all.
- StorageRouter never deletes a local file until the Drive copy is verified.
- Connection drops trigger the watchdog; persistent failure falls back to the
  one-tap tile and a clear notification.

## Testing

- **Unit:** StorageRouter routing matrix (Local/Drive/Both × success/failure),
  verify-before-delete, retry-queue behavior; mDNS port parsing; pairing-store
  persistence.
- **Integration:** ScrcpyLauncher over a live ADB shell (push + launch + socket
  read) on the target device; SAF write-only copy into a real Drive folder.
- **E2E (device):** the MVP exit criterion below, exercised on the OnePlus 12.

## Risks & De-risking

1. **Hands-free boot on OxygenOS 16** — the primary unknown; OEM may kill the
   boot receiver / FGS. Mitigations: FGS + battery/auto-launch onboarding +
   watchdog; pre-agreed **QS-tile one-tap fallback**.
2. **ADB pairing/transport library** — must perform the Android-11 PIN pairing
   handshake. **De-risk FIRST** with a throwaway spike that pairs and runs one
   shell command on the device. If a pure-Kotlin client (dadb) cannot pair, fall
   back to **LADB's approach** (bundle the `adb` binary, run it in-app against
   localhost).
3. **scrcpy over forwarded socket on this OEM build** — upstream warns OEM
   builds can break capture; validate audio capture early on-device.

## Implementation Order (high level)

1. **Spike:** in-app ADB pair + connect + `shell echo` on the OnePlus 12.
2. AdbShell layer (AdbPairing/Connector/PairingStore) + mDNS discovery.
3. ScrcpyLauncher; rewire `ScrcpyClient` to the forwarded socket; remove Shizuku.
4. Recording validated on-device (manual connect).
5. StorageRouter + Storage Target setting + retry queue.
6. BootBridge + watchdog + whitelisting onboarding + QS-tile fallback.
7. Full hands-free reboot validation on-device.

## MVP Exit Criterion

On the OnePlus 12 / OxygenOS 16: after a **full reboot with zero taps** (Wireless
debugging left ON), an **incoming and outgoing** call is auto-recorded with
**both sides audible**, and each file lands in the configured target(s) with the
Drive copy **verified**.
