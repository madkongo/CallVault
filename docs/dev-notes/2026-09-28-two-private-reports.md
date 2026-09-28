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
