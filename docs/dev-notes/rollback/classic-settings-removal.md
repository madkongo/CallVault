# Rollback: removal of the classic Settings screen

🧪 VERIFYING — built on branch `chore/remove-classic-settings` (2026-10-31); unit tests, translation check and
release build pass. Nothing seen on a phone yet. To settle it: install the build, open Settings from the main
screen and from each page, check every group opens and the old "Switch to the classic view" card is gone, and
run `tools/device-checks/mode-switch-check.sh <serial>` (moved to the new screen's groups, not yet run).

Plan: `docs/dev-notes/2026-10-20-settings-per-page-plan.md` ("delete classic one release after the new one").
The new per-page screen has been the default since v2.4.7.

## How to roll back

All of the removal is on one branch, merged with `--no-ff`. One command undoes all of it:

```
git revert -m 1 c7827a1b
```

`c7827a1b` = the merge commit of `chore/remove-classic-settings` into `integration/next`. **Fill it in at merge.**

The revert brings back the code, the 15 strings in all 15 languages, the tests and the device check exactly as
they were. No data step is needed: no preference was renamed, moved or migrated.

## What was removed

Code (`app/src/main/java/com/baba/callvault/ui/screens/`):

- `SettingsScreen.kt`: `SettingsContent` (the classic screen), the `useClassicScreen` switch, the classic
  section keys (`SECTION_*`) and sub-section keys (`SUB_FILE_NAME` … `SUB_EXPERIMENTAL`, `SUB_UPDATES`). The
  preview now draws `ScopedSettingsContent`.
- `SettingsLayoutSwitchCard.kt` (whole file): the "Switch to the classic view" / "Try the new Settings layout"
  card at the top of both screens; and its item in `ScopedSettingsContent.kt`.
- Classic-only containers: `RecordingSection`, `StorageSection` + `RetentionSubSection`, `TranscriptionSection`,
  `GeneralSection` + `VisualSubSection` + `ExperimentalSubSection`, `ResilienceRows`, and the
  `SettingsSubSection` component. The rows inside them are untouched; the new screen's groups call them.
- `AppPreferences.kt`: `isClassicSettingsScreen()`, `setClassicSettingsScreen()` and
  `CLASSIC_SETTINGS_SCREEN_DEFAULT`.

Strings, deleted from `values/` and all 14 translations (only strings no remaining code uses):
`settings_section_recording`, `settings_section_retention`, `settings_section_transcription_and_summaries`,
`settings_section_general`, `settings_section_visual`, `settings_section_experimental`,
`settings_subsection_incoming`, `settings_subsection_outgoing`, `settings_subsection_resilience`,
`settings_subsection_voip`, `settings_ui_subtitle`, `settings_layout_try_new_title`,
`settings_layout_try_new_subtitle`, `settings_layout_back_to_classic_title`,
`settings_layout_back_to_classic_subtitle`.

Kept on purpose because the new screen or the wizard still uses them: `settings_section_storage`,
`settings_section_privacy`, `settings_section_updates`, `settings_storage_target_label`,
`settings_file_name_template`, `settings_subsection_phone_calls`, `wizard_schedule_title`, `general_settings`.

## What was kept for the rollback

- The preference key `classic_settings_screen` (`AppPreferences.Key.CLASSIC_SETTINGS_SCREEN`). Unused now, with
  a comment saying why it stays. Nothing writes or clears it, so a user who had chosen the classic view still
  has `true` stored, and a revert opens the classic screen for them again. Guarded by
  `ScopedSettingsScreenTest` ("the classic screen's preference key is kept for a revert").
- Every other preference key, including all the Experimental switches: they are read and written by the
  per-page screen's groups under the same keys and defaults.
- The classic section keys are not reused by the new screen's group keys (`SettingsGroupsTest`), so a revert
  finds the classic screen's "open section" state undisturbed.

## Where the classic Experimental switches are now

All reachable on the per-page screen (Settings from the main screen shows every group):

| Classic Experimental row | Per-page screen |
|---|---|
| Resilient recording (`HandoffPersistToggle`) | Connection |
| Offline recording (`OfflineRecordingToggle`) | Connection |
| USB debugging (`UsbDebuggingToggle`) | Connection |
| Keep Wireless debugging on (`WirelessDebuggingEnforceToggle`) | Connection |
| Approve new Wi-Fi networks (`WifiNetworkAutoApproveToggle`) | Connection |
| Never turn Wireless debugging off (`WirelessDebuggingNeverOffToggle`, #30) | Connection |
| Default USB configuration (`UsbDefaultConfigRow`) | Connection |
| Pair again (`PairAgainRow`) | Connection |
| Record VoIP calls (`VoipRecordingSwitch`) | Recording ▸ App calls |
| VoIP app list (`VoipAppPicker`) | Recording ▸ App calls |
| Start automatically (`VoipAutoStartRow`) | Recording ▸ App calls ("Record app calls automatically") |
| Also skip ignored contacts (`VoipIgnoreContactsRow`) | Calls to skip (while app calls are on) |

`ScopedSettingsScreenTest` checks every Settings row composable is placed in a group of the main screen's
Settings, and that `ConnectionRows` holds all eight connection switches.

## What users would see after a rollback

- The card "Switch to the classic view" is back at the top of Settings (under the Shizuku mode row).
- Users who had picked the classic view before this removal open straight into it again; everyone else stays
  on the per-page screen. No setting changes value either way.
- The classic screen's General ▸ Experimental section is back with the same switches.

## Follow-ups

- A revert restores the old tests as they were (`ScopedSettingsScreenTest`, `SettingsGroupsTest`,
  `OnboardingInfoModeTest`), so nothing to restore by hand. If the revert is partial (cherry-picked), restore
  those three tests and `tools/device-checks/mode-switch-check.sh` + `ui_find.py` + `ui_rows.py` with it.
- `mode-switch-check.sh` was rewritten to walk the Connection, Recording and Calls to skip groups. It has
  not been run on a phone yet.
- The README is held; its line about *Keep Wireless debugging on for recording* still says "(classic layout:
  *Settings ▸ General ▸ Experimental*)", and screenshot `06-settings` shows the classic-view card. Logged in
  `readme-impact-log.md`.
- Some code comments still describe rows "as on the classic screen" (history of where a row came from). Left
  as they are; they are true as history.

**OP9 check 2026-10-31 (2.4.8-next4):** the Settings list shows only the per-page screen, with no classic-view switch anywhere down to Help and about. Still 🧪 until the maintainer confirms.
