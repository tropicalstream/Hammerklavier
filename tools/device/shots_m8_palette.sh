#!/usr/bin/env bash
# M8 palette pass: tools/device/shots_m8_palette.sh <prefix>  (e.g. m8_before / m8_after). Screencaps of the
# three instruments in Player / Action cutaway / Hall into docs/shots/<prefix>_<instrument>_<view>.png.
set -euo pipefail
ROOT=$(git rev-parse --show-toplevel); . "$ROOT/tools/env.sh"
S=${HK_SERIAL:-A06B4A96A733283}; PKG=com.tropicalstream.hammerklavier; P=${1:?prefix}
exec "$ROOT/tools/device/lock.sh" -- bash -c "
A='adb -s $S'; ctl() { \$A shell am broadcast -a $PKG.CONTROL \"\$@\" >/dev/null; }
\$A shell settings put global device_wearing 1; \$A shell input keyevent KEYCODE_WAKEUP
\$A shell am start -n $PKG/.MainActivity >/dev/null; sleep 4
for pair in grand:bach.bwv846.krueger.1 harpsichord:bach.bwv988.1 upright:mozart.k545.1; do
  i=\${pair%%:*}; m=\${pair#*:}
  ctl --es play \$m; sleep 1.5; ctl --es instrument \$i; sleep 6
  for v in PLAYER:0 ACTION:0 HALL:0; do ctl --es view \${v%%:*} --ei framing \${v#*:}; sleep 3.5
    \$A exec-out screencap -p > '$ROOT/docs/shots/${P}_'\$i'_'\$(echo \${v%%:*} | tr A-Z a-z)'.png'; done
done
ctl --es instrument grand; ctl --es view PLAYER --ei framing 0; ctl --ez pause true; echo done"
