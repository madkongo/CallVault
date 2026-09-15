# OP12 wedge of 2026-09-14: regression hunt and full app flow map

🧪 VERIFYING — this is code reading only. Nothing here is measured on a device, and no fix exists yet.
To settle it, the maintainer needs to export `app_debug.log` from the OP12 (Settings → Save log)
covering 2026-09-14 12:30 → 23:10. Section 1.4 lists the lines to look for.

Companion notes: `2026-09-15-adb-lock-wedge-op12.md` (the mechanism), `2026-09-14-debugging-switches-model.md`,
`2026-07-30-keepalive-rewarm-latch-wedge.md` (the same signature on v1.5.3).

Line numbers refer to `fix/adb-transport-dead-ends` (ed847a1), which matches the installed build
`build/op12-probe-plus-transport-fix` for every non-capture file. App paths are under
`app/src/main/java/com/baba/callvault/`. "libadb" means libadb-android 3.1.1 (`app/build.gradle.kts:420`).

---

## 1. Plain answer

### 1.1 What caused it

- **No code change in the new build explains the wedge.** Seven reviews and eleven adversarial checks
  covered the full `b5e6dc9..fix/adb-transport-dead-ends` diff. None found a new piece of code that runs
  in the OP12's setup (standalone, USB debugging on, Wireless debugging off, off-Wi-Fi recording on,
  loopback armed) and either adds ADB traffic, restarts adbd, kills the daemon, or blocks recovery.
- **The wedge is an old bug in libadb, and it has hit this phone before.** A stream open in libadb can wait
  forever while it holds the connection lock. Every rescue path needs that same lock. The same picture
  (four leaked `cv-shell-probe` threads, a `CLOSE_WAIT` socket to :51392, a parked rewarm thread) was
  recorded on this OP12 on 2026-07-30 with v1.5.3. The same pair of log lines was recorded on 2026-08-18.
  So "never happened in the last 5 releases" is true for what was seen, but the bug itself is older.
- **Why only this build hit it is still open.** It could be a rare race that hit by chance. It could also be
  something outside the code, such as a relaunch at an unlucky moment. Two clean days on the diag build
  cannot rule out a rare race.

### 1.2 How sure

- **Wedge mechanism: high.** Every observed number follows from the code: 4 probe threads 1.9 s apart,
  134 + 134 threads, never "rebuilding", notice stuck on STARTING, calls lost.
- **"Not caused by the new build": medium.** The code shows the new paths are inert in this setup. But why
  the daemon died at ~19:27 is not known, and a few new triggers (below) cannot be ruled out without the log.

### 1.3 Proven by code vs inferred

Proven by code:
- `AbsAdbConnectionManager.openStream` holds `mLock` for the whole of `AdbConnection.open()`
  (libadb AbsAdbConnectionManager.java:410-420). `open()` waits on the stream with no loop, no condition and
  no timeout (AdbConnection.java:515-517).
- `isConnected`, `connect` and `disconnect` all take the same `mLock` (AbsAdbConnectionManager.java:163-166,
  370, 389-397).
- The keep-alive's rescue `AdbShell.dropConnection` → `disconnect()` (AdbShell.kt:349-352) is called from the
  rewarm thread (services/recording/DaemonKeepAliveService.kt:500). `rewarmGate.leave()` and
  `recoveryPolicy.onAttemptFailed()` come only after it returns (DaemonKeepAliveService.kt:454-462). So a
  wedge never grows the failure streak, REBUILD_CONNECTION is never chosen (DaemonRecoveryPolicy.kt:110),
  and the STUCK notice is never shown (ReadinessNotice.kt:79-80).
- Call start takes `AdbShell.heavyOperationLock` with no bound before any cancel check
  (AudioRecordingEngine.kt:446/516 → RecorderBackend.kt:55 → RecorderServerLauncher.kt:141).
- With USB debugging on, the new `reviveAdbdIfStopped` returns NOTHING at once (AdbdRevivalPolicy.kt:72-74,
  AdbShell.kt:196-207). The new early return in `maybeRewarm` (DaemonKeepAliveService.kt:399-403) cannot fire,
  because `ReadinessNotice.of` gives STARTING when USB is on and loopback is armed (ReadinessNotice.kt:73-80).
- `git log v2.3.0..fix/adb-transport-dead-ends` is empty for RecorderServerLauncher, UsbDefaultConfig,
  RecorderConnection, DaemonRecoveryPolicy, RewarmGate and AdbConnectionManager. `git diff v2.3.0 b5e6dc9 -- app/src/main`
  is empty. The stereo probe patch (e15265b) is identical to the diag build's (9114bcd).

Inferred (no stack dump exists):
- Which thread holds `mLock`. The best fit is `cv-shell-probe` #1 of the ~19:27 relaunch. The probe spacing
  (1.5 s cap + 0.4 s gap, AdbShell.kt:59-61, over a 6 s budget, RecorderServerLauncher.kt:105) gives exactly
  4 probes. Probes are only created after `ensureConnected` returned true, and that call takes `mLock`
  (AdbShell.kt:90), so `mLock` was free just before.
- The exact lost wakeup. The variant that never heals is: the reader thread handles OKAY (and for `echo`,
  WRTE + CLSE), or a rejecting CLSE, between `sendPacket(OPEN)` (AdbConnection.java:512) and the opener
  entering `synchronized(stream)` (:515). CLSE removes the stream from `mOpenedStreams` (:272-276), so the
  later `cleanupStreams` (:564-575) cannot wake it either.
- The two `cv-usbdefault-refresh` threads (12:37, 14:41) are a separate libadb read hang
  (AdbStream.java:125-131 sets only `mPendingClose`; the read loop at :154-160 then waits forever). They hold
  no lock. The 15:01 and 18:16 calls each ran the same refresh through `isConnected` (`mLock`) and left no
  stuck thread, so `mLock` was free until after 18:16.
- Why the daemon was down at ~19:27. The most likely cause is an ordinary OnePlus adbd restart. It is not proven.

### 1.4 What would settle it (the OP12 debug log)

Ask for `app_debug.log` covering 09-14 12:30 → 23:10. Look for these, in order:

1. **Why the daemon died, just before ~19:27:**
   - `CV:MicOpHeal` with "replacing the recorder daemon": a planned kill after a call. WhatsApp calls do
     not appear in the call log, so also check for a `*_voip*` recording or `CV:VoipRec` lines between 19:00 and 19:27.
   - `keep-alive: binder-death signal — relaunching immediately`, then `force=true`: an immediate death.
     A 60 s watchdog with `force=false` means it was noticed by the watchdog instead.
   - `CV:ShizukuBackend` "Shizuku died; dropping the stale recorder binding": the new Shizuku watcher firing
     in standalone mode (suspect R3).
   - `USB debugging switched off`, `Wireless debugging switched on by hand` / `switched off by hand` /
     `went off with Wi-Fi`, `CV:WdAction`: a switch changed (suspects R4, R5).
2. **Which path the 19:27 relaunch took:**
   - Expected: `keep-alive: daemon down — relaunching (force=… offline=true)`, then `Connected over loopback tcpip`
     or `Loopback self-healed`, then `Loopback shell not ready within 6000ms (4 probes)`, then
     `Attempt 1/3: loopback shell not ready`, then **no** `Attempt 2/3` line, then
     `relaunch still blocked after 45000ms — abandoning`.
   - If instead there is `Not switching Wireless debugging on: no Wi-Fi` or
     `Cannot arm loopback — no base connection (NO_WIFI)` in that attempt, the new NO_WIFI gate (R1) was on
     the path and this verdict must be revised.
   - If there is `adbd is stopped after keep-alive relaunch`, the revive code ran when it should not have
     (contradicts R2).
   - If there is `did not complete the ADB handshake`, the wedge is in connect, not open. The mechanism would then be different.
3. **What started the 14:41 refresh thread:** the line before `USB-default refresh still blocked after 1500ms`
   around 14:41 (call standby/start, `RecorderConnection received daemon binder`, VoIP re-arm).
4. **The lost calls:** at 22:57 and 23:04, expect `Ensuring recorder daemon is running` and then nothing from
   `CV:RecorderLauncher`. Expect `No active session, exiting standby state` at hang-up.

Stronger proof, next time it wedges: before force-stopping, take a Java thread dump. This needs a
debuggable build with `-PisolateTestApp`; a release build cannot be dumped from the shell uid. Expect:
- one `cv-shell-probe` in `AdbConnection.open` → `Object.wait`, holding the manager lock
- the other probes, `cv-keepalive-launch` #1 (in `forceReconnect` → `disconnect`) and every
  `cv-keepalive-rewarm` (in `dropConnection` → `disconnect`) blocked on that same lock
- the `cv-usbdefault-refresh` threads in `AdbStream.read`, holding no lock

---

## 2. Regression suspects, ranked

"New" means added in `b5e6dc9..fix/adb-transport-dead-ends`. Everything else is already in v2.3.0.

| # | Suspect | New? | Verdict | Why |
|---|---|---|---|---|
| R0 | libadb `open()` lost wakeup + rescue on the same lock (§3) | no | **Survived (the mechanism)** | Every observed count follows. Two checks each, none refuted. Does not explain "why this build". |
| R1 | NO_WIFI gate removes the off-Wi-Fi WD write (29c4a83; WirelessDebuggingEnableGate.kt:57-66, AdbShell.kt:276-281, 644-647) | yes | **Refuted as cause** (2 of 2 checks) | Reached only after a loopback connect fails (RecorderServerLauncher.kt:175-178). A NO_WIFI result ends that attempt before `waitForShellReady` (:179-181). The 4 probes prove the wedging attempt had connected. After the wedge the gate cannot run. At most it moves the timing of earlier failed attempts by ~12.75 s. A v2.3.0 adbd kick would not have freed a stream already removed from the map. |
| R2 | `reviveAdbdIfStopped` on every relaunch (4c345eb/3ae3da2/0bb836a; AdbShell.kt:175-238, DaemonKeepAliveService.kt:421) | yes | **Refuted for this setup** | With USB debugging on it returns NOTHING (AdbdRevivalPolicy.kt:72-74), with no sleep and no write. It takes only `reviveLock`, a leaf lock. The 45 s gap between the log pair also leaves no room for extra delay. |
| R3 | Shizuku death listener installed in every mode → `RecorderConnection.onBinderDied()` → forced rewarm (8293ef0/059a34f; ShizukuLifecycleWatcher.kt:43-55, ShizukuBackend.kt:192-196, RecorderConnection.kt:89-97) | yes | **Unproven, unlikely** | Fires only if a Shizuku/Sui server once delivered its binder to CallVault. It cannot drop a live daemon (the `isBinderAlive` guard). It adds at most a forced relaunch at an adbd-restart moment. Captured 01:12 lines are all `force=false`. Settle with the log (§1.4 item 1) and `pm list packages | grep -i shizuku`. |
| R4 | WD observer forces a rewarm on any WD-on write, including CallVault's own (391134a/0bb836a; DaemonKeepAliveService.kt:213-220) | yes | **Refuted for this incident** | Needs a WD write, which needs Wi-Fi. WD stayed off all day. It is a real new risk in other setups (§4, F6). |
| R5 | USB observer acts on any USB-off change (4c345eb; DaemonKeepAliveService.kt:136-176) | yes | **Refuted for this incident** | USB debugging stayed on. With USB on and WD off it returns at :159. No `cv-transport-change` thread was alive at 01:12. |
| R6 | Per-tick notice rebuild + ReadinessNotice guard (fc79115/0bb836a; DaemonKeepAliveService.kt:97-102, 399-403) | yes | **Refuted** | Only settings, sysprop, ConnectivityManager and prefs reads. No ADB lock. The guard gives STARTING here, so the relaunch goes ahead. |
| R7 | WirelessDebuggingOffCause mislabels an Android switch-off as USER (391134a) | yes | **Refuted for this incident** | WD never turned off during the incident, and USB on + loopback armed exempts it (ReadinessNotice.kt:78). It is a new silent-loss risk in other setups (§4, F7). |
| R8 | Stereo probe (e15265b) | same patch in diag build | **Refuted** | Byte-identical to 9114bcd. Capture-only code. No call was running at 19:27. |
| R9 | `cv-usbdefault-refresh` read hang (libadb AdbStream.java:125-160) as the lock holder | no | **Refuted as holder**, survived as a separate hang | `mLock` was free at 18:16 and at 19:27 (see 1.3). |

Bottom line: every new candidate is either inert in this setup or refuted by the thread evidence. R3 is the
only one not closed by code alone. The log closes it.

---

## 3. Pre-existing weaknesses that made it unrecoverable

All of these are in v2.3.0. The new build neither added nor removed any of them.

1. **`open()` can park forever holding `mLock`** (libadb AdbConnection.java:497-526; AbsAdbConnectionManager.java:410-420).
   The riskiest callers are the fast one-shot shell commands: the probe `echo` (AdbShell.kt:397-414),
   `killStaleDaemons` (RecorderServerLauncher.kt:320-332), `pm grant` (AdbShell.kt:785-794) and `tcpip:`/`usb:`
   (AdbShell.kt:570-578, 593-600).
2. **The probe's rescue closes nothing.** `streamRef` is set only after `openShell` returns (AdbShell.kt:402-410).
3. **Every rescue takes the same lock.** Examples: `forceReconnect` (AdbShell.kt:325-330), `dropConnection` (:349-352),
   `connectBounded`'s timeout disconnect (:487), arm's `mgr.disconnect()` (:557). Also, `AdbConnection.close()` joins the
   reader thread with no timeout (AdbConnection.java:586-590).
4. **Bounded wrappers abandon threads; they never interrupt them.** No app code calls `interrupt()` on an ADB thread.
5. **Escalation happens only after the rescue returns** (DaemonKeepAliveService.kt:454-462). REBUILD's own
   `dropConnection` (:446) also sits outside the gate's try/finally.
6. **The gate expires after 90 s** (RewarmGate.kt:61-69, DaemonKeepAliveService.kt:683). Each cycle adds a new
   rewarm thread and a new launch thread, with no limit.
7. **Call start has no time bound and no fallback** (AudioRecordingEngine.kt:446/516 → RecorderServerLauncher.kt:141).
   It checks `isCancelled` only afterwards (:451/:520), and posts no error notification.
8. **Health surfaces read the unreachable streak.** The notice (ReadinessNotice.kt:79-80) and Home
   (HomeViewModel.kt:746) both stay STARTING/READY. `SilentFailureNotifier` has no runtime caller.
9. **`connect()` replaces a dead connection without closing it** (AbsAdbConnectionManager.java:368-381). This leaks
   `CLOSE_WAIT` sockets and reader threads.
10. **The only escape is a new process.** `AdbConnectionManager` is a singleton (AdbConnectionManager.kt:114-129)
    and `mLock` belongs to that instance. The keep-alive's START_STICKY foreground service keeps the process alive.

---

## 4. App flow map

### 4.0 Lock and thread inventory (whole app)

Lock order everywhere: **L1 → L2 → L4 → (L5/L6/L7/L8)**. No path takes them in reverse, so there is no lock
cycle. The wedge is a chain of threads queued behind one thread that is parked forever.

| id | Lock | Taken at |
|---|---|---|
| L1 | `AdbShell.heavyOperationLock` (AdbShell.kt:27) | RecorderServerLauncher.kt:141; UpdateInstaller.kt:82; `armLoopbackLocked` AdbShell.kt:526 (re-entrant); `disarmLoopback` :586 |
| L2 | `AdbShell` monitor (`@Synchronized`) | `ensureConnected` :87, `connectViaWirelessDebuggingWithReason` :271, `forceReconnect` :325 (not `dropConnection`) |
| L3 | `AdbShell.reviveLock` (NEW) | `reviveAdbdIfStopped` :196. A leaf: holds no other lock, and sleeps up to ~8.5 s |
| L4 | libadb `AbsAdbConnectionManager.mLock` | `isConnected` :163, `connect` :370 (up to 20 s), `disconnect` :390, `openStream` :411 (across `open()`) |
| L5/L6 | `AdbConnection.this` / `AdbConnection.mLock` | `waitForConnection` :530; socket writes :600 |
| L7 | per-stream monitor | `open()` wait :515-517; `write()` OKAY wait AdbStream.java:206-214 |
| L8 | `AdbStream.mReadQueue` | `read()` wait AdbStream.java:154-160 |
| L10 | `ShizukuBackend.bindLock` | `start` :134, `onShizukuDied` :192 (NEW, often on main), `stop` :205 (binder IPC under the lock) |
| — | `WirelessDebuggingLease` counter | not a lock; a thread parked inside `asAdbUser` never gives its lease back (AdbShell.kt:736-745) |

Unbounded waits:
- W1: `open()`, holding L4
- W2: `AdbStream.read` after a pending close
- W3: `write()` waiting for OKAY
- W4: `close()` joining the reader thread
- W5: every `synchronized(L1)` entry except `launchDaemonBounded`'s 45 s join
- W6: every L2 entry
- W7: binder calls to the daemon (none have a timeout)

Threads seen on the OP12 at 01:12 and where the code creates them:
- `cv-keepalive-rewarm` ×134: DaemonKeepAliveService.kt:406
- `cv-keepalive-launch` ×134: DaemonKeepAliveService.kt:485
- `cv-shell-probe` ×4: AdbShell.kt:406
- `cv-usbdefault-refresh` ×2: RecorderServerLauncher.kt:246

### 4.1 App start, boot, post-update

**Entry points:**
- `CallVaultApplication.onCreate` (main thread). In order:
  - NEW `ShizukuLifecycleWatcher.install` (:52).
  - Standalone only: `ShizukuBackend.stop(remove=true)` on main (:177-186).
  - Unbounded warm-up `ensureRunning` on a background thread (:191-209).
- `BootReceiver` (:24-39) → `AdbConnectionService` (unbounded `ensureRunning`, :64-89), `CallMonitorService`,
  `DaemonKeepAliveService`.
- `UpdatePackageReplacedReceiver.recoverAfterReplace` (:78-114) → `cv-post-update-recover` →
  `tryHealWriteSecureSettings` (AdbShell.kt:807-825, L2 plus an unbounded `pm grant` read) → `ensureRunning`.

**Threads:** main, warm-up, `cv-post-update-recover`, `cv-voip-rearm`, `cv-diag-sync`.
**Locks:** L1, L2 and L4 through `ensureRunning`. L10 on main.
**Exits:** only a new process clears an L4 wedge.

| Sev | New? | Where | Scenario | Consequence |
|---|---|---|---|---|
| high | no | UpdatePackageReplacedReceiver.kt:88-113; AdbShell.kt:785-794, 807-825 | Install-over drops the grant. The heal's `pm grant` read parks while holding L2. The warm-up and keep-alive then queue behind it. | Recorder never comes up after an update. |
| medium | no | AdbConnectionService.kt:64-89; CallVaultApplication.kt:192-208 | An ADB step parks during boot or warm-up. | Boot notice 4713 never clears. The warn notification is never posted. |
| medium | no | RecorderServerLauncher.kt:271-280 | `killStaleDaemons` kills a daemon from an earlier attempt that bound late. | Longer launches, with more stream opens during adbd churn. |
| low | yes | CallVaultApplication.kt:52; ShizukuLifecycleWatcher.kt:52-55 | A Shizuku server dies while CallVault is in standalone mode and our daemon is already down. | Extra forced relaunch (R3). |
| low | no | CallVaultApplication.kt:177-186; ShizukuBackend.kt:205-218 | Shizuku is installed but hung. | `onCreate` stalls on main; ANR possible. |

### 4.2 Keep-alive relaunch over Wireless debugging (off-Wi-Fi recording off)

**Chain:**
1. Watchdog (main, 60 s, DaemonKeepAliveService.kt:85-113), binder death (:355-367), or a switch observer →
   `maybeRewarm` (:392-468).
2. `cv-keepalive-rewarm`: `reviveAdbdIfStopped` → `nextStep`. Usually RESTORE here, with a NEW 1 s sleep
   (:435) and then the gated `enableWirelessDebugging`.
3. `launchDaemonBounded` → `cv-keepalive-launch` → `ensureServerRunning` (L1, lease) → `ensureConnected` (L2) →
   `connectViaWirelessDebuggingWithReason`, which runs: enable gate → 750 ms → read-back → mDNS 12 s →
   `connectBounded` (`cv-adb-connect`, L4 up to 20 s).
4. `waitForShellReady` → `launchOnce` → `pollConnected` → `applyWdPolicy` (`cv-usbdefault-refresh`, then
   `releaseWirelessDebugging`).

| Sev | New? | Where | Scenario | Consequence |
|---|---|---|---|---|
| critical | no | libadb AdbConnection.java:497-526; DaemonKeepAliveService.kt:454-462, 500 | §3 items 1-5 | Permanent wedge |
| high | no | RecorderServerLauncher.kt:214-253; AdbShell.kt:759-779 | Every launch switches WD off while the refresh thread and drain thread may still be opening streams. `applyWdPolicy` also ignores the lease. | One more chance at W1 on every launch, plus a leaked socket and thread |
| high | yes | DaemonKeepAliveService.kt:213-220, 494-501 | CallVault's own WD write forces a rewarm. After 45 s that rewarm drops the connection of a slow launch on the call path (worst case ~124 s 📐 CALCULATED). | Call lost; can start a wedge |
| high | yes | DaemonKeepAliveService.kt:225-250; WirelessDebuggingOffCause.kt:26-31 | Android turns WD off while Wi-Fi still reads CONNECTED, and the cause is recorded as USER | Recovery latched off until the user taps the button |
| medium | no | DaemonKeepAliveService.kt:394, 506-510 vs WifiState.kt | Wi-Fi without internet while mobile data is the default network | Keep-alive never relaunches while the notice says STARTING |
| medium | yes | AdbShell.kt:196-233; DaemonKeepAliveService.kt:435 | USB off: revive plus RESTORE add up to ~10 s. A CYCLE restarts adbd under another thread's launch. | Longer gap after a death; extra adbd restarts |
| low | yes | AdbShell.kt:687-701 | The own-write attribution has one slot; the user switches WD off within 10 s of CallVault's write | #30 promise broken in a narrow window |
| low | yes | DaemonKeepAliveService.kt:120-122, 141, 216, 250 | `pingBinder` on main from new observer and notice paths while the daemon is hung | Main-thread stall |

### 4.3 Standalone with off-Wi-Fi recording (the incident setup)

**Chain:**
1. `ensureConnected` goes to `connectLoopback` (AdbShell.kt:102-125): `connectBounded`, a 12 s self-heal, then
   a 2.5 s settle.
2. If it fails: `armLoopbackIfNeeded` → `armLoopbackLocked` (:526-563): `connectLoopback` →
   WD bootstrap (NEW: NO_WIFI off Wi-Fi) → `cv-arm-tcpip` `tcpip:` (restarts adbd) → `disconnect` → `connectLoopback`.
3. Then the same probe, launch and refresh steps as 4.2.

**Incident trace** (📐 inferred from the code plus thread counts):
1. At ~19:27 the daemon is down. The watchdog fires. `ensureConnected` succeeds.
2. Probe #1 parks in W1 holding L4. Probes #2-4 block on L4.
3. Attempt 2's `forceReconnect` blocks on L4 while holding L1 and L2.
4. The rewarm thread's `dropConnection` blocks on L4.
5. The gate expires. A new pair of threads starts about every 2 min: uptime-clock ticks stretch under doze,
   giving 134 pairs.
6. Calls at 21:07, 22:57 and 23:04 park on L1.
7. Wi-Fi returning at 21:59 changes nothing.

| Sev | New? | Where | Scenario | Consequence |
|---|---|---|---|---|
| critical | no | §3 | open() park during an adbd restart window | Wedge until force-stop |
| critical | no | AudioRecordingEngine.kt:446/516 | Calls during a wedge | Silently unrecorded |
| high | no | libadb AdbStream.java:125-187 | CLSE arrives while output is still queued. The read hangs, or the probe reports a healthy shell as not ready. | Leaked threads; more retries inside restart windows |
| high | no | AdbShell.kt:526-563, 570-578 | `tcpip:` arm (after a reboot, or loopback refused for more than 12 s) parks in W1 | Same wedge. The WD lease is never released. |
| high | no | DaemonKeepAliveService.kt:355-366; AdbShell.kt:110-120 | Relaunches land right after OnePlus screen-transition adbd restarts | Most likely source of the ~19:27 death and the race (📐) |
| medium | no | AbsAdbConnectionManager.java:368-383 | Dead connection replaced without being closed | `CLOSE_WAIT` leaks; `openStream` on a dying reader |
| medium | yes | DaemonKeepAliveService.kt:225-248 | A mislabelled user switch-off after a reboot clears tcpip | Relaunch suppressed |
| medium | yes | AdbShell.kt:175-235 | CYCLE with USB off during a transient adbd stop | Kills a daemon that just came back; WD may be left off |
| medium | no | DaemonRecoveryPolicy.kt:102-107 | Reboot while away from Wi-Fi | No recording until Wi-Fi returns |
| low | yes | AdbShell.kt:276-281, 644-647 | NO_WIFI removes the ~13 s off-Wi-Fi detour | Retry timing changes; effect unmeasured (📐) |

### 4.4 Carrier call: ring to saved file

**Chain:**
1. `PhoneStateReceiver` / `CallMonitorService` → `CallSessionManager.handlePhoneState` (`@Synchronized`, main)
   → `processSessionUpdate` → `RecordingPolicy` → `startForegroundService(START)`.
2. `RecordingForegroundService` (main) → IO coroutine → `startPipeline`:
   - `createAudioFile` first
   - handoff attempt if enabled
   - `startDaemonPipeline` → `ensureRunning` (L1) → binder `startRecording`
   - local fallback `ensureConnected` + scrcpy
3. STOP (main) → `engine.release()` → CallLog rename → `routeFinalRecording` → `MicOpAutoHeal`.

| Sev | New? | Where | Scenario | Consequence |
|---|---|---|---|---|
| critical | no | RecordingForegroundService.kt:315-340; RecorderServerLauncher.kt:141 | L1 held by a stuck launch | Every call lost silently; pfd and IO thread leak |
| high | no | RecorderServerLauncher.kt:141-152, 214-253 | Even with the daemon warm, the call takes L1 and starts a `dumpsys usb` stream | Lost calls with a healthy recorder; the call path can itself create the wedge |
| high | no | RecorderServerLauncher.kt:320-332; AdbShell.kt:477-491 | A launch started at call time parks while holding L1 | Call path becomes the wedging thread |
| medium | no | AudioRecordingEngine.kt:297, 451-457 | Cancel is checked only around `ensureRunning` | A stuck start is never cancelled; a late unblock can reuse old metadata |
| medium | no | AudioRecordingEngine.kt:363-385 | Local fallback without a cancel check | Mic capture can start after hang-up; a second capture drops the user's voice |
| medium | no | MicOpAutoHeal.kt:59-138 | Heal destroys the daemon after a call | Forces a relaunch into the fragile path |
| medium | no | AudioRecordingEngine.kt:612-721 | Stop and finalise on main | ANR or truncated file |
| medium | no | SetupPrerequisites.kt:56-70 | A stuck start leaves no health record | Invisible until the gap sweep |
| low | no | RecordingForegroundService.kt:172-173 | Starting state not treated as a session | Parallel pipeline, duplicate file |
| low | no | RecordingForegroundService.kt:583-613 | CallLog lookup picks the wrong row | Wrong file name |
| low | yes | AdbShell.kt:620-659 | The gate changes how the arm path fails at call start off Wi-Fi | Faster failure; no incidental adbd restart |

### 4.5 Resilient recording (audio handoff)

**Chain:**
1. `startHandoffPipeline` (AudioRecordingEngine.kt:516-583) → `ensureRunning` (L1) → binder `startHandoff`.
2. Daemon side: `HandoffSource.deliverToApp` → provider `sendHandoff` → `HandoffReceiver.onReceived` →
   `cv-handoff-encode` + `cv-handoff-drain` (native).
3. On daemon death: `onDeath` → forced relaunch during the call.
4. Re-arm needs `RecorderConnection.service` (AudioRecordingEngine.kt:556-563).
5. Stop on main: `HandoffReceiver.stop` joins for 8 s.

| Sev | New? | Where | Scenario | Consequence |
|---|---|---|---|---|
| critical | no | AudioRecordingEngine.kt:522 → RecorderServerLauncher.kt:141 | Wedge | Handoff never begins; no fallback; silent |
| high | no | RecorderServerLauncher.kt:245-253 | The fast path spawns refresh threads that touch L4 | A hidden hang can sit until the next relaunch |
| high | no | DaemonKeepAliveService.kt:355-366 | Daemon dies mid-call; immediate relaunch against a just-restarted adbd | Current call survives; next call likely lost |
| medium | no | HandoffReceiver.kt:383-411 | Route change while the daemon is down | Rest of the call missing, no notification |
| medium | no | AudioRecordingEngine.kt:348-375 | Handoff fallback runs `ensureRunning` twice, then an unbounded connect | ~48 s before local fallback 📐 CALCULATED |
| medium | no | HandoffReceiver.kt:205 | 8 s join on main at stop | Watchdog and death handling delayed; corrupt file on timeout |
| low | no | HandoffEncoder.kt:100-104; audiohandoff.cpp:263-269 | Codec stops returning buffers | Busy-looping drain thread, truncated file |
| low | no | HandoffReceiver.kt:263-272; MicOpAutoHeal.kt | Track release depends on GC | Heal kill, then relaunch |
| low | yes | ShizukuLifecycleWatcher | R3 | Extra forced relaunch |
| low | same in diag | AudioRecordingEngine.kt:315, 551 | Stereo probe; handoff loses speaker turns | No speaker labels |

### 4.6 VoIP detection and capture

**Chain:**
1. `VoipCallDetector` (main-looper mode listener) → `VoipRecordingCoordinator.onCallStarted` (`@Synchronized`, main):
   binder `voipCallAppUid`, `voipCallerName`, `startVoipRecording`.
2. Daemon side: `VoipCaptureSession` (`voip-capture`, `voip-near`, `voip-far`).
3. Arming via `onDaemonReady` → `cv-voip-rearm` → `VoipCaptureController.sync` → `ensureRunning` (L1, plus a
   refresh stream) → binder `armVoipCapture`.
4. End: `stopRecording` blocks up to 6 s on main → `MicOpAutoHeal`.

| Sev | New? | Where | Scenario | Consequence |
|---|---|---|---|---|
| high | no | VoipCaptureController.kt:44; RecorderServerLauncher.kt:141 | L1 held by a stuck launch | Every arm hangs; every VoIP call lost; Settings spinner hangs |
| medium | no | CallVaultApplication.kt:86-96 | Re-arm on every daemon arrival opens another abandoned `dumpsys usb` stream | More concurrent opens in the hostile window |
| medium | no | VoipRecordingCoordinator.kt:492; MicOpAutoHeal.kt | A VoIP call ends with a stuck shell mic | Daemon destroyed → relaunch. A candidate for the 19:27 death (check the log) |
| medium | no | VoipRecordingCoordinator.kt:89-96 | VoIP call while the daemon is down | Lost for good, by design |
| low | no | VoipCallerName.kt:110-121; RecorderServiceImpl.kt:141-177 | Binder IPC on main (unbounded `dumpsys notification`, 6 s stop) | Watchdog and observers stall |
| low | no | VoipCaptureSession.kt:398-414, 451-465 | Feeder retakes the mic after stop or suspend | Stuck mic → heal kill; dropped own side on a carrier call |
| low | no | VoipRecordingCoordinator.kt:333-360 | Daemon dies mid-VoIP | No trailer; false one-sided warning |
| low | yes | ShizukuBackend.kt:192-196 | R3 | Extra forced rewarm |

### 4.7 Shizuku mode and mode switching

**Chain:**
- `RecorderBackend.ensureShizukuRunning` (:279-309): `ShizukuBackend.start` (L10, `bindUserService`) → 10 s poll →
  `killStaleRecorders`. No L1 or L4.
- `switchTo` (:152-265): teardown → prefs → keep-alive stop/start → `completeSwitch` (:117-144). NEW: clears the warning.
- Watcher `onDead` / `onReceived` (NEW) run on Shizuku's main handler. The keep-alive stands down in Shizuku mode.

| Sev | New? | Where | Scenario | Consequence |
|---|---|---|---|---|
| high | no | ShizukuBackend.kt:154-157, 173-176 | The user service dies while the server lives; `connection` stays set | "Already bound" forever; every call lost until the process restarts |
| medium | yes | ShizukuLifecycleWatcher.kt:64-72 | Warning cleared before the rebind; rebind fails | Silent miss |
| medium | no | RecorderBackend.kt:117-119; ModeSwitchDialog | Switching back to standalone during an L1/L4 wedge | Dialog that cannot be dismissed spins forever |
| medium | no | DaemonKeepAliveService.kt:599-603 | No watchdog in Shizuku mode | Dead service found only at the next call (10 s bind) |
| low | yes | ShizukuLifecycleWatcher.kt:52-55 | R3 in standalone | Extra forced relaunch, silent in logs |
| low | yes/no | CallVaultApplication.kt:177-186; ShizukuBackend.kt:192 | IPC under L10 on main | ANR |
| low | no | Shizuku library connection reuse | Old user service survives a server restart | Short window with two recorder hosts |
| low | yes | ShizukuLifecycleWatcher.kt:58-61 | WD cycle while the user is restarting Shizuku | User's start fails once |

### 4.8 Debugging-switch handling (new in the build)

**Chain:**
- USB observer (DaemonKeepAliveService.kt:136-176) and WD observer (:209-252), both on main.
- `reviveAdbdIfStopped` (AdbShell.kt:175-235, L3).
- `WirelessDebuggingEnableGate` (:620-659).
- `ReadinessNotice` (ReadinessNotice.kt:62-111).
- `WirelessDebuggingActionReceiver` (:28-50).
- Settings/onboarding toggles (SettingsScreen.kt ~:2390-2490).
- `OfflineRecording.enable` (:40-55).

In the incident setup all of this is inert: no switch changed, revive returned NOTHING, the notice was
STARTING, and the next step was CONNECT.

| Sev | New? | Where | Scenario | Consequence |
|---|---|---|---|---|
| medium | yes | DaemonKeepAliveService.kt:213-220 | Our own WD write forces a rewarm that drops another launcher's connection mid-OPEN | New route into the wedge |
| medium | yes | DaemonKeepAliveService.kt:225-249 | Wi-Fi drop recorded as a USER switch-off | Latched; no re-arm after reboot |
| medium | yes | WirelessDebuggingActionReceiver.kt:30-43 | Button tapped during a wedge; unbounded `ensureRunning` inside `goAsync` | Broadcast ANR, possible process kill |
| medium | no | ReadinessNotice.kt:79-80 | Streak starved by the wedge | STARTING for hours |
| low | yes | DaemonKeepAliveService.kt:440-450 | Off Wi-Fi, REBUILD loses its WD endpoint (NO_WIFI) | No incidental adbd restart during escalation |
| low | yes | DaemonKeepAliveService.kt:146-174 | Every USB-off change starts an unbounded `ensureRunning` | Thread pile-up in a wedge |
| low | yes | AdbShell.kt:198-226 | CYCLE on a transient adbd stop | Extra adbd restart; WD left off |
| low | yes | SettingsScreen.kt:2390-2406 | Enforce toggle does not re-kick recovery | Up to ~80 s of avoidable downtime 📐 CALCULATED |

### 4.9 Readiness and health surfaces

**What exists:**
- Watchdog on main every 60 s (uptime clock).
- `RewarmGate`: 90 s in-flight expiry, 20 s throttle.
- `DaemonRecoveryPolicy`: streak held in process memory only.
- `ReadinessNoticeText` (NEW) is used by the keep-alive, `CallMonitorService` and `RecorderReadinessNotifier`.
- `SharedStatusNotice` ID 4720. Home `computeStatus`. `SilentFailureNotifier` 4716.
- None of these takes L1, L2 or L4 (proven), so they can neither be blocked by the wedge nor cause it.

| Sev | New? | Where | Scenario | Consequence |
|---|---|---|---|---|
| critical | no | DaemonKeepAliveService.kt:454-462 | Streak never grows in a hang | No escalation, no STUCK |
| high | no | DaemonKeepAliveService.kt:440-450 | REBUILD's `dropConnection` sits outside the try/finally | Fixing the streak alone would still park |
| high | no | HomeViewModel.kt:746; SilentFailureNotifier.kt:66 | Runtime outage | Home shows READY; no warning |
| medium | no | RewarmGate.kt:62 | Two parked threads per cycle | ~70 threads/hour 📐 CALCULATED; eventual OOM |
| medium | yes | ReadinessNotice.kt:79 | The new STUCK notice is fed by the starved streak | The #39 fix cannot show in this failure |
| medium | yes | DaemonKeepAliveService.kt:219; ShizukuLifecycleWatcher | New forced-rewarm triggers bypass the throttle | More opens during adbd flaps |
| low | no | DaemonRecoveryPolicy.kt:117-128 | Streak not reset when the daemon comes back by another path | Stale STUCK; immediate REBUILD next time |
| low | no | SharedStatusNotice.kt:45-57 | A recording holds 4720 while its start is stuck | Notice updates frozen |
| low | yes | DaemonKeepAliveService.kt:97-102 | Main-thread reads every tick | Cost only |
| low | yes | DaemonKeepAliveService.kt:506-510 vs WifiState.kt | Two different Wi-Fi checks | STARTING with no attempt |

### 4.10 Other ADB users

**Users:** UpdateInstaller (L1 held across stream write and read, :82-118), UsbDefaultConfig refresh and probe
(:118-119, 384-397), SystemLogCollector fallback (:216-247), `grantSecureSettingsIfNeeded`, loopback arm/disarm,
pairing (AdbPairingService.kt:190, 213), ScrcpyLauncher (:65-66). Drive backup and transcription do not touch ADB.

| Sev | New? | Where | Scenario | Consequence |
|---|---|---|---|---|
| high | no | libadb AdbStream.java:125-186; UsbDefaultConfig.kt:394; AdbShell.kt:789-790 | Read hang while holding a lock (grant heal under L2/L1) | Full wedge instead of a harmless leak |
| medium | no | UpdateInstaller.kt:82-118 | adbd stalls mid-install | L1 held forever; every call blocked |
| medium | no | AbsAdbConnectionManager.java:410-420 | `openStream` on a half-established connection (unanswered RSA prompt) | Second route into W1 |
| medium | no | AdbShell.kt:736-745 | Leases leak from parked threads | WD never switched off again (#30 exposure) |
| medium | no | SystemLogCollector.kt:197-247 | Log export during a wedge | Missing log section; more leaks |
| low | no | AdbShell.kt:477-491 | `connectBounded` exceeds its 8 s by ~20 s | Slow-but-healthy launches abandoned |
| low | no | AdbShell.kt:554-600; UsbDefaultConfig.kt:290-297 | User-triggered adbd restarts | Expected death, then a relaunch |

---

## 5. Fix plan (ordered, no code here)

Each step is its own commit on its own branch so one revert undoes it. Every step starts as 🧪 VERIFYING.

1. **Replace the connection instead of waiting on its lock.** Give `AdbConnectionManager` a "discard" path
   that drops the singleton instance without taking the old `mLock`. Close its socket from outside the lock so
   the reader dies; the parked threads can be left behind. Make a fresh manager for the next caller.
   `dropConnection` and `forceReconnect` use it when `disconnect()` does not return within a few seconds.
   - Test: a JVM unit test with a fake adbd socket server that answers OKAY+WRTE+CLSE before the opener waits
     (inject a pause after `sendPacket` in a patched libadb copy). Assert that `openStream` parks, that the
     discard returns quickly, and that a new connect succeeds.
   - Emulator (`mymemory-test`): run `adb shell stop adbd; start adbd` in a loop while the keep-alive relaunches.
   - OP9, then OP12: normal daily use.
2. **Record the failure before rescuing.** Call `onAttemptFailed()` and `rewarmGate.leave()` before the rescue
   in `launchDaemonBounded`, and move REBUILD's `dropConnection` inside the try/finally
   (DaemonKeepAliveService.kt:440-462).
   - Test: unit test with a fake launcher that never returns. Assert the streak reaches 2, the next step is REBUILD,
     `isStuck` becomes true and the notice becomes STUCK. Then on the emulator, with step 1's fake hang.
3. **Patch libadb's two lost wakeups** (vendor 3.1.1 or upstream a fix):
   - `open()` waits in a loop on a condition (opened, closed or connection dead), with a timeout.
   - `read()` treats `mPendingClose` plus an empty queue as end of stream.
   - `cleanupStreams` also wakes streams that are waiting to open.
   - Test: unit tests against the fake socket server for both races, plus the existing instrumented ADB tests on the emulator.
4. **Bound every entry into `heavyOperationLock` on the call path.** Use `tryLock` with a deadline tied to
   the call (e.g. 8 s). On timeout: skip, post the recorder-unavailable warning, and let the keep-alive
   recover. Add a cancel check before any ADB work.
   - Test: unit test with the lock held by another thread; assert the call returns and the warning is posted.
   - Emulator: hold the lock and place a fake call through the telephony emulator console.
   - OP12: a real call after a forced wedge (debuggable isolated build only).
5. **Stop the fast path from opening streams.** When the daemon is already connected, `ensureServerRunning`
   should check `RecorderConnection.isConnected` before taking the lock. The USB-default refresh should run at
   most once per daemon launch, not on every call, standby or re-arm.
   - Test: unit test counting `openShell` calls across STANDBY + START + VoIP re-arm with a connected daemon;
     expect zero. Then the emulator.
6. **Make health surfaces tell the truth.** Home and the notice should show "can't record" once the daemon
   has been down longer than N minutes, whatever the streak says. `SilentFailureNotifier` should warn from
   the keep-alive.
   - Test: unit test on `ReadinessNotice.of` and `HomeViewModel.computeStatus` with a down-duration input.
     Then the emulator with the daemon killed and relaunch blocked.
7. **Close the new silent-loss risks from this build before merging it:**
   - (a) The WD observer should not force a rewarm for CallVault's own writes: move the own-write check
     before `maybeRewarm(force=true)` (DaemonKeepAliveService.kt:213-220).
   - (b) Attribute a switch-off as USER only if Wi-Fi is the default network and no network change happened
     in the last few seconds. Clear the flag when Wi-Fi reconnects to a different network.
   - (c) Bound `ensureRunning` in `WirelessDebuggingActionReceiver` and the `cv-transport-change` thread.
   - (d) The Shizuku watcher should do nothing in standalone mode unless a Shizuku binding existed.
   - Test: unit tests on `WirelessDebuggingOffCause` and the observer ordering. Then the OP9 (USB off, WD on,
     walk out of Wi-Fi, check the notice and relaunch).
8. **Get evidence for the next occurrence.** Add a debug-log line when any ADB wrapper abandons a thread,
   naming the thread and what it was doing. Add a "dump app threads" item to the debug report, using
   `Thread.getAllStackTraces()` from inside the app, which works on release builds.
   - Test: emulator with step 1's fake hang; confirm the report shows the parked `AdbConnection.open` frame.
   - OP12: keep debug logging on.
9. **Request the OP12 log (§1.4) now**, before any of the above. If it shows a new trigger (R3, R1 lines,
   a heal kill), re-rank §2 and fix that first.
