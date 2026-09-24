#!/usr/bin/env bash
# Regenerate the icon and render previews:  tools/icon/render.sh
set -euo pipefail
ROOT=$(git rev-parse --show-toplevel); cd "$ROOT"
python3 tools/icon/make_icon.py
B=build/icon
for n in icon_round icon_square icon_mono; do
  qlmanage -t -s 512 -o "$B" "$B/$n.svg" >/dev/null 2>&1
  sips -z 48 48 "$B/$n.svg.png" --out "$B/${n}_48.png" >/dev/null
done
cp "$B/icon_round.svg.png" docs/shots/icon_512.png
cp "$B/icon_round_48.png" docs/shots/icon_48.png
cp "$B/icon_mono.svg.png" docs/shots/icon_mono_512.png
cp "$B/icon_square.svg.png" app/src/main/ic_launcher-playstore.png
echo "[icon] docs/shots/icon_512.png icon_48.png icon_mono_512.png app/src/main/ic_launcher-playstore.png"
