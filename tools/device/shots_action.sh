#!/usr/bin/env bash
# Action-view screencaps: tools/device/shots_action.sh <prefix>  (e.g. before / after). The grand (reference),
# upright and harpsichord in the Action cutaway (framing 0) and overhead (1) into
# docs/shots/action_<prefix>_<instrument>_<cutaway|overhead>.png, plus an apl.py-style mean per shot.
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
  for f in 0:cutaway 1:overhead; do ctl --es view ACTION --ei framing \${f%%:*}; sleep 3.5
    \$A exec-out screencap -p > '$ROOT/docs/shots/action_${P}_'\$i'_'\${f#*:}'.png'; done
done
ctl --es instrument grand; ctl --es view PLAYER --ei framing 0; ctl --ez pause true; echo done"
