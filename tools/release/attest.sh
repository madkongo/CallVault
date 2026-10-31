#!/usr/bin/env bash
# Sign the locally built release APK with Sigstore (cosign, keyless) so anyone can check that the
# CallVault.apk on a GitHub release is the one the maintainer published.
#
# WHY THIS AND NOT CI PROVENANCE: releases are built and signed on the maintainer's Mac, because the
# update-signing key (cert c875ffd0…) must never sit in CI secrets. CI's attest-build-provenance only
# covers artifacts CI builds, and `gh attestation` can only download/verify, not create, attestations
# for a local file. cosign keyless needs no key at all: it logs in through the browser (GitHub, Google
# or Microsoft), gets a 10-minute certificate for that identity, and writes the APK's digest into the
# public Rekor transparency log. Nothing secret is created, stored or committed.
#
# What it proves: "the holder of <identity> published this exact file at this time". It does NOT
# prove the APK was built from a given commit — that needs reproducible builds (F-Droid track).
#
# Usage:
#   tools/release/attest.sh --dry-run app/build/outputs/apk/release/CallVault.apk
#   tools/release/attest.sh --identity you@example.com \
#       [--issuer https://github.com/login/oauth] app/build/outputs/apk/release/CallVault.apk
#
# --dry-run runs every local check (cert pin, not debuggable, SHA-256) and prints the cosign commands
# without running them. It needs no login and no cosign.
#
# Outputs, next to the APK:
#   <apk>.sha256          "<sha256>  CallVault.apk"
#   <apk>.sigstore.json   the Sigstore bundle (certificate + signature + Rekor entry); real run only
#
# Release-time order, because releases are IMMUTABLE (assets cannot be added after publishing):
#   1. build, install-test, then run this script on the exact file you will upload
#   2. gh release create vX.Y.Z --draft ... CallVault.apk CallVault.apk.sigstore.json CallVault.apk.sha256
#   3. check the draft, then: gh release edit vX.Y.Z --draft=false
#
# Requires: cosign v2.4+ (brew install cosign) for a real run; Android build-tools (apksigner, aapt2).
set -euo pipefail

readonly EXPECTED_CERT_SHA256="c875ffd0122aa6baceca1826eeb6c6ecbabe023ed551f0be856e8b7a80f285ea"
readonly DEFAULT_ISSUER="https://github.com/login/oauth"

die() { echo "attest: $*" >&2; exit 1; }

usage() {
  sed -n '15,19p' "$0" | sed 's/^# \{0,1\}//' >&2
  exit 2
}

find_build_tool() {
  local name="$1" sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
  local found
  found=$(ls -d "$sdk"/build-tools/*/ 2>/dev/null | sort -V | tail -1)
  [[ -n "$found" && -x "$found$name" ]] || die "$name not found under $sdk/build-tools (set ANDROID_HOME)"
  echo "$found$name"
}

check_signing_cert() {
  local apk="$1" apksigner certs
  apksigner=$(find_build_tool apksigner)
  certs=$("$apksigner" verify --print-certs "$apk") || die "apksigner verify failed: APK is unsigned or corrupt"
  grep -q "certificate SHA-256 digest: $EXPECTED_CERT_SHA256" <<<"$certs" \
    || die "signing cert is not the release cert ${EXPECTED_CERT_SHA256:0:8}… — refusing to attest"
  echo "ok  signing cert ${EXPECTED_CERT_SHA256:0:8}…"
}

check_not_debuggable() {
  local apk="$1" aapt2
  aapt2=$(find_build_tool aapt2)
  if "$aapt2" dump badging "$apk" | grep -q "application-debuggable"; then
    die "APK is debuggable — this is not a release build"
  fi
  echo "ok  not debuggable"
}

write_sha256() {
  local apk="$1" sum
  sum=$(shasum -a 256 "$apk" | awk '{print $1}')
  printf '%s  %s\n' "$sum" "$(basename "$apk")" > "$apk.sha256"
  echo "ok  sha256 $sum -> $(basename "$apk").sha256"
}

main() {
  local dry_run=0 identity="" issuer="$DEFAULT_ISSUER" apk=""
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --dry-run) dry_run=1 ;;
      --identity) identity="${2:-}"; shift ;;
      --issuer) issuer="${2:-}"; shift ;;
      -h|--help) usage ;;
      -*) die "unknown option $1" ;;
      *) apk="$1" ;;
    esac
    shift
  done
  [[ -n "$apk" ]] || usage
  [[ -f "$apk" ]] || die "no such file: $apk"

  check_signing_cert "$apk"
  check_not_debuggable "$apk"
  write_sha256 "$apk"

  local bundle="$apk.sigstore.json"
  local id_shown="${identity:-<your-login-email>}"
  local sign_cmd=(cosign sign-blob --yes --bundle "$bundle" "$apk")
  local verify_cmd=(cosign verify-blob --bundle "$bundle"
    --certificate-identity "$id_shown" --certificate-oidc-issuer "$issuer" "$apk")

  if [[ $dry_run -eq 1 ]]; then
    echo "dry-run: would run (opens a browser to log in):"
    echo "  ${sign_cmd[*]}"
    echo "dry-run: then verify with:"
    echo "  ${verify_cmd[*]}"
    return 0
  fi

  [[ -n "$identity" ]] || die "--identity is required for a real run (the email you log in with)"
  command -v cosign >/dev/null || die "cosign not installed (brew install cosign)"

  "${sign_cmd[@]}"
  "${verify_cmd[@]}"
  echo "ok  attested: upload $(basename "$apk"), $(basename "$bundle") and $(basename "$apk").sha256 to the DRAFT release"
}

main "$@"
