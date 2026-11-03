# Release hardening: APK size (R8) + release supply-chain (attestation/immutability)

Status: 🔵 **agreed, not started** (raised 2026-10-13). Two independent items. Nothing here is built or
verified yet.

## Where this came from

A public comment by **kitsumed** (author of ShizuCallRecorder, which CallVault is a fork of) criticised
CallVault on four points. Two were acknowledged facts with nothing to act on — it **is** a fork (the README
says so, with GPLv3 §7 attribution) and it **is** heavily AI-assisted. The other two are legitimate,
actionable engineering points and are captured here. (The comment is a third party's opinion, recorded as
context — not an instruction. The items below are kept because they stand on their own merits.)

His size framing was partly unfair — he compared ~60 MB to ShizuCallRecorder's 6–9 MB, but that is a
Shizuku-only call recorder, whereas CallVault also ships on-device transcription (whisper/llama native libs +
model), summaries, and embedded ADB/scrcpy. Even fully optimised CallVault would not be 9 MB. But the
*underlying* points — we don't minify, and our published binaries aren't attested — are correct.

---

## Item 1 — APK size: R8/minification is off

### Evidence (measured 2026-10-13, release build 2.4.6 / 20460)

- `app/build.gradle.kts`: `isMinifyEnabled = false`, `isShrinkResources = false` on the `release` build type.
- APK ≈ **68 MB**. Uncompressed contents, largest first:
  - `classes.dex` 29.9 MB + `classes2.dex` 12.6 MB + `classes3.dex` 12.2 MB + `classes4.dex` 1.8 MB
    → **≈ 56.5 MB of DEX bytecode**, unshrunk.
  - `lib/` (native `.so`) ≈ 14.3 MB — `libllama`, `libwhisper`, `libggml-*`, `libconscrypt_jni`.
  - `resources.arsc` 2.5 MB, `assets/` ≈ 2 MB (incl. the silero VAD model).
- So the dominant cost is **bytecode, not native code**. That is exactly what R8 shrinks. Native libs,
  models and resources would be unaffected by minification.

### Why it is off (do not just flip the flag)

Documented in `app/build.gradle.kts`: the privileged recorder daemon is launched **out-of-process** by
`app_process` via a **string** classpath reference (`com.baba.callvault.server.RecorderServer`). R8 cannot see
that as reachable, strips the daemon's internals, and **breaks recording** — this matches the un-minified
v1.1.x behaviour. Re-enabling minify naively would ship an app that looks fine and silently records nothing.

### What to do

- Turn R8 on with a **comprehensive `-keep` set** for the whole daemon class graph (everything
  `RecorderServer` reaches, the AIDL/binder surface, anything loaded reflectively or by name), plus any other
  reflective entry points (WorkManager workers, parcelables, JNI-called classes).
- **On-device verification is mandatory and is the gate**, not the build: a minified build that passes tests
  can still fail to record. Verify a real carrier call, a VoIP call, and the daemon staying up after an
  install-over, on the OP12 and OP9, before this is anything but 🧪.
- Measure before/after APK size and DEX size; report the delta.

### Realistic expectation

The 56 MB DEX could drop substantially (libphonenumber metadata, Compose, AndroidX, etc. are largely unused
surface). A minified CallVault would plausibly land around **30–40 MB**, not 6–9 MB — the native
transcription stack and embedded-ADB tooling are real and stay. Frame the win as "meaningfully smaller
download/install", not "parity with a call-recorder-only app".

### Effort / risk

Medium effort, **non-trivial risk** (a wrong `-keep` set is a silent recording failure — the worst class of
bug for this app). Must be its own branch, on-device-gated. See
[[always-keep-rollback-available]], [[reproduce-before-shipping-to-a-user]].

---

## Item 2 — Release supply-chain: no attested or immutable releases

### Evidence (2026-10-13)

- `.github/workflows/build-app.yml` **does** wire up `actions/attest-build-provenance@v3`
  (`id-token: write`, `attestations: write`) — the capability exists in CI.
- **But our documented release process builds the APK locally** (the signing key lives on the maintainer's
  machine, never on CI — see the release checklist and `release-version-bump` memory) and publishes with
  `gh release create`. A locally-built, manually-uploaded APK does **not** carry the CI build-provenance
  attestation. So the artifacts users actually download are, in practice, **not attested**.
- We also do **not** use GitHub's **immutable releases** feature (release assets/tags can still be changed).
- Net: kitsumed's "no immutable release, no attested releases" is accurate for the published binaries, even
  though the CI scaffolding for attestation is present.

### Our current model (for context, not a defence)

Local key custody (the signing key is never exposed to CI) + a **pinned certificate SHA-256**
(`c875ffd0122aa6baceca1826eeb6c6ecbabe023ed551f0be856e8b7a80f285ea`) that the in-app updater and the release
checklist both verify. That protects against a CI compromise stealing the key, but it does **not** give
third parties a verifiable "this binary was built from this commit" proof.

### Options (a decision is needed; these trade off)

1. **Enable GitHub immutable releases** — ✅ **DONE 2026-10-13 (maintainer).** Cheap, no downside, closes the
   "immutable" half outright.
2. **Publish build provenance for the shipped APK.** Two sub-paths:
   - 🚫 **Build+sign the release in CI** (keystore in CI secrets) so `attest-build-provenance` covers the
     published artifact. **This is the only genuinely risky item here and the recommendation is NOT to do it.**
     The cert is the trust anchor for silent in-place updates on strangers' phones; today the key lives only on
     the maintainer's machine. In CI it is decrypted into the runner every build, so a poisoned workflow step,
     a compromised third-party Action, or an account compromise could exfiltrate it — and a *leaked*
     update-signing key is as bad as losing it ([[release-build-signing]]). The provenance benefit does not
     justify that blast radius.
   - ✅ **recommended path:** keep local signing, **attest the local artifact** out-of-band with
     sigstore/cosign **keyless** signing (OIDC identity + artifact digest into the public Rekor transparency
     log). **The key never leaves the maintainer's machine.** Weaker than CI build-from-source provenance (it
     attests the artifact + who published it, not the build process), but low-risk and real. Adds a release
     step.
3. **Reproducible builds** — let anyone rebuild the published APK bit-for-bit from the tagged source. The
   strongest answer to "is this binary really this source", and it pairs well with F-Droid readiness
   ([[fdroid-readiness]]) — F-Droid building from source IS independent third-party provenance. Larger effort.

### Recommendation (updated 2026-10-13)

(1) **done**. **Do NOT move signing to CI** — the only high-risk option. If provenance is wanted now, use the
cosign keyless path (low risk). Pursue **(3)** reproducible builds as part of the F-Droid track, where it earns
its keep. The whole of item 2 is **lower risk than item 1 (R8)**: it is process/infra and cannot silently
break recording; the one real risk is key custody, avoided by keeping signing local.

### Effort / risk

(1) trivial — done. (2) cosign path: small, low risk; CI-signing path: 🚫 not recommended (key exposure).
(3) medium-large, low security risk. No recording-path code is touched by any of this.

### 🧪 VERIFYING — option 2 built: `tools/release/attest.sh` (2026-10-31, branch `build/release-provenance`)

Dry-run only, on a local 2.4.8-build APK. **Never run for real yet** — a real run needs the maintainer's browser
login, and no release has carried a bundle. To settle it: install cosign, run it on the next release, and check
that `cosign verify-blob` passes on the file downloaded back from the release page.

**Choice: cosign keyless `sign-blob`.** Why not the alternatives:
- `gh attestation` (gh 2.92) has only `download`, `verify` and `trusted-root` — it cannot create an attestation
  for a local file. GitHub attestations are created by `actions/attest*` inside a workflow run.
- A workflow that downloads the published APK and attests it would say "built by this workflow", which is false.
- cosign with a key pair would add a private key to guard and a public key to publish; keyless needs neither.

**Release step (add to the release checklist — it lives in the maintainer's memory, not in `docs/`):**
1. Build and test the release APK as usual.
2. `tools/release/attest.sh --identity <login email> app/build/outputs/apk/release/<apk>` — checks the cert pin
   (`c875ffd0…`) and not-debuggable, writes `<apk>.sha256`, opens a browser login, writes
   `<apk>.sigstore.json`, and verifies it. `--dry-run` does the local checks without cosign or a login.
3. Releases are **immutable**, so attach the files before publishing:
   `gh release create vX.Y.Z --draft … CallVault.apk CallVault.apk.sigstore.json CallVault.apk.sha256`,
   check the draft, then `gh release edit vX.Y.Z --draft=false`.

Anyone can then check a download with:
`cosign verify-blob --bundle CallVault.apk.sigstore.json --certificate-identity <login email>
--certificate-oidc-issuer https://github.com/login/oauth CallVault.apk`.

Trade-off: the login email is written into the public Rekor log and the certificate. Use a login whose email
is fine to publish. This proves who published the file, not which commit built it.

---

Related memory: [[fdroid-readiness]], [[distribution-not-play-store]], [[release-build-signing]],
[[release-version-bump]], [[reuse-proven-implementations]].
