# Threading and scope policy

Status: written 2026-11-21 (review item F4). Not yet confirmed by the owner. The rules below describe how new code
must behave; the "current violations" list is the work still to burn down. Nothing here changes behaviour by itself.

## Why

The app grew concurrency one feature at a time. The main-thread defects found in the 2.5.2 review (C74 note sidecar
written on Main, C85 onboarding status takes the ADB lock on Main, C108 app-call lookups wait on Main, C132 call-end
finalisation on Main) all came from nobody owning the question "which thread does this run on, and who cancels it".

## Scope kinds (the only ones to use)

| Kind | Owner | Lives as long as | Dispatcher |
|---|---|---|---|
| Process | `CallVaultApplication` | the process | `Dispatchers.IO` (or Default for CPU work) |
| Service | the Service that creates it | `onCreate` to `onDestroy`, cancelled in `onDestroy` | IO by default; Main only for UI/notification calls |
| ViewModel | `viewModelScope` | the ViewModel | start on Main, move disk/IPC to IO with `withContext` |
| Work | WorkManager worker (`CoroutineWorker`) | one run | IO |

Compose screens use `rememberCoroutineScope()`; it is cancelled with the composition and counts as a ViewModel/UI scope.

Rules:
1. Do not create a new `CoroutineScope(...)` outside the four kinds. If a hand-made scope is truly needed, give it a
   named owner and a cancel point in the same change, and add it to the table below.
2. `GlobalScope` is never used (no owner, never cancelled).
3. `runBlocking` blocks its thread. Never call it on Main; on a worker thread it needs a comment saying why a
   suspend call cannot be used.
4. No disk, SAF, database, ADB or binder IPC on Main. Wrap with `withContext(Dispatchers.IO)`. A Service callback
   (`onStartCommand`, a receiver's `onReceive`) must hand slow work to its scope or a named thread and return at once.
5. Locks (`synchronized`, `Mutex`) guard one named piece of state and are never held across a call that can block on
   IO, ADB or a binder. State read from another thread is `@Volatile` or behind the same lock (C139 is a lock that
   excludes nothing).
6. A job started for a user-visible result logs its failure reason to the always-on log, not only the debug log.

## Plain threads: the only allowed jobs

Raw `Thread`, `HandlerThread` and `Executors` are allowed for exactly these, each named `cv-...` and daemon unless it
must outlive the process:
1. The audio thread(s): the AudioRecord read loop and the encoder/mux loop (`DirectAudioRecorderSession`,
   `VoipCaptureSession`, `HandoffReceiver`). These are urgent-audio threads like BCR's; they must never wait on
   Main or on a coroutine dispatcher, which is why they are not coroutines.
2. Blocking-process and shell readers (`AdbShell`, `BoundedProcess`, `ScrcpyLauncher`): a blocking read cannot be
   cancelled by a coroutine, so a thread with a timeout and a join owns it.
3. Single-purpose serial workers and watchdogs with a name (`cv-transcribe`, `cv-binder-sender`,
   `cv-adb-open-watchdog`, `recorder-worker`): one executor, one job kind.

Everything else that is currently `Thread { ... }.start()` is a short fire-and-forget job that should move to the
process or service scope (see the burn-down list).

## The audio-thread exception

The recorder daemon and the direct-capture path run in a separate shell-uid process with no coroutine scope. They use
plain threads on purpose. Do not "modernise" them to coroutines: a dispatcher hop on the audio path can drop samples,
and a past rewrite of that path cost recordings. Changes there follow `device-quirks.md`.

## Guard test

`ThreadingPolicyGuardTest` is a ratchet. Per file it counts `CoroutineScope(`, `runBlocking`, and raw
`Thread`/`HandlerThread`/`Executors.new*`, and fails if a file has more than its baseline (a file not in the baseline
may have none). `GlobalScope` fails outright. It also fails when a baseline is higher than the real count, so a
removal must lower the number. It cannot see which dispatcher a call runs on; "no disk/IPC on Main" is enforced by
review plus the burn-down list.

## Current violations to burn down (2026-11-21, 📐 CALCULATED from grep, not measured)

Counts at the base of the B29 batch: 24 hand-made `CoroutineScope(` in 14 files, 4 `runBlocking`, 86 raw
thread/executor constructions in 44 files, about 104 `synchronized` and 187 `@Volatile`.

Main-thread work (known defects, tracked in the review):
- C74 `data/waveform/RecordingExtrasRepository.kt`: note sidecar written on Main.
- C85 `onboarding/OnboardingStatus.kt`: takes the ADB lock on Main.
- C108 `services/recording/VoipRecordingCoordinator.kt`: caller lookups awaited on Main (only timing is logged so far).
- C132 `services/recording/RecordingForegroundService.kt`: call-end finalisation on Main.
- `services/call/CallSessionManager.kt`: `managerScope` is `Dispatchers.Main`; three `withContext(Dispatchers.Main)` hops.
- `services/recording/RecordingForegroundService.kt`: `serviceScope` is Main; four more IO scopes beside it.

Hand-made scopes (candidate owner in brackets):
- `VoipRecordingCoordinator` x7 and `RecordingForegroundService` x5 (service scope, cancelled in onDestroy)
- `CallVaultApplication`, `AppLogger`, `CloudKeySaver`, `BatteryUseCollector`, `SetupReportLink` (process scope)
- `AdbConnectionService`, `AdbPairingService`, `AudioRecordingEngine`, `CallSessionManager`, `VoipToggleEffects` (service scope)
- `SettingsReconcile` (ViewModel scope), `AfterCallOverlay` (Main immediate, overlay lifetime)

`runBlocking`: `CallVaultApplication` (stale-work release at start, on a thread), `AudioRecordingEngine`,
`AfterCallDecision`, `VoipRecordingCoordinator`.

Fire-and-forget threads: `CallVaultApplication` (11), `DaemonKeepAliveService` (11), `AdbShell` (6, reader threads: allowed), and
`AppStartBinderSender` (2: a single-thread delivery executor, and one short-lived re-delivery thread per app death while recording, owned by the daemon's death watch, capped at 3 cycles), and
about 40 single ones across receivers and helpers; the receiver ones should hand off to the process scope.

Locks: `SetupHealthStore` locks exclude nothing (C139).

## Changing this file

Add a line to the burn-down list when you find a violation; remove it in the commit that fixes it. Lower the baseline
numbers in `ThreadingPolicyGuardTest` in the same commit.
