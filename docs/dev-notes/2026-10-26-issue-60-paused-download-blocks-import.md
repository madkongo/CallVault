# Issue #60: a paused model download blocks importing that model

🧪 VERIFYING — fix on `fix/model-paused-import-60` (b52bcf97), merged into integration/next 2026-10-26.
Emulator-proven only. To settle it: on a phone, pause a speech-model download part-way, check the
Discard row appears and works, then import the same model from a file.

Reporter: AbdulAziz-Hatem (Samsung, Android 12, 2.4.7), who also sent PR #59 (Arabic).

## The three claims

| claim | in the code | outcome |
|---|---|---|
| A paused speech model has no Discard button | True. `ModelRows.kt` Paused showed Resume + Import only. Its comment said "the delete row below reclaims it"; no delete row is shown in that state. | Fixed |
| The paused `.part` counts against free space during import | True. `importUnderLock` checked `hasRoomFor(usableSpace, size)` first; the `.part` was removed only after a successful import (`finalizeImport`). | Fixed |
| Returning from the file picker lands on Home | Not reproduced. The report points at `AppNavigationViewModel._manualScreen`, but nothing sets it to Settings any more. Settings is a drawer (`rememberDrawerState`) and its open page is `rememberSaveable`. | No change |

## The fix

- **Discard row** for Paused, and for ImportFailed with a partial, behind the same confirm dialog as the
  summariser. It reuses `summary_model_discard*` / `summary_discard_confirm_*`, which don't name the
  model, so no new strings and no new translations. Discard calls the existing delete, which already
  removes the `.part` and the import temp file.
- **`ModelDownloadPolicy.importRoom`**: FITS / FITS_IF_PARTIAL_DISCARDED / NO_ROOM. The `.part` is
  deleted only when it is the one thing in the way. If the import would not fit even without it, it is
  kept, so a refusal loses no progress. Accepted cost: if the `.part` was discarded and the picked file
  then fails its digest check, the paused progress is gone. The user chose to replace it.
- The worker logs the decision: `Import room for <id>: free=… MB paused=… MB -> <room>`. Sizes are in MB
  because AppLogger masks long digit runs as phone numbers. The first emulator run printed
  `free=[PHONE_REDACTED]`.

## Evidence (emulator mymemory-test, API 36, debug build, 2026-10-26)

- Wizard models step, small model, fake 150 MB `.part`: "Resume the download · 150 MB of 190 MB",
  then "Discard what was downloaded · Frees 0.2 GB", then Import.
- Discard: confirm dialog "Discard the download? … 0.2 GB …"; on Discard the `.part` was deleted and
  the rows went back to "Download model · 190 MB".
- Tight space: `free=376.0 MB paused=143.1 MB -> FITS_IF_PARTIAL_DISCARDED`, "discarded the paused
  download to make room", then "Installed imported model small-q5_1". The old check needs 181 + 250 MB
  free and would have refused.
- With room to spare: `-> FITS`; the `.part` was kept until `finalizeImport` removed it.
- Picker return: with CallVault's process killed (`run-as … kill -9`) while the picker was on top,
  picking the file restarted the app in the Settings drawer on the model page, and the import
  completed. Setting `always_finish_activities` to 1 by `settings put` did not take effect, because
  no destroy appeared in the event log, so that run proves nothing.
- Side observation: `File.usableSpace` read about 85 MB more than `df` showed (456 vs 371 MB),
  presumably cache the system can clear.
- Unit tests: four new cases in `ModelDownloadPolicyTest`; the full suite passes.

Not tested: a real phone, the summariser's import path (same worker, so same code), and Android 12
specifically.
