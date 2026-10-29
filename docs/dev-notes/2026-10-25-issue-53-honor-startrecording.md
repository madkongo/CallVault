# Issue #53: Honor MagicOS 11 refuses `startRecording()` in the recorder

🧪 VERIFYING — fix merged into integration/next 2026-10-25 (1dc02189, merge 87f49364), shipping in 2.4.7.
Needs the reporter's Honor (Magic 9 Pro Max, MagicOS 11.0.0.111) and one WhatsApp call.

## What happens

Reporters: rik297981 (Magic 8 Pro, MagicOS 11 beta) and jlp9989-sudo (Magic 9 Pro Max WKL-AN20, stable
MagicOS 11.0.0.111, Android 17, 2.4.6, built-in mode). App calls are detected and both captures open, then:

```
E/CV:RecorderServer: startVoipRecording failed: null
java.lang.NullPointerException
  at java.util.Objects.requireNonNull(Objects.java:222)
  at android.media.AudioRecord.startRecording(AudioRecord.java:1573)
  at com.baba.callvault.server.VoipCaptureSession.startInternal(VoipCaptureSession.kt:151)
```

jlp9989-sudo disassembled the ROM's `AudioRecord.startRecording()`. For any source other than CAMCORDER, Honor's
audit block runs `Objects.requireNonNull(ActivityThread.currentApplication())`, then reads the app's label.
Our recorder hosts have no Application: the `app_process` daemon never has one, and `ShellAttribution`
deliberately clears the one Shizuku loads, because our own Application makes AppOps refuse the capture.
Phone calls survived because the direct path fails over to scrcpy, and scrcpy installs a fake Application.

The 2026-10-13 reply on the issue blamed an Android 17 beta framework bug. That was wrong: the ROM is stable,
and the cause is Honor's added code.

## The fix

- `VendorStartCrash` recognises the crash by its shape (an NPE thrown by code `startRecording` called), like
  `VendorConstructorCrash` does for vivo.
- `HostAudioRecord.start()` is now how the recorder starts every capture (VoIP far/near, resume, mic re-take,
  direct). Only on that crash does it run `ShellApplication.install()`, then start the same record again. The
  crash comes before the native start, so the record is still fresh.
- `ShellApplication` is scrcpy's `Workarounds` + `FakeContext`. It makes an ActivityThread if the process has
  none, and sets `mInitialApplication` to an Application over the system context. That Application reports
  package, op package and attribution as `com.android.shell` (uid 2000), so the shell identity is kept.
- Every other phone never hits the crash and keeps exactly the identity it has today.

## Evidence

- Unit tests: `HostAudioRecordStartTest` covers the reported trace, AOSP NPEs, a normal start, repair and
  retry, other errors and a failed repair.
- Emulator, 2026-10-25 (mymemory-test, API 36). A test-only build forced the install at recorder start; it
  was not committed. The daemon was launched by hand as uid 2000:
  - `currentApplication.pkg=com.android.shell` and `currentOpPackageName=com.android.shell`; binder delivery
    still ok;
  - `CaptureSelfTest`: arm=true, sinkRec=RECORDING, micRec=RECORDING, sinkDb=-17.5 (the tone was heard;
    the emulator mic is silent, as always).
- Not run: the Shizuku host. It already has an ActivityThread, so only the `mInitialApplication` swap applies.
- Not run: a real Honor phone.

## Side finding: the mode-switch check script is stale

On 2026-10-25, `tools/device-checks/mode-switch-check.sh` reported 20 failures on the OP9 with 2.4.7. The
script navigates Settings ▸ General ▸ Experimental, which the new default layout no longer has, so every row
read "-". Its VoIP-policy check also reads the event log rather than the live policies, and app-call
recording was off on the OP9 after `pm clear`. Neither is an app regression; the script needs updating for the
new layout.

## 2026-10-25 field report on 2.4.7 (rik297981, two Honor Magic 8 Pro BKQ-N49, MagicOS 11.0.0.130)

🧪 VERIFYING — reporter's report, not confirmed by the maintainer. rik297981 says WhatsApp calls now record on
both phones. The logs agree: "the ROM's startRecording() needs an Application (null); giving the recorder a
shell one" → "started after the shell Application was installed", then both sides captured. Both phones are
standalone mode, so the Shizuku host is still untested.

**New problem, on the second phone (MARIANNA): the recording runs on after the call ends.** The reporter says
it keeps recording until WhatsApp is closed. The two logs are the same calls seen from each end
(sidecar names match):

| call | Riccardo: answered → ended | Marianna: answered → ended | overrun |
|---|---|---|---|
| 2 | 12:24:53 → 12:25:31 | 12:24:54 → 12:27:28 | ~1 min 57 s |
| 3 | 12:29:02 → 12:29:50 | 12:29:02 → 12:30:07 | ~17 s |

In call 3 the far side's peak drops to 0 right after 12:29:50, so the call really was over on her phone too.
Riccardo said he hung up both times, so the overrun is on the phone whose far party hung up, which in both
calls was also the caller.

Cause, from the code: `VoipCallDetector` ends an app call **only** when the audio mode leaves
`MODE_IN_COMMUNICATION`. On her phone WhatsApp kept the mode after the call had ended, until she closed it.
There is no second end signal.

Candidate fix, 📐 not built: while the mode still says "in a call", also watch `CallEvidence` (no
VOICE_COMMUNICATION capture by any app means nobody is on a call), and end once it has been absent for a few
seconds. Unknowns that need checking before it ships:
- Does WhatsApp keep its capture open while muted? If not, muting would end the recording, which is far worse
  than an overrun.
- Does Honor's ROM answer `getActiveRecordingConfigurations()` from the app?

Also open: Riccardo's first call (12:21:50–12:22:14, sidecar "MARIANNA") is missing from her log; her phone
logged nothing between 12:17 and 12:23. Nobody has explained it yet, and we need to ask.

### 2026-10-25 research: a better end-of-call signal (📐 code and web reading only)

The maintainer rejected anything that could end a recording because the user muted. Options:

- **ShizuCallRecorder v2.0.0 (2026-10-24, read at tag v2.0.0, not built).** App calls are detected only by
  an `InCallService` declared with `INCLUDE_SELF_MANAGED_CALLS` and `IN_CALL_SERVICE_UI=false`. Telecom binds
  it because the `android:manage_ongoing_calls` app-op is granted through the shell, with companion-role
  fallbacks for Chinese ROMs. The call ends on `onCallRemoved`, which is Telecom's own answer and does not
  depend on audio mode, mute or silence. Their phone-state path covers carrier calls only. Limit, in their
  own release notes: apps that do not report calls to Telecom are invisible (their #131 plans a CallStyle
  notification listener for those). One of their testers (#13, 2026-10-18) says WhatsApp records
  "flawlessly", so WhatsApp must report to Telecom on that phone.
- **This contradicts our `CallEvidence` comment** (OP12, 2026-09-06: "WhatsApp registers no self-managed
  ConnectionService"; `isInCall()` false during a WhatsApp call). It may differ by phone or by WhatsApp
  version. Settle it with `dumpsys telecom` on a phone that has WhatsApp, during a call.
- **The WhatsApp ongoing-call notification** (CallStyle, Android 12+) goes away on hang-up whatever the audio
  mode. It needs notification-listener access, which the shell can grant. It does not depend on mute.
- **The capture-evidence idea** (no VOICE_COMMUNICATION capture) is rejected: it is mute-sensitive.

Proposed order: (1) measure on a phone with WhatsApp which signals change at hang-up; (2) build Telecom
`onCallRemoved` as the end signal where the app reports to Telecom, with the notification as the fallback,
and the audio mode kept as the last resort.

### 2026-10-25 15:47 reporter's answer (rik297981)

"The problem with the recording not stopping on Marianna's phone occurs when Riccardo ends the conversation.
Marianna's phone returns to the chat. Only when she switches to the desktop do I feel the vibration that
indicates she's finished recording. Riccardo and Marianna have the same settings."

So the overrun happens when **the other party hangs up**: WhatsApp drops back to the chat screen and keeps
`MODE_IN_COMMUNICATION` while it stays in the foreground. The mode is released when she leaves WhatsApp for the
home screen; the vibration is our stop haptic. This matches the log: Riccardo hung up both overrun calls.
Questions 1 (does a call notification or bar stay) and 3 (the missing 12:21 call) were not answered.

It should be reproducible on any phone with WhatsApp: the other party hangs up, you stay on the chat screen,
and you watch the mode. That is the measurement planned above (mode, Telecom calls, WhatsApp call
notification, WhatsApp audio tracks, once a second), now with a known trigger.

## OP12 end-of-call probe — 2026-10-26 (📐 measured once, one phone)

Maintainer's WhatsApp call on the OP12 (6011b07e, 2.4.7), read-only probe sampling every ~1.5 s
(`voip-end-probe.sh`). Muted ~5 s mid-call; far side hung up; stayed on the chat ~30 s, then home.

| Signal | During call | At far-side hang-up (16:29:01) | Mute |
|---|---|---|---|
| Audio mode | MODE_IN_COMMUNICATION (owner WhatsApp uid 10394) | → MODE_NORMAL immediately | no change |
| WhatsApp VOICE_COMMUNICATION player | `started` | → `idle` same sample; gone ~16 s later | no change |
| VOICE_COMMUNICATION record count | 1 | → 0 same sample | no change |
| Telecom call list | 0 calls; WhatsApp not in Telecom | — | — |
| WhatsApp notifications (probe grep) | nothing matched | — | — |

Results:
- **No overrun on the OP12.** Call 16:28:34–16:29:01; recording 28.4 s, i.e. hang-up + the 1.5 s
  `END_DEBOUNCE_MS`. The "recording saved" notice the maintainer saw ~10 s later is post-save work,
  not recording time. So the overrun is Honor-specific: WhatsApp there keeps the mode after hang-up.
- **Mode is mute-safe here**: muting changed none of the signals.
- **Telecom `onCallRemoved` is dead as a candidate** for WhatsApp: it never appears in Telecom on this phone.
- **Notification candidate: inconclusive** — the probe's grep matched nothing during the call (likely the
  grep, not proof the notification is absent). Needs a better probe before ruling it in or out.
- **New candidate: WhatsApp's own VOICE_COMMUNICATION playback going `started` → `idle`** at hang-up. It is
  playback, so mute cannot touch it. Only useful if on the Honor this player goes idle while the mode stays
  held — unknown until the reporter's phone is measured.

Next step proposed: a test build for rik297981 that only LOGS these signals at hang-up (no behaviour
change), so their debug log shows whether the player goes idle while the mode stays.

## Log-only test build for the reporter — 2026-10-26 (🧪 VERIFYING)

`2.4.7-endprobe2` = the v2.4.7 tag + `VoipEndProbe` (branch `diag/voip-end-probe-53`, 1fa45102). Logs
`endprobe[...]` lines (mode, vcPlay = active voice-call players, every player's usage:contentType, vcRec)
on change and every 5 s while an app call is up, and for 60 s after. Decides nothing.

Checked on the OP12 with endprobe1 (same probe, counted "speech" instead): WhatsApp's call audio is
`[2:0]` — usage VOICE_COMMUNICATION, content type UNKNOWN. So **the usage is visible to the app on this
ROM** (VoipCallDetector's header says it is anonymised; that was true where it was written, not here).
Hang-up: player gone 16:44:44.303, mode NORMAL 16:44:44.330, "VoIP call ended" 16:44:46.887 (debounce).

What the reporter's log will settle: after the far side hangs up on the Honor, does `vcPlay` drop to 0
while `mode=IN_COMMUNICATION` holds? If yes, "the call app's voice-call playback stopped" is a mute-proof
end signal for that phone. If the player stays too, WhatsApp holds the whole call open and only the
notification route is left.

## 2026-10-29 — endprobe2 results: the "WhatsApp keeps the mode" theory was WRONG (📐 read from two reports)

rik297981 ran 2.4.7-endprobe2 on two Honor BKQ-N49 phones (MagicOS 11 / Android 17), 4 WhatsApp calls between them.

- **Riccardo's phone: no overrun on any call.** At every hang-up WhatsApp's voice-communication player `[2:0]`
  disappeared, then the mode went NORMAL 85–106 ms later; "VoIP call ended" ~2.5 s after (1.5 s debounce). The mode
  **never** stayed IN_COMMUNICATION after a hang-up. Same pattern as the OP12.
- **Marianna's phone: CallVault's app process stops running for tens of seconds while WhatsApp is on screen.** The 5 s
  heartbeat and the answer-wait polling (both app-process) go silent while the recorder host (uid 2000) keeps logging;
  queued callbacks arrive in a burst when the app wakes. Effects: calls 1–2 (she was called) were "detected" only after
  they ended → 1 s files, "only your side" warning (wrong blame), discarded (<10 s); calls 3–4 saved with ~10–11 s
  overrun — including when SHE hung up, so the overrun is not "remote hang-up only" either.
- Likely cause (guess, strongly supported): Honor power management freezing/throttling the app process.
- **Earlier claims in this note that WhatsApp holds MODE_IN_COMMUNICATION after the call are not supported** — the
  probe shows the mode drops on time; the late end came from our frozen app process. Do not build on that theory.
- Player-stopped end signal: led the mode by ~0.1 s on all hang-ups; brief 10–36 ms blips at answer/route changes
  (1.5 s debounce covers them); far-side silence did not stop the player. Still useless while the app is frozen.
- Next steps (not built): compare Honor App-launch/battery settings between the two phones; move call-end detection
  into the recorder host process; ignore a "call detected" signal when a fresh mode read is already NORMAL, and don't
  show the one-sided warning for recordings under the minimum length.
