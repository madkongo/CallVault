# Rollback: `build/release-provenance`

🧪 VERIFYING (2026-10-31) — dry-run only; never run against a real release.

## What changed
- New `tools/release/attest.sh`: checks the release APK's signing cert (`c875ffd0…`) and that it is not
  debuggable, writes `<apk>.sha256`, then signs the APK with cosign keyless (browser login, public Rekor log)
  into `<apk>.sigstore.json` and verifies it. `--dry-run` does only the local checks.
- Docs: a release-step section in `docs/dev-notes/release-hardening-size-and-attestation.md`; backlog entry
  "Attested / immutable releases" marked 🧪 BUILT.
- No app code, Gradle or CI change. The APK is byte-for-byte unaffected.

## Revert
```
git revert -m 1 4d7fd78d   # 4d7fd78d = the merge of build/release-provenance into integration/next
```

## What changes after rollback
- The script and the doc section disappear. Releases go back to APK only, no `.sigstore.json` / `.sha256`.
- Releases already published with a bundle keep it (releases are immutable) and stay verifiable — the
  Rekor entry is public and permanent. Nothing on users' phones changes either way.

## Symptoms that would justify rollback
- cosign keyless becomes unusable (login flow breaks, Sigstore public-good service retired) and the step
  blocks releases.
- The maintainer decides the login email in the public Rekor log / certificate is not acceptable.
- A better route replaces it (reproducible builds via F-Droid, or a key-pair scheme).
