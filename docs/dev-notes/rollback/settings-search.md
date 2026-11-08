# Rollback: search line in the Settings panel

🧪 VERIFYING — merged 2026-11-03 (option B, chosen by the maintainer). 2415 unit tests pass. OP9 (2.4.9-search1):
"contacts" → Calls to skip, 3 matches; "whatsapp" → Recording 4 + Calls to skip 1, other groups dimmed.

## What changed
- `SettingsSearchIndex` (row → strings it draws + everyday English words), built from the source with
  `tools/settings-search-index.py`; `SettingsSearchTest` fails when a row has no entry.
- `SettingsSearch`: normalize (case, accents), every word must match; texts in the app language + English.
- `SearchFiltered(name)` around every row call in `ScopedSettingsGroupBodies`; `SettingsSubHeader` and
  `SettingsDivider` hide while searching (`LocalSettingsSearchRows`).
- `ScopedSettingsContent`: search line on top; while it has text, all groups of ALL are searched; matches opened
  and narrowed, misses dimmed; mode row and footer hidden. 4 strings (1 plural) × 15 locales.
- Known: a matching group's chevron does nothing during a search; rows a parent switch hides stay hidden.

## How to roll back
```
git revert -m 1 a36ad4b6
```
