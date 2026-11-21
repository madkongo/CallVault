#!/usr/bin/env bash
# Fails when a native library WE build has a LOAD segment aligned below 16 KB: such a .so does not
# load on an Android 15+ phone running 16 KB pages. Third-party libraries from AARs are only reported
# (NOTE), because we cannot fix them here.
# Usage: check-16kb-alignment.sh <dir-with-the-arm64-.so-files> [llvm-readelf path]
set -euo pipefail
dir="${1:?usage: check-16kb-alignment.sh <dir> [llvm-readelf]}"
readelf="${2:-llvm-readelf}"
if ! command -v "$readelf" >/dev/null 2>&1; then
  echo "check-16kb-alignment: $readelf not found" >&2
  exit 2
fi
ours=0
bad=0
while IFS= read -r so; do
  name="$(basename "$so")"
  is_ours=0
  case "$name" in
    libaudiohandoff.so|libwhispercv.so|libllamacv.so|libwhisper.so|libllama.so|libggml*.so) is_ours=1 ;;
  esac
  [ "$is_ours" -eq 1 ] && ours=$((ours + 1))
  min=""
  while IFS= read -r align; do
    if [ -z "$min" ] || [ "$((align))" -lt "$min" ]; then min=$((align)); fi
  done < <("$readelf" -lW "$so" | awk '$1 == "LOAD" { print $NF }')
  if [ -n "$min" ] && [ "$min" -lt 16384 ]; then
    if [ "$is_ours" -eq 1 ]; then
      echo "check-16kb-alignment: $name LOAD alignment $min < 16384" >&2
      bad=1
    else
      echo "check-16kb-alignment: NOTE third-party $name LOAD alignment $min < 16384"
    fi
  fi
done < <(find "$dir" -name '*.so' -type f | sort)
if [ "$ours" -eq 0 ]; then
  echo "check-16kb-alignment: none of our libraries found under $dir" >&2
  exit 2
fi
[ "$bad" -eq 0 ] && echo "check-16kb-alignment: $ours of our libraries, all LOAD segments >= 16 KB"
exit "$bad"
