#!/usr/bin/env bash
# PLAN §8.3: tools/device/smoke.sh M<n>. Runs the milestone's CONTROL script under lock.sh, grabs a
# screencap per step into build/smoke/M<n>/, runs the self-test and fails on FAIL, AndroidRuntime,
# FRAME HITCH or missing expected log lines. M0 here; M1 in smoke_m1.sh (later milestones add theirs).
set -euo pipefail
ROOT=$(git rev-parse --show-toplevel); . "$ROOT/tools/env.sh"
M=${1:-M0}
S=${HK_SERIAL:-A06B4A96A733283}; PKG=com.tropicalstream.hammerklavier
OUT="$ROOT/build/smoke/$M"; mkdir -p "$OUT"
SELFTEST_SECS=${HK_SELFTEST_SECS:-60}
case "$M" in M0) ;; M1) exec "$ROOT/tools/device/smoke_m1.sh" "$OUT" "$S" "$PKG" "$SELFTEST_SECS";; M3) ROOT="$ROOT" exec "$ROOT/tools/device/smoke_m3.sh" "$OUT" "$S" "$PKG";; M4) ROOT="$ROOT" exec "$ROOT/tools/device/smoke_m4.sh" "$OUT" "$S" "$PKG";; M5) ROOT="$ROOT" exec "$ROOT/tools/device/smoke_m5.sh" "$OUT" "$S" "$PKG";; M6) ROOT="$ROOT" exec "$ROOT/tools/device/smoke_m6.sh" "$OUT" "$S" "$PKG";; M7) ROOT="$ROOT" exec "$ROOT/tools/device/smoke_m7.sh" "$OUT" "$S" "$PKG";; *) echo "[smoke] $M not defined yet" >&2; exit 2;; esac

# The step script goes to a file (adb shell would swallow a script fed on stdin).
cat > "$OUT/steps.sh" <<'SH'
set -uo pipefail
S=$1; PKG=$2; OUT=$3; SECS=$4
A="adb -s $S"
fail=0
ctl() { $A shell am broadcast -a $PKG.CONTROL "$@" >/dev/null; }
shot() { $A exec-out screencap -p > "$OUT/$1.png"; }
expect() { # expect <description> <grep -E pattern>
  if grep -Eq "$2" "$OUT/logcat.txt"; then echo "[smoke] PASS $1"; else echo "[smoke] FAIL $1 (no /$2/)"; fail=1; fi; }
$A shell settings put global device_wearing 1; $A shell wm dismiss-keyguard
$A shell input keyevent KEYCODE_WAKEUP
$A logcat -c
# The device's log ring is only 64 KiB: stream the tags to a file for the whole run.
$A logcat -v threadtime HKInput:V HKUi:V HKThermal:V HKSelfTest:V HKPerf:V HKRender:V AndroidRuntime:E '*:S' > "$OUT/logcat.txt" &
LC=$!
$A shell am start -S -n $PKG/.MainActivity >/dev/null; sleep 4
shot 01_title
$A shell input tap 320 240; sleep 1
$A shell input keyevent KEYCODE_DPAD_CENTER; sleep 1
ctl --es gesture double; sleep 0.5
ctl --es gesture triple; sleep 0.5
$A shell input swipe 200 240 500 240 120; sleep 1
shot 02_after_swipe
ctl --es echo hello --ei n 7; sleep 0.5
ctl --ei faketemp 405; sleep 1
ctl --ei faketemp -1; sleep 0.5
ctl --ez selftest true --ei selftestsecs "$SECS"
sleep $((SECS + 8))
$A shell input keyevent KEYCODE_SLEEP; sleep 3
ctl --ei faketemp 425; sleep 2
ctl --ei faketemp -1; sleep 1
$A shell input keyevent KEYCODE_WAKEUP; $A shell wm dismiss-keyguard; sleep 2
$A shell input keyevent KEYCODE_BACK; sleep 2
sleep 1; kill $LC 2>/dev/null; wait $LC 2>/dev/null
expect "tap from input tap"            'HKInput.*tap gesture=TAP src=touch'
expect "tap from DPAD_CENTER"          'HKInput.*tap gesture=TAP src=key'
expect "double via CONTROL"            'HKInput.*double gesture=DOUBLE'
expect "triple via CONTROL"            'HKInput.*triple gesture=TRIPLE'
expect "FORWARD from input swipe"      'HKInput.*gesture=FORWARD src=touch'
expect "CONTROL echo"                  'HKUi.*CONTROL echo=hello n=7'
expect "HKThermal lines"               'HKThermal.*status=.*battery='
expect "faketemp 405 gives Q1"         'HKThermal.*battery=40.5C \(fake\).*-> Q1'
expect "HKThermal with display asleep" 'HKThermal.*battery=42.5C \(fake\).*-> Q2'
expect "selftest version line"         'HKSelfTest.*version=.*branch=.*commit='
expect "selftest torn-read"            'HKSelfTest.*PASS tornRead'
expect "selftest GL info"              'HKSelfTest.*PASS gl .*renderer='
expect "selftest done"                 'HKSelfTest.*done pass='
expect "BACK leaves"                   'HKInput.*system_back gesture=SYSTEM_BACK'
if grep -Eq 'HKSelfTest.* FAIL |AndroidRuntime|FRAME HITCH' "$OUT/logcat.txt"; then
  echo "[smoke] FAIL found FAIL/AndroidRuntime/FRAME HITCH:"; grep -E 'HKSelfTest.* FAIL |AndroidRuntime|FRAME HITCH' "$OUT/logcat.txt"; fail=1; fi
top=$($A shell dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity' | head -1)
case "$top" in *hammerklavier*) echo "[smoke] FAIL still in front after BACK: $top"; fail=1;; *) echo "[smoke] PASS left the app";; esac
python3 - "$OUT/01_title.png" <<'PY' || fail=1
import sys, zlib, struct
d=open(sys.argv[1],'rb').read(); assert d[:8]==b'\x89PNG\r\n\x1a\n'
p=8; idat=b''; w=h=0
while p<len(d):
    n,t=struct.unpack('>I4s',d[p:p+8]); c=d[p+8:p+8+n]
    if t==b'IHDR': w,h,bd,ct=struct.unpack('>IIBB',c[:10])
    if t==b'IDAT': idat+=c
    p+=12+n
raw=zlib.decompress(idat); bpp=4 if ct==6 else 3; st=w*bpp; rows=[]; prev=bytearray(st); i=0
for y in range(h):
    f=raw[i]; line=bytearray(raw[i+1:i+1+st]); i+=1+st
    for x in range(st):
        a=line[x-bpp] if x>=bpp else 0; b=prev[x]; c=prev[x-bpp] if x>=bpp else 0
        if f==1: line[x]=(line[x]+a)&255
        elif f==2: line[x]=(line[x]+b)&255
        elif f==3: line[x]=(line[x]+(a+b)//2)&255
        elif f==4:
            pp=a+b-c; pa,pb,pc=abs(pp-a),abs(pp-b),abs(pp-c)
            line[x]=(line[x]+(a if pa<=pb and pa<=pc else b if pb<=pc else c))&255
    rows.append(bytes(line)); prev=line
half=w//2; diff=lit=0
for r in rows[::4]:
    for x in range(0,half*bpp,bpp*4):
        l=r[x:x+3]; rr=r[x+half*bpp:x+half*bpp+3]
        if max(l)>40: lit+=1
        if sum(abs(l[k]-rr[k]) for k in range(3))>60: diff+=1
tot=len(rows[::4])*len(range(0,half*bpp,bpp*4))
ok = lit>0 and diff/tot<0.02
print("[smoke] %s eyes match: %dx%d lit=%d differing=%.2f%%" % ("PASS" if ok else "FAIL", w, h, lit, 100*diff/tot))
sys.exit(0 if ok else 1)
PY
[ $fail = 0 ] && echo "[smoke] $(basename "$OUT") PASS" || echo "[smoke] $(basename "$OUT") FAIL"
exit $fail
SH
exec "$ROOT/tools/device/lock.sh" -- bash "$OUT/steps.sh" "$S" "$PKG" "$OUT" "$SELFTEST_SECS" </dev/null
