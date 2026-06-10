# Plan 5 — build status & handoff (2026-06-10)

Branch `feat/callvault-adb-spike`. The persistent privileged recorder server (Shizuku-style) so
Wireless debugging (WD) can stay OFF between calls.

## Commit trail (newest first)
- `1f61f0a` fix(plan5): code-review fixes + delete throwaway spike harness
- `c745711` feat(plan5): settings toggles (persistent server + WD policy) — Task 5
- `ee53d4d` feat(plan5): harden launcher + boot launch + WD policy — Task 5
- `8e1872d` feat(plan5): keep WD off in daemon mode + on-device validation PASS
- `c2fd825` feat(plan5): gated persistent-server recording path — Task 4
- `7570a04` feat(plan5): production RecorderServer + binder subsystem — Tasks 1-3
- `2f79752` spike(plan5): Task 0 GREEN — all 4 risks proven

## ✅ Validated ON-DEVICE (OnePlus 12 / OxygenOS 16 / Android 16)
- Task 0 spike: all 4 risks (daemonization survives WD-off, audio from detached daemon, shell→app
  binder delivery, app→daemon IPC + pfd write).
- Production daemon path, **WD ON**: real call → 440 KB SAF `.ogg`, clean finalize, daemon persists.
- Production daemon path, **WD OFF the whole call**: real call recorded hands-free, WD never
  re-enabled (gating fix), daemon survived, 404 KB SAF `.ogg` (Opus stereo 48k, 25.9 s, 125 kbps).
  This is the Plan 5 payoff. NOTE: in both runs the daemon was launched via **laptop adb** because
  the in-app launcher was flaky (see gap #1).

## ⚠️ NOT yet validated on-device (test these when the phone is back)
The phone was unavailable for the rest of the session; everything below compiles but is unproven.

1. **In-app launcher reliability (THE critical gap).** The embedded-ADB launch failed with
   `Stream closed`; I launched the daemon from laptop adb for the validated tests. `RecorderServerLauncher`
   was then **hardened** (commit `ee53d4d`): up to 3 attempts, `AdbShell.forceReconnect` (disconnect +
   reconnect) between attempts, and "Stream closed" during the detached-`&` drain is treated as expected
   (binder arrival is the real success signal). **Unproven.** First thing to test:
   - With `isPersistentServerEnabled` on, no daemon running, WD on, place a call. STANDBY calls
     `RecorderServerLauncher.ensureServerRunning` → should launch the daemon in-app and record.
     Watch logcat `SCR:RecorderLauncher` for the retry/connect lines.
2. **Boot path** (`AdbConnectionService`, commit `ee53d4d`): reboot → on boot the service reconnects
   ADB and, in daemon mode, launches `RecorderServer`. Test: reboot → place a call → records hands-free.
3. **WD policy "turn off when idle"** (`AppPreferences.isWdDisableWhenIdle`, default off): when on, the
   launcher disables WD once the binder is connected and re-enables it transiently (forceReconnect) to
   relaunch. Test: enable both toggles → daemon connects → confirm WD flips OFF automatically → place a
   call (records) → reboot → confirm boot re-enables WD transiently, launches daemon, then WD off again.
4. **Settings UI** (`c745711`): two switches in Settings — "Persistent recorder server" and
   "Turn off Wireless debugging when idle" (greyed unless the first is on). Replaces the old debug
   broadcasts (now deleted). Test: toggles persist + drive the behaviour.

## Known risks / recommended next steps
- **No local fallback.** In daemon mode, if `RecorderServerLauncher.ensureServerRunning` fails, the
  recording FAILS (no automatic fall back to the proven local ADB path). If the hardened launcher is
  still flaky on-device, the highest-value next change is: in `AudioRecordingEngine.startPipeline`, on
  daemon-start failure, fall back to the local path (reuse the already-created SAF pfd; `ensureConnected`
  first). That makes daemon mode safe to leave on (worst case = records locally with WD on). Deferred
  because it changes the validated recording flow and needs on-device testing.
- The `state` flag `isCurrentlyRecording` was fixed for daemon mode (added `daemonRecording`); confirm
  the recording notification still behaves correctly in daemon mode.
- Pause/resume is a no-op in daemon mode (the daemon writes straight to the pfd). Acceptable v1 gap.

## Current device/app state (left for you)
- `isPersistentServerEnabled` = **ON** (you chose to keep it on). The old debug toggle broadcast was
  deleted; use the Settings switch now. The build on the phone is from BEFORE the harness deletion +
  review fixes — **reinstall** (`./gradlew :app:installDebug`) to get `1f61f0a`.
- WD policy defaults to OFF (WD stays on) — flip the second Settings switch to get auto-WD-off.
- After reinstall the app process restarts, so any running daemon's binder is stale; the next call's
  STANDBY relaunches it (this exercises gap #1 — the launcher).

## Architecture recap (for the reviewer)
App creates the SAF file + `ParcelFileDescriptor` (it holds the SAF grant) and passes the pfd to the
daemon over binder. The shell-uid daemon (`RecorderServer`, launched once via ADB, survives WD-off)
runs scrcpy as a child and muxes into that pfd. Commands flow over binder (no ADB at record time).
`RecorderConnection` holds the binder; `RecorderBinderProvider` (exported) receives it via
`getContentProviderExternal` (Shizuku's mechanism). Findings: `2026-06-10-plan5-task0-spike-findings.md`.
