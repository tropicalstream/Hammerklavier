#!/usr/bin/env bash
# PLAN §8.5 (integrator only): tools/device/soak.sh start <plan> | pull
#   start: checks the battery is < 33 °C, starts the in-app recorder and plan (therm45|therm30|bright|rest10|sleep20);
#          then unplug the cable. pull: after reconnecting, fetches soak.csv and the uptime into build/.
set -euo pipefail
ROOT=$(git rev-parse --show-toplevel); . "$ROOT/tools/env.sh"
S=${HK_SERIAL:-A06B4A96A733283}; PKG=com.tropicalstream.hammerklavier
cmd=${1:-}; plan=${2:-therm45}
case "$cmd" in
  start) exec "$ROOT/tools/device/lock.sh" -- bash -c "
    set -e; t=\$(adb -s $S shell dumpsys battery | grep temperature | tr -dc 0-9)
    echo \"[soak] battery \$((t/10)).\$((t%10)) C\"; [ \"\$t\" -lt 330 ] || { echo '[soak] too warm (>= 33 C)'; exit 1; }
    adb -s $S shell am broadcast -a $PKG.CONTROL --ez soak true --es soakplan $plan >/dev/null
    echo '[soak] recorder and plan $plan started: unplug the USB cable now'";;
  pull) mkdir -p "$ROOT/build"; out="$ROOT/build/soak-$(date +%Y%m%d-%H%M).csv"
    exec "$ROOT/tools/device/lock.sh" -- bash -c "
    adb -s $S pull /sdcard/Android/data/$PKG/files/soak.csv '$out' && adb -s $S shell cat /proc/uptime && echo '[soak] $out'";;
  *) echo "usage: tools/device/soak.sh start <therm45|therm30|bright|rest10|sleep20> | pull" >&2; exit 2;;
esac
