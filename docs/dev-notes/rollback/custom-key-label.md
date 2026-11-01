# Rollback: custom server key label

🧪 VERIFYING — merged 2026-11-01 as d5b8fd36. checkTranslations and 2354 unit tests pass. Found on the OP9 during the #67 check:
with Custom selected, the key field read "Your your server key". To settle: Settings ▸ Model and language ▸ Custom.
The key field should read "Your server's key".

## What changed

- New string `transcription_cloud_key_label_custom` in all 15 languages.
- `SettingsTranscriptionServiceRows.kt` uses it when the service has no preset (Custom). Groq and OpenRouter still use
  "Your %1$s key".
- No preference or data change.

## How to roll back

```
git revert -m 1 d5b8fd36
```
