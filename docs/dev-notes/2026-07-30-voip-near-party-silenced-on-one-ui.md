# VoIP near-party capture drops out on One UI — the platform silences it

**Device:** Galaxy S24 FE (SM-S721B), Android 16 / API 36, One UI.
**Compared against:** OnePlus 12, which records the same call cleanly.
**Status:** cause established, **fixed** — the near feeder re-takes the mic when silenced.

---

## What the platform says, in its own words

`dumpsys audio`, during a WhatsApp call, `uid:2000` = our daemon (`com.android.shell`):

```
16:56:08.435  rec update  uid:2000   src:MIC  not silenced  pack:com.android.shell
16:56:08.876  rec update  uid:10030  src:MIC  not silenced  pack:com.whatsapp
16:56:08.909  rec update  uid:10030  src:MIC  SILENCED      pack:com.whatsapp
16:56:08.911  rec update  uid:2000   src:MIC  not silenced  pack:com.android.shell
16:56:12.422  rec stop    uid:10030  src:MIC  SILENCED      pack:com.whatsapp
16:56:12.446  rec update  uid:2000   src:MIC  SILENCED      pack:com.android.shell
16:56:12.465  rec update  uid:10030  src:MIC  not silenced  pack:com.whatsapp
16:56:18.560  rec update  uid:2000   src:MIC  not silenced  pack:com.android.shell
16:56:21.067  rec stop    uid:2000   src:MIC  not silenced  pack:com.android.shell
```

**Two mic clients, and One UI silences one of them at a time.** It is not an error and not a refusal:
the loser keeps receiving buffers, and those buffers are digital zeros. Arbitration re-runs each time
WhatsApp restarts its capture (note the changing `riid`), and the most recent starter appears to win.

Our capture lost for **~6 s of a 21 s call** — not the whole call.

## It matches the audio exactly

That call started at 16:55:59.8, so the silenced window maps to **file time ~12.6 s - 18.8 s**. The
recording contains a stretch of perfectly flat **-107.3 dB** (±1 LSB, i.e. decoded digital silence)
from **11.5 s to 19.5 s**. Same window, within about a second.

Flatness is the tell: a live mic always has a fluctuating noise floor. A dead-constant floor is
synthesised silence.

## Why `substituted` did not catch it

`VoipCaptureSession.captureLoop` counts only chunks that never arrived:

```kotlin
val n = qNear.poll(CHUNK_WAIT_MS, ...) ?: silence.also { substituted++ }
```

A silenced `AudioRecord` still delivers chunks — full of zeros. The log for that call read
`21s, 2 silence-filled chunks, farPartyHeard=true`, which looks healthy and is not. **We have a
far-party silence detector (`farPartyHeard`) and no near-party equivalent.**

## The far party is unaffected

It arrives via the policy loopback mix, logged as `src:REMOTE_SUBMIX`, and never appears as silenced.
Different mechanism, outside the mic arbitration entirely.

## The obvious escape hatch is locked

The platform defines the permission for exactly this:

```
android.permission.BYPASS_CONCURRENT_RECORD_AUDIO_RESTRICTION
protectionLevel: signature|privileged
```

`com.android.shell` does not hold it, and `pm grant` refuses:
*"not a changeable permission type"*. Shell access does not open it; this needs root or a
platform signature.

## Established vs not

**Established:** One UI silences the shell-uid MIC capture intermittently while a VoIP app holds the
mic; the loser gets zeros; the drop-outs correlate with flat silence in the output; the far-party path
is unaffected; the bypass permission is ungrantable; the OP12 records the same call cleanly.

**Not established:** *why* ColorOS permits what One UI arbitrates. Whether the drop-outs are
predictable or avoidable. Whether any other audio source is exempt.

## Two corrections worth keeping

1. **"The near party doesn't work on Samsung" was wrong** — it works most of the time and drops out
   intermittently. The first log excerpt showed a silenced event and it was over-read as a permanent
   state.
2. **A same-room result was attributed to acoustic bleed** and the maintainer rejected that from
   listening — correctly. The intermittency model explains every observation without invoking bleed,
   including why outcomes varied between tests that looked identical: it depends on whether speech
   landed inside a silenced window.

The code carries the same class of over-generalisation: *"`MIC` is not silenced"* in
`VoipCaptureSession`'s doc comment. True on ColorOS, false here.

## Fixed 2026-07-30 — re-take the mic

The arbitration is **symmetric and winnable: the most recent starter wins.** Our capture is silenced
when the VoIP app restarts its own; restarting ours wins it straight back. `feeder()` now watches the
near source for chunks that are *exactly* zero — a real mic never returns exact zeros, even a silent
room carries a noise floor — and after `SILENT_CHUNKS_BEFORE_RETAKE` of them reopens the
`AudioRecord`.

| | before | with re-take |
|---|---|---|
| longest silent run | **8.0 s** | **1.5 s** |
| silence-filled chunks | holes throughout | 6 in a 50 s call |

The re-take fired 10 times in that call and every subsequent capture registered `not silenced`. What
remains is detection latency; `SILENT_CHUNKS_BEFORE_RETAKE` trades gap length against re-taking more
eagerly.

**Why this is safe, which is the part that took longest to establish:** with the phones in **separate
rooms**, the far end still heard everything while our capture held the mic. There is no acoustic path
between rooms, so the VoIP app was still transmitting — it keeps working regardless of what the
arbitration reports about it. Winning the mic does not cost the user their conversation.

## Three wrong turns, worth keeping

1. **"No source can survive the arbitration."** Wrong, and worse, untested — it followed one HOTWORD
   failure. A five-source survey in a single call showed all candidates reading **identical** RMS
   values second by second: they are the same stream, and the source is irrelevant. Testing one source
   per call could never have shown this, because the arbitration depends on when the VoIP app restarts
   its own capture, so no two single-source calls are the same experiment.
2. **"Taking the mic breaks the call."** Wrong. Derived from `dumpsys` reporting the VoIP app's
   capture silenced and stopped, without checking it against the separated-rooms evidence that already
   existed — which showed the far end hearing speech that had no acoustic path to it.
3. **Acoustic bleed was invoked twice** to explain results the maintainer had already excluded by ear
   and by experiment. When someone who can hear the recording says it is not bleed, that is data.

## What to do

1. **Report it.** Mirror `farPartyHeard` with a near-party silence check, so a recording with holes
   says so instead of looking fine. Cheapest, and it turns an invisible failure into a visible one.
2. ~~**Try `HOTWORD` as the near source.**~~ **TRIED 2026-07-30 — DEAD.** It is refused *silently*:
   the `AudioRecord` constructs and reports `STATE_INITIALIZED`, so it looks fine, but the first
   `read()` returns 0 and `dumpsys audio` never shows a `rec start` for it at all. The platform
   accepted the object and never registered the capture.

   ```
   17:26:09.478  VoIP near-party source: hotword     <- opened, STATE_INITIALIZED
   17:26:09.723  near read=0, feeder ending          <- read() returned 0 immediately
   17:26:25.443  finished: 2s, 127 silence-filled chunks
   ```

   Reverted. Do not retry without a way to verify the capture actually registers — `STATE_INITIALIZED`
   is not that verification.
3. **A dead near feeder truncates the WHOLE recording — fix this regardless.** Found by the HOTWORD
   experiment, but independent of it. When the near feeder ends, every `captureLoop` cycle waits
   `CHUNK_WAIT_MS` for a chunk that will never arrive, and the loop is paced by those timeouts — so it
   encodes **slower than real time**. An 11 s call came out as ~6 s of audio with 127 silence-filled
   chunks. Losing one source should cost that source, not the recording's length or the far party's
   audio. One UI's arbitration is exactly the kind of thing that could kill that feeder mid-call.

4. **Do not** re-start our capture on detecting silence to win arbitration back — it would fight the
   VoIP app for the mic during a call, and losing that fight degrades the user's actual conversation.

## 2026-09-23 — the re-take's trigger is too broad

On phones where WhatsApp captures with `VOICE_COMMUNICATION` (OP9, OP12) there is no arbitration to
lose, yet the 15-zero-chunk trigger still fires through every pause and re-opens the mic in chains.
The re-take remains right for the case measured here (WhatsApp on `src:MIC`); what is wrong is
deciding "silenced" from zeros alone. See `2026-09-23-voip-near-side-zeros.md`.
