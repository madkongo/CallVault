# Two private reports, 2026-09-28: every second call lost (Xiaomi), app calls fail on vivo

**Status:** 📐 analysed from the two debug reports and the code — nothing built, nothing reproduced.
Both reporters run **v2.4.2-rc2**, standalone mode, with **Resilient recording on**.

## 1. meti.sh — Xiaomi Redmi Note 10 Pro (M2101K9AG, courbet), MIUI 14 / Android 13

"Hit-or-miss; a call at 18:44 was not recorded at all. Shizuku mode is fine."

**The log shows a strict alternation, 12 phone calls 18:03–18:44:** the first call after the recorder host
(re)starts records normally; the next call on the same host fails; the host is then restarted, and the
next call works again.

```
18:03 OK  (host just connected)      18:04:02 FAIL   → host restarted 18:04:09
18:04:38 OK                          18:09 FAIL      → restarted 18:09:51
18:10 OK   18:11 FAIL   18:12 OK   18:12:59 FAIL   18:15 OK   18:16:07 FAIL
18:16:31 OK   18:17 FAIL   18:18 OK   18:19 FAIL   18:20 OK   18:44 FAIL  → restarted 18:44:27
```

Every failure is the same, within ~10 ms of the handoff: `drain segment: streamed=0B elapsed=0ms`,
then `TRACK INVALIDATED by AudioFlinger (CBLK_INVALID)`; three rebuilds fail identically; `0 frames`;
nothing (or a stub) published. Shizuku mode has no Resilient recording, hence "fine there".

**Likely cause (📐 code + log, not reproduced):** the host finds the capture's shared control block by
scanning its own open files (`nativeFindCblkFd`, `audiohandoff.cpp`) and returns the **first** ashmem
region whose frame-count field matches — then hands the app a `dup()` of it. That dup
(`HandoffSource.deliverToApp` → `ParcelFileDescriptor.adoptFd`) is put in the delivery Bundle and **never
closed** in the host; it lives until a garbage collection happens to finalise it. So after one call, the
host still holds the previous call's control block — same frame count (7680), and dead. On the next call
the scan can find that old one first, the app maps a block already marked invalid, and the capture "fails"
instantly with 0 bytes. A host restart closes every fd, which is exactly why the call after works.
Device dependence fits too: whether the old fd sorts before the new one, and when GC runs, varies.

Not seen on our phones: no ashmem fds in either host on 2026-09-28 — inconclusive (unknown whether
Resilient recording is on there, or whether calls happened since the host started).

**Also a gap:** when the handoff fails at the very start of a call there is no fallback to the normal
capture — the call is simply lost.

**Workaround for him now:** Settings → turn **Resilient recording off**. Phone calls then use the direct
capture, which the handoff bug does not touch.

**Fix candidates (not built):** close the host's dup right after delivery; make the scan skip blocks
already marked invalid / pick the newest; and fall back to the direct capture when the handoff yields
nothing in its first second.

## 2. scrunscotty — iQOO / vivo V2507A, OriginOS 6, Android 16

"Cannot record calls properly; fails to save. Fine on my Xiaomi (Android 13)."

The log was cleared just before; it holds **no phone calls at all**, only two **Telegram** calls
(11:00:01, 11:00:30). Both failed the same way:

```
VoipPolicy: createSink failed … NullPointerException: … Context.getOpPackageName() on a null object reference
  at android.media.VivoAudioRecordImpl.isSupportSubMixRecording(VivoAudioRecordImpl.java:133)
  at android.media.AudioRecord.<init>
  at android.media.audiopolicy.AudioPolicy.createAudioRecordSink
```

**Cause (from the stack trace, certain):** we register the app-call audio policy with a **null Context**
on purpose (`VoipAudioPolicy`: on AOSP a null Context gives uid 2000 with no package, the only attribution
AudioFlinger accepts from the shell; a real system Context was measured to be rejected EX_SECURITY). vivo
adds its own hook to the `AudioRecord` constructor that calls `context.getOpPackageName()` without a null
check, so on vivo the far-party sink can never be created. App calls cannot record on this ROM at all.

**Also misleading:** the app then logs (and may tell the user) "captured only your side — the other app
blocks capture" and "produced no audio". Telegram blocked nothing; our sink failed.

**Fix candidate (not built, needs his phone):** on vivo, build the policy with a stand-in Context that
answers `getOpPackageName() = "com.android.shell"` with the shell uid — the scrcpy `FakeContext` pattern,
which records as uid 2000 under that package. Must stay null on every other ROM (proven there). Only his
phone can confirm AudioFlinger accepts it.

**Phone calls on his vivo:** unknown — nothing in this log. With Resilient recording on he could be
hitting report 1's bug too. Needs a log with debug logging on and two or three phone calls.

## 2026-09-28 — research pass (done after the analysis above; should have come first)

- **vivo (report 2):** scrcpy hit the identical crash — same frame, `VivoAudioRecordImpl.isSupportSubMixRecording`
  NPE on `getOpPackageName()` — in Genymobile/scrcpy #3805 (vivo/iQOO, Android 13) and #3791 (vivo V2055A,
  Android 12). Fixed by scrcpy PR #5154, confirmed on vivo by the reporter: `Workarounds.createAudioRecord`
  builds the `AudioRecord` through its private `(long)` constructor and `native_setup` by reflection, so vivo's
  modified public constructor never runs. For us the crash is inside `AudioPolicy.createAudioRecordSink`, so the
  port is: build the far-party sink ourselves the same way, with the attributes `createAudioRecordSink` would
  use (REMOTE_SUBMIX preset + the mix's address tag) and the attribution we already rely on —
  `AttributionSource.myAttributionSource()`, uid 2000 with no package — so every non-vivo phone keeps today's
  proven identity. Only on vivo (or when the normal path throws that NPE).
- **Handoff (report 1):** no prior art — the cblk handoff is our own mechanism (memory: scrcpy/sndcpy/Shizuku/SCR
  all keep the privileged process alive and stream out). Fix built from the code reading: `fix/handoff-stale-cblk`.
- **Downloads:** Android's WorkManager docs — a worker gets 10 minutes, is then stopped and rescheduled, and is
  expected to "cooperatively abort … closing open handles to databases and files". The old worker did not
  (blocking read, file open). Long-running workers need `setForeground` + a `dataSync` foreground-service type
  on Android 14+ — not adopted; the stop is now handled safely instead.

## 2026-09-28 15:30 — scrunscotty on 2.4.3: app calls still fail, one step later; phone calls now save

Log `callvault_report_2026-09-28_15-30.txt` (vivo V2507A, Android 16, 2.4.3, Resilient on, VoIP on).

- **The 2.4.3 fix works:** `createSink: the ROM's AudioRecord constructor crashed without a Context … building
  the sink without it` → `VoIP sink built without the vendor constructor (state=1)`. The far side opened.
- **Then the near side fails:** 15 ms later `startVoipRecording failed: VoIP mic capture failed to initialise`
  (`VoipCaptureSession.newNearRecord`). Same cause: the MIC `AudioRecord` is built with the public constructor,
  vivo's hook throws, and `runCatching { … }.getOrNull()` swallows it without logging. Four Telegram calls,
  all identical; the app then says "captured only your side — the other app blocks capture", which is false.
- **Phone calls DO record on 2.4.3.** 15:30 incoming call: Resilient recording's capture would not initialise
  (`deliver: voice-call AudioRecord not initialized`), the direct capture would not either
  (`AudioRecord would not initialise for source 4`), and the host fell back to **scrcpy**, which carries the vivo
  workaround itself — 6 s stereo recording published; his library went from 0 to 1 recording.

**Every public-constructor `AudioRecord` in the host fails on vivo:** `VoipCaptureSession.newNearRecord` and
`retakeMic` (app calls — no fallback, so app calls fail), `DirectAudioRecorderSession.openAudioRecord` and
`HandoffSource.deliverToApp` (phone calls — rescued by scrcpy, but Resilient recording can never work on vivo).
2.4.3 fixed only the far-party sink.

**Fix proposal (not built):** one host helper that opens an `AudioRecord` with the public constructor and, only
on the vendor-constructor crash, builds it through `BypassedAudioRecord` (same preset, same identity), logging
the cause; used at all four sites. App calls then work on vivo, and phone calls use our own capture (and
Resilient recording) instead of scrcpy. Also: log the constructor failure instead of swallowing it, and stop
the "other app blocks capture" message when the capture never started.
