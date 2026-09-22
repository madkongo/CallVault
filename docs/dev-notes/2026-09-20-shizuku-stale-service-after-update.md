> ✅ VERIFIED 2026-09-22 14:49 on the OP9: 2.4.1 (20410) installed over 2.4.0 in Shizuku mode, the update
> receiver restarted the service (two user services started, one binder taken, the extras cleared, one
> `com.baba.callvault:recorder` left), and the very next phone call recorded — standby at dial, start at
> the answer, `Capture start check: STARTED (daemon reachable=true, recording=true)`, file published.
> That is the real call after an install-over that fix 3 had been waiting for.

# 2026-09-20 — an update in Shizuku mode silently costs the next call

Status: **🧪 VERIFYING** — and the ✅ this carried earlier on 2026-09-20 was **wrong to stand alone**. The
maintainer's 12:25 test was real and did pass, but it passed on one ORDER of events. At 13:00 an ordinary
install-over on the OP9 took a third order, got past both fixes, and left the stale recorder attached
again (see "Third cause" below). A third fix — asking the host which APK it runs from — is on branch
`fix/shizuku-stale-host-check`. Five install-overs in a row then ended on a current recorder, one of them
exercising the new check. **Not yet followed by a real call.** To settle: install-over in Shizuku mode,
do not open the app, make a call, check the file — and do it more than once, because once has already
been shown not to be enough.

History: ❌ NOT WORKING 2026-09-20 (10:36 call lost on the OP9). Present in **2.4.0 as published**, and in
every earlier version with Shizuku mode. Found while testing issue #38; unrelated to that work.

## What happened

A new build was installed over the app while the OP9 was in Shizuku mode (10:32:54). A carrier call at
10:36:53 recorded **nothing** — no file, no error the user could see until afterwards.

```
10:36:53.005 D CV:RecorderBackend:   Recorder already connected; reusing existing binder
10:36:53.013 I CV:RecorderServer:    startRecording source=voice-call codec=opus bitRate=24000
10:36:53.165 W CV:RecorderServer:    Direct capture unavailable, falling back to scrcpy: AudioRecord failed to enter RECORDING state
10:36:53.167 E CV:RecorderServer:    startRecording failed (all paths): This recorder process is stale: its APK
                                     (/data/app/~~DG2O3iyNvGYlnxGkSgumnA==/com.baba.callvault-…/base.apk) no longer
                                     exists, so scrcpy cannot be extracted. The app was updated while the service
                                     kept running; the service must be restarted…
10:37:19.033 W CV:AudioRecordingEngine: Staged recording is empty — publishing nothing (capture never produced audio)
```

The app diagnosed itself perfectly. It just did so **at call time**, which is too late — the call is
already happening and cannot be recovered.

## Why the existing recovery did not save it

This is *known* and there is already code for it. `UpdatePackageReplacedReceiver` fired with the right
plan:

```
10:32:54.507 I CV:UpdateReplacedRecv: Package replaced; now 2.4.0
10:32:54.518 I CV:UpdateReplacedRecv: App replaced (mode=SHIZUKU, grant survived=false):
                                      Plan(healGrant=false, ensureRecorder=true,
                                           restartShizukuService=true, restartKeepAlive=false)
```

`restartShizukuService=true` is correct, and `ShizukuBackend.stop(remove = true)` is what should have
retired the stale process. **It did not.** Two lines later:

```
10:32:54.521 D CV:ShizukuBackend: Already bound
10:32:54.525 I CV:ShizukuBackend: Shizuku started the recorder service
10:32:54.526 I CV:RecorderConn:   RecorderConnection received daemon binder
10:32:54.527 I CV:RecorderServer: Diagnostics ring disabled in the recorder host   ← pid 29485, the OLD one
10:32:54.742 I CV:RecorderServer: Clearing 1 other recorder process(es): [10404] (I am 29485)
10:32:54.744 I CV:UpdateReplacedRecv: Post-replace recovery done
```

**pid 29485 is the pre-update process**, and it is still the one answering at 10:36:53. The recovery
completed in **237 ms** and reported success while leaving the stale service in place — it rebound to
the old process rather than replacing it, and "Already bound" is the tell.

Note the irony: the old service *did* clear other recorder processes, so the one survivor was the stale
one.

After the call, opening the app at 10:37:37 finally produced a fresh host (pid 18445) which cleared three
leftovers. So the restart works — just not from the replace path, and not before the next call.

## Why this matters more than it looks

- It is **silent**. The user sees a call that simply is not there. On the OP9 the only visible sign was
  the absence of a file.
- It fires on **every** install-over in Shizuku mode: our own dev installs, and a user taking an update
  through the in-app updater. 2.4.0 shipped with it.
- The existing code and comments show this was understood and fixed once before — the receiver's own
  KDoc describes the 13-minute call this class of bug cost on 2026-09-06. The plan is right; the
  execution does not achieve it.

## Where to look

- `system/updates/UpdatePackageReplacedReceiver.kt:99-102` — the `restartShizukuService` branch, calling
  `ShizukuBackend.stop(remove = true)`.
- `server/ShizukuBackend.kt` — why `stop(remove = true)` left the process running, and what "Already
  bound" means at that moment. Suspicion, untested: the stop is asynchronous, or `remove` does not force
  a rebind, and `RecorderBackend.ensureRunning` immediately after re-bound to the survivor rather than
  waiting for it to die.
- `server/RecorderServiceImpl.kt:457` — `startWithFallback`, which raises the stale-APK IOException. It
  knows the process is stale. **Nothing asks it that question until a call starts.**

## Two fix directions, neither written

1. **Make the replace path actually replace it.** Wait for the old process to die before rebinding, and
   verify the new host's pid differs. The current code cannot tell "restarted" from "rebound to the same
   process", which is exactly the distinction that failed here.
2. **Ask before the call, not during it.** The staleness test is cheap and local — the host knows its own
   APK path and can `File.exists()` it. A readiness check at bind time, or on the keep-alive's tick,
   would turn a silently lost call into a self-heal. This is the stronger fix: it catches the same
   failure however the service came to be stale.

## Reproducing it

On a phone in Shizuku mode: install any build over the top, then make a carrier call **without opening
the app in between** — opening it is what repaired the OP9. Expect a call with no file and the
`This recorder process is stale` line in the log.

## Root cause (added later on 2026-09-20 — corrects the section above)

**"It rebound to the old process" above was wrong about the mechanism, and "the stop is asynchronous" was
only half of it.** Shizuku's own server log (`UserServiceManager`, pid 28124), which the first pass did
not read, shows a fresh process WAS started at 10:32:54.526 — and the stale one killed it. There are two
independent ways the stale service stays attached, and the OP9 produced one on each of two installs:

1. **A late answer to an earlier bind** (10:32 install). App start bound the surviving service at .505.
   The recovery ran `stop(remove = true)` at ~.52, and at .525 the first bind's `onServiceConnected`
   arrived on the main thread and put the *removed* service's binder into `RecorderConnection`.
   `ensureRunning` found "a recorder" and called `killStaleRecorders` on it — so stale pid 29485 killed
   fresh pid 10404, then 10407, 10412, 10409 and 10413 as Shizuku kept retrying.
2. **`stop()` does not detach a live binder** (10:55 install, with only fix 1 in place). The binder
   arrived *before* the stop. `stop()` detaches through `RecorderConnection.onBinderDied()`, which keeps
   any binder that is still alive — correct for the case it was written for — and Shizuku's `remove`
   does not kill the process on this phone. Log: `A previous recorder's binder died; the current one is
   alive - keeping it`, then `Recorder already connected; reusing existing binder` 1 ms later.

`RecorderBackend.switchTo` had already met cause 2 and solved it (ask the service to `destroy()` itself,
wait, then `forceClear`). The post-update path simply never got the same treatment.

## The fix

- `ShizukuBackend`: a connection's callbacks ignore themselves once they are no longer the current
  binding, and the binding is claimed before `bindUserService` rather than after.
  Test: `ShizukuBackendStaleCallbackTest`.
- `RecorderBackend.retireShizukuService`: the mode switch's teardown, extracted and shared — `destroy()`
  the service, ask Shizuku to remove it, wait for the binder to go, drop it on purpose if it will not.
  `UpdatePackageReplacedReceiver` now calls this instead of a bare `stop`. Test: `RetireShizukuServiceTest`.

Measured on the OP9 at 10:58 with both in place: stale pid 18445 (two installs old) gone, recorder pid
20954 running from the *current* `base.apk` (checked in `/proc/20954/fd`), app never opened.

Measured again at 11:04 after the review fixes below: recorder pid 21977 from the current `base.apk`, and
the log shows cause 1 being caught in the act — `Ignoring a recorder binder for a binding that was
already stopped`.

Review (kotlin-reviewer) found one thing that mattered: the new teardown calls `destroy()`, which stops
a recording and exits, and a `daemon(true)` service can be **mid-call** when an install lands. The
post-update path now leaves a recording service alone (`retireShizukuService` returns false); a mode
switch, being the user's own act, still goes through. 📐 CALCULATED, not measured: nobody has installed
over a live Shizuku call to watch this guard fire.

Fix direction 2 above (ask the host whether its APK still exists, before a call) is **still not written**
and is still worth having: it would catch a stale host however it came about.

## Controlled re-test, 2026-09-20 12:25 (✅ the run the maintainer confirmed)

The earlier post-fix calls (11:07, 11:52) could not settle it: the maintainer was not sure whether the
app had been opened between install and call, and opening it repairs the old bug on its own. So it was
run again with the log as the witness.

- 12:25:18 install-over, phone idle on the home screen. 12:25:23 `Started by Shizuku … pid=16360`,
  `Post-replace recovery done`. Old recorder pid 7138 gone.
- **No CallVault activity was started between the install and the call** — no `START u0` or
  `Displayed` line for `com.baba.callvault` in ActivityTaskManager for the whole window.
- 12:25:52 carrier call. pid 16360 answered `startRecording`, extracted scrcpy from the *current*
  `base.apk`, `Capture start check: STARTED`, and published a 58 KB file: 16.1 s, 2 ch, mean −35 dB,
  peak −6.3 dB. No `stale` line anywhere.

This is the exact reproduction recipe from above, and it recorded. The maintainer then opened the app,
found the 12:25 call in the list and played it: ✅ VERIFIED 2026-09-20.

## Third cause, 2026-09-20 13:00 — and why the first two fixes could never be enough

❌ NOT WORKING 2026-09-20 13:00 with fixes 1 and 2 in place: after an install-over the OP9's only recorder
was pid 16360, running from `…==deleted==/base.apk`.

```
13:00:31.827 UserServiceManager: Found existing service record (2548a9e9…)      ← app start binds the OLD one
13:00:31.869 CV:RecorderBackend: Previous recorder is gone                       ← retire ran with no binder held yet
13:00:31.876 UserServiceManager: New service record (bfd21b67…) … Starting process
13:00:31.881 CV:ShizukuBackend:  Shizuku started the recorder service            ← 5 ms later: not the new process
13:00:31.882 CV:RecorderConn:    RecorderConnection received daemon binder       ← the OLD binder, on the NEW binding
```

**Shizuku dispatches a service's binder by service name, not by connection object.** The old record's
pending answer was delivered to whatever connection was registered for that name — by then the new one —
so the identity check from fix 1 saw a current connection and accepted it. Fixes 1 and 2 are both real
and both stay, but they are ordering logic, and there is always another order.

**Fix 3** is the "ask before the call" direction this note called the stronger one from the start:
`IRecorderService.hostApkPath()` (last in the AIDL), compared with `applicationInfo.sourceDir` in
`RecorderBackend.retireIfStale`. Asked before `killStaleRecorders` and on the already-connected fast path,
so it also heals at call time. A host too old to answer counts as stale; a dead one is just dropped; a
stale host that is recording is left alone. Test: `StaleShizukuHostTest`.

Measured after it, five install-overs: all five ended on a recorder running from the installed APK. Run 1
logged `The recorder is running from /data/app/~~vuJv…/base.apk, not the installed APK` and recovered.

Still open: every install-over spawns several recorder processes (`Clearing 4 other recorder
process(es)`), which the winner then kills. It ends correctly, but it is churn, and it is unexplained.

## 2026-09-22 — review finding on fix 3 (🧪, not seen on a phone)

`retireIfStale` asked one binder where it runs from and then called `retireShizukuService`, which re-read
`RecorderConnection.service` before `destroy()`. Nothing serialises that check against a bind callback,
so a fresh binder landing in that window would have been the one destroyed while the stale process lived
on. Fixed in `56b310ac`: the diagnosed host is passed in and is the only one acted on. Test:
`the_host_that_was_found_stale_is_the_one_destroyed_even_if_a_fresh_one_arrived_meanwhile`. The window is
milliseconds wide and was never observed; the fix is cheap and removes a whole class of ordering.
