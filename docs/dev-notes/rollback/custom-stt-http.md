# Rollback: http:// for the custom transcription server (#67)

🧪 VERIFYING — built on `feat/custom-stt-http` 2026-10-31; unit tests (2341) and checkTranslations pass. Not tried
against a real http server on a phone. To settle: Settings ▸ Model and language ▸ Custom, enter an
`http://<lan-or-vpn-ip>:<port>/v1` address of a Whisper-compatible server, tap Test, then transcribe one call.

## What changed

- `transcription/cloud/TranscriptionService.kt`: `validCustomBaseUrl` accepts `http` as well as `https`.
  Built-in services (Groq, OpenRouter) are fixed https addresses and never go through it.
- `AndroidManifest.xml`: `android:usesCleartextTraffic="true"` on `<application>`. Without it Android refuses
  http for the app. Only a user-typed custom address can be http; the updater still rejects non-https
  (`GitHubReleases.kt`), model downloads and Drive use fixed https addresses.
- `transcription_cloud_custom_url_invalid` in all 15 languages: "https://" → "http(s)://".
- `CloudPiecesTest`: http now resolves; scheme-less and host-less addresses still refused.
- No database, preference or file-format change.

## How to roll back

```
git revert -m 1 d76156df
```

(`d76156df` = the merge of `feat/custom-stt-http` into integration/next.) Nothing to migrate.

## What users notice after a rollback

A saved http:// custom address stops resolving: the Custom choice shows the "Use an https:// address" error and
transcription with it does not start until the address is changed to https. Saved settings are kept.

## When to roll back

- If cleartext being allowed app-wide is judged too broad (e.g. an audit flags it), or a request is seen going
  out over http that was not a user-typed custom address.
