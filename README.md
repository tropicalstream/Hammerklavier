# Hammerklavier

A sampled piano and harpsichord MIDI player for the **RayNeo X3 Pro** glasses: a keyboard recital by
candlelight in an evocation of the Konzertzimmer at Sanssouci (1747). Three instruments (a concert
grand, an upright and a Flemish harpsichord), each with a moving action you can watch from the
player's seat, in a cutaway, from overhead or from the hall. 67 bundled works (197 movements), plus
your own MIDI files.

## Requirements

- macOS or Linux with JDK 17 and the Android SDK (`local.properties` with `sdk.dir=…`, or `ANDROID_HOME`)
- Python 3.11+ (pipeline and tests only)
- `git lfs` (the instrument kits are LFS objects): `git lfs install --local && git lfs pull`
- A RayNeo X3 Pro with USB debugging on, visible in `adb devices`

## Build

```bash
tools/ci.sh                     # purity check, JVM tests, release build, pipeline tests, asset ledger, size report
tools/gw :app:assembleRelease   # just the APK: app/build/outputs/apk/release/Hammerklavier-release.apk
```

Always build through `tools/gw` (a wrapper around `./gradlew` that shares two build slots on one machine).

## Install and run

```bash
tools/device/run.sh             # CI, install the release APK, verify its md5 on the device, compile, launch
tools/device/run.sh --no-ci     # install what is already built
tools/device/smoke.sh M7        # the device smoke of a milestone (M0, M1, M3–M8); `all` runs every one
```

Set `HK_SERIAL` if your glasses are not the default serial. On the glasses: tap the temple to start
("Start here" plays the first piece), double-tap for the menu, swipe to move through lists; the
title card shows the address of the phone companion.

## Importing your own MIDI

**From a phone or computer on the same Wi-Fi:** open the address on the title card
(`http://<glasses-ip>:19112`, with the token shown) and upload `.mid`, `.midi`, `.kar` or a `.zip` of them.
A zip or a folder becomes one work with its files as movements, in filename order.

**With adb:** use the script, never a bare `adb push` (pushed files would be unreadable to the app):

```bash
tools/device/push_scores.sh "Op 109/" some-piece.mid
```

It launches the app once if needed, pushes into `Android/data/com.tropicalstream.hammerklavier/files/Scores/`,
fixes the permissions and asks the app to rescan. Imported works appear in the Library under *Imported*.
Files without a sustain pedal whose range fits the harpsichord and whose name mentions Bach, Scarlatti,
Handel, Couperin or Rameau open on the harpsichord; everything else on the grand.

## Rebuilding the assets (optional)

The kits and bundled MIDI are already committed. To rebuild them from the sources:

```bash
python3 tools/pipeline/fetch.py --manifest samples --tier core
python3 tools/pipeline/fetch.py --manifest midi
python3 tools/pipeline/kit_build.py --kit grand-std   # also grand-hd, upright, harpsichord
python3 tools/pipeline/build_catalog.py && python3 tools/pipeline/build_credits.py
```

Downloads go to `tools/cache/` (ignored by git).

## Credits

- **Grand piano:** Salamander Grand Piano V3 by Alexander Holm (Yamaha C5), CC-BY 3.0 / public domain (2022).
- **Upright and harpsichord:** Versilian Community Sample Library (VCSL), Versilian Studios / Sam Gossner;
  upright sampled by Simon Dalzell (Ivy Audio). CC0 1.0.
- **Piano performances:** Bernd Krueger, http://www.piano-midi.de, CC BY-SA 3.0 Germany (files unmodified).
- **Harpsichord performances:** John Sankey, © John Sankey, free to copy and play under his notice.
- Further pieces from Wikimedia Commons (Shane Nieb, Michael Bednarek, Ricardo André Frantz) and The Mutopia Project.

The full list, with every asset's source, licence and whether it was modified, is in [CREDITS.md](CREDITS.md),
`app/src/main/assets/licenses/` and the app's own *Credits* and *About* panels (menu → More).

Code © tropicalstream.
