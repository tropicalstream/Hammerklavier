#!/usr/bin/env bash
# Records the ~95s YouTube demo trailer for Hammerklavier on the RayNeo X3 Pro glasses
# (the approved take runs the ~55-65s scripted showcase THEN keeps rolling through the restore
# phase's own on-screen wind-down back to the original piece and the title screen — see `main`'s
# `record` case for why that's kept in, not trimmed to a bare ~70s)
# (serial $SERIAL) via adb CONTROL broadcasts — no finger/hand needed, the app is designed to
# be driven this way (see DebugControl.kt / AppController's CONTROL switch, PLAN §8.2).
#
# Usage:
#   tools/device/record_demo.sh dry     # drives the sequence with screenshots at each beat,
#                                        # no scrcpy recording — use this to check timing
#   tools/device/record_demo.sh record  # runs the real scrcpy capture (video+device audio)
#   tools/device/record_demo.sh restore # re-applies only the restore phase (safety net if a
#                                        # take was interrupted after changing device state)
#
# Output (record mode): docs/demo/hammerklavier_demo.mp4 (single-eye crop, 640x480, real
# device audio via scrcpy --audio-source=output — confirmed non-silent on this device
# before the real take; Android 11+ audio forwarding, this device is Android 12/SDK 32).
#
# ── A note on per-work instrument memory (read this before changing the sequence) ──
# SessionController.playMovement resolves the instrument as
#   inst_param ?: prefs.workInstrument(work) ?: work.defaultInstrument ?: session.instrument
# and a CONTROL "instrument" broadcast persists prefs.workInstrument(CURRENTLY LOADED work) = X
# whenever a movement is loaded (SessionController.kt:460). That means an "instrument" broadcast
# sent *before* "play" can silently overwrite the *previous* movement's remembered instrument
# instead of doing anything useful for the movement about to load — confirmed on this device
# (beethoven.op106 already had a stale per-work mapping that won over an explicit
# `instrument grand` sent right before `play beethoven.op106.4`). The fix used throughout below:
# always **play, then force the instrument** (the `load` helper), never the other way round.
# That both fixes the movement just loaded AND is itself the on-screen "instrument
# appears/switches" reveal beat — never open a segment with a bare instrument switch while the
# previous piece is still loaded.
#
# What this script restores at the end (everything it *touches*):
#   view=PLAYER framing=0, session instrument=HARPSICHORD, resume=bach.bwv846.krueger.1
#   @135778936us (display ms 135379), harpsichord tuning=WERCKMEISTER_III @ A415 (this was
#   the pre-existing value — during setup it's deliberately turned down to Equal/A440 so the
#   on-camera switch back to Werckmeister III/A415 in segment C is a real, visible change,
#   landing exactly back on the original). Sound/Sight menus (tempo, noises, look-around,
#   reverse swipe, MSAA, registration) are never touched, so nothing there needs restoring.
#   Per-work instrument memory for the three DEMO pieces (op.106, BWV 903, K.141 — not the
#   user's own resume piece) is intentionally left as this session's own real use set it;
#   only bach.bwv846.krueger.1's mapping (HARPSICHORD) is the one the original session
#   actually depended on, and it's restored the same play-then-force way.
#   The app is left backgrounded via a real KEYCODE_HOME (not CONTROL leave — finish() alone
#   left the process alive in one check; a real HOME is what the RayNeo launcher's
#   BackgroundAppManager force-stops ~1s later, exactly matching the pre-task screenshot).

set -euo pipefail
SERIAL="A06B4A96A733283"
PKG="com.tropicalstream.hammerklavier"
ACTION="$PKG.CONTROL"
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT_DIR="$REPO/docs/demo"
FINAL_MP4="$OUT_DIR/hammerklavier_demo.mp4"
SCRATCH="${HK_SCRATCH:-/tmp/hk_demo}"
mkdir -p "$SCRATCH" "$OUT_DIR"

MODE="${1:-dry}"

adbs() { adb -s "$SERIAL" "$@"; }

# One-shot broadcast (setup/restore — timing doesn't matter here, clarity does).
ctrl() { adbs shell am broadcast -a "$ACTION" "$@" >/dev/null; }

# `burst` runs a whole timed beat as ONE `adb shell` call (one USB round trip instead of one
# per gesture — on this link each separate `adb shell am broadcast` costs ~0.3-0.6s, which blew
# the first dry run to ~82s against a 70s budget). Tokens:
#   g:<name>   -> am broadcast --es gesture <name>
#   e:<extras> -> am broadcast <extras verbatim>       (e.g. e:'--es view hall --ei framing 0')
#   s:<secs>   -> sleep <secs>
burst() {
  local cmd="" tok kind val
  for tok in "$@"; do
    kind="${tok%%:*}"; val="${tok#*:}"
    case "$kind" in
      g) cmd+="am broadcast -a $ACTION --es gesture $val >/dev/null; " ;;
      e) cmd+="am broadcast -a $ACTION $val >/dev/null; " ;;
      s) cmd+="sleep $val; " ;;
      *) echo "burst: bad token $tok" >&2; exit 1 ;;
    esac
  done
  adbs shell "$cmd"
}

shot() {  # shot <name> — screenshot to $SCRATCH/<name>.png (dry-run checkpoints only)
  [ "$MODE" = "dry" ] || return 0
  adbs shell screencap -p /sdcard/hk_shot.png
  adbs pull /sdcard/hk_shot.png "$SCRATCH/$1.png" >/dev/null
  echo "  [shot] $1.png"
}
dumpstate() {
  adbs logcat -c
  ctrl --ez dump true
  sleep 0.35
  adbs logcat -d -s HKUi:I | grep "dump view" || true
}
note() { echo ">>> $*"; }

# load <movementId> <instrument> [seekMs] — play, THEN force the instrument (see header note),
# then seek. Leaves it PLAYING (CONTROL play auto-starts playback; confirmed by the pre-flight
# audio test — real non-silent opus audio came back with no separate resume/toggle call).
load() {
  local mv="$1" inst="$2" seek="${3:-}"
  ctrl --es play "$mv"; sleep 0.35
  ctrl --es instrument "$inst"; sleep 0.6
  if [ -n "$seek" ]; then ctrl --el seek "$seek"; sleep 0.2; fi
}

# ─────────────────────────────────────────────────────────────────────────────
# SETUP — not recorded. Launches the app and warms all three kits from the on-disk decode
# cache (this device has an extensive test history so this should be fast — watch the
# `dumpstate` lines rather than assume it). Also stages the harpsichord's tuning to
# Equal/A440 so segment C's on-camera switch back to Werckmeister III/A415 is real.
# ─────────────────────────────────────────────────────────────────────────────
setup() {
  note "launch"
  adbs shell am force-stop "$PKG" || true
  adbs shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null
  sleep 2.5
  ctrl --ez debug false
  ctrl --ei quality -1              # governor auto (do not force a level)

  note "warm grand (op.106 iv, the exact clip we'll use)"
  load beethoven.op106.4 grand 155000
  sleep 8
  ctrl --ez pause true; sleep 0.3

  note "warm harpsichord (BWV 903, the exact clip) + stage its tuning to Equal/A440"
  load bach.bwv903.1 harpsichord 475000
  sleep 8
  ctrl --ez pause true; sleep 0.3
  ctrl --es temperament EQUAL; sleep 0.3
  ctrl --ef pitch 440; sleep 0.3
  dumpstate

  note "warm upright (K.141) — 29.8s to full completion per the M7 gate; give it real time"
  load scarlatti.k141.1 upright 65000
  sleep 18
  ctrl --ez pause true; sleep 0.3
  dumpstate

  note "settle back on grand, paused at the op.106 seek point, Player framing 0 — ready to record"
  load beethoven.op106.4 grand 155000
  ctrl --ez pause true; sleep 0.3
  ctrl --es view player; ctrl --ei framing 0; sleep 0.3
  dumpstate
}

# ─────────────────────────────────────────────────────────────────────────────
# THE TAKE — same commands in dry and record mode; only recording + screenshot checkpoints
# differ. Every gesture burst is one adb round trip. This is the on-camera showcase itself
# (~55-65s); the approved final take also keeps rolling through `restore`'s wind-down after it
# (see `main`) rather than cutting at the end of this function.
# ─────────────────────────────────────────────────────────────────────────────
sequence() {
  local t0=$SECONDS
  mark() { echo "  [t=$((SECONDS-t0))s] $*"; }

  # ── A: Grand / Beethoven op.106 iv fugue, Action cutaway ── ~0-14s
  mark "A: grand, Player (already loaded+paused by setup)"
  ctrl --ez resume true
  sleep 1.4
  shot "a1_grand_player"

  mark "A: Action cutaway on the dense fugue passage"
  ctrl --es view action; ctrl --ei framing 0
  sleep 12.0
  shot "a2_action_cutaway"

  # ── B: Harpsichord / Bach Chromatic Fantasia and Fugue, Hall, instrument history ── ~14-35s
  mark "B: harpsichord reveal (play BWV903, force+seek), Player"
  ctrl --ez pause true; sleep 0.2
  load bach.bwv903.1 harpsichord 475000
  ctrl --es view player; ctrl --ei framing 0
  sleep 1.4
  shot "b1_harpsichord_player"

  mark "B: Hall (Konzertzimmer, candles)"
  ctrl --es view hall; ctrl --ei framing 0
  sleep 8.0
  shot "b2_hall"

  mark "B: instrument history (About panel: Bach/Silbermann)"
  burst e:'--ez menu true' s:0.5 \
        g:down s:0.15 g:down s:0.15 g:down s:0.15 g:down s:0.15 g:down s:0.15 g:down s:0.15 \
        g:tap s:0.5 \
        g:down s:0.15 g:down s:0.15 g:down s:0.15 g:down s:0.15 g:down s:0.15 \
        g:tap s:0.7
  shot "b3_about_history"
  sleep 3.2
  burst g:double s:0.35 g:double s:0.35 g:double s:0.35

  # ── C: setting — temperament + pitch, Player·follow (HUD tuning line) ── ~35-47s
  mark "C: Player follow, open Sound > Temperament (Equal -> Werckmeister III)"
  ctrl --es view player; ctrl --ei framing 1
  sleep 1.1
  shot "c1_player_follow_before"
  burst e:'--ez menu true' s:0.5 \
        g:down s:0.15 g:down s:0.15 g:down s:0.15 g:down s:0.15 g:down s:0.15 g:down s:0.15 \
        g:tap s:0.5 \
        g:tap s:0.5 \
        g:down s:0.15 \
        g:tap s:0.5
  shot "c2_temperament_before"
  burst g:down s:0.15 g:tap s:0.55
  mark "C: Sound > Pitch (A440 -> A415)"
  burst g:down s:0.15 g:tap s:0.5
  shot "c3_pitch_before"
  burst g:down s:0.15 g:down s:0.15 g:tap s:0.55
  burst g:double s:0.35 g:double s:0.35 g:double s:0.35   # Sound -> More -> Transport -> closed
  sleep 0.5
  shot "c4_tuning_after"
  sleep 1.2

  # ── D: Upright / Scarlatti K.141, framing swap ── ~47-65s
  mark "D: upright reveal (play K141, force+seek), Player"
  ctrl --ez pause true; sleep 0.2
  load scarlatti.k141.1 upright 65000
  ctrl --es view player; ctrl --ei framing 0
  sleep 1.4
  shot "d1_upright_player"
  ctrl --ez resume true
  sleep 6.5
  shot "d2_upright_playing"

  mark "D: framing swap to Follow"
  ctrl --ei framing 1
  sleep 6.5
  shot "d3_upright_follow"

  # ── wrap ── end of the deliberate showcase (restore's wind-down continues on camera in record mode) ──
  mark "wrap"
  ctrl --ez pause true
  sleep 2.5
  shot "e1_final"

  mark "sequence complete"
}

# ─────────────────────────────────────────────────────────────────────────────
# RESTORE — put every touched value back exactly (see header), then a real HOME to background
# the app the same way it was found.
# ─────────────────────────────────────────────────────────────────────────────
restore() {
  note "restore: resume point + harpsichord tuning -> Werckmeister III / A415"
  load bach.bwv846.krueger.1 harpsichord 135379
  ctrl --es temperament WERCKMEISTER_III; sleep 0.3
  ctrl --ef pitch 415; sleep 0.3
  ctrl --ez pause true; sleep 0.3
  ctrl --es view player; ctrl --ei framing 0; sleep 0.3
  ctrl --ez debug false
  dumpstate

  note "restore: real HOME (RayNeo's launcher force-stops the app ~1s later — matches the pre-task state)"
  adbs shell input keyevent KEYCODE_HOME
  sleep 3.0
  if adbs shell pidof "$PKG" >/dev/null 2>&1; then
    note "process still alive 3s after HOME — waiting longer"
    sleep 3.0
  fi
  if adbs shell pidof "$PKG" >/dev/null 2>&1; then
    note "WARNING: process still alive after HOME; does not match the pre-task state, flag this in the report"
  else
    note "process gone, matches the pre-task state (RayNeo launcher home, app not running)"
  fi
  shot "z_restored_launcher"
}

# ─────────────────────────────────────────────────────────────────────────────
main() {
  case "$MODE" in
    dry)
      setup
      sequence
      restore
      echo "Dry run complete. Screenshots in $SCRATCH — review timing before recording."
      ;;
    record)
      setup
      note "starting scrcpy recording (audio-source=output, crop to left eye 640x480)"
      RAW="$SCRATCH/hk_demo_raw.mp4"
      rm -f "$RAW"
      # The approved take (2026-09-24) runs the camera through `sequence` AND `restore`: the
      # restore phase's own on-screen actions (switching back to the resume piece, correcting
      # its tuning, then a real HOME back to the title/launcher) read as a natural wind-down
      # outro rather than as cut content, and that's the version that shipped — final length
      # ~95s, not trimmed down to ~70s. Killing scrcpy exactly on cue has proven unreliable on
      # this link (an earlier attempt ran to the full --time-limit despite `kill`), so this
      # bounds the take with --time-limit and trims only the small dead air at each end.
      scrcpy -s "$SERIAL" -N --audio-source=output --crop=640:480:0:0 \
        --record="$RAW" --time-limit=100 &
      SCRCPY_PID=$!
      T_REC_START=$(date +%s.%N)
      sleep 2.0   # let the recorder attach before the first on-camera action
      T_SEQ_START=$(date +%s.%N)
      sequence
      restore
      T_END=$(date +%s.%N)
      sleep 1.0
      kill "$SCRCPY_PID" 2>/dev/null || true
      sleep 1.0
      pkill -f "scrcpy-server" 2>/dev/null || true   # on-device fallback if the client kill didn't land
      wait "$SCRCPY_PID" 2>/dev/null || true
      OFFSET=$(python3 -c "print(max(0.0, $T_SEQ_START - $T_REC_START - 0.4))")
      DUR=$(python3 -c "print(($T_END - $T_SEQ_START) + 1.0)")
      note "trimming raw take: offset=${OFFSET}s duration=${DUR}s (measured wall-clock, not scrcpy's own timer) — includes the restore wind-down back to title"
      ffmpeg -y -ss "$OFFSET" -i "$RAW" -t "$DUR" -c:v libx264 -crf 18 -preset medium -c:a aac -b:a 192k "$FINAL_MP4"
      ffprobe -v error -show_entries format=duration -show_entries stream=codec_type,codec_name,width,height \
        -of default=noprint_wrappers=0 "$FINAL_MP4"
      ;;
    restore)
      restore
      ;;
    *)
      echo "usage: $0 [dry|record|restore]" >&2; exit 1 ;;
  esac
}
main
