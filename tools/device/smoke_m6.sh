#!/usr/bin/env bash
# PLAN §7.4 M6 / §8.3 / §8.4: tools/device/smoke.sh M6 → this script (under lock.sh).
#   T-5MIN  : the §1.9 walkthrough by --es gesture, a screencap per screen (title, Start here, views, Transport,
#             Library, shelf, More, Import, Credits, About, Calibrate card), expected HKUi lines.
#   T-IMPORT: curl upload (UTF-8 name, a zip, a text file named .mid, wrong token), push_scores.sh with a
#             subfolder `Op 109/`, a bare `adb push` of a 0600 host file, then the Imported shelf within 10 s.
#   T-LEAVE : playing with the display on: HOME, BACK at the root, another app → fade+pause <= 0.5 s and the
#             resume point saved; display asleep → keeps playing.
# HK_M6_PRELAUNCH=1 also runs `pm clear` first to test a push before the first launch (re-voices the grand).
set -euo pipefail
OUT=$1; S=$2; PKG=$3
ROOT=$(git rev-parse --show-toplevel)
cat > "$OUT/steps.sh" <<'SH'
set -uo pipefail
S=$1; PKG=$2; OUT=$3; ROOT=$4
A="adb -s $S"; L="$OUT/logcat.txt"; fail=0
ctl() { $A shell am broadcast -a $PKG.CONTROL "$@" >/dev/null; }
g() { for x in "$@"; do ctl --es gesture "$x"; sleep 0.6; done; }
shot() { sleep 0.8; $A exec-out screencap -p > "$OUT/$1.png"; }
ok() { echo "[smoke] PASS $1"; }
bad() { echo "[smoke] FAIL $1"; fail=1; }
expect() { if grep -Eq "$2" "$L"; then ok "$1"; else bad "$1 (no /$2/)"; fi; }
count() { grep -Ec "$1" "$L" || true; }
dumpline() { # waits (up to 5 s) for a dump line newer than the last one
  local n0; n0=$(grep -c 'dump view=' "$L"); ctl --ez dump true
  for i in 1 2 3 4 5 6 7 8 9 10; do sleep 0.5; [ "$(grep -c 'dump view=' "$L")" -gt "$n0" ] && break; done
  grep 'dump view=' "$L" | tail -1; }
$A shell settings put global device_wearing 1; $A shell input keyevent KEYCODE_WAKEUP; $A shell wm dismiss-keyguard
if [ "${HK_M6_PRELAUNCH:-0}" = 1 ]; then
  $A shell am force-stop $PKG; $A shell pm clear $PKG >/dev/null
  mkdir -p "$OUT/pre"; cp "$ROOT/app/src/main/assets/midi/test/scale.mid" "$OUT/pre/prelaunch.mid" 2>/dev/null || true
  HK_LOCK_HELD=1 HK_SERIAL=$S "$ROOT/tools/device/push_scores.sh" "$OUT/pre/prelaunch.mid" && ok "T-IMPORT push before the first launch (script launched the app)" || bad "T-IMPORT push before first launch"
fi
$A logcat -c
$A logcat -v epoch -s HKUi HKInput HKLoader HKAudio HKRender AndroidRuntime > "$L" & LP=$!
$A shell am start -S -n $PKG/.MainActivity >/dev/null; sleep 8
# ── T-5MIN ──
shot t5_01_title
expect "title card" 'overlay context=TITLE titleCard=true'
for i in $(seq 1 60); do grep -q 'event KIT_PLAYABLE' "$L" && break; sleep 2; done
expect "grand playable" 'event KIT_PLAYABLE'
d0=$(dumpline); r0=$(echo "$d0" | grep -o 'resume=[^@ ]*' | cut -d= -f2)
first=bach.bwv846; [ -n "$r0" ] && [ "$r0" != null ] && first=$r0 && echo "[smoke]   resume point $r0: the title reads Tap to continue"
g tap; sleep 3; shot t5_02_start_here
expect "tap enters and Start here plays" 'action Enter'
expect "movement started" 'event MOVEMENT_STARTED'
d=$(dumpline); echo "[smoke]   $d"
echo "$d" | grep -q "movement=$first" && ok "tap plays $first (Start here, or the resume point)" || bad "Start here movement ($d)"
wk=$(echo "$d" | grep -o 'works=[0-9]*' | cut -d= -f2); im=$(echo "$d" | grep -o 'imported=[0-9]*' | cut -d= -f2); bw=$(( wk - ${im:-0} ))
[ "$bw" -ge 67 ] && [ "$bw" -le 70 ] && ok "catalogue: $bw bundled works (+${im:-0} imported)" || bad "catalogue size $bw"
g fwd; sleep 2; shot t5_03_action
g fwd; sleep 2; shot t5_04_hall
g back back; sleep 2
g double; shot t5_05_transport
expect "Transport opens" 'overlay context=MENU'
g fwd fwd fwd fwd fwd tap; shot t5_06_library
expect "Library rescans on open" 'action Rescan'
g tap; shot t5_07_shelf
g double double; g fwd tap; shot t5_08_more
g fwd fwd fwd tap; shot t5_09_import
g tap; g fwd tap; shot t5_10_credits
g tap; g fwd tap; shot t5_11_about
g tap; g back back back tap; shot t5_12_calibrate
g tap; shot t5_13_floor_card
g double double double double; sleep 1; shot t5_14_stage
grep -Eq 'FATAL|AndroidRuntime' "$L" && bad "AndroidRuntime in log" || ok "no AndroidRuntime"
# ── T-IMPORT ──
URL=$(grep -o 'companion url=http://[0-9.:]*' "$L" | tail -1 | cut -d= -f2)
ctl --ez companion true; sleep 0.8
TOK=$(grep -o 'token=[A-Z0-9]*' "$L" | tail -1 | cut -d= -f2)
echo "[smoke]   companion $URL token $TOK"
if [ -n "$URL" ]; then
  W="$OUT/imp"; rm -rf "$W"; mkdir -p "$W/Op 109"
  M="$ROOT/app/src/main/assets/midi/mutopia"; ls "$M"/*.mid | head -6 > "$W/list"
  cp "$(sed -n 1p "$W/list")" "$W/test.mid"; cp "$(sed -n 2p "$W/list")" "$W/a.mid"; cp "$(sed -n 3p "$W/list")" "$W/b.mid"
  (cd "$W" && zip -q t.zip a.mid b.mid); printf 'this is not a midi file\n' > "$W/bad.mid"
  up() { curl -s -m 30 -H "x-hk-token: $TOK" -H 'Expect:' -H "x-hk-name: $2" --data-binary @"$1" "$URL/api/upload"; }
  t0=$(date +%s)
  r=$(up "$W/test.mid" 'H%C3%A4ndel.mid'); echo "[smoke]   upload mid: $r"; echo "$r" | grep -q '"saved":\[{' && ok "T-IMPORT curl .mid saved" || { echo "$r" | grep -q DUPLICATE && ok "T-IMPORT curl .mid (already imported)" || bad "T-IMPORT curl .mid"; }
  r=$(up "$W/t.zip" 'suite.zip'); echo "[smoke]   upload zip: $r"; echo "$r" | grep -q '"saved":\[{\|DUPLICATE' && ok "T-IMPORT zip" || bad "T-IMPORT zip"
  r=$(up "$W/bad.mid" 'bad.mid'); echo "[smoke]   upload corrupt: $r"; echo "$r" | grep -q '"reason":"NOT_MIDI"' && ok "T-IMPORT corrupt rejected with its reason" || bad "T-IMPORT corrupt"
  c=$(curl -s -o /dev/null -w '%{http_code}' -H "x-hk-token: WRONGTOK" "$URL/api/state"); [ "$c" = 403 ] && ok "wrong token 403" || bad "wrong token $c"
  st=$(curl -s -o /dev/null -w '%{time_total}' -H "x-hk-token: $TOK" "$URL/api/state"); echo "[smoke]   /api/state ${st}s"
  sleep 2; shot ti_01_after_upload
  expect "glasses show the rejection" 'IMPORT_FAILED|Couldn'"'"'t read'
  # push_scores.sh with a subfolder, then a bare adb push of a 0600 file
  cp "$(sed -n 4p "$W/list")" "$W/Op 109/1.mid"; cp "$(sed -n 5p "$W/list")" "$W/Op 109/2.mid"
  if HK_LOCK_HELD=1 HK_SERIAL=$S "$ROOT/tools/device/push_scores.sh" "$W/Op 109" > "$OUT/push.txt" 2>&1; then ok "push_scores.sh ran"
  else bad "push_scores.sh"; sed 's/^/[smoke]   push: /' "$OUT/push.txt"; fi
  cp "$(sed -n 6p "$W/list")" "$W/private.mid"; chmod 0600 "$W/private.mid"
  $A push "$W/private.mid" /sdcard/Android/data/$PKG/files/Scores/ >/dev/null
  $A shell "chmod 0600 /sdcard/Android/data/$PKG/files/Scores/private.mid" 2>/dev/null
  ctl --ez rescan true; sleep 4; shot ti_02_after_push
  d=$(dumpline); echo "[smoke]   $d"; el=$(( $(date +%s) - t0 ))
  n=$(echo "$d" | grep -o 'imported=[0-9]*' | cut -d= -f2); [ -n "$n" ] && [ "$n" -ge 3 ] && ok "Imported shelf has $n works (${el}s after the first upload)" || bad "Imported shelf ($d)"
  expect "0600 push → Permission denied status or a copy" 'IMPORT_PERMISSION|Permission denied|imported'
  ctl --ez library true; sleep 1; g fwd; sleep 0.5; shot ti_03_library_imported; g double double double
else bad "no companion url (no Wi-Fi)"; fi
# ── T-LEAVE ──
leave() { # leave <name> <adb shell command>
  ctl --es play bach.bwv846.krueger.1; sleep 4
  t=$($A shell "date +%s.%N; $2 >/dev/null" | head -1 | tr -d '\r'); sleep 2
  ln=$(awk -v t="$t" '$1+0 >= t+0 && /left with the display on: fade and pause/ {print $1; exit}' "$L")
  if [ -n "$ln" ]; then dt=$(awk "BEGIN{printf \"%.3f\", $ln - $t}"); awk "BEGIN{exit !($dt <= 0.5)}" && ok "T-LEAVE $1 fade+pause ${dt}s" || bad "T-LEAVE $1 ${dt}s > 0.5"
  else bad "T-LEAVE $1 (no fade/pause line)"; fi
  $A shell am start -n $PKG/.MainActivity >/dev/null; sleep 3
  d=$(dumpline); echo "$d" | grep -q 'playing=false' && echo "$d" | grep -q 'resume=bach.bwv846' && ok "T-LEAVE $1 paused, resume saved ($(echo "$d" | grep -o 'resume=[^ ]*'))" || bad "T-LEAVE $1 state ($d)"
}
leave HOME "input keyevent KEYCODE_HOME"
leave BACK "input keyevent KEYCODE_BACK"
leave other-app "am start -a android.settings.SETTINGS"
ctl --es play bach.bwv846.krueger.1; sleep 4
$A shell input keyevent KEYCODE_SLEEP; sleep 8
d=$(dumpline); echo "[smoke]   asleep: $d"
echo "$d" | grep -q 'playing=true' && ok "T-LEAVE asleep keeps playing" || bad "T-LEAVE asleep ($d)"
$A shell input keyevent KEYCODE_WAKEUP; $A shell wm dismiss-keyguard; sleep 2
$A shell am start -n $PKG/.MainActivity >/dev/null; sleep 2; ctl --ez pause true
kill $LP 2>/dev/null
grep -q 'FATAL EXCEPTION' "$L" && bad "crash" || ok "no crash"
[ $fail = 0 ] && echo "[smoke] $(basename "$OUT") PASS" || echo "[smoke] $(basename "$OUT") FAIL"
exit $fail
SH
exec "$ROOT/tools/device/lock.sh" -- bash "$OUT/steps.sh" "$S" "$PKG" "$OUT" "$ROOT" </dev/null
