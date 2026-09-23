# Hammerklavier: architecture proposal, realism-first

Proposal date: 2026-09-22. Lens: **make the sound and the motion as close to the real instruments as the RayNeo X3 Pro allows.** Where realism and the device budget pull apart, this document states the choice and why.

It builds on five research reports in `docs/research/`:

- `sampled-instruments.md` and `sample-download-manifest.tsv`
- `instrument-mechanics-and-sound.md`
- `repertoire.md`
- `engine_reuse.md`
- `visual_design.md`

It also draws on `/Users/me/Documents/FABLE_X3_STARTER_GUIDE.md` and on the MathCosmos engine (`StereoMathRenderer.kt`, `MainActivity.kt`, `MathCosmosView.kt`). Numbers taken from those reports keep their tags there. Design values chosen here are marked **[D]**. Anything this proposal could not check is marked **UNVERIFIED**.

---

## 0. The decisions in one screen

| Topic | Decision | Why it is the realistic choice |
|---|---|---|
| Grand samples | **Salamander V3, all 16 velocity layers** × 30 notes. Plus 88 release samples, the `harmL/harmS` resonance samples and 4 pedal samples. Salamander's own retuning table and per-file onset offsets are applied. The build tool also smooths the level from one layer to the next. | With 16 real dynamics the engine picks one layer and never blends two different recordings. Blending two takes smears the attack and causes phasing. Salamander's own SFZ also hard-switches. Using one recording per note also halves the audio CPU compared with cross-fading. |
| Upright samples | VCSL "Knight" (2 layers) plus the VSCO-2 CE pp layer, giving **3 layers**. It includes 45 release samples and 8 pedal noises. Layers are cross-faded with equal power. | This is the only redistributable upright with releases and pedal noise. With only 3 layers, a cross-fade is less wrong than a hard switch. |
| Bach-era instrument | **A harpsichord, and it is the same instrument you see and hear.** VCSL Flemish 8′ + 4′ with its own jack-fall releases. It is drawn as a single-manual Flemish-German harpsichord (ravalement compass FF–f‴, MIDI 29–89), because that is what the samples are. Sankey also played his two-manual works on a single manual. | We never put a harpsichord sound inside a Silbermann or Blanchet case. The English lute stop is dropped because it comes from a different instrument. |
| Fortepiano | **Not shipped.** There is no redistributable set, and faking one by filtering the grand is ruled out. The engine and scene have a fourth slot. It is switched on only if a licensed set arrives, and it would get a case to match whatever was sampled. | The brief asks for real samples. |
| Format | **Ogg Opus 128 kb/s VBR, 48 kHz stereo** in the APK, about 75 MB. On first launch it is decoded once into a 16-bit PCM cache in internal storage, about 1.1 GB. | The glasses decode Opus and mix at 48 kHz. 128k is more than enough on the quiet decays where Opus artefacts would show. |
| RAM | **Heads stay resident, tails stream from disk.** The first 250 ms of every sample lives in heap `ShortArray`s (about 45 MB for the grand). The tails stream from the PCM cache into lock-free ring buffers, one per voice, filled by a separate streamer thread. | This lets 16 layers fit a `low_ram` device. The audio thread never touches a file or page-faults. |
| Voices | Grand: 96, one read stream each, 4-point Hermite resampling. The voice stealer puts damping voices first. | Beethoven with pedal builds up 60–90 voices. |
| Dampers | Per register: an extra decay rate plus a low-pass filter that closes as the damper lands. Treble keys 89–108 have no dampers on the grand. Half-pedal is continuous, re-pedalling freezes the release, and sostenuto is a true latch. | This follows the damper physics in the mechanics report (R3, R9, R12, R13 and R15). |
| Sympathetic resonance | **88 string resonators**, one tuned comb filter per string with fractional delay. Each is tuned to the measured f0 of that string's samples and to the current temperament. They are fed by the dry mix and switched by the same damper state as the voices. Under una corda, the struck note's own resonator is excited too, standing in for the unstruck string. | One comb resonates at all the string's harmonics. That gives held-key ring, the pedal-down halo and an always-ringing undamped treble for about 5% of a core. |
| Room | **Reverb and room geometry come from one room description.** Early reflections are computed from the room walls (image sources) at the listener's actual position, which follows the camera. They feed an 8-line feedback delay network (FDN) whose reverb times per frequency band come from Sabine's formula on the room's materials. About 1.6 s at mid frequencies. | "What you see is what you hear." Convolution would cost about 25–45% of a core, and there is no measured impulse response of the room. |
| Clock | **The audio output is the only clock.** Events are scheduled to the sample inside the audio callback. The renderer converts `AudioTrack.getTimestamp` into the song time being heard at the moment the frame appears. It evaluates keys, hammers and pedals from the parsed MIDI data, looking up to 230 ms ahead. | Keys start to move before the sound, exactly as in Goebl 2005. Picture and sound cannot drift apart. |
| Views | Swipe forward/back cycles **Player → Action (hammers striking strings, in cutaway) → Inside → Hall**. Swipe up/down changes the framing within a view. Tap is play/pause. Double-tap is the menu or back. | These are the brief's two views plus two more that show the mechanism truthfully. |
| Venue | The Sanssouci Konzertzimmer (1746–47) on a candlelit evening: gilt, flames and mirrors are drawn and the white walls are left out. | See `visual_design.md`. It is the right room, and it suits the waveguide. |

---

## 1. Product definition

### 1.1 Instruments offered

| Menu name | Sound | Sight | Pedals and stops | Tuning default |
|---|---|---|---|---|
| **Concert grand** | Salamander V3 (Yamaha C5): 16 layers, 30 roots a minor third apart, releases, resonance samples, pedal noises | 200 cm C5-size grand in ebony lacquer, lid on full stick | Una corda (CC67), sostenuto (CC66), damper (CC64 with continuous half-pedal) | A440, equal temperament |
| **Parlour upright** | VCSL Knight vl1/vl2 plus VSCO-2 CE dyn1 (pp): 45 roots a whole tone apart, releases, pedal noises | Yamaha U3-size case (verified dimensions), walnut finish by default because ebony disappears on the waveguide, labelled "sampled: Knight upright" | Soft pedal (hammer rail), damper pedal, and a practice mute that the user toggles | A440, equal temperament |
| **Harpsichord, Bach's keyboard** | VCSL Flemish 8′ (28 roots) and 4′ (26 roots), whole-tone spacing, jack-fall releases for each stop | Single-manual Flemish-German harpsichord, FF–f‴. Bone naturals and black-stained sharps, Flemish block-printed papers, Latin lid motto, painted soundboard | Registrations 8′, 4′ and 8′+4′. No pedal: CC64 becomes "finger pedalling" (held keys, visible) | **A415 Werckmeister III**. A415 is reached by moving the whole keymap down one key, like a real transposing harpsichord (§3.4), so the timbre stays intact |
| *(slot 4) Fortepiano* | Not shipped. Enabled only if a licensed multisample exists (the Dore Mark Clementi 1808 needs written permission). Its case would be modelled on the sampled instrument, not on the Silbermann | — | Knee levers | A430 Vallotti |

**Why these three.** They are the three real instruments we can legally ship, and each pairs a sound with a matching sight.

The harpsichord is the honest reading of "early Bach-style piano". Bach's keyboard was the harpsichord. The only "Bach piano" is the Silbermann, and it has no licensed samples. The About screen says this in one line: "Bach played Silbermann fortepianos in Potsdam in 1747; no recording of one is licensed for this app, so the harpsichord stands in for his keyboard."

### 1.2 Views (swipe forward/back cycles them; each view keeps its own framing)

| # | View | What it shows | Swipe up/down (framing) | Head look-around |
|---|---|---|---|---|
| 0 | **Player** | The whole keyboard and the pedals from the bench. Keys dip 4.4 px and pedals travel 5.5 px (`visual_design.md` §4.1) | Whole keyboard ↔ **Follow**: three octaves at 28 px per white key, tracking the notes, with a picture-in-picture of the pedals | ±5° parallax |
| 1 | **Action** | A cutaway of the melody note's action: the key rocks, the jack escapes, the hammer flies, strikes, and is caught by the backcheck, the damper lifts, and the string blurs as it vibrates. On the harpsichord: jack, quill pluck, the tongue swinging back, the damper landing | Follow the melody ↔ follow the bass ↔ fixed on middle C | ±5° |
| 2 | **Inside** | The lid lifted away, looking down the string bed: rows of hammers flick up and the strings visibly shimmer, including strings that ring in sympathy | Whole bed ↔ close-up following the centre of the notes | ±5° |
| 3 | **Hall** | Row 3 of the Konzertzimmer, with world-locked look-around (the chandelier is overhead) | Wide (40°) ↔ life-size (18.3°, the optical field) | World-locked, ±60° yaw, +45° pitch |

Transitions fly the camera for 0.9 s with the field of view fixed, as in `visual_design.md` §4.5. **The listening position follows the camera** (§3.10). You hear the bench, the inside of the case, or row 3.

### 1.3 Temple-pad input

The pad is handled by WanderQuest's `TrackpadGestureEngine`, copied with a `cyttsp6` name filter added on the key path. Swipes are read as forward/back, never left/right, because the direction flips with the system's natural-mode setting.

**Stage (no menu open)**

| Gesture | Action |
|---|---|
| **Swipe forward / back** | Next / previous view. This is the brief's rule. |
| Swipe up / down | Next / previous framing within the current view (§1.2) |
| Tap | Play / pause. The 300 ms needed to rule out a double-tap is acceptable for this. |
| Double-tap | Open the menu on the Now Playing tab |
| Triple-tap | Recenter the gaze and re-seat the keyboard in view |
| Left pad | System media volume (the app does not touch it) |

**Menus** (a panel covering the left 300 px of each eye; the stage stays visible and keeps playing)

| Gesture | Action |
|---|---|
| Swipe up / down | Move the highlight one row per gesture (latched, re-armed on UP or CANCEL). A long swipe of more than 3× the threshold moves 5 rows. |
| **Swipe forward / back** | Previous / next **tab**: Now Playing ⟷ Library ⟷ Instrument ⟷ Room ⟷ Settings. In edit mode, it adjusts the value instead. |
| Tap | Activate the row: open a shelf, play a piece, toggle, enter edit mode, or confirm an edit |
| Double-tap | Go back one level, or cancel an edit. At the top level it closes the menu. |
| Triple-tap | Close the menu from anywhere |

**Title card.** Tap opens the Library. On first run it shows the voicing progress (§3.2).

**Calibration cards** (presence floor, A/V sync). Swipe up/down adjusts, tap confirms, double-tap cancels.

### 1.4 Menu contents

- **Now Playing**
  - Play/Pause · Restart · Next movement · Previous movement.
  - Seek: in edit mode, a forward/back swipe moves ±10 s and up/down moves ±1 bar.
  - Tempo 50–150% in 5% steps.
  - Loop: off / movement / work.
  - Credits: shows the licence line for the playing piece.
- **Library**
  - Shelves from `repertoire.md` §4: Start here, Bach young virtuoso, Bach teaching, WTC, Suites and Partitas, Handel, Scarlatti, French clavecinists, Galant, Haydn, Mozart, Clementi, Beethoven. **Imported** comes first when it is not empty, then Recently played.
  - Drill down: shelf → work → movement.
  - Each row shows the default instrument glyph and the duration.
- **Instrument**
  - Grand / Upright / Harpsichord. While an instrument is still being voiced its row shows "voicing 43%" and cannot be picked.
  - Harpsichord registration: 8′, 4′, 8′+4′.
  - Temperament: Equal, Werckmeister III, Vallotti, Young II, Kirnberger III, Kellner, Lehman (labelled "one proposal"), ¼-comma meantone.
  - Pitch: A440 / 430 / 415 / 392.
  - Touch response (veltrack 50–100%).
  - Sympathetic resonance: off / natural / rich.
  - Harpsichord pedal: finger-pedal / ignore.
  - Una corda: follow the file / off.
  - Upright practice mute.
- **Room**
  - Venue level: Salon, Stage, Instrument, Passthrough.
  - Palette: Sanssouci 1747 / Stadtschloss 1747.
  - Reverb: drier / natural / wetter, which is ±4 dB on the reverberant field.
  - Listener: follows the view / bench / row 3.
  - Candle flicker.
- **Settings**
  - Presence-floor card, A/V sync card, stereo depth.
  - Speaker bass enhancer: auto / on / off.
  - Companion server: on/off, URL and token.
  - Diagnostics overlay: fps, CPU, voices, underruns, stream starvation.
  - About and Credits.

### 1.5 Importing scores

- **Companion phone page.** NanoHTTPD serves plain HTTP on port **19112**. The page lives in `assets/companion/index.html`, and the token is injected into it when served.
  - **Upload:** drag and drop, file picker or whole folder. Each file is sent as its own multipart POST with a progress bar. Allowed: `.mid`, `.midi`, `.kar` and RIFF-RMID, up to 4 MB each.
  - **Library:** list with play and delete buttons.
  - **Remote:** play, pause, next, previous, tempo, instrument and view.
  - **Now playing:** polls `GET /api/state` every second.
  - Each upload is parsed on the device, and the response reports title, duration, note range and pedal type, or the parse error.
- **adb push folder**: `getExternalFilesDir(null)/Scores/`, that is

  ```
  adb -s A06B4A96A733283 push MyPiece.mid /sdcard/Android/data/com.tropicalstream.hammerklavier/files/Scores/
  ```

  The app rescans the folder in `onResume`, every 10 s while the Library tab is open, and on `--ez rescan true`. It does not use FileObserver, because inotify is not reliable through the FUSE storage layer.
- **Storage rules** (copied from TapVibe's `MusicLibrary`): bytes are copied exactly, names sanitised, and ` (n)` added on a name clash. The `MThd` header (or `RIFF…RMID`) is checked before accepting. Unparseable files stay in the library marked "unreadable" with the parser's error and byte offset.
- **Default instrument for an import** is guessed from the file name and contents:
  - A composer token of `bach`, `scarlatti`, `handel`, `couperin` or `rameau` **and** no CC64 **and** at most 2 notes in 1000 outside 29–89 → harpsichord.
  - Otherwise → grand.
- Imports are tagged `licence=user`. They are played only, never re-uploaded.

### 1.6 On-screen status

Everything 2D is a view inside the single child of `BinocularSbsLayout`. It uses a hardware layer, refreshes at most twice a second, and has no dark boxes.

- **Title card**: HAMMERKLAVIER in gilt; "voicing the grand · 43%" on first run; `companion: http://192.168.x.x:19112 · key 7F2Q`, or "no Wi-Fi".
- **Now-playing caption**, top-left for 6 s after a piece starts, then fading to one small line. For example:
  - "Beethoven · Sonata op. 106 'Hammerklavier' · I. Allegro"
  - the credit line, e.g. "Performance: Bernd Krueger · piano-midi.de · CC BY-SA 3.0 DE"
- **Progress**: a gilt hairline across the bottom 12 px of the safe area, with `3:12 / 9:53` updating once a second.
- **Chips**, top-right, shown only when relevant:
  - instrument and tuning ("Harpsichord · A415 Werckmeister III · 8′+4′");
  - "3 notes folded", "pedal: finger-pedalled" or "7 channels merged";
  - thermal ("cooling · 20 fps").
- **Diagnostics overlay** (Settings or `--ez debug true`): fps, hk-audio CPU %, voices / peak, underruns, starved streams, battery °C, quality level, A/V offset.

---

## 2. Module architecture

### 2.1 Threads

| Thread | Priority | Owns | Talks to others through |
|---|---|---|---|
| UI (main) | default | `MainActivity`, `InputRouter`, menus and HUD, `PlaybackController`, `ThermalGovernor`, CONTROL receiver | `EngineCommandRing` (SPSC) → audio; `StageControl` calls, marshalled with `queueEvent` → GL |
| `hk-audio` | `THREAD_PRIORITY_URGENT_AUDIO`, `Thread.MAX_PRIORITY` | `AudioOutput`, `EngineGraph` (sequencer, voices, dampers, DSP) | Reads commands from the ring. Writes `SongClock`, `EnergyRing`, `EngineStatus` and `StreamRequestRing` |
| `hk-stream` | `THREAD_PRIORITY_AUDIO` | `Streamer`: `FileChannel` reads from the PCM cache into each voice's `StreamRing` | `StreamRequestRing` (audio → streamer); `StreamRing` (streamer → audio) |
| `hk-voicer` | `THREAD_PRIORITY_BACKGROUND` | `PcmCacheBuilder` (MediaExtractor/MediaCodec Opus → PCM) | Progress callbacks → UI |
| `hk-loader` | `THREAD_PRIORITY_BACKGROUND` | SMF parse, `PerformanceBuilder`, catalogue load, import validation | Posts immutable results → UI |
| GL | GLSurfaceView default | `StageRenderer`, `MechanismModel`, meshes, `GlyphBoard` | Reads `SongClock` and `EnergyRing` (lock-free) and the immutable `Performance` |
| Sensor | main looper | `GazeCamera` | `@Volatile` yaw and pitch |
| NanoHTTPD | pool | `CompanionServer` | `mainHandler.post` → UI |

Two rules follow from the sibling apps' house rules (`engine_reuse.md` §4.2):

- Nothing allocates on `hk-audio` or `hk-stream` after start-up. Every cross-thread object is either immutable or passed through a lock-free ring or seqlock.
- **No `android.*` imports** in `core/`, `midi/`, `mech/`, `audio/` (except `audio/AudioOutput.kt`), `audio/dsp/`, `scene/` or `venue/`. They must run as plain JVM unit tests.

### 2.2 Data flow

```
 assets/catalog/catalog.json ─┐                          assets/instruments/<kind>/{instrument.json, samples/*.opus}
 files/Scores/*.mid ──────────┤                                        │ (first run, hk-voicer)
                              ▼                                        ▼
                     library/LibraryRepository                  bank/PcmCacheBuilder ──► files/pcm/<kind>-<hash>.pcm
                              │ bytes                                  │
                              ▼ (hk-loader)                            ▼
   midi/SmfReader → midi/PerformanceBuilder(instrument) ──►  core/Performance (immutable)   bank/SampleBank (heads in RAM)
                              │                                   │            │                    │
                              ▼                                   │            │                    │
                   app/PlaybackController (UI) ── EngineCommand ──┼──► hk-audio: Sequencer → VoicePool → DamperModel
                              │                                   │        → ResonanceBank → Direct/ER/FDN → Limiter → AudioTrack
                              │                                   │            │  ▲ StreamRing (tails)      │
                              │                                   │            │  └──── hk-stream ◄─────────┘ StreamRequestRing
                              │                                   │            ▼
                              │                                   │   SongClock (segments + getTimestamp)   EnergyRing (88 energies/block)
                              ▼                                   ▼            │                          │
                   render/StageView ──► GL thread: SongClock.read(photonTime) → songUs → mech/<Instrument>Mechanism.evaluate(Performance, songUs, energy)
                                                   → core/ScenePose → uniform arrays → skinned draws (keys, hammers, dampers, jacks, strings, pedals)
```

### 2.3 The timing model

1. **One master clock.** `hk-audio` counts `framesWritten`, and song time advances only by the frames it renders:

   ```
   songUs += blockFrames × (1e6 / 48000) × tempoScale     // tempoScale = 0 while paused
   ```

   Each time tempo, play/pause or a seek changes the mapping, the audio thread writes a `SongClock` segment `(frame0, songUs0, usPerFrame, generation, playing)`.
2. **Events are scheduled to the sample inside the callback.** Each 256-frame block is split into eight 32-frame control periods (1.5 kHz control rate). The `Sequencer` dispatches events from `Performance.evUs` whose time falls inside a period.
   - Note-ons are dispatched `maxPreroll` (50 ms) early. Each voice gets a start delay in frames so that **the sample's measured hammer onset lands exactly on the event time**: `startFrame = frameOf(t_on) − onsetFrame(sample)`. Each sample keeps 3 ms of pre-roll before its onset, and any finger or keybed noise in the recording sits inside that pre-roll.
   - Note-offs, damper landings and pedal edges take effect at control-period boundaries. That is at most 0.67 ms of error, which is inaudible.
3. **Presentation time.** Every 20 blocks (≈107 ms) `hk-audio` calls `AudioTrack.getTimestamp(ts)` and publishes `(ts.framePosition, ts.nanoTime)`. Both sides use `CLOCK_MONOTONIC`, the same base as `System.nanoTime`. Until the first valid timestamp arrives, it publishes `framesWritten − bufferedFrames − 192` instead.
4. **Renderer.** The Choreographer callback hands the vsync `frameTimeNanos` to the GL thread. The renderer computes:

   ```
   photon = frameTimeNanos + displayLatency (default 40 ms [D], calibrated) + userAvOffset
   presentedFrame(photon) = ts.framePosition + (photon − ts.nanoTime) × 48000/1e9
   songUs_vis = segment lookup (ring of 8 segments) of presentedFrame
   ```

   A slew corrects 10% of any error per frame and snaps if the error is over 30 ms (a seek), so updates from `getTimestamp` do not jitter the picture.
5. **Look-ahead.** The renderer evaluates each key's mechanism from the immutable per-key note list, not from the sound. A key whose next note-on is less than `keyTravelMs(v)` away (up to 230 ms) is already moving: `t_start = t_on − tt(v)`, per `instrument-mechanics-and-sound.md` §1.2.
   - Travel times are physical, so they are converted to wall time with the current tempo scale: `Δwall = (t_on − songUs_vis) / tempoScale`.
   - The pedal curves already carry their lead, because `PedalShaper` builds them that way (§4.4). Audio and picture read the **same** curve, so the dampers are heard lifting at the moment the pedal is seen passing ⅓ of its travel.
6. **The live snapshot.** The only state the renderer needs from the audio thread, besides the clock, is how strongly each string is actually sounding. That includes sympathetic ringing, stolen voices and damping. `hk-audio` writes 88 floats per block into `EnergyRing`, a 32-slot seqlock ring (170 ms), tagged with the frame index at which that block will be heard. The renderer reads the slot nearest `presentedFrame`.

### 2.4 Files, responsibilities and public APIs

Package root `com.tropicalstream.hammerklavier`. WP = work package (§7).

#### `core/`: frozen contracts (WP1, written first)

```kotlin
// core/Kinds.kt
enum class InstrumentKind(val id: String, val keyLo: Int, val keyHi: Int, val lastDamper: Int) {
    GRAND("grand", 21, 108, 88), UPRIGHT("upright", 21, 108, 88 /* overwritten from instrument.json */),
    HARPSICHORD("harpsichord", 29, 89, 127), FORTEPIANO("fortepiano", 29, 88, 127) }
enum class ViewId { PLAYER, ACTION, INSIDE, HALL }
enum class VenueLevel { SALON, STAGE, INSTRUMENT, PASSTHROUGH }
enum class Palette { SANSSOUCI_1747, STADTSCHLOSS_1747 }
enum class PedalMode { NONE, SWITCH, CONTINUOUS }
class Vec3(@JvmField var x: Float = 0f, @JvmField var y: Float = 0f, @JvmField var z: Float = 0f)

// core/Ev.kt  (packed event word: type 4 bits | tag 12 bits | key 8 bits | value 8 bits)
object Ev {
    const val NOTE_ON = 1; const val NOTE_OFF = 2; const val SOSTENUTO = 3; const val SOFT = 4
    const val PEDAL_NOISE = 5; const val BAR = 6; const val END = 15
    const val TAG_RESTRIKE = 0x001; const val TAG_FOLDED = 0x002; const val TAG_STOP_8 = 0x010; const val TAG_STOP_4 = 0x020
    fun pack(type: Int, key: Int, value: Int, tag: Int = 0): Int = (type shl 28) or (tag shl 16) or (key shl 8) or value
    fun type(w: Int) = w ushr 28; fun tag(w: Int) = (w ushr 16) and 0xFFF
    fun key(w: Int) = (w ushr 8) and 0xFF; fun value(w: Int) = w and 0xFF
}

// core/PedalCurve.kt  (piecewise-linear travel 0..1, already slewed and lead-shifted)
class PedalCurve(@JvmField val tUs: LongArray, @JvmField val p: FloatArray) {
    fun at(t: Long): Float                                 // binary search (GL thread, seeks)
    fun cursor(): Cursor
    class Cursor internal constructor(private val c: PedalCurve) {
        fun seek(t: Long); fun advanceTo(t: Long): Float }  // O(1) amortised, audio thread, no allocation
    companion object { val FLAT: PedalCurve }
}

// core/Performance.kt  (immutable; built on hk-loader, shared by audio + GL)
class Performance(
    val id: String, val generation: Int, val instrument: InstrumentKind, val durationUs: Long,
    @JvmField val evUs: LongArray, @JvmField val ev: IntArray,          // sorted; ties: OFF < pedal edges < ON
    @JvmField val keyFirst: IntArray,                                  // size 129: notes of key k are [keyFirst[k], keyFirst[k+1])
    @JvmField val onUs: LongArray, @JvmField val offUs: LongArray,
    @JvmField val vel: ByteArray, @JvmField val flags: ByteArray,      // per note, grouped by key, time-sorted
    val sustain: PedalCurve, val sostenuto: PedalCurve, val soft: PedalCurve,
    @JvmField val barUs: LongArray,
    val meta: PerformanceMeta)
data class PerformanceMeta(val title: String?, val copyright: String?, val trackNames: List<String>,
    val smfFormat: Int, val division: Int, val noteCount: Int, val foldedNotes: Int, val mergedChannels: Int,
    val droppedDrumNotes: Int, val pedalMode: PedalMode, val fingerPedalled: Boolean, val warnings: List<String>)

// core/SongClock.kt  (writer hk-audio; readers GL, UI; seqlock; no allocation on read)
class SongClock {
    fun writeSegment(frame0: Long, songUs0: Double, usPerFrame: Double, generation: Int, playing: Boolean)
    fun writeTimestamp(presentedFrame: Long, nanoTime: Long)
    fun writeFallback(framesWritten: Long, bufferedFrames: Int, nanoTime: Long)
    fun read(photonNanos: Long, out: ClockRead): Boolean
}
class ClockRead { @JvmField var songUs = 0L; @JvmField var usPerWallUs = 0.0; @JvmField var generation = 0
                  @JvmField var playing = false; @JvmField var presentedFrame = 0L; @JvmField var fromTimestamp = false }

// core/EnergyRing.kt  (88 per-key energies per audio block, tagged by presentation frame)
class EnergyRing(slots: Int = 32) {
    fun write(frame: Long, energy: FloatArray, generation: Int)        // hk-audio
    fun read(presentedFrame: Long, out: FloatArray): Boolean           // GL
}

// core/EngineApi.kt
interface Engine {
    val clock: SongClock; val energy: EnergyRing; val status: EngineStatus
    fun start(); fun stop()                                            // UI thread
    fun post(cmd: EngineCommand): Boolean                              // UI thread only (SPSC); false = ring full
    var listener: EngineListener?                                      // invoked on the UI thread
    @get:JvmName("headYaw") var headYawRad: Float                      // volatile, from GazeCamera (Hall only)
}
sealed interface EngineCommand {
    class LoadBank(val bank: SampleSource) : EngineCommand
    class SetPerformance(val perf: Performance, val startUs: Long, val play: Boolean) : EngineCommand
    object Play : EngineCommand; object Pause : EngineCommand
    class Seek(val songUs: Long) : EngineCommand
    class Tempo(val scale: Float) : EngineCommand
    class Settings(val s: AudioSettings) : EngineCommand
    class Listener(val pose: ListenerPose, val glideMs: Int) : EngineCommand
    class Quality(val level: Int) : EngineCommand
    object SyncTest : EngineCommand
}
data class AudioSettings(val temperament: Temperament, val aRefHz: Float, val veltrack: Float,
    val resonance: Resonance, val registration: Int, val harpsichordPedal: HarpsiPedal, val practiceMute: Boolean,
    val unaCordaFollowsFile: Boolean, val reverbTrimDb: Float, val speaker: SpeakerMode, val masterDb: Float)
enum class Temperament { EQUAL, WERCKMEISTER_III, VALLOTTI, YOUNG_II, KIRNBERGER_III, KELLNER, LEHMAN, MEANTONE_QC }
enum class Resonance { OFF, NATURAL, RICH }
enum class HarpsiPedal { FINGER, IGNORE }
enum class SpeakerMode { AUTO, ON, OFF }
data class ListenerPose(val ear: Vec3, val forwardYawRad: Float, val source: Vec3, val directWidth: Float,
                        val worldLocked: Boolean)                     // room frame, metres
class EngineStatus { @Volatile @JvmField var playing = false; @Volatile @JvmField var songUs = 0L
    @Volatile @JvmField var voices = 0; @Volatile @JvmField var peakVoices = 0; @Volatile @JvmField var cpu = 0f
    @Volatile @JvmField var underruns = 0; @Volatile @JvmField var starved = 0; @Volatile @JvmField var quality = 0
    @Volatile @JvmField var generation = 0; @Volatile @JvmField var bufferFrames = 0 }
interface EngineListener { fun onEnded(generation: Int); fun onOverload(stepDown: Int) }

// core/SampleSource.kt  (what the voices need from a bank; implemented by bank/SampleBank)
interface SampleSource {
    val spec: InstrumentSpec
    fun head(sample: Int): ShortArray                // interleaved stereo PCM16, frames [0, headFrames)
    fun headFrames(sample: Int): Int
    fun frames(sample: Int): Int
    fun onsetFrame(sample: Int): Int
    fun gain(sample: Int): Float                     // linear; restores natural level + layer smoothing
    fun rms(sample: Int, frame: Int): Float          // from the 10 ms envelope, linear
    fun pick(key: Int, pitchKeys: Float, velocity: Int, stop: Int, out: Pick): Int   // returns layer count 1..2
    fun release(key: Int, stop: Int, out: Pick): Int
    fun pedalNoise(down: Boolean, roundRobin: Int, out: Pick): Int
    val streams: StreamService
}
class Pick { @JvmField val sample = IntArray(2); @JvmField val rate = DoubleArray(2); @JvmField val weight = FloatArray(2) }
interface StreamService { fun open(slot: Int, sample: Int, fromFrame: Int): Boolean; fun close(slot: Int); fun ring(slot: Int): StreamRing }
class StreamRing(capacityFrames: Int /* power of two */) {
    @JvmField val data: ShortArray; @JvmField val mask: Int
    @Volatile @JvmField var written = 0L; @Volatile @JvmField var consumed = 0L; @Volatile @JvmField var sample = -1 }

// core/InstrumentSpec.kt  (the parsed instrument.json; §6.4)
class InstrumentSpec(val kind: InstrumentKind, val bankHash: String, val recordedAHz: Float, val lastDamper: Int,
    val layerMode: LayerMode, val layers: List<LayerSpec>, val samples: List<SampleSpec>, val keyF0Hz: FloatArray,
    val damperT60s: FloatArray, val releaseRule: ReleaseRule, val pedalNoises: PedalNoiseSpec,
    val stops: List<StopSpec>, val veltrackDefault: Float, val extraRegions: List<ExtraRegion>)
enum class LayerMode { HARD, XFADE }

// core/ScenePose.kt  (filled on the GL thread each frame by mech/, read by render/)
class ScenePose {
    @JvmField val keyDip = FloatArray(128)       // 0..1 of full dip at the key front
    @JvmField val hammerMm = FloatArray(128)     // travel toward the string; blow distance = contact
    @JvmField val hammerPhase = ByteArray(128)   // REST, RISING, FREE, CONTACT, REBOUND, CHECKED, RELEASING
    @JvmField val damperMm = FloatArray(128)
    @JvmField val jackMm = FloatArray(256)       // harpsichord [key*2 + stop]
    @JvmField val tongueDeg = FloatArray(256)
    @JvmField val stringAmp = FloatArray(128)    // 0..1, from EnergyRing
    @JvmField val strikeAgeMs = FloatArray(128)  // for the strike pulse; 1e9 when none
    @JvmField var sustain = 0f; @JvmField var sostenuto = 0f; @JvmField var soft = 0f
    @JvmField var keyboardShiftMm = 0f; @JvmField var hammerRailMm = 0f; @JvmField var muteRail = 0f
    @JvmField var registers = 0; @JvmField var songUs = 0L
    @JvmField var highest = 60; @JvmField var lowest = 60; @JvmField var centroid = 60f
}
interface MechanismModel {
    val kind: InstrumentKind
    fun evaluate(perf: Performance, clock: ClockRead, frameDtUs: Long, energy: FloatArray, out: ScenePose)
    fun reset()
}

// core/Meshes.kt  (pure data from scene/ and venue/, uploaded by render/)
enum class SkinKind { STATIC, KEY_ROT, HAMMER_ROT, DAMPER_LIFT, JACK_LIFT, TONGUE_ROT, STRING_SPINDLE, PEDAL_ROT, SHIFT_X, RAIL }
enum class MaterialId { LACQUER, IVORY, EBONY_KEY, BONE, WOOD, FELT, LEATHER, BRASS, PLATE, SOUNDBOARD, STEEL, COPPER,
                        GILT, PARQUET, FLAME, CRYSTAL, MIRROR_MASK, SECTION_CAP, CLOTH }
class BakedMesh(val name: String, val floatsPerVertex: Int, val data: FloatArray, val index: ShortArray?,
                val material: MaterialId, val skin: SkinKind, val clipped: Boolean)
class CameraPose(@JvmField val eye: Vec3 = Vec3(), @JvmField val target: Vec3 = Vec3(),
                 @JvmField var vFovDeg: Float = 34f, @JvmField var ipdScale: Float = 0.6f, @JvmField var zeroParallaxM: Float = 1.75f)
interface InstrumentModel {
    val kind: InstrumentKind
    fun meshes(): List<BakedMesh>
    fun camera(view: ViewId, subMode: Int, pose: ScenePose, out: CameraPose)
    val soundSource: Vec3                                 // instrument frame
    val skinParams: FloatArray                            // pivots, ratios for the vertex shaders
}
interface VenueModel {
    val geometry: VenueGeometry
    fun meshes(level: VenueLevel, palette: Palette): List<BakedMesh>
    val flames: FloatArray                                // xyz + phase, room frame
    val mirrors: FloatArray                               // plane quads
}

// core/VenueGeometry.kt  (ONE room description: renderer, light bake and RoomAcoustics all read it)
class VenueGeometry(val widthM: Float = 10.5f, val depthM: Float = 8.0f, val corniceM: Float = 4.6f, val ceilingM: Float = 5.7f,
    val surfaces: List<Surface>, val instrumentAt: Map<InstrumentKind, Placement>, val seats: List<Vec3>)
class Surface(val name: String, val areaM2: Float, val plane: FloatArray, val material: AcousticMaterial)
class AcousticMaterial(val name: String, val alpha: FloatArray /* 125,250,500,1k,2k,4k,8k */)

// core/Library.kt
data class LibraryEntry(val id: String, val workId: String, val shelf: String, val composer: String, val title: String,
    val movement: String?, val era: String, val defaultInstrument: InstrumentKind, val altInstruments: List<InstrumentKind>,
    val assetPath: String?, val filePath: String?, val durationSec: Int, val noteLo: Int, val noteHi: Int,
    val hasSustain: Boolean, val pedalMode: PedalMode, val credit: String, val licenceId: String,
    val exportAllowed: Boolean, val temperament: Temperament?, val aRefHz: Float?, val tier: String, val unreadable: String?)
class Shelf(val id: String, val title: String, val works: List<String>)
interface Library {
    fun shelves(): List<Shelf>; fun work(id: String): List<LibraryEntry>; fun entry(id: String): LibraryEntry?
    fun readScore(e: LibraryEntry): ByteArray
    fun import(tmp: java.io.File, originalName: String): ImportResult   // any thread
    fun delete(id: String): Boolean; fun rescan(); fun addListener(l: () -> Unit)
}
class ImportResult(val ok: Boolean, val entryId: String?, val summary: String, val error: String?)

// core/StageControl.kt  (implemented by render/StageView; called on the UI thread)
interface StageControl {
    fun setInstrument(kind: InstrumentKind); fun setPerformance(p: Performance?)
    fun setView(v: ViewId, animate: Boolean); fun stepView(dir: Int); fun stepSubMode(dir: Int)
    fun setQuality(q: Int); fun setVenue(level: VenueLevel, palette: Palette)
    fun setPresenceFloor(level: Int); fun setAvOffsetMs(ms: Int); fun setFov(v: ViewId, deg: Float); fun setIpdScale(v: ViewId, s: Float)
    fun recenter(); fun showSyncFlash(on: Boolean)
    var onViewChanged: ((ViewId, ListenerPose) -> Unit)?
}

// core/CommandRing.kt  (generic SPSC ring of references; used for EngineCommand and StreamRequest)
class SpscRing<T : Any>(capacityPow2: Int) { fun offer(x: T): Boolean; fun poll(): T? }
```

#### `platform/` (WP1)

| File | Responsibility | API |
|---|---|---|
| `BinocularSbsLayout.kt` | Copied from MathCosmos. Draws its single child in both eyes and remaps touches. | `var sbsEnabled: Boolean` |
| `TrackpadGestureEngine.kt` | Copied from WanderQuest, plus a `cyttsp6` device-name check on the key path | `onTap/onDoubleTap/onTripleTap/onSwipeHorizontal(dir)/onSwipeVertical(dir)`; `onKeyEvent/onTouchEvent/onGenericMotion/release()` |
| `ThermalGovernor.kt` | MathCosmos logic (battery temperature plus thermal status, with hysteresis), extended to 4 levels. Can take a fake temperature for testing. | `class ThermalGovernor(ctx, onLevel: (Int) -> Unit) { fun resume(); fun pause(); fun force(level: Int?); fun fakeBatteryTenths(t: Int?) }` |
| `ControlReceiver.kt` | adb `com.tropicalstream.hammerklavier.CONTROL` → typed callbacks (§8.2) | `class ControlReceiver(val handler: ControlHandler) : BroadcastReceiver` |
| `DeviceInfo.kt` | RayNeo identity (manufacturer/brand/product), site-local `deviceIp()`, free-space query | `object DeviceInfo` |
| `AudioRouteMonitor.kt` | Built-in speaker vs headset/BT via `AudioDeviceCallback` | `class AudioRouteMonitor(ctx, onRoute: (speaker: Boolean) -> Unit)` |

#### `midi/` (WP2, pure Kotlin)

| File | Responsibility | API |
|---|---|---|
| `SmfReader.kt` | Bytes → raw tracks. Handles MThd, MTrk, unknown chunks, RIFF-RMID, VLQ, running status, sysex and meta. | `object SmfReader { fun read(bytes: ByteArray): MidiFile }` (throws `SmfException(offset, msg)`) |
| `MidiFile.kt` | Raw model | `class MidiFile(val format: Int, val division: Int, val tracks: List<RawTrack>)`; `class RawTrack(val ticks: LongArray, val status: IntArray, val d1: IntArray, val d2: IntArray, val metaType: IntArray, val metaData: List<ByteArray?>)` |
| `TempoMap.kt` | Merged tempo changes (any track) and SMPTE division → tick-to-µs; time signatures → bar starts | `class TempoMap(file: MidiFile) { fun us(tick: Long): Long; fun bars(endTick: Long): LongArray }` |
| `ChannelMerge.kt` | K-way merge of all tracks into one stream, tie-break rules (§4.2), drum channel drop, channel counting | `fun merge(file: MidiFile, tempo: TempoMap): MergedEvents` |
| `NotePairing.kt` | Per-key reference counting, restrike flags, zero-length and overlap handling | `fun pair(ev: MergedEvents, keyLo: Int, keyHi: Int): NoteTable` |
| `InstrumentAdapter.kt` | Range folding, collision merge, harpsichord finger-pedalling, per-instrument pedal and CC policy | `fun adapt(notes: NoteTable, pedals: RawPedals, kind: InstrumentKind, s: AudioSettings): AdaptedScore` |
| `PedalShaper.kt` | Switch vs continuous detection, slewed and lead-shifted curves, dips, sostenuto/soft edges, pedal-noise events with speed class | `fun shape(raw: RawPedals, kind: InstrumentKind): ShapedPedals` |
| `PerformanceBuilder.kt` | Orchestrates the above into `Performance` | `object PerformanceBuilder { fun build(bytes: ByteArray, id: String, generation: Int, kind: InstrumentKind, s: AudioSettings): Performance }` |

#### `bank/` (WP3)

| File | Responsibility | API |
|---|---|---|
| `InstrumentSpecLoader.kt` | `assets/instruments/<kind>/instrument.json` → `InstrumentSpec`, validated against schema v1 | `object InstrumentSpecLoader { fun load(json: String): InstrumentSpec }` |
| `PcmCacheFormat.kt` | Pure. Header layout (magic `HKPCM1`, bankHash, sample count, 4 KB-aligned offsets, frame counts) plus its checks | `object PcmCacheFormat { fun writeHeader(...); fun readHeader(ch: FileChannel): PcmIndex }` |
| `PcmCacheBuilder.kt` | Android. Opus → PCM16 through `MediaExtractor` + `c2.android.opus.decoder`, using 2 codec instances on 2 threads. Checks each frame count against `instrument.json` (drops a 312-frame pre-skip if the decoder left it; fails on any other mismatch). Resumable, pauses when the battery is at or above 39 °C, writes progress | `class PcmCacheBuilder(ctx) { fun state(kind): BankState; fun build(spec, onProgress: (Float) -> Unit): Result<File> }` |
| `SampleBank.kt` | `SampleSource` implementation: heads in heap `ShortArray`s, keymap and layer tables, `pick()` including the pitch-standard remap (§3.4) | `class SampleBank(spec, cache: File, streamer: Streamer) : SampleSource` |
| `Streamer.kt` | The `hk-stream` thread. Handles open/close requests and fills each voice's ring with `FileChannel.read(buf, pos)` 64 KB at a time, refilling when a ring is half empty | `class Streamer(cache: File, index: PcmIndex, slots: Int) : StreamService { fun start(); fun stop() }` |
| `BankProviderImpl.kt` | Tracks per-instrument state (MISSING / VOICING / READY / FAILED), builds lazily, opens banks | `class BankProviderImpl(ctx) : BankProvider` |

#### `audio/` (WP4)

| File | Responsibility | API |
|---|---|---|
| `AudioOutput.kt` | The only Android audio class. Builds a float, 48 kHz stereo `AudioTrack` with `USAGE_MEDIA`/`CONTENT_TYPE_MUSIC` and `PERFORMANCE_MODE_LOW_LATENCY`. Calls `play()` before priming. Buffer capacity 4096 frames, initial size 1152 frames (6 HAL bursts), grown by one burst after each underrun up to 3072. Polls `getTimestamp` and `getUnderrunCount`. Handles audio focus (duck to 30% on CAN_DUCK, pause on a permanent loss). | `class AudioOutput(graph: EngineGraph, clock: SongClock, status: EngineStatus) { fun start(); fun stop() }` |
| `EngineImpl.kt` | Implements `Engine`: the command ring, lifecycle, and applying quality levels | `class EngineImpl(ctx) : Engine` |
| `EngineGraph.kt` | `renderBlock(out: FloatArray)`. Owns every DSP object; the allocation-free render loop (§3.13) | `class EngineGraph { fun renderBlock(out: FloatArray, framesWritten: Long) }` (JVM-testable offline) |
| `Sequencer.kt` | Event cursor, pre-roll dispatch, transport (play/pause fades, seek reconstruction, tempo), end detection | `class Sequencer { fun setPerformance(p, startUs); fun dispatch(t0Us: Double, t1Us: Double, sub: Int, sink: EventSink) }` |
| `KeyState.kt` | Held state per key (reference-counted), damper-landing timers, sostenuto latch, una corda flag, damping factor D(k) per control period | `class KeyState(kind, spec) { fun noteOn(k); fun noteOff(k, frame); fun update(sustainP: Float, sost: Boolean, frame: Long); fun damping(k): Float }` |
| `Voice.kt` | One voice: the Hermite reader over head then ring, gain ramp, damping decay, spectral-damping low-pass, bus flag | internal |
| `VoicePool.kt` | Allocation, re-strike handling, stealing, the level cull, per-key energy accumulation | `class VoicePool(cap: Int, fadeSlots: Int = 16) { fun start(...); fun releaseKey(k); fun render(...); fun keyEnergy(out: FloatArray) }` |
| `NoiseVoices.kt` | Release and pedal-noise one-shots (cap 16) on the noise bus | internal |
| `Tuning.kt` | Temperament tables (`instrument-mechanics-and-sound.md` §10), pitch standards, the cents-to-ratio table, comb frequencies | `object Tuning { fun cents(t: Temperament, pc: Int): Float; fun pitchKeys(key: Int, t: Temperament, aRef: Float, recordedA: Float): Float }` |
| `DamperTables.kt` | Precomputed decay multipliers per control period for each key and 64 damping levels, low-pass coefficients, T60 tables | `class DamperTables(spec: InstrumentSpec)` |
| `AudioMeters.kt` | Render-time EWMA (CPU load), peak voices, starvation counts, 5 s log line | internal |

#### `audio/dsp/` (WP5, pure Kotlin)

| File | Responsibility | API |
|---|---|---|
| `ResonanceBank.kt` | 88 comb resonators: first-order allpass for the fractional delay, a one-pole loop filter, glided feedback gain, input gating, pan, per-comb energy | `class ResonanceBank(fs: Int) { fun tune(f0Hz: FloatArray); fun setString(k, free: Boolean, inputGain: Float, t60: Float, t60Damped: Float); fun process(mono, outL, outR, n); fun energy(out: FloatArray) }` |
| `RoomAcoustics.kt` | Pure maths. Sabine and Eyring T60 per octave band from `VenueGeometry`; image-source early reflections (6 first-order and 6 second-order taps); direct distance gain and air absorption; direct-to-reverberant ratio | `object RoomAcoustics { fun design(g: VenueGeometry, pose: ListenerPose): RoomDesign }` |
| `EarlyReflections.kt` | 12-tap stereo delay line with bright and dull tap groups; gains ramp over 0.9 s on a listener change | `class EarlyReflections(fs) { fun set(d: RoomDesign, glideMs: Int); fun process(inL, inR, outL, outR, n) }` |
| `FdnReverb.kt` | 8 lines, Householder feedback, Jot absorption with a one-pole low-pass and low shelf per line, two modulated lines, decorrelated stereo taps | `class FdnReverb(fs) { fun set(t60Low: Float, t60Mid: Float, t60High: Float, preDelayMs: Float); fun process(inMono, outL, outR, n) }` |
| `DirectPath.kt` | Distance gain, air-absorption low-pass, mid/side width, world-anchored balance from head yaw (Hall only) | `class DirectPath { fun set(d: RoomDesign); fun process(l, r, n, headYaw: Float) }` |
| `ShelfBus.kt` | Una corda / soft-pedal bus (−2.5 dB, −4 dB shelf at 2.5 kHz) and practice-mute bus (800 Hz low-pass, −16 dB) | `class ShelfBus(fs, kind)` |
| `SpeakerEnhancer.kt` | For the built-in speakers: 100 Hz high-pass, virtual bass (low band → soft rectify → 150–450 Hz band-pass → mix at −6 dB), +3 dB at 250 Hz | `class SpeakerEnhancer(fs) { var enabled: Boolean; fun process(l, r, n) }` |
| `Limiter.kt` | 1.5 ms look-ahead peak limiter (72-frame running maximum), 1 ms attack, 250 ms release, ceiling −0.5 dBFS, then a Padé soft clip | `class Limiter(fs) { fun process(l, r, n) }` |

#### `mech/` (WP6, pure Kotlin)

| File | Responsibility | API |
|---|---|---|
| `Touch.kt` | `hammerVelocity`, `keyTravelMs`, `keyBottomRelMs`, `freeFlightMs`, the pressed-to-struck blend (copied from the mechanics report §1.2 and §11) | `object Touch` |
| `KeyTimeline.kt` | Per-key cursors into the `Performance` note lists (binary search after a seek, advancing otherwise). Returns the previous and next note around a time | `class KeyTimeline(perf) { fun around(k: Int, songUs: Long, out: NoteWindow) }` |
| `GrandMechanism.kt` | Evaluates key angle, the hammer state machine, dampers, una corda shift and pedals for the grand (§5.4) | `class GrandMechanism : MechanismModel` |
| `UprightMechanism.kt` | Horizontal hammer throw, spring return, slower repetition, hammer rail, mute rail | `class UprightMechanism : MechanismModel` |
| `HarpsichordMechanism.kt` | Jack rise, staggered pluck heights, tongue swing on the return, damper landing, register engagement | `class HarpsichordMechanism : MechanismModel` |
| `ExposureSampler.kt` | Shows each hammer's contact if the contact fell anywhere within the 33 ms frame | `object ExposureSampler` |

#### `render/` (WP7)

| File | Responsibility | API |
|---|---|---|
| `StageView.kt` | GLSurfaceView plus Choreographer pacing (divider 2/2/3/4 for levels Q0–Q3). Passes `frameTimeNanos` on. Implements `StageControl` | `class StageView(ctx, engine: Engine) : GLSurfaceView(ctx), StageControl` |
| `EglChooser.kt` | RGB888 with depth 24, stencil 8 and 4× MSAA, falling back to no MSAA, then to the default config. Logs what it got | `class EglChooser : EGLConfigChooser` |
| `StageRenderer.kt` | One frame: read the clock → evaluate the mechanism → camera → draw two eyes with shared simulation. Allocates nothing per frame | `class StageRenderer(engine, ctx) : GLSurfaceView.Renderer` |
| `StereoRig.kt` | Off-axis parallel frustum (SpyHunt), IPD 63 mm × a per-view scale, zero parallax at the subject | `class StereoRig { fun eye(e: Int, cam: CameraPose, aspect: Float, view: FloatArray, proj: FloatArray) }` |
| `CameraDirector.kt` | View and framing state, 0.9 s fly-throughs, the follow spring (ω = 6 rad/s, ±2-semitone dead band, 0.6 m/s cap), gaze composition, listener pose | `class CameraDirector(models) { fun update(dt, pose, gaze, out: CameraPose): ListenerPose }` |
| `GazeCamera.kt` | Copied from MathCosmos | as in MathCosmos |
| `GlyphBoard.kt` | Copied from MathCosmos: note names in the Action view, the fallboard lettering | as in MathCosmos |
| `GlKit.kt` | `makeVbo`, `DynMesh`, `compileProgram`, `FRAG_PRECISION` (copied), plus a uniform-array packer | internal |
| `Shaders.kt` | Programs: `skinned` (5 skin modes), `lacquer` (floor, rim, speculars, probe), `ivoryKey` (analytic bevel), `stringSpindle`, `flame`, `crystal`, `gilt`, `parquetPool`, `sectionCap` (clip plane) | internal |
| `LightProbe.kt` | Bakes a 128×64 equirectangular reflection texture from the venue's flames and gilt at load | `class LightProbe { fun bake(v: VenueModel, at: Vec3): Int /* tex id */ }` |
| `DrawList.kt` | Per-view list of draws with budget checks (≤ 35 per eye) and the frame-hitch probe (logs `FRAME HITCH` above 120 ms) | internal |
| `TestCards.kt` | Presence-floor swatches, APL test frame, the A/V sync flash | internal |

#### `scene/` (WP8, pure Kotlin mesh builders)

| File | Responsibility |
|---|---|
| `MeshBuilder.kt` | Shared builder: boxes, extrusions, swept profiles, Catmull-Rom outlines. Writes per-vertex part slot and one-hot lane for skinning |
| `KeyboardMesh.kt` | Keys on equal 13.75 mm slots at the back, 23.57 mm natural heads at the front, cut-outs around the sharps, key-local UVs for the bevels. Variants for piano and harpsichord |
| `GrandModel.kt` | C5 case outline, lid, legs and lyre, 3 pedals, plate, soundboard, 228 strings, 88 hammers, 70 dampers, action parts. Implements `InstrumentModel` |
| `UprightModel.kt` | U3 case, vertical overstrung strings, horizontal hammers, dampers, hammer rail, mute rail, 3 pedals |
| `HarpsichordModel.kt` | Flemish case with papers and motto, soundboard rose, 61 keys, 122 jacks (8′ and 4′), 2 registers, 122 strings, stand |
| `ActionMeshes.kt` | Grand key lever, capstan, wippen, jack, repetition lever, let-off button, backcheck. Upright butt, jack, spoon. Harpsichord jack with tongue and quill |
| `StringMeshes.kt` | Spindle-ribbon vertices (slot, lane, t along the speaking length, side ±1, tangent), wound vs plain, overstringing |

#### `venue/` (WP9, pure Kotlin)

| File | Responsibility |
|---|---|
| `KonzertzimmerModel.kt` | Implements `VenueModel` and provides `VenueGeometry` values: shell, cove, trellis and spider-web ribbons, cartouches, mirrors, windows, doors, 18 chairs, parquet, placements. Follows the build sheet in `visual_design.md` §1.3 |
| `Candles.kt` | 12 + 6 chandelier flames, 20 sconce flames, 10 candelabra flames and 2 desk candles; flicker hash |
| `Chandelier.kt` | Arms as ribbons; 160 crystals with hashed normals |
| `MirrorReflections.kt` | Reflects flames through each mirror plane (first order, plus one N–S bounce at Q0) |
| `VenueLevels.kt` | Per-level visibility and distance fades (`visual_design.md` §3.3) |
| `Materials.kt` | The acoustic material table used by `VenueGeometry` (§3.10) |
| `PaletteTokens.kt` | Colour tokens (`visual_design.md` §3.5), the only source of colours |

#### `ui/` (WP10)

| File | Responsibility | API |
|---|---|---|
| `InputRouter.kt` | Context state machine (TITLE, STAGE, MENU, EDIT, CARD) mapping gestures to actions (§1.3) | `class InputRouter(g: TrackpadGestureEngine, ui: UiActions)` |
| `MenuOverlay.kt` | Left-panel menu View: tabs, rows, highlight, edit mode | `class MenuOverlay(ctx) : View { fun show(tab); fun hide(); fun move(d); fun tab(d); fun activate(); fun back() }` |
| `MenuModel.kt` | Row definitions for the 5 tabs, bound to `PlaybackController`, `SettingsStore`, `Library` and `BankProvider` | pure |
| `HudView.kt` | Caption, credit, progress hairline, chips, diagnostics | `class HudView(ctx) : View { fun bind(now: NowPlaying, status: EngineStatus) }` |
| `TitleCard.kt` | Gilt title, voicing progress, companion URL | View |
| `CalibrationCards.kt` | Presence-floor card (16 warm swatches from 8 to 68) and A/V sync card (5 ms steps) | View |
| `Styles.kt` | Text sizes (22/18/14 px), HUD colours, no dark boxes | object |

#### `library/` and `companion/` (WP11)

| File | Responsibility | API |
|---|---|---|
| `library/Catalog.kt` | Parses `assets/catalog/catalog.json` | `object Catalog { fun load(json: String): CatalogData }` |
| `library/ImportedLibrary.kt` | `files/Scores` folder, sanitising, `files/library/imported.json`, metadata inference | `class ImportedLibrary(dir: File)` |
| `library/LibraryRepository.kt` | Merges bundled and imported entries; shelves, recents, playlists | `class LibraryRepository(ctx) : Library` |
| `library/ScoreValidator.kt` | MThd/RMID sniffing, size cap, trial parse with `SmfReader` and `PerformanceBuilder` | `object ScoreValidator { fun check(bytes: ByteArray): ImportResult }` |
| `companion/CompanionServer.kt` | WanderQuest structure merged with TapVibe's multipart upload; token; UTF-8 bodies read by hand | `class CompanionServer(port = 19112, api: CompanionApi) : NanoHTTPD` |
| `companion/CompanionApi.kt` | Routes → `PlaybackController` / `Library`, marshalled to the UI thread | interface plus implementation |
| `assets/companion/index.html` | The phone page (inline JS, no CDN, no `$` or backticks in the Kotlin-side strings) | — |

#### `app/` (WP1)

| File | Responsibility | API |
|---|---|---|
| `HammerklavierApp.kt` | Application; kills an orphaned engine left by a previous process | — |
| `MainActivity.kt` | Wiring: dispatchers → gestures, SBS layout, lifecycle. Audio **keeps playing through `onPause`** (the display-off sleep button) and fades out and pauses in `onStop` | — |
| `PlaybackController.kt` | UI-thread orchestration: entry → parse on `hk-loader` → `SetPerformance`; instrument switch (re-parse with the new adapter); next/previous; auto-advance; `NowPlaying` snapshots | `class PlaybackController(engine, banks, library, stage, settings) { fun play(entryId: String, kind: InstrumentKind? = null, startUs: Long = 0); fun togglePause(); fun next(); fun previous(); fun seekTo(us: Long); fun seekBy(us: Long); fun seekBars(d: Int); fun setTempo(s: Float); fun setInstrument(k: InstrumentKind); val now: NowPlaying; fun addListener(l: (NowPlaying) -> Unit) }` |
| `SettingsStore.kt` | SharedPreferences ↔ `AudioSettings` and render settings; per-instrument defaults | `class SettingsStore(ctx)` |
| `Stubs.kt` | Stub `Engine`, `SampleSource`, `Library`, `StageControl` and `MechanismModel` for parallel work (deleted at integration) | — |

---

## 3. The audio engine

### 3.1 Sample format: APK, disk and RAM

| Stage | Format | Grand | Upright | Harpsichord |
|---|---|---|---|---|
| Source (Mac, `raw/`, git-ignored) | Salamander FLAC 48k/24; VCSL WAV (rate and depth UNVERIFIED, checked with ffprobe) | 480 sustain + 88 release + 46 resonance + 4 pedal | 90 + 23 pp + 45 release + 8 pedal | 54 sustain + 54 release |
| APK `assets/instruments/<kind>/samples/*.opus` (uncompressed asset entries) | Ogg Opus 128k VBR, 48 kHz stereo, peak-normalised to −1 dBFS, TPDF-dithered from float | ≈ 55 MB (budget 70 MB) [D] | ≈ 13 MB (budget 16 MB) | ≈ 6.5 MB (budget 9 MB) |
| Disk `filesDir/pcm/<kind>-<bankHash>.pcm` | PCM16 interleaved stereo, each sample on a 4 KB boundary | ≈ 0.8 GB | ≈ 0.2 GB | ≈ 0.1 GB |
| RAM, heads (heap `ShortArray`s) | First 250 ms after the onset for sustains; releases and pedal noises in full | 480 × 0.25 s × 192 kB/s = 23 MB, + releases 8 MB + pedals 3.5 MB + resonance heads 2.2 MB ≈ **37 MB** | ≈ 12 MB | ≈ 6 MB |
| RAM, stream rings | 16,384 frames × 2 channels × 2 B per slot | 112 slots = 7.3 MB | 144 slots = 9.4 MB | 112 slots = 7.3 MB |

The size estimates come from the source FLAC sizes. 727 MB of 16-layer FLAC at about 165–180 kB/s is roughly 4,000–4,400 s of source audio. After trimming, the sample lengths depend on the tail rule in §6.3. **Only the active instrument is loaded.** An instrument switch frees one bank before opening the next (a 1–2 s change, faded). The Java heap stays around 80 MB, under the 192 MB `heapgrowthlimit`, so `largeHeap` is not needed.

**Why samples are normalised.** A pp layer stored at its natural −40 dBFS would keep only about 56 dB of 16-bit resolution. That hiss would be audible in quiet Bach. The natural level is restored through `gain(sample)`.

### 3.2 Decoding and caching ("voicing")

- On first launch `hk-voicer` decodes the **grand first**, then the upright, then the harpsichord.
  - It uses two `MediaCodec` instances of `c2.android.opus.decoder`, each reused across files (flush and reconfigure).
  - The title card shows "voicing the grand · 43%". Playback is allowed as soon as the grand is ready. The Library can be browsed straight away.
- **Verification is exact.** `instrument.json` carries the expected frame count of each sample.
  - If the decoded count equals expected + 312, the decoder kept the Opus pre-skip, so it is dropped.
  - Any other mismatch fails that instrument with a logged error. It is never guessed or padded.
  - Trailing Opus padding is cut to the expected count.
- The cache header carries the `bankHash` of the `instrument.json` that built it. A new APK with a different bank rebuilds that one cache.
- Voicing is resumable (a per-sample progress file), checks for 2× the cache size in free space, pauses when the battery reaches 39 °C, and runs at background priority.
- Voicing speed is **UNVERIFIED**. The estimate is 60–100× real time per software Opus decoder, which means ≈ 25–45 s for the grand on 2 threads. WP3 measures it.

### 3.3 Voice structure

```
Voice (preallocated: cap + 16 fading slots; no allocation after start)
  key, stop (register), velocity, age, bus (DRY | SHELF | MUTE)
  sample[2], weight[2]                  // 2 only for the upright cross-fade
  pos: Long (32.32 fixed-point frames), inc: Long, startDelay: Int
  gain, gainTarget                      // linear; ramped linearly within each 32-frame period
  decayPerPeriod: Float                 // from DamperTables[key][D quantised to 64]; 1.0 = natural decay only
  lpA, lpZl, lpZr                       // spectral-damping one-pole; engaged once D > 0.05; never re-opened
  state: STARTING | FREE | DAMPING | FADING | DONE
  slot: Int                             // stream slot; head → ring handover at headFrames − 4 (4-frame overlap)
```

- **Read path.** Each voice reads 4 taps per channel from either the resident head or its `StreamRing`, masking the index by the ring size. It applies a 4-point Hermite (Catmull-Rom) interpolation, multiplies by the gain, and accumulates into its bus. No `exp`, `pow` or `sin` is ever called per sample.
- **Pitch range.**
  - Grand: roots a minor third apart, so shifts stay within ±1 semitone plus tuning.
  - Upright: roots every whole tone, or every major third for the pp layer (±2 semitones).
  - Harpsichord: up to +5 semitones for f‴ (§3.4).
  - At these ratios Hermite keeps the top octave within about 0.5 dB, where linear interpolation loses 1–2 dB of treble shimmer. Linear is used only at Q3.
- **Stereo image.** The samples are stereo. The Salamander AB pair and the VCSL recordings already place the notes left to right, so no per-voice panning is added. The width of the direct sound is set per view (§3.10).

### 3.4 Choosing the sample: velocity layer, pitch and tuning

1. **Effective pitch** in fractional keys:

   ```
   pitchKeys = key + (tempCents[pc] + (1200 × log2(aRef / recordedA))) / 100
   ```

   `recordedA` is measured by the build tool from every sample's f0. It is not assumed. `tempCents` uses the tables in `instrument-mechanics-and-sound.md` §10, normalised so that A has 0 cents.
2. **Root choice.** For each layer, take the sampled root nearest to `pitchKeys`, then `rate = 2^((pitchKeys − root − rootTuneCents/100) / 12)`. `rootTuneCents` combines Salamander's `tune_ret` and the build tool's f0 measurement.
   - **A415 on a harpsichord recorded at A440 therefore uses the sample one key lower**, shifted by only −1.27 cents. That is exactly what a transposing harpsichord does, so the timbre is intact. A415 on the grand works the same way.
3. **Velocity** (`LayerMode` from `instrument.json`):
   - **Grand, HARD mode.** Salamander's own velocity splits are used unchanged:

     ```
     1–26 | 27–34 | 35–36 | 37–43 | 44–46 | 47–50 | 51–56 | 57–64 | 65–72 | 73–80 | 81–88 | 89–96 | 97–104 | 105–112 | 113–120 | 121–127
     ```

     One layer, no blending. The gain within a layer follows the SFZ amplitude law:

     ```
     g = (vel/127)^(2 × veltrack),   veltrack default 0.73
     ```

     That law is multiplied by the per-sample level smoothing (§6.3).
   - **Upright, XFADE mode.** Three layers with equal-power cross-fades 24 velocity units wide, centred on 45 and 90. Both samples are onset-aligned to the same frame, which limits phasing.
   - **Harpsichord.** Velocity changes **nothing** in the sound (Fletcher & Beebe). It only sets the key lead and the 4′-before-8′ stagger (§3.9).
4. **Range and stops.** Harpsichord keys 85–89 (c#‴–f‴) use the c‴ root shifted up by as much as +5 semitones. Notes beyond 29–89 are folded by `InstrumentAdapter` (§4.5). On the grand and upright, anything outside 21–108 is folded.

### 3.5 Envelopes and dampers, per register

The recorded note is the natural decay, so a **FREE** voice keeps a constant gain. The damper adds decay on top.

- **Damping level D(k) ∈ [0,1]**, recomputed every 32-frame period for keys that have voices:
  - D = 0 if `k > lastDamper`, or the key is held, or sostenuto has latched it.
  - Otherwise `D = 1 − smooth(0.33, 0.55, sustainP)` (§4.4 of the mechanics report).
  - Half-pedal therefore works on continuous CC64 and on the dips `PedalShaper` creates.
- **Damper landing.** Note-off does not damp immediately. The damper lands when the key has risen halfway:
  - grand: note-off + 17 ms [D from the 35 ms key return];
  - upright: + 25 ms;
  - harpsichord: + 10 ms (the jack falls).
- **Decay rate**:

  ```
  σ(k, D) = D × 6.91 / damperT60(k),   damperT60(k) = 0.12 + 1.2 × ((88 − k) / 67)²  s
  ```

  (mechanics report R3; C6 0.12 s, C4 0.33 s, C2 0.84 s, A0 1.3 s). It is stored as a per-period multiplier for each key and 64 values of D. WP12 recalibrates the curve against each pack's release samples, and the result ships in `instrument.json`.
- **Spectral damping.** When a damper engages, a one-pole low-pass closes over 40 ms to `min(18 kHz, 6 × f0(k))`, scaled by D. Felt kills the upper partials first. The bass damper is the least effective, so its fundamental lingers.
- **Re-pedalling** (R13). If D falls back to 0 while a voice is damping, its decay stops at the current level and the low-pass **stays where it is**, because the upper partials are already gone. The release is continued, not restarted.
- **Undamped treble** (R9). Grand keys 89–108 never damp. They ring until the cull at −80 dBFS or the sample end, and their resonators are always free (§3.8). The upright's last damper is taken from the keys that the VCSL SFZ gives a 10 s release, confirmed by listening. The harpsichord damps every key.
- **Sostenuto** (CC66, grand only). On the rising edge it latches every key whose damper is lifted **by its key** at that moment. If the sustain pedal is down at that moment, it latches every damper. It releases on the falling edge. The upright ignores CC66 (there is no sostenuto; the mute is the user's choice). The harpsichord ignores it.
- **Una corda** (CC67 ≥ 64, grand).
  - Voices started while it is down go to the `ShelfBus`: −2.5 dB and a −4 dB shelf above 2.5 kHz.
  - That key's own resonator is fed at −26 dB. In una corda the hammer misses one string of the trichord, and that string is driven through the bridge. This gives the "aftersound-dominated" tone the mechanics report (§4.3) describes, and it costs nothing extra.
  - Upright soft pedal: the same bus, −4 dB, a milder shelf, no resonator feed. The harpsichord ignores CC67.
- **Practice mute** (upright, a user toggle) sends every voice to the `MUTE` bus.

### 3.6 Re-strikes, release samples and pedal noises

- **Re-strike** (R10). A note-on for a key that already has a voice starts a new voice. The old one ramps to −4.4 dB (×0.6) over 5 ms, then decays with τ = 60 ms if the pedal is up or 200 ms if it is down. That approximates the hammer resetting a moving string, and it keeps the voice count bounded in fast repetitions.
- **Release samples.** Grand `rel<n>`, upright `Player_rel` and the harpsichord's jack fall for each stop.
  - They are triggered **when the damper actually lands**: at the landing time with the pedal up, or on the pedal's up-crossing for keys whose dampers then fall.
  - With the pedal up, the gain follows the Salamander SFZ `rt_decay` or `amp_veltrack` if present (WP12 carries these into `ReleaseRule`). Otherwise it is `0.5 + 0.5 × velNorm`, times `(0.4 + 0.6 × currentLevel / levelAtOn)`.
  - A key released under the pedal (the damper stays up) plays its release sample at −9 dB: only the key-return noise.
  - When the pedal lifts, only the pedal-up noise plays, not 30 separate release samples.
- **Pedal noises.** `PEDAL_NOISE` events come from `PedalShaper` where the pedal crosses 0.33 in either direction.
  - They alternate round robins (Salamander has 2 down and 2 up, the upright 4 and 4).
  - Gain follows the speed class (0–3) taken from the curve's slope. A 0/127 switch-type file counts as class 2.
  - They play on the noise bus, at the SFZ's −20 dB for the grand.
- **Salamander `harm*` samples.** WP12 copies the harm regions' opcodes from the shipped `.sfz` into `extraRegions` (trigger, lokey/hikey, lovel/hivel, CC conditions, amp), and the engine implements them as the SFZ specifies. If the SFZ uses opcodes outside `{trigger, lokey, hikey, lovel, hivel, loccN, hiccN, rt_decay, amp_veltrack, volume}`, the build fails and we decide. The resonance bank's "natural" level is calibrated **with** harm playing, so the two do not double up.
- **Keybed and finger noise** (R5 and R6) are already **in the recordings**, because each sample recorded the whole strike. The 3 ms pre-roll keeps any finger noise. Nothing is synthesised.

### 3.7 Voice cap, stealing and culling

| Quality level | Grand voices | Upright voices (×2 streams) | Harpsichord voices | Noise voices | Interpolation |
|---|---|---|---|---|---|
| Q0, Q1 | 96 | 64 | 96 | 16 | Hermite |
| Q2 | 64 | 48 | 64 | 12 | Hermite |
| Q3 | 48 | 32 | 48 | 8 | linear |

- **Stealing order** when the cap is reached:
  1. the quietest **DAMPING** voice;
  2. a re-strike's old voice;
  3. the quietest FREE voice older than 1 s (usually held by the pedal).
  - A voice younger than 50 ms is never stolen. A stolen voice fades over 5 ms in one of the 16 fading slots.
- **Culling.** A voice is freed when `gain × rms(pos)` drops below −80 dBFS.
- **Stream starvation.** If a ring runs dry because the streamer is late, the voice outputs silence for that stretch and the `starved` counter goes up. The audio thread never waits.

### 3.8 Sympathetic resonance: 88 string resonators

- **One comb filter per key** (the string's round trip).
  - Delay: `N = 48000 / f0(k)`. The fractional part is handled by a first-order allpass, so the magnitude stays flat.
  - Loop filter: a one-pole low-pass. Its coefficient comes from the register's brightness, which follows the mechanics report's §8.3 spectra: many partials in the bass, few in the treble.
  - Feedback gain: `g = 10^(−3 N / (48000 × T60))`, gliding between T60_free and T60_damped once per control period.
  - T60_free follows `freeT60s(k) × 0.8` from mechanics §8.2. T60_damped is `damperT60(k) × 0.5`.
- **Tuning.** `f0(k)` is the build tool's **measured** f0 of that key's samples (interpolated in cents between roots, so the recorded stretch tuning is kept), times the current temperament and pitch standard. The resonators therefore ring exactly where the samples' strings do.
- **Input.** The mono dry mix at −30 dB ("natural") or −24 dB ("rich"). Gating per key:
  - a free string (D < 1) that is **not currently struck** is fed;
  - a struck key's own resonator is off, because its recording already contains its own string;
  - under una corda, the struck key's own resonator is fed at −26 dB (§3.5);
  - a damped string (D = 1) gets no input and decays at T60_damped.
- **What it produces:**
  - pedal up: held keys ring when related notes are played (R7), and keys 89–108 ring all the time (R9);
  - pedal down: all 88 strings form the "open piano" halo (R8), and the mid-register decay lengthens (Lehtonen 2007).
- **Output** is panned by key position (bass left) and adds to the dry bus before the room. It also gives per-key energy for the Inside view's sympathetic shimmer.
- **Active set.** Only combs that are fed or still hold energy above −90 dB are processed. The others are skipped, and their buffers are cleared once. Worst case, with the pedal down: 88.
- **Harpsichord.** Only held keys are free, fed at −34 dB. **Upright:** as the grand, at −32 dB.

### 3.9 The harpsichord's stagger and registers

- In the 8′+4′ registration, both stops are separate voices.
- The 4′ jack plucks at 2.6 mm of key travel and the 8′ at 4.2 mm (mechanics §6.1, UNVERIFIED values). So the **4′ sounds earlier** by `(4.2 − 2.6) mm / keySpeed(v)`, about 3–9 ms, depending on velocity through the harpsichord lead model. The 8′ lands on the MIDI time. Real registers pluck one after another, not together, and this reproduces that small offset.
- The registration is a user setting applied at note-on. Changing it mid-piece affects only new notes, as moving a register slide by hand would.

### 3.10 The room: early reflections from the geometry, then an FDN

- **One description, two users.** `VenueGeometry` holds the surfaces with their acoustic materials. The renderer draws them, and `RoomAcoustics` computes their acoustics.
- The scene is "an evening at Sanssouci": 18 guests seated, silk drapes drawn over the three windows. The design room from `visual_design.md` §1.3 gives:
  - V ≈ 469 m³ (10.5 × 8.0 × 5.7 m, less the cove), S ≈ 354 m².
  - Materials, α at 500 Hz, all [U] textbook values:
    - oak parquet 0.07;
    - plaster ceiling and cove 0.06;
    - boiserie on battens 0.10 (0.25 at 125 Hz);
    - window glass 0.18, mirrors 0.04, doors 0.06, canvas 0.10;
    - drawn drapes 0.50;
    - 18 seated people, 0.5 m² absorption each.
  - Result: A₅₀₀ ≈ 47 m², so **T60 ≈ 1.6 s at 500 Hz**. By band: ≈ 1.9 s at 125 Hz, ≈ 1.4 s at 2 kHz, ≈ 0.9 s at 8 kHz (air absorption included). These are [D] values from the design room, not measurements of Sanssouci. `RoomAcoustics` computes them, so changing a material changes the sound.
- **The direct path.** A sample is the close-miked direct sound.
  - `DirectPath` applies distance gain relative to the bench (1/r), air absorption (a one-pole low-pass by distance) and width (M/S). Width is 100% at the bench, 70% in the Action and Inside views, and **30% in the Hall**. From row 3 the grand is side-on, so its range is mostly heard as near and far, not left and right.
  - In the Hall view the direct and early sound is **anchored to the room**. Turning the head shifts the balance by `sin(yaw)`, so the piano stays where it stands.
- **Early reflections.** Image sources for the 6 room planes at first order, plus 6 second-order paths (floor–ceiling and the north–south pair). They are computed for the current listener, who is the camera, and the source, which is the soundboard centre.
  - Each gives a delay, a gain of `(1/d) × √(1 − α)` and a pan. They are split into bright taps (mirrors, glass, plaster) and dull taps (drapes, audience), each group sharing one low-pass.
  - They are recomputed when the view changes and the gains glide over the 0.9 s camera flight.
- **Late reverb (FDN).**
  - 8 delay lines of 853, 1031, 1277, 1471, 1693, 1951, 2203 and 2521 samples (17.8–52.5 ms, mutually prime, around the room's 5.3 m mean free path).
  - Householder feedback matrix.
  - Jot absorption: per-line gain `10^(−3 m / (fs · T60mid))`, a one-pole low-pass for the high-frequency T60 and a low shelf for the bass T60.
  - Lines 2 and 5 are modulated by ±12 samples at 0.31 and 0.47 Hz. Piano is the harshest test for metallic ringing, and the modulation breaks it up.
  - Pre-delay equals the first reflection. The reverberant level is set from the critical distance: `r_c ≈ 0.057 √(V/T) × √Q` with Q = 2 for the open lid, which is ≈ 1.3 m. So the bench is mostly direct and row 3 is mostly room (DRR ≈ −11 dB), as it is in a small, live, gilded room. The Room menu trims this by ±4 dB.
- **Convolution is rejected.**
  - A 1.6 s stereo impulse response at 48 kHz is 76,800 taps. Uniform partitioned FFT convolution at 256-frame blocks is ≈ 1.3 Mflop per block, ≈ 240 Mflop/s, which is 25–45% of a core in scalar ART code.
  - There is also no measured, licensed impulse response of this room. We would have to synthesise one, which is what the FDN already does, for 0.4% of a core.

### 3.11 Output stage

In order:

1. Sum the dry and shelf buses and the resonance output.
2. `DirectPath`.
3. Add the early reflections and the FDN.
4. Master gain (−10 dB by default, so fortissimo pedalled chords stay below the limiter most of the time).
5. `SpeakerEnhancer`, when the built-in speakers are the route. The speakers produce essentially nothing below about 150 Hz (`engine_reuse.md` §4.2), so a grand's bass octaves would vanish without it. With a headset or Bluetooth it is off and the output is flat.
6. `Limiter`, then interleave into the float output.

### 3.12 Tuning and temperament

Offsets per pitch class are taken from `instrument-mechanics-and-sound.md` §10: Equal, Werckmeister III, Vallotti, Young II, Kirnberger III, Kellner, Lehman and ¼-comma meantone. The pitch standards are 440, 430, 415 and 392.

| Instrument | Default | Catalogue override |
|---|---|---|
| Grand, upright | A440 equal. The samples keep their own stretch; `tune_ret` corrects Salamander's tuning errors | `temperament` / `aRefHz` per work (none set by default) |
| Harpsichord | **A415 Werckmeister III** (by key remap if recorded at 440, §3.4) | French pieces may set Vallotti |

Changing the temperament retunes new voices and glides the resonators over 200 ms. Voices already sounding keep their pitch, as struck strings would.

### 3.13 The render loop (`EngineGraph.renderBlock`, 256 frames)

```
drain ≤ 16 commands (load bank, performance, play/pause/seek/tempo, settings, listener, quality)
rate = playing ? tempo × 1e6/48000 : 0 ; if changed → clock.writeSegment(framesWritten, songUs, rate, gen, playing)
for sub in 0..7:                                   // 32-frame control periods
    t0 = songUs + sub·32·rate ; t1 = t0 + 32·rate
    sequencer.dispatch(t0, t1 + preroll)           // note-ons with frame-exact start delay; offs → landing timers; edges
    sustainP = sustainCursor.advanceTo(t1) ; keyState.update(sustainP, sostenuto, soft)
    voices.render(dry, shelf, mute, monoFeed, sub·32, 32)    // uses decayPerPeriod[key][D]
    noises.render(noise, sub·32, 32)
resonance.process(monoFeed → dry)                  // active combs only
shelfBus / muteBus → dry ; directPath(dry) ; er(dry) ; fdn(mono(dry)+er) ; master ; enhancer ; limiter ; interleave(out)
publish: energy.write(framesWritten, keyEnergy + combEnergy), status (voices, cpu EWMA), every 20 blocks getTimestamp
```

`AudioOutput` then calls `write(out, WRITE_BLOCKING)`, `framesWritten += 256`, `songUs += 256 × rate`.

**Pause** fades the input to the room over 60 ms, then freezes the voices, so the reverb tail rings on naturally. **Resume** continues the sustained chord with a 60 ms fade-in.

**Seek** fades the voices over 15 ms and clears the resonators. It then rebuilds the pedal cursors, the sostenuto latch and the held keys at the target (replaying key and pedal state, not sound), and starts from the next events. Notes that began before the target are not restarted, because a restarted attack would be heard.

### 3.14 CPU budget

The per-frame costs below are [D] estimates for AOT-compiled Kotlin, meaning a release build with `cmd package compile -m speed`. WP4's on-device benchmark (`--ez bench true`) replaces them with measurements.

| Block | Cost per frame | Units, worst case at Q0 | Share of one core |
|---|---|---|---|
| Grand voice (1 stream, Hermite, damping, spectral low-pass) | 35 ns | 96 | 16.1% |
| Upright voice (2 streams, cross-fade) | 60 ns | 64 | 18.4% |
| Harpsichord stop voice | 30 ns | 96 | 13.8% |
| Noise voices | 20 ns | 16 | 1.5% |
| Resonance comb | 12 ns | 88 | 5.1% |
| Direct path + 12 early-reflection taps | 25 ns | 1 | 0.1% |
| FDN, 8 lines | 90 ns | 1 | 0.4% |
| Enhancer + limiter + interleave | 40 ns | 1 | 0.2% |
| Sequencer, pedal cursors, key state (per 32-frame period) | 3 µs × 1,500/s | — | 0.5% |
| **hk-audio total, grand, worst case** | | | **≈ 24%** |
| hk-audio, typical (Moonlight iii, pedalled, ~40 voices) | | | ≈ 11% |
| hk-stream (≈ 18 MB/s copied into rings plus flash reads) | | | ≈ 3% |

**Acceptance ceiling:** hk-audio ≤ 40% of one core on the stress piece and ≤ 20% on the Hammerklavier.

If the measurement comes in over the ceiling, step down in this order:

1. linear interpolation (voices get about 35% cheaper);
2. resonators capped at 44, keeping the most energetic;
3. voice cap 72.

Separately, a watchdog steps down one level if a block takes more than 70% of its duration for 20 blocks in a row.

---

## 4. The MIDI pipeline

### 4.1 SMF reader (`SmfReader`)

- **Containers:** `MThd` + `MTrk`. Unknown chunks are skipped by length. RIFF-`RMID` wrapping and `.kar` files (lyrics ignored) are accepted. Formats 0, 1 and 2; **format 2 plays its tracks one after another**.
- **Delta times:** VLQ of at most 4 bytes. A longer one is a `SmfException` giving the byte offset.
- **Running status** applies to channel messages only. Meta and sysex events **cancel** it. A stray real-time byte (F8–FE) inside a track is skipped. A data byte with no running status is an error.
- **Sysex:** `F0 <vlq> …` and `F7 <vlq> …` are skipped by length.
- **Meta events kept:** 0x51 tempo, 0x58 time signature, 0x59 key signature, 0x03 track name, 0x01 text, 0x02 copyright (shown in Credits), 0x2F end. The rest are skipped.
- **Tolerance:** a missing end-of-track, a chunk length running past the end of the file (truncated to the file), and trailing junk each produce a warning, not a failure.
- **Division:** PPQ, or SMPTE with a negative high byte: `ticksPerSecond = fps × ticksPerFrame`, with −29 meaning 29.97 fps.

### 4.2 Tempo map and merging

- **Tempo** changes from **every** track are merged. Some format-1 files put tempo changes in their note tracks. The default is 500,000 µs per quarter. Converting ticks to µs uses cumulative segments, so there is no drift. Time signatures give bar starts, used for seeking by bar and in the HUD.
- **Merge:** a k-way merge by tick. Ties are broken by class, then by track index, then by file order:
  1. meta events;
  2. note-off, and note-on with velocity 0;
  3. controllers;
  4. note-on.

  Because of this order, a repeated note at the same tick is a re-strike rather than a stuck note, and a pedal change at the tick of a new chord is applied before the chord.
- **Channels:** every non-drum channel is merged into one keyboard, a "piano reduction", and the HUD says "7 channels merged". Channel 10 is dropped unless it is the only channel with notes. **Ignored:** program changes, pitch bend, aftertouch and CC7/CC11. A piano has no volume or expression control, and applying them would swell sustained notes in a way no piano can. Each ignored type is counted in `warnings`.

### 4.3 From notes to a playable performance

- **Pairing** is reference-counted per key across channels, because one key is one key:
  - a note-on while the key is already down is a **re-strike** (`TAG_RESTRIKE`);
  - the key rises only when its count returns to 0;
  - a note-off with no matching note-on is dropped;
  - a 0 ms note becomes 20 ms.
- **In the picture**, a re-strike without a release is a double-escapement repetition: the key rises to at least ⅓ of its travel in the time available (at least 67 ms on the grand) and strikes again.

### 4.4 Pedals (`PedalShaper`)

- **Detection:** CC64 is `CONTINUOUS` if it takes 8 or more distinct values strictly between 0 and 127. Disklavier captures and Bednarek's captures may be continuous; this is UNVERIFIED. Otherwise it is `SWITCH`, with 64 as the threshold.
- **Switch mode. The MIDI time marks the acoustic event, and the mechanism moves before it.**
  - Down: the curve goes 0 → 1 over 70 ms, positioned so that it crosses 0.33 (where the dampers start to lift, per Redekop) at the event time. So it starts 23 ms early.
  - Up: 1 → 0 over 60 ms, crossing 0.33 at the event time.
  - An up and down less than 60 ms apart overlap into a **dip**. It reaches `max(0, …)`, so the dampers brush the strings: a quick pedal change still partly damps, as a real one does.
- **Continuous mode:** the values are used directly with 8 ms smoothing and no lead, because they are sampled positions.
- **Derived events:** sostenuto edges (crossing 0.5) and the soft curve (0 → 1 over 60 ms) with its edges. `PEDAL_NOISE` events at each 0.33 crossing, with a speed class from the slope.

### 4.5 Files written for other instruments (`InstrumentAdapter`)

| Case | Grand | Upright | Harpsichord |
|---|---|---|---|
| Note outside the compass | Fold by octaves into 21–108 | same | Fold into **29–89**. If the folded note collides with a note already sounding on that key (within 30 ms), they merge and the higher velocity is kept. Folds are counted for the "3 notes folded" chip |
| CC64 | Pedal | Pedal | **Finger-pedalling** (default): each note-off is delayed to the next pedal release, capped at 1.5 s and never past the next note-on of the same key. The keys are *seen* held. With "ignore", CC64 is dropped. No pedal is drawn either way |
| CC66 | Sostenuto | Ignored | Ignored |
| CC67 | Una corda | Soft pedal (hammer rail) | Ignored |
| Velocity | Layers and gain | Layers and gain | Key lead and the 4′ stagger only |

### 4.6 Transport (`PlaybackController` on the UI thread, `Sequencer` on hk-audio)

- **Play/pause** as in §3.13.
- **Seek**: to an absolute time, by ±10 s, or by bar.
- **Next / previous**: previous within 3 s of the start goes to the previous movement, otherwise restarts. At the end of a movement it moves on to the next movement, then stops at the end of the work. The "Start here" shelf plays straight through.
- **Tempo** 0.5–1.5×. Pitch is unchanged, and the keys' travel times stay physical.
- **Loop**: off / movement / work.
- **Instrument switch**: the entry is re-parsed with the new adapter, then played from the same song position with a 300 ms fade.
- **Generations.** Every Performance carries a `generation` number. The renderer and energy readers ignore data from other generations, which prevents a flash of the previous piece's keys.

### 4.7 The catalogue: `assets/catalog/catalog.json` (bundled) and `files/library/imported.json`

```json
{
  "schema": 1,
  "shelves": [
    { "id": "start", "title": "Start here", "works": ["bach-bwv846-krueger", "bach-bwv772-786-sankey", "beethoven-op106-krueger"] }
  ],
  "works": [
    {
      "id": "beethoven-op106-krueger",
      "composer": "Ludwig van Beethoven",
      "title": "Piano Sonata No. 29 in B-flat major, op. 106",
      "nickname": "Hammerklavier",
      "catalogue": "op. 106",
      "year": 1818,
      "era": "classical",
      "defaultInstrument": "grand",
      "altInstruments": ["upright"],
      "tuning": null,
      "movements": [
        {
          "id": "beethoven-op106-1",
          "title": "I. Allegro",
          "asset": "midi/krueger/beethoven/beethoven_hammerklavier_1.mid",
          "sha1b32": "QK4QBBMFW4SWQETC4NJ5SG2ZBTZPBFXK",
          "durationSec": 593,
          "noteRange": [26, 99],
          "hasSustain": true, "hasSostenuto": false, "hasSoft": false,
          "pedalMode": "switch"
        }
      ],
      "source": {
        "name": "piano-midi.de",
        "author": "Bernd Krueger",
        "url": "http://www.piano-midi.de",
        "performanceType": "step-sequenced",
        "tier": "A"
      },
      "licence": {
        "id": "CC-BY-SA-3.0-DE",
        "url": "https://creativecommons.org/licenses/by-sa/3.0/de/deed.en",
        "credit": "Performance: Bernd Krueger · piano-midi.de · CC BY-SA 3.0 DE"
      },
      "exportAllowed": true
    }
  ]
}
```

- `durationSec`, `noteRange`, `has*` and `pedalMode` are **measured by `build_catalog.py`**, never typed by hand. The `noteRange` above is illustrative.
- Sankey works have `"exportAllowed": false`. The app has no export in v1 anyway; see non-goals.
- Imported entries use the same schema with `"source": {"name": "imported"}`, `"licence": {"id": "user"}`, and `file` in place of `asset`.

---

## 5. Rendering

### 5.1 GL setup and pacing

- **Context and config.** `setEGLContextClientVersion(2)` (the proven path) with `EglChooser`: RGB888, depth 24, stencil 8 (mirror reflections), **4× MSAA**, falling back as described in §2.4. Shaders are GLSL ES 1.00. Moving parts are skinned from uniform arrays, which the ES 2.0 minimum of 128 vertex uniform vectors comfortably allows. `GL_MAX_VERTEX_UNIFORM_VECTORS` is logged at start.
- **Pacing:** 30 fps from a Choreographer callback that calls `removeFrameCallback` before posting, following WanderQuest. Frame `dt` is clamped to [0, 0.05] s. The frame-hitch probe is always on.
- **Stereo:** off-axis parallel frustums (SpyHunt's `perspectiveOffAxis`), IPD 0.063 m × the view's scale, zero parallax at the subject. Values from `visual_design.md` §4.6: Player 0.6 / 1.75 m, Action 0.35 / 1.1 m, Inside 0.5 / 1.8 m, Hall 1.0 / 4.9 m. **Simulation runs once per frame and the scene is drawn twice.**
- **Field of view.** Fixed per view and never animated (vertical FOV Player 34°, Action 22–26°, Inside 36–44°, Hall 40° / 18.27°). Each is adjustable over CONTROL and settled on the glasses.

### 5.2 Scene graph

This is a flat, fixed draw list per view, not a general scene graph.

```
Stage
 ├─ Venue (static VBOs: shell, gilt ribbons, decals, chairs, parquet pool) + dynamic sprites (flames, mirror flames, crystals)
 ├─ Instrument (placement from VenueGeometry)
 │   ├─ Case group (static: lacquer / Flemish paint / walnut), lid (static transform per view), legs, lyre/stand
 │   ├─ Keys (1 skinned draw: KEY_ROT about the balance rail, SHIFT_X for una corda)
 │   ├─ Hammers (HAMMER_ROT), Dampers (DAMPER_LIFT), Jacks (JACK_LIFT + TONGUE_ROT)
 │   ├─ Strings steel + wound (STRING_SPINDLE; amplitude from ScenePose.stringAmp)
 │   ├─ Pedals (PEDAL_ROT) / hammer rail + mute rail (RAIL)
 │   └─ Action cutaway set (Action view only; clip plane discard + SECTION_CAP)
 └─ Overlays: GlyphBoard labels (note name in Action), pedal PiP (Follow sub-mode, mono, scissored)
```

### 5.3 Procedural instruments (WP8; dimensions from `visual_design.md` §2 and `instrument-mechanics-and-sound.md` §2–§6)

- **Keyboard.** 164.5 mm octave. Equal 13.75 mm slots at the back, 23.57 mm natural heads at the front, cut-outs around the sharps. White keys 148 mm long [U], sharps 92 mm long and 12 mm high. Edges are **analytic bevels** in key-local UV space, not geometry gaps. The harpsichord uses a 159 mm octave and 7 mm dip.
- **Grand (C5).**
  - Case: Catmull-Rom outline, 200 × 149 × 101 cm. Lid on full stick at 38–40°.
  - 228 strings: 8 single, 20 pairs and 60 trichords, overstrung at 15–20° across the tenor.
  - Plate with lightening holes; spruce soundboard.
  - 88 hammers: heads 50 → 30 mm tall, shanks 133 mm. 70 dampers.
  - Action parts: key lever pivoting at 259.5 mm from the front, capstan, wippen, jack, repetition lever, let-off button, backcheck.
- **Upright (U3).** 131 × 153 × 65 cm. Vertical strings; strike line 1.08 m, damper row 1.18 m. Hammer rail and mute rail. Walnut or mahogany finish; ebony as an option.
- **Harpsichord (Flemish-German, single manual, FF–f‴).**
  - The Blanchet sheet's jack kit, reduced to one manual and two registers.
  - 61 keys: bone naturals (226,212,182), black-stained sharps treated as ebony.
  - Case in a warm painted colour with block-printed-paper bands. The lid motto *MUSICA LAETITIAE COMES MEDICINA DOLORUM* (a Ruckers motto, public domain).
  - Painted soundboard with rose. 122 jacks, 122 strings, gilt stand.

### 5.4 Motion models (WP6): evaluated from song time every frame, never tweened

All numbers are from `instrument-mechanics-and-sound.md`, section in brackets.

- **Key** (§1.2, §1.3, §2).
  - It starts at `t_on − tt(v)` with tt = 230 / 86 / 20 ms at velocity 20 / 64 / 110+.
  - Profile: pressed touch `dip × u^1.8`; struck touch (HV > 3 m/s) is near-linear with a stall at ⅓ of the dip.
  - It reaches the bottom at `t_on + keyBottomRel(v)`.
  - The key rotates about the balance rail, 2.24° at a full 10.16 mm dip (8 mm at the front of a sharp).
  - Release: a 35 ms ease-out on the grand, 50 ms on the upright.
  - Re-strike: from the current height, only after rising at least ⅓.
- **Grand hammer** (§3.1–§3.3). The state machine:
  - **RISING:** `h = 5.0 × keyDisp`, capped at blow − let-off (47 − 1.5 mm).
  - **FREE:** from `t_on − freeFlight(v)`, coasting at HV.
  - **CONTACT:** at `t_on`, lasting `4 × 0.2^((n−21)/87)` ms.
  - **REBOUND:** at 0.45 HV.
  - **CHECKED:** 15 mm below the string while the key is down.
  - **RELEASING:** rises to the drop height on the repetition lever, then falls to rest over the key's return plus 30 ms.
  - `ExposureSampler` shows the contact in any frame whose 33 ms window contains `t_on`. A real eye would see that strike as a blur, so every strike is visible at 30 fps.
- **Dampers** (§4.1).
  - Lift = max(key lift from half hammer travel, pedal lift from ⅓ of the pedal's travel, sostenuto hold), times 5 mm [U]. With the pedal down, a key lifts its damper slightly further.
  - Keys above `lastDamper` have no damper drawn.
  - In the Inside view, a lifted head opens a **light gap**: the strip of string that was in the felt's shadow becomes lit.
- **Pedals.** Travel comes from the shared `PedalCurve`, so audio and picture agree. Pedal angle `p × 5°` [U]. The damper lift tray moves.
  - Una corda slides the keyboard and action 2.5 mm toward the treble [U] over 60 ms.
  - Upright soft pedal: the hammer rail and every hammer move 22 mm toward the strings.
  - Upright mute rail: drops 12 mm over 150 ms.
  - Sostenuto rail: rotates 45° → 90°.
- **Harpsichord jacks** (§6).
  - Jack rise = key travel × 1.25, up to the jack rail. The 4′ passes its pluck height at 2.6 mm and the 8′ at 4.2 mm. The quill visibly bends the string, then slips.
  - On release the jack falls. As it passes the pluck point, the tongue swings back up to 12° for 20 ms, then re-centres. The damper cloth lands.
  - A disengaged register slides 1.5 mm sideways, so its quills rise and miss the strings.
  - Key lead: 15–40 ms by velocity [U].
- **Strings** (§8.4).
  - A spindle ribbon: `halfWidth = A × g × sin(πx/L)`, minimum 1.2 px.
  - A comes from **the audio thread's per-key energy**, so dampers are seen stopping strings and resonating strings are seen shimmering. Visual gain is 5–20: exaggerated ×4–6 in the Inside view only, and documented as such.
  - A 2–12 Hz wobble for "stroboscopic" motion, and a 150 ms strike pulse travelling out from the strike point (1/8 in the bass, falling to 1/15 in the treble).
  - Colours: copper for wound strings, pale steel for plain.

### 5.5 The venue (WP9, following `visual_design.md` §1 and §3)

- **Room.** The Konzertzimmer design room.
  - Gilt trellis, and a spider-web ceiling with 16 spokes and 9 turns (UNVERIFIED counts).
  - Hunting cartouches in the cove.
  - Three mirrors on the north wall, six Pesne panels (the canvas left dark), three windows (transparent, so the real world shows through), two doors.
  - 18 chairs, and a parquet floor pool.
- **Light.**
  - About 50 flames. Three additive layers per flame, all flames in one draw.
  - Mirror flames in one draw through the stencil. At Q0, one extra north–south bounce between mirrors facing each other.
  - 160 crystals, sparkling with the head's movement.
  - Lighting baked per vertex from all flames at load, plus at most 4 dynamic lights. Flicker is ±4% global plus variation per sprite.
- **Palettes:** Sanssouci 1747 (default) or Stadtschloss 1747 (celadon walls near flames).

### 5.6 Colour on the waveguide

- **Black instruments are drawn as reflectors, not as dark colours** (`visual_design.md` §3.2):
  - a presence floor, (22,18,15) by default, **calibrated on the glasses with the first build's test card**;
  - a Fresnel rim light;
  - candle speculars;
  - a 128×64 baked reflection probe, so candle streaks slide over the lid as the head moves;
  - a feature-edge overlay in Passthrough.
- **Brightness.** Large surfaces are capped at 220. Pure 255 is kept for flame cores and sparkles. Blue is kept low.
- **Average picture level (APL) budgets:** Salon ≤ 12%, Stage ≤ 9%, Instrument ≤ 6%, Passthrough ≤ 5%. They are measured from screencaps (§8).

### 5.7 Draw calls and triangles

The draw list is `visual_design.md` §5.2: about 28 draws per eye, a hard budget of 35, and about 39k triangles per eye (budget 45k).

- **All 88 keys are one draw**, likewise the hammers, the dampers, and the strings (steel and wound). The action cutaway adds 3 draws.
- Per frame the CPU evaluates about 88 × 4 part states into primitive arrays and makes 5–8 `glUniform4fv` calls.
- No `glBufferData` in the steady state and no Kotlin allocation in `onDrawFrame`. Labels are rasterised at most 3 per frame (GlyphBoard).

### 5.8 Quality ladder (one governor for picture and sound)

The inputs are MathCosmos's: battery temperature, because the Android thermal status reads 0 on this device, plus that thermal status. Q1 and Q2 use the sibling thresholds. Q3 is new [D].

| Level | Enter / relax (battery °C) | Picture | Sound |
|---|---|---|---|
| Q0 | — | 30 fps, 4× MSAA, Salon in the Hall and Stage elsewhere, mirror flames, 160 crystals | Full |
| Q1 | ≥ 39.0 / < 37.5 | 30 fps, no MSAA, Stage everywhere, no mirror flames, 60 crystals | Full. **Audio is the product, so it is the last thing to degrade.** |
| Q2 | ≥ 42.0 / < 40.5 | 20 fps, Instrument level | Voice caps per §3.7, 32 resonators |
| Q3 | ≥ 44.0 / < 43.0 [D] | 15 fps, Passthrough | Q3 caps, linear interpolation, no resonators |

Display-off listening (the sleep button, `onPause`) stops rendering entirely. That is the coolest way to hear a whole sonata.

---

## 6. Asset pipeline (Python on the Mac)

### 6.1 Tools

The tools use Python 3.14 with numpy 2.4 (both present) and Homebrew ffmpeg/ffprobe with libopus (present; no soxr, so resampling, if needed, uses swr with `filter_size=64:cutoff=0.97`). No scipy and no new installs.

| Tool | Does |
|---|---|
| `tools/common.py` | Paths, polite sequential fetch (User-Agent; one file at a time; a 2 s gap for Wayback, 0.3 s for GitHub), retries, `sha1_b32`, manifest readers |
| `tools/fetch_samples.py` | Reads `docs/research/sample-download-manifest-realism.tsv` (§6.2), downloads into `raw/samples/…`, and checks each byte count against the manifest |
| `tools/fetch_midi.py` | Reads `tools/repertoire_manifest.csv` (made from `repertoire.md` §8). Tries piano-midi.de first, then the Wayback raw URL, and **checks the SHA-1 (base32)**. Extracts Sankey zip entries **byte for byte**, recording the SHA-1 of each entry. For IMSLP it prints the two disclaimer URLs and waits for the user to put the files in `raw/midi/imslp/`, because clicking "I understand" is the user's decision |
| `tools/analyze.py` | numpy analysis:<ul><li>onset: energy envelope plus first difference, refined to the sample;</li><li>f0: autocorrelation over the 200 ms after the onset, with parabolic refinement;</li><li>T60: Schroeder backward integration;</li><li>10 ms RMS envelope and noise floor;</li><li>attack loudness: 50 ms RMS after the onset.</li></ul> |
| `tools/build_instrument.py {grand,upright,harpsichord}` | Parses the SFZ and `Data/*.txt` → trims, fades and normalises → smooths levels → dithers → encodes with `ffmpeg -c:a libopus -b:a 128k -vbr on -application audio -frame_duration 20` → writes `instrument.json`, `samples/*.opus` and a report (§6.3) |
| `tools/midi_facts.py` | Standard-library SMF parser: format, division, channels, note range, CC64/66/67 counts, pedal mode, duration through the tempo map. Also writes the golden file `app/src/test/resources/midi_facts_golden.json` used to cross-check the Kotlin parser |
| `tools/build_catalog.py` | `tools/catalog_meta.csv` (shelves, titles, composers, credits, default instruments from `repertoire.md` §4) plus facts from `midi_facts.py` → `assets/catalog/catalog.json`. Fails if any entry lacks a licence, credit or SHA-1 |
| `tools/credits.py` | `tools/ledger.csv` → `assets/licenses/` (CC-BY-SA-3.0-DE.txt, CC-BY-SA-4.0.txt, CC-BY-SA-3.0.txt, SANKEY.txt verbatim, CC-BY-3.0.txt, CC0.txt), `assets/credits.json`, `CREDITS.md` and `NOTICE`. **Fails the build if any shipped asset matches no ledger row**, or if a Sankey file's bytes differ from its zip entry |
| `tools/make_test_midis.py` | Our own CC0 test files: `stress.mid`, `sync.mid` (one C5 every second plus a flash marker), `pedal_half.mid` (continuous CC64 ramps), `sostenuto.mid`, `unacorda.mid`, `repeat15.mid` (15 notes per second on one key), `fold.mid` (notes 12–120) |
| `tools/make_test_bank.py` | A tiny synthetic bank (decaying sines and clicks at known onsets) so WP3 and WP4 can be tested before the real samples exist |
| `tools/size_report.py` | Checks the APK assets against the budget in §6.5 |
| `tools/device/*.sh`, `apl.py` | Deploy, soak, CPU, APL and A/V capture (§8) |

### 6.2 The download this proposal needs (an amendment to the approved list)

The approved per-file list is 685 files, 786.0 MiB required. This proposal changes it:

- **Adds** Salamander layers v2, v3, v5, v6, v8, v9, v11, v12, v14 and v15 for all 30 notes: 300 files, **456,288,480 B** (the 16-layer total of 726,974,711 B minus the approved 6-layer 270,686,231 B). The URLs follow the same verified pattern.
- **Moves from optional to required:** grand `harmL/S` (46 files, 10,076,858 B) and the upright pp layer (27 files, 65,307,007 B).
- **Drops** the English lute stop (54 files, 32,583,948 B) and its SFZ map.
- **New required total: 1,355,835,593 B ≈ 1,293 MiB.**

**This needs the user's approval before anything is downloaded.** `fetch_samples.py` refuses to run without the amended TSV, which WP12 writes. If the user approves only the original list, the pipeline builds a 6-layer grand with `layerMode: XFADE`, and the engine plays it unchanged. That is a supported fallback, not the plan.

### 6.3 What `build_instrument.py` does to every sample

1. **Decode** to float32 at 48 kHz (`ffmpeg -f f32le`), resampling only if the source is not already 48 kHz.
2. **Onset.** Salamander's `vel_XX` offsets give the coarse cut. `analyze.py` refines the hammer onset to the sample. The file starts **3 ms before** the onset, and `onsetFrame` is stored (always about 144 frames, but measured).
3. **Tail.** Cut where the RMS falls 60 dB below the attack peak or reaches the noise floor + 6 dB, whichever comes first. Then a 300 ms raised-cosine fade. Caps: grand 24 s at A0 down to 4 s at C8; upright 12 → 3 s; harpsichord 14 → 4 s. Real bass tails matter under the pedal (Moonlight i, Waldstein).
4. **Tuning.** f0 is measured. The root correction combines `tune_ret` and the measurement, and any sample still more than 5 cents off after `tune_ret` is flagged. `keyF0Hz[88]` is written for the resonators, with the stretch kept. `recordedAHz` is derived per instrument, which settles the VCSL harpsichord's pitch standard (A440 or 415, UNVERIFIED until measured).
5. **Level smoothing.** This is our own fix for Salamander's known unevenness between layers.
   - For each root, the 16 attack loudnesses are fitted with isotonic regression followed by a 2nd-order smoothing.
   - For each layer, a 3-tap median is taken across roots.
   - The correction is clamped to ±2.5 dB and stored in `gainDb`.
6. **Normalise** to −1 dBFS peak and store the natural gain. Apply TPDF dither to 16-bit, then encode Opus.
7. **Damper calibration.** From the release samples, estimate the decay of a damped string per register and fit the `damperT60` curve (the mechanics report's §3.5 defaults are the starting point). Write it to `damperT60s[88]`.
8. **SFZ carry-over:** velocity splits, `amp_veltrack`, `rt_decay`, `extraRegions` (harm), pedal-noise volumes, and the upright's undamped keys → `lastDamper`.

### 6.4 `instrument.json` (schema v1, excerpt)

```json
{ "schema": 1, "kind": "grand", "bankHash": "sha1-of-all-opus-and-this-json", "recordedAHz": 440.0,
  "layerMode": "HARD", "veltrackDefault": 0.73, "lastDamper": 88, "headMs": 250,
  "layers": [ { "index": 0, "velLo": 1, "velHi": 26 }, "… 16 entries" ],
  "samples": [ { "id": 0, "file": "samples/A0v1.opus", "root": 21, "layer": 0, "frames": 1104000,
                 "onsetFrame": 144, "gainDb": -18.4, "tuneCents": 3.1, "rmsEnv": "base64 u8 dB per 10 ms" } ],
  "keyF0Hz": [27.52, "… 88 values"], "damperT60s": [1.31, "… 88 values"],
  "releases": { "mode": "perKey", "samples": [ "… rel1..rel88 ids" ], "rule": { "rtDecayDbPerS": 0.0, "veltrack": 0.5 } },
  "pedalNoises": { "down": [ "ids" ], "up": [ "ids" ], "volumeDb": -20 },
  "stops": [], "extraRegions": [ { "sample": 612, "trigger": "release", "lokey": 21, "hikey": 23, "cc64": [64, 127], "volumeDb": -6 } ] }
```

The `extraRegions` entry shows the shape only. The real conditions are copied from Salamander's SFZ, whatever they are.

### 6.5 Size budget

| APK part | Budget | Estimate |
|---|---|---|
| `instruments/grand` | 70 MB | ≈ 55 MB |
| `instruments/upright` | 16 MB | ≈ 13 MB |
| `instruments/harpsichord` | 9 MB | ≈ 6.5 MB |
| `midi/` (70 works plus the complete Scarlatti) | 6 MB | ≈ 4.8 MB |
| Textures and atlases (rocaille 1024² ASTC, parquet, soundboards, lid) | 4 MB | ≈ 2 MB |
| Code, dex and resources | 6 MB | ≈ 4 MB |
| **APK total** | **≤ 115 MB (hard fail at 125 MB)** | ≈ 85 MB |
| On the device: PCM caches | ≤ 1.4 GB | ≈ 1.1 GB |

- **Git.** `*.opus` under `app/src/main/assets/instruments/` goes in LFS (`.gitattributes`), about 75 MB per bank version. Commit a bank only at milestones, because GitHub's free LFS allowance is 1 GB of storage and 1 GB of bandwidth a month.
- `raw/`, `*.apk`, `local.properties` and any Fish config stay git-ignored. MIDI files are small and go in plain git.
- The commit identity is `tropicalstream <tropicalstream@users.noreply.github.com>`.

---

## 7. Work breakdown for parallel implementation

**Rules for every package:**

- Code only against `core/` (frozen after WP1 phase 0) and the stubs in `app/Stubs.kt`.
- Touch only files you own.
- Pure packages must pass `./gradlew :app:testDebugUnitTest` with no Android runtime (`unitTests.isReturnDefaultValues = true`; `org.json:json` as a test dependency).

### 7.1 Packages

| WP | Name | Owns (exclusive) | Consumes | Acceptance tests |
|---|---|---|---|---|
| **WP1** | Platform, contracts, integration | Gradle files, wrapper, manifest, `res/**`, `HammerklavierApp.kt`, `MainActivity.kt`, `core/**`, `platform/**`, `app/**`, `baseline-prof.txt` | — | **Phase 0 gate:** builds; installs; title text in both eyes; `--es tap 1` and `--es swipe fwd` log through `InputRouter` stubs; a fake battery temperature of 40.0 °C logs Q1. **JVM:** `SongClock` reads stay consistent against a 10 kHz writer thread for 10 s; its extrapolation is exact for synthetic timestamps; the `EnergyRing` slot nearest a frame is found; `SpscRing` passes 10⁷ items without loss or reordering; `PedalCurve.at` and cursor agree at 10⁵ random times. |
| **WP2** | MIDI | `midi/**`, `test/midi/**`, `test/resources/midi/**` | `core` | **JVM, with hand-built byte arrays:**<ul><li>VLQ 0x00, 0x7F, 0x80 and 0x0FFFFFFF, and a 5-byte VLQ rejected;</li><li>running status across notes, cancelled by meta and by sysex;</li><li>velocity-0 note-on = note-off;</li><li>F0 and F7 sysex skipped;</li><li>an unknown chunk skipped;</li><li>RMID unwrapped;</li><li>SMPTE −25/40 timing;</li><li>a tempo change in track 2 of a format-1 file;</li><li>format 0 and format 1 of the same music produce identical `Performance`s;</li><li>tie order (a re-strike at the same tick gives 2 notes, not a stuck key);</li><li>overlapping notes on one key from two channels;</li><li>range fold with collision merge;</li><li>finger-pedalling caps (1.5 s, next note-on);</li><li>switch pedal crosses 0.33 at the event time ± 0.1 ms;</li><li>up/down 30 ms apart makes a dip below 0.33;</li><li>continuous detection;</li><li>channel 10 dropped;</li><li>the golden file from `midi_facts.py`: note count, range and duration within 1 ms on 12 real files.</li></ul> |
| **WP3** | Sample bank runtime | `bank/**` | `core`; `make_test_bank.py` output | **JVM:** cache header round-trip; `pick()` returns the nearest root and the right rate for EQUAL, WERCKMEISTER_III and 415 (the harpsichord at 415 uses root −1 with −1.27 ± 0.01 cents); the HARD layer matches the Salamander splits for all 127 velocities; XFADE weights have equal power; the streamer (run on the JVM with a temp file) delivers data identical to the file across ring wraps for 200 slots. **Device:** the grand voices without error; decode speed and cache size logged; relaunch skips voicing; a changed `bankHash` rebuilds only that instrument. |
| **WP4** | Audio engine core | `audio/*.kt` (not `dsp/`) | `core`, stub `SampleSource` | **JVM (offline `EngineGraph` with the synthetic bank):**<ul><li>`sync.mid` click onsets land on the exact frame for tempo 1.0, 0.5 and 1.37, including across a pause and a seek;</li><li>the voice cap is honoured and the stealing order is right;</li><li>a sub-50 ms voice is never stolen;</li><li>damper landing is off + 17 ms ± 0.7 ms;</li><li>measured T60 under full damping matches `damperT60(k)` ± 10%;</li><li>re-pedalling freezes the level;</li><li>sostenuto latch cases (pedal up, pedal down, key released before the edge);</li><li>una corda routes to the shelf bus;</li><li>no allocation (checked with `-verbose:gc` or allocation counts in a test hook).</li></ul>**Device:** `getUnderrunCount` = 0 over 10 min of the stress piece; the `--ez bench true` ns-per-voice-frame figure is logged; the audio track is FAST in `dumpsys media.audio_flinger` (UNVERIFIED with USAGE_MEDIA; not required). |
| **WP5** | Audio DSP | `audio/dsp/**` | `core` (`VenueGeometry`) | **JVM:**<ul><li>all comb feedback gains below 1 (a 10⁶-sample impulse decays);</li><li>a comb tuned to C3 peaks within ±2 cents of 130.81 Hz and of its 2nd–6th harmonics;</li><li>FDN impulse-response T60 (Schroeder) within ±10% of target in the 500 Hz and 2 kHz bands;</li><li>no mode rings more than 6 dB above its neighbours (a 1 s FFT);</li><li>image-source delays for a listener at the room centre equal the analytic values;</li><li>Sabine on the design room gives 1.6 ± 0.1 s at 500 Hz;</li><li>limiter output ≤ −0.5 dBFS for +20 dB input;</li><li>enhancer off = bit-exact bypass.</li></ul> |
| **WP6** | Mechanics | `mech/**` | `core` | **JVM:**<ul><li>`keyTravelMs(64)` = 86 ± 1 and (20) = 230;</li><li>`hammerVelocity(57.96)` = 1.0;</li><li>maximum key angle 2.24°;</li><li>the hammer reaches blow distance exactly at `t_on` and is CHECKED 15 mm below the string while held;</li><li>`ExposureSampler` shows the contact for every note in `repeat15.mid` at 30 fps;</li><li>the damper lifts when the hammer passes half its travel;</li><li>a re-strike never starts before ⅓ rise;</li><li>the harpsichord 4′ plucks before the 8′ by the stagger;</li><li>evaluating at time t is independent of the previous frame time (seekable, frame-rate independent).</li></ul> |
| **WP7** | Renderer core | `render/**`, `assets/shaders/**` | `core`, stub meshes, stub `ScenePose` | **Device:**<ul><li>the EGL config (MSAA, stencil) is logged;</li><li>30 fps held with a stub scene;</li><li>draw count ≤ 35 per eye logged;</li><li>zero allocations in `onDrawFrame` (Studio profiler, once);</li><li>view transitions keep the FOV fixed;</li><li>`--ef fov` and `--ef ipd` apply live;</li><li>presence-floor and sync-flash test cards render in both eyes.</li></ul> |
| **WP8** | Instrument models | `scene/**` | `core` | **JVM:**<ul><li>white-key front width 23.57 mm and sharp pitch 13.75 mm;</li><li>88/88/61 keys;</li><li>228 grand strings and 70 dampers;</li><li>every skinned vertex's slot and lane valid;</li><li>triangle counts within the §5.7 budget;</li><li>case bounds 2.00 × 1.49 m (grand) and 1.31 m tall (upright);</li><li>`camera()` gives the `visual_design.md` §4.6 poses.</li></ul> |
| **WP9** | Venue | `venue/**`, `assets/textures/venue/**` | `core` | **JVM:** `VenueGeometry` areas add up (floor 84 m², walls 170 m²); ≤ 18k triangles; 50 ± 2 flames; mirror reflections are exact reflections of the flame positions. **Device:** APL per level against §5.6. |
| **WP10** | UI and input | `ui/**` | `core`, `PlaybackController` stub, `Library` stub | **JVM** (`InputRouter` with a fake gesture source): the full gesture table of §1.3 in every context; a double-tap never also fires a tap; edit mode and cancel. **Device:** menus legible at 22/18 px in both eyes; HUD refresh ≤ 2 Hz (logged invalidations). |
| **WP11** | Library, import, companion | `library/**`, `companion/**`, `assets/companion/**` | `core`, `midi` (for validation) | **JVM:**<ul><li>catalogue parse;</li><li>sanitise, and ` (n)` on a name clash;</li><li>bytes kept identical;</li><li>MThd and RMID sniffing;</li><li>a 4 MB cap;</li><li>composer-token default instrument;</li><li>UTF-8 names ("Für Elise", "Händel").</li></ul>**Device:** upload from a phone browser; `adb push` then rescan; a write without the token is refused (403); `/api/state` answers in under 50 ms while audio plays with no underrun. |
| **WP12** | Asset pipeline and device tooling | `tools/**`, `raw/` (ignored), `app/src/main/assets/{instruments,catalog,midi,licenses}/**`, `CREDITS.md`, `NOTICE`, `.gitattributes`, `docs/research/sample-download-manifest-realism.tsv` | — | **Mac:**<ul><li>every Krueger SHA-1 matches;</li><li>Sankey entries are byte-identical;</li><li>`midi_facts` golden file generated;</li><li>`build_instrument` report shows onset jitter ≤ 1 frame, tuning residual ≤ 5 cents, level-smoothing corrections within ±2.5 dB, and no clipping;</li><li>`credits.py` passes;</li><li>`size_report.py` passes.</li></ul>Downloads run **only after the user approves the amended manifest.** |

### 7.2 Interfaces between packages (all in `core/`, §2.4)

| Producer → consumer | Contract |
|---|---|
| WP2 → WP4, WP6, WP7, WP1 | `Performance`, `Ev`, `PedalCurve`, `PerformanceMeta` |
| WP12 → WP3 | `instrument.json` schema v1 (§6.4) → `InstrumentSpec`; Opus files; the PCM header |
| WP3 → WP4 | `SampleSource`, `Pick`, `StreamService`, `StreamRing` |
| WP4 → WP7, WP10, WP1 | `Engine`, `EngineCommand`, `SongClock`/`ClockRead`, `EnergyRing`, `EngineStatus` |
| WP5 ↔ WP4 | DSP classes with the signatures in §2.4, instantiated by `EngineGraph` |
| WP9 → WP5, WP7 | `VenueGeometry`, `VenueModel` |
| WP6 → WP7 | `MechanismModel`, `ScenePose` |
| WP8, WP9 → WP7 | `InstrumentModel`, `BakedMesh`, `CameraPose`, `SkinKind`, `MaterialId` |
| WP7 → WP1, WP10 | `StageControl` |
| WP11 → WP1, WP10 | `Library`, `LibraryEntry`, `ImportResult` |
| WP1 → everyone | `PlaybackController`, `SettingsStore`, `AudioSettings` |

A change to any `core/` file needs WP1's approval and a note to every consumer.

### 7.3 Integration order

1. **Phase 0 (WP1).** Skeleton, `core/`, stubs. Gate: compiles on the JVM and on the device.
2. **Wave A, in parallel:** WP2, WP5, WP6, WP8, WP9, WP10 and WP11 against the stubs. WP12 writes the tools and the synthetic bank, and asks for the download approval.
3. **Wave B, in parallel:** WP3 and WP4 against the synthetic bank. WP7 against stub meshes, then WP8's grand.
4. **I1 "first sound".** The real grand bank (WP12 → WP3) plus WP4 plus WP5's limiter. `PlaybackController` plays Für Elise.
   - Device gate: 0 underruns in 5 min; hk-audio CPU logged; release samples and pedal noises audible.
5. **I2 "keys in time".** WP6 + WP7 + WP8 grand in the Player view.
   - Gate: A/V sync test (§8.4) within ±15 ms after calibration; keys visibly lead the sound at pp.
6. **I3 "the room".** WP5 in full (resonance, early reflections, FDN, listener following the view) plus WP9 and the Hall view.
   - Gate: APL ≤ 12% in Salon; the listening position changes audibly between Player and Hall.
7. **I4 "three instruments, four views".** Upright and harpsichord banks and models; Action and Inside views.
   - Gate: harpsichord at A415 with the root remap verified by ear and in the log; jacks and tongues visible.
8. **I5 "library and import".** WP10 menus, WP11 catalogue, companion and adb push.
   - Gate: all 70 works play; imports appear in under 10 s.
9. **I6 soak and polish.** Thermal soak, calibration cards, baseline profile, credits screen.

---

## 8. Verification on the real glasses (serial A06B4A96A733283)

### 8.1 Build and install

The APK is a **release build signed with the debug key and not debuggable**, so the DSP runs as compiled code. It is then compiled ahead of time on the device:

```bash
cd /Users/me/Projects/Hammerklavier && ./gradlew :app:assembleRelease && \
adb -s A06B4A96A733283 install -r app/build/outputs/apk/release/Hammerklavier-release.apk && \
adb -s A06B4A96A733283 shell cmd package compile -m speed -f com.tropicalstream.hammerklavier && \
adb -s A06B4A96A733283 shell settings put global device_wearing 1 && \
adb -s A06B4A96A733283 shell wm dismiss-keyguard && \
adb -s A06B4A96A733283 shell am start -n com.tropicalstream.hammerklavier/.MainActivity
# flat captures: … am start -n …/.MainActivity --ez mono true
# after bench work: adb -s A06B4A96A733283 shell settings put global device_wearing 0
```

`app/src/main/baseline-prof.txt` lists `audio/`, `audio/dsp/`, `mech/` and `render/`, using `androidx.profileinstaller` so a plain install also gets them compiled ahead of time. Always pass `-s`, and always chain with `&&`.

### 8.2 CONTROL broadcasts

Action: `com.tropicalstream.hammerklavier.CONTROL`.

| Area | Extras |
|---|---|
| Playback | `--es play <entryId>`, `--ez pause true`, `--ez resume true`, `--ei seek <ms>`, `--ef tempo 0.8`, `--ez next true`, `--ez prev true` |
| Instrument and sound | `--es instrument grand\|upright\|harpsichord`, `--es temperament WERCKMEISTER_III`, `--ef pitch 415`, `--ei registration 3`, `--es resonance RICH`, `--ef reverb -2` |
| Views | `--ei view 0..3`, `--ei sub 0..2`, `--ef fov 31.2` (current view), `--ef ipd 0.6`, `--ez recenter true` |
| Venue and quality | `--ei quality 0..3\|-1` (-1 = automatic), `--ei faketemp 405`, `--es venue SALON`, `--es palette STADTSCHLOSS_1747` |
| Calibration | `--ei floor 22`, `--ei avoffset 35`, `--es card floor\|sync` |
| Diagnostics | `--ez debug true`, `--ez bench true`, `--ez dump true` |
| Test pieces | `--ez synctest true`, `--ez stress true` |
| Input | `--es tap 1\|2\|3`, `--es swipe fwd\|back\|up\|down` (call the same `InputRouter` entry points as the pad) |
| Library | `--ez rescan true` |

### 8.3 CPU, heat and dropouts

- **Threads:**

  ```bash
  adb -s A06B4A96A733283 shell top -H -b -d 1 -n 60 -p $(adb -s A06B4A96A733283 shell pidof com.tropicalstream.hammerklavier) | grep -E 'hk-audio|hk-stream|GLThread|hk-voicer'
  ```

  Thread names are 15 characters or fewer.
- **Heat:** `adb -s A06B4A96A733283 shell dumpsys battery | grep temperature` every 30 s, plus the app's `HKThermal` log lines. Reading `/sys/class/thermal/thermal_zone*/temp` is attempted read-only; access is UNVERIFIED.
- **App statistics** every 5 s:

  ```bash
  adb -s A06B4A96A733283 logcat -s HKAudio HKStream HKFrame HKThermal AndroidRuntime:E
  ```

  - `HKAudio`: `underruns` (from `AudioTrack.getUnderrunCount()`), buffer frames, cpu EWMA, voices and peak, starved, quality.
  - `HKFrame`: fps and hitches over 120 ms.
  - Cross-check with `adb -s A06B4A96A733283 shell dumpsys media.audio_flinger`, which shows track underrun counts and the FAST flag.
- **Pass criteria:**

  | Test | Criterion |
  |---|---|
  | Stress piece, Q0 forced, 10 min | underruns = 0, starved = 0, hk-audio ≤ 40% of one core |
  | **Soak:** Hammerklavier op. 106 complete (35 min), governor automatic | no reboot, underruns = 0, frame hitches ≤ 3, battery < 44 °C at the end, quality ≤ Q2 |
  | Display-off listening, same piece | battery temperature at least 3 °C below the soak |

  `tools/device/soak.sh` runs the soak and writes a CSV.

### 8.4 Audio/visual sync

1. **Internal check:** with `--ez debug true`, the overlay shows `songUs_vis − songUs_audio_presented` together with `ClockRead.fromTimestamp`. After a settling time of 1 s it must stay steady within ±2 ms.
2. **Perceptual calibration:** the `sync` card plays `sync.mid` (a key strike, an audible click and a full-frame gilt flash, all at the same song time). The wearer adjusts `avOffset` in 5 ms steps until flash and click seem simultaneous. It is stored per device and defaults to `displayLatency` = 40 ms [D].
3. **External measurement:** film the lens at 240 fps with a phone next to the temple speaker. Measure the flash-to-click offset from the recording's frames over 20 events. The target is |mean| ≤ 15 ms with a standard deviation ≤ 8 ms. The phone's own audio/video alignment adds about ±10 ms, UNVERIFIED.
4. **Look-ahead check:** in the Action view at 10% speed, `pedal_half.mid` and `repeat15.mid` must show the key moving before each click and the hammer at the string on the click.

### 8.5 The picture

- `adb -s A06B4A96A733283 exec-out screencap -p > view.png` for each view and instrument, then `tools/device/apl.py` averages the Rec.709 luma against §5.6.
- Whether screencap captures the GL layer is UNVERIFIED. The fallback is scrcpy recording.
- **The presence-floor card is the first thing built, and the first thing tested on the glasses.**

---

## 9. Risks and non-goals

### 9.1 Risk register

| # | Risk | Likelihood / impact | Mitigation |
|---|---|---|---|
| R1 | The user declines the 1.29 GiB amended download | Medium / medium | Build the 6-layer grand from the approved list with `layerMode: XFADE`. The engine handles both. |
| R2 | ART code is slower than the 35 ns per voice-frame estimate (§3.14) | Medium / high | Release build, forced AOT compile and a baseline profile. The bench mode measures on day one. Step-down order: linear interpolation, then 44 resonators, then 72 voices. |
| R3 | Page-cache eviction or slow flash starves the streams on a `low_ram` device | Low / high | Heads resident for 250 ms (a 150 ms margin after the 100 ms deferred open); `hk-stream` at audio priority; 64 KB reads; a `starved` counter. Worst case, the head grows to 400 ms (+14 MB). |
| R4 | Opus pre-skip or padding shifts onsets and breaks sample-accurate timing | Medium / high | Exact frame counts in `instrument.json`; drop 312 if seen, fail otherwise; an onset check in the WP3 device test. |
| R5 | Salamander's `harm*` SFZ semantics are unexpected | Medium / low | The build fails on unknown opcodes. The resonance bank alone gives the pedal halo. |
| R6 | The resonators sound metallic or phasey, or double what harm already adds | Medium / medium | Calibrate with harm on; −30 dB default; allpass tuning to the measured f0; "off" in the menu. |
| R7 | FDN metallic ringing on piano | Medium / medium | Mutually prime delays, two modulated lines, the mode-flatness test in WP5. |
| R8 | Heat: 30 fps stereo plus MSAA plus 96 voices exceeds the budget | Medium / high | A four-level governor degrading picture first; display-off listening; the soak gate. MSAA is the first thing dropped. |
| R9 | Voicing takes longer than 60 s, or the process is killed | Medium / low | Resumable; grand first; the library can be browsed meanwhile; background priority; pause at 39 °C. |
| R10 | Stereo discomfort or wrong scale in some views | Medium / medium | Per-view IPD scale and FOV adjustable over CONTROL; zero parallax at the subject; tuned on the device. |
| R11 | The presence floor is invisible, so black lacquer vanishes | Medium / medium | Test card first; rim, speculars and probe reflections; the edge overlay; walnut default for the upright. |
| R12 | The mismatch between a Knight sound and a U3 case is noticed | Low / low | Honest label; the case is a documented choice using verified U3 dimensions. |
| R13 | Licences: the Krueger share-alike condition, the Sankey byte-identity rule, IMSLP disclaimer clicks | Low / high | Ship original `.mid` files and parse at runtime (no adaptation); `credits.py` refuses unledgered or modified files; no export feature; the user clicks the IMSLP disclaimer; Couperin is dropped unless Madore grants CC0. |
| R14 | piano-midi.de returns 418 | High / low | Wayback raw URLs with SHA-1 checks (`repertoire.md` §1). |
| R15 | LFS quota | Medium / low | Commit banks only at milestones; raw sources never in git. |
| R16 | `getTimestamp` is unreliable on a low-latency track on this device | Low / medium | Fallback from frames written and the buffer size, plus smoothing; the internal sync overlay shows which source is in use. |
| R17 | The built-in speakers cannot carry the bass | Certain / medium | Virtual-bass enhancer on the speaker route; headphones recommended on the title card and in About. |
| R18 | The left temple's firm click enters as a tap through `cyttsp6_btn` | Medium / low | Name filter on the key path, tested in WP10's device checks. |

### 9.2 Non-goals for v1

- **Any fortepiano, or any "early piano" made by filtering another sample set.** The fourth slot opens only for a licensed multisample.
- Physical-modelling synthesis, convolution reverb, and simulating each string physically.
- **Audio or video export or recording.** This avoids the Sankey and Krueger share-alike conditions on derived media.
- Live MIDI input from a USB or Bluetooth keyboard. It is the natural v2, because the engine is clocked from the sample counter.
- Score or notation display, hand colouring, a teaching mode.
- MAESTRO, SMD and other non-commercial packs; transcription datasets.
- The Viennese Walter action, the Silbermann case, and the Amalienburg venue.
- 6-DoF movement, multiplayer, and cloud sync.
- Changing the grand's lid position; the lid is always on full stick.
