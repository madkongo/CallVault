# Rollback: Call history on the Recordings page

🧪 VERIFYING — merged 2026-11-03; look approved by the maintainer on the OP9 (2.4.9-calls3, three fake call-log rows:
not recorded, missed, declined). Unit tests (CallHistoryTest) pass. To settle: everyday use — real missed/unrecorded
calls show, recorded ones play from the row.

## What changed
- `data/calls/CallHistory`: the call log lined up with recordings (±2 min, one recording per call, phone calls only;
  app calls and unmatched recordings listed as recorded), newest first; filters All / Recorded / Not recorded / Missed.
- `HomeViewModel`: `callsView`, `callFilter`, `phoneCalls` (`PhoneCallReader`, last 500), `callSimLabels`;
  `callHistory` derived with the search line.
- `HomeScreen`: "Recordings | Call history" switch (option D); recorded rows reuse `RecordingRow` with an
  `originLabel` ("Phone call" / app name); `CallHistoryUi.kt`: switch (teal, stated colours), chips (wrap),
  `CallHistoryRow`, `EmptyCalls`. Shared `callTimeLabel` / `formatCallDuration`.
- 13 strings × 15 locales; the tab reads "Call history" (maintainer's wording).

## How to roll back
```
git revert -m 1 04ed3ef9
```
(`04ed3ef9` = the merge of `feat/call-history` into integration/next.)
