#!/usr/bin/env bash
# PLAN §1.7: the only documented way to import scores with adb.  tools/device/push_scores.sh <files or folders>…
set -euo pipefail
ROOT=$(git rev-parse --show-toplevel); . "$ROOT/tools/env.sh"
[ $# -gt 0 ] || { echo "usage: tools/device/push_scores.sh <files or folders>…" >&2; exit 2; }
S=${HK_SERIAL:-A06B4A96A733283}; PKG=com.tropicalstream.hammerklavier
D=/sdcard/Android/data/$PKG/files
LOCK="$ROOT/tools/device/lock.sh --"; [ "${HK_LOCK_HELD:-0}" = 1 ] && LOCK=   # smoke_m6 already holds it
exec $LOCK bash -c '
  set -euo pipefail; S=$1; PKG=$2; D=$3; shift 3
  A="adb -s $S"
  owner=$($A shell "stat -c %U $D 2>/dev/null" | tr -d "\r" || true)
  if [ -z "$owner" ] || [ "$owner" = shell ]; then
    echo "[push] $D missing or not app-owned ($owner): launching the app once"
    $A shell am start -n $PKG/.MainActivity >/dev/null; sleep 5
    owner=$($A shell "stat -c %U $D 2>/dev/null" | tr -d "\r" || true)
    [ -n "$owner" ] && [ "$owner" != shell ] || { echo "[push] the app did not create $D" >&2; exit 1; }
  fi
  $A shell mkdir -p $D/Scores
  $A push "$@" $D/Scores/
  # the Scores dir itself is app-owned (chmod on it is refused); the pushed entries are shell-owned
  $A shell "cd $D/Scores && for f in *; do chmod -R a+rwX \"\$f\" 2>/dev/null; done; true"
  $A shell am broadcast -a $PKG.CONTROL --ez rescan true >/dev/null
  echo "[push] done; rescan requested"' _ "$S" "$PKG" "$D" "$@"
