#!/usr/bin/env bash
# PLAN §8.1: CI gate, install the release build, verify the installed APK's md5, compile speed, launch.
#   tools/device/run.sh [--no-ci] [--mono]
set -euo pipefail
ROOT=$(git rev-parse --show-toplevel); . "$ROOT/tools/env.sh"
S=${HK_SERIAL:-A06B4A96A733283}; PKG=com.tropicalstream.hammerklavier
APK="$ROOT/app/build/outputs/apk/release/Hammerklavier-release.apk"
CI=1; MONO=""
for a in "$@"; do case "$a" in --no-ci) CI=0;; --mono) MONO="--ez mono true";; esac; done
if [ "$CI" = 1 ]; then "$ROOT/tools/ci.sh"; fi
[ -f "$APK" ] || { echo "[run] no $APK" >&2; exit 1; }
"$ROOT/tools/device/lock.sh" -- bash -c "
  set -e
  adb -s $S install -r '$APK'
  P=\$(adb -s $S shell pm path $PKG | head -1 | sed 's/package://' | tr -d '\r')
  R=\$(adb -s $S shell md5sum \"\$P\" | cut -d' ' -f1)
  L=\$(md5 -q '$APK')
  if [ \"\$R\" != \"\$L\" ]; then echo \"[run] md5 mismatch: device \$R local \$L\" >&2; exit 1; fi
  echo \"[run] installed APK verified (md5 \$L)\"
  adb -s $S shell cmd package compile -m speed -f $PKG >/dev/null
  adb -s $S shell am start -S -n $PKG/.MainActivity $MONO"
