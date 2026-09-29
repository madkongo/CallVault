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

## 2026-09-28 16:41 — scrunscotty on v2.4.4-rc1: both sides open, the other side is silent

Log `callvault_report_2026-09-28_16-41.txt` (V2507A, Android 16, 2.4.4-rc1), three Telegram calls.

- **rc1 did what it was built for:** `createSink … built without the vendor constructor (state=1)` and
  `HostAudioRecord: VoIP mic: built without the vendor constructor (state=1)`; capture started, file anchored,
  sync flat (−8…+11 ms), recordings published (20–32 KB). His own voice is recorded.
- **The other side is digital silence:** `far{read=570 sub=5 drop=0}` — the sink delivers every chunk on time —
  but `peak far=0` in every window and `farPartyHeard=false`. Telegram's audio never reaches our mix.
- **Not Telegram:** we recorded Telegram both ways on our own phone (2026-07-26, memory
  `voip-recording-feasibility`), and Telegram's source plays calls as `USAGE_VOICE_COMMUNICATION` with no
  capture opt-out (`DrKLO/Telegram` `VoIPService` / `WebRtcAudioTrack`). So: vivo.
- **Not the check we skip:** vivo's decompiled `AudioRecord` constructor (quoted in scrcpy #3805) calls
  `isSupportSubMixRecording()` only AFTER `native_setup` has succeeded, and the answer only switches on a
  "live app" flag and a Game-cube notification. It gates nothing about the audio.
- **What remains unknown:** whether vivo's audio policy honours a dynamic loop-back mix for voice-communication
  playback at all. scrcpy's audio works on vivo — but through direct REMOTE_SUBMIX output capture, not a policy
  mix. His report has no audio-policy dump, so nothing here can tell which.
- **Also:** 58 mic re-takes in 11 s — the re-take chain from `2026-09-23-voip-near-side-zeros.md`, worse here.

Next step proposed (not built): a diagnostic rc that, during an app call, dumps the audio policy's mixes and
where Telegram's track is routed, so the next log shows whether the mix is registered, matched, or bypassed.

## 2026-09-28 18:18 — scrunscotty on 2.4.4-rc3: identity change did not help; our side is proven correct

Log `callvault_report_2026-09-28_18-18.txt` (V2507A, 2.4.4-rc3).

- The policy's `Inputs (2)` now shows our sink **attached exactly right**: an input on
  `AUDIO_DEVICE_IN_REMOTE_SUBMIX @:<id>:ap:29mixp:0`, client uid 2000, `Source: 8 (REMOTE_SUBMIX)`,
  `Tags: addr=<id>:ap:29mixp:0`, **State: Active** — the same address as our mix
  (`Audio Policy Mix 1 … device address: <id>:ap:29mixp:0`, `RULE_MATCH_ATTRIBUTE_USAGE VOICE_COMMUNICATION`).
- Far side still pure silence: `far{read=507 sub=0 drop=0} peak far=0`, `farPartyHeard=false`. 48 mic re-takes.
- So, end to end on his phone: mix registered ✔, Telegram's tracks copied onto the submix output ✔ (rc2),
  our record client on the matching submix input, active ✔ (rc3) — and the data between them is zeros.
  Attribution to `com.android.shell` changed nothing.

**Conclusion (📐, from three logs):** vivo's audio stack delivers silence on the remote submix for this
capture, below anything an app can configure — consistent with its `vivo_remote_support` allow-list (package +
signing certificate, queried by `isSupportSubMixRecording`), which we cannot join. No further configuration on
our side is left to try with evidence behind it. Remaining ideas are guesses: scrcpy-style whole-output
REMOTE_SUBMIX capture (address 0) on vivo — unknown whether voice-call audio is in it at all.

**What rc1–rc3 did achieve on vivo:** no crash; the user's own side records; phone calls record (and use our
own capture instead of the scrcpy fallback). The identity change (rc3) should be dropped before 2.4.4 — it
fixed nothing and every line kept is a line to maintain.

## 2026-09-28 — deep research: vivo zero-fills remote-submix capture for apps not on its allow-lists

Research pass (maintainer rejected "stop here"): decompiled vivo framework repos (imwangwang/vivo-framework,
imwangwang/vivo-apps, imwangwang/vivo-service, SivanLiu/VivoFramework) and an iQOO firmware dump
(el-vertedero/iqoo_canoe_dump, branch qssi_64-user-17-CP2A…): its `vivoaudiopolicy/*.xml` and the strings of
`bin/audioserver`, `lib64/libaudioclient.so`, `lib64/libaudiopolicymanagerdefault.so`.

- **The gate is vivo-added code in the native audioserver.** Strings: `updateRecordCaptureState,c_uid:%d,t_uid:%d,
  …,allowCapture:%d,…,isInCommunication:%d,isSpecialCapture:%d`; `isRemoteSubMixApp:%d isLiveApp: %d isVgcApp: %d`;
  `setRecordSilenced portId:%d, silenced:%d`; `WhitePkgList allowCapture!`; `AudioServerOrRootUid allowCapture!`;
  `isLiveApp setRecordSilenced true in COMMUNICATION MODE if not open AllowLiveAppCaptureMicData`; params
  `LiveAppList=`, `RemoteProtectList=`, `RemoteSubmixSupp`. It zero-fills the record while leaving it "Active" —
  exactly the three logs.
- **The allow-lists are vivo-system only** (`vivo_audiopolicy_common_whitelist.xml`): LiveApp = `com.duowan.kiwi`;
  record/voip/call "share special" = vivo's own recorders and assistants; `persist.sys.audio.vapc.record.share_record.enable`
  ships off. uid 2000 / com.android.shell is on none — so the rc3 identity change could not help.
- **`isSupportSubMixRecording` does not gate audio** (only a notification + "gamecube" signal) — bypassing it is harmless.
- **MIC is not gated** by this path, which is why the user's own voice records.
- No public report of any non-system app getting non-silent internal audio on vivo/iQOO (scrcpy #3805 is only the NPE).

Rejected: spoofing a listed package (e.g. com.duowan.kiwi) on the AttributionSource — AudioFlinger replaces a
package the calling uid does not own, and impersonating another app is not acceptable. Not done without the
maintainer: `AudioSystem.setParameters("LiveAppList=…")` — it would change a vivo system audio setting.

**rc4 (2.4.4-rc4, 20434, private):** read-only diagnostics to confirm the branch on his phone in one call —
vivo's own decision lines (`allowCapture` / `setRecordSilenced` / `LiveApp` …) pulled at the end of a one-sided
app call; vivo audio properties; the lists the audioserver consults read with `AudioSystem.getParameters`
(`LiveAppList`, `RemoteProtectList`, `RemoteSubmixSupp`, `APPShare`); per-record `isClientSilenced` + routed device.

## vivo on 2.4.4-rc4 — the diagnostics answered (report 2026-09-29 19:18)

📐 Read from the reporter's log; nothing new built. 8 Telegram calls, all one-sided (`farPartyHeard=false`); no
phone call in this log.

- **Our far record is NOT silenced as far as Android's policy knows:** `silenced=false`, routed to device 25
  (REMOTE_SUBMIX) at our own mix address, every call. So vivo does not mark the record silenced through the
  policy manager. That fits a native zero-fill in audioserver/AudioFlinger below the policy (the firmware
  finding) but does not prove it: none of the strings we grep for (`allowCapture`, `setRecordSilenced`,
  `updateRecordCaptureState`) appear at this log level.
- **vivo's lists, read with `AudioSystem.getParameters`:** `LiveAppList` = only Chinese live-streaming apps
  (com.duowan.kiwi, com.duowan.live, com.kuaishou.nebula, com.kwai.livepartner, com.smile.gifmaker,
  com.ss.android.ugc.aweme(.lite), com.ss.android.ugc.livepro); `RemoteProtectList` empty; `RemoteSubmixSupp=true`;
  `APPShare` empty.
- **New:** vivo's framework inside each app calls `AudioSystem.setParameters("PlaybackCaptureProtectSupport=<pkg>")`
  whenever that app starts playing (pid = Telegram's own; also Messenger `com.facebook.orca`). Public GitHub logs
  show the same line for Instagram and ordinary apps, so it is a per-playback registration of the player's package,
  not a list of protected apps. No documentation exists (web + GitHub, 2026-09-29).
- **rc4's end-of-call dumps block the main thread ~5 s per one-sided call** in his log too (19:18:15.78 →
  19:18:20.78) — the freeze fixed in rc16 (`e598175a`). He must not stay on rc4.

**Conclusion (unchanged, better supported):** vivo zero-fills the far side below anything an app controls; the only
lever found is adding a package to `LiveAppList` via `AudioSystem.setParameters`, which changes a vivo system
audio setting and is the maintainer's decision. His own voice and phone calls are what 2.4.4 can deliver on vivo.

## Before touching `LiveAppList` — what the audioserver binary says (2026-09-29, 📐 strings only)

`strings` of `system/system/bin/audioserver` from the iQOO dump (same firmware family as the reporter's V2507A):

- **`LiveAppList` is probably the WRONG lever.** vivo's own messages: `isLiveApp setRecordSilenced true in
  COMMUNICATION MODE if not open AllowLiveAppCaptureMicData` and `AllowLiveAppCaptureMicData %d, should mute %d`.
  A live-streaming app is *silenced* during a call unless a separate switch is on. Adding CallVault there could
  mute the part that works today (his own voice). **Not built.**
- **The deciding check is an app-op check:** `checkop cause allowCapture false!currentUid = %d @@@` and
  `checkop cause allowCapture false but force allowCapture true pkgName:%s`. The force-allow list is fed by the
  firmware XML whitelists (`vivo_audio_policy_record_not_silence_whitelist`, `…mustrecord_whitelist`,
  `…needrecord_voip_whitelist`, …) — not writable by an app.
- **vivo can log its decision:** the binary reads the log tag **`log.tag.audio.vivo.verbose`**. A `log.tag.*`
  property is settable by the shell, only changes logging, and is cleared by a reboot. With it on, the
  `updateRecordCaptureState … allowCapture …` / `checkop …` lines should appear and name the exact reason.
- Other switches seen: `persist.sys.audio.vapc.record.share_record.enable` (off),
  `persist.sys.audio.vapc.voip.reroute_record.enable`, `persist.sys.audio.vapc.record.enable`.

Proposed next private build (awaiting the maintainer): on vivo only, turn that log tag on at each app-call start
(auto-heals after a reboot) and pull vivo's decision lines at call end. Choose a lever only once they are read.

## vivo on rc18 — verbose tag ON, decision still not exposed (report 2026-09-29 21:53)

📐 Read from the reporter's log. 4 Telegram calls, all one-sided.

- **The verbose-log auto-heal works.** First call `before=[] after=[true]` (cleared — reboot/first run),
  next calls `before=[true]` (survived). So `vivo_verbose_arm` re-arms per call and the reboot signal is real.
- **But vivo's decision lines never appear**, even with `log.tag.audio.vivo.verbose=true`: no
  `updateRecordCaptureState`, `setRecordSilenced`, `allowCapture`, `checkop` in `-b main -b system`. On this
  retail/release-keys build those verbose ALOGs are compiled out or written to a buffer the shell doesn't get;
  the vivo bool prop did not re-enable them. Only our own lines and `PlaybackCaptureProtectSupport=<pkg>` show.
- **What Android itself reports about our record (rc4 `recordStatus`):**
  `far{silenced=false routed=25(REMOTE_SUBMIX):<ourAddr> source=8(REMOTE_SUBMIX) state=3}`,
  `near{silenced=false routed=15 source=1(MIC) state=3}`. So the far record is attached to the correct submix at
  our mix address, RECORDING, and **the policy does not mark it silenced** — yet every far read is pure zeros.

**Verdict (HIGH confidence, convergent):** the far side is zero-filled in vivo's **native** audioserver, below the
policy layer — which is exactly why `isClientSilenced=false` (the policy never denied us; the buffer is just
zeroed). The gate is on system-only allow-lists (`LiveAppList` = Chinese streaming apps; firmware string
`isLiveApp setRecordSilenced true in COMMUNICATION MODE if not open AllowLiveAppCaptureMicData`) and system-only
props (`persist.sys.audio.vapc.*`, not shell-settable). The one app-reachable lever, `LiveAppList` via
setParameters, its own binary says SILENCES a live app in a call unless a system prop is on. No public case of a
non-system app capturing internal call audio on vivo/iQOO. **Not possible for a third-party app without root or a
system/vendor privilege we do not have.** The only remaining diagnostic that could add anything is reading ALL
logcat buffers (`-b all`) to be certain vivo isn't logging its decision somewhere unread.

## Deep research verdict — 4 parallel agents, 2026-09-29: NOT POSSIBLE for a third-party app on vivo (no root)

The maintainer asked for concrete evidence, possible or not. Four independent agents (firmware gate; PlaybackCaptureProtectSupport/capture-policy; alternative routes + log buffer; real-world prior art) converged, HIGH confidence:

1. **Why the far side records on our phones but not vivo.** Capturing call audio needs `CAPTURE_VOICE_COMMUNICATION_OUTPUT` (a permission separate from `CAPTURE_AUDIO_OUTPUT`). `com.android.shell` HOLDS it (Shell manifest), and AOSP's `AudioService.registerPolicy` calls `setVoiceCommunicationCaptureAllowed(true)` on our mix because the caller holds it — so on AOSP (OP9/OP12) our submix legitimately captures the far party. vivo's proprietary audioserver **ignores that grant** and zero-fills the record anyway. (AOSP `AudioPolicyMix.cpp mixMatch`: a VOICE_COMMUNICATION track joins a submix only if `mVoiceCommunicationCaptureAllowed`; vivo overrides this below the Java layer.)
2. **Every lever is system-only.** This ROM's `plat_property_contexts`: shell may write `log.tag.*` (why our verbose write worked) but NOT `system_prop`/`audio_prop`, so `persist.sys.audio.vapc.*` is denied even to `adb root`. The allow-lists (`LiveAppList`, `mustrecord`, `record_not_silence`, `RemoteProtectList`) are static XML in the **read-only system partition**, pushed to audioserver by system_server only. `AllowLiveAppCaptureMicData` is a framework-internal runtime flag. The `checkop` path is standard OP_RECORD_AUDIO, which we already pass (hence `isClientSilenced=false`) — the block is package-identity + call-state, no app-op flips it.
3. **No alternative route.** VOICE_CALL/VOICE_DOWNLINK read the cellular telephony path — a VoIP call never traverses it (silence, not a vivo issue). A usage-matched mix, BT/earpiece render, or pre-arming before MODE_IN_COMMUNICATION all carry the same voice-comm stream and hit the same gate; audioserver re-evaluates on the mode transition, so nothing is grandfathered. Every escape needs vendor root/system (patch the HAL, tinycap the DSP, LSPosed-hook isLiveApp, or edit the system XML).
4. **Prior art: none.** No non-root third-party app has ever captured VoIP far-party audio on vivo/iQOO (scrcpy #3805/#4582/#4602 = the same disable). Two stacked barriers: Android's playback-capture opt-out for public apps, and vivo's audioserver zero-fill for privileged submix. Every "success" needed root/Xposed (playback-capture, which VoIP still opts out of) or system VOICE_CALL (cellular only). vivo's whitelist has only ever been changed by editing the system image (root).

**FINAL: the far party of an app call cannot be recorded by CallVault on stock vivo without root.** The verbose-log path is a dead end too (lines almost certainly compiled out with `LOG_NDEBUG` on the retail build; even reading `-b all` would likely show nothing). 2.4.4 ships with vivo app-call far-side unsupported, reported honestly after each call; his own voice and carrier calls record. Recommend NOT spending another diagnostic build.
