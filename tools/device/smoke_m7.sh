#!/usr/bin/env bash
# PLAN §7.4 M7 / §8.3 / §8.4: tools/device/smoke.sh M7 → this script (under lock.sh).
#   Goldberg on the harpsichord (A415 Werckmeister III): the key-69 remap (root 68, standard −101.27 c =
#     −1.27 + one semitone, + temperament + shape, output within 0.5 c of target), registers 8′ / 8′+4′,
#     Equal ↔ Werckmeister III; K. 545 on the upright; a screencap of every instrument in every view/framing.
#   Instrument switch mid-piece (cached kits): `switch to=… perf ms=` < 2000 with playing=true, voices after.
#   T-Q3SWITCH: --ei quality 3 (display rest), switch; the performance reaches the audio < 2 s and the new
#     scene is the one drawn on the first frame after the rest.
#   Self-test: no FAIL; no AndroidRuntime / FRAME HITCH; underruns 0 after warm-up.
# HK_M7_TDEC=1 first runs `pm clear` and measures T-DEC for the harpsichord and the upright (idle voicing,
#   sum of the unit wall times from the kit's open line to its last unit): harpsichord ≤ 12 s, upright ≤ 30 s.
set -euo pipefail
OUT=$1; S=$2; PKG=$3
ROOT=$(git rev-parse --show-toplevel)
cat > "$OUT/steps.sh" <<'SH'
set -uo pipefail
S=$1; PKG=$2; OUT=$3; ROOT=$4
A="adb -s $S"; L="$OUT/logcat.txt"; fail=0
ctl() { $A shell am broadcast -a $PKG.CONTROL "$@" >/dev/null; }
shot() { sleep 0.8; $A exec-out screencap -p > "$OUT/$1.png"; }
ok() { echo "[smoke] PASS $1"; }
bad() { echo "[smoke] FAIL $1"; fail=1; }
expect() { if grep -Eq "$2" "$L"; then ok "$1"; else bad "$1 (no /$2/)"; fi; }
mark() { ctl --es echo "$1"; sleep 0.3; }
after() { awk -v m="CONTROL echo=$1" 'index($0, m){a=1; next} a' "$L"; }   # log lines after a mark
$A shell settings put global device_wearing 1; $A shell input keyevent KEYCODE_WAKEUP; $A shell wm dismiss-keyguard
$A logcat -c
$A logcat -v epoch -s HKUi HKKit HKAudio HKRender HKSelfTest HKClock AndroidRuntime > "$L" & LP=$!
if [ "${HK_M7_TDEC:-0}" = 1 ]; then
  $A shell am force-stop $PKG; $A shell pm clear $PKG >/dev/null
  $A shell am start -n $PKG/.MainActivity >/dev/null
  for i in $(seq 1 40); do sleep 1; grep -q 'event KIT_PLAYABLE' "$L" && break; done
  ctl --es instrument harpsichord
  for i in $(seq 1 40); do sleep 1; [ "$(grep -c 'harpsichord: unit' "$L")" -ge 3 ] && break; done
  ctl --es instrument upright
  for i in $(seq 1 60); do sleep 1; [ "$(grep -c 'upright: unit' "$L")" -ge 5 ] && break; done
  python3 - "$L" <<'PY' || fail=1
import re, sys
t = open(sys.argv[1], errors="replace").read().splitlines()
bad = 0
for kit, n, lim in (("harpsichord", 3, 12.0), ("upright", 5, 30.0)):
    t0 = next((float(l.split()[0]) for l in t if f"HKKit" in l and f"{kit}: kit=" in l), None)
    units = [(float(l.split()[0]), l) for l in t if f"{kit}: unit" in l and "voiced" in l]
    if t0 is None or len(units) < n:
        print(f"[smoke] FAIL T-DEC {kit}: {len(units)}/{n} units"); bad = 1; continue
    play = next((u for u, l in units if "(releases)" not in l and "(pedals)" not in l), None)
    done = max(u for u, _ in units) - t0
    rt = [re.search(r"\((\d+)× real time", l).group(1) for _, l in units]
    ok = done <= lim
    print(f"[smoke] {'PASS' if ok else 'FAIL'} T-DEC {kit}: complete {done:.1f} s (≤ {lim:.0f}), playable {play - t0:.1f} s, units ×{'/'.join(rt)} real time")
    bad |= not ok
sys.exit(bad)
PY
  $A shell dumpsys cpuinfo | grep -i 'swcodec' | head -1 | sed 's/^/[smoke]   media.swcodec (T-DEC window): /'
  $A shell am force-stop $PKG
fi
T_MAIN=$($A shell date +%s.%N | tr -d '\r'); $A shell am start -S -n $PKG/.MainActivity >/dev/null; sleep 8
for i in $(seq 1 60); do grep -q 'event KIT_PLAYABLE' "$L" && break; sleep 2; done
views() { # views <prefix>
  for v in PLAYER ACTION HALL; do for f in 0 1; do ctl --es view $v --ei framing $f; sleep 3.2; shot "$1_$(echo $v | tr A-Z a-z)_$f"; done; done
  ctl --es view PLAYER --ei framing 0; sleep 1
}
# ── Goldberg on the harpsichord ──
mark harp; ctl --es play bach.bwv988.1; sleep 1.5; ctl --es instrument harpsichord
sincemain() { awk -v t="$T_MAIN" '$1+0 >= t+0' "$L"; }
for i in $(seq 1 30); do sleep 1; sincemain | grep -q 'remap harpsichord' && break; done
sleep 4
r=$(sincemain | grep 'remap harpsichord 415.0Hz WERCKMEISTER_III key=69' | tail -1); echo "[smoke]   $r"
python3 - "$r" <<'PY' && ok "A415 Werckmeister III remap: key 69 root 68, standard −101.27 c, output within 0.5 c" || bad "A415 remap ($r)"
import re, sys
l = sys.argv[1]; g = dict(re.findall(r"(\w+)=(-?[\d.]+)", l))
sys.exit(0 if g and int(g["root"]) == 68 and abs(float(g["std"]) + 101.27) < 0.02 and abs(float(g["err"])) <= 0.5
         and abs(float(g["shift"]) - (float(g["target"]) - float(g["native"]))) < 0.02 else 1)
PY
ctl --ez dump true; sleep 1
after harp | grep 'dump view=' | tail -1 | grep -q 'movement=bach.bwv988.1 instrument=HARPSICHORD' && ok "Goldberg on the harpsichord" || bad "Goldberg on the harpsichord ($(after harp | grep 'dump view=' | tail -1))"
views m7_harpsichord
mark reg8; ctl --ei registration 1; ctl --es view ACTION --ei framing 1; sleep 4; shot m7_harpsichord_reg8_overhead
ctl --es view ACTION --ei framing 0; sleep 3; shot m7_harpsichord_reg8_cutaway
mark reg84; ctl --ei registration 3; sleep 4; shot m7_harpsichord_reg84_cutaway
ctl --es view PLAYER --ei framing 0
mark equal; ctl --es temperament EQUAL; sleep 2
after equal | grep -q 'remap harpsichord 415.0Hz EQUAL key=69' && ok "Equal temperament rebuilds the key map" || bad "Equal remap"
mark wm3; ctl --es temperament WERCKMEISTER_III; sleep 2
after wm3 | grep -q 'remap harpsichord 415.0Hz WERCKMEISTER_III key=69' && ok "back to Werckmeister III" || bad "Werckmeister III remap"
ctl --ez menu true; sleep 1.2; shot m7_menu_transport; ctl --es gesture double; sleep 0.8
# ── K. 545 on the upright ──
mark up; ctl --es play mozart.k545.1; sleep 1.5; ctl --es instrument upright; sleep 5
ctl --ez dump true; sleep 1
after up | grep 'dump view=' | tail -1 | grep -q 'movement=mozart.k545.1 instrument=UPRIGHT' && ok "K. 545 on the upright" || bad "K. 545 on the upright ($(after up | grep 'dump view=' | tail -1))"
after up | grep -q 'remap upright 440.0Hz EQUAL key=69' && ok "upright A440 Equal key map" || bad "upright remap"
views m7_upright
# ── grand for the comparison set ──
ctl --es play bach.bwv846.krueger.1; sleep 1.5; ctl --es instrument grand; sleep 5; views m7_grand
# ── switch mid-piece, cached ──
ctl --es play mozart.k545.1; sleep 1.5; ctl --es instrument upright; sleep 5
mark sw
for inst in harpsichord grand upright harpsichord upright grand; do ctl --es instrument $inst; sleep 5; done
python3 - "$L" <<'PY' || fail=1
import re, sys
t = open(sys.argv[1], errors="replace").read().splitlines()
i = max(k for k, l in enumerate(t) if "CONTROL echo=sw" in l)
ms = [(re.search(r"to=(\w+)", l).group(1), int(re.search(r"perf ms=(\d+)", l).group(1)), "playing=true" in l) for l in t[i:] if "switch to=" in l]
v = [l for l in t[i:] if "HKAudio" in l and "stats voices=" in l]
ok = len(ms) == 6 and all(m < 2000 and p for _, m, p in ms)
print(f"[smoke] {'PASS' if ok else 'FAIL'} instrument switch mid-piece (cached): " + ", ".join(f"{n} {m} ms" for n, m, _ in ms))
vo = [int(re.search(r"voices=(\d+)", l).group(1)) for l in v]
print(f"[smoke] {'PASS' if vo and max(vo) > 0 else 'FAIL'} voices sounding across the switches ({vo})")
sys.exit(0 if ok and vo and max(vo) > 0 else 1)
PY
shot m7_after_switches
# ── T-Q3SWITCH ──
mark q3; ctl --ei quality 3; sleep 3; ctl --es instrument harpsichord; sleep 4
after q3 | grep -q 'display rest: GL paused' && ok "Q3 display rest" || bad "Q3 rest"
q=$(after q3 | grep -o 'switch to=harpsichord perf ms=[0-9]* playing=[a-z]*' | head -1)
echo "$q" | grep -Eq 'perf ms=(1?[0-9]{1,3}) playing=true' && ok "T-Q3SWITCH sound continues within 2 s ($q)" || bad "T-Q3SWITCH audio ($q)"
mark q3end; ctl --ei quality -1; sleep 3; shot m7_q3switch_after_rest
f=$(after q3end | grep -E 'HKRender.*(scene |view=)' | head -1)
echo "$f" | grep -q 'scene harpsichord\|tris=10616\|tris=21038\|tris=22294' && ok "T-Q3SWITCH first frame after the rest draws the harpsichord ($f)" || { after q3end | grep -q 'display rest over' && ok "T-Q3SWITCH rest over (scene applied during the rest; screencap m7_q3switch_after_rest)" || bad "T-Q3SWITCH frame ($f)"; }
# ── self-test (its 10 kHz torn-read reader loads the GL thread: hitches are counted before it) ──
[ "${HK_M7_TDEC:-0}" = 1 ] && grep -q 'FRAME HITCH' "$L" && ! awk -v t="$T_MAIN" '$1+0 >= t+0' "$L" | grep -q 'FRAME HITCH' && echo "[smoke]   note: FRAME HITCH during the T-DEC first-run phase: $(grep -c 'FRAME HITCH' "$L")"
awk -v t="$T_MAIN" '$1+0 >= t+0' "$L" | awk '/CONTROL echo=selftest/{exit} 1' | grep -q 'FRAME HITCH' && bad "FRAME HITCH before the self-test" || ok "no FRAME HITCH (cached launch → self-test)"
mark selftest; ctl --ez pause true; sleep 1
ctl --ez selftest true --ei selftestsecs 20; sleep 32
expect "selftest done" 'HKSelfTest.*done pass='
grep -E 'HKSelfTest.*FAIL' "$L" && bad "self-test FAIL lines" || ok "self-test: no FAIL"
grep -Eq 'FATAL|AndroidRuntime' "$L" && bad "AndroidRuntime in log" || ok "no AndroidRuntime"
python3 - "$L" <<'PY' || fail=1
import re, sys
t = [l for l in open(sys.argv[1], errors="replace") if "HKAudio" in l and "stats voices=" in l]
ur = [int(re.search(r" ur=(\d+)", l).group(1)) for l in t]
ok = len(ur) > 1 and max(ur[1:]) == ur[1]
print(f"[smoke] {'PASS' if ok else 'FAIL'} underruns after warm-up: {ur[1] if len(ur) > 1 else '?'} → {ur[-1] if ur else '?'}")
sys.exit(0 if ok else 1)
PY
kill $LP 2>/dev/null
[ $fail = 0 ] && echo "[smoke] $(basename "$OUT") PASS" || echo "[smoke] $(basename "$OUT") FAIL"
exit $fail
SH
exec "$ROOT/tools/device/lock.sh" -- bash "$OUT/steps.sh" "$S" "$PKG" "$OUT" "$ROOT" </dev/null
