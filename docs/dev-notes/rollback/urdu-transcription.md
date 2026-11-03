# Rollback: Urdu as a pinnable transcription language

🧪 VERIFYING — built and unit-tested on branch `feat/urdu-transcription` (2026-10-31); not seen on a phone.

## What changed

All on one branch, no preference migration, no new setting key:

1. `TranscriptionLanguageChoice.SUPPORTED` gains `"ur"`.
2. `TranscriptionLabels.languageOf("ur")` → `transcription_language_urdu`, translated in all 15 locales.
3. `LanguageScript.NON_LATIN` gains `"ur"` → Arabic script (so a Latin-letter transcript under an Urdu pin is
   retried, and contact names in Arabic script can prime whisper).
4. `SummaryPrompt.LANGUAGE_NAMES` gains `"ur" to "Urdu"`.
5. `SummaryLanguage.scriptOf`: Arabic-script text is now split three ways. Arabic vs (Persian or Urdu) is
   the old rule, unchanged for any text with no Urdu-only letter. Persian vs Urdu is decided by Urdu's own
   letters (ٹ ڈ ڑ ں ے ہ ھ ۃ) weighed against plain ه, which Persian writes and Urdu does not.

Both transcription engines already pass the code through untouched: the local whisper gets `"ur"` as its
language, and the cloud client sends any two-letter code as the `language` form field. No model is
language-filtered for Urdu (only the Persian-tuned tier is, for `"fa"`).

## Revert

```
git revert -m 1 207a5f84
```

## What users would notice after a rollback

- Urdu disappears from the language list and the per-call language question.
- **A user who had pinned Urdu** keeps the stored value `"ur"` (`AppPreferences.getTranscriptionLanguage`
  returns any stored string as-is). Nothing crashes: the Settings dropdown cannot find `"ur"` and shows its
  first entry, **Auto-detect**, and the Settings summary line shows "Auto-detect" too. But whisper and the
  cloud service still receive `"ur"` (both accept it), so transcripts stay in Urdu while the screen says
  Auto-detect. Picking any language in Settings clears it.
- The per-call language question opens on Auto-detect (`preselectedLanguageKey` falls back for a code it
  does not offer), and a per-recording `"ur"` already queued in WorkManager falls back to the Settings
  language (`TranscriptionLanguageChoice.resolve` drops unknown codes).
- Summaries for that user would be asked for in "UR" (the prompt falls back to the upper-cased code)
  instead of "Urdu"; Arabic-script transcripts without a pin go back to being read as Arabic or Persian.

## Symptom that would justify rollback

Persian or Arabic summaries starting to come out in Urdu (the `scriptOf` split misreading their text), or
any crash or blank picker around the transcription language. Urdu transcripts merely being poor is not a
reason — that is the model's quality, not this change.
