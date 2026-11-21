#!/usr/bin/env bash
# Fails when the default versionCode in app/build.gradle.kts is below the floor (the last shipped
# versionCode, tools/ci/versioncode-floor.txt). Android refuses a lower versionCode over a higher one,
# and uninstalling to get around it wipes the ADB pairing, so a build below the floor is uninstallable.
# Raise the floor in the same commit as every release version bump.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
floor="$(tr -d '[:space:]' < "$root/tools/ci/versioncode-floor.txt")"
code="$(sed -n 's/.*val ciVersionCode = .*orElse(\([0-9][0-9]*\)).*/\1/p' "$root/app/build.gradle.kts")"
if [ -z "$code" ]; then
  echo "check-version-floor: could not read the default versionCode from app/build.gradle.kts" >&2
  exit 2
fi
if [ "$code" -lt "$floor" ]; then
  echo "check-version-floor: versionCode $code is below the floor $floor; this build could not install over a release" >&2
  exit 1
fi
echo "check-version-floor: versionCode $code >= floor $floor"
