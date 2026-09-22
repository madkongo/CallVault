#!/bin/sh
# Once a second: the calling app's notification (text, chronometer, when) and its active audio
# players, from the moment the audio mode leaves NORMAL until it returns. Used to find what, if
# anything, changes at the moment an outgoing app call is ANSWERED — the app-call counterpart of
# mForegroundCallState. Run from the host machine: voip-answer-timeline.sh <serial> [package].
S=${1:?serial}; PKG=${2:-com.whatsapp}
a() { adb -s "$S" shell "$@"; }
UID_=$(a pm list packages -U "$PKG" | grep -m1 -oE 'uid:[0-9]+' | cut -d: -f2 | tr -d '\r')
echo "$PKG uid=$UID_"
echo "waiting for audio mode to leave NORMAL on $S ..."
until a dumpsys audio 2>/dev/null | grep -qE 'Actual mode = MODE_(IN_COMMUNICATION|IN_CALL|RINGTONE)'; do sleep 0.5; done
echo "=== call up $(date +%T)"
while a dumpsys audio 2>/dev/null | grep -qE 'Actual mode = MODE_(IN_COMMUNICATION|IN_CALL|RINGTONE)'; do
  t=$(date +%T.%N | cut -c1-12)
  key=$(a cmd notification list 2>/dev/null | grep "|$PKG|" | head -1 | tr -d '\r')
  n=$(a cmd notification get "'$key'" 2>/dev/null | grep -oE "android\.(title|text|subText|showChronometer|when|chronometerCountDown)=[^ ]*( \([^)]*\))?" | tr '\n' ' ')
  p=$(a dumpsys audio 2>/dev/null | grep -E "AudioPlaybackConfiguration|piid" | grep "u/pid:$UID_/" | grep -oE "(piid:[0-9]+|usage=[A-Z_]+|state:[a-z]+|type:[A-Za-z.]+)" | tr '\n' ' ')
  r=$(a dumpsys audio 2>/dev/null | sed -n '/recording configs/,/^$/p' | grep -oE "(source:[A-Z_]+|state:[a-z]+|client uid:[0-9]+)" | tr '\n' ' ')
  echo "$t | NOTIF $n | PLAY $p | REC $r"
  sleep 1
done
echo "=== call down $(date +%T)"
