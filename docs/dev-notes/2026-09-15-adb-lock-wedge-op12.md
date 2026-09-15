# 2026-09-15 — OP12 stuck on "starting up" for ~6 h: a thread parked inside libadb holds its lock

Status: 🧪 VERIFYING — diagnosis from live device state; fix built and tested off-device (see "Fix" at the
end). Settled when the fix is on the OP12 and the maintainer sees a call after an off-Wi-Fi trip record.

## What the maintainer saw

Build `build/op12-probe-plus-transport-fix` installed 2026-09-14 12:37. The 18:16 call recorded. Went
out (off Wi-Fi), came home, the 23:04 call (12.5 min) did not record. Notification stuck on
"Call recorder starting up…".

## What the phone showed at 01:12 (read over adb, release build so no run-as / thread dump)

- No recorder process. adbd running, USB debugging on, Wireless debugging off, loopback port 51392 armed
  and listening. Wi-Fi connected. So an endpoint existed.
- Every 2 min: `keep-alive: daemon down — relaunching (force=false offline=true)`, then 45 s later
  `relaunch still blocked after 45000ms — abandoning it and dropping the ADB connection`. Never a
  `rebuilding the ADB connection` line, and the notice said STARTING, not STUCK.
- App threads (`/proc/<pid>/task/*/comm`): **134 `cv-keepalive-rewarm` + 134 `cv-keepalive-launch`**
  alive, **4 `cv-shell-probe`** alive, 2 `cv-usbdefault-refresh`.
- The 4 probe threads started 1.9 s apart at **≈19:27** (from `/proc/.../stat` start ticks vs uptime).
  134 × 2 min ≈ the same start. Two app-owned sockets to :51392 sat in CLOSE_WAIT.
- Timeline (from `dumpsys connectivity` / `dumpsys wifi` / call log): Wi-Fi lost ≈18:27; wedge ≈19:27
  (off Wi-Fi); Wi-Fi back 21:59; calls at 21:07 (missed), 22:57 (57 s), 23:04 (755 s) not recorded.

## Mechanism (📐 from libadb-android 3.1.1 source; the exact parked line is inferred, not dumped)

- `AbsAdbConnectionManager.openStream` holds `mLock` and calls `AdbConnection.open`, which sends OPEN
  and then does `synchronized(stream) { stream.wait(); }` — **no timeout and no condition**. If the
  OKAY is lost (notify before wait) or the reader thread has already died and run `cleanupStreams()`
  before this stream was added, nothing ever notifies it. The caller parks forever **holding `mLock`**.
  Upstream master is unchanged; no upstream issue reports it.
- `isConnected()`, `connect()`, `disconnect()` all take the same `mLock`.
- `AdbShell.probeShellOnce` caps the probe at 1.5 s and rescues by closing the stream — but the stream
  reference is only set after `open` returns, so there is nothing to close. The thread stays parked.
- Every later relaunch worker blocks on `mLock` (`isConnected`). The keep-alive's rescue,
  `AdbShell.dropConnection` → `disconnect()`, **also blocks on `mLock`** — so each rewarm thread parks
  forever right after logging "abandoning it", never reaches `onAttemptFailed()`, the failure streak
  never grows, `REBUILD_CONNECTION` is never chosen, and the notice never turns to STUCK. The gate's
  expiry is the only thing that starts the next (equally doomed) attempt.

This is the same log signature as the 2026-08-18 wedge (see `DaemonRecoveryPolicy`); the escalation
added then could never run.

⚠️ CORRECTION 2026-09-15: an earlier version of this note said the build's debugging-switch changes
were "probably not" the cause. That was not investigated, only asserted. The maintainer has run the
last five releases (and the stereo-probe-only diag build for two days on this phone) without this ever
happening, so this build is the prime suspect. The follow-up sweep is
`2026-09-15-wedge-regression-and-app-flow-map.md`. Wi-Fi loss itself did not start it — the wedge
began about an hour after Wi-Fi was lost.

## Fix direction (as first proposed; what was built is under "Fix" below — the singleton swap was not needed)

1. Interrupt, don't just abandon: every bounded ADB worker (`probeShellOnce`, `connectBounded`,
   `launchDaemonBounded`, `refreshUsbDefaultBounded`, `armFireThread`) interrupts its thread on timeout.
   `Object.wait()` throws `InterruptedException`, so `open` unwinds and `mLock` is released.
2. `dropConnection` must never block: run `disconnect()` bounded; if it cannot get the lock, replace the
   `AdbConnectionManager` singleton with a fresh one (new lock) and move on.
3. A test that reproduces it: a fake local ADB server that answers CNXN and never answers OPEN.

## Immediate relief

Force-stop and reopen CallVault (clears the parked threads).

## Fix — 🧪 VERIFYING (branch `fix/adb-open-lock-wedge`, 2026-09-15)

Settled when the OP12 runs this build through normal use, including leaving and returning to Wi-Fi, with
no "starting up" that does not clear. Not merged, not pushed.

Commits: c9474e3, db88601, b6ad393, f981de7, 7862be3. OP12 test APK: branch `build/op12-probe-plus-wedge-fix`
(fix + stereo probe).

1. `AdbConnectionManager` overrides both `openStream` overloads: an open that adbd does not answer within 5 s
   of waiting (time queued for the lock does not count) interrupts the caller, which unwinds libadb out of its
   lock; the connection is dropped before the failure is reported.
2. Keep-alive records a stuck relaunch as failed BEFORE the rescue; `dropConnection` is bounded; the stuck
   worker's stack is logged ("It is parked at:"); abandoned probe/connect/USB-default threads are interrupted.
3. New-build risks from the sweep: Shizuku death no longer drops the built-in recorder; the notification's
   "Turn Wireless debugging on" finishes its broadcast at once; CallVault's own WD write no longer forces a
   second relaunch; a "switched off by hand" is taken back after 3 s if Wi-Fi turns out to have gone.

Evidence so far:
- `AdbOpenLockWedgeTest` (fake adbd that never answers OPEN): RED before (open parked, disconnect blocked;
  a queued second open failed 516 ms after its OPEN), GREEN after, 3 reruns. Unit suite 1338/0.
- OP9: install-over; recorder back 5 s after WD on; stale daemon from the day before killed. Wi-Fi off →
  Android "Disabling adbwifi" +0.18 s → classified USER → recheck took it back at +3 s (📐→ measured once).
- Emulator (loopback armed, the incident config minus Wi-Fi): 20 cycles of kill recorder + kill adbd at
  0.3–3 s offsets; 20/20 recovered in 0–5 s, no leftover ADB threads. The 5 s open bound never fired — the
  lost-wakeup race was NOT reproduced on a device; only the unit test exercises it.

Not done (fix plan steps 4–6 of the sweep): bounded call-start lock, fewer stream opens on the fast path,
"can't record" by down-duration. Open finding: on the OP9 an install-over with no ADB transport (WD
respected off, no loopback) could not kill the previous install's recorder, which kept running old code
until WD came back.
