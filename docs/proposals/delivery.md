# Hammerklavier: delivery-first architecture proposal

**Lens:** a staff engineer planning a build that has to work on the first install. The aim is to keep integration risk low: frozen interfaces, stubs for every external dependency, pure-Kotlin cores covered by JVM unit tests, and milestones that each run on the glasses.
**Date:** 2026-09-22.
**Target:** RayNeo X3 Pro (`A06B4A96A733283`), package `com.tropicalstream.hammerklavier`, project `/Users/me/Projects/Hammerklavier`.

**Inputs read in full:**
- `docs/research/sampled-instruments.md` and `sample-download-manifest.tsv`
- `repertoire.md`
- `instrument-mechanics-and-sound.md`
- `engine_reuse.md`
- `visual_design.md`
- the X3 starter guide (input, audio, gotchas)
- MathCosmos `MainActivity.kt`, `StereoMathRenderer.kt` and `MathCosmosView.kt`
- SpyHunt `AudioEngine.kt` (output thread)
- WanderQuest `TrackpadGestureEngine.kt`

**Facts I checked today (read-only):**
- **Glasses, over adb:**
  - `ro.build.version.sdk=32`.
  - 4 cores, `cpuinfo_max_freq` 1,996,800 kHz each. That is 2.0 GHz, not the 2.5 GHz on the spec sheet.
  - MemTotal 3.87 GB, MemAvailable 2.63 GB, `ro.config.low_ram=true`, 19 GB free on `/data`.
  - Battery at 26.5 °C when idle.
  - FastMixer present, and `dumpsys media.audio_flinger` prints `underruns=` counters.
  - `top`, `simpleperf` and toybox 0.8.4 are on the device.
  - The shell can reach `/sdcard/Android/data/<pkg>/`.
- **Mac:**
  - ffmpeg with `libopus`, plus ffprobe.
  - numpy 2.4.3. scipy and PIL are not installed.
  - scrcpy 3.3.4.
  - The Gradle cache already holds `junit:junit:4.13.2`, `org.json:json:20180813` and `org.nanohttpd`.
- **The project directory already holds a skeleton:**
  - gradle wrapper 8.9, `settings.gradle.kts`, root `build.gradle.kts`, `gradle.properties`, `.gitignore`, and `res/` (themes, launcher vectors, backup rules);
  - no `app/build.gradle.kts`, no manifest and no sources;
  - `res/values/colors.xml` still carries MathCosmos comments;
  - `.git` exists with no commits.

---

## 0. Decisions at a glance

| Question | Decision |
|---|---|
| Instruments | **Three:** Grand (Salamander C5), Upright (VCSL "Knight"), and a **Bach harpsichord** (VCSL Flemish 8′/4′, drawn as a French double after Blanchet). No fortepiano, because no shippable samples exist. |
| Views | **Player → Action → Inside → Hall**, cycled by swipe forward/back. Player and Action (hammers striking strings) ship first, at M3–M4. |
| Venue | Sanssouci Konzertzimmer (1746–47) by candlelight, drawn as gilt, flames and mirror light only. |
| Modules | One Gradle `:app` module. **Pure-Kotlin packages** (`contract`, `midi`, `engine`, `dsp`, `clock`, `mech`, `geom`, `bank`, `library` core, `ui.model`) have no `android.*` imports. A script enforces this, and JVM tests (JUnit 4.13.2, cached) cover them. |
| Master clock | The audio output. The sequencer runs inside the audio block. The renderer maps `AudioTrack.getTimestamp` onto score time and computes every key, hammer and pedal pose **analytically** from the immutable `Performance`, so moves that start before the sound come for free. |
| Audio format | Ogg Opus 112 kb/s at 48 kHz in the APK. It is decoded once to a page-aligned PCM16 cache file and memory-mapped. Only the active instrument is mapped. |
| Output | Float, 48 kHz, stereo, `PERFORMANCE_MODE_LOW_LATENCY`, `THREAD_PRIORITY_URGENT_AUDIO`, 256-frame blocks. A latency tuner grows the buffer on underruns. |
| DSP | Linear interpolation, table-driven decays, 8-line FDN reverb (no convolution), a 24-resonator open-string halo, SpyHunt's limiter. Voice cap 64/48/32 streams by thermal level. |
| Rendering | GLES 2.0 API with GLSL ES 1.00, off-axis stereo. Keys, hammers, dampers and strings are each one uniform-skinned draw. ≤35 draws and ≤45k triangles per eye. Choreographer at 30/20/15 fps. |
| Fallbacks | A stub sample bank in the APK, plus a synthesized in-code bank for when even decoding fails. Bundled MIDI is validated at build time. Imports are validated before they are accepted. A bad file shows a reason on screen and never crashes the app. |
| Import | Companion page on `http://<ip>:19112` (plain HTTP, token-gated writes) and an adb folder `/sdcard/Android/data/com.tropicalstream.hammerklavier/files/Scores/`. |
| Parallel build | 12 work packages with disjoint file ownership. Day 0 freezes the contract files and their stubs. The plan has ten milestones, M0–M9, and each one is demonstrated on the glasses. |

---

## 1. Product definition

### 1.1 Instruments offered, and why

| UI name | Samples | Model drawn | Compass | Pedals / stops | Default pitch |
|---|---|---|---|---|---|
| **Grand piano** | Salamander Grand V3 (Yamaha C5): 30 notes × 6 layers (v1/4/7/10/13/16), 88 releases, 4 pedal noises | 200 cm C5-size grand, black lacquer, lid on the stick | A0–C8 (21–108); last damper 88 | Damper (CC64, half pedal), sostenuto (CC66), una corda (CC67) | A440, equal |
| **Upright piano** | VCSL Knight, 45 notes × 2 layers, plus the VSCO-2 CE pp layer (23 notes), 45 releases, 8 pedal noises | Yamaha U3-size, 131 cm tall; walnut finish by default because it reads on the waveguide, ebony as an option | 21–108; last damper 90 | Damper, soft (hammer rail), practice-mute rail (menu only) | A440, equal |
| **Bach harpsichord** | VCSL Flemish 8′ (28 notes) + 4′ (26 notes) with their own jack-fall releases; the English lute stop as an optional registration | French double after Blanchet c.1740: green-and-gold case, painted soundboard, ebony naturals and bone sharps | FF–f‴ (29–89); notes outside are folded | No pedals. Registrations 8′, 4′, 8′+4′, lute. CC64 becomes finger legato | A415, Werckmeister III |

**Why three instruments.** The brief asks for real samples of each instrument. No redistributable fortepiano multisample exists: Dore Mark's Clementi 1808 has no licence, and it is an English piano in any case. What Bach actually played was the harpsichord (and the clavichord, for which nothing is shippable either). "Early Bach-style piano" therefore becomes the harpsichord, labelled honestly. A Silbermann fortepiano would need real samples and its own Stossmechanik action model, so it is v2 (§9 non-goals). The harpsichord samples are Flemish and the drawing is a French double. That pairing is historically coherent, because Blanchet rebuilt Ruckers instruments.

### 1.2 Views

| # | View | What it is for | Ships at |
|---|---|---|---|
| 0 | **Player** | The pianist's view: the whole keyboard and the pedals. Swipe up switches to a close "follow" framing of three octaves. | M3 |
| 1 | **Action** | A cutaway through the instrument at the melody note: key rocking, wippen, jack escapes, hammer strikes the string and is caught by the backcheck, damper lifts. On the harpsichord: jack rises, quill plucks, tongue flips back. Swipe up locks the cut on the current note. | M4 (grand), M5 (upright, harpsichord) |
| 2 | **Inside** | Lid off, looking down the string bed: all hammers flicking up, strings blurring into spindles, the damper row lifting as one. | M8 |
| 3 | **Hall** | Row 3 of the Konzertzimmer: lid open, candles doubled in the mirrors, chandelier overhead (look up with your head). Swipe up gives the life-size optical-FOV mode. | M7 |

Swiping forward or back cycles 0→1→2→3→0, skipping views that are not built yet. A switch is a 0.5 s **dip to transparent**: the scene fades to black (the real room shows through) in 250 ms, cuts, then fades in over 250 ms. I chose this over a camera fly, because the views have different FOVs and animating the FOV on a head-worn display is uncomfortable. It also costs nothing.

The audio perspective follows the view. Reverb send and distance gain ease over 300 ms:

| View | Reverb send | Gain |
|---|---|---|
| Player | 0.20 | 0 dB |
| Action | 0.14 | 0 dB |
| Inside | 0.16 | 0 dB |
| Hall | 0.40 | −3 dB |

### 1.3 Input contract (right temple pad only)

`TrackpadGestureEngine` is copied from WanderQuest. It merges light taps (touch) and firm clicks (KEY `BUTTON_A`/`DPAD_CENTER`), removes echoes, and ignores the left pad by the name `cyttsp6`. I add a key-path filter so a firm click on the left arm is ignored too. The engine emits the gestures `TAP`, `DOUBLE`, `TRIPLE`, `FWD`, `BACK`, `UP` and `DOWN`. A pure `InputRouter` maps a (gesture, context) pair to a `UiAction`, and only `AppController` executes actions. A single tap resolves after 300 ms, which is acceptable for play/pause. View switching uses swipes, which fire at once. Long-press never reaches apps and is not used.

| Context | TAP | DOUBLE-TAP | TRIPLE-TAP | SWIPE fwd / back | SWIPE up | SWIPE down |
|---|---|---|---|---|---|---|
| **Title card** | Enter (library on first run, otherwise the last piece, paused) | – | Recenter | – | – | – |
| **Calibration card** (presence floor, first run and from the menu) | Accept | Cancel | – | Brighter / dimmer swatch | Brighter | Dimmer |
| **Play** (no overlay open) | Play / pause, and show the now-playing card for 4 s | Open the library | Recenter the gaze and show the card | **Next / previous view** | Alternate framing of this view | Open the quick menu |
| **Library** | Drill in; on a movement, play it on its default instrument | Up one level (closes at the top) | Recenter | Drill in / up one level | Highlight up | Highlight down |
| **Quick menu** | Activate the item | Close | Recenter | Change the item's value (live) | Highlight up | Highlight down |
| **Import / Credits panel** | – | Close | – | Page | Scroll | Scroll |

- Menus step once per gesture (the engine latches `swipeFiredForGesture` and re-arms it on UP/CANCEL).
- Direction words are *forward/backward*, never left/right, because natural mode can flip them.
- Menu → Settings has an **Invert vertical** switch in case up and down feel reversed on some units.

**Quick menu items**, in order:

| Item | Values or action |
|---|---|
| Instrument | Grand / Upright / Harpsichord |
| Tempo | 50–150 % in 5 % steps |
| Seek | ±10 s per swipe |
| Restart, Next piece, Previous piece | Actions |
| Registration | Harpsichord only |
| Tuning | Presets per instrument (§3.6) |
| Venue | Salon / Stage / Instrument / Passthrough |
| Library | Action |
| Add music | Opens the Import panel |
| Calibrate display | Opens the calibration card |
| Settings | Invert vertical, Key glow, Release noises, Pedal noises, Resonance |
| Credits | Opens the Credits panel |

### 1.4 Library and import UX

**Library tree:** Categories → Works → Movements. A single-movement work plays on tap.
- **Start here** (13 tracks, from the repertoire report §4.1) is at the top.
- **Imported** comes second, and shows only when the user has imported something. New items carry a `new` badge.
- Then the 13 repertoire categories, in chronological order.
- **The rows:**
  - Work rows show composer, title, catalogue number and the default-instrument glyph (G/U/H).
  - Movement rows show the title and the duration.
- **Paging:** at most 7 rows are visible at 18 px. The highlight moves and the page scrolls.
- **When a movement ends:** the next movement follows after a 3 s gap. At the last movement of a work, playback continues to the next work in the category.

**Companion page** (`assets/companion.html`, served by NanoHTTPD on port **19112**; plain HTTP, because TLS handshakes stutter audio):

| Route | Purpose |
|---|---|
| `GET /` | The page, with a per-install token injected (the WanderQuest pattern) |
| `GET /api/library` | Categories, works and movements, imported pieces included |
| `GET /api/state` | Now playing, position, instrument, view, fps, underruns, battery °C, thermal level |
| `POST /api/upload` (multipart, token) | `.mid`, `.midi` or `.kar`, ≤ 4 MiB each. Validated synchronously. The reply is `{ok, id, title, notes, durationSec}` or `{ok:false, reason}` |
| `POST /api/delete?id=` (token) | Delete an imported piece |
| `POST /api/play?id=&instrument=` (token) | Play |
| `POST /api/transport?action=pause\|resume\|next\|prev\|seek&ms=` (token) | Remote control |

The page has drag-and-drop, including whole folders via `webkitGetAsEntry`, a file picker, per-file progress, the imported list with Play and Delete buttons, and a transport strip. JSON bodies are read as UTF-8 from `content-length`, never through `parseBody`, because titles contain names like Händel and Für Elise. The server runs only while the app is resumed.

**adb folder.** Drop files into `/sdcard/Android/data/com.tropicalstream.hammerklavier/files/Scores/`:
- The app rescans on resume, on a `FileObserver` event, and on `CONTROL --ez rescan true`.
- A file that fails validation moves to `Scores/rejected/`, with a `<name>.why.txt` giving the reason.
- **Fallback for debug builds:** `adb push x.mid /data/local/tmp/ && adb shell run-as com.tropicalstream.hammerklavier cp /data/local/tmp/x.mid files/Scores/`, then `am broadcast … --ez rescan true`. The app scans both `getExternalFilesDir("Scores")` and `filesDir/Scores`.

**Import panel** (Quick menu → Add music):
- the URL, e.g. `http://192.168.1.23:19112`, or `no Wi-Fi — connect in Settings`;
- the adb path, in 14 px;
- the last import result, e.g. `Imported "bwv1006.mid" — 2,311 notes, 3:41` or `Rejected "x.mid": not a MIDI file`;
- the imported count.

### 1.5 On-screen status (everything inside the `BinocularSbsLayout` child; never Toasts or dialogs, which reach one eye only)

| Element | Where | What it shows | Update rate |
|---|---|---|---|
| **Now-playing card** | bottom, 36 px margins | Composer · title · movement; performer credit (e.g. `Performance: Bernd Krueger · piano-midi.de · CC BY-SA 3.0 DE`); instrument and tuning; `3:12 / 9:53` and a thin bar | on start and pause, and for 4 s after a tap; the timer at 1 Hz |
| **Status line** | top-left, 14 px | By priority: errors (`Couldn't read "x.mid": truncated track`), progress (`Tuning the grand piano… 43 %`), warnings (`Stand-in tones: sample bank not installed`), thermal (`Cooling: 20 fps`) | on change, ≤ 2 Hz |
| **View badge** | top-right | `ACTION` or `HALL · life-size`, for 1.5 s after a switch | on change |
| **Debug HUD** (`--ez debug true`) | top-left | `fps 30.0 draws 27/eye streams 23/64 audio 0.9/2.1 ms ur 0 batt 36.4 °C q0` | 1 Hz |

**Styling:**
- warm white (255,236,200) and accent (240,190,100) text on nothing, with a soft glow shadow and no dark boxes;
- sizes: titles 22 px, body 18 px, never below 14 px;
- `LAYER_TYPE_HARDWARE` on static views.

### 1.6 First-run flow

1. The title card shows "Hammerklavier" and the companion URL. At the same moment the loader thread begins **decoding the grand** into the PCM cache, and the card shows `Tuning the grand piano… n %`.
2. Tap opens the **calibration card**: 16 warm swatches at levels 8…68. Swipe to the dimmest one you can see, then tap. That sets `presenceFloor` (the visual research, §3.6).
3. The library opens on **Start here**. Tapping a piece before decoding finishes queues it: `Will play when the grand is ready (43 %)`.
4. The other instruments decode on demand, with progress, or in the background when the app is idle and not playing.

---

## 2. Module architecture

### 2.1 Build layout and the purity rule

- **One Gradle module, `:app`.** A separate Kotlin-JVM module would need a plugin marker the cache may not hold, so every pure package lives in `:app`. They run as **AGP local unit tests** on the JVM (`./gradlew :app:testDebugUnitTest`), the setup 8 sibling apps already use.
- **Test dependencies:** `testImplementation("junit:junit:4.13.2")`, and `testImplementation("org.json:json:20180813")` so that `org.json` works in JVM tests. Both are cached.
- **The purity rule:** files under the pure packages may import only `kotlin.*`, `java.*` and `org.json.*`. `tools/check_purity.sh` greps for `import android` in those directories and fails CI if it finds one.
- **`app/build.gradle.kts`** follows the shape in engine_reuse §6.2, plus:
  - `androidResources { noCompress += listOf("opus","ogg","mid","midi","kar","bin") }`;
  - `testOptions { unitTests.isReturnDefaultValues = true }`;
  - `implementation("org.nanohttpd:nanohttpd:2.3.1")`;
  - `archivesName = "Hammerklavier"`.

  No vendor AARs are used.
- **Manifest:**
  - the MathCosmos form plus `com.rayneo.mercury.app` meta-data, and **no** `ar_mode`;
  - the launch-proven TapBubbles form of **two intent-filters** (MAIN+LAUNCHER, and MAIN+`com.rayneo.intent.category.AR_APP`), with `resizeableActivity="false"`, `singleTask` and landscape;
  - permissions INTERNET, ACCESS_WIFI_STATE, ACCESS_NETWORK_STATE and MODIFY_AUDIO_SETTINGS only, so there are no runtime prompts.

```
app/src/main/java/com/tropicalstream/hammerklavier/
  HammerklavierApp.kt  MainActivity.kt  AppController.kt
  contract/ (frozen day 0)   contract/stub/
  midi/  engine/  dsp/  clock/  mech/  geom/  bank/  library/  ui/model/      ← pure, JVM-tested
  audio/  gl/  gl/shader/  gl/scene/  ui/  input/  net/  system/            ← Android
app/src/test/java/com/tropicalstream/hammerklavier/...                       ← JUnit 4
app/src/main/assets/{banks,midi,licenses,textures?,catalogue.json,companion.html}
tools/ (Python 3 + ffmpeg + numpy; shell scripts)
```

### 2.2 Threads

| Thread | Priority | Owns | May not |
|---|---|---|---|
| **Main (UI)** | normal | `MainActivity`, `AppController`, gesture engine, overlays, Choreographer pacing, thermal governor, CONTROL receiver, stats polling at 4 Hz | block on I/O; touch GL or audio objects directly |
| **Audio** `HK-Audio` | `THREAD_PRIORITY_URGENT_AUDIO` | `AudioTrack`, `EngineCore` (sequencer, voices, pedals, DSP), block records for `AudioClock` | allocate, lock, log per block, or call anything that can block except `AudioTrack.write` |
| **GL** (GLSurfaceView) | normal | `StereoRenderer`, meshes, shaders, `MechanicsEvaluator`, `GlyphBoard` | allocate per frame; read mutable state that another thread writes, except through volatile or seqlock snapshots |
| **Loader** (single-thread executor) | `THREAD_PRIORITY_BACKGROUND` | bank decode, mmap, MIDI parse, adaptation, catalogue load, import validation | run two decodes at once (the Qualcomm FLAC decoder allows only 2 instances; Opus is decoded one file at a time as well) |
| **PageWarmer** | `THREAD_PRIORITY_BACKGROUND + 1` | touching mapped pages ahead of active voices | write shared state |
| **NanoHTTPD** (daemon) | normal | request parsing | anything except validation and a file copy; results are posted to the main thread |
| **Sensor** (`GazeCamera` on the main looper) | – | IMU yaw and pitch (volatile) | – |

**Cross-thread traffic is limited to these four channels:**
1. **Main → Audio:** `CommandRing`, a lock-free SPSC ring of 256 slots `(type:Int, l:Long, f:Float, obj:Any?)`. Carrying an object reference allocates nothing. If the ring is full the command is dropped and a counter is bumped. That should never happen, because commands arrive at human rates.
2. **Audio → GL:** `AudioClock`, a 64-entry block-record ring guarded by a seqlock, plus the latest `(framePosition, nanoTime)` from `getTimestamp`.
3. **Audio → Main:** `EngineStats`. The audio thread writes plain fields each block. The main thread copies them at 4 Hz under a version counter. Values may tear by one block, which is fine for a HUD.
4. **Main → GL:** `queueEvent {}` for structural changes (instrument model, performance binding, view), and `@Volatile` scalars (quality, floor level, fov, lead).

**Invariant:** the *same immutable `Performance` object* and the same generation number go to both the engine and the renderer. Both therefore apply the same adaptation, and the renderer only trusts clock samples whose generation matches its own.

### 2.3 Data flow

```
 assets/midi/**.mid ─┐                 (Loader thread)
 files/Scores/*.mid ─┴─ Library.readBytes ─► SmfParser ─► PerformanceBuilder ─► InstrumentAdapter ─► Performance(gen N)
                                                                                                   │
                     ┌──────────── AppController.startPlayback(perf, gen) ◄────────────────────────┘
                     ├─► EngineControl.load(perf, gen, startFrame, autoplay) ─CommandRing─► EngineCore (Audio thread)
                     └─► StereoRenderer.setPerformance(perf, gen) ─queueEvent─► MechanicsEvaluator.bind (GL thread)

 assets/banks/<id>/bank.json + s/*.opus ─► BankLoader ─► OpusDecoder ─► files/pcm/<id>-<sha8>.pcm (+ .idx.json, .ok)
                                                          └─► SampleStore.map ─► SampleBankView ─► EngineControl.setBank

 EngineCore.render(256) ─► float stereo ─► AudioTrack.write(BLOCKING)
        └─► AudioClock.publishBlock(outFrame, scoreFrame, tempo, playing, gen)   every block
        └─► AudioClock.publishTimestamp(framePosition, nanoTime)                  every 16 blocks (≈85 ms)
 GL frame at N ns ─► AudioClock.sample(N + lead) ─► MechanicsEvaluator.evaluate(score, tempo) ─► MechanicsFrame ─► uniforms
```

### 2.4 The timing model

**One master clock: the audio output.**

- **Frame counting.** The audio thread owns `outputFrame`, the number of frames written since `play()`. Each block starts at `F_k = outputFrame`. At that instant the sequencer's score position is `S_k` (score frames at 48 kHz on the tempo-1 timeline, including a 300 ms pre-roll) and the tempo is `τ_k`.
- **Sample-accurate events.** During block *k* the sequencer advances the score from `S_k` to `S_k + 256·τ_k`. Every event with `S_k ≤ evFrame < S_k + 256·τ_k` fires at output offset `⌊(evFrame − S_k)/τ_k⌋` inside the block. Note-ons start their voice at exactly that sample. Pedal changes set their targets at that sample; damping multipliers update per block, which is 5.3 ms granularity and well under the damper's own 15–20 ms. Nothing depends on wall-clock time on this path.
- **What is heard, and when.** Every 16 blocks the audio thread calls `AudioTrack.getTimestamp(ts)`, which gives `(framePosition P, nanoTime T)`: frame P left the DAC at time T. `AudioClock` stores it. Until the first valid timestamp arrives, it estimates `P = outputFrame − bufferSizeInFrames − halBurst·2` and `T = System.nanoTime()` at write time.
- **The renderer.** At wall time `N`, with display lead `L` (default 30 ms; CONTROL `--ei lead`), it computes:
  - the heard output frame `H = P + (N + L − T)·48000/1e9`;
  - the block record *k* with `F_k ≤ H < F_{k+1}` (a 64-entry ring covers 341 ms, far more than the ~21–43 ms of buffer);
  - the score position `s = S_k + (H − F_k)·τ_k` if that block was playing, or `S_k` if it was paused.

  The result goes into a `ClockSample{scoreFrame, tempo, playing, generation}`. The ring is written with a per-entry seqlock (version odd while writing). The reader retries at most 3 times and otherwise uses the previous sample. There are no locks and no allocation.
- **Lookahead, from the physics.** The renderer does not receive pushed key states. It calls `MechanicsEvaluator.evaluate(s, τ)`, which computes each part's pose from the *whole* immutable `Performance`:
  - For the grand, the key of a note at `on` with velocity *v* starts moving at `on − tt(v)·τ·48000`, where `tt` runs from 230 ms at v=20 to 86 ms at v=64 and 20 ms at v≥110.
  - Travel times are physical, so they are wall-clock durations. The evaluator therefore scales them by the current tempo when mapping them onto score time.
  - Because the score is fully known, every key is already moving before its sound starts.
  - Seeking, tempo changes and pause need no special cases beyond `bind()` resetting the per-key cursors.
  - The 300 ms pre-roll gives the first notes of a piece room to travel.
- **Why an analytic model beats pushing states from the audio thread:**
  - Nothing overflows.
  - Seek is correct by construction.
  - Every frame is deterministic and JVM-testable: `evaluate(t)` after a scrub equals a fresh `evaluate(t)`.
  - Audio and visuals share the physical constants that both need, through `contract/PedalMotion.kt` and `InstrumentProfile.damperDelayMs`.
- **Pause.** The engine lifts all keys, fading voices through the normal damper path, and holds the score position. The renderer blends every part to its rest pose over 60 ms, driven by `playing=false` in the clock sample.

### 2.5 Frozen contracts (WP0 writes these on day 0; changes go through the integrator)

```kotlin
// contract/Constants.kt
package com.tropicalstream.hammerklavier.contract
const val SAMPLE_RATE = 48_000
const val BLOCK_FRAMES = 256
const val PRE_ROLL_FRAMES = 14_400L        // 300 ms of silence before the first event, so keys can travel
const val KEY_SLOTS = 128

// contract/Ids.kt
enum class InstrumentId(val key: String) { GRAND("grand"), UPRIGHT("upright"), HARPSICHORD("harpsichord") }
enum class ViewId { PLAYER, ACTION, INSIDE, HALL }
enum class VenueLevel { SALON, STAGE, INSTRUMENT, PASSTHROUGH }
enum class TemperamentId { EQUAL, WERCKMEISTER_III, VALLOTTI, YOUNG_II, KIRNBERGER_III, KELLNER, LEHMAN, MEANTONE_QUARTER }
enum class Registration { EIGHT, FOUR, EIGHT_FOUR, LUTE }
enum class ActionType { GRAND, UPRIGHT, HARPSICHORD }
enum class OutputRoute { SPEAKER, HEADSET }

// contract/InstrumentProfile.kt — static facts shared by audio, mechanics, rendering and UI
data class InstrumentProfile(
    val id: InstrumentId, val displayName: String, val action: ActionType,
    val lowKey: Int, val highKey: Int,            // playable compass (MIDI)
    val lastDamper: Int,                          // keys above this have no damper
    val hasSustain: Boolean, val hasSostenuto: Boolean, val hasSoft: Boolean,
    val velocityToGain: Boolean,                  // false for the harpsichord
    val keyDipMm: Float, val travelScale: Float,  // × grand travel-time fits
    val keyReturnMs: Float, val damperDelayMs: Float,
    val defaultPitchHz: Float, val defaultTemperament: TemperamentId,
)
object Instruments {
    val GRAND = InstrumentProfile(InstrumentId.GRAND, "Grand piano", ActionType.GRAND, 21, 108, 88,
        true, true, true, true, 10.16f, 1.0f, 35f, 15f, 440f, TemperamentId.EQUAL)
    val UPRIGHT = InstrumentProfile(InstrumentId.UPRIGHT, "Upright piano", ActionType.UPRIGHT, 21, 108, 90,
        true, false, true, true, 10.0f, 1.05f, 50f, 20f, 440f, TemperamentId.EQUAL)
    val HARPSICHORD = InstrumentProfile(InstrumentId.HARPSICHORD, "Bach harpsichord", ActionType.HARPSICHORD, 29, 89, 89,
        false, false, false, false, 7.0f, 0.35f, 25f, 20f, 415f, TemperamentId.WERCKMEISTER_III)
    fun of(id: InstrumentId): InstrumentProfile = when (id) {
        InstrumentId.GRAND -> GRAND; InstrumentId.UPRIGHT -> UPRIGHT; InstrumentId.HARPSICHORD -> HARPSICHORD }
}

// contract/PedalMotion.kt — ONE implementation used by both the audio PedalModel and the visual evaluator
object PedalMotion {
    const val FULL_TRAVEL_MS = 70f                                   // 0→1 slew (visual research §2.7)
    fun slew(current: Float, target: Float, dtMs: Float): Float      // rate-limited toward target
    fun damperLiftByPedal(p: Float): Float                           // ((p-0.33)/0.67).coerceIn(0,1)  [V: 1/3 onset]
    fun dampingFactor(p: Float): Float                               // 1 - smoothstep(0.33, 0.55, p)   1 = fully damping
    const val NOISE_THRESHOLD = 0.33f                                // crossing fires pedal-down / pedal-up noise
}

// contract/Performance.kt — immutable, sample-indexed score (48 kHz frames on the tempo-1 timeline, incl. pre-roll)
object EvKind { const val NOTE_OFF: Byte = 1; const val SUSTAIN: Byte = 2; const val SOSTENUTO: Byte = 3
                const val SOFT: Byte = 4; const val NOTE_ON: Byte = 5; const val END: Byte = 6 }   // also the tie order
class PerformanceMeta(val title: String?, val sha1: String, val lowKey: Int, val highKey: Int, val noteCount: Int,
    val hasSustain: Boolean, val hasSostenuto: Boolean, val hasSoft: Boolean,
    val durationFrames: Long, val warnings: List<String>)
class Performance(
    val evFrame: LongArray, val evKind: ByteArray, val evKey: ByteArray, val evValue: ByteArray,   // sorted (frame, kind)
    val noteKey: ByteArray, val noteOn: LongArray, val noteOff: LongArray, val noteVel: ByteArray,
    val noteTrack: ByteArray,                                                                        // sorted by noteOn
    val meta: PerformanceMeta,
) {
    val eventCount: Int get() = evFrame.size
    val noteCount: Int get() = noteOn.size
    val spansByKey: Array<IntArray>                    // per key: note indices sorted by on; never overlapping
    fun firstEventAtOrAfter(frame: Long): Int          // binary search
    fun controllerAt(kind: Byte, frame: Long): Int     // last SUSTAIN/SOSTENUTO/SOFT value ≤ frame, else 0
}

// contract/SampleBank.kt
enum class SampleKind { SUSTAIN, RELEASE, PEDAL_DOWN, PEDAL_UP }
enum class Register { NORMAL, EIGHT, FOUR, LUTE }
class SampleInfo(val id: Int, val file: String, val frames: Int, val rootKey: Int, val tuneCents: Float,
    val gainDb: Float, val kind: SampleKind, val layer: Int, val register: Register)
class LayerInfo(val index: Int, val loVel: Int, val hiVel: Int, val gainDb: Float)
class BankIndex(val instrument: InstrumentId, val schema: Int, val sha: String, val sampleRate: Int,
    val pitchRefHz: Float, val veltrack: Float, val crossfadeVel: Int, val lastDamper: Int,
    val layers: List<LayerInfo>, val samples: List<SampleInfo>, val source: String, val credit: String)
interface SampleSource {                               // interleaved stereo PCM16; AUDIO THREAD ONLY
    val frames: Int
    fun read(startFrame: Int, dst: ShortArray, frameCount: Int)   // zero-fills outside [0, frames)
}
class SampleBankView(val index: BankIndex, val sources: Array<SampleSource>, val isStub: Boolean, val note: String)

// contract/Engine.kt
data class EngineOptions(val maxStreams: Int = 64, val reverbSend: Float = 0.20f, val distanceGainDb: Float = 0f,
    val resonance: Boolean = true, val releaseSamples: Boolean = true, val pedalNoise: Boolean = true,
    val registration: Registration = Registration.EIGHT_FOUR, val masterGainDb: Float = 0f,
    val route: OutputRoute = OutputRoute.SPEAKER, val practiceMute: Boolean = false)
class EngineStats { var activeStreams = 0; var peakStreams = 0; var stolen = 0L; var renderNsAvg = 0L
    var renderNsMax = 0L; var underruns = 0; var bufferFrames = 0; var outputFrame = 0L; var scoreFrame = 0.0
    var playing = false; var generation = -1; var endedGeneration = -1; var bankIsStub = false; var droppedCommands = 0 }
class ClockSample { var valid = false; var scoreFrame = 0.0; var tempo = 1f; var playing = false; var generation = -1 }
interface AudioClockReader { fun sample(nowNanos: Long, out: ClockSample) }     // GL thread; lock-free
class CoreClockState { var scoreFrame = 0.0; var tempo = 1f; var playing = false; var generation = -1 }
interface EngineCoreApi {                               // AUDIO THREAD ONLY (implemented by engine.EngineCore)
    fun bindBank(bank: SampleBankView, profile: InstrumentProfile)
    fun load(perf: Performance, generation: Int, startFrame: Long, autoplay: Boolean)
    fun play(); fun pause(); fun seek(scoreFrame: Long); fun setTempo(factor: Float)
    fun setTuning(pitchHz: Float, temperament: TemperamentId); fun setOptions(options: EngineOptions)
    fun render(outL: FloatArray, outR: FloatArray, frames: Int)          // the only per-block call
    fun clockState(out: CoreClockState)                                 // state at the START of the next block
    fun fillStats(s: EngineStats)
}
interface EngineControl {                               // MAIN THREAD (implemented by audio.AudioOutput)
    val clock: AudioClockReader
    fun start(); fun stop()                             // onResume / onPause
    fun setBank(bank: SampleBankView, profile: InstrumentProfile)
    fun load(perf: Performance, generation: Int, startFrame: Long, autoplay: Boolean)
    fun play(); fun pause(); fun seek(scoreFrame: Long); fun setTempo(factor: Float)
    fun setTuning(pitchHz: Float, temperament: TemperamentId); fun setOptions(options: EngineOptions)
    fun readStats(into: EngineStats)
}

// contract/Mechanics.kt — normalised poses; drawables convert to angles/millimetres with their geometry
class MechanicsFrame {
    val keyDepth = FloatArray(KEY_SLOTS)      // 0 rest .. 1 full dip (incl. aftertouch)
    val hammer = FloatArray(KEY_SLOTS)        // 0 rest .. 1 at string (grand/upright); harpsichord jack rise
    val escaped = FloatArray(KEY_SLOTS)       // 0 jack under knuckle .. 1 escaped (action view)
    val damper = FloatArray(KEY_SLOTS)        // 0 on string .. 1 fully lifted
    val tongue = FloatArray(KEY_SLOTS)        // harpsichord tongue swing 0..1
    val stringAmp = FloatArray(KEY_SLOTS)     // 0..1 visual vibration amplitude
    val strikeFlash = FloatArray(KEY_SLOTS)   // 0..1 pulse at contact/pluck, fades in 150 ms
    var sustain = 0f; var sostenuto = 0f; var soft = 0f; var muteRail = 0f   // pedal / rail travel 0..1
    var melodyKey = 60; var scoreSec = 0.0; var playing = false; var restBlend = 0f
}
interface MechanicsEvaluator {                          // GL THREAD
    fun bind(perf: Performance?, profile: InstrumentProfile, registration: Registration)
    fun evaluate(sample: ClockSample, nowNanos: Long, out: MechanicsFrame)
}

// contract/Library.kt
data class Category(val id: String, val title: String, val order: Int)
data class Movement(val id: String, val workId: String, val title: String, val asset: String?, val file: String?,
    val sha1: String, val durationSec: Float, val lowKey: Int, val highKey: Int,
    val hasSustain: Boolean, val hasSoft: Boolean, val hasSostenuto: Boolean)
data class Work(val id: String, val composer: String, val title: String, val catalogueNo: String?, val year: Int?,
    val era: String, val categoryId: String, val defaultInstrument: InstrumentId, val altInstruments: List<InstrumentId>,
    val tier: String, val performanceType: String, val credit: String, val licence: String, val licenceUrl: String?,
    val movements: List<Movement>, val imported: Boolean = false)
data class Catalogue(val schema: Int, val categories: List<Category>, val works: List<Work>, val startHere: List<String>)
interface Library {
    fun catalogue(): Catalogue                                 // bundled + imported, cached
    fun find(movementId: String): Pair<Work, Movement>?
    fun readBytes(m: Movement): ByteArray                      // loader thread
    fun refreshImports(): Int                                  // loader thread; returns count
}

// contract/UiAction.kt — output of ui.model.InputRouter, executed only by AppController
enum class Gesture { TAP, DOUBLE, TRIPLE, FWD, BACK, UP, DOWN }
enum class UiContext { TITLE, CALIBRATION, PLAY, LIBRARY, MENU, PANEL }
sealed interface UiAction {
    data object Enter : UiAction; data object PlayPause : UiAction; data object Recenter : UiAction
    data class CycleView(val dir: Int) : UiAction; data object ToggleFraming : UiAction
    data object OpenMenu : UiAction; data object OpenLibrary : UiAction; data object Close : UiAction
    data class Move(val dir: Int) : UiAction; data class Adjust(val dir: Int) : UiAction; data object Activate : UiAction
    data object Back : UiAction; data class Page(val dir: Int) : UiAction
}
```

**Stubs** (`contract/stub/`, also written on day 0, so every package can run alone):

| Stub | Stands in for | What it does |
|---|---|---|
| `StubPerformances` | a parsed file | `scale()` (C major, velocities 20→127), `pedalStudy()` (CC64 up and down, a sostenuto case), `restrike()`, `avSync()` (a note every 1 s alternating 60/72 at velocity 127) |
| `FakeClock` | `AudioClock` | Wall time since `start()`, tempo 1 |
| `NullEngine` | `AudioOutput` | Accepts every call; its clock is a `FakeClock` |
| `SineCore` | `EngineCore` | An `EngineCoreApi` that beeps a 440 Hz tone per note-on. WP4 tests with it before WP2 lands |
| `StubMechanics` | the evaluator | Keys ripple in a travelling wave. WP7, WP8 and WP9 draw with it before WP6 lands |
| `StubLibrary` | `LibraryRepository` | Serves the four stub performances |

### 2.6 File catalogue (single responsibility · public API · thread · owner)

Package root `com.tropicalstream.hammerklavier`. The thread column abbreviations: **A** audio, **G** GL, **M** main, **L** loader, **any** thread-safe/pure.

**Root and system (WP0)**

| File | Responsibility | Public API | Thr |
|---|---|---|---|
| `HammerklavierApp.kt` | Uncaught-exception handler writes `files/crash.txt`; it is shown in the status line on the next launch | `class HammerklavierApp : Application` | M |
| `MainActivity.kt` | Lifecycle, view tree (GL surface + SBS overlay), feeds gestures from all three dispatchers | `dispatchKeyEvent`, `dispatchTouchEvent`, `dispatchGenericMotionEvent` → `gestures`; `onResume`/`onPause` fan-out | M |
| `AppController.kt` | App state machine and the glue: the current piece, instrument, view, overlays; routes `UiAction`s; publishes a performance to the engine and renderer; polls stats; auto-advance; thermal → options | `fun handle(a: UiAction)`, `fun play(movementId: String, instrument: InstrumentId? = null, startMs: Long = 0)`, `fun setInstrument(id)`, `fun onThermal(level: Int)`, `fun onImport(result)` | M |
| `system/ThermalGovernor.kt` | Battery temperature + thermal status → Q0–Q3 with hysteresis (MathCosmos numbers, plus Q3) | `class ThermalGovernor(ctx, onLevel: (Int) -> Unit) { fun start(); fun stop(); var forced: Int? }` | M |
| `system/DebugControl.kt` | Parses the `…hammerklavier.CONTROL` broadcast into controller calls (§8.2) | `class DebugControl(ctx, controller) { fun register(); fun unregister() }` | M |
| `system/PerfProbe.kt` | Frame-hitch detector (>120 ms), 10 s `HKPerf` log line, audio-thread majflt via `/proc/self/task/<tid>/stat` | `object PerfProbe { fun onFrame(nanos: Long); fun logLine(stats: EngineStats, fps: Float, draws: Int, q: Int, battTenths: Int) }` | G/M |
| `system/Prefs.kt` | SharedPreferences: last piece, position, instrument per work, view, floor, tuning, venue, toggles | typed getters and setters | M |
| `system/SelfTest.kt` | `--ez selftest true`: runs the checks in §8.3 and logs `HKSelfTest PASS/FAIL` lines | `fun run(controller): Unit` | L |
| `input/TrackpadGestureEngine.kt` | Copied from WanderQuest, plus a left-arm key filter (`cyttsp6` by name) | `onTap/onDoubleTap/onTripleTap`, `onSwipeHorizontal(+1 fwd/−1 back)`, `onSwipeVertical(−1 up/+1 down)`, `onKeyEvent`, `onTouchEvent`, `onGenericMotion`, `release()` | M |
| `ui/BinocularSbsLayout.kt` | Copied verbatim: the single child drawn twice | `var sbsEnabled` | M |
| `ui/CalibrationCard.kt` | Presence-floor test card (16 swatches) | `class CalibrationCard(ctx) : View { var level: Int; fun step(d: Int) }` | M |
| `gl/HkSurfaceView.kt` | GLSurfaceView with an EGL chooser (888/d24/s8/MSAA4 → 888/d24/s8 → 888/d16 → any); Choreographer pacing (divider 2/3/4; Q3 = stop); idle 15 fps when nothing plays | `fun setQuality(q: Int)`, `fun setIdle(idle: Boolean)`, `val eglInfo: EglInfo` (stencil bits, samples) | M/G |

**MIDI (WP1, pure)**

| File | Responsibility | Public API | Thr |
|---|---|---|---|
| `midi/SmfModel.kt` | Raw parse output | `class SmfFile(format, division: Division, tracks: List<SmfTrack>, warnings)`; `sealed class Division { Ppq(tpq), Smpte(fps, tpf) }`; `class SmfTrack(name: String?, n: Int, tick: LongArray, kind: ByteArray, chan: ByteArray, a: ByteArray, b: ByteArray, tempoUs: IntArray)`; `sealed class SmfResult { Ok(file), Failed(error: SmfError, detail) }`; `enum class SmfError { NOT_MIDI, TRUNCATED_HEADER, UNSUPPORTED_FORMAT, NO_TRACKS, TOO_LARGE, TOO_MANY_EVENTS, NO_NOTES }`; `data class SmfLimits(maxBytes = 8 shl 20, maxEvents = 2_000_000, maxTracks = 256)` | any |
| `midi/SmfParser.kt` | Bytes → `SmfFile`, tolerant and bounded | `object SmfParser { fun parse(bytes: ByteArray, limits: SmfLimits = SmfLimits()): SmfResult }` | L |
| `midi/TempoMap.kt` | Tick ↔ µs (piecewise, merged from all tracks), SMPTE too | `class TempoMap { companion object { fun of(f: SmfFile): TempoMap }; fun tickToMicros(t: Long): Long; fun microsToTick(us: Long): Long; val changeCount: Int }` | any |
| `midi/PerformanceBuilder.kt` | Merge tracks, pair notes, extract pedals, convert to frames, sort, gather metadata | `object PerformanceBuilder { fun build(f: SmfFile, sha1: String, o: BuildOptions = BuildOptions()): BuildResult; fun parseAndBuild(bytes: ByteArray, sha1: String): BuildResult }`; `data class BuildOptions(val dropDrums: Boolean = true, val tailFrames: Long = 96_000)`; `sealed class BuildResult { Ok(perf), Failed(reason: String) }` | L |
| `midi/InstrumentAdapter.kt` | Per-instrument transform: compass folding, harpsichord legato, pedal masking | `object InstrumentAdapter { fun adapt(p: Performance, profile: InstrumentProfile, o: AdaptOptions = AdaptOptions()): Performance }`; `data class AdaptOptions(val harpsichordLegato: Boolean = true, val legatoCapMs: Int = 1500)` | L |

**Engine (WP2, pure; everything except `StubBank` runs on the audio thread)**

| File | Responsibility | Public API |
|---|---|---|
| `engine/EngineCore.kt` | Implements `EngineCoreApi`: sequencer → voices → buses → DSP chain | `class EngineCore(sampleRate: Int = SAMPLE_RATE) : EngineCoreApi` |
| `engine/CommandRing.kt` | Lock-free SPSC command ring (main → audio) | `class CommandRing(capacity: Int = 256) { fun offer(type: Int, l: Long = 0, f: Float = 0f, obj: Any? = null): Boolean; fun drain(h: CommandHandler): Int }`; `fun interface CommandHandler { fun on(type: Int, l: Long, f: Float, obj: Any?) }` |
| `engine/Sequencer.kt` | Event cursor; sample-accurate dispatch within a block | `class Sequencer { fun bind(p: Performance?); fun seek(frame: Long); fun advance(frames: Int, tempo: Float, sink: EventSink): Boolean; val position: Double }`; `interface EventSink { fun noteOn(key: Int, vel: Int, offset: Int); fun noteOff(key: Int, offset: Int); fun controller(kind: Byte, value: Int, offset: Int) }` |
| `engine/Voice.kt` | One sounding stream, or two while crossfading layers: position, rate, gain ramp, state, optional one-pole | `internal class Voice { fun start(...); fun render(mixL, mixR, frames, scratch); fun setState(s: Int); val finished: Boolean }` |
| `engine/VoicePool.kt` | Fixed pool (cap + 8 fade slots), per-key voice lists, restrike fades | `class VoicePool(capacity: Int = 72) { var cap: Int; fun allocate(key: Int, prio: Int): Voice; fun fadeKey(key: Int, tauMs: Float); fun fadeAll(tauMs: Float); fun forEachActive(f: VoiceVisitor); val active: Int }` |
| `engine/StealPolicy.kt` | Chooses the victim when the pool is full (pure function) | `object StealPolicy { fun victim(state: IntArray, gain: FloatArray, age: IntArray, n: Int): Int }` |
| `engine/PedalModel.kt` | Pedal travel (via `PedalMotion`), key-down counts, sostenuto latch, per-key damping strength, noise-crossing triggers | `class PedalModel { fun reset(profile); fun keyDown(k: Int); fun keyUp(k: Int); fun controller(kind: Byte, v: Int); fun advance(ms: Float); fun dampingStrength(k: Int): Float; fun pollNoise(): Int; val softOn: Boolean }` |
| `engine/VelocityMap.kt` | Per-velocity tables: layer A/B, crossfade weight, gain | `class VelocityMap(index: BankIndex, profile: InstrumentProfile) { fun layerA(v: Int): Int; fun layerB(v: Int): Int; fun weightB(v: Int): Float; fun gain(v: Int): Float }` |
| `engine/Temperaments.kt` | Cents tables (§3.6) | `object Temperaments { fun offsets(id: TemperamentId): FloatArray }` |
| `engine/TuningTable.kt` | Key → sample and rate, chosen by target frequency, for each layer and register | `class TuningTable(index: BankIndex) { fun retune(pitchHz: Float, t: TemperamentId); fun sample(layer: Int, reg: Register, key: Int): Int; fun rate(layer: Int, reg: Register, key: Int): Float }` |
| `engine/DecayTables.kt` | Per-block multipliers: damper decay for each key × 17 strength levels; fade taus | `class DecayTables(profile: InstrumentProfile, blockFrames: Int) { fun damper(key: Int, strengthQ: Int): Float; fun fade(tauMs: Float): Float }` |
| `engine/StubBank.kt` | Synthesizes a stand-in bank in memory (additive decaying partials) | `object StubBank { fun synthesize(profile: InstrumentProfile): SampleBankView }` |
| `engine/ArraySampleSource.kt` | A `SampleSource` backed by a `ShortArray` (stub bank and tests) | `class ArraySampleSource(val pcm: ShortArray) : SampleSource` |

**DSP (WP3, pure, audio thread)**

| File | Public API |
|---|---|
| `dsp/Fdn8Reverb.kt` | `class Fdn8Reverb(sr: Int) { fun configure(rt60Mid: Float = 1.35f, rt60High: Float = 0.9f, preDelayMs: Float = 12f); fun process(inL: FloatArray, inR: FloatArray, outL: FloatArray, outR: FloatArray, n: Int, send: Float); fun clear() }` (adds into out) |
| `dsp/Limiter.kt` | `class Limiter(sr: Int, threshold: Float = 0.92f, attackMs: Float = 0.4f, releaseMs: Float = 150f) { fun process(l: FloatArray, r: FloatArray, n: Int); fun reset() }`; `fun softClip(x: Float): Float` |
| `dsp/Biquad.kt` | `class Biquad { fun highPass(fc: Float, q: Float, sr: Int); fun lowPass(...); fun peaking(fc: Float, q: Float, dB: Float, sr: Int); fun lowShelf(...); fun process(x: FloatArray, n: Int); fun reset() }` |
| `dsp/OnePole.kt` | `class OnePole { fun lowPass(fc: Float, sr: Int); fun tick(x: Float): Float; fun reset() }` |
| `dsp/ResonatorBank.kt` | `class ResonatorBank(sr: Int) { fun retune(freqHz: FloatArray); fun process(mono: FloatArray, outL: FloatArray, outR: FloatArray, n: Int, send: Float); var active: Boolean; fun clear() }` |
| `dsp/SpeakerVoicing.kt` | `class SpeakerVoicing(sr: Int) { var route: OutputRoute; fun process(l: FloatArray, r: FloatArray, n: Int) }` |
| `dsp/DspTables.kt` | `object DspTables { fun dbToGain(db: Float): Float; val equalPowerCos: FloatArray; ... }` (precomputed; no runtime `pow`/`exp` in hot loops) |

**Clock, bank index and audio I/O (WP4)**

| File | Responsibility | Public API | Thr |
|---|---|---|---|
| `clock/AudioClock.kt` (pure) | Block-record ring + timestamp → `ClockSample` | `class AudioClock(sr: Int = SAMPLE_RATE) : AudioClockReader { fun publishBlock(outFrame: Long, st: CoreClockState); fun publishTimestamp(framePos: Long, nanoTime: Long); fun publishEstimate(outFrameWritten: Long, latencyFrames: Int, nowNanos: Long); override fun sample(nowNanos: Long, out: ClockSample) }` | A writes, G reads |
| `bank/BankIndexCodec.kt` (pure) | `bank.json` ↔ `BankIndex` (org.json) | `object BankIndexCodec { fun parse(json: String): BankIndex; fun validate(i: BankIndex): List<String> }` | L |
| `audio/AudioOutput.kt` | Implements `EngineControl`: owns the AudioTrack, the audio thread, the command ring, clock publishing, underrun counts, route detection | `class AudioOutput(ctx: Context, core: EngineCoreApi) : EngineControl` | M/A |
| `audio/LatencyTuner.kt` | Grows `bufferSizeInFrames` by one block when the underrun count rises, from 4 blocks up to 16 | `class LatencyTuner(track: AudioTrack, block: Int) { fun onBlock() }` | A |
| `audio/OpusDecoder.kt` | One asset → PCM16 stereo written at a file offset (MediaExtractor + `c2.android.opus.decoder` by name) | `class OpusDecoder { fun decode(afd: AssetFileDescriptor, out: FileChannel, outOffset: Long, expectedFrames: Int, skipFrames: Int): DecodeResult }` | L |
| `audio/DecoderProbe.kt` | Decodes `banks/probe.opus` (a click at frame 4800) and measures the decoder offset; the result is cached in prefs | `object DecoderProbe { fun measure(ctx): Int }` | L |
| `audio/SampleStore.kt` | Cache file layout, the `.ok` commit marker, mmap, and deleting stale caches | `class SampleStore(filesDir: File) { fun isCached(i: BankIndex): Boolean; fun cacheFile(i: BankIndex): File; fun open(i: BankIndex): SampleBankView }` | L |
| `audio/MappedSampleSource.kt` | A `SampleSource` over one `ShortBuffer` view of the map (bulk `get` = native memcpy) | `class MappedSampleSource(buf: ShortBuffer, offsetShorts: Int, frames: Int) : SampleSource` | A |
| `audio/PageWarmer.kt` | Pre-touches the first 250 ms of each sample, then runs every 50 ms to touch the next 500 ms ahead of each active voice | `class PageWarmer(bank: SampleBankView, positions: VoicePositions) { fun start(); fun stop() }` | own |
| `audio/BankLoader.kt` | Orchestrates index → cache check → decode with progress → map → view, and falls back through §3.15 | `class BankLoader(ctx, store: SampleStore, exec: Executor) { fun load(id: InstrumentId, progress: (Float) -> Unit, done: (BankLoadResult) -> Unit) }`; `sealed class BankLoadResult { Ready(bank, fromCache), Fallback(bank, reason) }` | L |
| `audio/AudioFocusGate.kt` | Requests `AUDIOFOCUS_GAIN` on play; pauses on loss; ducks to 30 % on CAN_DUCK | `class AudioFocusGate(ctx, onPause: () -> Unit, onDuck: (Float) -> Unit)` | M |
| `audio/OutputRouteWatcher.kt` | `AudioDeviceCallback`: built-in speaker vs wired or BT headset → `OutputRoute` | `class OutputRouteWatcher(ctx, onRoute: (OutputRoute) -> Unit)` | M |

**Mechanics (WP6, pure, GL thread)**

| File | Public API |
|---|---|
| `mech/Touch.kt` | `object Touch { fun hammerVelocity(v: Int): Float; fun travelMs(v: Int): Float; fun keyBottomRelMs(v: Int): Float; fun freeFlightMs(v: Int): Float }` (128-entry tables built from the Goebl fits) |
| `mech/ActionGeometry.kt` | `class ActionGeometry(blowMm, letOffMm, dropMm, checkMm, dipMm, pivotToFrontMm, ratio)`; `object ActionGeometries { val GRAND; val UPRIGHT; val HARPSICHORD }` |
| `mech/SpanKinematics.kt` | `class SpanKinematics(p: Performance, profile: InstrumentProfile) { val leadMs: FloatArray; val bottomMs: FloatArray; val flightMs: FloatArray; val hv: FloatArray; val struck: FloatArray }` (precomputed once per bind) |
| `mech/GrandActionModel.kt`, `UprightActionModel.kt`, `HarpsichordActionModel.kt` | `interface ActionModel { fun pose(key: Int, spanPrev: Int, spanCur: Int, tFrames: Double, tempo: Float, k: SpanKinematics, p: Performance, out: MechanicsFrame) }` |
| `mech/StringEnvelope.kt` | `object StringEnvelope { fun amp(ageSec: Float, hv: Float, key: Int, dampedAgeSec: Float): Float }` |
| `mech/MelodyTracker.kt` | `class MelodyTracker { fun update(frame: MechanicsFrame, dtSec: Float): Int }` (highest key struck in the last 0.5 s, held at least 1.5 s) |
| `mech/MechanicsEvaluatorImpl.kt` | `class MechanicsEvaluatorImpl : MechanicsEvaluator` (per-key cursors, pedal lanes via `PedalMotion`, sostenuto visual latch, rest blend) |

**Geometry (pure; WP7/8/9 by file) and GL (Android)**

| File | Owner | Public API / responsibility |
|---|---|---|
| `geom/MeshData.kt`, `geom/MeshBuilder.kt` | WP7 | `class MeshData(val v: FloatArray, val stride: Int, val idx: ShortArray?)`; the builder offers `box`, `extrude`, `ribbon`, `lathe`, `quad`, and per-vertex `slot` + one-hot `sel` for skinning |
| `geom/KeyboardGeometry.kt` | WP7 | `class KeyboardGeometry(profile, octaveMm = 164.5f) { fun keyX(k: Int): Float; fun isBlack(k: Int): Boolean; fun build(): MeshData }` (equal 13.71 mm back slots, 23.5 mm fronts, analytic bevel UVs) |
| `geom/GrandCaseGeometry.kt`, `geom/PedalGeometry.kt` | WP7 | Catmull-Rom plan outline → rim, lid, legs, lyre, plate, soundboard; 3 pedals |
| `geom/CameraMath.kt`, `geom/ViewPoses.kt` | WP7 | `fun offAxis(out, fovY, aspect, near, far, eyeShift, convergence)`, `lookAt`; `object ViewPoses { fun pose(v: ViewId, framing: Int, inst: InstrumentId, xCut: Float): Pose }` (the §5.5 table) |
| `geom/GrandActionGeometry.kt`, `UprightCaseGeometry.kt`, `UprightActionGeometry.kt`, `HarpsichordCaseGeometry.kt`, `JackGeometry.kt`, `StringsGeometry.kt` | WP8 | Action parts, cases, 228 strings (the grand's single, bichord and trichord split), 180 jacks |
| `geom/RoomGeometry.kt`, `geom/ChandelierGeometry.kt`, `geom/FlameLayout.kt` | WP9 | Konzertzimmer shell, trellis ribbons, cove cartouches, mirrors, windows, chairs; about 50 flame positions |
| `gl/StereoRenderer.kt` | WP7 | `class StereoRenderer(ctx, clock: AudioClockReader, mech: MechanicsEvaluator) : GLSurfaceView.Renderer { fun setView(v: ViewId); fun setFraming(i: Int); fun setInstrument(id: InstrumentId, reg: Registration); fun setPerformance(p: Performance?, gen: Int); fun setVenue(l: VenueLevel); @Volatile var quality; var floorLevel; var leadMs; var fovOverride; var ipdScale; var stereo; var avSyncFlash: Boolean; fun counters(): RenderCounters }` |
| `gl/StereoRig.kt` | WP7 | Per-eye view and projection (off-axis; IPD 63 mm × per-view scale; zero parallax per view) plus gaze yaw and pitch |
| `gl/CameraDirector.kt` | WP7 | View state, dip transition, follow spring (ω = 6 rad/s, ±2-semitone dead band, 0.6 m/s cap), Action cut spring (ω = 4) |
| `gl/GlKit.kt` | WP7 | `makeVbo`, `compileProgram` (with `require` and the info log), `DynMesh` (from MathCosmos), draw counter, `FRAG_PRECISION` header |
| `gl/Palette.kt` | WP7 | The visual research §3.5 tokens as float triplets: the single source of colour |
| `gl/LightProbe.kt` | WP7 | Bakes a 128×64 equirect reflection texture from flame and gilt positions |
| `gl/GlyphBoard.kt`, `gl/GazeCamera.kt` | WP7 | Copied verbatim from MathCosmos |
| `gl/shader/SkinnedPartShader.kt` | WP7 | Keys, hammers, dampers, jacks: uniform-array skinning (`uState[22]` vec4 + one-hot), `uClip` plane, presence floor, Fresnel rim, candle speculars, probe reflection |
| `gl/shader/LacquerShader.kt`, `ColorShader.kt` | WP7 | Case surfaces; unlit lines and quads |
| `gl/shader/StringShader.kt` | WP8 | Spindle ribbons: screen-space width, at least 1.2 px, `uAmp[22]` |
| `gl/shader/GiltShader.kt`, `FlameShader.kt` | WP9 | Emissive gilt ribbons and decals; additive flame, halo and sparkle sprites |
| `gl/scene/InstrumentDrawable.kt` | WP7 | `interface InstrumentDrawable { fun build(); fun update(f: MechanicsFrame); fun draw(eye: EyeCtx, pass: Pass); fun release() }`; `interface PartDrawable` (the same, for sub-assemblies) |
| `gl/scene/KeyboardDrawable.kt`, `PedalDrawable.kt`, `GrandDrawable.kt`, `SceneList.kt` | WP7 | The grand is composed of keyboard + case + pedals + an optional `PartDrawable` for action and strings (WP8 supplies them). `SceneList` builds the draw list for each view and venue level |
| `gl/scene/GrandActionDrawable.kt`, `UprightDrawable.kt`, `UprightActionDrawable.kt`, `HarpsichordDrawable.kt`, `JackDrawable.kt`, `StringsDrawable.kt` | WP8 | Implement `PartDrawable` / `InstrumentDrawable` |
| `gl/scene/VenueDrawable.kt`, `FlameField.kt`, `MirrorPass.kt`, `FloorPool.kt`, `CrystalSparkle.kt`, `OrnamentAtlas.kt` | WP9 | Room, 50 flames, stencil mirrors (off if the config has no stencil), floor pools, 160 crystals, and a rocaille atlas drawn on the device with `android.graphics.Path` (no texture assets) |

**Library, UI models and views (WP10), companion (WP11)**

| File | Owner | Public API / responsibility | Thr |
|---|---|---|---|
| `library/CatalogueCodec.kt` (pure) | WP10 | `object CatalogueCodec { fun parse(json: String): Catalogue; fun parseImports(json: String): List<Work>; fun writeImports(works: List<Work>): String }` | L |
| `library/ImportValidator.kt` (pure) | WP10 | `object ImportValidator { fun sanitize(name: String): String; fun check(bytes: ByteArray, name: String): ImportCheck }`; `sealed class ImportCheck { Accepted(meta: PerformanceMeta, title: String), Rejected(reason: String) }` (uses the WP1 parser + builder) | L/net |
| `library/ImportStore.kt` (pure, java.io) | WP10 | `class ImportStore(dirs: List<File>) { fun save(src: File, original: String): ImportOutcome; fun delete(id: String): Boolean; fun scan(): List<Work>; fun reject(f: File, why: String) }` (bytes copied verbatim; unique ` (n)` names; `index.json`) | L |
| `library/Sha1.kt` (pure) | WP10 | `fun sha1Hex(b: ByteArray): String`, `fun sha1Base32(b: ByteArray): String` | any |
| `library/LibraryRepository.kt` | WP10 | `class LibraryRepository(ctx, store: ImportStore) : Library` (asset `catalogue.json` + imports; `FileObserver`) | L |
| `ui/model/InputRouter.kt` (pure) | WP10 | `class InputRouter { var invertVertical: Boolean; fun route(g: Gesture, c: UiContext): UiAction? }` (the §1.3 table) | M |
| `ui/model/LibraryModel.kt` (pure) | WP10 | `class LibraryModel(cat: Catalogue) { val level: Int; val rows: List<Row>; var highlight: Int; fun move(d: Int); fun enter(): LibraryResult; fun back(): Boolean }` | M |
| `ui/model/MenuModel.kt` (pure) | WP10 | `class MenuModel(state: MenuState) { val items: List<MenuItem>; fun move(d: Int); fun adjust(d: Int): MenuEffect?; fun activate(): MenuEffect? }`; `sealed class MenuEffect` (SetInstrument, SetTempo, Seek, Restart, Next, Prev, SetTuning, SetVenue, SetRegistration, OpenPanel, Toggle…) | M |
| `ui/model/NowPlayingText.kt` (pure) | WP10 | Formats the card and status strings (off the GL thread, cached) | M |
| `ui/LibraryView.kt`, `MenuView.kt`, `HudView.kt`, `TitleCard.kt`, `ImportPanel.kt`, `CreditsView.kt`, `OverlayStyle.kt` | WP10 | Passive views drawing the models; no input handling of their own | M |
| `net/CompanionServer.kt` | WP11 | `class CompanionServer(port: Int = 19112, token: String, page: () -> String, api: CompanionApi) : NanoHTTPD`; `interface CompanionApi { fun library(): String; fun state(): String; fun upload(tmp: File, name: String): String; fun delete(id: String): Boolean; fun play(id: String, inst: String?): Boolean; fun transport(action: String, ms: Long?): Boolean }` | net→M |
| `net/NetInfo.kt` | WP11 | `object NetInfo { fun siteLocalIpv4(): String? }` (the TapVibe rule) | any |
| `assets/companion.html` | WP11 | The page (no `$` or backticks; the token placeholder is `%%HK_TOKEN%%`) | – |

---

## 3. The audio engine

### 3.1 Output

| Setting | Value | Why |
|---|---|---|
| Track | `AudioTrack.Builder`, `USAGE_MEDIA`/`CONTENT_TYPE_MUSIC`, `ENCODING_PCM_FLOAT`, 48 kHz stereo, `MODE_STREAM`, `PERFORMANCE_MODE_LOW_LATENCY` | The 48 kHz fast path, measured on the device. A build failure falls back to `PERFORMANCE_MODE_NONE` (try/catch). |
| Block | 256 frames (5.33 ms), `WRITE_BLOCKING` | The SpyHunt form, proven on the device |
| Buffer | Starts at 4 blocks rounded up to HAL bursts (192-frame bursts measured). `LatencyTuner` adds one block per new underrun, up to 16 | For sequenced playback, stability matters more than latency, and `getTimestamp` takes care of sync |
| Thread | `Thread.MAX_PRIORITY` + `Process.setThreadPriority(URGENT_AUDIO)`. **`play()` before priming with silence** | The SpyHunt rule: priming a LOW_LATENCY track before `play()` can hang the thread at MAX_PRIORITY |
| Lifecycle | `start()` in `onResume`, `stop()` in `onPause` (`join(600)`). All voices are killed on `start()`. The position is saved | Playing on while the display is off is a non-goal (§9) |
| Idle | After 2 s of silence with no voices, the core writes zeros and skips the DSP | Keeps idle CPU near zero without the risk of closing the track |
| Errors | An exception in `render` is caught; the block goes out as silence; the core is `reset()` once. A second failure stops audio and posts a status error | The app never crashes from the audio thread |

### 3.2 Sample format and the memory budget

- **In the APK:** Ogg Opus at 112 kb/s VBR stereo, 48 kHz, `-application audio`. The research estimates 18 MB at 96k and 24 MB at 128k for the grand, so about 21 MB at 112k. Plus a 4 KB `bank.json` per instrument.
- **In RAM:** PCM16 interleaved stereo in one cache file per instrument. Each sample starts on a 4 KB page boundary, which wastes about 0.5 MB. The file is mapped with `FileChannel.map(READ_ONLY)` as clean page-cache pages. On ART, direct ByteBuffers live in the Java heap, so the map is the only way to keep 300 MB of samples off the heap.

| Instrument | Audio seconds (trim model) | PCM cache on disk / mapped | Pre-touched heads (250 ms × samples) | Typical resident set while playing |
|---|---|---|---|---|
| Grand (6 layers + releases + pedals) | 1575 s | **302 MB** | 13 MB | 40–120 MB |
| Upright (2 layers + pp + releases + pedals) | 914 s | 175 MB | 8 MB | 30–80 MB |
| Harpsichord (8′ + 4′ + lute + releases) | 490 s | 94 MB | 8 MB | 20–50 MB |
| Stub bank | 36 s | 7 MB | 7 MB | 7 MB |

- **Only the active instrument is mapped.** The old view's references are dropped when the instrument changes. Its pages stay clean and reclaimable, so leaving them in the page cache costs nothing.
- **Heap target ≤ 96 MB** (the default growth limit is 192 MB; `largeHeap` is not needed):
  - performances: the Hammerklavier finale is about 10k notes, under 1 MB;
  - mechanics tables;
  - mesh float arrays before upload, ≤ 4 MB;
  - GlyphBoard bitmaps.
- **GPU:** VBOs ≤ 6 MB, textures ≤ 16 MB.
- **If memory is still tight:** the pipeline can switch to a 5-layer grand (242 MB) or cap bass tails at 10 s by changing one tool flag.

### 3.3 Decode once, cache, map

1. `BankLoader.load(id)` reads `assets/banks/<id>/bank.json` and validates it with `BankIndexCodec.validate`.
2. `SampleStore.isCached(index)`: `files/pcm/<id>-<sha8>.pcm`, its `.idx.json` and the `.ok` marker must all exist, and the size must match. If any is missing, decode.
3. **Decode:**
   - Measure the decoder offset once with `DecoderProbe`, which decodes a click placed at frame 4800 and caches the result in prefs.
   - For each sample in index order, `OpusDecoder.decode(afd, channel, pageAlignedOffset, expectedFrames, skipFrames = probeOffset)`:
     - it asks for `c2.android.opus.decoder` by name;
     - it writes through a 64 KB direct buffer;
     - it pads or truncates to exactly `frames`.
   - Progress is reported as decoded frames / total frames.
   - The `.ok` marker is written last, so a kill mid-decode simply restarts the decode.
   - Stale `<id>-*.pcm` files with another sha are deleted.
   - The target is ≤ 60 s for the grand. If it is slower on the device, decode with two threads (two Opus decoder instances are allowed).
4. **Map:**
   - `FileChannel.map`, then `.order(LITTLE_ENDIAN).asShortBuffer()`.
   - Build one `MappedSampleSource` per sample: offset in shorts plus frame count. The view is read only by the audio thread.
   - The `PageWarmer` gets its own `duplicate()`.
   - Call `MappedByteBuffer.load()` in the background once, then pre-touch the first 250 ms of every sample.
5. `EngineControl.setBank(view, profile)`. The engine fades out the old instrument over 30 ms, then swaps.

### 3.4 Voice structure and the render loop

**Voice fields:**
- key, velocity;
- two sample slots (A, B) with weights, for layer crossfades;
- `pos` (Double, in frames);
- `rate` (Float);
- `gain` (current linear), `state`, `ageFrames`, `damperCountdown`, `startOffset`;
- `lp` (one-pole, used by una corda only);
- a scratch `ShortArray((256·1.35+4)·2)`, allocated once per voice slot.

**Per block, for each active voice:**
1. **Read the source.** Call `source.read(⌊pos⌋, scratch, need)` with `need = ⌈256·rate⌉ + 2`. That is one bulk copy (native memcpy through `ByteBufferAsShortBuffer`). Interpolating from a local array is far cheaper than calling `ShortBuffer.get` on every frame.
2. **Gain ramp.** `gEnd = gain × mul`, where `mul` depends on the voice state:

   | State | `mul` |
   |---|---|
   | HELD, FREE | 1.0. The sample carries the natural decay |
   | DAMPED | `DecayTables.damper(key, q(strength))` |
   | FADING | `DecayTables.fade(tau)` |

   The gain is ramped linearly across the block, so no `exp` or `pow` is ever called per sample.
3. **Mix.** The inner loop runs from `startOffset`:
   ```
   i = pos-int; f = frac
   l = s[2i] + (s[2i+2]-s[2i])·f
   r = s[2i+1] + (s[2i+3]-s[2i+1])·f
   mixL += l·g·(1/32768)
   mixR += r·g·(1/32768)
   g += dg; pos += rate
   ```
   For a crossfade, the same loop reads the second scratch, weighted by `wB`.
4. **End the voice** when `gain < 1e−4` (−80 dB) or `pos ≥ frames`. Every sample already ends in a 300 ms raised-cosine fade.

**Buses:**
- `dryL/R`: all voices;
- `monoSend`: the dry sum into the resonator bank;
- the reverb input is `dry × reverbSend`.

**Order in `render()`:**
1. drain commands;
2. sequencer dispatch;
3. pedal advance;
4. voices;
5. resonator;
6. FDN;
7. speaker voicing;
8. master gain;
9. limiter.

### 3.5 Velocity layers and crossfade

- **Selection.** `VelocityMap` precomputes, for v in 1–127: `layerA`, `layerB`, `wB` (equal-power, over a band of ±`crossfadeVel` around each split) and `gain(v) = layerGain × (v/127)^(2·veltrack)`. That is the SFZ `amp_veltrack` law, so we keep the mapping authors' calibration.
- **Grand:**
  - splits 1–30 / 31–46 / 47–64 / 65–88 / 89–112 / 113–127 (research §1);
  - ±4 crossfade band;
  - veltrack 0.73 (from the Salamander SFZ).
- **Upright:**
  - pp (dyn1) 1–40, mf (vl1) 41–83, f (vl2) 84–127;
  - ±6 band;
  - the pp layer's gaps (it is sampled every major third) fall back to vl1 below v=40 for keys it does not cover.
  - VCSL samples are normalised, so `bank.json` carries per-layer `gainDb`, calibrated by `build_bank.py` from the VCSL SFZ amplitude and velocity curve, and veltrack 0.9.
- **Harpsichord:** one layer, `velocityToGain=false`, so gain is constant plus ±1 dB of deterministic humanising hashed from the key and the note index.

### 3.6 Pitch shifting, pitch standard and temperament

- **Frequency, not note number, chooses the sample.** `TuningTable.retune(pitchHz, temperament)` computes for each key `f = A·2^((k−69)/12)·2^(off[k mod 12]/1200)` and its equivalent key `k_eq = 69 + 12·log2(f/440)`. For each layer and register it then picks the sample whose `rootKey + tuneCents/100` is nearest, and stores `rate = 2^((k_eq − root − tuneCents/100)/12)`. All transcendental math happens here, once per tuning change, never per note.
- **Shift distances:**
  - grand: ≤ ±1 semitone (minor-third spacing);
  - upright and harpsichord: ±1 (whole-tone spacing);
  - harpsichord 4′ and top notes 85–89: up to +5 semitones, a rate of 1.33, affecting 5 keys.
- **Interpolation is linear.** At |shift| ≤ 1 semitone, its high-frequency droop is ≈ −1.2 dB at 9.6 kHz. Hermite interpolation would double the reads, and I do not think it earns that.
- **The measured pitch of every sample** goes into `tuneCents`:
  - Salamander: `Data/tune_ret.txt` (the retuned cents per region);
  - VCSL: measured by `tools/measure_pitch.py`, whose median partial-based f0 settles the unverified 415-vs-440 question.
- **Tuning presets**, shown as `A440 · Equal`, etc.:

  | Instrument | Presets (default first) |
  |---|---|
  | Grand | A440 Equal · A430 Vallotti · A415 Werckmeister III |
  | Upright | A440 Equal · A430 Young II |
  | Harpsichord | **A415 Werckmeister III** · A415 Vallotti · A415 Kellner · A415 Lehman ("one proposal") · A415 ¼-comma meantone · A440 Equal |

**Temperament offsets from 12-TET, in cents, with A = 0** (mechanics research §10):

| | C | C♯ | D | E♭ | E | F | F♯ | G | G♯ | A | B♭ | B |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Werckmeister III | +11.7 | +2.0 | +3.9 | +5.9 | +2.0 | +9.8 | 0.0 | +7.8 | +3.9 | 0 | +7.8 | +3.9 |
| Vallotti | +5.9 | 0.0 | +2.0 | +3.9 | −2.0 | +7.8 | −2.0 | +3.9 | +2.0 | 0 | +5.9 | −3.9 |
| Young II | +5.9 | −3.9 | +2.0 | 0.0 | −2.0 | +3.9 | −5.9 | +3.9 | −2.0 | 0 | +2.0 | −3.9 |
| Kirnberger III | +10.3 | +0.5 | +3.4 | +4.4 | −3.4 | +8.3 | +0.5 | +6.8 | +2.4 | 0 | +6.4 | −1.5 |
| Kellner | +8.2 | −1.6 | +2.7 | +2.3 | −2.7 | +6.3 | −3.5 | +5.5 | +0.4 | 0 | +4.3 | −0.8 |
| Lehman 2005 | +5.9 | +3.9 | +2.0 | +3.9 | −2.0 | +7.8 | +2.0 | +3.9 | +3.9 | 0 | +3.9 | 0.0 |
| ¼-comma meantone | +10.3 | −13.7 | +3.4 | +20.5 | −3.4 | +13.7 | −10.3 | +6.8 | −17.1 | 0 | +17.1 | −6.8 |

### 3.7 Envelope and damper model by register

**Voice states:**
- **HELD:** key down; natural decay.
- **FREE:** key up, but the damper is lifted by the pedal or the sostenuto, or the key is above `lastDamper`.
- **DAMPED:** decaying at `strength × damperRate(key)`.
- **FADING:** restrike, steal, seek or instrument change.

**Damper timing.** On note-off the voice waits `damperDelayMs` in wall time before damping: 15 ms on the grand (the damper lands halfway through the key's return), 20 ms on the upright and harpsichord.

**Damper T60 by register** (mechanics research R3, to be calibrated at M8 against each pack's release samples):

| Instrument | Formula | Values |
|---|---|---|
| Grand | `0.12 + 1.2·((88−k)/67)²` s | 0.12 s at C6, 0.33 s at C4, 0.84 s at C2, 1.3 s at A0 |
| Upright | the same curve × 1.2 | – |
| Harpsichord | – | 0.10 s in the treble to 0.25 s in the bass |

`DecayTables` stores the per-block multiplier `exp(−blockSec·6.91/T60)^q` for 17 strength levels per key.

**Undamped treble.** Keys above `lastDamper` (88 on the grand, 90 on the upright) never enter DAMPED. They ring on at the natural decay until the sample ends, and their release noise plays at −10 dB, because the key and action still make a sound.

**Half pedal.** `strength = PedalMotion.dampingFactor(p)`, which is 1 − smoothstep(0.33, 0.55, p). A damper that is only just touching gives a partial decay rate. Continuous CC64 values, such as Krueger's or the Disklavier captures', therefore produce real half-pedalling.

**Re-pedaling (R13).** A DAMPED voice whose damper lifts again becomes FREE at its *current* gain. The note is not restarted.

### 3.8 Pedals

- **Sustain (CC64), grand and upright:**
  - travel `p` slews toward `value/127` at `PedalMotion.FULL_TRAVEL_MS` = 70 ms, in wall time, so the ear and the eye agree;
  - a key's damping strength is 0 if the key is down, latched by the sostenuto, or above `lastDamper`; otherwise it is `dampingFactor(p)`.
- **Sostenuto (CC66), grand only:**
  - *Rising edge:* `latched[k] = keyDown[k] || damperLiftByPedal(p) > 0` for every damped key. If the sustain is down at that moment, all dampers are up, so all are latched. That is how the mechanism really behaves.
  - *Falling edge:* clear.
  - Keys pressed after the edge are not latched.
  - On the upright, CC66 is ignored; its middle pedal is the practice mute.
- **Una corda (CC67):**
  - *Grand:* notes that *start* while the pedal is on get −2.5 dB and a per-voice one-pole low-pass at 4 kHz. The visual keyboard shifts 2.5 mm.
  - *Upright:* −4 dB, no filter. The hammer rail moves halfway toward the strings.
  - *Harpsichord:* ignored.
- **Practice mute (upright, menu only):** a master one-pole at 900 Hz and −16 dB; the felt rail drops visually.
- **Harpsichord:** `InstrumentAdapter` turns CC64 into finger legato (each note-off is delayed until the pedal lifts, capped at 1.5 s), then removes all pedal events. The engine's pedal model is inert and no pedal is drawn.

### 3.9 Restrike, release samples, pedal noises

- **Restrike (R10).** A note-on for a key that is already sounding fades that key's voices with τ = 60 ms if the pedal is up, or 200 ms if it is down, then starts a new voice. At most 3 streams per key; the oldest is hard-faded over 5 ms.
- **Release samples (R4).** A release sample fires when a voice enters DAMPED *because of a key release*, never when the pedal lifts (the pedal-up noise covers that).
  - Gain = `gain(v) × age(t)`, where `age(t) = max(0.25, e^(−t/2.5 s))` is tabulated in 50 ms steps.
  - Grand: `rel<k>`, one per key. Upright: the nearest of 45, pitch-shifted. Harpsichord: the 8′ and 4′ jack falls, one per engaged register.
- **Pedal noises (R11).** `PedalModel.pollNoise()` fires when `p` crosses 0.33:
  - *upward:* `PEDAL_DOWN` (the damper whoosh);
  - *downward:* `PEDAL_UP`;
  - gain `(0.5 + 0.5·|Δtarget|) × −20 dB`;
  - round-robin over the bank's variants (grand 2+2, upright 4+4).

  Noise voices are the first to be stolen.

### 3.10 Sympathetic resonance (approximation)

`ResonatorBank` has **24 two-pole resonators tuned to C2–B3** (MIDI 36–59), retuned whenever the temperament changes. They stand for the open strings a lifted damper rail exposes.

- **Input:** the dry mono sum, high-passed at 150 Hz, at −30 dB.
- **Decay:** Q set for T60 ≈ 1.5 s.
- **Output:** spread across the stereo field (even keys left, odd keys right) at −24 dB.
- **Active** while `p > 0.5`, with a 1 s fade out. Grand and upright only.
- **Cost:** about 8 flops per resonator per frame.
- **Thermal:** off at Q ≥ 1.

Held-key sympathetic resonance with the pedal up (R7) is a non-goal for v1.

### 3.11 Reverb: the baroque room

**Algorithmic FDN, not convolution.**
- A 1.3 s stereo impulse response at 48 kHz is about 62k taps per channel. Uniformly partitioned FFT convolution in Kotlin would cost an estimated 20–40 % of a core, which the thermal budget cannot carry.
- An 8-line FDN costs about 80 flops per frame, under 2 % of a core.

**Parameters:**

| Element | Value |
|---|---|
| Room (design) | 10.5 × 8.0 × 5.7 m, V = 479 m³, S = 379 m², mean free path 5.06 m ≈ 14.7 ms |
| Delay lines (samples, co-prime) | 709, 919, 1103, 1297, 1523, 1777, 1999, 2239 (14.8–46.6 ms) |
| Feedback | 8×8 Hadamard (fast Walsh–Hadamard, 24 add/sub) × g_i, with g_i set per line from the RT60 |
| Damping | One-pole per line, giving RT60 1.35 s at mid frequencies and 0.9 s at 8 kHz, and a slightly longer 1.5 s bass |
| Early reflections | 6 taps (floor, ceiling, two side walls, rear wall, mirror wall) at 9–31 ms, panned |
| Pre-delay | 12 ms |
| Send per view | Player 0.20 · Action 0.14 · Inside 0.16 · Hall 0.40, eased over 300 ms |

### 3.12 Master chain

- **Speaker voicing** (`OutputRouteWatcher`):
  - *Built-in speaker:* high-pass at 120 Hz (Q 0.7) plus a peaking +3 dB at 250 Hz (Q 0.8). The speakers have essentially no output below about 150 Hz.
  - *Headset or BT:* high-pass at 20 Hz only.
- **Master gain** is calibrated so that a v=127 C-major triad in the middle register peaks at about −6 dBFS before the limiter.
- **Limiter:** SpyHunt's (0.92 threshold, 0.4 ms attack, 150 ms release) into a Padé soft clip.
- **Focus ducking** multiplies the master gain by 0.3.

### 3.13 Voice cap and stealing

- **Pool:** `cap` streams plus 8 fade slots. `cap` = 64 at Q0, 48 at Q1, 32 at Q2 and Q3. A layer crossfade or a harpsichord 8′+4′ note counts two streams.
- **Allocation:** take a free slot. Otherwise `StealPolicy.victim` picks the lowest `gain × w(state)`, weighted by state and made about 10 % more likely to be taken for each second of age, with the weights:

  | State | w |
  |---|---|
  | noise | 0.1 |
  | damped | 0.25 |
  | free (pedal-held) | 0.5 |
  | held | 1.0 |

  The victim moves to a fade slot and fades out over 5 ms. If every fade slot is busy, the oldest fade is cut.
- **Held keys** are stolen only when nothing else is left.
- **Stats** count steals; `HKPerf` logs them.

### 3.14 CPU budget (these are estimates; M2 measures them)

**Per-stream cost model.** One bulk copy plus a stereo linear interpolation and a gain ramp is about 20 array ops per frame. On ART I budget **≤ 100 ns per stream-frame**, which is 4.8 ms per second, or **0.48 % of one core per stream**.

| Component | Q0 | Q1 | Q2 |
|---|---|---|---|
| Sample streams | 64 → 31 % | 48 → 23 % | 32 → 15 % |
| Sequencer, pedal model, voice management | 1 % | 1 % | 1 % |
| FDN reverb | 1.5 % | 1.5 % | 1.5 % |
| Resonator bank | 3 % | off | off |
| Speaker EQ, limiter | 1 % | 1 % | 1 % |
| **Audio thread total (worst case)** | **≤ 38 %** | **≤ 27 %** | **≤ 19 %** |

- **Block deadline:** 5.33 ms. Render time must stay ≤ 2.5 ms worst case (64 streams × 256 frames × 100 ns ≈ 1.64 ms).
- **Acceptance:** at a 64-stream stress load (`midi/test/stress64.mid`), the audio thread must average ≤ 40 % of a core (`top -H`), with render max ≤ 3 ms and 0 underruns over 10 min.
- **What a typical piece should cost:** Für Elise uses about 12–20 streams and should come in at ≤ 12 %.
- **No allocation on the audio thread after warm-up:**
  - JVM `NoAllocTest` reads `com.sun.management.ThreadMXBean.getThreadAllocatedBytes`;
  - the debug build logs `Debug.getThreadAllocCount()` every 10 s.

### 3.15 Failure modes and fallbacks (why it works on first install)

| Failure | Detection | Fallback | User sees |
|---|---|---|---|
| `bank.json` missing or invalid | `BankIndexCodec.validate` | APK stub bank (`banks/stub`, Opus, about 0.6 MB) | `Stand-in tones: sample bank not installed` |
| Opus decoder missing or throwing | `DecodeResult.Failed`, or the probe fails | The stub bank synthesized in code (`StubBank.synthesize`, no decoder needed) | `Stand-in tones: decoder unavailable` |
| Cache write fails (disk full) | IOException | Decode into a smaller bank: the 2 grand layers v4 and v13 only | `Reduced grand: low storage` |
| Decode interrupted (killed) | No `.ok` marker | Re-decode on the next load | progress again |
| mmap page faults | `majflt` on the audio thread > 0 after warm-up | `PageWarmer` look-ahead doubled to 1 s; if it persists, the bank is decoded with tails capped at 10 s | – |
| AudioTrack refuses LOW_LATENCY | Builder throws | `PERFORMANCE_MODE_NONE`; sync is unaffected | – |
| No AudioTrack at all | Builder throws twice | The app runs silently; visuals are driven by `FakeClock` | `Audio output unavailable` |
| Underruns | `getUnderrunCount` increments | `LatencyTuner` +1 block (up to 16); polyphony cap −8 | – |

---

## 4. The MIDI pipeline

### 4.1 `SmfParser`: tolerant, bounded, never throws

- **Header.** Needs `MThd` with length ≥ 6 (extra bytes are skipped). Formats 0 and 1 are supported; format 2 plays its tracks one after another, a rare case. Division is PPQ, or SMPTE (`−fps`, ticks/frame).
- **Chunks.** Non-`MTrk` chunks are skipped. A chunk length that runs past EOF is clamped, with a warning.
- **Events:**
  - VLQ reads at most 4 bytes; a malformed VLQ ends the track with a warning.
  - **Running status**, which **meta and sysex events cancel**.
  - Note-on with velocity 0 counts as note-off.
  - Kept: CC64, CC66, CC67, tempo `FF 51`, time signature `FF 58`, key signature `FF 59`, track name `FF 03`, end of track `FF 2F`.
  - Skipped: sysex `F0`/`F7`, other metas, program change, pitch bend, aftertouch, other CCs (CC7/CC11 included; see non-goals).
  - Unknown status bytes are resynced to the next status byte.
- **Limits:** 8 MiB, 2 M events, 256 tracks. Exceeding one returns `Failed(TOO_LARGE / TOO_MANY_EVENTS)`.
- **Hand-built byte array test** (one of about 30 parser tests):
  ```kotlin
  @Test fun runningStatus_velocityZeroOff_hangingNoteClosed() {
      val midi = bytes(0x4D,0x54,0x68,0x64, 0,0,0,6, 0,0, 0,1, 0x01,0xE0,      // MThd fmt0, 1 trk, 480 PPQ
                       0x4D,0x54,0x72,0x6B, 0,0,0,23,                          // MTrk, 23 bytes
                       0x00, 0xFF,0x51,0x03, 0x07,0xA1,0x20,                   // tempo 500 000 µs/qn
                       0x00, 0x90,60,100,                                       // C4 on
                       0x83,0x60, 64,90,                                        // +480, running status: E4 on
                       0x83,0x60, 60,0,                                         // +480, C4 vel 0 = off
                       0x00, 0xFF,0x2F,0x00)                                    // end of track
      val perf = (PerformanceBuilder.parseAndBuild(midi, "t") as BuildResult.Ok).perf
      assertEquals(2, perf.noteCount)
      assertEquals(PRE_ROLL_FRAMES, perf.noteOn[0]);  assertEquals(PRE_ROLL_FRAMES + 48_000, perf.noteOff[0])
      assertEquals(PRE_ROLL_FRAMES + 24_000, perf.noteOn[1])   // E4, closed at end of track + warning
      assertTrue(perf.meta.warnings.any { it.startsWith("hanging note") })
  }
  ```

### 4.2 `PerformanceBuilder`: the playable performance model

1. **Tempo map.** Merge tempo events from all tracks (format 1 puts them in track 0, but some files put them elsewhere) into a `TempoMap`. Convert every tick to µs, then to frames: `frame = PRE_ROLL + round(us·48000/1e6)`.
2. **Merge channels.** Merge all tracks and channels onto one keyboard, **dropping channel 10 (drums)**. Program changes are ignored, so files written for other instruments still play on the keyboard.
3. **Pair notes** per key, first in, first out. A note-on for a key that is already on closes the previous span at the new onset, because a keyboard can't hold one key twice. Offs without an on are dropped. Hanging notes are closed at the end of their track, or at the last event + 2 s, with a warning.
4. **Pedal lanes.** CC64, CC66 and CC67 become `SUSTAIN/SOSTENUTO/SOFT` events with their raw value 0–127. Consecutive identical values are deduplicated.
5. **Sort** by `(frame, kind)`, with the tie order NOTE_OFF < pedals < NOTE_ON < END, so a re-pedal at the same tick as a new chord catches the chord.
6. **Metadata:** title (from the first `FF 03` in track 0, or null), note range, `has*` flags, `durationFrames = lastEvent + tail` (2 s), warnings.
7. **Failure:** no notes after the drum drop gives `Failed("no keyboard notes")`.

### 4.3 `InstrumentAdapter`: files meant for other instruments

- **Compass folding.** Each note outside `[lowKey, highKey]` moves by octaves into range: 29–89 on the harpsichord, 21–108 on the pianos. A fold that collides with a sounding span on the same key triggers the restrike rule (4.2 step 3). Warning `folded n notes`.
- **Harpsichord:**
  - CC64 is converted to finger legato: while the pedal is down, note-offs are delayed to `min(pedalUp, off + 1.5 s)`, and to the next onset of the same key if that comes sooner;
  - then all pedal events are removed;
  - velocities are kept, because they still shape the key animation lead.
- **Upright:** CC66 is removed.
- **Pure function:** same input, same output. The result is shared by the engine and the renderer.

### 4.4 Transport

All transport commands go through `EngineControl` and are executed on the audio thread at a block boundary.

| Command | Engine behaviour | Visual behaviour |
|---|---|---|
| Play | Advance from the current position. Starting from 0 includes the 300 ms pre-roll | Clock goes to `playing` |
| Pause | Keys lift (the normal damper path); after 300 ms the pedals are released; the position is held | Rest blend over 60 ms |
| Seek(ms) | Fade all voices over 10 ms; binary-search the cursor; recompute pedal states with `controllerAt`. Notes that span the seek point are **not** retriggered | Poses are analytic at the new position, so keys that are held show down |
| Next / previous | AppController loads the next or previous movement (or work) with a 3 s gap. "Previous" within the first 3 s of a movement goes back one; otherwise it restarts the movement | Dip transition |
| Tempo | 50–150 %. Changes the sequencer's advance per block, with no pitch change. Physical key durations stay in wall time | Travel scaled by τ |
| Instrument | Fade 30 ms, swap the bank, re-adapt the performance (new generation), reload at the current position with the same play state | Model swap under the dip |

### 4.5 Catalogue format

- **`assets/catalogue.json`** is generated by `tools/build_catalogue.py` and never edited by hand.
- **Imports** live in `files/Scores/index.json` with the same `works` schema and `"imported": true`. Each import is one Work with one Movement.
- **Imported defaults:**
  - title: the track name, else the filename;
  - composer: "Imported";
  - `defaultInstrument`: `harpsichord` if there is no CC64 and the note range fits 29–89, otherwise `grand`.

```json
{
  "schema": 1,
  "generated": "2026-09-22T18:00:00Z",
  "startHere": ["bwv846-krueger", "bwv772", "bwv988-aria", "k141", "hwv430-5", "..."],
  "categories": [ { "id": "bach-young", "title": "Bach: the young virtuoso", "order": 2 } ],
  "works": [ {
    "id": "bwv992", "composer": "J. S. Bach", "title": "Capriccio sopra la lontananza del fratello dilettissimo",
    "catalogueNo": "BWV 992", "year": 1704, "era": "baroque", "categoryId": "bach-young",
    "defaultInstrument": "harpsichord", "altInstruments": ["grand", "upright"],
    "tier": "A", "performanceType": "performed",
    "credit": "Harpsichord: John Sankey · johnsankey.ca/harpsichord.html · free-copy notice",
    "licence": "Sankey", "licenceUrl": "https://www.johnsankey.ca/copyright.html",
    "movements": [ { "id": "bwv992-1", "title": "Arioso: Adagio", "asset": "midi/sankey/bach/misc1/<entry>.mid",
      "sha1": "…", "durationSec": 171.3, "lowKey": 34, "highKey": 81,
      "hasSustain": false, "hasSoft": false, "hasSostenuto": false } ]
  } ]
}
```

The fields `durationSec`, `lowKey`, `highKey` and `has*` are **computed from the files** by `tools/smf_stats.py`. `MidiCorpusTest` (Kotlin, JVM) re-derives them with the app's own parser and fails if the two disagree (±1 ms, exact ranges). Two independent parsers checking each other at build time means no bundled file can surprise us on the device.

---

## 5. The rendering design

### 5.1 GL setup and stereo

- **GLES 2.0 API with GLSL ES 1.00**, which every sibling app uses. The device offers ES 3.2, but nothing in this plan needs it.
- **EGL config chooser**, tried in order: (RGB888, depth 24, stencil 8, 4× MSAA) → (888, d24, s8) → (888, d16) → the first config offered. The chosen config's stencil bits and sample count go to the renderer, and mirrors are disabled when there is no stencil. `--ez msaa false` persists for the next launch.
- **Stereo: off-axis frustum, not toe-in.** This follows SpyHunt's `perspectiveOffAxis`: parallel eyes, IPD 63 mm × a per-view scale, zero parallax at the subject, in metre-scale world units.
  - The simulation (clock, mechanics, camera) runs **once** per frame; the scene is drawn twice with `glViewport(e·w/2, 0, w/2, h)`.
  - `--ez mono true` or an emulator gives one full-width eye.
  - `fov` and `ipd` are CONTROL-tunable.
- **Sign rule** (MathCosmos): right = forward × up, and up = right × forward.

### 5.2 Scene organisation

This is not a general scene graph. There is a fixed set of drawables, and `SceneList` assembles an ordered draw list per (view, venue level, quality). The order is:
1. opaque venue, baked lighting;
2. opaque instrument, lacquer or wood;
3. skinned moving parts;
4. strings (alpha);
5. additive gilt, flames, reflections and crystals;
6. glyphs.

Each drawable owns its VBOs, which are built once and uploaded with `GL_STATIC_DRAW`. Per frame, a drawable only uploads uniforms.

### 5.3 Procedural meshes

All geometry is generated in `geom/` (pure Kotlin → `MeshData`, JVM-tested) from the visual research build sheets.

| Instrument | Parts | Triangles |
|---|---|---|
| Grand (C5 size, 200 × 149 × 101 cm) | Plan outline as a Catmull-Rom spline, extruded to the rim (0.30 m deep); lid (stick 38°) with a front flap; 3 legs with casters; lyre and 3 pedals; gold plate with lightening holes; soundboard; 88 keys (1 VBO, `slot/sel` attributes); 88 hammers; 70 dampers; 228 strings (8 single wound, 40 bichord wound, 180 trichord), overstrung at 17° | ≈ 12k |
| Upright (U3, 131 × 153 × 65 cm) | Case panels, knee board, 3 pedals; vertical overstrung strings; horizontal hammers; damper row above the strike line; hammer rail; felt mute rail | ≈ 9k |
| Harpsichord (Blanchet, 232 × 91 cm) | Bentside double case on a cabriole stand; 2 manuals (60 keys each, ebony naturals, bone sharps, arcaded fronts); 180 jacks with tongues and dampers; jack rail; rose; register levers | ≈ 10k |
| Action set (Action view, ±6 notes) | Key lever, capstan, wippen, jack, repetition lever, let-off button, hammer and shank, backcheck, damper underlever, wire and head (grand); the upright and jack equivalents | ≈ 5k |

**Key layout.** Every semitone gets an equal 13.71 mm slot at the back, and naturals get equal 23.5 mm heads at the front, with their tails cut around the sharps. Key edges are an analytic `smoothstep` bevel in key-local UVs, never real geometry gaps, which would be 0.1–0.5 px wide and would shimmer.

### 5.4 The baroque venue (WP9)

The venue is the Sanssouci Konzertzimmer, built to the visual research §1.3 sheet: a 10.5 × 8.0 × 5.7 m design room; 3 pier mirrors on the north wall; 3 French windows on the south wall; 6 Pesne panels; a cove with hunting cartouches; a ceiling trellis rising to a spider's web; a chandelier with 12+6 candles and about 160 crystals; sconces; 2 floor candelabra; 3 rows of 6 rococo chairs; oak parquet.

**Drawing it:**
- **What is drawn:** only gilt ribbons and decals, flames, mirror reflections (stencil), crystals and floor pools. The white wall fields are **not drawn**; the wearer's own room stands in for them.
- **Lighting:** baked per vertex from all 50 flames at load time, plus at most 4 dynamic lights (the chandelier aggregate, 2 candelabra, the nearest sconce).
- **Flicker:** a global ±4 % noise at 6–10 Hz, plus per-sprite hashed flicker.
- **The rocaille atlas** is drawn on the device with Canvas paths, so there is no texture asset and nothing to download.
- **Palette variant:** "Stadtschloss 1747" (celadon walls near the flames) is a menu option at M8.

**Venue levels** (the user picks one; the thermal governor may step it down):

| Level | Target APL | Default for |
|---|---|---|
| Salon | ≤ 12 % | Hall view |
| Stage | ≤ 9 % | Player, Action, Inside |
| Instrument | ≤ 6 % | – |
| Passthrough | ≤ 5 % | daylight (feature edges on) |

### 5.5 Views and cameras

The values come from visual research §4.6, in the piano frame (y up, x toward the treble, z toward the pianist). The render FOV is fixed per view and is never animated.

| View / framing | Camera → target | vFOV | IPD × | Zero parallax |
|---|---|---|---|---|
| Player wide (grand, upright) | (−0.10, 1.30, 1.55) → (0, 0.50, −0.12) | 34° | 0.6 | 1.75 m |
| Player follow (swipe up) | (x_c, 1.15, 0.62) → (x_c, 0.70, −0.08) | 30° | 0.5 | 0.95 m |
| Player (harpsichord) | (−0.06, 1.22, 1.05) → (0, 0.62, −0.10) | 34° | 0.6 | 1.25 m |
| Action grand | (x_cut+0.95, 0.95, 0.30) → (x_cut, 0.76, −0.24) | 22° | 0.35 | 1.1 m |
| Action upright | (x_cut+1.05, 1.00, 0.10) → (x_cut, 0.93, −0.20) | 26° | 0.35 | 1.1 m |
| Action harpsichord | (x_cut+0.60, 0.93, −0.02) → (x_cut, 0.86, −0.42) | 24° | 0.3 | 0.73 m |
| Inside grand | (0, 1.95, 0.55) → (0, 0.84, −0.90) | 44° | 0.5 | 1.8 m |
| Inside upright | (1.05, 1.40, 0.75) → (0, 1.06, −0.28) | 36° | 0.5 | 1.5 m |
| Inside harpsichord | (0, 1.85, 0.45) → (0, 0.80, −0.95) | 44° | 0.5 | 1.8 m |
| Hall wide (room frame) | (0.4, 1.20, 3.0) → (0, 1.95, −1.9) | 40° | 1.0 | 4.9 m |
| Hall life-size (swipe up) | (0.4, 1.20, 3.0) → (0, 1.05, −1.9) | 18.27° | 1.0 | 4.9 m |

- **Player** includes the pedal feet in frame. A white key is 11 px wide and dips 4.4 px; a pedal travels 5.5 px.
- **Action:**
  - A fragment `discard` against the `uClip` plane at x_cut produces the cutaway. Section caps are drawn in SECTION_CAP colour with a gilt outline.
  - x_cut follows `MelodyTracker`, with a 1.5 s minimum dwell.
  - Notes within ±6 are drawn; the rest fade, and inactive ones dim to 40 %.
  - A GlyphBoard label names the note ("C4", 16 px).
  - Hammer travel is 48 px.
- **Inside:** the lid lifts off.
  - Hammers flick about 13 px.
  - Strings are spindles, their blur exaggerated 4–6× in this view only, and the design notes say so.
  - Lifted dampers open a lit gap above the string.
- **Hall:**
  - world-locked gaze (±60° yaw, +45° pitch, via `GazeCamera`); the other views get ±5° of parallax only;
  - an upright stands against the north wall, turned 30°.

### 5.6 Animation models (`mech/`, pure, evaluated analytically at each frame's score time)

**Precomputed per note span** (`SpanKinematics`, at bind):
- **Hammer velocity:** `HV = 10^((v−57.96)/71.3)`, clamped to 0.25–7 m/s.
- **Travel time** (Steinway fits): `tt = [(1−s)·98.57·HV^−0.7147 + s·65.19·HV^−0.7268] × travelScale`, with `s = smoothstep(2, 4.5, HV)`, clamped to 20–230 ms.
- **Key-bottom time relative to onset:** `tb`, from the pressed and struck fits (+18.6 ms at v=20, −3.4 ms at v=127).
- **Free flight:** `ff` (9.1 ms at v=20 down to 0.1 ms).

All are wall-clock milliseconds. At evaluation they become score frames × 48 × τ.

**Grand (a state machine per key):**
- **Key depth:**
  - *pressed:* `d = u^1.8`, where `u = (t − (on − tt))/(tt + tb)`;
  - *struck:* linear with an 8 ms stall at 0.33;
  - the two are blended by `s`.
  - The rendered angle is `d × 2.24°` about the balance rail at 259.5 mm.
- **Hammer**, normalised to the 47 mm blow:
  - `h = min(5.0·d·dip/blow, (blow−letOff)/blow)` until escapement at `on − ff`;
  - then a linear coast to 1 at `on`;
  - contact lasts `4·0.2^((k−21)/87)` ms;
  - rebound at 0.45·HV down to the check position (1 − 15/47 = 0.68) while the key is held.
- **Release:** the key returns over 35 ms (ease-out) and the hammer follows it. A new note that arrives before the key is fully up rises from the current height (repetition, at least 67 ms apart), and the jack resets once the key has risen by a third.
- **Damper:** `max(keyLift = ((h − 0.5)/0.5).clamp, PedalMotion.damperLiftByPedal(p), sostenutoLatched ? held : 0)`. It falls with a 20 ms slew and lands at 50 % of the key's return.
- **String:** `A(t) = A0(HV)·(0.8e^(−3t/τ) + 0.2e^(−t/τ))`, with `τ = T60(k)/6.91` and `T60(k) = 6.24·10^(−0.0275(k−60))` s (capped at 30 s). When the damper lands, τ switches to the damper T60. The strike flash lasts 150 ms.

**Upright:**
- blow 46 mm (horizontal), let-off 3.2 mm, check 16 mm, key return 50 ms;
- the jack resets only after an 80 % key return, so repetition is slower (≥ 143 ms);
- soft pedal: the hammer rest moves to 0.5·blow (22 mm), with lost motion at the key;
- the mute rail drops 30 mm over 200 ms.

**Harpsichord:**
- lead `15 + 25·(1 − v/127)` ms;
- the jack rises 1.25 × the key travel (7 mm dip);
- the pluck, at depth 0.45, coincides with `on`;
- the jack rail stop comes at depth 1;
- on release, the jack falls with the key over 25 ms; the tongue swings 0→1→0 over 10 ms as the quill passes the string, then the damper lands;
- a disengaged register slides sideways, so its quills miss the strings;
- a registration change moves the levers over 200 ms.

**Pedals:**
- travel uses `PedalMotion.slew` (70 ms);
- the grand damper pedal tip rotates 5°; lost motion to 0.33 appears as damper travel only;
- una corda shifts the keyboard and action 2.5 mm (60 ms);
- sostenuto rotates its rail blade from 45° to 90°.

### 5.7 Batching and budgets

- **Uniform-array skinning.** The grand's moving parts come to **4 draw calls in total**:
  - all 88 keys in one draw (`uState[22]` vec4 plus a one-hot lane);
  - all hammers in one;
  - all dampers in one;
  - strings in two (steel and copper), counted as one pass.

  That is about 1.5 KB of uniforms per frame. `GL_MAX_VERTEX_UNIFORM_VECTORS` is logged by the self-test; each shader needs ≤ 23 + 8 vectors against a minimum of 128.
- **Draw calls per eye:**

  | View | Draws |
  |---|---|
  | Player | ≈ 20 |
  | Action | ≈ 22 |
  | Inside | ≈ 18 |
  | Hall (Salon) | ≈ 28 |
  | **Cap** | **35** |

  The cap is enforced in debug builds: `GlKit.drawCounter` logs a warning when it is exceeded.
- **Triangles:** ≤ 45k per eye (the estimate is about 39k). Textures < 16 MB. No `glBufferData` in the steady state, and no Kotlin allocation in `onDrawFrame`; arrays are preallocated and labels come from the GlyphBoard cache.

### 5.8 Text and overlays

- **HUD, menus and library** are Android Views inside the single `BinocularSbsLayout` child (§1.5), with hardware layers. Invalidation is ≤ 2 Hz, because fast UI refresh starves audio.
- **In-scene text** uses `GlyphBoard`:
  - the Action view's note label;
  - the composer placard on the music desk (`drawOriented`) in the Player view;
  - at most 3 new labels per frame.

### 5.9 Waveguide colour strategy

- **Black is transparent**, so black lacquer and ebony are drawn as reflectors:
  - `max(lit, uFloor)` presence floor, calibrated on the device (default 22,18,15);
  - Fresnel rim `pow(1−N·V, 3) × (120,78,40)·0.8`;
  - Blinn-Phong candle streaks (exponent 96);
  - the baked 128×64 light-probe reflection, whose streaks slide as the head moves;
  - optional gilt feature edges in Passthrough.
- **The bright interior reads without tricks:** the plate, spruce, steel strings, white felt and ivory.
- **Brightness caps:** large areas ≤ 220, with 255 reserved for flame cores and sparkles. Blue is kept low.
- **Shadows** read as holes, and only work because a lit floor pool always surrounds them.
- **Key glow** is off by default: a +12 % warm emissive on pressed keys, offered as a legibility aid.
- **Textures** use the MathCosmos gamma lift `pow(c, 0.85)`.

### 5.10 Pacing and the thermal governor

- **Pacing** (MathCosmos): `HkSurfaceView` renders on demand. A Choreographer callback requests a frame every `divider`-th vsync: 2 → 30 fps, 3 → 20, 4 → 15. The idle state (nothing playing, no transition) runs at 15 fps. `onResume` calls `removeFrameCallback` before `postFrameCallback` (the WanderQuest fix), and `dt` is clamped to 0.05 s.
- **Governor:** `ThermalGovernor` combines the battery temperature (tenths of °C, from the sticky `ACTION_BATTERY_CHANGED`) with `PowerManager` thermal status, which reads 0 even when the chip is hot. Each level relaxes one step at a time.

| Level | Enter at | Relax below | Frame rate | Venue and GPU | Audio |
|---|---|---|---|---|---|
| Q0 | – | – | 30 fps | Chosen level, mirrors, 160 crystals, MSAA | 64 streams, resonance on |
| Q1 | 39.0 °C (or status MODERATE) | 37.5 °C | 20 fps | At most Stage, mirrors off, 60 crystals | 48 streams, resonance off |
| Q2 | 42.0 °C (or SEVERE) | 40.5 °C | 15 fps | Instrument level | 32 streams |
| Q3 [D] | 44.5 °C | 43.0 °C | **Display rest**: GL paused, black (transparent), the status says `Resting the display to cool: music continues` | – | 32 streams |

---

## 6. Asset pipeline (Python 3 + ffmpeg + numpy on the Mac)

### 6.1 Tools

All tools live in `tools/`. Downloads are sequential, resumable and verified. Nothing is downloaded until the user approves the manifests.

| Tool | Does | Output |
|---|---|---|
| `fetch_samples.py --need required\|all [--group G] [--dry-run]` | Downloads rows of `docs/research/sample-download-manifest.tsv`: one at a time, byte count checked against the manifest, sha256 recorded | `tools/cache/samples/<group>/…`, `tools/cache/samples/ledger.tsv` |
| `build_bank.py --instrument grand\|upright\|harpsichord [--layers 1,4,7,10,13,16] [--kbps 112]` | Per sample: decode to float WAV 48 kHz (`ffmpeg -ar 48000`); trim leading silence (Salamander `Data/vel_XX.txt` offsets; VCSL at −60 dB re peak with 2 ms of pre-roll); trim the tail by register (piano 14 s at A0 → 3 s at C8, upright 12 → 3 s, harpsichord 8 → 3 s) with a 300 ms raised-cosine fade; remove DC; **no per-sample normalisation**, so level is kept across layers (VCSL gets layer `gainDb` from the SFZ); encode `ffmpeg -c:a libopus -b:a 112k -vbr on -application audio`; write `bank.json` (schema §2.5), taking `tuneCents` from `tune_ret.txt` or `measure_pitch.py` | `app/src/main/assets/banks/<id>/{bank.json,s/*.opus}` |
| `measure_pitch.py --bank <dir>` | Median partial-based f0 of 0.3–1.3 s after the attack (numpy FFT) → `tuneCents`; reports each bank's pitch reference (settles 415 vs 440) | updates `bank.json` |
| `make_stub_bank.py` | Synthesizes 30 notes × 1 layer, 1.2 s decaying partials, same format | `assets/banks/stub/` (≈ 0.6 MB) |
| `make_probe.py` | 0.5 s of silence with a click at frame 4800, as Opus | `assets/banks/probe.opus` |
| `make_test_midis.py` | `scale`, `chords_pedal`, `half_pedal`, `sostenuto`, `restrike_fast` (70 ms repeats), `undamped_top`, `range_fold` (MIDI 12–120), `stress64` (64 streams with pedal), `avsync`, `format0_vs_1` pair, `smpte`, `running_status` | `assets/midi/test/*.mid` |
| `fetch_midis.py [--only krueger\|sankey\|commons\|mutopia]` | `tools/midi-manifest.tsv` (built from repertoire §8). Krueger: primary URL, then the Wayback raw URL, with **SHA-1 base32 verified**. Sankey: zips downloaded, then entries extracted **byte-for-byte** per `tools/sankey_map.tsv` (filled in after listing each zip). Commons with a descriptive User-Agent. Mutopia. 2 s pause between requests | `tools/cache/midi/…` → `assets/midi/<source>/…` |
| `smf_stats.py <files…>` | An independent SMF reader (≈ 150 lines): format, PPQ, tracks, note range, counts of CC64/66/67, duration | JSON to stdout |
| `build_catalogue.py` | `tools/works.json` (hand-authored from repertoire §4: works, categories, defaults, credits, movement → asset) + stats + ledger → catalogue; copies the licence texts; writes CREDITS | `assets/catalogue.json`, `assets/licenses/*`, `CREDITS.md` |
| `check_ledger.py` | Every file under `assets/banks` and `assets/midi` has a ledger row (source URL, licence, sha1, bytes, credit, modified yes/no). Sankey and Krueger sha1s match the originals (byte-identical) | CI gate |
| `size_report.py --cap-mb 70` | Asset and APK size table; fails above the cap | CI gate |
| `apl.sh <png>` | `ffmpeg -vf signalstats` mean luma of a 1280×480 screencap → APL % | stdout |
| `avsync.py <rec.mkv>` | numpy: flash frames (luma jumps, with pts) vs click onsets (audio envelope) → mean, std, max offset | stdout + pass/fail |
| `run.sh`, `ci.sh`, `check_purity.sh`, `smoke.sh`, `soak.sh` | §8 | – |
| `tests/test_*.py` | `python3 -m unittest`: smf_stats vs the generated test MIDIs; bank.json schema; stub-bank determinism; sha1 helpers; ledger completeness | CI |

### 6.2 Licence ledger

`tools/ledger/ledger.tsv` is the single source of attribution and is published as `assets/licenses/SOURCES.csv`. The Credits panel renders `assets/licenses/CREDITS.txt`, which contains the attribution texts from both research reports:
- Salamander (Alexander Holm, CC-BY 3.0; the author declared it public domain in 2022; retune tables by Markus Fiedler; SFZ data by kinwie). The samples are marked as *trimmed, resampled, encoded to Opus*.
- VCSL and VSCO-2 CE (CC0; courtesy credit to Versilian Studios / Sam Gossner and Simon Dalzell).
- Bernd Krueger (CC BY-SA 3.0 DE, files unmodified).
- John Sankey (his notice verbatim in `SANKEY.txt`, files unmodified).
- Gouin (CC BY-SA 4.0), Nieb (CC BY-SA 3.0), Bednarek and Frantz (CC0 or PD), Mutopia (PD).

Licence texts ship as files. **MIDI files are shipped as their original bytes and parsed at runtime**, so nothing is converted and no CC BY-SA adaptation is created. There is no export feature, which also satisfies Sankey's audio clause.

### 6.3 Gated steps (user actions)

1. Approve the 786 MiB required sample download (plus the 103 MiB optional: the upright pp layer, the lute stop, and the harmL/S files, which are not used in v1).
2. Approve the MIDI download (≈ 5 MB).
3. Click IMSLP's "I understand" for #365752 and #340106 yourself, and drop the files into `tools/cache/midi/imslp/`. If you skip this, those two works are omitted and the build does not fail.
4. Madore's Couperin stays **excluded** unless he grants CC0.

Until steps 1–2 happen, everything runs on the stub bank and test MIDIs. **M0–M1 and the visual milestones do not depend on any download.**

### 6.4 APK size budget

| Item | Size |
|---|---|
| Grand bank, Opus 112k (6 layers, releases, pedals) | ≈ 21 MB |
| Upright bank (2 layers + pp, releases, pedals) | ≈ 12 MB |
| Harpsichord bank (8′, 4′, lute, releases) | ≈ 6.5 MB |
| Stub bank + probe | ≈ 0.7 MB |
| MIDI corpus (core 70 works + test files) | ≈ 3.5–5 MB |
| Catalogue, licences, companion page | ≈ 0.4 MB |
| Code + androidx + nanohttpd | ≈ 4 MB |
| **Total** | **≈ 50 MB** (CI cap 70 MB) |

In git, `*.opus` goes in LFS (the user's convention), MIDI files are committed normally, and `*.apk` is ignored, as it already is.

---

## 7. Work breakdown for parallel implementation by agents

### 7.1 Rules

- **Ownership.** Every file has exactly one owner package (§2.6). Nobody edits another package's files. A needed change goes to the owner as a request.
- **Contracts.** `contract/**` is written by WP0 on day 0, compiles with its stubs, and is tagged `contracts-v1`. After the tag it changes only by integrator decision, and all packages rebase onto the change.
- **Branches.** Each package works in its own git worktree and branch. The integrator merges in milestone order (§7.3).
- **The CI gate** (`tools/ci.sh`) must pass before any merge:
  - `check_purity.sh`;
  - `./gradlew :app:testDebugUnitTest :app:assembleDebug`;
  - `python3 -m unittest discover tools/tests`;
  - `check_ledger.py`;
  - `size_report.py`.
- **Commits** are authored as `tropicalstream <tropicalstream@users.noreply.github.com>`. No other address appears anywhere.
- **Wiring notes.** Each package ships a short `docs/wiring/WPn.md` listing the calls the integrator must add to `AppController`. Only WP0 edits `AppController` and `MainActivity`.

### 7.2 Packages

**WP0: Shell, contracts and integration** (the integrator)
- *Files:*
  - `app/build.gradle.kts`, `AndroidManifest.xml`; rewrite `res/values/*` (Hammerklavier palette; remove the MathCosmos comments);
  - `HammerklavierApp.kt`, `MainActivity.kt`, `AppController.kt`;
  - `contract/**`, `contract/stub/**`;
  - `input/TrackpadGestureEngine.kt`, `ui/BinocularSbsLayout.kt`, `ui/CalibrationCard.kt`, `gl/HkSurfaceView.kt`;
  - `system/*`;
  - `tools/{run,ci,check_purity,smoke,soak}.sh`.
- *Provides:* all contracts and stubs, and the running shell.
- *Acceptance:*
  - JVM: `PerformanceContractTest` (binary searches, `controllerAt`, `spansByKey` on stub performances); `PedalMotionTest` (slew 0→1 takes 70 ms ± one step; `damperLiftByPedal(0.33) = 0`; `dampingFactor(0.55) = 0`).
  - Device: installs; both eyes show the title card (the screencap halves are identical); `adb shell input swipe` echoes `FWD` in the debug HUD; `--ez card true` shows the calibration card; `HKThermal` lines appear; `--ez selftest true` prints GL info (renderer, max vertex uniforms, stencil bits, samples).

**WP1: MIDI** (pure)
- *Files:* `midi/*`; tests plus the test-only `SmfWriter.kt` helper.
- *Consumes:* `contract/Performance`, `InstrumentProfile`.
- *Acceptance (JVM):*
  - **Parser:**
    - VLQ edges (`00`, `7F`, `81 00`, `FF FF FF 7F`, 5-byte invalid);
    - running status across a note stream, and cancelled by meta and sysex;
    - vel-0 off; format 0 ≡ format 1 (identical `Performance` arrays);
    - a tempo change in track 0 applied to track 1; SMPTE 25 fps/40 tpf;
    - truncated header → `TRUNCATED_HEADER`; a chunk length past EOF (clamped + warning); junk chunk skipped;
    - zero-length track; limits exceeded; drums-only → `no keyboard notes`.
  - **Builder:** FIFO pairing and overlap → restrike split; hanging notes closed; tie order (off < pedal < on); the §4.1 byte-array test.
  - **Adapter:** fold 12→36 and 120→84 on the harpsichord; legato cap 1.5 s; pedals removed; the upright drops CC66.
  - **Fuzz:** 10,000 seeded mutations (bit flips, truncations, inserted bytes) of every test file must never throw, and must finish in < 20 s total.
  - **`MidiCorpusTest`:** every asset in `assets/midi/**` parses Ok and its stats equal the catalogue fields.
  - **Speed:** the Hammerklavier finale parses and builds in < 150 ms on the JVM.

**WP2: Engine core** (pure)
- *Files:* `engine/*`; tests, `OfflineRender` (a test utility writing `app/build/renders/*.wav`), `EngineBench`.
- *Consumes:* contracts, `dsp/Limiter` (WP3; it uses a pass-through until WP3 merges).
- *Acceptance (JVM, stub bank):*
  - **Timing:**
    - a note-on at score frame F produces its first non-zero output sample at exactly F (sample-accurate, at several offsets inside a block);
    - tempo 0.5 doubles the spacing to within 1 frame;
    - seek re-derives the pedal state;
    - pause stops advancing.
  - **Pool:** the cap is never exceeded; stealing takes a damped voice before a held one; fades run 5 ms with no step > 0.02 (click test); ≤ 3 streams per key.
  - **Pedals and dampers:**
    - restrike fades the old voice with τ 60 ms ± 10 % (envelope fit);
    - **sostenuto:** latched notes sustain after key-up, notes pressed after CC66 are damped, and sustain held during the edge latches all;
    - half pedal (p = 0.44) gives a decay between the free and the damped rate;
    - keys > 88 ring on after note-off;
    - pedal noise fires exactly once per 0.33 crossing.
  - **Levels:** harpsichord RMS within 1 dB across v 20–127; piano RMS monotonic in v; Werckmeister C rate = 2^(11.7/1200) relative to equal temperament.
  - **Allocation:** `NoAllocTest` — 0 bytes allocated on the render thread over 10 s of rendering after warm-up.
  - **Speed:** `EngineBench` reports ns per stream-frame for the record.

**WP3: DSP** (pure)
- *Files:* `dsp/*`; tests.
- *Acceptance (JVM):*
  - FDN RT60 within 15 % of 1.35 s (Schroeder backward integration of the impulse response); stable over 60 s of white noise (no NaN, bounded); L/R correlation < 0.3;
  - limiter peak ≤ 0.99 on +12 dB bursts;
  - biquad magnitude at the test frequencies within 0.2 dB of the analytic value;
  - resonator bank stable for every temperament and pitch;
  - speaker voicing −3 dB point at 120 Hz ± 10 %;
  - no allocation in `process`.

**WP4: Audio I/O, clock and banks**
- *Files:* `clock/AudioClock.kt`, `bank/BankIndexCodec.kt`, `audio/*`.
- *Consumes:* `EngineCoreApi` (tests use `SineCore` until WP2 merges), `SampleBankView`.
- *Acceptance:*
  - **JVM:**
    - `AudioClockTest`: simulated block streams with tempo changes, pause, seek and timestamp jitter; `sample()` within 1 frame of the analytic answer; seqlock torn-read retry exercised by a writer thread; generation mismatch → invalid;
    - `BankIndexCodecTest` on the golden `bank.json` files, stub and grand.
  - **Device:**
    - `--ez selftest true`: probe offset logged; stub bank plays the C major scale for 10 s with `underruns=0`;
    - grand decode progress reported and total time logged; the second launch does not decode;
    - majflt on the audio thread is 0 after 60 s;
    - a route change (headset plug) switches the voicing;
    - LOW_LATENCY track granted (logged), or the fallback taken.

**WP5: Asset pipeline** (Python and data)
- *Files:* `tools/*.py`, `tools/works.json`, `tools/midi-manifest.tsv`, `tools/sankey_map.tsv`, `tools/ledger/*`, `tools/tests/*`, and everything under `app/src/main/assets/{banks,midi,licenses}` plus `catalogue.json` and `CREDITS.md`.
- *Provides:* banks (the stub on day 1; real banks after approval), test MIDIs (day 1), the corpus and the catalogue.
- *Acceptance:*
  - `python3 -m unittest` green;
  - `build_bank.py` output passes `BankIndexCodec.validate` (via WP4's JVM test);
  - peak per sample ≤ −0.5 dBFS; leading silence ≤ 2 ms;
  - `measure_pitch` report committed;
  - Krueger and Sankey sha1s match; the catalogue covers ≥ 60 works (70 if every source succeeds);
  - `size_report` ≤ 70 MB.

**WP6: Mechanics** (pure)
- *Files:* `mech/*`; tests.
- *Consumes:* `Performance`, `InstrumentProfile`, `PedalMotion`, `ClockSample`, `MechanicsFrame`.
- *Acceptance (JVM):*
  - **Touch model:** `travelMs(20)` = 230 ± 1, `travelMs(64)` = 86 ± 1, `travelMs(110)` = 20.
  - **Grand key and hammer:**
    - key depth 0 at `on − tt` and 1 at `on + tb` (±0.5 ms);
    - the hammer reaches 1 at `on` (±0.5 ms) and sits at ≤ (blow − letOff)/blow before escapement;
    - the damper starts to lift when the hammer passes 0.5.
  - **Repetition and pedals:**
    - a second note 70 ms later starts from partial height;
    - pedal travel 0→1 takes 70 ms; dampers do not move below p = 0.33;
    - sostenuto keeps a latched damper up after key-up.
  - **Tempo:** at τ = 0.5 the travel spans 2× more score frames and the same wall time.
  - **Seek invariance:** evaluating sequentially to t equals a fresh evaluation at t, over 1,000 random t.
  - **Other instruments:** upright soft pedal moves the hammer rest to 0.5; the harpsichord pluck (depth 0.45) happens at `on` and the tongue cycles on release.
  - **Speed:** 88 keys of a 20k-note performance evaluate in < 0.3 ms on the JVM.

**WP7: GL core, Player view, keyboard and grand case**
- *Files:* the WP7 rows of §2.6 (`gl/*` core, `gl/shader/{SkinnedPart,Lacquer,Color}`, `gl/scene/{InstrumentDrawable,Keyboard,Pedal,Grand,SceneList}`, `geom/{MeshData,MeshBuilder,KeyboardGeometry,GrandCaseGeometry,PedalGeometry,CameraMath,ViewPoses}`).
- *Consumes:* `AudioClockReader` (`FakeClock`), `MechanicsEvaluator` (`StubMechanics`), `Performance`.
- *Acceptance:*
  - **JVM:** 88 keys = 52 white + 36 black; octave span 164.5 ± 0.1 mm; sharp centres on the 13.71 mm slots; grand plan bounds 2.00 × 1.49 m; triangle counts within budget; off-axis matrix symmetric at shift 0 and equal to the analytic frustum.
  - **Device:** Player view of the stub scale — keys and pedals move (screencap sequence); `HKPerf` fps ≥ 29.5 at Q0; ≤ 35 draws per eye; no allocation in `onDrawFrame` (debug `Debug.getThreadAllocCount` on the GL thread is 0 after warm-up); presence floor applied; stereo comfort checked by the user with `--ef ipd`.

**WP8: Action and Inside views, upright and harpsichord**
- *Files:* the WP8 rows of §2.6.
- *Consumes:* WP7's `InstrumentDrawable`/`PartDrawable`, `GlKit`, `SkinnedPartShader` (`uClip`); `MechanicsFrame`.
- *Acceptance:*
  - **JVM:** 228 grand strings with the right counts per register (8 single, 40 bichord, 180 trichord); harpsichord 60 keys and 180 jacks; the upright strike line at 1.08 m.
  - **Device:** Action view on `restrike_fast` shows the hammer caught by the backcheck and repetition from partial height; the avsync test in the Action view (the hammer contact frame vs the click) passes the §8.4 thresholds; Inside view hammers rise and strings blur; harpsichord jacks and tongues; ≤ 35 draws per eye in every view.

**WP9: Venue and Hall view**
- *Files:* the WP9 rows of §2.6.
- *Consumes:* WP7 `GlKit`, `LightProbe` (feeding it flame positions), `ViewPoses` (Hall).
- *Acceptance:*
  - **JVM:** room bounds 10.5 × 8.0 × 5.7 m; 50 ± 2 flames; mirror planes and reflected positions correct.
  - **Device:** APL for each view and instrument via `tools/apl.sh` within the §5.4 budgets; mirrors off when there is no stencil; Q1 drops reflections; head look-around in the Hall; draws ≤ 35.

**WP10: Library and UI**
- *Files:* the WP10 rows of §2.6.
- *Consumes:* `Library`, `Catalogue`, WP1 `PerformanceBuilder` (validator), `UiAction`, `Gesture`, `UiContext`.
- *Acceptance (JVM):*
  - **InputRouter:** the full §1.3 table as a parameterised test (context × gesture → action); invert-vertical works.
  - **Library and menu models:** `LibraryModel` navigation and paging; `MenuModel` adjust ranges (tempo stays within 50–150, seek emits ±10 s).
  - **Catalogue:** `CatalogueCodec` round-trips the golden `catalogue.json`.
  - **Import validation:** `ImportValidator` rejects non-MIDI, >4 MiB and drums-only files with human-readable reasons; `sanitize` handles `../`, Unicode (keeps é and ü) and 200-character names.
  - **Import store:** `ImportStore` byte-identical copy (sha1 equal), `(n)` uniqueness, rejected-file move with its `.why.txt`, index rebuilt after deleting `index.json`.
- *Acceptance (device):* browse to any bundled piece and play it; the Imported category appears after an adb push + rescan.

**WP11: Companion server**
- *Files:* `net/*`, `assets/companion.html`.
- *Consumes:* `CompanionApi` (implemented by `AppController` via its wiring note).
- *Acceptance:*
  - **JVM:** `CompanionServerTest` starts the server on 127.0.0.1 with a fake API and checks:
    - GET `/` injects the token;
    - `/api/library` is valid UTF-8 JSON (Für Elise survives);
    - a multipart upload of a test `.mid` → `ok`, with the bytes identical;
    - upload without the token → 403;
    - a 5 MiB upload → 413;
    - an unknown path → 404;
    - a handler exception → JSON 500.
  - **Device:** upload from a phone on the same Wi-Fi; `/api/state` refreshes; transport buttons control playback.

### 7.3 Integration order and milestones

Every milestone ends with `tools/ci.sh` green, `tools/run.sh` installing it on the glasses, `tools/smoke.sh M<n>` passing, and a short on-glasses check by the user.

| M | Name | Merged | Runs on the glasses | Gate |
|---|---|---|---|---|
| **M0** | Shell | WP0 | Title card in both eyes, calibration card, gesture echo, CONTROL, thermal log, self-test GL info | Screencaps, logcat |
| **M1** | First sound | WP1, WP2, WP3 (limiter), WP4 (output + clock), WP5 (test MIDIs, stub bank) | Tap plays `test/scale.mid` and `chords_pedal.mid` on the stub bank; HUD shows streams and underruns | 5 min with 0 underruns; audio thread < 10 % |
| **M2** | Real grand | WP4 (decoder, store, warmer), WP5 (grand bank + 5 Start-here MIDIs), WP3 (FDN) | First-run decode with progress, then Für Elise and the Prelude in C on the Salamander grand with room reverb | Decode ≤ 60 s; stress64 for 10 min: 0 underruns, ≤ 40 % core, majflt 0, battery ≤ 39 °C |
| **M3** | Keys move | WP6, WP7 | Player view: keyboard and pedals animated from the audio clock; follow framing | avsync passes (§8.4); 30 fps; ≤ 35 draws |
| **M4** | Swipe to the hammers | WP8 (grand action), WP10 (InputRouter only) | Swipe forward/back switches Player ↔ Action with the dip; hammers strike on the sound | avsync in the Action view; swipe latency < 100 ms |
| **M5** | Library and three instruments | WP10 (all), WP5 (full catalogue, upright and harpsichord banks), WP8 (upright and harpsichord Player/Action) | Browse and play any piece; instrument switch mid-piece; harpsichord folding and legato; menu transport | Each instrument has a scripted CONTROL run; 0 underruns |
| **M6** | Import | WP11, WP10 (import store) | Phone upload, adb push, malformed file rejected with its reason, imported piece plays | The smoke script uploads with `curl` |
| **M7** | The Konzertzimmer | WP9, WP3 (reverb per view) | Hall view with candles and mirrors; Salon and Stage levels | APL budgets; **45-min soak** (§8.5) |
| **M8** | Realism pass | WP8 (Inside), WP3 (resonance), WP2 and WP10 (temperaments, registrations, una corda, practice mute, damper-T60 calibration against the release samples) | All four views; tuning presets | Listening checklist with the user; soak again |
| **M9** | Release candidate | WP0 (credits, README, final wiring), WP5 (ledger) | Everything | Full regression (`smoke.sh all`), ledger complete, commit |

**How work runs in parallel:**
- **Critical path:** WP0 → (WP2 ∥ WP4) → M1 → WP5 grand bank (gated on the download approval) → M2.
- **Independent of that path:** WP1, WP3, WP6, WP7, WP10 and WP11 start on day 0 against stubs.
- **WP8 and WP9** build their `geom/` from day 0, and wire their drawables once WP7's `GlKit` and shaders merge (M3).

---

## 8. Verification plan on the real glasses

### 8.1 Build and install

```bash
cd /Users/me/Projects/Hammerklavier
tools/ci.sh                                   # purity + JVM tests + assembleDebug + python tests + ledger + size
tools/run.sh                                  # = the chain below, device chosen by -s, && so a failed build never installs
./gradlew :app:assembleDebug && \
adb -s A06B4A96A733283 install -r app/build/outputs/apk/debug/Hammerklavier-debug.apk && \
adb -s A06B4A96A733283 shell am start -n com.tropicalstream.hammerklavier/.MainActivity
# flat captures: … am start … --ez mono true
# bench hatch (off-head the launcher force-stops apps): set, test, then RESET
adb -s A06B4A96A733283 shell settings put global device_wearing 1   # … later: put global device_wearing 0
adb -s A06B4A96A733283 shell wm dismiss-keyguard
```

### 8.2 CONTROL broadcast (`am broadcast -a com.tropicalstream.hammerklavier.CONTROL …`)

| Group | Extras |
|---|---|
| Views | `--ei view 0..3` · `--ei framing 0\|1` |
| Playback | `--es play <movementId\|test/scale>` · `--es instrument grand\|upright\|harpsichord` · `--ez pause true` · `--ez resume true` · `--el seek <ms>` · `--ef tempo 0.9` · `--ez next true` · `--ez prev true` |
| Display | `--ei quality -1\|0..3` · `--ez recenter true` · `--ef fov 31.2` · `--ef ipd 0.6` · `--ei lead 30` · `--ez card true` · `--ei floor 22` · `--es venue salon\|stage\|instrument\|passthrough` · `--ez msaa false` |
| Sound | `--es tuning A415-WERCKMEISTER_III` · `--es registration EIGHT_FOUR` |
| Library and input | `--ez library true` · `--ez menu true` · `--ez rescan true` · `--es gesture tap\|double\|triple\|fwd\|back\|up\|down` (synthetic, through `InputRouter`) |
| Diagnostics | `--ez debug true` · `--ez selftest true` · `--ez avsync true` |

### 8.3 Self-test (`--ez selftest true` → `logcat -s HKSelfTest`)

The self-test prints PASS or FAIL lines for:
1. each `bank.json` valid;
2. PCM cache valid, or decoded in N s;
3. decoder probe offset;
4. catalogue parsed (N works) and every movement asset present;
5. 20 random bundled files parse;
6. AudioTrack running (sample rate, buffer frames, fast path, underruns);
7. GL renderer string, `GL_MAX_VERTEX_UNIFORM_VECTORS`, stencil bits, MSAA samples;
8. companion server bound (URL);
9. free space in `filesDir`.

`tools/smoke.sh M<n>` runs the milestone's CONTROL sequence, grabs screencaps and greps logcat for FAIL, `AndroidRuntime`, `FRAME HITCH` and non-zero `underruns`.

### 8.4 Metrics and how they are measured

| Metric | How | Pass |
|---|---|---|
| CPU per thread | `adb -s … shell top -H -b -d 2 -n 15 -p $(adb -s … shell pidof com.tropicalstream.hammerklavier)` → `HK-Audio`, `GLThread`, main | Audio ≤ 40 % at stress64 and ≤ 20 % typical; GL ≤ 50 %; process ≤ 120 % (of 400) |
| Audio render time | `HKPerf audioAvgUs/audioMaxUs` (nanoTime around `render`) | avg ≤ 1.5 ms, max ≤ 3 ms at stress64 |
| Underruns | `AudioTrack.getUnderrunCount()` in `HKPerf` every 10 s; cross-check with `dumpsys media.audio_flinger` (`underruns=`) | 0 after warm-up across a 45-min soak |
| Page faults | `majflt` (field 12) of `/proc/<pid>/task/<audio tid>/stat` in `HKPerf` | Δ = 0 after 60 s |
| Allocation | Debug build: `Debug.getThreadAllocCount()` for the audio and GL threads in `HKPerf` | 0 per 10 s after warm-up |
| Temperature | `dumpsys battery` (tenths °C) + `HKThermal` level changes; `soak.sh` samples every 30 s | Plateau ≤ 41 °C at 22 °C room temperature (Q1 allowed); never Q3 in normal use; no reboot |
| Frame rate and hitches | `HKPerf fps`, `FRAME HITCH` (> 120 ms) | ≥ 29.5 fps at Q0; ≤ 1 hitch per 10 min |
| A/V sync | `--ez avsync true`: the `avSync()` performance (a note per second, v = 127) with a 200×200 white quad on the frame whose heard time crosses each onset. `scrcpy -s A06B4A96A733283 --no-playback --record=<scratch>/avsync.mkv --time-limit=30`, then `python3 tools/avsync.py avsync.mkv` | \|mean\| ≤ 15 ms, std ≤ 8 ms. The mean offset sets the default `lead`. A human clap test on the glasses confirms, since the recording excludes panel and speaker latency |
| APL | `adb -s … exec-out screencap -p > v.png` (or a scrcpy frame if the GL layer isn't captured) → `tools/apl.sh v.png` | §5.4 budgets |
| Decode time | `HKAudio decode grand 1575 s audio in N s` | ≤ 60 s |

### 8.5 Soak

`tools/soak.sh 45` does the following:
- plays the Hammerklavier sonata (4 movements, 35 min) and then the Moonlight on the grand, in the Hall view at the Salon level;
- runs a stress64 loop for the last 5 minutes;
- logs HKPerf and the battery temperature every 30 s;
- **fails** on any adb disconnect (a reboot), battery ≥ 44 °C, underruns > 0 after warm-up, sustained fps < 19, or an app crash.

It is repeated on the harpsichord (Goldberg) at M8.

---

## 9. Risk register and non-goals

### 9.1 Risks

| # | Risk | Likelihood / impact | Mitigation |
|---|---|---|---|
| R1 | Thermal reboot (a sibling app rebooted the glasses) | Med / High | Table-driven DSP, stream caps, a VBO-only renderer at 30 fps, a governor with Q3 display rest. Stress and soak gates at M2 and M7 before any feature work continues |
| R2 | Audio-thread CPU above the budget in ART | Med / High | Bulk-copy + linear interpolation; `EngineBench` numbers at M1; caps of 64/48/32; resonance optional; drop layer crossfades at Q1 if needed |
| R3 | mmap page faults cause underruns on a low_ram device | Med / Med | `load()`, head pre-touch, `PageWarmer` look-ahead, majflt metric, a larger buffer via `LatencyTuner`, fallback tail cap |
| R4 | First-run decode too slow or interrupted | Med / Med | Progress on the title card, the `.ok` marker, two decoder threads, the harpsichord decodable on its own, stub bank meanwhile |
| R5 | Opus decoder offset or behaviour differs | Low / Med | Probe asset measures it once; exact frame counts in `bank.json`; stub bank fallback |
| R6 | A/V sync drifts (timestamp unavailable, FastMixer refused) | Low / Med | Timestamp mapping with estimate fallback; block-record ring; avsync gate at M3 and M4; tunable lead |
| R7 | Stereo discomfort (off-axis unproven in this app) | Med / Med | CONTROL `ipd` and `fov`; the per-view zero-parallax table; mono mode as the last resort |
| R8 | Black lacquer invisible, or the room too bright (APL) | Med / Med | Calibration card at M0; rim, probe and edge overlay; venue levels with measured APL |
| R9 | EGL config without stencil or MSAA | Med / Low | Chooser fallback chain; mirrors and MSAA are optional |
| R10 | Sample or MIDI downloads not approved or failing (piano-midi.de 418, IMSLP click) | Med / Med | Stub bank and test MIDIs keep M0–M4 unblocked; Wayback with SHA-1; the catalogue omits missing works without failing the build |
| R11 | Malformed or hostile import crashes the app | Med / High | Bounded parser, fuzz test, validation before accepting, rejected folder with a reason, the parse runs on the loader thread |
| R12 | Licence breach (Sankey bytes, CC BY-SA adaptation, attribution) | Low / High | Byte-identical sha1 gate, runtime parsing only, no export, ledger CI gate, Credits panel |
| R13 | Gesture ambiguity (touch + key double-fire, natural-mode flip) | Med / Low | WanderQuest engine dedup, forward/back semantics, invert-vertical setting, synthetic-gesture CONTROL for tests |
| R14 | Parallel agents collide | Med / Med | Frozen contracts, disjoint ownership, wiring notes, a single integrator, the CI gate |
| R15 | Launcher force-stops the app during bench tests | High / Low | `device_wearing 1` hatch, documented with its reset |
| R16 | Unknown harpsichord pitch standard or uneven Salamander tuning | Med / Low | `measure_pitch.py` and `tune_ret.txt` into `tuneCents`; frequency-based sample choice |
| R17 | Built-in speakers make the bass inaudible | High / Low | Speaker voicing EQ; flat on headsets; the README recommends headphones |

### 9.2 Non-goals for v1

- Live MIDI input (USB or BLE keyboards) and playing the virtual keyboard by hand.
- Audio or video recording and export (also required by Sankey's terms and the CC BY-SA "moving images" clause).
- A fortepiano: Silbermann or Walter samples and actions. The clavichord. Physical-modelling synthesis.
- Held-key sympathetic resonance with the pedal up (R7), duplex scale modelling, the harmL/harmS resonance samples, the Accurate-Salamander upgrade.
- CC7/CC11 dynamics, pitch bend, General MIDI multi-instrument playback. All non-drum channels play on the one keyboard.
- Playback in the background or with the display off (it pauses on `onPause`), and any foreground service.
- Camera fly-through transitions and FOV animation (the dip cut is used), multiview rendering, and ES 3 features.
- Score or notation display, editing, rubato tools, loops and practice modes.
- The complete 555 Scarlatti sonatas and the non-commercial MAESTRO/SMD packs. Beethoven op. 109 and 110 and op. 111/ii, which have no clean source (users can import them).
- Spoken programme notes (the glasses have no TTS engine) and any cloud catalogue, accounts or analytics.
