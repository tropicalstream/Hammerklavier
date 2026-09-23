#!/usr/bin/env bash
# PLAN §7.4 M5 / §8.3: tools/device/smoke.sh M5 → this script (under lock.sh). The room: WP8's venue in every view
# (Hall at Salon, the others at Stage), candle flames, sound follows the view (one setRoom per view change with the
# §5.6 listener: bench → case → row 3, world-locked only in the Hall), T-APL per view, draws <= 28, no hitch.
set -euo pipefail
OUT=$1; S=$2; PKG=$3
ROOT=$(git rev-parse --show-toplevel)
cat > "$OUT/steps.sh" <<'SH'
set -uo pipefail
S=$1; PKG=$2; OUT=$3; ROOT=$4
A="adb -s $S"; L="$OUT/logcat.txt"; fail=0
ctl() { $A shell am broadcast -a $PKG.CONTROL "$@" >/dev/null; }
g() { ctl --es gesture "$1"; }
shot() { $A exec-out screencap -p > "$OUT/$1.png"; }
expect() { if grep -Eq "$2" "$L"; then echo "[smoke] PASS $1"; else echo "[smoke] FAIL $1 (no /$2/)"; fail=1; fi; }
apl() { # apl <shot> <max%>
  v=$(python3 "$ROOT/tools/device/apl_meter.py" "$OUT/$1.png" | grep -o 'apl=[0-9.]*' | cut -d= -f2)
  awk "BEGIN{exit !($v <= $2)}" && echo "[smoke] PASS T-APL $1 ${v}% <= $2%" || { echo "[smoke] FAIL T-APL $1 ${v}% > $2%"; fail=1; }; }
$A shell settings put global device_wearing 1; $A shell input keyevent KEYCODE_WAKEUP
$A logcat -c
$A logcat -v time -s HKRender HKUi HKInput HKLoader HKAudio AndroidRuntime > "$L" & LP=$!
$A shell am start -S -n $PKG/.MainActivity >/dev/null; sleep 8
g tap; sleep 3
ctl --ei quality 0
ctl --es play asset:midi/krueger/bach/bach_846.mid; sleep 1; ctl --es instrument ${HK_M5_INSTRUMENT:-grand}; sleep 9   # M7: the instrument persists across launches
shot m5_player_toast; sleep 3; shot m5_player
g fwd; sleep 4; shot m5_action_cutaway
g down; sleep 4; shot m5_action_overhead
g up; sleep 2
g fwd; sleep 4; shot m5_hall; sleep 2; shot m5_hall_2; sleep 2; shot m5_hall_3
g fwd; sleep 4                                                # Hall -> Player
ctl --ez pause true; sleep 3
kill $LP
expect "venue built (scene assembled)" 'view=HALL/0 level=SALON'
expect "player at Stage" 'view=PLAYER/0 level=STAGE'
expect "setRoom Player (bench)" 'setRoom view=PLAYER/0 .*worldLocked=false width=1.0'
expect "setRoom Action (case)"  'setRoom view=ACTION/0 .*worldLocked=false width=0.8'
expect "setRoom Hall (row 3)"   'setRoom view=HALL/0 ear=0.40,1.20,3.00 worldLocked=true width=0.4'
grep 'setRoom' "$L" | sed 's/^.*setRoom/[smoke]   setRoom/'
n=$(grep -c 'setRoom view=' "$L"); echo "[smoke]   setRoom lines=$n (1 at start + 1 per view change)"
apl m5_player 9; apl m5_action_cutaway 9; apl m5_action_overhead 9; apl m5_hall 12
grep -o 'maxDraws=[0-9]*' "$L" | sort -t= -k2 -n | tail -1 | sed 's/^/[smoke]   /'
md=$(grep -o 'maxDraws=[0-9]*' "$L" | cut -d= -f2 | sort -n | tail -1)
[ -n "$md" ] && [ "$md" -le 28 ] && echo "[smoke] PASS draws <= 28 (max $md)" || { echo "[smoke] FAIL draws max=$md"; fail=1; }
grep -o 'fps=[0-9.]* ' "$L" | tail -3 | sed 's/^/[smoke]   /'
grep -q 'hitches=[1-9]\|glErrors=[1-9]\|FATAL\|scene build failed' "$L" && { echo "[smoke] FAIL hitch/glError/crash/build"; fail=1; } || echo "[smoke] PASS no hitch, glErrors 0, no crash"
[ $fail = 0 ] && echo "[smoke] $(basename "$OUT") PASS" || echo "[smoke] $(basename "$OUT") FAIL"
exit $fail
SH
exec "$ROOT/tools/device/lock.sh" -- bash "$OUT/steps.sh" "$S" "$PKG" "$OUT" "$ROOT" </dev/null
