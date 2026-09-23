# Hammerklavier: device-first architecture proposal

**Lens:** device-first. The RayNeo X3 Pro (ARGF20, serial `A06B4A96A733283`) sets the limits: Snapdragon AR1 Gen1 with 4 cores, 4 GB RAM with `low_ram=true`, no NDK, a 640×480 waveguide per eye where black is transparent, input only from the right temple pad, and a history of heat reboots. This proposal describes the leanest app that still looks and sounds real, and gives every subsystem a budget that can be measured on the glasses.
**Date:** 2026-09-22. **Status:** proposal (one of three). **Project:** `/Users/me/Projects/Hammerklavier`, package `com.tropicalstream.hammerklavier`.
**Inputs, read in full:**
- `docs/research/sampled-instruments.md`, plus the `sample-download-manifest.tsv` list
- `docs/research/instrument-mechanics-and-sound.md`
- `docs/research/repertoire.md`
- `docs/research/engine_reuse.md`
- `docs/research/visual_design.md`
- `/Users/me/Documents/FABLE_X3_STARTER_GUIDE.md`, the input, audio and gotcha sections
- MathCosmos `StereoMathRenderer.kt`, `MainActivity.kt` and `MathCosmosView.kt`

**How to read the numbers:**
- **(est.)** marks an engineering estimate. A named acceptance test in §8 turns it into a measurement before milestone M7.
- **[R:file §n]** points to the research report the number comes from.
- Wherever the research gave options, this document picks one.

---

## 0. The design on one page

### 0.1 Decisions

| Area | Decision | Why this is the device-first choice |
|---|---|---|
| Instruments | **Three.** Grand (Salamander C5, 6 velocity layers), upright (VCSL "Knight" plus the VSCO-2 pp layer, 3 layers), and a **Bach-era harpsichord** (VCSL Flemish 8′+4′ in a French double case). | These are the only real-sample sets we may redistribute. No fortepiano set is shippable [R:sampled-instruments §3.4], and the brief requires real samples. |
| Views | **Three on the swipe ring: Player → Action → Hall.** Each has a second framing on vertical swipe. The research's "Inside" view becomes Action's second framing. | Three stops are easy to learn in five minutes. The overhead framing reuses the same meshes, so it costs only a camera. |
| Master clock | **The audio output.** Only time crosses threads, never state. The GL thread gets the song time actually being heard from a seqlock record the audio thread publishes (frames written + `AudioTrack.getTimestamp`). | There is one clock, so there is no drift and no locks. |
| Visual state | Keys, hammers and dampers are **analytic functions of (immutable Performance, song time)**, evaluated on the GL thread. Lookahead comes for free: a key starts moving at `t_on − travel(v)` because the whole score is known in advance. | It is stateless and can seek. Nothing gets copied from the audio thread 30 times a second. |
| Audio output | One `AudioTrack`: float, 48 kHz, stereo, `USAGE_MEDIA`, **`PERFORMANCE_MODE_NONE`**, a 4096-frame (85 ms) buffer, 256-frame render blocks, `THREAD_PRIORITY_URGENT_AUDIO`. | This is a player, not an instrument, so stability matters more than latency. The thread wakes about 50 times a second instead of about 250 on the fast path, and the buffer has 85 ms of slack against thermal jitter. The timestamp takes care of A/V sync. |
| Sample storage | Ogg Opus at 128 kb/s in the APK (43 MB). It is **decoded once** into a 16-bit PCM cache in `filesDir/pcm` (516 MiB for all three instruments), and **memory-mapped** read-only. Prefetch is driven by the Performance. | The heap limit is 192 MB and the device is `low_ram`. Page-cache pages are clean and the kernel can evict them. The score says which samples are needed 1.5 s ahead. |
| Voices | Cap of 64 / 48 / 40 / 32 voices depending on thermal level. 4-point Hermite interpolation (linear from Q2). Sample data is staged into per-voice `ShortArray` windows by bulk copy. | Worst case is ≤ 28% of one core (est.). A typical piece uses about 11%. |
| Sympathetic resonance | **36 fixed string resonators** (C2–B4), gated by how far the dampers are lifted. | This is what the open strings physically do, for about 1% of a core. |
| Reverb | **8-line FDN plus 12 early-reflection taps** taken from the Konzertzimmer geometry. The mix changes with the view. | Convolution with a 1.3 s impulse response would take 30–50% of a core. |
| Rendering | GLES 2.0 context, static VBOs, uniform-array skinning for all moving parts. **≤ 28 draws per eye**, ≤ 45k triangles per eye, 30 fps, and **10 fps when nothing moves**. | The sibling apps proved this on the device. Draw calls are what cost heat, not fill rate [R:visual_design §5.2]. |
| Mirrors | Reflected flame sprites are culled against the mirror rectangles **on the CPU**. There is no stencil pass. | It needs no EGL stencil config and costs about 250 point tests per frame. |
| Thermal | **One ladder, Q0–Q3**, steps GL and audio down together, driven by battery temperature (39.0 / 42.0 / 44.0 °C with 1.5 °C hysteresis). | Thermal status reports 0 on this device even when the chip is hot [R:engine_reuse §0]. |
| Import | Companion page (NanoHTTPD on :19112, token-gated uploads) plus `adb push` into `…/files/Scores/`. | The patterns are already proven in WanderQuest and TapVibe. |

### 0.2 Budgets

| Subsystem | Budget | Measured by (§8) |
|---|---|---|
| Audio thread | ≤ 28% of one core at 64 voices (Q0 worst case); ≤ 12% typical. p99 block render ≤ 1.3 ms of the 5.33 ms block | `BlockStats` in logcat, `top -H` |
| GL thread | ≤ 6 ms of CPU per frame (target 3 ms). ≤ 28 draws and ≤ 45k triangles per eye | `RenderStats` in logcat, `top -H` |
| UI thread | < 3% of one core. Overlays redraw ≤ 1 Hz except in response to input | `top -H` |
| Java heap | ≤ 48 MiB | `dumpsys meminfo` |
| Proportional memory (PSS), excluding clean page cache | ≤ 200 MiB | `dumpsys meminfo` |
| APK | ≤ 60 MB, enforced by the build (51 MB planned) | `check_apk_budget.py` |
| First sound on first run | ≤ 8 s, by decoding the most-used grand layer first; ≤ 4 s once cached | logcat `HKLoader` |
| A/V sync | Hammer-contact flash within ≤ 20 ms of the audio onset, after lead calibration | sync test, §8.5 |
| Underruns | 0 in a 30-minute soak with the densest piece | `AudioTrack.getUnderrunCount()` |
| Heat | Battery ≤ 39.5 °C after 30 min at Q0 in a 22 °C room, and no reboot | `soak.sh` |
| Brightness (APL, average picture level) | Player ≤ 9%, Action ≤ 9%, Hall ≤ 12% | `apl_meter.py` |
| GC | ≤ 2 garbage collections in 5 minutes of playback, which shows the audio and GL threads allocate nothing | `art.gc.gc-count` |

---

## 1. Product definition

### 1.1 Instruments offered, and why

| | Grand | Upright | Harpsichord · Bach era |
|---|---|---|---|
| Samples | Salamander Grand V3 (Yamaha C5): 30 notes a minor third apart × layers v1, v4, v7, v10, v13, v16; 88 key-release noises; 4 pedal noises [R:sampled-instruments §1.1] | VCSL Knight vl1 and vl2 (45 notes a whole tone apart), the VSCO-2 CE dyn1 pp layer (23 notes a major third apart), 45 releases, 8 pedal noises [§2.1] | VCSL Flemish 8′ (28 notes) and 4′ (26 notes), each with its own jack-fall release samples [§3.1] |
| Licence | CC-BY 3.0; the author later declared it public domain. Credited either way | CC0; courtesy credit | CC0; courtesy credit |
| Case drawn | 200 cm C5-size ebony grand, lid on full stick [R:visual_design §2.2] | 131 cm U3-size. **Walnut by default**, because ebony disappears on the waveguide; ebony and mahogany are options [§2.3] | French double after Blanchet c.1740: green-and-gold case, ebony naturals, bone sharps, cabriole stand [§2.4] |
| Compass | A0–C8 (MIDI 21–108) | A0–C8 | **FF–e‴ (MIDI 29–88)**, 60 keys on each of two manuals |
| Controls that move | Una corda (the keyboard slides), sostenuto, damper pedal | Soft pedal (the hammer rail moves), damper pedal. The middle pedal does not move | No pedals. Jacks, tongues and registers move |
| Last damper | 88 (E6) | 90 (F♯6) | none undamped |
| Default tuning | A440, equal temperament | A440, equal temperament | **A415, Werckmeister III** |
| Velocity | Selects the layer and trims gain | Selects the layer and trims gain | Sets key-travel lead time only; gain stays constant ±1 dB [R:mechanics §6.2] |

**Why a harpsichord answers "early Bach-style piano".** The only real samples we can ship from Bach's era are harpsichords. Filtering the Salamander to fake a fortepiano would break the brief's "realistic samples of actual piano sounds for each piano". The Menu calls this instrument "Harpsichord · Bach era". The Performance model supports a two-manual layout, so Sankey's two-channel Goldberg recordings play on the upper and lower manuals as he performed them [R:repertoire §6].

**v2 slot (not built in v1):** "Silbermann fortepiano". The kit map, the profile and the Action-view model are all written against `InstrumentProfile`, so a fourth instrument means adding one profile, one kit and one case mesh. Two things would unblock it: written permission from Dore Mark for his Clementi 1808 samples, or a CC0 fortepiano sample set.

**Not shipped in v1:**
- the harpsichord lute stop (optional 32.6 MB download);
- the Salamander `harm*` string-resonance samples, which the resonator bank (§3.10) replaces;
- Accurate-Salamander. It would be a drop-in later, because it uses the same `map.json` schema.

### 1.2 Views

**Swipe forward/back moves round the ring Player → Action → Hall → Player.** A **vertical swipe toggles the second framing** of the current view. Each view keeps its own framing.

| View | Primary framing | Second framing (swipe up/down) | Venue level | Reverb wet / stereo width | Head look-around |
|---|---|---|---|---|---|
| **Player** | Whole keyboard plus pedals, from just above the bench: camera (−0.10, 1.30, 1.55) → (0, 0.50, −0.12), vertical FOV 34°. White keys are 11 px wide, the key dip is 4.4 px and pedal travel is 5.5 px [R:visual_design §4.1] | **Follow:** three octaves tracking the centroid of the sounding notes (vertical FOV 30°, white keys 27.8 px), with a 200×150 px pedal inset | Stage | 0.20 / 1.0 | ±5° of parallax |
| **Action** ("hammers hitting strings") | **Cutaway:** clip plane at the highest sounding note, with ±6 neighbouring actions receding. Vertical FOV 22°; hammer travel is 48 px [§4.2] | **Overhead:** lid lifted off, looking down the string bed, all 88 hammers flicking up (vertical FOV 44°) [§4.3] | Stage | 0.16 / 0.9 | ±5° |
| **Hall** | Row 3 of the Konzertzimmer, wide (vertical FOV 40°) [§4.4] | **Life-size:** optical vertical FOV, default 18.3°, adjustable over CONTROL | Salon | 0.42 / 0.55 | World-locked: ±60° yaw, +45° pitch |

- **Transitions:** a 0.9 s ease-in-out camera glide. Each view has a fixed FOV, and the FOV is blended only when the two views differ by more than 10° [R:visual_design §4.5].
- **Cameras for the upright and harpsichord** follow the research camera table [§4.6].
- **Sound follows the view.** Moving to the Hall glides the reverb wet level from 0.20 to 0.42, narrows the stereo width to 0.55 and switches the early-reflection set to the row-3 listener. It costs nothing, and it is how the room would actually sound from those seats.

### 1.3 Input: what every gesture does in every context

The engine is WanderQuest's `TrackpadGestureEngine`, copied verbatim [R:engine_reuse §3.2]:
- light taps and firm clicks (the KEY event) are merged into one stream;
- a 300 ms resolver tells single, double and triple taps apart;
- each gesture fires one swipe;
- the left pad is ignored by name.

One addition: on the key path, events from a device whose name contains `cyttsp6` are dropped, so a firm click on the left arm is not taken as a tap. This is untested on the glasses; §8 checks it.

| Gesture | Title card | Playing (no menu) | Menu | Adjust (Position, Tempo) | Calibration card |
|---|---|---|---|---|---|
| Swipe forward | – | next view | next row (latched: one step per gesture) | +10 s / +5% | brighter swatch |
| Swipe back | – | previous view | previous row | −10 s / −5% | dimmer swatch |
| Swipe up | – | toggle second framing | page up (7 rows) | +60 s / +20% | – |
| Swipe down | – | toggle second framing | page down | −60 s / −20% | – |
| Tap | enter (once one layer is ready) | play / pause | select | confirm | accept |
| Double-tap | – | open the Transport menu | back one level; closes at the root | cancel and restore | cancel |
| Triple-tap | recentre gaze | recentre gaze and show the HUD | recentre gaze | – | – |
| Left temple pad | system volume, untouched. The app calls `setVolumeControlStream(STREAM_MUSIC)` and uses `USAGE_MEDIA`, so the pad controls the piano | ← same | ← same | ← same | ← same |

- A tap waits 300 ms to be told apart from a double-tap. That is acceptable for play/pause. Swipes fire the moment they are recognised.
- Any gesture while playing brings the HUD back for 6 s.
- **More › Reverse swipe direction** exists because the system's "natural mode" setting flips the slide semantics [GUIDE Part IV §9].

### 1.4 Library, menus and the Transport card

**The menu card:**
- Centred in each eye, 400×300 px: a 22 px title, at most 7 rows at 18 px, and a 14 px footer hint ("⇄ move · tap choose · double-tap back").
- Warm white text (255,236,200) with a bloom, over a single thin gilt rule.
- **No dark box**, because a box on the waveguide is invisible and only adds edges [R:visual_design §5.4].
- The scene keeps playing behind the menu.

**Transport** (double-tap while playing):

| # | Row | Tap does |
|---|---|---|
| 1 | ❚❚ Pause / ▶ Play | toggles |
| 2 | Next: *«next title»* | next movement in the playlist |
| 3 | Previous | restarts the movement if more than 3 s in, otherwise goes to the previous one |
| 4 | Position 3:12 / 9:53 | enters Adjust. Forward/back is ±10 s, up/down is ±60 s, tap seeks, double-tap cancels |
| 5 | Instrument: Grand › | a sub-list of Grand, Upright and Harpsichord · Bach era, marked "piece default" and with voicing progress. Choosing one switches at the current position |
| 6 | Library › | shelves |
| 7 | More › | Tempo (Adjust, 50–150% in 5% steps), Temperament, Pitch (A440 / 430 / 415 / 392), Harpsichord registration (8′ / 8′+4′), Upright finish, Room (Sanssouci 1747 / Stadtschloss 1747), Venue detail (Auto / Salon / Stage / Instrument / Passthrough), Reverb (Dry / Room / Resonant), Stereo depth, Look-around on/off, Black-key visibility (calibration card), Reverse swipe, Import from phone, Credits, About |

**Library › shelves**, 15 rows in the repertoire's order [R:repertoire §4]:
- Start here (13)
- Bach · the young virtuoso
- Bach · teaching the keyboard
- Bach · Well-Tempered Clavier
- Bach · suites, partitas & variations
- Handel
- Scarlatti
- The French clavecinists
- Galant & Empfindsamkeit
- Haydn
- Mozart
- Clementi
- Beethoven
- Imported (n)
- Recently played

**Opening a shelf and playing:**
- Selecting a shelf lists its works, one row each, for example: `Beethoven · Sonata op. 106 "Hammerklavier"   35:35  G`. The trailing G, U or H marks the default instrument.
- A work with one movement starts playing on tap. A multi-movement work opens a movement list headed by **Play all ▶**.
- Playing a movement builds the playlist: that movement, the rest of its work, then the remaining works on the shelf.

**What gets remembered:** the last movement, the position and the instrument chosen for each work. The title card then offers "Tap to continue: «title»".

### 1.5 Companion upload page (phone browser)

The server merges two proven ones [R:engine_reuse §5]:
- **WanderQuest's structure:** the token is injected into a page served from `assets/`, routing is wrapped in `runCatching`, and `deviceIp()` finds the address. WanderQuest uses the first IPv4; TapVibe's site-local-first rule is better, so use that.
- **TapVibe's multipart `/upload`,** plus the `MusicLibrary`-style byte-for-byte storage.

**Server settings:**
- NanoHTTPD 2.3.1 on port **19112**, plain HTTP.
- A random 16-character token per install. Reads are open to the LAN; every write needs the token (`x-hk-token` header, a JSON `token` field, or `?token=`).

**The page offers:**
- Upload by drag and drop (whole folders via `webkitGetAsEntry`) or with a file picker. Each file is its own `FormData` POST with a progress bar.
- A library list with a filter box. Tapping an entry plays it on the glasses.
- Buttons for play/pause, next and previous, instrument and view.
- Now playing (polled every 2 s).
- Delete for imported files.

| Route | Method | Body / query | Effect |
|---|---|---|---|
| `/`, `/index.html` | GET | – | `companion.html` with `%%HK_TOKEN%%` replaced |
| `/api/library` | GET | – | shelves, works and movements as JSON (built-in plus imported) |
| `/api/now` | GET | – | `{movementId, title, instrument, view, positionSec, durationSec, playing}` |
| `/api/upload` | POST | multipart | Accepts `.mid`, `.midi`, `.kar` and `.zip`. Limits: 2 MB per MIDI, 20 MB and 200 entries per zip. Every file must start with `MThd` (or be RIFF `RMID`). Temp files are copied inside the handler. Returns `{saved:[…], rejected:[…]}` |
| `/api/delete` | POST | `?id=` | removes an imported file |
| `/api/play` | POST | `?id=<movementId>` | plays it |
| `/api/transport` | POST | `?cmd=toggle\|next\|prev` | – |
| `/api/instrument` | POST | `?id=grand\|upright\|harpsichord` | – |
| `/api/view` | POST | `?id=player\|action\|hall` | – |

Two NanoHTTPD gotchas are handled:
- JSON bodies are read as `content-length` bytes and decoded as UTF-8 by hand, because titles contain names like Händel and Für Elise [R:engine_reuse §5.3].
- Every command is posted to the main thread.

The server starts in `onCreate` as a daemon (`start(5000, true)`) and stops in `onDestroy`. It costs nothing while idle, because the accept thread is blocked. The URL is shown on the title card, under **More › Import from phone**, and in the header row of the Imported shelf. If Wi-Fi is off, those places read "no Wi-Fi: use adb push" instead.

### 1.6 adb push folder

- **Folder:** `/sdcard/Android/data/com.tropicalstream.hammerklavier/files/Scores/`, created on first launch. It is the same `ImportStore` directory the companion writes to.
- **Rescans happen** on resume, when the Library opens, and on `am broadcast … CONTROL --ez rescan true`.
- **Loose file:** becomes one work with one movement.
- **Subfolder:** becomes one work, with its files as movements in filename order. So `adb push "Op 109/" …/Scores/` produces a three-movement sonata.
- **Titles:** taken from the MIDI track-name or copyright meta events, falling back to the file name.
- **Default instrument:** grand if the file has sustain pedal; harpsichord if its range fits 29–88, it has no pedal, and it came from a folder named like `bach` or `scarlatti`; otherwise grand.
- **Privacy:** imported files never leave the device. The companion page only lists them on the LAN.

```bash
adb -s A06B4A96A733283 push "Op 109/" /sdcard/Android/data/com.tropicalstream.hammerklavier/files/Scores/
adb -s A06B4A96A733283 shell am broadcast -a com.tropicalstream.hammerklavier.CONTROL --ez rescan true
```

### 1.7 On-screen status

All 2D status is Android Views inside the single child of `BinocularSbsLayout`, so it is drawn in both eyes [R:engine_reuse §2.8]. Text is at least 14 px, warm white on transparency, with a bloom.

| Place (per 640×480 eye) | Content | Updates |
|---|---|---|
| Top-left, 18 px | `Beethoven · Sonata op. 106 "Hammerklavier"` | on movement change |
| Top-left, second line, 14 px | `I. Allegro · bar 112` | 1 Hz |
| Top-right, 14 px | `Grand · A440 equal` | on change |
| Bottom-left, 14 px | `3:12 / 9:53` over a 1 px gilt progress rule, 200 px long | 1 Hz |
| Bottom centre, 14 px | Source credit, for example `Performance: Bernd Krueger · piano-midi.de · CC BY-SA 3.0 DE`, shown for the first 8 s of every movement (a licence obligation [R:repertoire §3]) | per movement |
| Top centre, 18 px, 1.5 s | View toast: `Action · hammers`, `Hall · Konzertzimmer` | on swipe |
| Bottom-right, 14 px | Pills: `voicing upright 42%`, `paused`, and `▲ warm` at Q2 or above | on change |
| Centre-bottom, 14 px, first 3 sessions | Hint: `⇄ views · tap pause · double-tap menu` | – |
| Debug overlay (`--ez debug true`) | fps, draws, triangles, voices, block p50/p99/max, underruns, battery °C, Q level, clock drift | 1 Hz |

- The HUD **hides itself after 6 s without input while playing**. Toasts and credits still appear.
- Views are hardware layers, and none refreshes faster than 1 Hz. Fast overlay refresh has starved the audio decoder on this device before [GUIDE gotcha 7].

### 1.8 The first five minutes

1. **Launch.** A title card, "HAMMERKLAVIER · Konzertzimmer, Sanssouci 1747", shown over the dim candlelit room (the Hall view at Stage level, rendered at 10 fps).
   - **First run:** the card reads `Voicing the grand… 12%`. The grand is decoded layer by layer, most-used first: v10, v13, v7, v16, v4, v1, then releases and pedals.
   - When v10, the releases and the pedals are ready (about 5 s, est.), the card changes to `Tap to enter the Konzertzimmer`.
   - Notes whose layer is not decoded yet play the nearest decoded layer.
2. **Tap.** The gaze is recentred, the Player view opens, and **Start here** begins with Bach's Prelude in C, BWV 846 (Krueger, on the grand). The hint line appears.
3. **The first minutes use only the core gestures.** Swiping reaches the hammers and then the room, and double-tap opens the Transport card, whose rows name every other function.
4. **While idle,** the upright and harpsichord decode in the background (§3.3).

---
## 2. Module architecture

### 2.1 Threads and the rules between them

| Thread | Priority | Owns | Wakes |
|---|---|---|---|
| **main** (UI) | default | Activity, `AppController`, gesture engine, overlays, `Settings`, `ThermalGovernor`, CONTROL receiver, companion callbacks after `post` | Input, a 1 Hz HUD tick, broadcasts |
| **GL** (`GLThread`) | default | `StereoRenderer`, all meshes and textures, `ActionModel`, `CameraDirector`, `GlyphBoard` | A Choreographer callback on every 2nd vsync (30 fps). Every 3rd, 4th or 6th vsync at lower quality or when idle |
| **HKAudio** | `THREAD_PRIORITY_URGENT_AUDIO` + `MAX_PRIORITY` | `AudioTrack`, `Sequencer`, `VoicePool`, the DSP chain | Blocks in `write`; about 50 wakes/s, each rendering about 4 blocks |
| **HKLoader** | `THREAD_PRIORITY_BACKGROUND`, single-thread executor | SMF parsing, Performance building, catalogue and import scans, kit decoding, building mesh FloatArrays | On demand |
| **HKPrefetch** | `THREAD_PRIORITY_BACKGROUND` | Touches mmap pages ahead of voices and upcoming notes | Every 50 ms while playing, otherwise parked |
| **NanoHTTPD** | default (daemon) | HTTP I/O | On request |
| Sensor callbacks | main looper | `GazeCamera` (game rotation vector) | `SENSOR_DELAY_GAME` |

**Rules:**
1. After warm-up, **HKAudio and GL allocate nothing**: no boxing, no lambdas capturing per call, no string formatting, no iterators.
2. Only four kinds of traffic cross threads:
   - **(a)** immutable objects (`Performance`, `LoadedKit`, `TuningTable`, `QualityProfile`, `RenderSettings`), published by `@Volatile` reference or through the command ring;
   - **(b)** the single-producer single-consumer **command ring** from UI to audio;
   - **(c)** the **seqlock clock record** from audio to any reader;
   - **(d)** `@Volatile` stats scalars.
3. The only blocking call on HKAudio is `AudioTrack.write`. File I/O on it is limited to page faults in the mapped cache, and HKPrefetch exists to keep those rare.
4. Objects are built on HKLoader or main and handed over whole. Nothing is shared and then mutated.

### 2.2 Package map: every Kotlin file, one responsibility, one owner

Base package `com.tropicalstream.hammerklavier`. WP = work package (§7).

| File | Responsibility | WP |
|---|---|---|
| `HammerklavierApp.kt` | Application; creates `Settings`, the loader executor and `KitManager` once | 1 |
| `MainActivity.kt` | Lifecycle; builds the view tree (GL view + `BinocularSbsLayout` + overlays); feeds the gesture engine from `dispatchKeyEvent`, `dispatchTouchEvent` and `dispatchGenericMotionEvent`; holds audio focus and the route callback; registers the thermal, battery and CONTROL receivers | 1 |
| `core/Ids.kt` | `InstrumentId`, `ViewId`, `RoomLevel`, `OutputRoute`, `Swipe` | 1 |
| `core/Clock.kt` | `ClockSample`, `SongClock` | 1 |
| `core/Quality.kt` | `QualityProfile`, `QualityLadder` (Q0–Q3 table, §5.9) | 1 |
| `core/Tags.kt` | Logcat tags: `HKAudio`, `HKClock`, `HKRender`, `HKThermal`, `HKInput`, `HKLoader`, `HKLib`, `HKWeb` | 1 |
| `app/Settings.kt` | Typed SharedPreferences with change listeners | 1 |
| `app/ThermalGovernor.kt` | Battery temperature plus `PowerManager` thermal status → quality level with hysteresis. A superset of the MathCosmos governor | 1 |
| `app/DebugControl.kt` | The `…CONTROL` broadcast receiver; turns extras into `AppController.debug()` calls | 1 |
| `input/TrackpadGestureEngine.kt` | Copy of the WanderQuest engine, plus the `cyttsp6` filter on the key path | 1 |
| `ui/BinocularSbsLayout.kt` | Copy of the MathCosmos layout (95 lines) | 1 |
| `app/AppController.kt` | The UX state machine (Title, Loading, Playing, Menu, Adjust, Calibrate). Routes gestures, builds playlists, switches instruments, implements `CompanionCommands` | 11 |
| `ui/MenuModel.kt` | Pure menu tree and cursor logic: rows, paging, actions. JVM-testable | 11 |
| `ui/MenuCard.kt` | Draws a `MenuState` with Canvas. No touch handling | 11 |
| `ui/HudView.kt` | Status lines, toasts, pills, credits, hints, debug overlay | 11 |
| `ui/TitleCard.kt` | Title, voicing progress, companion URL | 11 |
| `ui/CalibrationCard.kt` | 16 warm swatches for the presence floor [R:visual_design §3.6] | 11 |
| `ui/OverlayViews.kt` | Builds the one logical 640×480 `FrameLayout` holding all of the above | 11 |
| `midi/SmfParser.kt` | Bytes → `RawSmf`: formats 0, 1 and 2, running status, meta, sysex skipping, RIFF-RMID unwrapping. Tolerant | 2 |
| `midi/RawSmf.kt` | `RawSmf`, `RawTrack`, `SmfException` | 2 |
| `midi/TempoMap.kt` | Tick ↔ µs (PPQ and SMPTE); bar starts from time signatures | 2 |
| `midi/InstrumentProfile.kt` | Per-instrument constants: compass, last damper, damper lag, action geometry, pedal policy | 2 |
| `midi/Performance.kt` | The immutable playable score, its per-key index, `PedalCurve` and the packed audio event stream | 2 |
| `midi/PerformanceBuilder.kt` | `RawSmf` + profile → `Performance`: note pairing, re-strike serialisation, range folding, pedal slew, legato hold, sostenuto latches, damper lag | 2 |
| `midi/SyntheticPerformances.kt` | Test scores: the A/V sync click, chromatic scale, chord storm, pedal sweep | 2 |
| `audio/AudioEngine.kt` | Owns the `AudioTrack` and HKAudio; the block loop; the mixer graph; implements `SongClock` | 3 |
| `audio/CommandRing.kt` | SPSC lock-free ring of `(code:Int, a:Long, b:Float, ref:Any?)`. Refs are allocated by the producer | 3 |
| `audio/ClockPublisher.kt` | The seqlock record: frames, song µs, rate, timestamp, epoch, playing | 3 |
| `audio/Sequencer.kt` | Walks `Performance.ev` per block and turns events into voice starts and key-ups at frame offsets. Samples the pedal curves and runs the sostenuto mask | 3 |
| `audio/VoicePool.kt` | Voice allocation, per-key bookkeeping, re-strike fades, stealing, per-block gain control | 3 |
| `audio/Voice.kt` | One sample playback: fixed-point position, window staging, Hermite or linear inner loop, gain ramp | 3 |
| `audio/DamperModel.kt` | Pure pedal and damper maths plus the per-block damping-gain table | 3 |
| `audio/Tuning.kt` | `Temperament` tables, pitch standards, `TuningTable`, the `exp2` cents table | 3 |
| `audio/Prefetcher.kt` | HKPrefetch: touches pages for active voices and for notes 1.5 s ahead | 3 |
| `audio/BlockStats.kt` | Histogram of block render times; underrun polling; a logcat line every 10 s | 3 |
| `audio/kit/KitMap.kt` | Parses `map.json` into `KitMap`, `KitLayer` and `KitRegion` | 4 |
| `audio/kit/LayerSelector.kt` | Pure lookup (key, velocity, stop, ready mask) → region; also release and pedal regions | 4 |
| `audio/kit/SampleStore.kt` | Opens and memory-maps a PCM cache file; `SampleReader` bulk reads and touches | 4 |
| `audio/kit/KitDecoder.kt` | Opus asset → PCM cache via `MediaExtractor`/`MediaCodec`, progressive by layer, resumable | 4 |
| `audio/kit/KitManager.kt` | Cache status, open/close, background decoding when idle, readiness callbacks | 4 |
| `audio/kit/SyntheticKit.kt` | An on-device additive "piano" kit (partials plus decay) so audio can be brought up before the real kits exist | 4 |
| `audio/dsp/Biquad.kt` | RBJ biquad, float, stereo-in-place | 5 |
| `audio/dsp/HallReverb.kt` | 8-line FDN, fast Hadamard, per-line one-pole damping, predelay, 12-tap early-reflection sets with crossfade | 5 |
| `audio/dsp/ResonatorBank.kt` | 36 two-pole string resonators for sympathetic resonance | 5 |
| `audio/dsp/MasterBus.kt` | Soft-bus shelf, mid/side width, speaker voicing, gain/duck, 1 ms look-ahead limiter, Padé soft clip | 5 |
| `render/HkGlView.kt` | `GLSurfaceView`: EGL config (MSAA attempt with fallback), Choreographer pacing, implements `RenderControl` | 6 |
| `render/StereoRenderer.kt` | `onDrawFrame`: clock sample → `ActionModel` → camera → layers `update` once → two eyes `draw` | 6 |
| `render/StereoRig.kt` | Off-axis parallel-eye frusta, per-view IPD scale, zero-parallax distance, gaze rotation | 6 |
| `render/CameraDirector.kt` | View and framing poses, 0.9 s glides, follow spring, cut-plane tracking | 6 |
| `render/GazeCamera.kt` | Copied verbatim from MathCosmos | 6 |
| `render/GlyphBoard.kt` | Copied verbatim from MathCosmos (label cap lowered to 32) | 6 |
| `render/gl/GlUtil.kt` | `makeVbo`, `DynMesh`, shader compile helpers, `FRAG_PRECISION` (copied from MathCosmos) | 6 |
| `render/gl/Programs.kt` | Compiles and holds every program; attribute and uniform handles | 6 |
| `render/gl/Shaders.kt` | GLSL ES 1.00 sources: lit, lacquer (rim, probe, floor), skinned-rotate, skinned-translate, spindle strings, sprites, ribbons, decals, section-cap | 6 |
| `render/mesh/MeshBuilder.kt` | FloatArray geometry helpers: box, extrusion, lathe, Catmull-Rom sweep, ribbon, skinned-part tagging | 6 |
| `render/SceneLayer.kt` | `SceneLayer`, `FrameContext`, `EyeContext`, `GlKit` | 6 |
| `render/model/InstrumentModel.kt` | Base class plus `InstrumentAnchors`; factory by `InstrumentId` | 7 |
| `render/model/KeyboardMesh.kt` | Procedural keys for any compass or octave span, with analytic bevel UVs | 7 |
| `render/model/GrandModel.kt` | Case, lid, legs, lyre, pedals, plate, soundboard, the 13-slot action set, 88 hammers, 70 dampers, 228 strings | 7 |
| `render/model/UprightModel.kt` | Upright case, action, hammer rail, dampers, strings, pedals | 7 |
| `render/model/HarpsichordModel.kt` | Blanchet double: two manuals, 180 jacks with tongues and plectra, registers, strings, stand | 7 |
| `render/anim/Mechanics.kt` | The Goebl/Askenfelt timing fits and the regulation constants [R:mechanics §11] | 8 |
| `render/anim/MechanismPose.kt` | Per-key pose arrays plus pedal scalars | 8 |
| `render/anim/ActionModel.kt` | Analytic key, hammer, damper and jack state at song time t, with lookahead; integrated string envelopes | 8 |
| `render/anim/PoseArrays.kt` | Packs poses into vec4-lane uniform arrays | 8 |
| `render/venue/VenueModel.kt` | The Konzertzimmer as a `SceneLayer`; venue levels; `LightRig` | 9 |
| `render/venue/VenueMesh.kt` | Room shell, cove, trellis ribbons, mirrors, windows, doors, chairs, chandelier, sconces, candelabra, floor | 9 |
| `render/venue/Atlas.kt` | Procedural rocaille, trellis, parquet and hunting-frieze textures drawn with Canvas at load (no image assets) | 9 |
| `render/venue/Candles.kt` | About 50 flames, flicker, mirror and floor reflection visibility (on the CPU), crystal sparkle | 9 |
| `render/venue/LightProbe.kt` | Bakes the 128×64 equirectangular reflection probe for lacquer | 9 |
| `render/venue/Palette.kt` | The single source of truth for colour tokens (sRGB lit-peak values [R:visual_design §3.5]), the presence floor and the palette variants; also used by WP7 | 9 |
| `library/CatalogModel.kt` | `LibraryModel`, `Shelf`, `Work`, `Movement`, `Source` | 10 |
| `library/Catalog.kt` | Loads `assets/catalog.json` and merges in imports | 10 |
| `library/ImportStore.kt` | `Scores/`: save (sanitise, validate `MThd`, dedupe by SHA-1), zip extraction, scan, delete, `index.json` | 10 |
| `library/Playlist.kt` | Queue, next/previous rules, resume point | 10 |
| `companion/CompanionServer.kt` | NanoHTTPD routes, token, multipart, `deviceIp()` | 10 |
| `assets/companion.html` | The phone page (vanilla JS) | 10 |

That makes 77 files across 12 work packages. Nothing uses reflection or dependency injection, and there is no vendor SDK. The dependencies are `androidx.core`, `appcompat`, `nanohttpd:2.3.1` and `androidx.profileinstaller` (§8.1).

### 2.3 Public APIs: the interfaces agents code against

These signatures are **frozen at the start of integration**. Changing one needs a change note in `docs/contracts-changelog.md`, which WP1 approves. Every work package's first commit, within its first hour, is its API file with `TODO()` bodies that compile against these signatures exactly.

```kotlin
// ───────────── core (WP1) ─────────────
package com.tropicalstream.hammerklavier.core
enum class InstrumentId(val key: String) { GRAND("grand"), UPRIGHT("upright"), HARPSICHORD("harpsichord");
    companion object { fun of(key: String): InstrumentId? = entries.firstOrNull { it.key == key } } }
enum class ViewId { PLAYER, ACTION, HALL }
enum class RoomLevel { SALON, STAGE, INSTRUMENT, PASSTHROUGH }
enum class OutputRoute { SPEAKER, HEADSET }
enum class Swipe { FORWARD, BACK, UP, DOWN }

class ClockSample {
    @JvmField var songUs = 0L        // song time (µs of file time at rate 1) reaching the ear at the asked instant
    @JvmField var rate = 1f          // current transport rate
    @JvmField var playing = false
    @JvmField var epoch = 0          // bumps on seek, new performance, instrument swap
    @JvmField var valid = false      // false until the first AudioTimestamp (estimate used meanwhile)
}
interface SongClock { fun sample(nanoTime: Long, out: ClockSample) }   // allocation-free, any thread

class QualityProfile(
    val level: Int, val frameDivider: Int, val roomCap: RoomLevel, val mirrorFlames: Boolean,
    val crystals: Int, val msaa: Boolean, val voiceCap: Int, val hermite: Boolean,
    val resonance: Boolean, val reverbLines: Int)
object QualityLadder { fun of(level: Int): QualityProfile }            // table in §5.9

// ───────────── midi (WP2) ─────────────
package com.tropicalstream.hammerklavier.midi
class SmfException(msg: String) : Exception(msg)
class RawTrack(val name: String?, val tick: LongArray, val status: IntArray, val data1: IntArray, val data2: IntArray)
class RawSmf(val format: Int, val division: Int, val tracks: List<RawTrack>, val tempo: TempoMap,
             val texts: List<String>, val warnings: List<String>)
object SmfParser {
    fun sniff(head: ByteArray): Boolean                                 // "MThd" or RIFF "RMID"
    @Throws(SmfException::class) fun parse(bytes: ByteArray): RawSmf
}
class TempoMap(division: Int, tempoTick: LongArray, usPerQuarter: IntArray,
               sigTick: LongArray, sigNum: IntArray, sigDen: IntArray) {
    fun tickToUs(tick: Long): Long
    fun usToTick(us: Long): Long
    fun barStartsUs(endTick: Long): LongArray
}
class InstrumentProfile(
    val id: InstrumentId, val lowKey: Int, val highKey: Int, val lastDamper: Int,
    val damperLagMs: Float, val keyDipMm: Float, val keyReturnMs: Float, val repeatMinMs: Float,
    val blowMm: Float, val letOffMm: Float, val checkMm: Float, val actionRatio: Float,
    val travelScale: Float, val usesSustain: Boolean, val usesSoft: Boolean, val usesSostenuto: Boolean,
    val legatoHoldMaxMs: Float, val velocityGain: Boolean, val manuals: Int,
    val defaultPitchHz: Float, val defaultTemperament: String) {
    companion object { val GRAND: InstrumentProfile; val UPRIGHT: InstrumentProfile; val HARPSICHORD: InstrumentProfile
                       fun of(id: InstrumentId): InstrumentProfile }
}
class PedalCurve(val us: LongArray, val v: FloatArray) {                 // slew-limited, piecewise linear, v in 0..1
    val isEmpty: Boolean
    fun valueAt(t: Long): Float                                         // binary search
    fun valueAt(t: Long, cursor: IntArray, slot: Int): Float            // monotone cursor, O(1) amortised
    fun nextCrossing(t: Long, level: Float): Long                       // Long.MAX_VALUE if none
}
class PerfInfo(val title: String?, val lowKey: Int, val highKey: Int, val sustainEvents: Int,
               val softEvents: Int, val sostenutoEvents: Int, val maxPolyphony: Int, val warnings: List<String>)
class Performance(
    val id: String, val instrument: InstrumentId, val durationUs: Long,
    // notes, sorted by onUs then key. onUs = hammer contact / pluck = MIDI note-on
    val onUs: LongArray, val offUs: LongArray,                          // offUs = finger leaves key (after serialisation/legato)
    val key: ByteArray, val vel: ByteArray, val manual: ByteArray, val track: ByteArray,
    val keyFirst: IntArray,  /* size 129: CSR offsets */ val keyNotes: IntArray,  /* note indices by key, by onUs */
    val sustain: PedalCurve, val soft: PedalCurve, val sostenuto: PedalCurve,
    val latchUs: LongArray, val latchLo: LongArray, val latchHi: LongArray,   // sostenuto latch masks (keys 0..127)
    val evUs: LongArray, val ev: IntArray,                              // audio event stream, time-sorted
    val barUs: LongArray, val info: PerfInfo) {
    val noteCount: Int get() = onUs.size
    fun eventIndexAtOrAfter(us: Long): Int
    fun barAt(us: Long): Int
    fun latchMaskAt(us: Long, out: LongArray)                           // out[0]=lo, out[1]=hi
    companion object {
        const val EV_NOTE_ON = 1; const val EV_KEY_UP = 2; const val EV_LATCH = 3; const val EV_END = 15
        fun type(e: Int) = e ushr 28                                    // 4 bits
        fun arg(e: Int) = e and 0x0FFFFFFF                              // note index or latch index
        fun pack(type: Int, arg: Int) = (type shl 28) or arg
    }
}
class BuildOptions(val legatoHold: Boolean = true, val manualOfChannel: IntArray? = null,
                   val pedalFullTravelMs: Float = 70f, val flatVelocity: Int = 0)   // >0: replace every velocity (catalogue "flat")
object PerformanceBuilder {
    fun build(id: String, smf: RawSmf, profile: InstrumentProfile, opts: BuildOptions = BuildOptions()): Performance
}
object SyntheticPerformances {
    fun syncClick(p: InstrumentProfile): Performance                    // A4 vel 118 every 600 ms, 60 s
    fun scale(p: InstrumentProfile): Performance
    fun chordStorm(p: InstrumentProfile, voices: Int): Performance      // stress: `voices` notes per 250 ms, pedal down
}

// ───────────── audio (WP3) ─────────────
package com.tropicalstream.hammerklavier.audio
class RoomMix(val wet: Float, val width: Float, val earlySet: Int)     // earlySet: 0 bench, 1 cutaway, 2 row-3
class AudioStats { @JvmField var voices = 0; @JvmField var voicesPeak = 0; @JvmField var stolen = 0
                   @JvmField var blockP50Us = 0; @JvmField var blockP99Us = 0; @JvmField var blockMaxUs = 0
                   @JvmField var underruns = 0; @JvmField var faultsSlow = 0 }
interface AudioListener { fun onEnded(performanceId: String); fun onKitStarved(region: Int) }  // called on main
enum class Temperament { EQUAL, WERCKMEISTER_III, KELLNER, VALLOTTI, YOUNG_II, KIRNBERGER_III, LEHMAN, MEANTONE_QUARTER;
    fun offsetCents(pitchClass: Int): Float }                           // table in §3.14, A = 0
class TuningTable(val aHz: Float, val temperament: Temperament) {
    fun keyCents(key: Int): Float                                       // cents from A440-ET for this key
}
class AudioEngine(ctx: android.content.Context) : SongClock {
    fun start(); fun stop()                                             // idempotent; stop joins ≤ 350 ms
    fun setKit(kit: LoadedKit?)
    fun setPerformance(p: Performance?, startUs: Long = 0L, autoPlay: Boolean = false)
    fun play(); fun pause(); fun seek(us: Long); fun setRate(rate: Float)   // rate 0.5..1.5
    fun setTuning(t: TuningTable); fun setQuality(q: QualityProfile); fun setRoom(m: RoomMix)
    fun setRoute(r: OutputRoute); fun setDuck(gain: Float)
    fun setRegistration(stopsMask: Int)                                 // harpsichord: bit0 8′, bit1 4′
    fun setListener(l: AudioListener?)
    override fun sample(nanoTime: Long, out: ClockSample)
    fun stats(out: AudioStats)
}

// ───────────── kits (WP4) ─────────────
package com.tropicalstream.hammerklavier.audio.kit
class KitLayer(val id: String, val velLo: Int, val velHi: Int, val velRef: Int, val decodeOrder: Int)
class KitRegion(val file: String, val kind: Int, val layer: Int, val stop: Int, val root: Int, val lo: Int, val hi: Int,
                val tuneCents: Float, val gainDb: Float, val frames: Int, val onsetFrame: Int) {
    companion object { const val SUSTAIN = 0; const val RELEASE = 1; const val PEDAL_DOWN = 2; const val PEDAL_UP = 3 } }
class KitMap(val id: InstrumentId, val version: String, val sha1: String, val lastDamper: Int, val stops: Int,
             val layers: Array<KitLayer>, val regions: Array<KitRegion>) {
    companion object { fun parse(json: String): KitMap } }
class LayerSelector(map: KitMap) {                                      // pure, allocation-free
    fun sustain(key: Int, vel: Int, stop: Int, readyMask: Long): Int    // -1 if nothing decoded yet
    fun release(key: Int, stop: Int, readyMask: Long): Int
    fun pedal(down: Boolean, roundRobin: Int, readyMask: Long): Int
}
class SampleReader {                                                    // one per thread (own ShortBuffer duplicate)
    fun read(region: Int, fromFrame: Int, frames: Int, dst: ShortArray, dstOff: Int): Int  // stereo-interleaved; zero-fills past end
    fun touch(region: Int, fromFrame: Int, frames: Int)                 // reads 1 short per 4 KiB page
}
class SampleStore private constructor() : java.io.Closeable {
    val regionCount: Int
    fun frames(region: Int): Int
    fun newReader(): SampleReader
    companion object { fun open(file: java.io.File, map: KitMap): SampleStore }
}
class LoadedKit(val id: InstrumentId, val map: KitMap, val store: SampleStore, val selector: LayerSelector) {
    @Volatile var readyMask: Long = 0L                                  // bit per layer; bit 62 releases, bit 63 pedals
}
interface KitCallback {
    fun onProgress(id: InstrumentId, fraction: Float, readyMask: Long)
    fun onPlayable(kit: LoadedKit)                                      // ≥ 1 sustain layer + releases ready
    fun onComplete(kit: LoadedKit)
    fun onError(id: InstrumentId, t: Throwable)
}
class KitManager(ctx: android.content.Context, loader: java.util.concurrent.ExecutorService) {
    fun isComplete(id: InstrumentId): Boolean
    fun open(id: InstrumentId, cb: KitCallback)                         // callbacks on main
    fun decodeWhenIdle(ids: List<InstrumentId>)                         // background, pausable
    fun pauseBackground(); fun resumeBackground()
}
object SyntheticKit { fun build(ctx: android.content.Context, id: InstrumentId): LoadedKit }

// ───────────── dsp (WP5) — pure processors, all buffers caller-owned ─────────────
package com.tropicalstream.hammerklavier.audio.dsp
class HallReverb(sampleRate: Int) {
    fun configure(rt60Mid: Float, rt60High: Float, lines: Int)
    fun setEarly(set: Int, fadeMs: Float)
    fun process(inL: FloatArray, inR: FloatArray, outL: FloatArray, outR: FloatArray, n: Int, wet: Float)
    fun reset() }
class ResonatorBank(sampleRate: Int) {
    fun tune(keyCents: FloatArray /*128*/, t60: FloatArray /*128*/)
    fun process(dryL: FloatArray, dryR: FloatArray, outL: FloatArray, outR: FloatArray, n: Int, send: Float)
    fun reset() }
class MasterBus(sampleRate: Int) {
    fun setRoute(r: com.tropicalstream.hammerklavier.core.OutputRoute)
    fun setWidth(w: Float); fun setGain(g: Float)
    fun softBus(inL: FloatArray, inR: FloatArray, n: Int)               // in place: -2.5 dB + high shelf
    fun process(l: FloatArray, r: FloatArray, n: Int, outInterleaved: FloatArray)  // width→EQ→gain→limiter→clip
    fun reset() }

// ───────────── render core (WP6) ─────────────
package com.tropicalstream.hammerklavier.render
class CameraPose { @JvmField val pos = FloatArray(3); @JvmField val target = FloatArray(3)
                   @JvmField var vFovDeg = 34f; @JvmField var ipdScale = 0.6f; @JvmField var zeroParallaxM = 1.75f
                   @JvmField var clipX = Float.NaN; @JvmField var lidLift = 0f }
class FrameContext { @JvmField var tUs = 0L; @JvmField var rate = 1f; @JvmField var dt = 0f; @JvmField var now = 0f
                     @JvmField var view = ViewId.PLAYER; @JvmField var framing = 0; @JvmField var viewBlend = 1f
                     lateinit var pose: MechanismPose; lateinit var quality: QualityProfile; lateinit var lights: LightRig
                     @JvmField var room = RoomLevel.STAGE; @JvmField var camera = CameraPose()
                     @JvmField val floorRgb = FloatArray(3); @JvmField var paletteVariant = 0 }
class EyeContext { @JvmField val view = FloatArray(16); @JvmField val proj = FloatArray(16); @JvmField val viewProj = FloatArray(16)
                   @JvmField val eye = FloatArray(3); @JvmField var index = 0; @JvmField var width = 640; @JvmField var height = 480
                   @JvmField var pxPerRad = 0f }
class GlKit(val programs: com.tropicalstream.hammerklavier.render.gl.Programs, val glyphs: GlyphBoard, val maxVertexUniforms: Int)
interface SceneLayer {
    fun onGlCreated(kit: GlKit)                 // upload VBOs/textures (FloatArrays prepared off-thread)
    fun update(f: FrameContext)                 // once per frame, before both eyes: pack uniforms
    fun draw(f: FrameContext, e: EyeContext)    // per eye: ≤ the draw budget of the layer
    fun onGlReleased()
}
class RenderSettings(val stereoDepth: Float, val gaze: Boolean, val roomOverride: RoomLevel?, val palette: Int,
                     val presenceFloor: Int, val displayLeadMs: Int, val lifeSizeVFov: Float, val uprightFinish: Int)
class RenderStats { @JvmField var fps = 0f; @JvmField var draws = 0; @JvmField var tris = 0
                    @JvmField var cpuUsP99 = 0; @JvmField var hitches = 0 }
interface RenderControl {
    fun setClock(c: SongClock)
    fun setPerformance(p: Performance?, profile: InstrumentProfile)
    fun setInstrument(id: InstrumentId)                                 // builds meshes off-thread, swaps on GL
    fun setView(v: ViewId, framing: Int, animate: Boolean = true)
    fun setQuality(q: QualityProfile); fun setSettings(s: RenderSettings)
    fun setStereo(on: Boolean); fun setIdle(idle: Boolean); fun setSyncTest(on: Boolean)
    fun setCalibrationSwatch(level: Int)                                // -1 hides
    fun onResume(); fun onPause(); fun stats(out: RenderStats)
}

// ───────────── instrument models (WP7) ─────────────
package com.tropicalstream.hammerklavier.render.model
class InstrumentAnchors(val keyX: FloatArray /*128, m*/, val keyTopY: Float, val keyFrontZ: Float,
                        val strikeY: Float, val pedalCentre: FloatArray /*3*/) {
    fun camera(view: ViewId, framing: Int, focusKey: Float, out: CameraPose)   // per-instrument table §5.4
}
abstract class InstrumentModel(val profile: InstrumentProfile) : SceneLayer {
    abstract val anchors: InstrumentAnchors
    companion object { fun prepare(id: InstrumentId, look: RenderSettings): InstrumentModel }  // CPU-side build, any thread
}

// ───────────── animation (WP8) ─────────────
package com.tropicalstream.hammerklavier.render.anim
class MechanismPose {
    @JvmField val keyDip = FloatArray(128)      // 0..1 of full dip (front edge)
    @JvmField val hammer = FloatArray(128)      // grand/upright: 0 rest .. 1 at string; harpsichord: jack rise 0..1
    @JvmField val damper = FloatArray(128)      // 0 on string .. 1 fully lifted
    @JvmField val tongue = FloatArray(128)      // harpsichord tongue deflection 0..1; grand: jack escape 0..1
    @JvmField val stringAmp = FloatArray(128)   // visual vibration envelope 0..1
    @JvmField val strikeAge = FloatArray(128)   // seconds since last contact (strike pulse)
    @JvmField var sustain = 0f; @JvmField var soft = 0f; @JvmField var sostenuto = 0f
    @JvmField var shiftMm = 0f                  // una corda keyboard slide
    @JvmField var focusKey = 60f                // smoothed highest-sounding key (Action cut plane)
    @JvmField var centroidKey = 60f             // smoothed centroid (Player follow)
}
class ActionModel(val profile: InstrumentProfile) {
    fun reset()                                                         // on epoch change
    fun evaluate(perf: Performance?, tUs: Long, rate: Float, dt: Float, out: MechanismPose)
}
object PoseArrays { fun pack(src: FloatArray, lowKey: Int, count: Int, dst: FloatArray) }  // key k → dst[k-lowKey]

// ───────────── venue (WP9) ─────────────
package com.tropicalstream.hammerklavier.render.venue
class LightRig { @JvmField val pos = FloatArray(12); @JvmField val rgb = FloatArray(12)
                 @JvmField var flicker = 1f; @JvmField var probeTex = 0 }
class VenueModel(palette: Int) : SceneLayer {
    val lights: LightRig
    fun placeInstrument(id: InstrumentId)                              // grand centre / upright on N wall at 30°
}

// ───────────── library + companion (WP10) ─────────────
package com.tropicalstream.hammerklavier.library
class Source(val id: String, val credit: String, val licence: String, val licenceUrl: String, val sourceUrl: String, val licenceFile: String?)
class Movement(val id: String, val workId: String, val title: String, val asset: String?, val file: java.io.File?,
               val durationSec: Int, val lowKey: Int, val highKey: Int, val hasSustain: Boolean, val sha1: String)
class Work(val id: String, val composer: String, val composerShort: String, val title: String, val shortTitle: String,
           val catalogue: String?, val era: String, val defaultInstrument: InstrumentId, val altInstruments: List<InstrumentId>,
           val movementIds: List<String>, val sourceId: String, val tier: String, val manualOfChannel: IntArray?,
           val velocityPolicy: String /* "as-is" | "flat" */)
class Shelf(val id: String, val title: String, val workIds: List<String>)
class LibraryModel(val shelves: List<Shelf>, val works: Map<String, Work>, val movements: Map<String, Movement>,
                   val sources: Map<String, Source>)
class Catalog(ctx: android.content.Context, imports: ImportStore) {
    fun load(): LibraryModel                                            // loader thread
    fun readBytes(m: Movement): ByteArray
}
class ImportResult(val ok: Boolean, val name: String, val movementId: String?, val reason: String?)
class ImportStore(val dir: java.io.File) {
    fun save(input: java.io.InputStream, originalName: String): ImportResult
    fun saveZip(input: java.io.InputStream): List<ImportResult>
    fun delete(movementId: String): Boolean
    fun scan(): List<Movement>                                          // parses new files once; caches in index.json
}
class Playlist { fun set(ids: List<String>, index: Int); fun current(): String?; fun peekNext(): String?
                 fun next(): String?; fun previous(): String?; val index: Int }
package com.tropicalstream.hammerklavier.companion
interface CompanionCommands {                                          // invoked on main
    fun play(movementId: String); fun toggle(); fun next(); fun previous()
    fun instrument(id: InstrumentId); fun view(v: ViewId); fun importsChanged(); fun nowPlayingJson(): String
}
class CompanionServer(port: Int, token: String, library: () -> LibraryModel, store: ImportStore,
                      commands: CompanionCommands, html: () -> String) : fi.iki.elonen.NanoHTTPD(port) {
    fun url(): String?                                                  // null when no site-local IPv4
}

// ───────────── UX (WP11) ─────────────
package com.tropicalstream.hammerklavier.app
class AppController(activity: android.app.Activity, settings: Settings, audio: AudioEngine, render: RenderControl,
                    kits: KitManager, catalog: Catalog, imports: ImportStore, overlay: OverlayViews,
                    loader: java.util.concurrent.ExecutorService) : CompanionCommands {
    fun onTap(); fun onDoubleTap(); fun onTripleTap(); fun onSwipe(s: Swipe)
    fun onResume(); fun onPause(); fun onStop(); fun onDestroy()
    fun onQuality(level: Int); fun onRoute(r: OutputRoute); fun onFocus(change: Int)
    fun playMovement(id: String, instrument: InstrumentId? = null, startUs: Long = 0L)
    fun debug(extras: android.os.Bundle)
}
```

### 2.4 Data flow

```
 assets/catalog.json ─┐                                ┌──────────── companion (NanoHTTPD) ◄── phone
 Scores/*.mid ────────┴─► Catalog/ImportStore ──main──► AppController ◄── gestures (TrackpadGestureEngine)
                                                        │   ▲      ◄── CONTROL broadcasts, ThermalGovernor
                           HKLoader: bytes ─► SmfParser ─► PerformanceBuilder(profile)
                                                        │   │ Performance (immutable)
                  KitManager ─► KitDecoder ─► pcm cache ─► SampleStore(mmap) ─► LoadedKit
                                                        ▼   ▼
              ┌──────────── CommandRing (SPSC) ──────► HKAudio ───────────────────────────────┐
              │                                    Sequencer ─► VoicePool ─► Voice×N ─► dry/soft buses
              │                                    ResonatorBank, HallReverb, MasterBus ─► AudioTrack.write
              │                                    ClockPublisher (seqlock) ◄─ frames written, getTimestamp
              │                                          │ time only
              ▼                                          ▼
   RenderControl.setPerformance ──► GL thread: clock.sample(now+lead) ─► ActionModel.evaluate(perf, t)
                                     ─► CameraDirector ─► InstrumentModel.update / VenueModel.update
                                     ─► eye 0 draw ─► eye 1 draw        (HUD Views ◄─ main, 1 Hz)
```

The same immutable `Performance` object goes to the audio thread and the GL thread. Neither can be ahead of the other, because both read the score directly and the audio thread owns the clock.

### 2.5 The timing model

**One master clock: output frames.** `F` counts frames rendered and written since the engine started. It is kept by HKAudio and never reset.

**Song time** `S` is in µs of file time at rate 1.0, so it is independent of tempo scaling. While playing, each 256-frame block advances song time by `ΔS = 256 · r · 10⁶ / 48000`, where `r` is the transport rate. While paused, `ΔS = 0`, and silence blocks keep `F` advancing.

**Sample-accurate events inside the callback.** At the start of a block covering `[S₀, S₀ + ΔS)`, the `Sequencer` consumes every event `e` with `evUs[e] < S₀ + ΔS` and gives each one a frame offset `k = ⌊(evUs[e] − S₀) · 48000 / (r · 10⁶)⌋`, clamped to 0–255.
- **Note-on:** allocate a voice with `startDelay = k`. The voice writes zeros for `k` frames, then starts the sample at its trimmed onset. The loop never splits.
- **Key-up** (key released plus damper lag): release noises start at exactly `k`. The damping of the sustain voice starts at the next block boundary, a 5.3 ms granularity, which cannot be heard because damper T60s are 120 ms or longer.
- **Pedals:** read once per block from the slewed `PedalCurve` at `S₀`. A pedal takes 70 ms from end to end, so block-rate sampling is 13 times finer than the mechanism.

**Publishing the clock (seqlock).** After each `write` the audio thread publishes one record. The writer does `seq++` (odd), writes the fields, then `seq++` (even). A reader loops until it reads the same even `seq` before and after reading the fields.

| Field | Meaning |
|---|---|
| `fEnd` | frames written so far |
| `sEnd` | song µs at `fEnd` |
| `r` | rate |
| `playing` | transport state |
| `epoch` | bumps on seek, new performance or instrument swap |
| `pauseS` | song time at which silence begins after a pause |
| `tsFrame`, `tsNanos` | the latest `AudioTrack.getTimestamp`, refreshed by the audio thread every 16 blocks (85 ms) |

**What a reader computes** for an instant `n` (on the `System.nanoTime` base, which is `CLOCK_MONOTONIC`, the same clock `AudioTimestamp` uses):

```
P(n)  = tsFrame + (n − tsNanos) · 48000 / 1e9                 // frame reaching the DAC at n
S(n)  = sEnd − (fEnd − P(n)) · r · 1e6 / 48000                 // song time heard at n
        clamp: if !playing  S(n) = min(S(n), pauseS)           // visuals stop exactly when sound stops
        before the first valid timestamp: P(n) = fEnd − bufferFrames − 480 (estimate), valid=false
```

**What the renderer does with it.**
- The renderer asks for `t_vis = S(now + displayLead)`. `displayLead` defaults to 25 ms, covering the swap, vsync and the waveguide. It is calibrated with the sync test (§8.5) and can be set live with `--ei lead N`.
- `t_vis` goes to `ActionModel.evaluate(perf, t_vis, r, dt, pose)`.
- **Lookahead is built into the evaluation.** For every key, the model finds the governing note: the latest note whose *key-start time*, `onUs − travel(v)·r`, is at most `t_vis`. That note may not have sounded yet.
- A pianissimo note (v20) therefore starts moving 230 ms before its sound. A fortissimo note (v110) starts 20 ms before. The hammer reaches the string at exactly `onUs`, the instant the audio thread starts that sample, and it gets there on the same clock [R:mechanics §0, §1.2].
- Mechanical durations are in real time, so the model converts them to song time with `× r`. At 50% tempo, a key still falls in 86 ms of real time.

**Why this beats a state snapshot.** The audio thread knows nothing about let-off, backcheck or jacks, and it never copies 88×6 floats 187 times a second. The renderer's state is a pure function of `(perf, t)` for keys, hammers, dampers, jacks and pedals. Seeking, pausing, changing tempo and resuming after a display-off `onPause` therefore need no catch-up. Only the string-vibration envelopes are integrated from frame to frame, and they reset when the epoch changes.

**Pause, seek and end of piece.**
- **Pause** ramps the master gain to 0 over 30 ms from the next block and records `pauseS`. The picture keeps moving until the audio already in the 85 ms buffer has been heard, then freezes.
- **Seek** fades all voices over 10 ms, sets `S` and the event cursor (binary search), and increments `epoch`. Notes held across the seek point stay silent until the next onset, the usual player convention. Their keys still show as down, because the key state comes from the analytic model.
- **End of piece:** `EV_END` sits at the last key-up. The engine posts `onEnded` once every voice is below −80 dB or 4 s have passed. `AppController` has already built the next Performance on HKLoader when the current one started, so the gap between movements is only the natural tails plus 1.5 s.

**Display off (sleep button).** `onPause` pauses GL but **keeps the audio playing** until the current movement ends [GUIDE gotcha 5]. `onStop` without playback stops the engine. On resume the picture simply evaluates at the current `t`, so there is nothing to catch up. There is no foreground service (a non-goal). If the process is killed, the resume point is kept every 5 s.

---
## 3. The audio engine

### 3.1 Output stage

The shape is copied from SpyHunt's `AudioEngine` [R:engine_reuse §4.2], with three device-first changes.

**The track:**

```kotlin
AudioTrack.Builder()
  .setAudioAttributes(AudioAttributes.Builder().setUsage(USAGE_MEDIA).setContentType(CONTENT_TYPE_MUSIC).build())
  .setAudioFormat(AudioFormat.Builder().setEncoding(ENCODING_PCM_FLOAT).setSampleRate(48000)
                  .setChannelMask(CHANNEL_OUT_STEREO).build())
  .setTransferMode(MODE_STREAM)
  .setBufferSizeInBytes(max(minBytes, 4096 * 2 * 4))        // 4096 frames = 85.3 ms
  .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_NONE)     // normal mixer, 20 ms period
  .build()
// HKAudio: Process.setThreadPriority(URGENT_AUDIO); play() FIRST, then prime one silent block (SpyHunt rule);
// loop { renderBlock(); write(out, 0, 512, WRITE_BLOCKING) }   // 256 stereo float frames per write
```

**The three changes, and why:**
1. **`PERFORMANCE_MODE_NONE` instead of `LOW_LATENCY`.**
   - Everything is sequenced, so the only latency a user can notice is the tap-to-pause response. That is already 300 ms because of tap disambiguation.
   - The normal mixer drains 960 frames every 20 ms. HKAudio therefore wakes about 50 times a second and renders about 4 blocks per wake, instead of about 250 wakes on the FastMixer.
   - The 85 ms buffer absorbs scheduling jitter when the device is warm.
   - Fallback: if §8 finds glitches on this path, switch to `LOW_LATENCY` with the same 4096-frame request. It is one line.
2. **`USAGE_MEDIA` / `CONTENT_TYPE_MUSIC` instead of `GAME`.**
   - The left temple volume pad and audio-focus ducking then behave like a music player.
   - `onFocus(TRANSIENT_CAN_DUCK)` sets duck to 0.3. A transient loss pauses; a permanent loss pauses and stops the engine.
3. **Block size stays 256 frames** (5.33 ms), which is the event and pedal resolution. Buses are `FloatArray(256)` allocated once.

**Kept from SpyHunt:** `play()` before priming; every voice is killed in `start()` so a stale tail cannot replay; `stop()` joins for at most 350 ms and uses a generation guard.

### 3.2 Sample format: in the APK, in the cache, in RAM

| Stage | Format | Grand | Upright | Harpsichord | Total |
|---|---|---|---|---|---|
| APK asset (`assets/instruments/<id>/…/*.opus`, stored **uncompressed** via `noCompress += "opus"`) | Ogg Opus, 48 kHz stereo, 128 kb/s VBR, `-application audio`. Each region is peak-normalised to −1 dBFS before encoding, and its true level is restored by `gainDb` | 272 files, 24.0 MB | 166 files, 13.9 MB | 108 files, 5.1 MB | **43.0 MB** |
| PCM cache (`filesDir/pcm/<id>.pcm`) | 16-bit little-endian interleaved stereo at 48 kHz; each region aligned to 4 KiB | 1575 s → **288 MiB** | 914 s → 167 MiB | 332 s → 61 MiB | 516 MiB (flash has 19 GB free) |
| RAM | The cache is **mmap'd read-only**. Its pages are clean, file-backed and evictable, never on the Java heap | resident working set 60–150 MiB while playing (est.) | – | – | only the active kit, plus the previous one kept mapped for quick A/B |

Sizes come from [R:sampled-instruments §6] at 128 kb/s, with 6 grand layers, the upright pp layer and no lute stop.

Rejected alternatives:
- **16-bit FLAC in the APK:** about 285 MB.
- **Float PCM in RAM:** 576 MiB for the grand alone. The heap limit is 192 MB and the device is `low_ram=true` [R:sampled-instruments §6].
- **Opus decoded on the fly:** one decoder per voice is impossible, and the Qualcomm FLAC decoder allows only 2 instances at once.

### 3.3 Decoding and caching

**When decoding happens:**
- **Once per install and instrument**, on HKLoader, with one `MediaCodec` alive at a time.
- The decoder is `c2.android.opus.decoder` by name, falling back to `createDecoderByType("audio/opus")`. The decoder list comes from the device check in [R:sampled-instruments §5].
- Input comes from `assets.openFd(path)` (possible because the asset is uncompressed) into `MediaExtractor.setDataSource(fd, off, len)`.

**The cache file, `<id>.pcm`:**
- A 4 KiB header: magic `HKPCM1`, the SHA-1 of `map.json`, the region count, and the region offset table in frames.
- Then each region's PCM, starting on a 4 KiB boundary so that prefetch works on whole pages and no two regions share a page.
- The file is pre-sized with `setLength` so the mapping never changes. Decoded bytes are written through `FileChannel.write(buf, pos)` into the same page cache the mapping reads.

**Progressive, resumable decoding:**
- Layers decode in `decodeOrder`. For the grand that is **v10, then releases and pedals, then v13, v7, v16, v4, v1**.
- After each layer, bits are set in `<id>.ready`, which is fsynced, and `LoadedKit.readyMask` is updated.
- `LayerSelector` never returns a region whose layer is not ready. It falls back to the nearest ready layer and trims the gain to match.
- The kit is **playable after v10 plus releases**: about 330 s of audio, roughly 5 s of decoding (est.).
- If the app is killed mid-decode, it resumes at the first layer not marked ready.

| Instrument | Decode time (est.) | Measured in |
|---|---|---|
| Grand, full | 26 s, from 1575 s of audio at about 60× real time on one core | §8.3 T-DEC |
| Upright | 15 s | §8.3 T-DEC |
| Harpsichord | 6 s | §8.3 T-DEC |

**Opus pre-skip alignment.**
- `map.json` stores each region's exact `frames` and its `onsetFrame`: the attack index after trimming, normally 96, which is a 2 ms pre-roll.
- If the decoder output is exactly 312 frames longer than `frames`, `KitDecoder` drops the first 312. Any other length mismatch is an error that invalidates the cache.
- Voices are scheduled so that `onsetFrame` lands exactly on the MIDI note-on frame.

**Background decoding of the other kits:**
- It runs only while the transport is paused or stopped and the battery is below 37.5 °C.
- It stops within one region (≤ 50 ms) when playback starts.
- If the user picks a kit that is not decoded, they see the voicing pill, and the kit becomes playable as soon as its first layer is done.

**Keeping the audio thread from page-faulting:**
1. **Kit open.** If `ActivityManager.MemoryInfo.availMem` is above 1.5 GiB, HKLoader calls `MappedByteBuffer.load()`, a sequential read of about 0.6 s (est.). Otherwise it touches the first 150 ms of every region, about 7 MiB for the grand.
2. **HKPrefetch, every 50 ms while playing:**
   - For each active voice, it reads the voice's `(region, frame)` cursor from an `AtomicLongArray`, written by the audio thread with `lazySet` and no allocation, and touches the next 300 ms.
   - For every note with `onUs` in `[S, S + 1.5 s]`, it asks the **same deterministic `LayerSelector`** which region will be used and touches its first 400 ms, plus the first 200 ms of its release region.
   - The score says in advance which pages the audio thread will need.
3. **Measurement.** Every bulk read on HKAudio is timed, and reads over 1 ms are counted in `faultsSlow`. The acceptance test T-PF requires 0 in steady state.

### 3.4 Voice structure and the inner loop

```kotlin
class Voice {                                     // 72 preallocated (cap 64 + 8 kill slots)
    @JvmField var state = IDLE                    // PLAYING, FADING, KILL, RELEASE_NOISE, PEDAL_NOISE
    @JvmField var key = 0; @JvmField var region = -1; @JvmField var bus = DRY   // or SOFT
    @JvmField var onUs = 0L; @JvmField var vel = 0
    @JvmField var pos = 0L                        // 32.32 fixed point, frames, relative to window start
    @JvmField var inc = 0L                        // rate * 2^32
    @JvmField var winStart = 0                    // region frame at window[0]
    @JvmField val win = ShortArray(2 * (WIN + 4)) // WIN = 1024 stereo frames, 1 guard before, 3 after
    @JvmField var startDelay = 0                  // frames of silence at block start (sample-accurate onset)
    @JvmField var g = 0f; @JvmField var gTarget = 0f   // block-linear gain ramp (no per-sample exp)
    @JvmField var baseGain = 0f                   // velocity trim × region gain × 1/32768
    @JvmField var damp = 1f                       // multiplicative damper/fade envelope, updated per block
    @JvmField var keyDown = true                  // until EV_KEY_UP
}
```

Each block, for each voice (`VoicePool.render`):
1. **Refill.** If `(pos + 256·rate) ≥ WIN − 3`, re-center the window. This is one bulk `SampleReader.read`, a memcpy of about 4 KiB. `pos` is shifted by the same amount.
2. **Gain target.** `gTarget = baseGain · damp · fadeGain`. The ramp is `dg = (gTarget − g) / (256 − startDelay)`.
3. **Inner loop, Hermite (Q0 and Q1):**

```kotlin
var p = pos; var gg = g
for (i in startDelay until 256) {
    val ip = (p ushr 32).toInt(); val fr = (p and 0xFFFFFFFFL).toFloat() * 2.3283064e-10f
    val b = (ip + 1) shl 1                               // +1: guard frame
    val lm = win[b - 2].toFloat(); val l0 = win[b].toFloat(); val l1 = win[b + 2].toFloat(); val l2 = win[b + 4].toFloat()
    val rm = win[b - 1].toFloat(); val r0 = win[b + 1].toFloat(); val r1 = win[b + 3].toFloat(); val r2 = win[b + 5].toFloat()
    val lc1 = 0.5f * (l1 - lm); val lc2 = lm - 2.5f * l0 + 2f * l1 - 0.5f * l2; val lc3 = 0.5f * (l2 - lm) + 1.5f * (l0 - l1)
    val rc1 = 0.5f * (r1 - rm); val rc2 = rm - 2.5f * r0 + 2f * r1 - 0.5f * r2; val rc3 = 0.5f * (r2 - rm) + 1.5f * (r0 - r1)
    outL[i] += (((lc3 * fr + lc2) * fr + lc1) * fr + l0) * gg
    outR[i] += (((rc3 * fr + rc2) * fr + rc1) * fr + r0) * gg
    gg += dg; p += inc
}
```

Linear interpolation (Q2 and Q3) is the same loop with two taps per channel. A voice whose `inc == 1L shl 32` (an exact sampled pitch) uses a copy loop with no interpolation. At the end of the loop, `startDelay = 0`, and the voice dies when `damp · baseGain < 1e−4` (−80 dB) or its region ends.

### 3.5 Velocity layers

| Kit | Layers and velocity splits | velRef (centre) | Within-layer trim |
|---|---|---|---|
| Grand | v1 1–30, v4 31–46, v7 47–64, v10 65–88, v13 89–112, v16 113–127 [R:sampled-instruments §1.1] | 22, 39, 56, 77, 101, 120 | 0.39 dB per velocity step (0.54 dB/step × 73% veltrack [R:mechanics §1.1]) |
| Upright | pp (VSCO dyn1) 1–40, mf (vl1) 41–83, f (vl2) 84–127 | 28, 62, 105 | 0.39 dB/step |
| Harpsichord | a single layer per stop | – | none. Velocity is ignored for gain; a note-index hash adds ±1 dB of variation |

**No dual-voice crossfade in v1.** Crossfading ±4 velocity steps at 5 grand boundaries would put 31% of notes on two voices. Instead:
1. The pipeline restores each region's **natural recorded level**. `gainDb` undoes the −1 dBFS normalisation, so the loudness steps between layers are the ones the recording actually has.
2. The pipeline measures attack loudness (A-weighted RMS over 0–150 ms) per note and layer. It fails the build if two neighbouring layers at their shared boundary differ from the 0.54 dB/step curve by more than 1.5 dB, and if so applies a per-layer correction of at most ±2 dB.

What remains is a timbre step between layers. At 6 layers it is modest.

`XFADE_STEPS` is kept as an engine constant, default 0. If listening on the glasses (§8.6 L-3) says it is needed, it can be turned on at a known cost of up to +31% voices.

### 3.6 Pitch shifting between sampled notes

**Rate at note-on, once per voice:**

```
rate = 2^(((key − root)·100 + tuning.keyCents(key) − region.tuneCents) / 1200)
inc  = (rate · 2^32).toLong()
```

This uses one `Math.pow` per note-on, never per sample.

**How far voices are shifted:**

| Kit | Sample spacing | Largest shift | Note |
|---|---|---|---|
| Grand | minor thirds | ±1 semitone | – |
| Upright mf/f | whole tones | ±1 semitone | – |
| Upright pp | major thirds | ±2 semitones | – |
| Harpsichord | whole tones | +1 semitone | Typically −1 semitone at A415 on the instrument's A440 recording, so down to −2 semitones plus the temperament. Keys 85–88 shift the c‴ sample up by as much as +4 semitones |

Everything else:
- 4-point Hermite keeps the passband flat to about 10 kHz at these ratios, which is well above the piano's dominant partials.
- Upward shifts at 48 kHz never alias audibly, because piano energy above 12 kHz is tiny.
- `region.tuneCents` comes from the pipeline. For the Salamander it is the `tune_ret` table [R:sampled-instruments §1.1]; for VCSL it is a YIN pitch measurement, because their A = 440 vs 415 standard is unverified.

### 3.7 Envelopes and dampers, by register

**The attack and natural decay are the sample itself.** The engine adds only a damping envelope, updated once per block:

```
d (damping strength, 0..1) =
    0                      if key > lastDamper              // grand > 88 (E6), upright > 90: always free [R:mechanics §4.1]
    0                      if keyDown                       // NOTE_ON … KEY_UP (KEY_UP = finger up + damper lag)
    0                      if sostenuto mask has key
    pedalDamping(p)        otherwise, = 1 − smooth(0.33, 0.55, p)  [R:mechanics §4.4]
damp *= DAMP[key][round(32·d)]            // table, 128 × 33 floats
DAMP[k][j] = exp(−6.91 · (256/48000) · (j/32) / T60d(k))   // effective T60 = T60d / d (log-T60 interpolation)
```

| Kit | Damped T60 `T60d(n)` | Damper lag after note-off |
|---|---|---|
| Grand | `0.12 + 1.2·((88 − n)/67)²` s: C6 0.12, C4 0.33, C2 0.84, A0 1.3 [R:mechanics §9 R3] | 18 ms |
| Upright | the grand value × 1.2 (weaker dampers, est.) | 25 ms |
| Harpsichord | `0.10 + 0.15·((88 − n)/59)` s [R:mechanics §6.2] | 8 ms (the jack falls) |

- The damper lag is added to note-off times when the Performance is built. On the grand, the damper lands at mid key return, 15–20 ms after note-off [R:mechanics §3.3].
- The grand and upright curves are checked in the pipeline against each kit's release samples, and the pipeline log records their fit (§6.2).
- **Re-pedalling (R13) falls out of the model.** If the pedal goes down during damping, `d` drops to 0, and the voice keeps its current level and decays naturally from there.

### 3.8 Pedals: sustain, half-pedal, sostenuto, una corda and the upright soft pedal

| Pedal | Source | Audio effect | Visual (same curve) |
|---|---|---|---|
| Sustain (CC64) | `perf.sustain`: continuous, slewed to 70 ms of full travel in `PerformanceBuilder` | `d` via `pedalDamping(p)`, so **half-pedal is continuous** (R12). The resonance send uses `pedalLift(p) = ((p − 0.33)/0.67).clamp01` | Pedal angle, damper lift across the row (from 1/3 of travel) |
| Sostenuto (CC66), grand | `EV_LATCH` events. The builder latches the keys whose dampers are lifted at the rising edge: keys held down, and every key if the sustain pedal is past 1/3 [R:mechanics §4.2] | Latched keys get `d = 0` until the falling edge | Middle pedal down; latched dampers stay up |
| Una corda (CC67), grand | `perf.soft` | A note-on with `soft ≥ 0.5` goes to the **soft bus**: −2.5 dB plus a high shelf of −4 dB at 2.2 kHz [R:mechanics §4.3]. One shared stereo biquad, not one per voice | The keyboard and action slide 2.5 mm toward the treble over 60 ms |
| Soft pedal (CC67), upright | `perf.soft` | Soft bus: −4 dB, shelf −2 dB at 3 kHz [R:mechanics §5] | The hammer rail moves 22 mm toward the strings |
| Upright middle pedal | ignored | none | static. The practice rail is a non-goal |
| Harpsichord | CC64 turns into **legato hold** in the builder: note-offs are delayed to the next pedal release, capped at 1.5 s and at the next same-key onset [R:repertoire §6]. CC66 and CC67 are ignored | – | no pedals drawn |

### 3.9 Re-strikes, release samples and pedal noises

**Re-strike (R10).**
- On `NOTE_ON` for a key that already has voices, those voices become `FADING` with τ = 60 ms if the pedal is below 0.33, or 200 ms if above. The per-block multipliers are 0.915 and 0.974.
- On the harpsichord, τ = 30 ms, because the damper touches the string as the jack falls.
- At most 3 voices per key. A fourth sends the oldest to a kill slot.

**Release samples (R4).** On `EV_KEY_UP` at offset `k`, a release voice starts only if the damper really lands: `key ≤ lastDamper`, `pedalDamping(p) > 0.5` and the key is not latched.

| Kit | Samples | Release gain |
|---|---|---|
| Grand | `rel<n>` | `relGain · (vel/127)^0.7 · max(0.25, e^(−age/3 s))`, where age = key-up − onUs |
| Upright | releases one whole tone apart, about 1 s long, including the room tail | same formula |
| Harpsichord | jack-fall sample for each active stop, at every key-up | constant |

When the pedal lifts over many ringing notes, the pedal-up noise stands in for the per-note releases, as in the Salamander mapping.

**Pedal noises (R11).**
- When the sustain curve rises through 0.33, a `pedalD` round-robin sample plays. When it falls through 0.30, a `pedalU` sample plays.
- Gain is −20 dB [R:sampled-instruments §1.1] × `clamp(speed/14.3 s⁻¹, 0.35, 1)`, where speed is the curve slope.
- Pedal noises are at least 150 ms apart, so files with pedal flutter don't machine-gun.
- The upright uses its 4 + 4 round robins.

Release and pedal voices share the pool, and they are the first to be stolen after voices already fading.

### 3.10 Sympathetic resonance: 36 string resonators

**What the bank models.** With the dampers raised, the open strings whose *fundamentals* coincide with partials of the played notes ring along. For example, C2 excites the C3, G3 and C4 strings. That is the audible part of R7/R8 [R:mechanics §9].

**The bank:** one two-pole band-pass resonator for each key from 36 to 71 (C2–B4, 65–494 Hz).
- Below C2 the effect is too faint to matter, and the built-in speakers reproduce nothing there anyway.
- Above B4, partial coincidences are sparse.

```
y[n] = a1·y[n−1] + a2·y[n−2] + b·(x[n] − x[n−2]),   R = 10^(−3/(T60·fs)),  T60 = min(freeT60(n), 6 s)
a1 = 2R·cos(2π f_n / fs), a2 = −R², b = (1 − R²)/2,  f_n from TuningTable (retuned with the temperament)
x = 0.5·(dryL + dryR) · send,  send = 0.032 (−30 dB) · pedalLift(p) · (grand 1.0, upright 0.7, harpsichord 0)
output: resonator n panned by key (bass left, as the player hears it), summed into the dry bus
```

- **Cost:** about 7 flops per resonator per frame, so about 250 per frame, or about 1.2% of one core (est.).
- It is off at Q2 and above.
- Coefficient tables are rebuilt on main when the temperament or pitch changes, and handed over through the ring.

### 3.11 The Konzertzimmer reverb: algorithmic FDN

**Convolution is rejected.** Uniformly partitioned FFT convolution of a stereo 1.3 s impulse response is about 2,000 flops per output frame. In ART that is about 30–50% of a core, more than the entire voice budget.

**The FDN:**

| Part | Choice |
|---|---|
| Delay lines | 8 lines of 1109, 1301, 1493, 1693, 1901, 2111, 2333 and 2549 frames (23.1–53.1 ms, all prime). They are sized against the design room's mean free path (4V/S = 5.1 m, 14.7 ms) for the 10.5 × 8.0 × 5.7 m room [R:visual_design §1.3] |
| Mixing | Fast Hadamard, 24 adds and a 1/√8 scale |
| Decay | Per-line gains `10^(−3·dᵢ/(fs·RT60_mid))`, plus an in-loop one-pole low-pass tuned so the high band decays to RT60_high |
| Room values | RT60_mid 1.3 s, RT60_high 0.7 s at 8 kHz. By Sabine this implies an average absorption of 0.156: panelling, damask chairs and an audience (est.) |
| Input | Dry + resonance, summed to mono, 12 ms predelay, spread into the 8 lines with ± signs |
| Output | Lines 0, 2, 4, 6 go to L and lines 1, 3, 5, 7 go to R, with alternating signs |
| Early reflections | 12 taps per listener set: the 6 first-order image sources plus the 6 strongest second-order ones, from the room geometry. Gains are `(1/r)·(1−α)^order`; the ears are offset ±8 cm; each tap has a one-pole low-pass. Three sets: **bench** (Player), **cutaway** (Action), **row 3** (Hall). A view change crossfades the sets over 0.9 s |
| Wet level | Player 0.20, Action 0.16, Hall 0.42, multiplied by the Reverb setting: Dry × 0.4, Room × 1.0, Resonant × 1.4 (with RT60 × 1.25) |
| Quality | Q3 uses 4 lines (4×4 Hadamard) |

**Cost:** about 130 operations per frame, about 0.8% of one core (est.).

### 3.12 Master bus

In order:
1. **Soft bus.** `softBus()`: −2.5 dB plus the high shelf, then added to the dry bus.
2. **Resonator bank.** Its output is added to the dry bus.
3. **Reverb.** `HallReverb` takes the dry bus as input and produces the wet bus.
4. **Sum.** Dry + wet.
5. **Stereo width.** Mid/side with S × w, where w is 1.0 in Player, 0.9 in Action and 0.55 in Hall.
6. **Route voicing:**

   | Route | Voicing |
   |---|---|
   | `SPEAKER` (from `AudioDeviceCallback`) | 2nd-order Butterworth high-pass at 110 Hz; the speakers put out essentially nothing below about 150 Hz [R:engine_reuse §4.2]. Then +3 dB peaking at 250 Hz (Q 0.9) and −1.5 dB high shelf at 7 kHz (est., tuned by ear in §8.6) |
   | `HEADSET` / Bluetooth | flat, with a 20 Hz high-pass |

7. **Gain.** Master −3 dB × duck.
8. **Limiter.** 1 ms (48-frame) look-ahead peak limiter. The sliding-window maximum uses a monotone deque in fixed arrays. Ceiling −1 dBFS; the gain ramps to its target over the look-ahead window and releases with a 120 ms exponential.
9. **Soft clip.** The Padé `softClip` from SpyHunt as the last safety [R:engine_reuse §4.2].
10. **Interleave** into the float output.

### 3.13 Voice cap and stealing

**Cap:** `QualityProfile.voiceCap` counts `PLAYING`, `FADING`, `RELEASE_NOISE` and `PEDAL_NOISE` voices. It is 64 / 48 / 40 / 32 for Q0–Q3. Eight more **kill slots** exist only for 5 ms (one-block) fade-outs, so stealing never clicks.

**Which voice to steal:** when a note-on finds the pool full, the voice with the lowest score goes to a kill slot.

```
score = level_dB + classBonus − 2·age_s
classBonus: FADING −40, RELEASE/PEDAL_NOISE −30, damping (d>0) −20, pedal-held (d=0, !keyDown) −10, keyDown +20
level_dB = 20·log10(baseGain·damp)   // from a 256-entry log table, not Math.log10
```

If both kill slots and candidates run out, the new note replaces the quietest voice with a ramp inside the same block.

**Self-protection, independent of the thermal ladder:** if the block p99 stays above 2.0 ms for 10 s, the cap drops by 8. It is restored after 60 s under 1.0 ms, and each change is logged.

### 3.14 Historical tuning and temperament

`TuningTable.keyCents(k) = 1200·log2(aHz/440) + Temperament.offsetCents(k mod 12)`. The offsets are in cents from equal temperament, normalised so that A = 0 [R:mechanics §10]:

| Temperament | C | C♯ | D | E♭ | E | F | F♯ | G | G♯ | A | B♭ | B |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Equal | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| Werckmeister III | +11.7 | +2.0 | +3.9 | +5.9 | +2.0 | +9.8 | 0.0 | +7.8 | +3.9 | 0 | +7.8 | +3.9 |
| Kellner "Bach" | +8.2 | −1.6 | +2.7 | +2.3 | −2.7 | +6.3 | −3.5 | +5.5 | +0.4 | 0 | +4.3 | −0.8 |
| Vallotti | +5.9 | 0.0 | +2.0 | +3.9 | −2.0 | +7.8 | −2.0 | +3.9 | +2.0 | 0 | +5.9 | −3.9 |
| Young II | +5.9 | −3.9 | +2.0 | 0.0 | −2.0 | +3.9 | −5.9 | +3.9 | −2.0 | 0 | +2.0 | −3.9 |
| Kirnberger III | +10.3 | +0.5 | +3.4 | +4.4 | −3.4 | +8.3 | +0.5 | +6.8 | +2.4 | 0 | +6.4 | −1.5 |
| Lehman 2005 ("one proposal") | +5.9 | +3.9 | +2.0 | +3.9 | −2.0 | +7.8 | +2.0 | +3.9 | +3.9 | 0 | +3.9 | 0.0 |
| ¼-comma meantone | +10.3 | −13.7 | +3.4 | +20.5 | −3.4 | +13.7 | −10.3 | +6.8 | −17.1 | 0 | +17.1 | −6.8 |

**Pitch standards:** A440 (0 cents), A430 (−39.8), A415 (−101.3), A392 (−200.0).

**Defaults:** grand and upright A440 equal; harpsichord A415 Werckmeister III. The temperament and pitch are stored separately for each instrument.

**When a change takes effect:** a change publishes a new immutable `TuningTable` and new resonator coefficients through the ring. **New notes** use it. Sounding notes keep their rate and are never glided. The samples already contain the piano's inharmonicity and stretch, so only the per-pitch-class offsets are applied.

### 3.15 CPU budget: voices × cost per frame

One output frame lasts 20.83 µs at 48 kHz, so the percentage of one core is `ns per frame ÷ 20,833`. Unit costs are ART estimates for an AOT-compiled loop (`cmd package compile -m speed`, §8.1). Test T-CPU replaces them with `BlockStats` measurements.

| Stage | Unit (ns/frame) | Count at Q0 worst case | ns/frame | % of one core |
|---|---|---|---|---|
| Voice, Hermite stereo | 80 | 64 | 5,120 | 24.6 |
| Window refills (bulk copy, amortised) | 3 per voice | 64 | 190 | 0.9 |
| Resonator bank | 7 per resonator | 36 | 250 | 1.2 |
| FDN, 8 lines + 12 early taps | 160 | 1 | 160 | 0.8 |
| Soft bus, width, EQ (2 biquads × 2 ch), limiter, clip | 70 | 1 | 70 | 0.3 |
| Sequencer and per-block voice control (amortised) | 40 | – | 40 | 0.2 |
| **Q0 worst case (64 voices)** | | | **5,830** | **≈ 28%** |
| Typical dense Beethoven (22 voices, est.) | | | 2,300 | ≈ 11% |
| Q2 (40 voices, linear at 45 ns, no resonators) | | | 2,300 | ≈ 11% |
| Q3 (32 voices, linear, 4-line FDN) | | | 1,700 | ≈ 8% |

The block budget is 5.33 ms. Q0 worst case uses about 1.5 ms, which leaves 3.5× headroom against jitter. The acceptance limit is p99 ≤ 1.3 ms and max ≤ 3 ms with the chord-storm stress score (§8.3).

### 3.16 Memory budget

| Item | Size | Kind |
|---|---|---|
| Grand PCM mapping (virtual) | 288 MiB | file-backed, clean, evictable |
| Resident while playing (est.) | 60–150 MiB | page cache, reclaimable |
| Pre-touched attack heads (when `load()` is skipped) | about 7 MiB | page cache |
| Voice windows: 72 × 2,056 shorts | 0.3 MiB | Java heap |
| Buses (12 × 256 floats), limiter, early-reflection sets | < 0.1 MiB | Java heap |
| FDN lines, 8 × up to 2,549 floats, plus 12 ms predelay | 0.09 MiB | Java heap |
| Largest Performance (Hammerklavier iv, about 20k notes × 40 B, est.) | 0.8 MiB | Java heap |
| Next Performance, pre-built | 0.8 MiB | Java heap |
| Catalogue (70 works) plus import index | < 1 MiB | Java heap |
| **Audio + MIDI Java heap** | **≤ 5 MiB** | budget for the whole heap: ≤ 48 MiB |

---
## 4. The MIDI pipeline

### 4.1 SMF parser (`SmfParser`, pure Kotlin, JVM-tested)

**Container**
- Reads `MThd` with any header length of 6 or more (extra bytes skipped).
- **RIFF `RMID`** wrappers are unwrapped.
- Unknown chunk types are skipped.
- A chunk length that runs past the end of the file is truncated to the bytes present, with a warning.

**Formats**
- Formats **0 and 1** are supported.
- Format 2 is merged like format 1, with a warning. It is rare in this repertoire.

**Division**
- PPQ when positive.
- **SMPTE** when negative: −24, −25, −29 (29.97) or −30 frames per second × ticks per frame, which gives a constant µs per tick. Tempo meta events are then ignored.

**Events**
- VLQ deltas are limited to 4 bytes; a longer one is a hard error for that track only, and the track is truncated.
- **Running status** is supported. Per the specification, meta and sysex events cancel it. In **tolerant mode** (the default), a data byte where a status byte was expected reuses the last channel status, with a warning. Many hand-made files depend on this.
- Channel messages 0x8–0xE use the correct data length (1 byte for 0xC and 0xD).
- **Sysex** (0xF0 and 0xF7) is skipped by its VLQ length.
- Stray 0xF8–0xFE bytes are skipped.

**Meta events**
- Kept: 0x51 tempo (µs per quarter note; a value of 0 is ignored), 0x58 time signature, 0x03 track name, 0x02 copyright, 0x01 text (the first 8 are kept for the title guess).
- 0x2F ends the track. A missing end-of-track marker is accepted.

**Channel events kept**
- Note on and off; a note-on with velocity 0 is a note-off.
- CC64, CC66 and CC67.
- CC120 and CC123 (all sound off, all notes off) become note-offs for every open note on that channel.
- **Program changes, CC7, CC11, pitch bend and aftertouch are ignored.** The piano has no volume knob, so hand balance comes from the performer's velocities.
- Channel 10 (index 9, General MIDI drums) is dropped.

**Output**
- `RawTrack` holds parallel primitive arrays, with no event objects.
- The tempo map comes from every track, because some format-1 files put tempo events outside track 0.

### 4.2 Tempo map

- **Tick → µs** is piecewise linear over the tempo segments, with an initial 500,000 µs per quarter note. `tickToUs` uses a binary search over the segments.
- **Bar starts** come from the 0x58 events (default 4/4) and feed the HUD's `bar N` line.
- Tempo scaling at run time is the transport rate, not a rebuild. The Performance stays in file time.

### 4.3 From MIDI to a playable performance (`PerformanceBuilder`)

The build runs once per (file, instrument) pair on HKLoader. It takes ≤ 60 ms for 20k notes (est.; test T-PB).

1. **Merge.** All channel events from all tracks, as absolute ticks, are stably sorted by `(tick, class, track)`. Class order is note-off, then CC, then note-on. Ticks are then converted to µs.
2. **Pedal curves.** For CC64, CC66 and CC67 separately, take the maximum across channels, as a step curve with values 0–127 mapped to 0–1.
   - The curve is **slew-limited to full travel in 70 ms** and becomes piecewise-linear breakpoints (`PedalCurve`).
   - Files that only send 0 and 127 then move like a real foot [R:visual_design §2.7].
   - A pedal the profile does not use (see step 7) becomes an empty curve.
3. **Note pairing and re-strike serialisation.**
   - `(channel, key)` note-offs match their note-ons first-in first-out.
   - All channels drive **one physical keyboard**, except the harpsichord's manuals (step 7).
   - A note-on for a key that is still down ends the previous note at `newOn − 2 ms`, but never less than `prevOn + 20 ms`. The note-off that belonged to it is then consumed silently.
   - Zero-length notes get 30 ms.
   - Notes with no note-off end at the last event + 1 s.
4. **Range.** The keyboard is `profile.lowKey..highKey`: 21–108, or 29–88 for the harpsichord.
   - Notes outside it are **folded by octaves** into range.
   - A folded note that would duplicate a sounding note on the same key within 20 ms is dropped.
   - Folds are counted in `info.warnings`, and the catalogue build flags any bundled file that needs folding (§6.4).
5. **Sostenuto latches.** On each rising edge of CC66 through 0.5, the latch mask is the set of keys whose dampers are lifted at that moment. That means keys between `onUs` and `offUs + damperLag`, plus every key up to `lastDamper` if the sustain pedal is past 0.33. The falling edge sets the mask to 0. The builder emits `EV_LATCH` events whose argument is an index into `latchUs/Lo/Hi`.
6. **Damper lag.** `EV_KEY_UP` is emitted at `offUs + profile.damperLagMs`. The visual key release starts at `offUs` itself.
7. **Instrument policy (`InstrumentProfile`):**

| | Grand | Upright | Harpsichord |
|---|---|---|---|
| CC64 | sustain | sustain | **legato hold**: each note-off is delayed to the next pedal release, capped at 1.5 s and at the next onset on the same key. The pedal curve is then dropped |
| CC66 | sostenuto | ignored | ignored |
| CC67 | una corda | soft pedal (hammer rail) | ignored |
| Velocity | layer + gain | layer + gain | lead time only |
| Manuals | 1 | 1 | 2: `Work.manualOfChannel`, taken from the catalogue (for example Sankey's Goldberg, channel 1 lower and 2 upper). Default is the lower manual |
| lastDamper | 88 | 90 | 127 (every key damped) |
| damperLagMs / keyReturnMs | 18 / 35 | 25 / 50 | 8 / 30 |

8. **Event stream.**
   - `EV_NOTE_ON` goes at `onUs`, `EV_KEY_UP` at key-up + lag, `EV_LATCH` at latch changes, and `EV_END` at the last key-up.
   - The stream is sorted by time. At equal times, key-ups come before note-ons so a re-strike releases first.
   - Harpsichord notes carry their manual, and the audio engine starts one voice per active stop.
9. **Indexes.** Build the per-key CSR arrays (`keyFirst`, `keyNotes`), the `barUs` bar-start times, and `PerfInfo`, which records the note range, pedal counts, maximum polyphony and warnings.

A Performance never changes after it is built. Switching instrument builds a new one from the cached `RawSmf`, which takes milliseconds, and resumes at the same `songUs`.

### 4.4 Files written for another instrument

| Case | Handling |
|---|---|
| Piano file (for example Krueger's BWV 846, with CC64) on the harpsichord | Legato hold replaces the pedal. Out-of-range notes are folded. No pedal is drawn |
| Harpsichord file (Sankey, no pedal) on the grand | Played as written, with no pedal. Velocity is honoured, even if Sankey's key velocity turns out to be constant (unverified; §6.4 measures it). If the catalogue's `velocityPolicy` says `"flat"`, the builder maps a constant velocity to 72 on the piano |
| Two-manual file on a piano | Manuals are merged onto one keyboard, and same-key collisions are serialised (step 3) |
| Organ-texture or pedal-board parts | Out of range for the harpsichord, so they are folded. BWV 565 is not in the catalogue [R:repertoire §5] |
| Imported file with channel 10 drums | Drums dropped |

### 4.5 Transport

| Command | Mechanism |
|---|---|
| Play / pause | Ring command. Pause ramps to zero in 30 ms; the clock freezes at `pauseS` (§2.5) |
| Seek (Position adjust, companion, CONTROL `--el seek`) | Ring command with the target µs. Voices fade out over 10 ms, the cursor is found by binary search, `epoch++`, and the renderer re-seeds its per-key cursors |
| Next / previous | `Playlist`. Previous restarts the movement if more than 3 s in. The next movement's Performance is **pre-built** when the current one starts |
| Tempo | `setRate(r)`, 0.5–1.5. Only the song clock changes, not pitch. Mechanical animation times are scaled so they stay real-time (§2.5) |
| Instrument switch | Pause, build the new Performance, open the kit (instant if cached; otherwise it becomes playable after the first layer), rebuild the renderer meshes (FloatArrays built off-thread, uploaded in one GL frame), then seek to the same `songUs` and resume |
| Auto-advance | `onEnded` leads to `Playlist.next()` after the tails have died out (below −80 dB, or at most 4 s) |

### 4.6 The library catalogue (`assets/catalog.json`, built by the pipeline)

```json
{
  "version": 1,
  "shelves": [
    { "id": "start", "title": "Start here", "works": ["bach.bwv846.krueger", "bach.bwv772-786", "…"] },
    { "id": "beethoven", "title": "Beethoven", "works": ["beethoven.op13", "beethoven.op106", "…"] }
  ],
  "sources": {
    "krueger": { "credit": "Performance: Bernd Krueger · piano-midi.de · CC BY-SA 3.0 DE",
                 "licence": "CC-BY-SA-3.0-DE", "licenceUrl": "https://creativecommons.org/licenses/by-sa/3.0/de/",
                 "sourceUrl": "http://www.piano-midi.de", "licenceFile": "licenses/CC-BY-SA-3.0-DE.txt",
                 "unmodified": true, "exportAllowed": false },
    "sankey":  { "credit": "Harpsichord: John Sankey · johnsankey.ca/harpsichord.html · free-copy notice",
                 "licence": "SANKEY", "licenceFile": "licenses/SANKEY.txt", "unmodified": true, "exportAllowed": false }
  },
  "works": [
    { "id": "beethoven.op106", "composer": "Ludwig van Beethoven", "composerShort": "Beethoven",
      "title": "Piano Sonata No. 29 in B-flat major, op. 106 “Hammerklavier”",
      "shortTitle": "Sonata op. 106 “Hammerklavier”", "catalogue": "op. 106", "year": 1818, "era": "classical",
      "defaultInstrument": "grand", "altInstruments": ["upright"], "source": "krueger", "tier": "A",
      "performanceType": "step-sequenced", "velocityPolicy": "as-is", "manualOfChannel": null,
      "movements": [
        { "id": "beethoven.op106.1", "title": "I. Allegro",
          "asset": "midi/krueger/beethoven/beethoven_hammerklavier_1.mid",
          "sha1": "QK4QBBMFW4SWQETC4NJ5SG2ZBTZPBFXK", "bytes": 74228,
          "durationSec": 593, "lowKey": 22, "highKey": 105, "notes": 9123,
          "hasSustain": true, "hasSoft": false, "hasSostenuto": false, "folds": { "harpsichord": 412 } }
      ] }
  ]
}
```

- **Written by hand:** shelves, titles, composers, default instruments, sources and `manualOfChannel`. These live in `tools/pipeline/catalog_src.json`.
- **Measured by the pipeline:** `sha1`, `bytes`, `durationSec`, `lowKey`/`highKey`, `notes`, the `has*` flags and `folds`. They are never typed by hand [R:repertoire §6].
- **Bundled MIDI stays byte-identical:**
  - Sankey requires it.
  - For Krueger it keeps the collection a *Sammelwerk* (collection) rather than an adaptation [R:repertoire §3].
  - The files are parsed at run time.
- **Imported files** are indexed in `files/Scores/index.json`, which has the same movement fields plus `addedAt`, `originalName`, `folder` and `lastInstrument`.
  - Their source is `"user"`: no credit line, and they are never uploaded anywhere.
  - Work ids are `user.<folder-or-file-slug>`; movement ids are `user.<sha1-10>`.

---

## 5. Rendering

### 5.1 Context, EGL config and pacing

**Context and configuration**
- `setEGLContextClientVersion(2)`, the proven low-heat path [R:engine_reuse §2.1]. It gives an ES 3.2 context, but only ES 2.0 and GLSL ES 1.00 are used.
- The config chooser tries RGB888, 24-bit depth, 4× MSAA first. It falls back to RGB888 with 24-bit depth, then to the default. The config chosen is logged as `HKRender cfg=…`.
- MSAA is used only at Q0.
- No stencil is requested (mirrors are culled on the CPU, §5.2).
- `preserveEGLContextOnPause = true`.

**Clear colour and state**
- The clear colour is **pure black (0, 0, 0)**, which is transparent on the waveguide.
- Depth test, back-face culling and blending are on.
- `GL_MAX_VERTEX_UNIFORM_VECTORS` is logged at start-up. The engine needs 64 or more; it asserts 128.

**Pacing** follows the MathCosmos Choreographer pattern [R:engine_reuse §2.2]: `requestRender()` on every Nth vsync.

| State | Divider | fps |
|---|---|---|
| Playing or camera moving, Q0–Q1 | 2 | 30 |
| Q2 | 3 | 20 |
| Q3 | 4 | 15 |
| **Idle**: paused, no glide, and gaze moving less than 0.2° per frame | 6 | 10 (flames still flicker) |

- `removeFrameCallback` is called before `postFrameCallback` in `onResume`, so two loops can never stack (WanderQuest).
- `dt` is clamped to 0–0.05 s.
- A `FRAME HITCH` is logged when the raw `dt` exceeds 120 ms.

**Frame order in `onDrawFrame`**, simulated once and drawn twice:
1. `clock.sample(now + lead)`.
2. On an epoch change: `actionModel.reset()` and re-seed the cursors.
3. `actionModel.evaluate(...)`.
4. `cameraDirector.update(...)`, applying the gaze.
5. `venue.update(f)` and `instrument.update(f)`, which pack the uniforms.
6. For each eye: `stereoRig.eye(...)`, then `venue.draw` and `instrument.draw`, then overlays in 3D (GlyphBoard).

### 5.2 Scene structure and the draw list

There is no general scene graph. The scene is **two `SceneLayer`s**, venue and instrument, each drawing a fixed list of batched draws. Every mesh is a static VBO uploaded once. Every moving part is skinned in the vertex shader from uniform arrays (§5.6).

| # | Draw (per eye) | Program | Shown in | Triangles (est.) |
|---|---|---|---|---|
| 1 | Room shell, floor and parquet pool (vertex colours baked from about 50 flames) | lit + decal | all levels ≥ Stage (distance-faded) | 6k |
| 2 | Gilt ribbons: trellis, frames, cornice, web | ribbon (screen-space antialiased, 1.5–2 px, emissive floor) | Salon full; Stage within 3.5–6 m | 3k |
| 3 | Cove cartouches and crests (atlas decals) | decal | Salon | 1k |
| 4 | Chairs, 3 rows × 6 | lit | Hall | 5k |
| 5 | Chandelier arms and sconces | ribbon | Hall; Stage (the sconces behind the instrument) | 1.3k |
| 6 | Flames and halos, about 50 | sprite (additive) | all | 0.2k |
| 7 | Reflected flames: mirror images (CPU culled) and floor images at 25% | sprite | Q0 Salon/Stage | 0.6k |
| 8 | Crystal sparkle, up to 160 | sprite | Hall, Q0/Q1 | 0.3k |
| 9 | Case, legs, lyre or stand | lacquer (rim, probe, floor, Blinn ×4 lights) | all | 3.6k |
| 10 | Lid, with its own transform (lifts off in Overhead) | lacquer | all | 0.6k |
| 11 | Plate, soundboard, bridges, pins | lit | Action / Hall | 2k |
| 12 | Keys, one skinned draw for all | skinned-rotate + bevel | all | 2.2k (harpsichord 2 × 1.5k) |
| 13 | Hammers (harpsichord: jacks and tongues) | skinned-rotate | Action, Overhead, Hall | 3.5k |
| 14 | Dampers | skinned-translate | Action, Overhead | 1.7k |
| 15 | Strings: steel and wound in one draw, colour per vertex | spindle | Action, Overhead, Hall | 7.3k |
| 16 | Pedals (grand/upright) or register levers | skinned-rotate | Player, Hall | 0.4k |
| 17 | Action set: 13 slots of key lever, capstan, wippen, jack, repetition lever, knuckle, backcheck, damper underlever | skinned-multi | Action cutaway | 4.8k |
| 18 | Section caps where the cut plane meets the case | section-cap | Action cutaway | 0.2k |
| 19 | Strike pulses, one sprite per recent strike | sprite | Action, Overhead | 0.1k |
| 20 | Feature-edge overlay | ribbon | Passthrough, or a toggle | 1k |
| 21 | GlyphBoard labels (≤ 3): note name in the cutaway, title card on the music desk | glyph | per view | – |
| 22–23 | Pedal inset (Player follow framing): pedals + floor pool redrawn with scissor and viewport | lacquer + skinned | Player follow | 0.8k |

**Budgets per eye, by view**

| View | Draws | Triangles |
|---|---|---|
| Player | 16 | ≈ 22k |
| Player follow | 18 | ≈ 23k |
| Action cutaway | 18 | ≈ 31k |
| Action overhead | 16 | ≈ 27k |
| Hall | 21 | ≈ 37k |
| **Budget** | **≤ 28** | **≤ 45k** |

**GL-thread CPU:** about 3 ms per frame (est.): about 0.1 ms for `ActionModel`, about 40 µs of uniform packing, and about 2.5 ms of driver time for about 40 draws. That is 9% of one core at 30 fps; the limit is 6 ms.

**Mirror reflections without a stencil**
- For each of the 5 mirror planes, every flame is reflected through the plane.
- A reflected flame is emitted only if the segment from the eye to its virtual image crosses the mirror rectangle. That is about 250 point-in-rectangle tests per frame.
- The sprites are small, so clipping them at the frame edge does not matter.

### 5.3 Procedural meshes

Everything is built from code: **zero image assets, zero licensing risk**, and nothing added to the APK. `MeshBuilder` writes into preallocated FloatArrays on HKLoader, and the GL thread only uploads them.

**Vertex format:** position (3), normal (3), uv (2), colour (4), slot (1), lane select as a one-hot vec4 (4). That is 17 floats, 68 bytes. Unskinned meshes use a 12-float subset.

| Instrument | Meshes (sizes from [R:visual_design §2]) |
|---|---|
| Keyboard, all instruments | **Keys:** equal 13.71 mm slots at the back (octave ÷ 12, with a 164.5 mm octave; the harpsichord's octave is 159 mm). Natural heads 23.5 mm wide at the front, with tails cut around the sharps (C/F cut right, E/B left, D/G/A both sides). Sharps have tapered tops. Bevels are **analytic, in key-local UV** (a smoothstep over 0.8 mm) instead of geometric gaps [§2.1].<br>**Pivot:** each key rotates about its balance line, with a maximum of 2.2° [R:mechanics §2]. |
| Grand (C5, 200 × 149 × 101 cm) | **Case:** rim from the research's Catmull-Rom plan points, extruded 0.30 m. One-piece lid hinged on the spine, on a 38° stick. Three tapered legs, the lyre, three brass pedals.<br>**Frame:** the plate is a gold slab outlined by ribbons around its lightening holes. Dim soundboard, bass bridge overstrung at 18°.<br>**Strings:** 228 (8 single, 40 bichord, 180 trichord).<br>**Action:** 88 hammers (heads 50 → 30 mm tall, 133 mm strike radius), 70 dampers (keys 21–88: wooden head over the felt, wire), and the 13-slot action template. |
| Upright (U3, 131 × 153 × 65 cm) | Case with its top lid, fallboard and knee board. The upper front panel is removed in Overhead.<br>Vertical overstrung strings. Hammers throw horizontally (47 mm blow, 3.2 mm let-off, 16 mm checking) [R:mechanics §5]. Dampers sit just above the strike line. Hammer rail. Three pedals. |
| Harpsichord (Blanchet double, 232 × 91 cm) | Bentside case in green and gold. The lid landscape is a procedural pastel gradient, dimmed to 60%. Cabriole stand.<br>**Two manuals:** 60 keys each, the upper 6.5 cm higher and 8.5 cm further back. Ebony naturals, bone sharps, arcaded key fronts.<br>**Jacks:** 3 rows × 60 = 180, each with its tongue and plectrum. The front 8′ row is disengaged (slid off), so its quills pass the strings silently.<br>**Strings:** 2 × 60 at 8′ and 60 at 4′. The soundboard is painted with flowers (a Canvas texture) and a gilt rose. |

### 5.4 Views and cameras

Positions and targets are in metres, in the piano frame: x toward the treble, y up, z toward the player. The origin is on the floor under the key fronts. The table comes from [R:visual_design §4.6].

| Instrument | View / framing | Position | Target | Vertical FOV | IPD scale | Zero parallax |
|---|---|---|---|---|---|---|
| Grand / upright | Player | (−0.10, 1.30, 1.55) | (0, 0.50, −0.12) | 34° | 0.6 | 1.75 m |
| Grand / upright | Player follow | (x_c, 1.15, 0.62) | (x_c, 0.70, −0.08) | 30° | 0.5 | 0.95 m |
| Harpsichord | Player | (−0.06, 1.22, 1.05) | (0, 0.62, −0.10) | 34° | 0.6 | 1.25 m |
| Grand | Action cutaway | (x_cut + 0.95, 0.95, 0.30) | (x_cut, 0.76, −0.24) | 22° | 0.35 | 1.1 m |
| Upright | Action cutaway | (x_cut + 1.05, 1.00, 0.10) | (x_cut, 0.93, −0.20) | 26° | 0.35 | 1.1 m |
| Harpsichord | Action cutaway | (x_cut + 0.60, 0.93, −0.02) | (x_cut, 0.86, −0.42) | 24° | 0.3 | 0.73 m |
| Grand | Action overhead (lid off) | (0, 1.95, 0.55) | (0, 0.84, −0.90) | 44° | 0.5 | 1.8 m |
| Upright | Action overhead (panel off) | (1.05, 1.40, 0.75) | (0, 1.06, −0.28) | 36° | 0.5 | 1.5 m |
| Harpsichord | Action overhead (lid and jack rail off) | (0, 1.85, 0.45) | (0, 0.80, −0.95) | 44° | 0.5 | 1.8 m |
| All (room frame) | Hall wide | (0.4, 1.20, 3.0) | (0, 1.95, −1.9) | 40° | 1.0 | 4.9 m |
| All (room frame) | Hall life-size | (0.4, 1.20, 3.0) | (0, 1.05, −1.9) | 18.3° (tunable) | 1.0 | 4.9 m |

**Tracking the music**
- `x_cut`, the cut plane, follows `pose.focusKey`: the highest sounding key, with a dead band of ±2 semitones and a critically damped spring (ω = 4 rad/s).
- `x_c`, the follow centre, tracks `pose.centroidKey` with ω = 6 rad/s, capped at 0.6 m/s [R:visual_design §4.1].
- Only actions within ±6 notes of `x_cut` are drawn (13 slots); the inactive ones are dimmed to 40%.

**Stereo rig**
- **Parallel eyes with an off-axis frustum**, not toe-in: the SpyHunt rig [R:engine_reuse §2.3]. IPD is 63 mm × the view's scale × Settings stereo depth. The frustum shift is `eyeShift · near / zeroParallax`.
- Frame axes: `right = forward × worldUp`, then `up = right × forward`. Keep that order, or the look-around comes out mirrored (MathCosmos's sign warning).

**Head look-around**
- **Gaze:** `GazeCamera` copied verbatim. In the Hall it is world-locked (±60° yaw, +45° pitch). Elsewhere it gives ±5° of parallax (look direction × 0.15, clamped).
- **Recentre:** a triple-tap recentres. The one-minute soft re-centre stays as it is.

**Transitions**
- A view change glides position and target over 0.9 s with smootherstep.
- The FOV is fixed per view. It is blended only when the two views differ by more than 10°, as Player (34°) → Action (22°) does.
- Player → Action sweeps the clip plane in from the treble. Action → Overhead lifts the lid off (`lidLift` 0 → 1). Leaving the Hall lowers the lid back onto its stick.

### 5.5 Animation models (`ActionModel`, pure Kotlin, JVM-tested)

All times are in song µs, and mechanical durations are converted with `× r`. The per-key cursor walks forward during playback and is re-seeded by binary search when the epoch changes.

For each key, the **governing note** is the latest note j with `tStart_j ≤ t`. Note j+1 is checked too, because a soft re-strike can start travelling before a loud one has lifted. Each evaluation is O(1).

**Grand keys and hammers**

```
HV = hammerVelocity(v) = 10^((v−57.96)/71.3) clamp [0.25, 7] m/s          [R:mechanics §1.1]
s  = smooth(2.0, 4.5, HV)   tt = keyTravelMs(v)·travelScale   tb = keyBottomRelMs(v)   ff = freeFlightMs(v)
tStart = on − tt·r ; tBottom = on + tb·r ; tEscape = on − ff·r
key press, u = (t − tStart)/(tBottom − tStart):
    pressed(u) = u^1.8 ; struck(u) = 0.33·u/0.25 (u<0.25), 0.33 (u<0.40), 0.33 + 0.67·(u−0.40)/0.60
    dip = d0 + (1 − d0)·((1−s)·pressed + s·struck)          // d0 = position at tStart (re-strike from part-way up)
key held: dip = 1 ; release from offUs: dip = dHeld·(1 − easeOut((t − off)/(keyReturnMs·r)))   // 35 ms grand
hammer h (fraction of 47 mm blow):
    rising:   h = min(actionRatio·dip·keyDipMm, blow − letOff)/blow     until tEscape  (ratio ≈ 5.0, let-off 1.5 mm)
    free:     linear to 1.0 at on
    contact:  1.0 for contactMs(n) = 4·0.2^((n−21)/87) ms
    rebound:  falls at 0.45·HV to (blow − check)/blow = 0.68 (checked by the backcheck) while the key is down
    release:  h = min(0.68, actionRatio·dip·keyDipMm/blow) → rest ; tongue[] (jack escape) = 1 between tEscape and key half-return
damper (keys ≤ lastDamper): lift = max(((dip − 0.5)/0.5).clamp01, pedalLift(sustain(t)), latched ? 1 : 0)
                             → drawn as 0..6 mm (≈ 6 px in the cutaway [R:visual_design §4.2])
pedals: angle = 5°·p (sustain, sostenuto, una corda) ; shiftMm = 2.5·smooth(0,1, soft(t)) over the 60 ms slew
```

| Velocity | Key starts before sound | Key reaches the bed |
|---|---|---|
| 20 | 230 ms | +18.6 ms after |
| 64 | 86 ms | +5.4 ms after |
| 110 | 20 ms | 1.9 ms before |

At 30 fps a fortissimo key reaches the bed within one frame [R:mechanics §1.2]. Because each frame evaluates the state from the formulas, that looks correct and never tweens past the target.

**Upright**
- `travelScale` 1.05. Hammers throw horizontally, with checking at 16 mm and a key return of 50 ms.
- **Soft pedal:** the hammer rest position becomes `soft·22/47` of the blow, and the throw is scaled to the rest. The hammer rail is drawn moving with it.

**Harpsichord**
- Lead time is `tt = (40 − 25·v/127)` ms, which is 15–40 ms [R:mechanics §6.3].
- Key dip is 7 mm. The key moves linearly and reaches the **pluck fraction** at `onUs`: 0.60 for 8′ and 0.37 for 4′ [§6.1]. It reaches the bed at the same rate afterwards.
- **Jack rise** = 1.25 × key travel, up to the jack rail. It is stored in `hammer[]`.
- **Return:** on key-up the jack falls with the key (30 ms return). As the quill passes back under the string, the **tongue deflects** (`tongue[]` 0 → 1 → 0 over 25 ms).
- The damper felt rides on the jack, so `damper = clamp(jackRiseMm / 1.5 mm)`.

**Strings**
- These are the only integrated state, and they reset when the epoch changes.
- **Strike:** at contact, `a1 = 0.8·A0` and `a2 = 0.2·A0`, where `A0 = (HV/7)^0.6` (harpsichord: 0.7). `strikeAge` is set to 0.
- **Natural decay, each frame:** `a1 *= e^(−3dt/τ)` and `a2 *= e^(−dt/τ)`, with `τ = freeT60(n)/6.91`.
- **Damped:** both are also multiplied by `e^(−6.91·dt·d/T60d(n))`, where `d` is the same damping strength the audio engine uses, from the same pose.
- **What is drawn:** `stringAmp = a1 + a2`, shown as a spindle whose half-width is `A·g·sin(πx/L)` [R:mechanics §8.4]. The visual gain is ×3 in the cutaway and ×5 overhead, because real amplitudes are under a pixel.
- **Strike pulse:** a bright pulse runs outward from the strike point for 150 ms.

### 5.6 Batching and uniform budget

The skinning approach is from [R:visual_design §5.3].
- Each moving-part mesh is one VBO.
- Every vertex carries `slot = partIndex/4` and a one-hot `sel` vec4.
- The vertex shader reads `dot(uState[int(slot)], sel)`. This avoids dynamic component indexing, which ES 2.0 does not guarantee.
- `PoseArrays.pack` maps the 128-key pose arrays into these lanes.

| Uniform array | Size | Used by |
|---|---|---|
| `uKey[22]` | vec4 × 22 | keys (88), harpsichord 2 × `uKey[15]` |
| `uHam[22]` | vec4 × 22 | hammers / jacks |
| `uDamp[22]` | vec4 × 22 | dampers |
| `uAmp[22]`, `uAge[22]` | vec4 × 44 | strings, strike pulses |
| `uAct[26]` | vec4 × 26 | 13 action slots × (key angle, wippen, jack tilt, hammer) plus (repetition lever, backcheck, damper lift, spare) |
| `uPed`, `uShift`, `uClip`, lights, matrices | about 16 | everything |

- **Per frame:** at most 8 `glUniform4fv` calls, about 2.4 KB. They are uploaded once per frame (uniforms persist per program) and drawn twice.
- **Per shader:** the largest uses about 60 vec4s, well under the ES 2.0 guarantee of 128.
- **Allocation:** nothing is allocated per frame. `glBufferData` is never called in the steady state.

### 5.7 Text and overlays

- **2D, in `BinocularSbsLayout`:** the HUD, menus, title card and calibration card (§1.7). They are `LAYER_TYPE_HARDWARE` Views, invalidated only on change and at most 1 Hz. The one exception is the menu responding to input.
- **3D, in GlyphBoard, copied from MathCosmos** [R:engine_reuse §2.6]:
  - the note name beside the cutaway hammer (for example `C4`), about 16 px tall at that depth;
  - the work title on a "sheet" lying on the music desk in the Player view (`drawOriented`).

  The label cap is lowered from 96 to **32**, which is at most about 8 MB of textures. There are at most 3 new labels per frame.
- **Baked into the case, not text:** the fallboard lettering "HAMMERKLAVIER" is drawn into the procedural atlas. No maker's trademark appears anywhere.

### 5.8 Waveguide colour strategy

The palette is the research's single source of truth [R:visual_design §3.5], held in one `Palette.kt` object owned by WP9 and used by WP7.

1. **Black instruments are drawn as reflectors.**
   - A **presence floor** `rgb = max(lit, uFloor)`, default (22,18,15). It is calibrated with the swatch card and applied only to instrument surfaces.
   - A Fresnel rim of (120,78,40) × 0.8.
   - Candle Blinn highlights from 4 dynamic lights (exponent 96).
   - A **baked 128×64 light-probe reflection**, so candle streaks slide over the lacquer as the head moves.
   - The walnut default for the upright.
2. **Surfaces:**
   - Textures and vertex colours are lifted with `pow(c, 0.85)` [R:engine_reuse §2.5].
   - No surface wider than about 40 px goes above 220.
   - 255 is reserved for flame cores, sparkles and specular pinpoints.
   - The ivory naturals are capped at (232,214,178).
3. **The room is sparse light.** White wall fields are **not drawn**; the wearer's real room shows through. What is drawn: gilt ribbons with an emissive floor of (48,32,13), flames and their reflections, floor pools, and chair silhouettes. **Shadows are holes** that read only because a lit floor pool always surrounds them.
4. **Venue levels** [R:visual_design §3.3]:

   | Level | APL | Used for |
   |---|---|---|
   | Salon | ≤ 12% | Hall |
   | Stage | ≤ 9% | Player, Action: instrument, floor pool, candelabra and the mirror bay behind, faded out over 3.5–6 m |
   | Instrument | ≤ 6% | – |
   | Passthrough | ≤ 5% | daylight, with the edge overlay on |

   Auto picks the level by view. The thermal cap overrides it (§5.9), and the user can override both.
5. **Palettes:** "Sanssouci 1747" (default, white and gold) and "Stadtschloss 1747" (celadon wall glow near the flames). The geometry is the same; only the colours change.

### 5.9 Thermal governor and the quality ladder

The governor is the MathCosmos code, extended to four levels [R:engine_reuse §2.4]. Its inputs:
- battery temperature from the sticky `ACTION_BATTERY_CHANGED` intent;
- the `PowerManager` thermal status listener;
- both registered in `onResume` inside `runCatching`, and removed in `onPause`.

**One ladder drives both the renderer and the audio engine.**

| Level | Trigger (battery; hysteresis −1.5 °C) or status | fps | Room cap | Mirror flames | Crystals | MSAA | Voices | Interp. | Resonators | FDN lines |
|---|---|---|---|---|---|---|---|---|---|---|
| **Q0** | < 39.0 °C | 30 | Salon | on | 160 | if available | 64 | Hermite | on | 8 |
| **Q1** | ≥ 39.0 (relax < 37.5) or MODERATE | 30 | Salon | off | 60 | off | 48 | Hermite | on | 8 |
| **Q2** | ≥ 42.0 (relax < 40.5) or SEVERE | 20 | Stage | off | 0 | off | 40 | linear | off | 8 |
| **Q3** | ≥ 44.0 (relax < 42.5) or CRITICAL | 15 | Instrument | off | 0 | off | 32 | linear | off | 4 |

- **Frame rate is protected longest.** Keys move fast, and dropping to 20 fps makes a forte key read as a jump. The ladder therefore sheds scene decoration first: reflections, crystals and MSAA.
- The idle pacing at 10 fps (§5.1) applies at every level.
- Transitions are logged as `HKThermal status=… battery=…C -> Q2`.
- At Q2 and above the HUD shows the `▲ warm` pill.
- `--ei quality N` forces a level; `-1` returns to automatic.

---
## 6. The asset pipeline (Python on the Mac)

### 6.1 Layout and environment

```
tools/pipeline/                 (WP12)
  requirements.txt              numpy, scipy, soundfile, mido, jsonschema, pytest
  manifests/samples.tsv         research manifest, pruned to: grand-* required, upright-* required + upright-sustain-pp,
                                harpsichord-8ft/4ft (+releases), vcsl docs/sfz maps         (lute and harm groups dropped)
  manifests/midi.tsv            repertoire §8 rows (Krueger primary + Wayback + SHA-1 b32, Sankey zips, Commons, Mutopia)
  fetch.py                      one file at a time, ≥ 2 s apart, resumable, size/SHA check, Wayback fallback, UA header
  kitlib.py                     decode → trim → measure → normalise → encode → map.json (shared)
  kit_grand.py  kit_upright.py  kit_harpsichord.py        per-instrument rules (offsets, splits, tail targets)
  midi_extract.py               copy chosen entries out of Sankey zips byte-identically; verify SHA-1
  midi_validate.py              parse + stats → build/midi_stats.json; range/fold/pedal/velocity reports
  catalog_src.json              hand-curated shelves/works/titles/defaults/sources/manualOfChannel
  build_catalog.py              catalog_src + midi_stats → app/src/main/assets/catalog.json
  build_credits.py              LEDGER.csv → assets/licenses/*, assets/credits.txt, CREDITS.md
  check_apk_budget.py           fails if assets exceed the §6.6 budget or any asset lacks a ledger row
  tests/                        pytest: onset, YIN, opus round-trip, schema, ledger completeness
downloads/  build/              git-ignored
```

- **Environment:** `python3 -m venv tools/pipeline/.venv && tools/pipeline/.venv/bin/pip install -r tools/pipeline/requirements.txt`.
- **ffmpeg** comes from the Mac's existing install, which has libopus [R:sampled-instruments §5]. Nothing else needs installing.
- **Output** is deterministic: the same inputs give byte-identical kit files.
- **Git LFS:** `*.opus` under `app/src/main/assets/instruments/**`, the same practice as the MathCosmos clips. APKs are never committed.

**Download gate.** Nothing is fetched until the user approves the manifests. WP12's first action is to put two lists in front of the user:
- samples: 557 required files, 786.0 MiB, plus the 23-file upright pp layer (65.3 MB);
- MIDI: about 4.8 MB.

Two downloads the pipeline does not fetch itself:
- **IMSLP files** sit behind a terms click. The user downloads HWV 430 and *La Poule* and drops them into `downloads/imslp/` [R:repertoire §1.2].
- **Couperin's *Barricades*** stays out unless David Madore grants a licence.

### 6.2 Samples: fetch, trim, normalise, encode, map

For every region, `kitlib.py` does:

1. **Decode** with `ffmpeg -i in.{flac,wav} -f f32le -ac 2 -ar 48000 -af aresample=resampler=soxr:precision=28`. The VCSL rate and bit depth are unverified, so everything goes to 48 kHz [R:sampled-instruments §2.1].
2. **Trim the start.**
   - Salamander uses the per-file `$OFF` offsets from `Data/vel_NN.txt`, which removes 15–36 ms of dead air [§1.1].
   - VCSL uses onset detection: the first sample above −50 dB relative to the peak in the first 200 ms.
   - Both keep a **96-frame (2 ms) pre-roll**, recorded as `onsetFrame = 96`.
3. **Trim the tail.**

   | Kind | Target length |
   |---|---|
   | Grand sustain | 14 s at A0, falling linearly to 3 s at C8 |
   | Upright sustain | 12 s → 3 s |
   | Harpsichord sustain | 8 s → 3 s |
   | Releases | grand 0.4 s, upright 1.0 s, harpsichord 0.6 s |
   | Pedal noises | down 4.5 s, up 0.5 s |

   A region may end earlier, where its 50 ms RMS falls below −75 dBFS (measured after normalising). Every region ends with a 300 ms raised-cosine fade.
4. **Measure:**
   - **Pitch.** The grand uses the Salamander `tune_ret` table. The pipeline runs YIN on every region anyway (on 0.3–1.3 s) and reports any deviation over 5 cents. VCSL regions use the YIN result as `tuneCents`, which settles the A440-vs-415 question per sample.
   - **Attack loudness:** A-weighted RMS over 0–150 ms after the onset.
   - **Upright only:** the damped decay rate from each release sample, fitted to `T60d(n)` and logged against the §3.7 curve. The grand's `rel*` files are mechanical noises and cannot give a T60.
5. **Normalise and set gain.** Each region is peak-normalised to −1 dBFS so the 16-bit cache keeps full resolution for pp layers. `gainDb` restores the natural recorded level, adjusted per layer only when the loudness check in §3.5 fails.
6. **Encode** with `ffmpeg -f f32le -ar 48000 -ac 2 -i - -c:a libopus -b:a 128k -vbr on -application audio -frame_duration 20 out.opus`.
7. **Verify the round trip.** ffmpeg decodes the Opus file again. The length must be `frames` (or `frames + 312`, with the pre-skip handled), and the onset must be at 96 ± 1 frames. Anything else fails the build.
8. **Write `map.json`.** It lists layers (with velocity splits, `velRef` and `decodeOrder`), regions (`file`, `kind`, `layer`, `stop`, `root`, `lo`, `hi`, `tuneCents`, `gainDb`, `frames`, `onsetFrame`), `lastDamper` and `version`. Its SHA-1 goes into the on-device cache header.
   - **Region keys:** Salamander regions cover ±1 semitone around their sampled note [§1.1], and VCSL covers the whole-tone gaps.
   - **Naming conventions:** Salamander file names use scientific octaves (C4 = 60). VCSL uses **Yamaha naming (C3 = 60)**, so the octave is converted before mapping [§2.1, §3.1].

The upright pp layer (VSCO-2 CE `dyn1`) is mapped with `MappingChart` (000 = 21, 002 = 25, …) [§2.1].

### 6.3 MIDI: fetch and validate

**Fetch (`fetch.py`):**
- **Krueger** files are tried at `piano-midi.de` first. On HTTP 418 the tool falls back to the raw Wayback `id_` URL, and **every file must match its SHA-1 (base32)** from repertoire §8.6. The Wayback CDX endpoint gives 503 errors under parallel load, so requests go one at a time.
- **Sankey** zips are downloaded whole. `midi_extract.py` lists the entries, maps the catalogue's chosen pieces to entry names (these names are unverified until the listing runs), and copies the bytes unchanged.
- **Commons** requests send a descriptive User-Agent.
- **Mutopia** needs nothing special.

**Validate (`midi_validate.py`)** parses every file with `mido` and with a Python port of `SmfParser`'s tolerance rules, then checks that the two agree on note count and range. It writes the following into `midi_stats.json`:
- format, PPQ, track and channel layout;
- note range and note count;
- CC64/66/67 counts (this confirms the Krueger pedal data [R:repertoire §2]);
- `durationSec`;
- the velocity histogram (flags Sankey files if they turn out to use a constant velocity, which then sets `velocityPolicy: "flat"`);
- fold counts per instrument.

**Validation fails the build** if:
- a harpsichord-default file needs more than 2% of its notes folded;
- a Krueger SHA-1 doesn't match;
- the parsed duration differs from the site listing by more than 10%, except the known typos: Clementi op. 36/5 ii and Haydn XVI:7 [R:repertoire §9].

**Listening list.** The tier-C engravings (Mutopia K. 457 and op. 111 i) are written to `build/listen.txt`. They are auditioned on the glasses in §8.6 and dropped if they sound mechanical.

### 6.4 Catalogue

`build_catalog.py` merges `catalog_src.json` with the measured statistics into `assets/catalog.json` (§4.6).
- **70 works in 13 categories** [R:repertoire §4], made into 15 shelves by adding Imported and Recently played.
- The **Start here** playlist has 13 tracks.
- The script checks that every movement has an existing asset, that every work's source has a licence file, and that every `defaultInstrument` can play the work's range with ≤ 2% folding.

### 6.5 Licence and attribution ledger

`LEDGER.csv` has one row per shipped asset file:

`asset_path, bytes, sha1, source_name, source_url, licence_id, licence_url, credit, modified, how_modified, notes`

| Asset | `modified` / how |
|---|---|
| MIDI | `no` for every file (byte-identical) |
| Samples | `yes: trimmed, resampled, normalised, Opus-encoded` |

`build_credits.py` turns the ledger into four outputs:
- `assets/licenses/` holds the texts:
  - `CC-BY-SA-3.0-DE.txt`, `CC-BY-SA-4.0.txt`, `CC-BY-SA-3.0.txt`, `CC-BY-3.0.txt` and `CC0-1.0.txt`, each copied from its legalcode URL;
  - `SANKEY.txt`, verbatim from johnsankey.ca/copyright.html (fetched at download time, never retyped [R:repertoire §3]);
  - `SOURCES.csv`.
- `assets/credits.txt` feeds the in-app Credits screen, using the research's draft text for the music [R:repertoire §7] and for the samples [R:sampled-instruments §7].
- `CREDITS.md` goes at the repository root.
- `NOTICE` gives the Salamander credit both ways: "CC-BY 3.0; declared public domain by the author, 2022".

**Checks:** `check_apk_budget.py` fails if any file under `assets/instruments` or `assets/midi` has no ledger row, or if a MIDI file with `modified=no` has a different SHA-1 from its source.

**Obligations this design honours:**
- It has **no audio or video export**, a non-goal that also satisfies Sankey's clause and the CC BY-SA 3.0 DE rule on soundtracking moving images [R:repertoire §3].
- It ships the original MIDI bytes.
- It shows the source credit on the now-playing HUD.

### 6.6 APK size budget

| Content | Size |
|---|---|
| Grand kit, 272 Opus files | 24.0 MB |
| Upright kit with pp layer, 166 files | 13.9 MB |
| Harpsichord 8′+4′, 108 files | 5.1 MB |
| MIDI: about 200 files, 70 works (≤ 5 MB) | ≈ 3.5 MB |
| `catalog.json`, credits, licences, companion page | 0.3 MB |
| Code: Kotlin, NanoHTTPD, androidx, baseline profile | ≈ 4 MB |
| Textures | 0 (all procedural at run time) |
| **Total** | **≈ 51 MB** (the check script enforces a 60 MB cap) |

The 516 MiB PCM cache is created on the device, not shipped. A first run with nothing cached needs about 520 MiB free in `/data`; 19 GB is free.

---
## 7. Work breakdown for parallel implementation by agents

### 7.1 Rules of engagement

1. **File ownership never overlaps** (the table in §2.2). An agent edits only the files its WP owns. Anything it needs from another WP goes through the §2.3 signatures.
2. **Contract first.** Each WP's **first commit, within its first hour**, is its public API file matching §2.3 exactly, with `TODO()` bodies. From then on the whole tree compiles, and every agent can code against real types.
   - Every consumer brings its own **fakes** in its test tree: `FakeSongClock`, `FakeAudio`, `FakeRender`, `FakeKits`, and a `FakeSampleStore` backed by `ShortArray`s.
3. **Changing a contract** needs a dated entry in `docs/contracts-changelog.md`, approved by WP1. Signatures only ever grow; nothing is removed during integration.
4. **Branches and commits.** Each WP works on branch `wp<N>-<slug>`. Commits are authored as `tropicalstream <tropicalstream@users.noreply.github.com>`. The user's email is never written anywhere.
5. **Acceptance tests.** JVM tests run with `./gradlew :app:testDebugUnitTest`, with no Robolectric. On-device checks use the §8 scripts. **A WP is done when its listed tests pass and its budget lines in §0.2 are met or measured.**

### 7.2 The work packages

**WP1: Shell, contracts, integration and device tooling** (the integrator)
- **Owns:**
  - Gradle files: root, `app/build.gradle.kts`, `gradle/*`, wrapper, `gradle.properties`, `settings.gradle.kts`.
  - Android files: `AndroidManifest.xml`, `res/**`, `proguard-rules.pro`, `.gitignore`, `.gitattributes`, `baseline-prof.txt`.
  - Kotlin: `HammerklavierApp.kt`, `MainActivity.kt`, `core/*`, `app/Settings.kt`, `app/ThermalGovernor.kt`, `app/DebugControl.kt`, `input/TrackpadGestureEngine.kt`, `ui/BinocularSbsLayout.kt`.
  - `tools/device/*` and `docs/contracts-changelog.md`.
- **Build:**
  - Copy the MathCosmos build skeleton [R:engine_reuse §6], renamed, with `noCompress += listOf("opus","mid","midi","json")`, `nanohttpd:2.3.1`, `profileinstaller`, and `base.archivesName = "Hammerklavier"`.
  - The manifest has two intent filters (MAIN+LAUNCHER and MAIN+AR_APP), `resizeableActivity=false`, `com.rayneo.mercury.app` meta-data, **no `ar_mode`**, and the INTERNET, WIFI_STATE and NETWORK_STATE permissions.
  - Set `FLAG_KEEP_SCREEN_ON`, immersive flags, and `setVolumeControlStream(STREAM_MUSIC)`.
- **Day-0 deliverable:** the app builds, installs and launches. It shows "HAMMERKLAVIER" in both eyes, logs every gesture as `HKInput`, and the CONTROL receiver echoes its extras.
- **Tests:**
  - JVM: `ThermalGovernorTest` feeds temperature sequences (37 → 39.1 → 42.2 → 41 → 40.4 °C) and expects levels 0, 1, 2, 2, 1 with hysteresis. `QualityLadderTest` checks the table values.
  - Device:
    - `adb shell input tap 320 240` logs `tap`.
    - `input keyevent KEYCODE_DPAD_CENTER` logs `tap`, with no double count from the key echo.
    - Two taps 150 ms apart log `double`.
    - `input swipe 200 240 500 240 120` logs `FORWARD`.
    - A firm click on the left arm logs nothing (checks the `cyttsp6` filter).
    - Screencap shows both eyes.

**WP2: MIDI and performance**
- **Owns:** `midi/*`.
- **Build:** §4.1–4.4.
- **Tests (all JVM):**
  - **T2.1** Hand-built byte arrays:
    - format 0 with running status across notes;
    - format 1 with tempo on track 2;
    - SMPTE division −25/40 gives 1,000 µs per tick;
    - sysex skip;
    - meta cancelling running status, with tolerant reuse and a warning;
    - truncated chunk;
    - VLQ edges (`00`, `7F`, `81 00`, `FF FF FF 7F`, a 5-byte VLQ gives an error);
    - note-on velocity 0 as note-off;
    - RMID wrapper;
    - channel-10 notes dropped.
  - **T2.2** Tempo map: 480 PPQ at 120 bpm puts tick 960 at 1,000,000 µs. A tempo change at tick 480 is honoured. Bar starts are right for a 3/4 section.
  - **T2.3** Builder:
    - two channels overlapping on the same key are serialised (no overlap, gap ≥ 20 ms);
    - zero-length notes become 30 ms;
    - unterminated notes end at the last event + 1 s;
    - on the harpsichord, key 96 folds to 84 and a colliding duplicate is dropped;
    - a CC64 step 0 → 127 becomes a 70 ms ramp;
    - the sostenuto latch mask equals the keys held (and all keys when sustain is down);
    - legato hold delays a note-off to the pedal-up, capped at 1.5 s;
    - at equal times `EV_KEY_UP` comes before `EV_NOTE_ON`;
    - the damper lag is applied;
    - with `flatVelocity`, every velocity becomes 72.
  - **T2.4** Golden: every bundled file parses and builds for all three profiles. Duration and range match `catalog.json` within 1 s and exactly, respectively.
  - **T2.5** Fuzz: 10,000 seeded mutations of a valid file throw only `SmfException`, and each parse takes < 50 ms.
  - **T2.6** Performance: building Hammerklavier iv takes ≤ 60 ms on the JVM.

**WP3: Audio engine core**
- **Owns:** `audio/AudioEngine.kt`, `CommandRing.kt`, `ClockPublisher.kt`, `Sequencer.kt`, `VoicePool.kt`, `Voice.kt`, `DamperModel.kt`, `Tuning.kt`, `Prefetcher.kt`, `BlockStats.kt`.
- **Build:** §2.5, §3.1, §3.4–3.9, §3.13–3.14.
- **Tests:**
  - JVM, with `FakeSampleStore`:
    - **T3.1** A note at 1,000,000 µs, rate 1, starts at output frame 48,000 (block 187, offset 128). At rate 0.5 it starts at frame 96,000.
    - **T3.2** Stealing:
      - with 64 voices in mixed states, `FADING` goes first, then noise voices, then damping ones;
      - `keyDown` voices are stolen only when nothing else is left;
      - there are never more than 3 voices per key, and kill slots fade out in one block.
    - **T3.3** `DamperModel`: `pedalDamping` is 1 at p = 0 and 0.33, 0 at 0.55, and monotonic. Half-pedal effective T60 equals T60d/d within 2%. Keys above `lastDamper` are never damped. Latched keys have d = 0. Re-pedalling freezes the level.
    - **T3.4** Tuning: Werckmeister III matches the table. A415 is −101.27 cents. A key at its root in equal temperament with 0 cents takes the copy fast path.
    - **T3.5** `Voice`:
      - at rate 1 the output is bit-identical to the source;
      - at rate 2^(1/12) the FFT peak is within 0.1 cent of the expected frequency;
      - window refills leave no discontinuity (maximum step bounded by the source's maximum step × 1.05).
    - **T3.6** Seqlock: 10⁶ reads against a concurrent writer never see a torn record. `S(n)` is right for synthetic timestamps. Pause clamps at `pauseS`.
    - **T3.7** Allocation: `ThreadMXBean.getThreadAllocatedBytes` shows 0 bytes during 10 s of `chordStorm(64)` after warm-up.
  - Device: **M1** (BWV 846 on `SyntheticKit`), then T-CPU, T-UND, T-PF and T-GC.

**WP4: Sample kits, decode and storage**
- **Owns:** `audio/kit/*`.
- **Build:** §3.2–3.3. `SyntheticKit` ships first, so WP3 can reach M1 without real samples.
- **Tests:**
  - JVM:
    - **T4.1** A golden `map.json` from the WP12 test fixture parses.
    - **T4.2** `LayerSelector`: every (key, velocity) pair maps to a region covering the key, with the layer chosen by the velocity splits. With `readyMask` set to v10 only, every velocity maps to v10. Release and pedal round robins alternate.
    - **T4.3** `SampleStore` on a temporary mmap'd file: reads across a region end zero-fill; a header SHA-1 mismatch is rejected; readers on two threads do not interfere.
  - Device:
    - **T-DEC** Decode times for all three kits, including time until playable.
    - **T-ALIGN** Decode C4v10: the energy onset is at `onsetFrame` ± 1.
    - **T-RESUME** `am force-stop` in the middle of a decode, then relaunch: decoding resumes at the right layer, and the cache verifies.

**WP5: DSP**
- **Owns:** `audio/dsp/*`.
- **Build:** §3.10–3.12.
- **Tests (JVM):**
  - **T5.1** The reverb impulse response gives RT60_mid within ±10% of 1.3 s (Schroeder backward integration of the 500 Hz–2 kHz band) and RT60 at 8 kHz of 0.7 s ± 15%. It stays stable for 60 s of full-scale noise. Crossfading the early-reflection sets produces no step larger than −60 dBFS.
  - **T5.2** `ResonatorBank`: a sine at f(n) makes resonator n respond more than 20 dB above its semitone neighbours. Free-decay T60 is within ±10%. Retuning moves the peak. With send 0 the output is exactly 0.
  - **T5.3** `MasterBus`: a +6 dBFS sine comes out with a peak ≤ −1 dBFS. A 1-sample impulse at +12 dBFS is caught by the look-ahead with no overshoot. The biquads match the RBJ reference magnitudes at 3 frequencies within 0.1 dB.
  - **T5.4** Zero allocation, and cost per frame measured by JMH-lite (informational).

**WP6: Render core**
- **Owns:** `render/HkGlView.kt`, `StereoRenderer.kt`, `StereoRig.kt`, `CameraDirector.kt`, `SceneLayer.kt`, `GazeCamera.kt` and `GlyphBoard.kt` (both copied), `render/gl/*`, `render/mesh/MeshBuilder.kt`.
- **Build:** §5.1, §5.4 (rig and director), §5.6 (the program side), §5.7 (GlyphBoard).
- **Tests:**
  - JVM:
    - **T6.1** Stereo rig: a point at the zero-parallax distance projects to the same NDC x in both eyes, and a nearer point has crossed disparity. `right = forward × up` holds for all views.
    - **T6.2** Camera director: glides reach their endpoints at 0.9 s; the FOV blends only when views differ by more than 10°; the follow spring stays within the 0.6 m/s cap.
    - **T6.3** `MeshBuilder` produces valid one-hot lanes and unit normals.
  - Device:
    - **M-GL** A test layer (a box keyboard skinned from a fake sine pose) renders in both eyes.
    - **T-FPS** Measured fps is 30 / 20 / 15 / 10 at divider 2 / 3 / 4 / 6.
    - There is no `FRAME HITCH` in 5 minutes.
    - The chosen EGL config and `GL_MAX_VERTEX_UNIFORM_VECTORS` are logged.
    - The mono launch flag works.

**WP7: Instrument models**
- **Owns:** `render/model/*`.
- **Build:** §5.3 and the §5.2 draws 9–20 and 22–23. Draw 21, the GlyphBoard labels, belongs to WP6. The shaders' uniform names come from WP6's `Shaders.kt`, whose first commit fixes them.
- **Tests:**
  - JVM:
    - **T7.1** Triangle budgets: grand ≤ 26k, upright ≤ 22k, harpsichord ≤ 24k.
    - **T7.2** `keyX` is monotonic, with a 13.71 mm pitch (grand) or 13.25 mm (harpsichord). Sharp positions follow the cut-out rule.
    - **T7.3** Every skinned vertex has slot < 22 and a one-hot selector.
    - **T7.4** `anchors.camera()` returns the §5.4 table exactly.
    - **T7.5** No NaNs.
  - Device: a mono screencap of every instrument × view × framing (18 shots) goes to `docs/shots/`. **T-APL** runs on them.

**WP8: Mechanism animation**
- **Owns:** `render/anim/*`.
- **Build:** §5.5.
- **Tests (JVM):**
  - **T8.1** `Mechanics` values: travel is 230 ms at v20, 86 ± 1 ms at v64 and 20 ms at v110. Key-bottom is +5.4 ± 0.3 ms at v64.
  - **T8.2** Keys and hammers:
    - `keyDip` is exactly 0 one millisecond before `on − tt` and positive just after;
    - the hammer reaches 1.0 at `on` ± 0.5 ms;
    - the hammer is checked at 0.68 while the key is held;
    - the key returns in 35 ms;
    - the damper lifts from `dip > 0.5` and from sustain > 0.33;
    - at rate 0.5, song-time durations double, so real-time durations are unchanged.
  - **T8.3** Re-strike from part-way up is continuous: no step larger than 0.05 of the dip per millisecond.
  - **T8.4** Harpsichord: the key reaches the pluck fraction at `on`; the tongue deflects on return; the damper follows the jack.
  - **T8.5** String envelopes: two-stage decay, damping by `d`, and reset on epoch change.
  - **T8.6** `evaluate` allocates nothing and takes ≤ 150 µs for 88 keys (JVM, informational).

**WP9: Venue**
- **Owns:** `render/venue/*`.
- **Build:** the §5.2 draws 1–8, and §5.8. The room geometry follows [R:visual_design §1.3]: 10.5 × 8.0 × 5.7 m, 3 mirrors on the north wall, 6 Pesne panels, the trellis converging on a spider web of 16 spokes and 9 turns, an 18-candle chandelier and about 50 flames.
- **Tests:**
  - JVM:
    - **T9.1** Mirror visibility: a flame and an eye in front of a mirror give a visible reflection. A flame behind the wall plane gives none. An eye outside the reflected cone gives none.
    - **T9.2** Flicker stays within ±4% globally and ±8% per sprite.
    - **T9.3** Probe bake: 128×64, and its energy is bounded.
    - **T9.4** Venue triangles ≤ 18k.
  - Device: **T-APL**, Hall ≤ 12% and Stage ≤ 9%. The Hall screencap is reviewed against the research build sheet.

**WP10: Library and companion**
- **Owns:** `library/*`, `companion/*`, `assets/companion.html`.
- **Build:** §1.4 (the data side), §1.5, §1.6, §4.6.
- **Tests:**
  - JVM:
    - **T10.1** The golden `catalog.json` parses: 70 works, 15 shelves, every movement's asset path is present.
    - **T10.2** `ImportStore`:
      - file names are sanitised;
      - files without `MThd` are rejected;
      - SHA-1 duplicates are recognised;
      - zip limits hold (201 entries or 20 MB is rejected; path traversal `../` is rejected);
      - a subfolder becomes one work with its movements in filename order.
    - **T10.3** `Playlist`: next and previous rules, including restart when more than 3 s in.
    - **T10.4** The companion server runs on the JVM on a random port:
      - `GET /api/library` returns JSON;
      - a multipart upload of a real `.mid` succeeds;
      - writes without the token get 401;
      - "Für Elise" round-trips in UTF-8;
      - an upload over 2 MB is rejected.
  - Device: upload from a phone browser on the same Wi-Fi. `adb push` followed by rescan shows the file in Imported. "No Wi-Fi" is shown when Wi-Fi is off.

**WP11: UX controller and overlays**
- **Owns:** `app/AppController.kt`, `ui/MenuModel.kt`, `MenuCard.kt`, `HudView.kt`, `TitleCard.kt`, `CalibrationCard.kt`, `OverlayViews.kt`.
- **Build:** §1.3, §1.4 (the UI side), §1.7, §1.8, and the transport behaviour in §4.5.
- **Tests:**
  - JVM, against fakes:
    - **T11.1** Menu navigation: move, page, select, back. Transport rows reflect state. Adjust arithmetic clamps.
    - **T11.2** Gesture scripts produce the expected fake calls:
      - `FORWARD` while playing calls `render.setView(ACTION)` and `audio.setRoom(cutaway)`;
      - `UP` calls `render.setView(ACTION, framing 1)`;
      - a double-tap opens the menu;
      - choosing Instrument › Upright while playing calls `pause`, then `kits.open(UPRIGHT)`, then `setPerformance(new, sameUs)`, then `render.setInstrument`, then `play`, in that order.
    - **T11.3** The first-run flow: the title card waits for `onPlayable`, a tap starts "Start here" #1, and the hint is shown.
    - **T11.4** Resume point: saved every 5 s, restored on relaunch.
  - Device: **T-5MIN**, a scripted walkthrough using `--es gesture` steps with a screencap per step, plus the human first-use test L-5.

**WP12: Asset pipeline**
- **Owns:** `tools/pipeline/*`, `app/src/main/assets/{instruments/**, midi/**, licenses/**, catalog.json, credits.txt}`, `CREDITS.md`, `LEDGER.csv`, `NOTICE`. WP1 adds the LFS line to `.gitattributes` when WP12 asks.
- **Build:** §6.
- **Tests (pytest):**
  - **T12.1** Onset detection within 1 frame on synthetic signals. YIN within 1 cent on synthetic tones.
  - **T12.2** The Opus round trip preserves length and onset.
  - **T12.3** `map.json` passes its JSON Schema, and the fixture it produces is used by WP4.
  - **T12.4** The ledger covers every asset, and MIDI SHA-1s are unchanged.
  - **T12.5** `check_apk_budget.py` passes.
  - **T12.6** The catalogue's measured fields are present, and folding is ≤ 2% for each default instrument.
- **Gate:** fetching blocks until the user approves the manifests. Code and tests can be written before then.

### 7.3 Dependency graph and integration order

```
hour 0 ── WP1 skeleton + every WP's API stub commit (tree compiles)
          │
phase A ──┼─ WP2 MIDI ─────────┐
(parallel)├─ WP4 SyntheticKit ─┼─► M1 FIRST SOUND   (WP1+2+3+4: BWV 846 on the synthetic kit, on the glasses;
          ├─ WP3 audio core ───┘                    baseline T-CPU/T-UND)
          ├─ WP12 pipeline ──(approval)──► grand kit ─► M2 REAL GRAND (WP4 decode: T-DEC, T-ALIGN, progressive voicing)
          ├─ WP6 render core ─┐
          ├─ WP8 animation ───┼─► M3 FIRST SIGHT (Player view: keys + pedals move; T-SYNC lead calibration)
          ├─ WP7 models ──────┘         └─► M4 HAMMERS (Action cutaway + overhead, strings, dampers)
          ├─ WP5 DSP ─────────┐
          ├─ WP9 venue ───────┴─► M5 THE ROOM (Hall view, reverb, resonance; T-APL; first T-THERM)
          ├─ WP10 library ────┐
          └─ WP11 UX ─────────┴─► M6 LIBRARY (full menus, 70 works, companion upload, adb push, credits)
                                   └─► M7 THREE INSTRUMENTS + SOAK (upright and harpsichord kits and models;
                                        45-minute soak; governor tuning; listening L-1..L-5; release candidate)
```

**Who integrates what:**
- WP1 wires `MainActivity` at each milestone.
- The other agents keep working in their own files, so merges never conflict.
- Milestones M1–M3 are the critical path: they prove the clock, the thermals and A/V sync before anyone polishes content.
- If M1 fails its budget, WP3 and WP5 re-plan before M4 begins.

---
## 8. Verification plan on the real glasses

### 8.1 Build, install, launch

```bash
S=A06B4A96A733283; PKG=com.tropicalstream.hammerklavier
cd /Users/me/Projects/Hammerklavier && ./gradlew :app:testDebugUnitTest :app:assembleDebug && \
adb -s $S install -r app/build/outputs/apk/debug/Hammerklavier-debug.apk && \
adb -s $S shell cmd package compile -m speed -f $PKG && \
adb -s $S shell am start -n $PKG/.MainActivity
# bench session prerequisites (reset device_wearing to 0 afterwards):
adb -s $S shell settings put global device_wearing 1 && adb -s $S shell wm dismiss-keyguard
# flat single view for screenshots:
adb -s $S shell am start -n $PKG/.MainActivity --ez mono true
adb -s $S logcat -s HKAudio HKClock HKRender HKThermal HKInput HKLoader HKLib HKWeb AndroidRuntime:E
```

- **Always pass `-s`, and chain with `&&`** so a failed build never installs a stale APK [R:engine_reuse §6.5].
- **`cmd package compile -m speed -f`** AOT-compiles the DSP and render loops, so the first minutes don't run interpreted or JIT-warming code on HKAudio. `baseline-prof.txt` lists `audio/**`, `render/anim/**` and `midi/**` for release builds, applied by `profileinstaller`.
- **Copying the tree:** after any copy, run `rm -rf .gradle app/build` before the first build [GUIDE gotcha 16].

### 8.2 CONTROL broadcast (`am broadcast -a com.tropicalstream.hammerklavier.CONTROL …`)

The receiver is registered only while the app is resumed (the MathCosmos pattern).

| Extra | Effect |
|---|---|
| `--ei view 0\|1\|2` | Player / Action / Hall |
| `--ei framing 0\|1` | primary or second framing |
| `--ei piano 0\|1\|2` | grand / upright / harpsichord |
| `--es play <movementId>` | play a movement, e.g. `beethoven.op106.4`; also `sync`, `scale`, `storm64` |
| `--ez pause true` / `--ez resume true` | transport |
| `--el seek <ms>`, `--ef rate 0.8` | position and tempo |
| `--ei quality -1\|0..3` | automatic or forced Q level |
| `--es gesture tap\|double\|triple\|fwd\|back\|up\|down` | inject into `AppController`, for scripted walkthroughs |
| `--ei lead <ms>` | display lead for A/V sync |
| `--ef fov 18.3`, `--ef ipd 0.6` | life-size FOV; stereo depth |
| `--es temperament werckmeister_iii`, `--ef pitch 415` | tuning |
| `--ez debug true` | debug overlay |
| `--ez sync true` | sync flash on |
| `--ez calib true` | calibration card |
| `--ez rescan true` | rescan imports |
| `--ez gcstats true` | log `art.gc.gc-count` and `art.gc.gc-time` |
| `--ez recenter true` | recentre the gaze |

### 8.3 Measurements and their pass criteria

| Test | Procedure | Pass |
|---|---|---|
| **T-CPU** | `--es play storm64`, then 3 min of `beethoven.op106.4`. Every 10 s the engine logs `HKAudio stats voices= peak= p50= p99= max= underruns= stolen= slow=`. Run `adb -s $S shell top -H -b -d 5 -n 36 -p $(adb -s $S shell pidof $PKG) \| grep -E 'HKAudio\|GLThread\|main'` | HKAudio ≤ 28% of one core under storm and ≤ 15% average on op. 106; p99 ≤ 1.3 ms; max ≤ 3 ms. GLThread ≤ 18% |
| **T-UND** | 30 min soak of the densest playlist (op. 106 i–iv, WTC I fugues). Underruns come from `AudioTrack.getUnderrunCount()` in the stats line, cross-checked with `adb shell dumpsys media.audio_flinger` | Δunderruns = 0 |
| **T-PF** | Same run. `slow=` counts bulk reads over 1 ms | 0 after the first 10 s; ≤ 3 in the first 10 s after a cold boot |
| **T-DEC** | Clear the cache with `adb shell run-as $PKG rm -r files/pcm`, launch, and read the `HKLoader` timings | grand playable ≤ 8 s and complete ≤ 40 s; upright ≤ 25 s; harpsichord ≤ 12 s |
| **T-ALIGN** | Debug `--es play scale` with the level meter on. `HKAudio onsetCheck` compares the first rendered energy frame against the scheduled frame | ±1 frame |
| **T-GC** | `--ez gcstats true` before and after 5 min of playback | ≤ 2 GCs; Java heap flat within 2 MiB |
| **T-MEM** | `adb shell dumpsys meminfo $PKG` at 5 and 30 min | Java heap ≤ 48 MiB; TOTAL PSS minus mapped-file PSS ≤ 200 MiB |
| **T-FPS** | Debug overlay and `HKRender fps=` lines in each view | 30 ± 1 (Q0–Q1), 20 (Q2), 15 (Q3), 10 when idle; no `FRAME HITCH` |
| **T-APL** | `tools/device/apl.sh <view>` runs `adb exec-out screencap -p`, then `apl_meter.py` averages Rec.709 luma over 1280×480. If screencap misses the GL layer, a frame grabbed from `scrcpy --record` is used instead | Player ≤ 9%, Action ≤ 9%, Hall ≤ 12% |
| **T-START** | Cold start with the cache warm, to first sound | ≤ 4 s |
| **T-THERM** | §8.4 | ≤ 39.5 °C at 30 min, Q0; no reboot |
| **T-SYNC** | §8.5 | |offset| ≤ 20 ms after calibration |

### 8.4 Thermal soak protocol

1. Start cool: battery below 33 °C (`adb shell dumpsys battery | grep temperature`), room about 22 °C, glasses worn or `device_wearing=1`, brightness at the user's normal level.
2. Run `tools/device/soak.sh 45`. Every 10 s it records to `build/soak-<date>.csv`:
   - battery temperature;
   - `dumpsys thermalservice` status;
   - `top -H` for HKAudio and GLThread;
   - PSS;
   - the latest HKAudio and HKRender stats lines;
   - the current Q level.
3. Play the op. 106 playlist on the grand in a view rotation Player → Action → Hall, 5 minutes each, repeated.
4. Report: time to each Q transition, peak temperature, CPU per thread per Q level, fps per Q level, and any hitches or underruns.
5. **Pass:** no reboot or kill. Q0 holds for at least 30 min at 22 °C. If Q1 is reached, the temperature stabilises below 42 °C.
   - If this fails, tighten the budgets in this order: idle fps, mirrors, crystals, MSAA, then the voice cap. Frame rate goes last.
6. Repeat at Q3 forced, for 10 min, to confirm the floor profile is thermally stable. The expected slope is flat or falling.

### 8.5 A/V sync protocol

- **Goal:** the hammer meets the string exactly when the note sounds.
- **Stimulus:** `--es play sync --ez sync true`. `SyntheticPerformances.syncClick` plays A4 at velocity 118 every 600 ms for 60 s. The renderer draws a bright disc (255,244,214), 60 px across at screen centre, on every frame where `pose.strikeAge[69] < 33 ms`. The disc comes from the same `ActionModel` hammer-contact state, not from an audio event.
- **Coarse check with scrcpy.** Run `scrcpy -s $S --record=build/sync.mkv --audio-source=output` for 30 s (scrcpy 2.x captures audio on Android 11 and later). `tools/device/av_offset.py` finds the audio onsets by energy and the flash frames by luma, then reports the median and spread of flash minus onset.
  - scrcpy's own capture latency differs between video and audio, so this value is a **regression baseline**, not the absolute offset.
- **Absolute check with a phone.** A phone recording in 240 fps slow motion films through the waveguide with the speaker in shot. Count frames from the flash to the waveform onset in the phone's audio track, or to a piezo contact mic taped to the temple.
- **Calibrate** with `--ei lead N` until the absolute median is within ±5 ms, and save that value as the `Settings.displayLeadMs` default.
- **Pass:** |median| ≤ 20 ms, and a spread (p90 − p10) ≤ 1 frame (33 ms).
- **Clock drift:** a 30-min run logs `HKClock drift=` (timestamp-predicted frame minus the extrapolated frame). It must stay within ±2 ms, which confirms that `getTimestamp` refreshes keep the clock locked.

### 8.6 Human listening and legibility checks (on the glasses, in M5–M7)

| # | Check | Decides |
|---|---|---|
| L-1 | Calibration card in a lit room and in a dim room: find the dimmest visible swatch; view the grand in Player and Hall | `presenceFloor` default; whether the edge overlay is on by default |
| L-2 | Built-in speakers vs headset, Für Elise and the Moonlight i | Speaker voicing constants (§3.12) |
| L-3 | A crescendo on repeated C4 (a synthetic score, v10 → 127) | Whether `XFADE_STEPS` > 0 is worth +31% voices |
| L-4 | Harpsichord in Werckmeister III vs equal temperament, WTC I no. 1 and Scarlatti K. 141 | Harpsichord defaults |
| L-5 | A person new to the app, given no instructions, for 5 minutes | Whether they reach all three views, pause, the library and another instrument unaided. If not, adjust the hints |
| L-6 | Tier-C engravings from `build/listen.txt` | Keep or drop them |

### 8.7 Input checks

- **Left-arm filter:** a firm click on the left arm must produce no `HKInput tap`. This checks the `cyttsp6` key-path filter, which has never been tested.
- **Swipe direction:** toggle the system's natural-mode setting and confirm the direction semantics. If they flip, the **Reverse swipe** setting fixes it.
- **Double-tap:** 200 double-taps in a row must never also trigger play/pause.
- **Swipe latching:** a very fast swipe must advance a menu by exactly one row.

---

## 9. Risk register and non-goals

### 9.1 Risks

| # | Risk | L | I | Mitigation | Early signal / owner |
|---|---|---|---|---|---|
| 1 | Heat: audio + GL + a bright display push the glasses to a reboot, as a sibling app did | M | H | Ladder Q0–Q3 across GL and audio; idle 10 fps; allocation-free loops; AOT compile; APL caps; decoding only while cool | T-THERM at M5 (not M7) / WP1 |
| 2 | Page faults in the mmap'd cache stall HKAudio, causing underruns on note bursts | M | H | `load()` when memory allows; head pre-touch; score-driven HKPrefetch; 85 ms buffer; `slow=` counter | T-PF at M2 / WP3, WP4 |
| 3 | The ART inner loop costs more than the 80 ns/voice-frame estimate | M | M | Q-dependent caps; linear fallback; self-protecting voice cap (§3.13); measure at M1 with SyntheticKit | T-CPU at M1 / WP3 |
| 4 | `PERFORMANCE_MODE_NONE` float track behaves badly on this HAL (resampling, glitches) | L | M | One-line fallback to `LOW_LATENCY` with the same buffer request; both measured at M1 | T-UND / WP3 |
| 5 | Opus decode is slow, or the c2 decoder mishandles pre-skip, so first run is long or onsets misalign | L | M | Progressive voicing (playable after v10); exact frames/onset checks; FLAC fallback (`c2.android.flac.decoder` by name) with a pipeline flag | T-DEC, T-ALIGN / WP4 |
| 6 | Black lacquer and ebony keys vanish on the waveguide | H | M | Reflector recipe; walnut upright default; presence-floor calibration; edge overlay | L-1 at M3 / WP7, WP9 |
| 7 | Stereo discomfort (off-axis vs toe-in, IPD scales) | M | M | Per-view IPD scale and zero-parallax; CONTROL-tunable; stereo-depth setting | M3 on-head review / WP6 |
| 8 | A/V misalignment from unknown display latency | M | M | Clock from `getTimestamp`; calibrated `displayLead`; sync test | T-SYNC at M3 / WP6, WP8 |
| 9 | Timbre steps between the 6 grand layers are audible | M | L | Loudness matching; `XFADE_STEPS` switch; Accurate-Salamander drop-in later | L-3 / WP3, WP12 |
| 10 | Upright/harpsichord sample rate, bit depth or pitch standard differ from assumptions | M | L | Pipeline resamples to 48 kHz and measures pitch with YIN, so nothing is assumed | T12.1 / WP12 |
| 11 | piano-midi.de blocks us (HTTP 418) | H | L | Wayback raw URLs with SHA-1 (base32) verification [R:repertoire §1] | fetch log / WP12 |
| 12 | Licence breach through modification or export | L | H | Byte-identical MIDI; ledger check; credit HUD; **no export feature** | T12.4 / WP12 |
| 13 | Messy imported MIDI (running status after meta, truncation, SMPTE, huge files) | H | L | Tolerant parser; fuzz tests; 2 MB cap; errors surfaced as "couldn't read this file" | T2.5 / WP2 |
| 14 | Input ambiguity: the double-tap also toggles pause; the left-arm firm click is read as a tap; natural mode flips swipes | M | M | 300 ms resolver (no instant single tap); `cyttsp6` key filter; reverse-swipe setting | §8.7 / WP1 |
| 15 | The low-RAM device kills the app in the background (display off) | M | L | Audio-only survival while paused; resume point saved every 5 s; no foreground service promised | T-MEM / WP11 |
| 16 | Built-in speakers make the bass octaves inaudible and the grand sounds thin | H | M | Speaker voicing (HPF 110 Hz, +3 dB at 250 Hz); the Hall wet mix carries body; recommend headphones on the title card when on the speaker route | L-2 / WP5 |
| 17 | The Adreno uniform limit is lower than assumed, or MSAA is unavailable | L | M | Log `GL_MAX_VERTEX_UNIFORM_VECTORS`; shaders ≤ 60 vec4; MSAA fallback chooser; analytic antialiasing on keys and lines | M-GL / WP6 |
| 18 | Screencap doesn't capture the GL layer, so APL can't be measured | M | L | Fall back to scrcpy frame grabs [R:visual_design §3.6] | T-APL / WP1 |
| 19 | Parallel agents drift from the contracts | M | M | API-stub-first commits; frozen §2.3; changelog; each WP ships its own fakes; the tree compiles at hour 0 | CI build on every push / WP1 |

L = likelihood, I = impact (L/M/H).

### 9.2 Explicit non-goals (v1)

- A **fortepiano** (Silbermann, Stein, Walter) or a clavichord. There are no redistributable samples, so this is the v2 slot (§1.1). The harpsichord lute stop, una corda samples and the Salamander `harm` resonance samples are also out.
- **Physical-model synthesis**, and any convolution reverb.
- **Audio or video export**, screen-recording features and sharing. Sankey forbids it and it complicates CC BY-SA.
- **Live MIDI input** (USB or Bluetooth keyboards) and the user playing the piano. The app is a player.
- **Learning features**: falling notes, score display, fingering, hand colouring, and MIDI editing or quantising.
- **A fourth "Inside" view.** It is Action's second framing. A pianist figure is also out.
- **Other rooms** (Amalienburg), fresco ceilings and painting reproductions. The venue is procedural only, with no downloaded images.
- **Per-key sympathetic resonance with the pedal up** (R7 without pedal), the upright practice rail, bass-only sustain, and the duplex/aliquot shimmer.
- **Complete Scarlatti (555 sonatas)**, MAESTRO/SMD non-commercial packs, and any library download from inside the app. Users can import these themselves.
- **A foreground media service** or background playback after the app leaves the foreground. **Voiced programme notes**, since the device has no TTS.
- **Head position (6DoF)**, hand tracking and camera use.
- **Vendor SDK UI** (MercurySDK). The only vendor dependency is the `com.rayneo.mercury.app` meta-data flag.
