# Keep or delete a recording when the call ends — build plan (2026-11-06)

Status: 🔨 BUILDING on `feat/keep-or-delete`. Design decided by the maintainer 2026-11-06 — see the backlog entry
and the artifact https://claude.ai/artifact/WW6HLdm45TYSaydaYvC44N.

## Decided behaviour

- Settings ▸ Recording ("Calls to record" group, "All calls" block):
  - **When a call ends**: Keep the recording (default) / Ask me: keep or delete.
  - **How to ask** (shown only with Ask): Notification (default) / Pop-up over the screen.
  - **If I don't answer** (shown only with Ask): Keep it (default) / Delete it after 24 hours.
- Buttons: **Keep / Delete only**. Delete shows "Recording deleted · Undo" and really deletes after ~10 s.
- Phone calls AND app calls.
- While undecided: no Drive copy, no automatic transcription (call-end hand-offs AND the nightly sweeps).
- Delete removes everything: `RecordingsRepository.deleteRecording` (file + .md + .json in both folders, then
  `RecordingCatalog.removeName` → `TranscriptCascade`: transcript, summary, notes, tags, stars, flags).
- Pop-up needs SYSTEM_ALERT_WINDOW. ~~The notification is ALWAYS posted too~~ — **changed 2026-11-07 (maintainer:
  "it should be as in what the user setup")**: with Pop-up, the pop-up asks alone. The notification asks instead
  only when the pop-up cannot be drawn (locked, no permission, window refused) or is closed unanswered
  (outside tap / Back), so no call is left without its question. Undo and startup still use the notification.
- All new rows must be findable in Settings search.
- Wizard: "When a call ends" goes on the AUTO_RECORD step, and since 2026-11-07 "How to ask" with it once Ask is on (it is a way of working a new user would never discover
  otherwise); "How to ask" and "If I don't answer" are Settings-only (sensible defaults, only matter once Ask is on).

## Code map (from the 2026-11-06 exploration)

- Carrier finalize: `RecordingForegroundService.routeFinalRecording` (~:785-888). Gate `StorageRouter.route` (:887),
  `StorageRouter.routeSidecar` (:876) and `TranscriptionScheduler.transcribeAfterCallIfEnabled` (:881).
- VoIP finalize: `VoipRecordingCoordinator.onCallEnded` catalog coroutine (~:792-897). Gate :857, :889, :894.
- Sweeps that must skip undecided names: `SyncSweepWorker.doWork` (lists the whole folder) and
  `TranscriptionQueue.pending` (:55-80).
- Delete: `RecordingsRepository.deleteRecording(context, RecordingItem)` needs only `uri` + `displayName`.
- Receiver pattern: `DriveCopyStopReceiver` (`system/storage/DriveCopyStop.kt:36-53`), manifest :231-245.
  PendingIntents per recording need a distinct data URI (filterEquals ignores extras).
- Channel pattern: `TranscriptNotifier.ensureChannel` (:258-269) + `PermissionChecks.hasNotificationPermission`.
- Delayed work: `CutOffRescueWorker` (:129-136); unique name per recording, REPLACE; cancel by unique name.
- Translucent activity: `ShareImportActivity` + `@style/Theme.CallVault.Share`.
- Settings registration: `SettingsScope.rowsOf/settingKeysOf`, `SettingsSearchIndex`, `SearchFiltered("Row")`,
  `ShownWhen(cond)`; choice row like `WirelessDebuggingModeRow`. Tests: SettingsSearchTest, ScopedSettingsScreenTest,
  SettingsGroupsTest, SettingDecisionsTest, AppPreferencesExportTest. Strings in all 16 locales (checkTranslations).

## Tasks

1. **Prefs + pending store + rules (pure, tested).** Three enums + keys + defaults + EXPORTABLE_KEYS.
   `AfterCallDecisions` store (SharedPreferences `cv_after_call_decisions`: name → {uri, mime, askedAt}).
   Pure `AfterCallPolicy` (should ask, which notification id, what a 24 h check does).
2. **Hold at call end.** In both finalize paths: when Ask, record the pending entry and post the question instead
   of the Drive/sidecar/transcription hand-offs. Sweeps skip pending names.
3. **Keep / Delete / Undo / 24 h.** `AfterCallDecision.keep()` runs the held hand-offs; `delete()` posts Undo and
   schedules the real delete in 10 s; `undo()` cancels and re-asks; 24 h worker applies "If I don't answer".
   Notification channel + receiver + manifest.
4. **Pop-up.** Translucent `AfterCallQuestionActivity` launched when style is Pop-up and `canDrawOverlays`; manifest
   permission; answering closes the notification too.
5. **Settings + wizard + search + strings (16 locales) + decision tests.**
6. **Device check on the OP9:** carrier call + app call, Keep, Delete (+ Undo), pop-up with and without permission.
