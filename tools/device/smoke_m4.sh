#!/usr/bin/env bash
# PLAN §7.4 M4 / §8.3: tools/device/smoke.sh M4 → this script (under lock.sh). Hammers hit strings:
# the WP10 UiStateMachine drives the view ring (Player <-> Action <-> Hall, with the dip) and the
# framings (Action cutaway / overhead) from pad gestures; synth:repeat15 in the Action view at Q0
# (30 fps) and Q2 (20 fps) with the renderer's strike audit (every contact in an exposure window drawn
# exactly once: drawn == expected); swipe-to-first-fade < 100 ms; screencaps of every view.
set -euo pipefail
OUT=$1; S=$2; PKG=$3
ROOT=$(git rev-parse --show-toplevel)
cat > "$OUT/steps.sh" <<'SH'
set -uo pipefail
S=$1; PKG=$2; OUT=$3
A="adb -s $S"; L="$OUT/logcat.txt"; fail=0
ctl() { $A shell am broadcast -a $PKG.CONTROL "$@" >/dev/null; }
g() { ctl --es gesture "$1"; }
shot() { $A exec-out screencap -p > "$OUT/$1.png"; }
expect() { if grep -Eq "$2" "$L"; then echo "[smoke] PASS $1"; else echo "[smoke] FAIL $1 (no /$2/)"; fail=1; fi; }
waitfor() { for i in $(seq 1 $2); do grep -Eq "$1" "$L" && return 0; sleep 1; done; return 1; }
$A logcat -c
$A logcat -v time -s HKRender HKUi HKInput HKLoader AndroidRuntime > "$L" & LP=$!
$A shell am start -S -n $PKG/.MainActivity >/dev/null; sleep 8
shot m4_title
g tap; sleep 3
ctl --ei quality 0
ctl --es play asset:midi/krueger/bach/bach_846.mid; sleep 10; shot m4_player
g fwd; sleep 1; shot m4_action_cutaway                        # Player -> Action (cutaway), inside the 1.5 s view toast
sleep 2; shot m4_action_cutaway_later
ctl --es play synth:repeat15; sleep 2
for i in 1 2 3 4 5 6; do shot m4_repeat15_$i; done
waitfor 'strike audit end id=synth:repeat15' 60 || true
ctl --es play asset:midi/krueger/bach/bach_846.mid; sleep 6
g down; sleep 1; shot m4_action_overhead                      # Action overhead, inside the view toast
sleep 3; shot m4_action_overhead_later
g up; sleep 2
ctl --ei quality 2; sleep 2
ctl --es play synth:repeat15; sleep 2
n0=$(grep -c 'strike audit end id=synth:repeat15' "$L")
for i in $(seq 1 60); do [ "$(grep -c 'strike audit end id=synth:repeat15' "$L")" -gt "$n0" ] && break; sleep 1; done
ctl --ei quality -1
ctl --es play asset:midi/krueger/bach/bach_846.mid; sleep 4
g fwd; sleep 1; shot m4_hall; sleep 3; shot m4_hall_later     # Action -> Hall
g fwd; sleep 4                                                # Hall -> Player (ring)
g back; sleep 4                                               # Player -> Hall
g back; sleep 4                                               # Hall -> Action
g back; sleep 4; shot m4_player_back                          # Action -> Player
# two swipes through the real pad path (TrackpadGestureEngine on injected touch events): fromPad timing
$A shell input touchscreen swipe 300 240 900 240 120; sleep 3  # Player -> Action
$A shell input touchscreen swipe 900 240 300 240 120; sleep 3  # Action -> Player
ctl --ez pause true; sleep 2
kill $LP
expect "real UI title card"  'overlay context=TITLE titleCard=true'
expect "title card left on tap" 'gesture=TAP'
expect "pad swipe recognised" 'src=touch fingerMs='
expect "action view"         'view=ACTION/0'
expect "action overhead"     'view=ACTION/1'
expect "hall view"           'view=HALL/'
grep 'strike audit' "$L" | sed 's/^/[smoke]   /'
a=$(grep 'strike audit end id=synth:repeat15' "$L" | grep -o 'fps=[0-9.]*' | tr '\n' ' ')
bad=$(grep 'strike audit end' "$L" | awk '{for(i=1;i<=NF;i++){split($i,a,"=");v[a[1]]=a[2]}; if (v["expected"]!=v["drawn"] || v["sameKeySameFrame"]!=0) print}' | wc -l | tr -d ' ')
c=$(grep -c 'strike audit end id=synth:repeat15' "$L")
[ "$c" -ge 2 ] && [ "$bad" = 0 ] && echo "[smoke] PASS strikes drawn exactly once ($c runs, $a)" || { echo "[smoke] FAIL strike audit runs=$c bad=$bad"; fail=1; }
grep 'damper audit' "$L" | sed 's/^/[smoke]   /'
dl=$(grep 'damper audit ' "$L" | awk '{for(i=1;i<=NF;i++){split($i,a,"=");v[a[1]]=a[2]}; s+=v["landed"]; st+=v["stopped"]; l+=v["late"]} END{print s+0, st+0, l+0}')
set -- $dl
[ "$1" -gt 0 ] && [ "$3" = 0 ] && echo "[smoke] PASS dampers stop strings (landed=$1 stopped=$2 late=$3)" || { echo "[smoke] FAIL damper audit landed=$1 stopped=$2 late=$3"; fail=1; }
grep 'swipe to first fade' "$L" | sed 's/^/[smoke]   /'
pm=$(grep -o 'fromPad ms=[0-9.]*' "$L" | cut -d= -f2 | sort -n | tail -1)
[ -n "$pm" ] && awk "BEGIN{exit !($pm < 100)}" && echo "[smoke] PASS pad event to first fade max=${pm} ms" || { echo "[smoke] FAIL pad to fade (${pm:-none})"; fail=1; }
mx=$(grep -o 'first fade ms=[0-9.]*' "$L" | cut -d= -f2 | sort -n | tail -1)
nf=$(grep -c 'first fade ms=' "$L")
[ "$nf" -ge 6 ] && awk "BEGIN{exit !($mx < 100)}" && echo "[smoke] PASS swipe to first fade max=${mx} ms over $nf swipes" || { echo "[smoke] FAIL swipe to fade n=$nf max=$mx"; fail=1; }
grep -q 'hitches=[1-9]\|glErrors=[1-9]\|AndroidRuntime' "$L" && { echo "[smoke] FAIL hitch/glError/crash"; fail=1; } || echo "[smoke] PASS no hitch, glErrors 0, no crash"
[ $fail = 0 ] && echo "[smoke] $(basename "$OUT") PASS" || echo "[smoke] $(basename "$OUT") FAIL"
exit $fail
SH
exec "$ROOT/tools/device/lock.sh" -- bash "$OUT/steps.sh" "$S" "$PKG" "$OUT" </dev/null
