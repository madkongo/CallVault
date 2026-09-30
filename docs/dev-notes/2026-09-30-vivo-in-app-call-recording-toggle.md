# vivo far-side app-call capture IS possible — enable OriginOS's own "In-app call recording" toggle

**Status: 🧪 REPORTED BY A USER 2026-09-30 (scrunscotty, vivo V2507A/iQOO, OriginOS 6 / Android 16), with
screenshots. NOT yet confirmed on a device by the maintainer (we have no vivo).** This REVERSES the
2026-09-29 "not possible without root" verdict — with an important qualifier: that verdict was correct
**with vivo's setting OFF**, which is the default and exactly what the reporter had during all the earlier
one-sided logs.

## What the user found

> "OriginOS comes with a built-in Recorder app that also has an 'In-app call recording' option, and after
> I enabled it, everything is working perfectly in CallVault now!"

The setting (screenshots on file): **OriginOS/Funtouch "Recorder" app → "In-app call recording"**, with:
- **In-app call recording** (master toggle) — "During voice calls in third-party apps, you can use the
  record floating button to record (supports QQ, WeChat, and more)."
- **Auto-start recording** — "Recording will start automatically for every in-app call."
- **Enable apps** list: QQ, WeChat, WeCom, DingTalk, TIM, Lark, VooV Meeting, HUAWEI CLOUD Meeting —
  **only Chinese/enterprise apps; NOT WhatsApp/Telegram/Signal.**

After enabling it, CallVault records the far side of app calls that are **not even in that list**.

## Why this is CallVault working, not vivo's recorder

The per-app "Enable apps" list governs vivo's **own** floating-button recorder — it only covers the
listed apps. The reporter records an app that is **not** on the list, yet the far side now captures in
CallVault. So this is not vivo's recorder; it is CallVault's own `CAPTURE_VOICE_COMMUNICATION_OUTPUT`
capture, and the master toggle lifted the block globally.

## Mechanism — this fits our own research exactly

The 2026-09-29 deep dive found (HIGH confidence) that vivo's **native audioserver zero-fills** the
REMOTE_SUBMIX / `USAGE_VOICE_COMMUNICATION` capture during `MODE_IN_COMMUNICATION` unless a runtime gate
is open — `updateRecordCaptureState … allowCapture → setRecordSilenced(portId, true)`, tied to props like
`persist.sys.audio.vapc.record.share_record.enable` and `AllowLiveAppCaptureMicData`. Our record track
sat on the right REMOTE_SUBMIX input at the right address, `isClientSilenced=false`, and still read zeros
— because the zero-fill is **below** the policy layer. We concluded the only lever was a system prop /
system XML a shell app cannot write.

We were looking for that lever in the wrong place: **vivo exposes it as a user Settings toggle.** Turning
on "In-app call recording" is what flips `share_record.enable` (or the equivalent runtime state) so the
audioserver stops zeroing the shared/remote-submix capture — and CallVault, which already holds the
capture grant as the shell user, immediately gets real audio. Default OFF = zeros (all our earlier logs);
ON = far side captured. That is a complete, consistent explanation of every prior observation.

## What was true and what changes

- **Still true:** with the toggle OFF (the default), a third-party app cannot capture the far side — the
  earlier HIGH-confidence verdict and every one-sided log stand for that state.
- **New:** it is NOT "impossible without root." It is **gated behind a vivo setting the user can enable**,
  no root, no system XML edit, no impersonation. This is the concrete answer the maintainer asked for.

## Actions taken (docs only)

- `docs/SUPPORT.md` §vivo rewritten from "cannot be recorded" → "enable OriginOS Recorder → In-app call
  recording (+ Auto-start), and it works."
- `CHANGELOG.md` vivo lines amended to point to the toggle instead of calling the far side unrecordable.
- Memory [[private-reports-2026-09-28]] and [[voip-recording-feasibility]] updated.

## Open / to confirm
- **Not maintainer-confirmed on a device** (no vivo in the fleet). Mark ✅ only after a vivo user's audio
  is confirmed audible, or the reporter re-confirms explicitly. The reporter's "everything works perfectly"
  is the current evidence.
- Which exact prop/state the toggle flips (`share_record.enable` vs an app-op) is inferred, not read.
- Whether it also needs "Auto-start recording" on, or just the master toggle — the reporter enabled both.
- Consider a vivo-only onboarding/health hint that points users to this toggle (not built; maintainer's call).
