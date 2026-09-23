#!/usr/bin/env bash
# PLAN §7.4 M1 / §8.3: tools/device/smoke.sh M1 → this script (under lock.sh). First sound on the
# stand-in bank: bench, synth:scale / pedalhalf / storm64 with the debug overlay, the clock cycle
# (pause → HOME → return → play: HKClock fromTimestamp=true within 1 s), audio continuing with the
# display asleep, the self-test. Fails on FAIL, AndroidRuntime, FRAME HITCH after launch, underrun
# growth after warm-up, majflt growth, clockMiss/energyMiss > 0.
set -euo pipefail
OUT=$1; S=$2; PKG=$3; SECS=$4
ROOT=$(git rev-parse --show-toplevel)
cat > "$OUT/steps.sh" <<'SH'
set -uo pipefail
S=$1; PKG=$2; OUT=$3; SECS=$4
A="adb -s $S"
fail=0
ctl() { $A shell am broadcast -a $PKG.CONTROL "$@" >/dev/null; }
shot() { $A exec-out screencap -p > "$OUT/$1.png"; }
L="$OUT/logcat.txt"
waitfor() { for _ in $(seq 1 "$2"); do grep -Eq "$1" "$L" && return 0; sleep 1; done; return 1; }
expect() { if grep -Eq "$2" "$L"; then echo "[smoke] PASS $1"; else echo "[smoke] FAIL $1 (no /$2/)"; fail=1; fi; }
trap '$A shell settings put global device_wearing 0 >/dev/null 2>&1' EXIT
$A shell settings put global device_wearing 1; $A shell input keyevent KEYCODE_WAKEUP; $A shell wm dismiss-keyguard
$A logcat -c
$A logcat -v threadtime HKAudio:V HKClock:V HKKit:V HKLoader:V HKUi:V HKPerf:V HKSelfTest:V HKInput:V AndroidRuntime:E '*:S' > "$L" &
LC=$!
$A shell am start -S -n $PKG/.MainActivity >/dev/null; sleep 6
shot 01_title
ctl --es echo launched
ctl --ez debug true
ctl --ez bench true; waitfor 'HKPerf.*bench cpuMhz=' 90; sleep 2          # the bench runs in 2 ms slices (~25 s)
ctl --es play synth:scale; sleep 12; shot 02_scale_debug; sleep 14
# The clock cycle. §8.3 uses pause → KEYCODE_HOME → return → play, but the RayNeo launcher
# force-stops a backgrounded app ~1 s after HOME (Mercury BackgroundAppManager, M1 finding), so
# the park is reached through the idle timer instead (IDLE_PARK_MS) and the un-park is measured.
ctl --es play synth:scale; sleep 4
ctl --ez pause true; sleep 13
ctl --ez stats true; ctl --es echo parked; sleep 1
ctl --ez resume true; sleep 3
ctl --es play synth:pedalhalf; sleep 12; shot 03_pedalhalf; sleep 10
ctl --es play synth:storm64; sleep 25; shot 04_storm64
# Display asleep: audio continues.
$A shell input keyevent KEYCODE_SLEEP; sleep 2
ctl --es echo asleep; sleep 22
ctl --ez stats true; sleep 1
ctl --es echo awake
$A shell input keyevent KEYCODE_WAKEUP; $A shell wm dismiss-keyguard; sleep 3
ctl --ez stats true; ctl --ez pause true; sleep 1
ctl --ez gcstats true
ctl --ez selftest true --ei selftestsecs "$SECS"
sleep $((SECS + 8))
kill $LC 2>/dev/null; wait $LC 2>/dev/null
expect "stand-in bank to audio"        'HKKit.*bank gen=[0-9]+ stub=true .*-> audio'
expect "decode bench"                  'HKKit.*decode bench: .*real time, setup'
expect "EngineBench result"            'HKPerf.*bench cpuMhz=.*q0Cap=[0-9]+'
expect "synth:scale compiled"          'HKLoader.*play synth:scale gen='
expect "synth:pedalhalf compiled"      'HKLoader.*play synth:pedalhalf gen='
expect "synth:storm64 compiled"        'HKLoader.*play synth:storm64 gen='
expect "scale ended"                   'HKAudio.*ended gen=.*synth:scale'
expect "clock: fromTimestamp on play"  'HKClock.*play fromTimestamp=true'
expect "parked after the idle timer"    'CONTROL echo=parked' 
if awk '/CONTROL echo=parked/{exit} /HKAudio.*stats /{l=$0} END{exit !(l ~ /parked=true/)}' "$L"; then echo "[smoke] PASS parked=true before resume"; else echo "[smoke] FAIL not parked before resume"; fail=1; fi
expect "clock: fromTimestamp on resume" 'HKClock.*resume fromTimestamp=true .*playing=true'
expect "selftest done"                 'HKSelfTest.*done pass='
# Audio kept playing asleep: a stats line between echo=asleep and echo=awake with voices > 0 and not parked.
if awk '/CONTROL echo=asleep/{a=1} /CONTROL echo=awake/{a=0} a && /HKAudio.*stats voices=[1-9]/ && /parked=false/{ok=1} END{exit !ok}' "$L"; then
  echo "[smoke] PASS audio continues with the display asleep"; else echo "[smoke] FAIL no voices while asleep"; fail=1; fi
# Underruns after warm-up (first stats line), majflt growth, clock and energy misses.
python3 - "$L" <<'PY' || fail=1
import re, sys
t = open(sys.argv[1], errors="replace").read().splitlines()
ur = [int(m.group(1)) for l in t for m in [re.search(r"HKAudio.*stats .* ur=(\d+)", l)] if m]
cm = [int(m.group(1)) for l in t for m in [re.search(r"HKClock.*drift .*clockMiss=(\d+)", l)] if m]
em = [int(m.group(1)) for l in t for m in [re.search(r"HKAudio.*stats .*energyMiss=(\d+)", l)] if m]
dm = [int(m.group(1)) for l in t for m in [re.search(r"HKPerf.* dMajflt=(-?\d+)", l)] if m]
bad = []
if len(ur) < 2: bad.append("fewer than 2 stats lines")
elif ur[-1] != ur[0]: bad.append("underruns %d -> %d after warm-up" % (ur[0], ur[-1]))
if any(cm): bad.append("clockMiss %s" % max(cm))
if any(em): bad.append("energyMiss %s" % max(em))
if any(d > 0 for d in dm[1:]): bad.append("majflt growth %s" % dm)
print("[smoke] %s underruns=%s clockMiss=%s energyMiss=%s dMajflt=%s" % ("FAIL" if bad else "PASS", ur[-1:] or "-", max(cm or [0]), max(em or [0]), dm[1:] or "-"), "; ".join(bad))
sys.exit(1 if bad else 0)
PY
# FRAME HITCH after the launch (the cold start is not a hitch), FAIL lines, crashes.
if awk '/CONTROL echo=launched/{a=1} a' "$L" | grep -Eq 'HKSelfTest.* FAIL |AndroidRuntime|FRAME HITCH'; then
  echo "[smoke] FAIL found:"; awk '/CONTROL echo=launched/{a=1} a' "$L" | grep -E 'HKSelfTest.* FAIL |AndroidRuntime|FRAME HITCH'; fail=1; fi
[ $fail = 0 ] && echo "[smoke] $(basename "$OUT") PASS" || echo "[smoke] $(basename "$OUT") FAIL"
exit $fail
SH
exec "$ROOT/tools/device/lock.sh" -- bash "$OUT/steps.sh" "$S" "$PKG" "$OUT" "$SECS" </dev/null
