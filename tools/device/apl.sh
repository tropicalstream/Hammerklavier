#!/usr/bin/env bash
# PLAN §8.4 T-APL: tools/device/apl.sh <name>: screencap the glasses into docs/shots/apl_<name>.png and print its APL.
set -euo pipefail
ROOT=$(git rev-parse --show-toplevel); . "$ROOT/tools/env.sh"
S=${HK_SERIAL:-A06B4A96A733283}; n=${1:?name}
"$ROOT/tools/device/lock.sh" -- bash -c "adb -s $S exec-out screencap -p > '$ROOT/docs/shots/apl_$n.png'"
python3 "$ROOT/tools/device/apl_meter.py" "$ROOT/docs/shots/apl_$n.png"
