# Report from mac: Samsung S24, phone calls record only his side

📐 CALCULATED 2026-10-25, read from his two log pairs (debug reports 18/19, system reports 7/8); nothing built.

**Phone:** Samsung Galaxy S24 SM-S921U (US, Snapdragon), Android 17, build S921USQU6EZIF; CallVault 2.4.7,
standalone, audio source voice-call, minimum length 0.

## What the logs show

Two outgoing carrier calls (09:28 and 09:32). **Both were Wi-Fi Calling.** Samsung's audio HAL logged
`bearer=WIFI;g_call_band=swb;g_call_ims_codec_type=evs` both times, and both calls ran through
`com.android.phone`. The two attempts are the same in every respect the logs can show. Nothing in them shows
a different phone app being used. The second attempt just repeated the first.

- The carrier recording ran normally: handoff, 29.1 s and 26.1 s, nothing dropped. He reports that only his
  own side is audible. That is the Samsung Wi-Fi Calling limit from #45
  (`2026-09-30-samsung-vowifi-wifi-calling-not-recordable.md`): Samsung renders the far side of a Wi-Fi call
  outside the call tap, and no non-root source has it. #45's Fold recorded silence; this S24 at least gets
  the mic.
- New detail: when our voice-call capture starts during a Wi-Fi call, Samsung's HAL opens its own
  REMOTE_SUBMIX output `crwfcmix:<n>` (16 kHz) and closes it at hang-up. The name suggests a Wi-Fi-call
  recording mix, probably the hook Samsung's own recorder uses since One UI 6.1. It is a lead, not a fix: we
  do not know who can read it.

## Our bug: a stray 1-second "app call" at every hang-up

At hang-up, about 1.5 s before Android broadcasts IDLE, both calls also produced a separate app-call recording
(`…_voip.m4a`, 1.0 s), with the warning **"captured only your side — the other app blocks capture"**. That
warning is the message mac quoted. It comes from this stray file, not from the phone app he used.

Why: on Samsung a Wi-Fi call keeps the audio mode at `MODE_IN_COMMUNICATION`, the same mode an app call uses.
`VoipTelephonyGate` blocks app-call starts only while Telecom has a managed call. At hang-up Telecom drops the
call before the mode leaves IN_COMMUNICATION, so for that moment the mode looks like an app call starting.
His minimum length is 0, so nothing deletes the file. With a minimum set, it is discarded, as Marianna's
1.2 s one was.

Candidate fix (📐, not built): do not start an app-call recording while the carrier recorder is still running,
or for a few seconds after a carrier call ends.

## What would help him now

Phone app ▸ Settings ▸ Wi-Fi Calling ▸ **Mobile network preferred** (or Wi-Fi Calling off). Both sides then
record, as they did for #45's reporter and the upstream Samsung users.
