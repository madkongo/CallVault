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
