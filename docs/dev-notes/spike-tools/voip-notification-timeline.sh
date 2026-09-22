#!/bin/sh
# Watches a phone for a VoIP call (audio mode -> MODE_IN_COMMUNICATION) and, from that instant, dumps
# the VoIP app's notifications once a second for 20 s. Answers "when does the ongoing-call
# notification appear, and in which field is the name?" — the question VoipCallerName depends on.
#
# Usage: voip-notification-timeline.sh <adb serial> [package, default com.whatsapp] [out dir]
# Then place or receive a call in that app on the phone. Names are redacted in the printed summary;
# the raw dumps (with names) stay in the out dir — delete them afterwards.
S=${1:?serial}; PKG=${2:-com.whatsapp}; OUT=${3:-/tmp/voip-timeline}
mkdir -p "$OUT"
echo "waiting for MODE_IN_COMMUNICATION on $S (audio mode polled every 0.5 s)…"
while :; do
  MODE=$(adb -s "$S" shell dumpsys audio 2>/dev/null | grep -m1 -oE "Actual mode = [A-Z_]+|mode \(internal\) = [A-Z_]+")
  case "$MODE" in *IN_COMMUNICATION*) break;; esac
  sleep 0.5
done
T0=$(date +%s.%N)
echo "call detected at $(date +%H:%M:%S.%N | cut -c1-12)"
for i in $(seq 0 19); do
  NOW=$(date +%s.%N); DT=$(printf "%.1f" "$(echo "$NOW - $T0" | bc)")
  adb -s "$S" shell dumpsys notification --noredact > "$OUT/dump-$i.txt" 2>/dev/null
  # summary: for each record of PKG, its flags and the TYPE of title/text and their lengths (no names)
  awk -v pkg="pkg=$PKG " '
    /NotificationRecord\(/ { show = index($0, pkg) > 0; if (show) rec = $0 }
    show && /flags=/ { f = $0 }
    show && /android\.title=/ { t = $0 }
    show && /android\.text=/ { x = $0 }
    show && /android\.callPerson=|android\.callType=|android\.template=/ { extra = extra " | " $0 }
    show && /^$/ { print "   " f; print "   " t; print "   " x; if (extra) print "   " extra; extra=""; show=0 }
  ' "$OUT/dump-$i.txt" | sed -E 's/\((.{0,3})[^)]*\)/(\1…)/g; s/^ +//' | sed "s/^/t+${DT}s  /"
  echo "t+${DT}s  ---"
  sleep 1
done
echo "raw dumps in $OUT (contain names) — delete when done"
