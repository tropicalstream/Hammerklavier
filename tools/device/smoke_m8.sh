#!/usr/bin/env bash
# PLAN §7.4 M8 / §8.4: tools/device/smoke.sh M8 → this script (under lock.sh).
#   T-START: cold start (force-stop, caches warm), `--es play` right after `am start`; the first note = a dump with
#     playing=true minus its song position, from the process's first log line: ≤ 4 s.
#   T-MEM: dumpsys meminfo + VmRSS after 5 min of op. 106 i (Java heap ≤ 48 MiB; TOTAL PSS − mapped-file PSS ≤ 200 MiB;
#     RSS ≤ 450 MiB). The 30-min point comes from the soak (INTEGRATION.md).
#   (Credits and About panels: smoke.sh M6 walks to both.) Self-test: no FAIL, 0 AndroidRuntime, underruns unchanged after warm-up.
set -euo pipefail
OUT=$1; S=$2; PKG=$3
ROOT=$(git rev-parse --show-toplevel)
MEMMIN=${HK_M8_MEMMIN:-5}
cat > "$OUT/steps.sh" <<'SH'
set -uo pipefail
S=$1; PKG=$2; OUT=$3; MEMMIN=$4
A="adb -s $S"; L="$OUT/logcat.txt"; fail=0
ctl() { $A shell am broadcast -a $PKG.CONTROL "$@" >/dev/null; }
shot() { sleep 0.8; $A exec-out screencap -p > "$OUT/$1.png"; }
$A shell settings put global device_wearing 1; $A shell input keyevent KEYCODE_WAKEUP; $A shell wm dismiss-keyguard
$A shell am force-stop $PKG; sleep 1
$A logcat -c
$A logcat -v epoch -s HKUi HKKit HKAudio HKRender HKSelfTest AndroidRuntime > "$L" & LP=$!
$A shell am start -n $PKG/.MainActivity >/dev/null
for i in $(seq 1 40); do ctl --ez dump true; sleep 0.1; grep -q 'dump view=' "$L" && break; done   # the receiver is up
ctl --es play bach.bwv846.krueger.1
for i in $(seq 1 30); do ctl --ez dump true; sleep 0.3; grep 'dump view=' "$L" | tail -1 | grep -Eq 'playing=true positionMs=[0-9]{3,}' && break; done
python3 - "$L" <<'PY' || fail=1
import re, sys
t = open(sys.argv[1], errors="replace").read().splitlines()
ts = lambda l: float(l.split()[0])
start = next(ts(l) for l in t if re.match(r"\s*\d+\.\d+", l))            # the process's first log line
d = next((l for l in t if re.search(r"dump view=.*playing=true positionMs=\d{3,}", l)), None)
if not d: print("[smoke] FAIL T-START no sound"); sys.exit(1)
pos = int(re.search(r"positionMs=(\d+)", d).group(1)) / 1000
dt = ts(d) - pos - start
print("[smoke] %s T-START process start -> first note %.2f s (<= 4; song position back-projected from a dump)" % ("PASS" if dt <= 4 else "FAIL", dt))
sys.exit(0 if dt <= 4 else 1)
PY
ctl --es play beethoven.op106.1; sleep $((MEMMIN * 60))
$A shell dumpsys meminfo $PKG > "$OUT/meminfo.txt"
pid=$($A shell pidof $PKG | tr -d '\r'); $A shell cat /proc/$pid/status | grep VmRSS > "$OUT/rss.txt"
python3 - "$OUT/meminfo.txt" "$OUT/rss.txt" <<'PY' || fail=1
import re, sys
m = open(sys.argv[1]).read(); rss = int(re.search(r"(\d+)", open(sys.argv[2]).read()).group(1)) / 1024
def row(name):
    r = re.search(r"^\s*%s\s+(\d+)" % re.escape(name), m, re.M); return int(r.group(1)) / 1024 if r else 0.0
heap = row("Java Heap:") or row("Dalvik Heap")
total = row("TOTAL PSS:") or row("TOTAL")
mapped = sum(row(n) for n in (".so mmap", ".jar mmap", ".apk mmap", ".ttf mmap", ".dex mmap", ".oat mmap", ".art mmap", "Other mmap"))
ok = heap <= 48 and total - mapped <= 200 and rss <= 450
print("[smoke] %s T-MEM heap %.1f MiB, PSS %.1f - mapped %.1f = %.1f MiB, RSS %.1f MiB" % ("PASS" if ok else "FAIL", heap, total, mapped, total - mapped, rss))
sys.exit(0 if ok else 1)
PY
ctl --es echo selftest; ctl --ez pause true; sleep 1
ctl --ez selftest true --ei selftestsecs 20; sleep 32
kill $LP 2>/dev/null
grep -q 'HKSelfTest.*done pass=' "$L" && echo "[smoke] PASS selftest done" || { echo "[smoke] FAIL selftest"; fail=1; }
if grep -Eq 'HKSelfTest.* FAIL |AndroidRuntime' "$L"; then echo "[smoke] FAIL FAIL/AndroidRuntime in log"; fail=1; else echo "[smoke] PASS no FAIL/AndroidRuntime"; fi
python3 - "$L" <<'PY' || fail=1
import re, sys
t = open(sys.argv[1], errors="replace").read()
play, st = t.split("CONTROL echo=selftest", 1) if "CONTROL echo=selftest" in t else (t, "")
ur = [int(x) for x in re.findall(r"HKAudio.*stats .* ur=(\d+)", play)]
urs = [int(x) for x in re.findall(r"HKAudio.*stats .* ur=(\d+)", st)]
ok = len(ur) >= 2 and ur[-1] == ur[1]
print("[smoke] %s underruns while playing %s after warm-up (during the self-test: %s)" % ("PASS" if ok else "FAIL", ur[1:2] + ur[-1:], urs[-1:] or "-"))
sys.exit(0 if ok else 1)
PY
exit $fail
SH
exec "$ROOT/tools/device/lock.sh" -- bash "$OUT/steps.sh" "$S" "$PKG" "$OUT" "$MEMMIN"
