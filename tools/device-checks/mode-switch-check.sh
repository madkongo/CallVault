#!/bin/bash
# Mode-switch check for a release build on a phone with Shizuku running and CallVault paired.
#
#   tools/device-checks/mode-switch-check.sh <adb-serial>
#
# From Settings (opened from the main screen: the Shizuku mode row above the groups) it switches built-in ->
# Shizuku -> built-in WITHOUT leaving the screen, and after each switch checks: which rows are greyed (read from the UI tree, not guessed), which
# recorder process runs, and that the VoIP policy was registered by that recorder. Prints PASS/FAIL per
# check and exits non-zero on any FAIL. Leaves the phone in the mode it started in.
#
# Born 2026-10-19: Settings kept the old mode's greyed rows until reopened, and nobody saw it because
# nobody switched modes and looked at the same screen. Never uninstalls; touches only CallVault's UI.
# 2026-10-31: moved from the removed classic screen (General > Experimental) to the per-page screen's groups:
# Connection, Recording and Calls to skip. Not yet run on a phone after the move.
set -u
S=${1:?usage: $0 <adb-serial>}
DIR=$(cd "$(dirname "$0")" && pwd)
TMP=$(mktemp -d)
FAILS=0
a() { adb -s "$S" "$@"; }

dump() { a shell uiautomator dump /sdcard/cv_ui.xml >/dev/null 2>&1; a pull /sdcard/cv_ui.xml "$TMP/ui.xml" >/dev/null 2>&1; }
tap() { # tap the first node whose text contains $1 (exactly equals it when $2 = exact)
  dump
  local xy; xy=$(python3 -I "$DIR/ui_find.py" "$TMP/ui.xml" "$1" ${2:-}) || { echo "  (could not find '$1')"; return 1; }
  a shell input tap $xy
}
to_top() { for i in 1 2 3 4 5 6 7 8; do a shell input swipe 700 600 700 2800 300; done; sleep 1; }
open_settings() {
  a shell am force-stop com.baba.callvault
  a shell 'monkey -p com.baba.callvault -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1'; sleep 6
  tap Settings; sleep 2
}
rows() { # open each group that holds a tracked row (one group is open at a time), scroll through, collect
  local files="" n=0
  for group in Connection Recording "Calls to skip"; do
    to_top; tap "$group" exact; sleep 2
    for i in 1 2 3 4; do
      n=$((n+1)); dump; cp "$TMP/ui.xml" "$TMP/ui_$n.xml"; files="$files $TMP/ui_$n.xml"
      a shell input swipe 700 2600 700 1500 400; sleep 1
    done
  done
  to_top
  python3 -I "$DIR/ui_rows.py" $files
}
mode() { if a shell pidof com.baba.callvault:recorder >/dev/null 2>&1; then echo SHIZUKU; else echo STANDALONE; fi; }
check() { if [ "$2" = "$3" ]; then echo "  PASS $1"; else echo "  FAIL $1 (expected $2, got $3)"; FAILS=$((FAILS+1)); fi; }

verify() { # $1 = expected mode
  local want=$1 got; got=$(mode); check "recorder host is $want" "$want" "$got"
  local daemon; daemon=$(a shell pgrep -f com.baba.callvault.server.RecorderServer | head -1 | tr -d '\r')
  if [ "$want" = SHIZUKU ]; then check "no built-in daemon in Shizuku mode" "" "$daemon"; else check "built-in daemon running" "yes" "$([ -n "$daemon" ] && echo yes)"; fi
  local host; host=$(a shell pidof com.baba.callvault:recorder | tr -d '\r'); [ "$want" = STANDALONE ] && host=$daemon
  local pol; pol=$(a shell dumpsys audio | grep registerAudioPolicy | tail -1 | grep -o 'u/pid:2000/[0-9]*' | cut -d/ -f3)
  check "VoIP policy registered by the current recorder (if VoIP is on)" "$host" "${pol:-$host}"
  local r; r=$(rows)
  for label in "Resilient recording" "Record VoIP calls" "Also skip ignored contacts"; do
    check "'$label' enabled" "enabled" "$(echo "$r" | grep -F "$label:" | awk -F': ' '{print $2}')"
  done
  local off; [ "$want" = SHIZUKU ] && off=DISABLED || off=enabled
  for label in "Offline recording (no Wi-Fi)" "Keep Wireless debugging on for recording" \
               "Never turn Wireless debugging off"; do
    check "'$label' $off" "$off" "$(echo "$r" | grep -F "$label:" | awk -F': ' '{print $2}')"
  done
}
switch() { tap "Shizuku mode"; sleep 30; tap OK; sleep 1; }

start=$(mode); other=$([ "$start" = SHIZUKU ] && echo STANDALONE || echo SHIZUKU)
echo "== starting in $start"; open_settings; verify "$start"
echo "== switched to $other (same screen)"; switch; verify "$other"
echo "== switched back to $start (same screen)"; switch; verify "$start"
rm -rf "$TMP"
echo "== $FAILS failure(s)"; exit $((FAILS > 0))
