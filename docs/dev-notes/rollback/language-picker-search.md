# Rollback: search box in the transcription-language list

🧪 VERIFYING — built and unit-tested 2026-10-31 on branch `feat/language-picker-search`; not yet used on a
phone. To settle it: open the language list in Settings ▸ Model and language, in the setup wizard, and in
the "Which language?" dialog (Settings toggle "Ask which language each time", then Transcribe or import);
type, clear with ×, try an Arabic/Persian UI and TalkBack.

## What changed

- One shared picker, `ui/common/TranscriptionLanguagePicker.kt`, replaces the `M3DropdownField` used for the
  transcription language in `TranscriptionLanguageField` (Settings classic + per-page, setup wizard) and in
  `TranscribeLanguageDialog` (per-recording "Which language?"). It looks like the old field; tapping it
  opens a dialog with a search box on top and radio rows below.
- `ui/common/LanguageSearch.kt` — the pure filter (shown name, English name, native name, code; case- and
  accent-insensitive; empty box = the old list in the old order).
- `transcription/LanguageNames.kt` — native name (endonym) table + English name from `Locale`.
- Three new strings in all 15 locales: `language_search_placeholder`, `language_search_clear`,
  `language_search_no_match`.
- Tests: `LanguageSearchTest`, `LanguageNamesTest`.
- **No preference, stored value or migration was added or changed.** The selected language is saved exactly
  as before.

## How to roll back

```
git revert -m 1 7e8beaae
```

where `7e8beaae` is the merge commit of `feat/language-picker-search` into `integration/next` (or `main`).
Everything is on that branch, so the revert removes it alone. Nothing to clean up on phones.

## What users would notice

- With it: tapping the language field opens a dialog list with a search box instead of a drop-down menu.
- After a rollback: the plain drop-down menu returns. Saved languages are untouched either way.

## Symptoms that call for a rollback

- The language list does not open, opens empty, or picking a row does not change the language.
- The keyboard covers the list so rows cannot be reached.
- A crash when opening the list, in particular from the "Which language?" dialog (a dialog opened over a
  dialog).

**OP9 check 2026-10-31 (2.4.8-next4):** search box shown; "ur" → Urdu only, ✕ clears back to the full list, the code "fa" → Persian. Still 🧪 until the maintainer uses it.
