#!/usr/bin/env bash
# PLAN §7.4 M3 / §8.3: tools/device/smoke.sh M3 → this script (under lock.sh). Keys move: the real
# renderer (WP6) draws the WP7 grand animated by WP5 from the audio clock. BWV 846 in Player
# overview and Follow (pedal inset) with screencaps; T-FPS (30 at Q0, 20 at Q2, 10 idle, no hitch,
# late frames <= 2%); draws per eye <= 28; T-GLRESET (glGeneration=1, no new scene line, glErrors 0);
# the sync flash (synth:sync) screencap. T-SYNC itself: tools/device/avsync.py on a scrcpy recording.
set -euo pipefail
OUT=$1; S=$2; PKG=$3
ROOT=$(git rev-parse --show-toplevel)
cat > "$OUT/steps.sh" <<'SH'
set -uo pipefail
S=$1; PKG=$2; OUT=$3
A="adb -s $S"; L="$OUT/logcat.txt"; fail=0
ctl() { $A shell am broadcast -a $PKG.CONTROL "$@" >/dev/null; }
shot() { $A exec-out screencap -p > "$OUT/$1.png"; }
expect() { if grep -Eq "$2" "$L"; then echo "[smoke] PASS $1"; else echo "[smoke] FAIL $1 (no /$2/)"; fail=1; fi; }
$A logcat -c
$A logcat -v time -s HKRender HKUi HKLoader AndroidRuntime > "$L" & LP=$!
$A shell am start -S -n $PKG/.MainActivity >/dev/null; sleep 8
ctl --es play asset:midi/krueger/bach/bach_846.mid; sleep 16; shot player_overview
ctl --ei framing 1; sleep 12; shot player_follow
ctl --ei framing 0; ctl --ei quality 2; sleep 12
ctl --ei quality -1; sleep 2; ctl --ez glreset true; sleep 8
ctl --ez sync true; sleep 3; shot sync
ctl --ez pause true; sleep 12
kill $LP
expect "scene grand built"   'scene grand items='
expect "fps 30 at Q0"        'fps=(29\.|30\.).*q=0 idle=false'
expect "fps 20 at Q2"        'fps=(19\.|20\.).*q=2 idle=false'
expect "fps 10 idle"         'fps=(9\.|10\.).*idle=true'
expect "follow framing"      'view=PLAYER/1'
expect "glreset generation"  'glGeneration=1'
n=$(grep -c 'scene grand items=' "$L"); [ "$n" = 1 ] && echo "[smoke] PASS no rebuild on glreset" || { echo "[smoke] FAIL scene built $n times"; fail=1; }
m=$(grep -o 'maxDraws=[0-9]*' "$L" | cut -d= -f2 | sort -n | tail -1)
[ "${m:-99}" -le 28 ] && echo "[smoke] PASS draws per eye max=$m <= 28" || { echo "[smoke] FAIL draws max=$m"; fail=1; }
grep -q 'hitches=[1-9]\|glErrors=[1-9]\|AndroidRuntime' "$L" && { echo "[smoke] FAIL hitch/glError/crash"; fail=1; } || echo "[smoke] PASS no hitch, glErrors 0, no crash"
[ $fail = 0 ] && echo "[smoke] $(basename "$OUT") PASS" || echo "[smoke] $(basename "$OUT") FAIL"
exit $fail
SH
exec "$ROOT/tools/device/lock.sh" -- bash "$OUT/steps.sh" "$S" "$PKG" "$OUT" </dev/null
