# Hammerklavier: implementation plan (v2, FINAL after architecture review)

**Date:** 2026-09-22 · **Status:** the single source of truth for every implementation agent. It supersedes `docs/proposals/*.md` and v1 of this plan. Where this plan and a proposal or research report disagree, this plan wins. §10 lists every review finding and how it was resolved.
**Project:** `/Users/me/Projects/Hammerklavier` · **Package:** `com.tropicalstream.hammerklavier` · **App name:** Hammerklavier · **Target:** RayNeo X3 Pro (ARGF20), adb serial `A06B4A96A733283`.
**Lineage:** the *device* proposal is the chassis (threads, clock, storage, budgets, UX ring, tests). Grafted onto it: the *realism* proposal's audio and motion realism (16 layers, 88 comb resonators, spectral dampers, frequency-based root choice, EnergyRing, ExposureSampler, image-source room, virtual bass, pedal lead shaping, harpsichord stagger, release build for measurement), and the *delivery* proposal's process and robustness (day-0 contracts with working stubs, CI purity gate, worktrees, wiring notes, fallback chain, DecoderProbe, pre-roll, dip transitions, Q3 display rest, bounded parser, numpy+ffmpeg-only pipeline, self-test and smoke scripts). v2 folds in the review of 2026-09-22 (§10).

**Tags used for numbers:**
- **[M]** measured on the glasses or the Mac (by whom and when is stated or it is from `delivery.md`'s 2026-09-22 checks, re-confirmed by the architect on 2026-09-22: SDK 32, `ro.config.low_ram=true`, `heapgrowthlimit=192m`, four cores at `cpuinfo_max_freq` 1,996,800 kHz, battery 26.5 °C idle, 19 GB free on `/data`, no Hammerklavier installed; Mac: Python 3.14.3, numpy 2.4.3, **no scipy/PIL**, ffmpeg with libopus and **without soxr**, scrcpy, git-lfs 3.8.0, JDK 17.0.19, **no `flock(1)`** (`/usr/bin/shlock` only), 16 GB RAM, 8 cores; every Gradle dependency of §2.2 is in the Gradle cache except `profileinstaller:1.4.1`, so 1.4.0 is used).
- **[M:rev]** measured on the glasses by the reviewers on 2026-09-22 over adb: four in-order Cortex-A55 (part 0xd05) in one WALT frequency domain, steps 0.69–2.0 GHz, `scaling_max_freq` 1,497,600 kHz when not worn at 26 °C; cooling devices can cap cpufreq and pause cpu2/cpu3; `MemAvailable` ≈ 2.6 GB idle; storage eMMC (`mmcblk0`), `/data` f2fs with `fsync_mode=nobarrier`; `Android/data` a direct f2fs bind mount (no FUSE, no sdcardfs), adb-pushed files owned `2000:1078`; the primary output's normal-mixer tracks show 54–71 ms in the dumpsys Latency column, 960-frame normal mixer plus a FastMixer reporting 21 ms and `localSR` 47,931.2 Hz, timestamp stats disc=4 err=7 jitter max 1018 ms after standby; a `DEEP_BUFFER` output (AudioOut_25, 1920-frame period) exists; audioflinger standby delay 2 s; `c2.android.opus.decoder` runs in `media.swcodec` (pid 1007); `task_profiles.json` maps `SCHED_SP_BACKGROUND` to HighEnergySaving + LowIoPriority + TimerSlackHigh (40 ms); `dumpsys battery` shows `USB powered: true, status 5` when plugged in; SmartTube is installed.
- **[R:file §n]** from a research report in `docs/research/`; **[P:name §n]** from a proposal in `docs/proposals/`.
- **[D]** a design decision made here. Final unless a named test disproves it.
- **[E]** an estimate. The named test in §8 replaces it with a measurement before the milestone gate.

**Change control:** contract files (`contract/**`) change only through a dated entry in `docs/contracts-changelog.md` approved by WP0, under the growth rules of §7.1. Anything else in this plan changes through `docs/plan-changelog.md`, also approved by WP0.

---

## 0. Executive summary

**What we are building.** A native Android app for the X3 Pro glasses that plays MIDI files, from early Bach to late Beethoven, on three sampled keyboard instruments standing in a candlelit reconstruction of Frederick the Great's **Konzertzimmer at Sanssouci (1746–47)**. Every key, pedal, hammer, damper, jack and string moves from the same clock as the sound. Files can be imported from a phone browser or with `tools/device/push_scores.sh` (adb).

**Instruments (all real recordings, all redistributable):**

| Menu name | Sound | Sight |
|---|---|---|
| **Grand** | Salamander Grand Piano V3 (Yamaha C5). **16 velocity layers** hard-switched on Salamander's own splits, with a continuous level curve, if the user approves the HD download (recommended). Otherwise 6 layers with a ±4-velocity crossfade. 88 key-release noises, 4 pedal noises | 200 cm C5-size grand in ebony lacquer, lid on full stick |
| **Upright** | VCSL "Knight" upright (2 layers) plus the VSCO-2 CE pp layer of the same piano: 3 layers, ±6-velocity crossfade, 45 releases (which carry the damped string tail), 8 pedal noises | 131 cm U3-size upright with an underdamper action, **walnut** by default (ebony vanishes on the waveguide) |
| **Harpsichord · Bach era** | VCSL Flemish harpsichord, 8′ + 4′ choirs with their own jack-fall releases. Default **A415, Werckmeister III**, reached by choosing samples by sounding frequency (a transposing harpsichord), not by pitching every note down. The 8′ on keys 85–89 borrows the 4′ samples that already sound those pitches | Drawn as **a Flemish-style single manual, FF–f‴ (MIDI 29–89)**, 228 × 93 × 26 cm, bone naturals, two registers. The recorded instrument's maker and compass (FF–c‴) are not claimed |

There is no fortepiano: no licensable multisample exists, and faking one by filtering the grand would break the brief's "real samples" [R:sampled-instruments §3.4]. The engine and scene keep a fourth slot for v2.

**Views.** Three stops on the swipe ring, **Player → Action → Hall**, each with a second framing on vertical swipe: Player (whole keyboard and pedals) / Follow (three octaves tracking the music); Action (cutaway: the melody note's hammer striking its string) / Overhead (lid off, all hammers and strings); Hall (row 3 of the Konzertzimmer) / Life-size (orthoscopic). Every view or framing change is a 0.5 s **dip to transparent**; the field of view is never animated on the head-worn display. The sound follows the view: bench, inside the case, or row 3.

**Repertoire.** A catalogue of **70 works** (≈ 11–12 h): Bach (Capriccio BWV 992, Inventions, Sinfonias, WTC I complete, WTC II 1–12, suites, partitas, Italian Concerto, Chromatic Fantasia, Goldberg), Handel, Scarlatti (10 sonatas), Couperin, Rameau, C.P.E. Bach, Haydn, Mozart, Clementi and Beethoven (16 works including the complete op. 106 "Hammerklavier"). 67 ship after the download approval; 2 more need the user's own IMSLP click; 1 (Couperin) needs its author's permission. Sources: Bernd Krueger (CC BY-SA 3.0 DE), John Sankey (free-copy notice), Wikimedia Commons, Mutopia, IMSLP/Gouin.

**Key technical bets:**

| # | Bet | Why it is safe on these glasses |
|---|---|---|
| 1 | **The audio output is the only clock.** Keys, hammers, dampers, jacks and pedals are analytic functions of (immutable `Performance`, heard song time), so lookahead (a key moves up to 230 ms before its sound) is free, seeking needs no catch-up, and nothing is copied from the audio thread 30 times a second except 88 string energies. The clock is reset with every track, rejects bad timestamps, clamps to what was written, and the renderer filters it to be monotone | One clock cannot drift; `AudioTrack.getTimestamp` maps frames to the display; every cross-thread record lives in `java.util.concurrent.atomic` arrays, so the seqlocks are data-race-free under the JMM on ART/ARMv8 |
| 2 | **`PERFORMANCE_MODE_NONE` float AudioTrack**, 48 kHz stereo, 4096-frame (85 ms) buffer, 256-frame blocks, about 50 wakes/s; parked with `pause()` after 10 s idle | A sequenced player needs stability, not latency; 85 ms absorbs thermal jitter; the write head runs ≈ 140 ms ahead of the DAC on the speaker [M:rev], so every ring holds 1.37 s |
| 3 | **Opus in the APK (112 kb/s, 60 ms frames), one Ogg stream per decode unit** (18 for the HD grand), decoded once into a 16-bit PCM cache, memory-mapped, with **score-driven `pread` prefetch**. The grand is playable in ≤ 8 s; the rest voices only while nothing plays | Heap limit 192 MB on a `low_ram` device; page-cache pages are clean and evictable; no eMMC writeback competes with playback |
| 4 | **Table-driven Kotlin DSP**: a voice cap measured on the device at M1 (64–128, ≈ 96 [E]) plus a 12-slot noise pool, **88 comb string resonators fed without their own string**, spectral dampers, **image-source early reflections from the same room geometry the renderer draws**, an 8-line FDN, virtual bass for the temple speakers. Worst case ≈ 52% of one core at today's 1.5 GHz cap (≈ 39% normalised to 2.0 GHz), typical ≈ 21% [E] | No per-sample or per-block `pow`/`exp`/`log`/`sin`; all coefficient maths prepared as tables off the audio thread; self-protection driven by buffer headroom |
| 5 | **GLES 2.0 stereo** (two viewports, off-axis frustum), static VBOs, uniform-array skinning: all 88 keys in one draw. **≤ 28 draws and ≤ 45k triangles per eye**, 30 fps, 10 fps when idle, mirror flames culled on the CPU (no stencil); MSAA fixed for the session | Draw calls, not fill rate, cooked the sibling app [R:visual_design §5.2] |
| 6 | **One thermal ladder Q0–Q3** (battery 39.0 / 42.0 / 44.0 °C, 1.5 °C hysteresis) steps picture, sound and window brightness together. **Q3 = display rest**: the waveguide goes transparent and the music continues. The governor lives as long as the engine, including with the display off | Thermal status reads 0 on this device even when hot [R:engine_reuse §0]; battery temperature is the signal that works |
| 7 | **Delivery:** WP0 tags `contracts-v1` (signatures and trivial stubs) within hours and `contracts-v1.1` (working primitives, M0) within two days; 13 work packages own disjoint files; the pure code lives in a JVM-only `:core` module; one device lock serialises the glasses; 9 milestones (M0–M8), each installed and run on the glasses | Agents never block each other; every milestone is demonstrable |

**Budgets (acceptance lines, §8 names the tests):** HKAudio ≤ 42% of a 2.0 GHz core (CPU time normalised by the measured frequency) under the stress score and ≤ 20% on op. 106; block p99 ≤ 3.2 ms at the frequency actually run; buffer headroom never below 1,536 frames; GL thread ≤ 6 ms CPU per frame; Java heap ≤ 48 MiB; RSS ≤ 450 MiB; 0 underruns in a 30-min soak with the display on and with it asleep; A/V offset |median| ≤ 20 ms after calibration at both 30 and 20 fps; APL (average picture level) Player/Action ≤ 9%, Hall ≤ 12%; no reboot and never Q3 in a 45-min **unplugged** soak at 22 °C room temperature; APK ≤ 100 MB (HD) or ≤ 60 MB (standard).

**What the user must decide (the only blockers).** Nothing is downloaded until then; M0 and M1 run entirely on the in-code stand-in bank and our own test MIDIs. The approval request is `docs/DOWNLOADS.md`.
1. **Sample download:** *HD* (recommended) 884 files, 1,345,758,735 B = 1,283.4 MiB; or *Standard* 584 files, 889,470,255 B = 848.3 MiB. Lists: `docs/manifests/samples-v2.tsv` (§6.3).
2. **MIDI and licence texts:** 131 small files fetched by the tool, ≈ 5.6 MB (the list, `docs/manifests/midi.tsv`, has 134 rows including the 2 IMSLP files and the conditional Couperin) (§6.4).
3. **IMSLP:** the user clicks IMSLP's "I understand" for #365752 (Handel HWV 430) and #340106 (Rameau *La Poule*) and drops the two files into `tools/cache/midi/imslp/`. If skipped, those two works are simply absent.
4. **Couperin:** *Les Barricades mystérieuses* stays out unless David Madore grants a licence (optional email).

---

## 1. Product and UX specification

### 1.1 The instruments

| | Grand | Upright | Harpsichord · Bach era |
|---|---|---|---|
| Samples | Salamander V3: 30 roots a minor third apart (A0 … C8) × 16 layers (HD) or v1/v4/v7/v10/v13/v16 (standard); `rel1`–`rel88`; `pedalD1/2`, `pedalU1/2` [R:sampled-instruments §1.1] | VCSL Knight `Player_vl1/vl2` (45 roots, whole tones), VSCO-2 CE `Player_dyn1` (23 roots, major thirds), 45 releases, 4 + 4 pedal noises [§2.1] | VCSL Flemish `HarpsiRH_Low` 8′ (28 roots, keys 30–84) and `HarpsiRH_High` 4′ (26 roots, mostly whole tones), each with jack-fall releases [§3.1]. The English lute stop is **not** shipped (it is another instrument) |
| Licence | CC-BY 3.0; the author declared it public domain on 2022-03-04. Credited either way | CC0 1.0; courtesy credit | CC0 1.0; courtesy credit |
| Case drawn | 200 × 149 × 101 cm C5-size, ebony lacquer, one-piece lid on a 38° stick, three legs, lyre, three brass pedals | 131 × 153 × 65 cm U3-size, underdamper action; finish Walnut (default) / Mahogany / Ebony | A Flemish-style single manual after the ravalement Ruckers type, **228 × 93 × 26 cm** [D] (a 0.818 m keyboard, two 40 mm cheek blocks, 12 mm case sides): warm painted case, block-printed papers, Latin lid motto, painted soundboard with gilt rose, turned oak stand |
| Compass | A0–C8 (21–108) | A0–C8 (21–108) | FF–f‴ (29–89), 61 keys. The recording covers FF–c‴; the 8′ on keys 86–89 plays the 4′ samples of keys 74–77, which already sound those pitches (shift ≤ 1 semitone, level and brightness matched at the key-84 seam); only the 4′ on keys 85–89 is shifted up to +5 semitones |
| Pedals / stops that move | Una corda (whole keyboard and action slide 2.5 mm toward the treble), sostenuto, damper (continuous half-pedal) | Soft (hammer rail moves 22 mm toward the strings), damper. The middle pedal is drawn but static (practice rail is a non-goal) | No pedals. Registers 8′+4′ (default), 8′, 4′; a disengaged register's jacks slide 1.5 mm so their quills miss |
| Last damper | from the bank (Salamander SFZ: 88, E6; 89–108 always ring) | measured from the release samples' decay (an undamped string rings > 1 s), clamped to 86–92 if inconclusive, default 90 | none: every key is damped |
| Default tuning | A440 equal | A440 equal | **A415 Werckmeister III** |
| Velocity | Selects the layer; a continuous level curve through the layer centres sets the gain | Selects the layers (crossfade), same level curve | Sets key-travel lead and the 4′-before-8′ stagger only; gain constant ±1 dB deterministic humanising [R:mechanics §6.2] |

**About screen line (verbatim):** "Bach played Silbermann fortepianos at Potsdam in 1747; no recording of one may be shipped in this app, so a harpsichord of the Flemish kind German builders grew from stands in for his keyboard."

### 1.2 Views, framings and transitions

**Swipe forward/back moves round the ring Player → Action → Hall → Player. Swipe up or down toggles the second framing of the current view.** Each view remembers its framing.

| View | Framing 0 (primary) | Framing 1 (swipe up/down) | Venue level (Auto) | Head look-around |
|---|---|---|---|---|
| **Player** | Whole keyboard plus pedals from just above the bench (vertical FOV 34°): white keys 11 px, key dip 4.4 px, pedal travel 5.5 px [R:visual_design §4.1] | **Follow:** three octaves tracking the centroid of the sounding notes (30°, white keys 27.8 px), with a 200 × 150 px pedal inset bottom-right (grand and upright only; the HUD pills move to the top right) | Stage | ±5° parallax |
| **Action** ("hammers hitting strings") | **Cutaway:** a clip plane at the highest sounding note; ±6 neighbouring actions recede behind; key lever, capstan, wippen, jack escaping at the let-off button, repetition lever, hammer striking and caught by the backcheck, damper lifting, the string's vibration blur (22°; hammer travel 48 px). Harpsichord: jack rising, quill plucking, tongue swinging back, damper landing | **Overhead:** lid lifted off, looking down the string bed, all hammers (or jacks) flicking up, strings shimmering, the damper row lifting (44°) | Stage | ±5° parallax |
| **Hall** | Row 3 of the Konzertzimmer, wide (40°): lid-open instrument, mirror wall multiplying the flames, chandelier overhead | **Life-size:** the optical field (18.27° default, tunable over CONTROL): the 2 m grand at 4.9 m fills the eye at true size | Salon | World-locked: ±60° yaw, +45° pitch |

**Transitions.** Every view or framing change is a **dip**: the whole frame fades to black (transparent) over 250 ms, the camera, lid, clip plane and room level cut, and the frame fades back in over 250 ms. The FOV is fixed per framing and never animated. Within a framing, the Follow camera and the Action clip plane track the music with critically damped springs (§5.6). A swipe during a dip is queued (at most one). During display rest (Q3) or with the display off, a pending scene change is applied instantly on the first frame after wake (no dip).

**Sound follows the view** (§3.12): each (view, framing) has a listener pose. The early reflections, reverberant level, direct-sound distance, air absorption and stereo width glide to the new listener over the same 500 ms as the dip. In the Hall the direct and early sound are anchored to the room by the head yaw, read by the audio thread every block and predicted forward by the output latency, so the piano stays where it stands.

### 1.3 Input: every gesture in every context

The gesture engine is WanderQuest's `TrackpadGestureEngine`, copied verbatim [R:engine_reuse §3.2], plus one change: on the key path, events from a device whose name contains `cyttsp6` are dropped (a firm click on the left arm must not register as a tap; checked physically at M0 and in §8.8). Light taps (touch) and firm clicks (`KEYCODE_BUTTON_A` / `DPAD_CENTER`) merge into one tap stream, and one firm click that arrives as both a touch and a key yields one tap; a 300 ms resolver separates single, double and triple taps; each swipe fires once per gesture (latched, re-armed on UP/CANCEL); the left pad is ignored by name and stays the system volume pad (the app calls `setVolumeControlStream(STREAM_MUSIC)` and uses `USAGE_MEDIA`, so the left pad controls the piano's volume). Long-press never reaches apps and is not used. **`KEYCODE_BACK`** is intercepted in `dispatchKeyEvent`: in a menu, adjust, card or panel it acts as a double-tap (back, cancel, close); at the root (title card or playing with no overlay) it **leaves the app**: 300 ms fade, pause, resume point saved, `moveTaskToBack(true)`. Bluetooth headset buttons reach the app through its `MediaSession` (play/pause, next, previous) while a movement is loaded.

| Gesture | Title card | Playing (no overlay) | Menu card | Adjust (Position, Tempo) | Card (Display floor, A/V sync) | Panel (Credits, About, Import) | Display rest (Q3) |
|---|---|---|---|---|---|---|---|
| Swipe forward | – | next view (dip) | next row | +10 s / +5 % | brighter swatch / +5 ms | next page | queue next view; toast "display resting" |
| Swipe back | – | previous view | previous row | −10 s / −5 % | dimmer swatch / −5 ms | previous page | queue previous view |
| Swipe up | – | toggle second framing | page up (7 rows) | +60 s / +20 % | brighter / +5 ms | page up | – |
| Swipe down | – | toggle second framing | page down | −60 s / −20 % | dimmer / −5 ms | page down | – |
| Tap | enter (once the grand is playable; before that, shows the voicing pill) | play / pause | select | confirm | accept and store | close | play / pause |
| Double-tap | – | open the Transport menu | back one level; closes at the root | cancel and restore | cancel | close | open the Transport menu (text only) |
| Triple-tap | recentre gaze | recentre gaze and show the HUD | recentre gaze | – | – | – | recentre gaze |
| Back key | leave the app | leave the app (fade, pause) | = double-tap | = double-tap | = double-tap | = double-tap | leave the app |

- A tap waits 300 ms to be told apart from a double-tap; acceptable for play/pause. Swipes fire the moment they are recognised.
- Any gesture while playing shows the HUD for 6 s.
- **More › Sight › Reverse swipe** flips forward/back, because the system's "natural mode" flips slide semantics [GUIDE Part IV §9].
- The right pad is not filtered by device name, so `adb shell input tap/swipe` works for tests [R:engine_reuse §3.4]; timing-sensitive gestures (double, triple) are tested through `--es gesture` (§8.2) because each `adb shell input` call starts its own process.

### 1.4 Screens

**Title card** (on launch; drawn over the Hall view at Stage level at 10 fps): `HAMMERKLAVIER` in gilt (22 px), `Konzertzimmer · Sanssouci 1747` (18 px), then one of: `Voicing the grand… 42%` → `Tap to enter the Konzertzimmer · voicing 42%` (or `Tap to continue: «title»` when a resume point exists). Voicing keeps running while the card shows. Below (14 px): `Phone: http://192.168.x.y:19112 · token K7QM4TZP` or `no Wi-Fi: use push_scores.sh`; on the speaker route `Headphones recommended for the bass`.

**The stage** (Player, Action, Hall) with the HUD (§1.8).

**Menu card:** centred in each eye, 400 × 300 px: a 22 px title, at most 7 rows at 18 px, a 14 px footer hint `⇄ move · tap choose · double-tap back`. Warm white (255,236,200) with a bloom over one thin gilt rule. **No dark box** (a box is invisible on the waveguide and only adds edges) [R:visual_design §5.4]. The stage keeps playing behind it. The highlighted row is gilt (240,190,100) with a 2 px underline.

**Transport** (double-tap while playing), in order:

| # | Row | Tap does |
|---|---|---|
| 1 | `❚❚ Pause` / `▶ Play` | toggles |
| 2 | `Next: «title»` | next movement in the playlist |
| 3 | `Previous` | restarts the movement if more than 3 s in, otherwise the previous one |
| 4 | `Position 3:12 / 9:53 ›` | Adjust: fwd/back ±10 s, up/down ±60 s, tap seeks, double-tap cancels |
| 5 | `Instrument: Grand ›` | sub-list Grand / Upright / Harpsichord · Bach era, the piece's default marked `(piece default)`, each with `voicing 42%` while not complete. Choosing one switches at the current position |
| 6 | `Library ›` | shelves (§1.5) |
| 7 | `More ›` | the sub-menus below |

**More ›**
- **Sound ›** `Tempo 100% ›` (Adjust, 50–150 % in 5 % steps; pitch unchanged) · `Temperament ›` (the 8 of §3.18; per instrument) · `Pitch ›` A440 / A430 / A415 / A392 (per instrument) · `Registration ›` 8′+4′ / 8′ / 4′ (harpsichord only) · `Resonance ›` Off / Natural / Rich · `Reverb ›` Dry / Room / Resonant · `Speaker bass ›` Auto / On / Off.
- **Sight ›** `Room ›` Auto / Salon / Stage / Instrument / Passthrough · `Palette ›` Sanssouci 1747 / Stadtschloss 1747 · `Upright finish ›` Walnut / Mahogany / Ebony · `Stereo depth ›` 50 / 75 / 100 / 125 % · `Look-around` On/Off · `Edge overlay` Auto/On/Off · `Reverse swipe` Off/On · `Antialiasing` On/Off (applies at the next launch).
- **Calibrate ›** `Display floor` (card) · `A/V sync` (card, for the current output: `speaker`, `wired` or the Bluetooth device's name).
- `Import from phone` (panel) · `Credits` (panel) · `About` (panel).

**Cards.**
- *Display floor* [R:visual_design §3.6]: 16 warm swatches at levels 8, 12 … 68 on transparency plus a neutral row; swipe to the dimmest swatch you can see; tap stores `presenceFloor` (default 22). The stage is hidden while the card shows (`RenderControl.setStageHidden`).
- *A/V sync:* plays `SYNC_CLICK` (A4 at v118, one click every 600 ms plus a pseudo-random 0–33 ms) through the normal Performance path and flashes a 60 px disc (255,244,214) at screen centre in the frame whose exposure window contains each hammer contact; fwd/back or up/down shifts the display lead by ±5 ms (range 0–400 ms); tap stores `displayLeadMs` **for the current route class** (speaker, wired/USB, or that Bluetooth device's address; defaults 30 ms speaker and wired, the measured offset for Bluetooth, §8.6). The first time a new Bluetooth device plays, the status line offers `New headphones: More › Calibrate › A/V sync`.

**Panels** (text pagers, 18 px, 7 lines per page): Credits (the §6.11 text), About (version, branch and commit, the §1.1 About line, source links), Import (the companion URL, the 8-character token, a `Rotate token` row, the adb command `tools/device/push_scores.sh`, the last import result, the imported count).

### 1.5 Library shelves and playlists

**Library › shelves** (15, in this order): Start here (13) · Imported (n) *(shown only when not empty)* · Recently played *(the last 15 movements, newest first; shown only when not empty)* · Bach · the young virtuoso · Bach · teaching the keyboard · Bach · Well-Tempered Clavier · Bach · suites, partitas & variations · Handel · Scarlatti · The French clavecinists · Galant & Empfindsamkeit · Haydn · Mozart · Clementi · Beethoven.

- A shelf lists its works, one row each: `Beethoven · Sonata op. 106 "Hammerklavier"   35:35  G` (trailing G, U or H = default instrument).
- A single-movement work plays on tap. A multi-movement work opens a movement list headed by `Play all ▶`.
- **Playlist:** playing a movement queues that movement, the rest of its work, then the remaining works **of the shelf it was chosen from** (`UiAction.Play` carries the shelf id). Start here plays straight through its 13 tracks; Recently played plays its list in order. At the end of a movement the next follows after the natural tails plus 1.5 s.
- **Remembered:** the last movement and position (saved every 5 s), the instrument last chosen for each work, and the recently played list.

### 1.6 Companion upload page (phone browser)

NanoHTTPD 2.3.1 on **port 19112**, plain HTTP (TLS handshakes stutter audio on this CPU [GUIDE]). An **8-character token** per install (alphabet `23456789ABCDEFGHJKMNPQRSTUVWXYZ`), shown on the Import panel and the title card, rotated from the Import panel. **Every `/api/*` route needs the token** (`x-hk-token` header, or `?token=`); only the page itself is open. The page is `assets/companion.html` (vanilla JS, no CDN, no `$` or backticks in Kotlin strings), served **unchanged** (no token inside) with `Cache-Control: no-store`; on first use it asks for the token and keeps it in `localStorage`, and asks again after a 403. Ten wrong tokens from one address within 60 s → 429 for 60 s. Structure from WanderQuest's server; site-local-first IPv4 from TapVibe [R:engine_reuse §5].

| Route | Method | Body / query | Effect / response |
|---|---|---|---|
| `/`, `/index.html` | GET | – | the page |
| `/api/library` | GET | token | shelves, works, movements (bundled + imported) as UTF-8 JSON |
| `/api/state` | GET | token | `{movementId,title,composer,instrument,view,framing,positionSec,durationSec,playing,quality,underruns,batteryC}` (display time, pre-roll excluded) |
| `/api/upload` | POST | raw `application/octet-stream` body, header `x-hk-name: encodeURIComponent(relativeName)`; token | one file per request: `.mid .midi .kar .rmi .zip`. `content-length` is checked **before** the body is read: ≤ 4 MiB per MIDI, ≤ 20 MiB per zip (≤ 200 entries), else 413. The handler reads exactly `content-length` bytes into `cacheDir/upload-<n>.tmp` itself (NanoHTTPD's `parseBody` is not used, so names are UTF-8, not US-ASCII). A relative name `Op 109/1.mid` makes the folder one work. Every MIDI must pass `ScoreCompiler.inspect`. Returns `{saved:[{id,title,durationSec,notes}],rejected:[{name,reason}]}` |
| `/api/delete` | POST | `?id=`; token | deletes an imported movement |
| `/api/play` | POST | `?id=&instrument=`; token | plays a movement |
| `/api/transport` | POST | `?cmd=toggle\|next\|prev\|seek&ms=` (display ms); token | remote control; `CompanionServer` converts `ms` to song µs (+ pre-roll) |
| `/api/instrument` | POST | `?id=grand\|upright\|harpsichord`; token | switch instrument |
| `/api/view` | POST | `?id=player\|action\|hall&framing=0\|1`; token | switch view |

- Unknown path → 404; missing or wrong token → 403; oversize → 413; token flooding → 429; a handler exception → JSON 500 (`runCatching` around routing). JSON bodies are read as `content-length` bytes and decoded as UTF-8 by hand (Händel, Für Elise) [R:engine_reuse §5.3]. Every command is posted to the main thread.
- **The page:** drag and drop of files or whole folders (`webkitGetAsEntry`), a file picker, one raw POST per file (relative path in `x-hk-name`) with a progress bar and its result line (title and duration, or the rejection reason), the library list with a filter box, Play and Delete buttons (Delete only on imports), a transport strip (play/pause, previous, next, instrument, view), and now-playing polled every 2 s.
- **Lifecycle:** a process singleton owned by `HammerklavierApp`, started with the engine (`start(5000, true)`, daemon) and stopped when the activity finishes. A `ConnectivityManager.NetworkCallback` refreshes `url()` and the HUD when Wi-Fi comes or goes. **Guarantee:** the remote answers while music plays or the display is on; with the display off and nothing playing the CPU may suspend and the page stops answering until the glasses wake.
- **Privacy:** imported files never leave the glasses; the page lists and plays them only for a client holding the token.

### 1.7 Importing with adb (`tools/device/push_scores.sh`)

- **Drop folder:** `/sdcard/Android/data/com.tropicalstream.hammerklavier/files/Scores/` (`getExternalFilesDir("Scores")`), created by the app on first launch. On this device `Android/data` is a direct f2fs bind mount and adb-pushed files keep the shell's ownership (`2000:1078`) and the host's mode [M:rev], so **pushed files are read-only input**: the app copies each readable file byte-for-byte into its own store `filesDir/imports/<sha1>.mid` and indexes the copy; it never moves, renames or deletes anything inside `Scores/`.
- **The only documented way to push** is the script, never a bare `adb push`:
  ```bash
  tools/device/push_scores.sh "Op 109/"          # or any files/folders
  # 1. if the app's files/ dir is missing or not owned by the app, launch the app once and wait (a push before the
  #    first launch would create files/ owned by shell and lock the app out of its own external directory)
  # 2. adb -s $S push "$@" /sdcard/Android/data/$PKG/files/Scores/
  # 3. adb -s $S shell chmod -R a+rwX /sdcard/Android/data/$PKG/files/Scores   (shell owns what it pushed, so it may chmod;
  #    the app-owned 2770 parent still keeps other apps out)
  # 4. adb -s $S shell am broadcast -a com.tropicalstream.hammerklavier.CONTROL --ez rescan true
  ```
- **Rescans:** on resume, when the Library opens, and on the CONTROL `rescan` broadcast. No `FileObserver`.
- **Loose file** → one work with one movement. **Subfolder** → one work, its files as movements in filename order (so `push_scores.sh "Op 109/"` makes a three-movement sonata). **Zip** → extracted into the app's store (limits as §1.6, `../` rejected).
- **Rejections** are recorded in `filesDir/imports/index.json` by (path, size, mtime) with the reason, so the same bad file is not inspected again until it changes; the status line and the Import panel show the reason. An unreadable file shows `Permission denied: run push_scores.sh`. On launch the app checks `Os.stat(scoresDir).st_uid == Process.myUid()` and shows `Import folder owned by adb: run push_scores.sh` if not.
- **Titles:** MIDI track name (first `FF 03` in track 0), else the copyright/text meta, else the file name. Composer: the folder name if it looks like one, else "Imported".
- **Default instrument:** harpsichord if the file has no CC64, its range fits 29–89 with ≤ 2 notes in 1000 folded, **and** its folder or file name contains `bach`, `scarlatti`, `handel`, `couperin` or `rameau`; otherwise grand.

### 1.8 On-screen status (HUD)

All 2D text is Android Views inside the single child of `BinocularSbsLayout`, so it is drawn in both eyes [R:engine_reuse §2.8]. Never Toasts or dialogs (one eye only). Text ≥ 14 px, warm white (255,236,200), accent (240,190,100), soft bloom shadow, no boxes. Views use `LAYER_TYPE_HARDWARE` and redraw at most 1 Hz except in response to input (fast overlay refresh has starved audio on this device [GUIDE gotcha 7]).

| Place (per 640 × 480 eye; safe area 600 × 440) | Content | Updates |
|---|---|---|
| Top-left, 18 px | `Beethoven · Sonata op. 106 "Hammerklavier"` | on movement change |
| Top-left line 2, 14 px | `I. Allegro · bar 112` | 1 Hz |
| Top-right, 14 px | `Grand · A440 Equal` / `Harpsichord · A415 Werckmeister III · 8′+4′` | on change |
| Bottom-left, 14 px | `3:12 / 9:53` over a 1 px gilt progress rule 200 px long (time excludes the 400 ms pre-roll) | 1 Hz |
| Bottom-centre, 14 px | source credit, e.g. `Performance: Bernd Krueger · piano-midi.de · CC BY-SA 3.0 DE`, for the first 8 s of every movement (a licence obligation) | per movement |
| Top-centre, 18 px, 1.5 s | view toast: `Player`, `Player · follow`, `Action · hammers`, `Action · overhead`, `Hall · Konzertzimmer`, `Hall · life-size` | on view change |
| Bottom-right, 14 px pills (**top-right, under the tuning line, in Player follow**, where the pedal inset takes the bottom right) | `voicing upright 42%` · `paused` · `▲ warm` (Q ≥ 2) · `stand-in tones` · `3 notes folded` · `finger-pedalled` · `7 channels merged` | on change |
| Centre, above the progress rule, 14 px **status line** (lowest priority number wins, 8 s) | producers emit `StatusCode`s with arguments (§2.3); WP10's `UiText` maps each to its text and priority: 1 `Last session ended unexpectedly: <first line of crash.txt>` · 2 import failures (`Couldn't read "x.mid": truncated track`, `Permission denied: run push_scores.sh`) · 3 fallbacks (`Stand-in tones: sample bank not installed` / `Stand-in tones: decoder unavailable` / `Reduced grand: low storage` / `Audio output unavailable` / `Audio stopped: <reason>`) · 4 `Resting the display to cool · music continues` (Q3, persistent) · 5 import results `Imported "bwv1006.mid" · 2,311 notes · 3:41` and `New headphones: More › Calibrate › A/V sync` | on event |
| Centre-bottom, first 3 sessions | hint `⇄ views · tap pause · double-tap menu` | – |
| Debug overlay (`--ez debug true`) | `fps draws tris late · voices/peak/cap noise combs · blk p50/p99/max µs f̄GHz · ur headroom · slow majflt · clk ts/est lat ms tsRej miss · eMiss · batt °C · Q · lead ms route` | 1 Hz |

The HUD **hides itself after 6 s without input while playing**; toasts, credits and the status line still appear.

### 1.9 The first five minutes

1. **Launch.** Title card over the dim candlelit room. First run: `Voicing the grand… n%`: the grand decodes most-used layer first (§3.3). When the first layer, the releases and the pedals are ready (≈ 5 s [E], ≤ 8 s by T-DEC), the card reads `Tap to enter the Konzertzimmer · voicing n%`; voicing continues for as long as the card stays up. Notes whose layer is not yet decoded play the nearest decoded layer on the continuous level curve.
2. **Tap.** The gaze recentres, the Player view opens, and Start here begins: Bach's Prelude and Fugue in C, BWV 846 (Krueger, grand). The hint line appears. Voicing stops while music plays (§3.3).
3. **Core gestures only:** swipe to the hammers, then the room; double-tap opens Transport, whose rows name every other function.
4. The rest of the grand voices whenever nothing plays (a pause, the title card, the gaps between movements, the display asleep while paused); the upright and harpsichord voice after the grand when idle.

No calibration card is forced; the presence-floor default (22) and the per-route lead defaults are used until the user runs More › Calibrate.

### 1.10 Lifecycle behaviour

The engine (AudioOutput, KitManager, LibraryService, CompanionServer, SessionController, ThermalGovernor, the CONTROL receiver, MediaButtons) is a set of **process singletons owned by `HammerklavierApp`**; `MainActivity` only attaches views and input. The manifest declares `android:configChanges="density|orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden|keyboard|navigation|uiMode|fontScale|locale|layoutDirection|colorMode|touchscreen"`, so no configuration change recreates the activity.

| Event | Behaviour |
|---|---|
| `onPause` (sleep button, display off, while worn) | GL pauses; **audio keeps playing** to the end of the current playlist item [GUIDE gotcha 5]. Resume point saved. The governor, the CONTROL receiver and the soak recorder stay alive (they belong to the engine) |
| `onStop` with the display asleep (`!PowerManager.isInteractive`) | if playing: keep playing to the end of the current playlist item, then pause; if not playing: park the engine (§3.1) |
| `onStop` with the display on (the user left: system home, another app, the Back key at the root) | fade out over 300 ms, pause, save the resume point; the engine parks after 10 s idle. Nothing plays without a UI |
| `onResume` | `audio.start()` (idempotent; if the engine was stopped it re-sends the bank, key map and Performance and restores the paused position), restart Choreographer pacing (`removeFrameCallback` before `postFrameCallback`), rescan imports; the picture evaluates at the current song time, nothing to catch up |
| `onDestroy` with `isFinishing` | stop audio, the companion server, the MediaSession, the governor and the receiver |
| Configuration change | handled in place (`configChanges` above); never a second engine |
| Audio focus | `CAN_DUCK` → duck to 0.3, keep playing; transient loss → pause; permanent loss → pause and park |
| Route change (the track's routed device changes: speaker, wired/USB, Bluetooth) | `AudioTrack.addOnRoutingChangedListener` + `getRoutedDevice()` → route class → speaker voicing (§3.13), the route's display lead and latency allowance; a Bluetooth address never seen before → status offer to calibrate |
| `AudioTrack.write` < 0 (`ERROR_DEAD_OBJECT`, e.g. audioserver restart or a Bluetooth switch) or `getTimestamp` failing 16 times in a row while playing | rebuild the track (at most 3 tries within 10 s), reset the clock, continue at the same song position; after 3 failures `Audio stopped: output lost` |
| Process killed (low RAM) | Next launch offers `Tap to continue`; an orphaned engine is impossible (the audio thread dies with the process, and one process holds one engine) |
| Uncaught exception | `files/crash.txt` written by `HammerklavierApp`'s handler; first line shown in the status line on next launch |
| Instrument switch | audio: 30 ms fade, new bank and Performance, resume at the engine's own song position; picture: the new meshes are swapped under the next dip (instantly after a display rest). Neither waits for the other |
| GL context lost | re-upload every mesh and texture from the resident arrays under a dip (§5.1) |

---

## 2. Architecture

### 2.1 Threads and the rules between them

| Thread (name ≤ 15 chars) | Priority | Owns | Wakes |
|---|---|---|---|
| **main** | default | `MainActivity`, `AppController` (thin adapter), `SessionController`, gesture engine, overlays, `Settings`, `ThermalGovernor`, CONTROL receiver, `MediaButtons`, `SoakRecorder`, `GazeCamera` sensor callbacks (which write `HeadPose`), companion commands after `post` | input, a 1 Hz HUD tick, broadcasts, sensors (`SENSOR_DELAY_GAME`) |
| **GLThread** | default | `StereoRenderer`, VBOs, textures, `VisualClock`, `MechanicsEvaluator`, `CameraDirector`, `GlyphBoard` | Choreographer: every 2nd vsync (30 fps), 3rd (20 fps at Q2), 6th (10 fps idle); paused in display rest and with the display off |
| **HKAudio** | `Thread.MAX_PRIORITY` + `THREAD_PRIORITY_URGENT_AUDIO` | `AudioTrack`, `EngineCore` (sequencer, voices, DSP), clock and energy publishing, `HeadPose` reads | blocks in `write`; ≈ 50 wakes/s, ≈ 4 blocks per wake; parked on a condition after 10 s idle |
| **HKPrefetch** | `THREAD_PRIORITY_BACKGROUND` | reads cache pages ahead of voices and upcoming notes with positional `FileChannel.read` (never through the mapping) | scheduled by song time (§3.4) while playing; parked otherwise |
| **HKLoader** | `THREAD_PRIORITY_BACKGROUND`, single-thread executor created once by `HammerklavierApp` and passed to every constructor that needs it | SMF parsing, Performance building, catalogue and import scans, KeyMap and DSP table preparation, mesh building, texture recipes | on demand |
| **HKVoicer** | `THREAD_PRIORITY_BACKGROUND` | Opus → PCM cache decoding (§3.3); never while music plays | on demand |
| **NanoHTTPD** | default, daemon | HTTP I/O; results posted to main | on request |

**Rules:**
1. After warm-up, **HKAudio and GLThread allocate nothing**: no boxing, no capturing lambdas per call, no string formatting, no iterators, no `listOf`, no per-call `Runnable`s (posts to main use preallocated `Runnable`s whose payloads are `@Volatile` fields). Logging from these threads is limited to preformatted counters read by main. JVM tests enforce this with escape analysis disabled (§7.1), and the device checks it (T2.10, WP6).
2. Only six kinds of traffic cross threads: (a) immutable objects (`Performance`, `LoadedBank` (except its informational `readyMask`), `KeyMap`, prepared DSP tables, `QualityProfile`, `RoomDesign`, `MixSettings`) published by `@Volatile` reference or through the command ring; (b) the SPSC **`CommandRing`** main → audio; (c) the **`AudioClock`** block-record seqlock ring audio → any reader; (d) the **`EnergyRing`** seqlock ring audio → GL; (e) `@Volatile` stats scalars and the `VoiceCursorBoard` (audio → prefetch); (f) the **`HeadPose`** atomic (main → audio and GL). **Every seqlocked payload lives in `AtomicLongArray` / `AtomicIntegerArray` (floats as raw int bits) and is touched only through `get`/`set`**: each access is a synchronisation action, so the protocol is data-race-free under the Java memory model on ART/ARMv8, where plain fields between two volatile version accesses are not ordered (`ldar` orders only later accesses, `stlr` only earlier ones) and `VarHandle` fences are unavailable (API 33+; the glasses are SDK 32).
3. The only blocking call on HKAudio is `AudioTrack.write` (plus the park condition when idle). File I/O on it is limited to minor page faults in the mapped cache; HKPrefetch keeps major faults rare, and `majflt` is measured.
4. Objects are built on HKLoader or main and handed over whole. Nothing is shared and then mutated.
5. All transcendental maths (`pow`, `exp`, `log`, `sin`, `atan2`) happens at preparation time (tuning, bank, room, quality changes) on HKLoader or main, never per sample or per block on HKAudio or GLThread; `sqrt` (a single instruction) is allowed. The run-time tables are named in §3.6, §3.7, §3.9, §3.11 and §3.12: comb gain per (key, D step), spectral coefficient per (key, D step), env byte → linear gain and power (256), `vel^0.7` (128), `exp(−age/3 s)` over 0–6 s (64), damping multipliers per (key, D step), a 1024-entry sine table for pans and LFOs, dB ↔ linear (256 + 256).
6. **GL rule:** a `queueEvent` runnable only sets fields of the renderer's desired state; every GL call happens inside `onDrawFrame` or `onSurfaceCreated`, where a context is current (§5.1).

### 2.2 Modules, packages and files: one responsibility, one owner

Two Gradle modules share the base package `com.tropicalstream.hammerklavier`:
- **`:core`** (Kotlin JVM library, no Android dependency; `org.json:json:20180813` `compileOnly` because Android provides it at run time): every **pure** package, at `core/src/main/java/com/tropicalstream/hammerklavier/…`. Pure code may import only `kotlin.*`, `java.*` (not `java.awt`) and `org.json.*`; the compiler enforces the rest, and `tools/check_purity.sh` also greps `core/src/main` for fully qualified `android.`/`androidx.`/`java.awt` references. Tests at `core/src/test/java/…`, resources at `core/src/test/resources/wp<N>/` (each WP owns its folder), shared test utilities at `core/src/testFixtures/java/…` (`java-test-fixtures`; `java.awt` allowed there). Pure WPs build and test with `./gradlew :core:test` only; no Android plugin runs.
- **`:app`** (Android application): everything that touches the platform, at `app/src/main/java/com/tropicalstream/hammerklavier/…`. `:app` depends on `:core`.

| Path (under the module's base package) | Module | Responsibility | WP |
|---|---|---|---|
| `HammerklavierApp.kt` | app | Application; uncaught-exception handler → `files/crash.txt`; creates `Settings`, the executors (HKLoader, HKVoicer) and the **process singletons**: AudioOutput, KitManager, LibraryService, CompanionServer, SessionController, ThermalGovernor, DebugControl, MediaButtons; engine lifetime (governor and receiver registered when AudioOutput or KitManager start, unregistered when both stop) | 0 |
| `MainActivity.kt` | app | attaches the GL view, `BinocularSbsLayout` and overlay host to the singletons; feeds `dispatchKeyEvent` (incl. `KEYCODE_BACK`) / `dispatchTouchEvent` / `dispatchGenericMotionEvent` to the gesture engine; §1.10 rules; `FLAG_KEEP_SCREEN_ON`, immersive flags, `setVolumeControlStream(STREAM_MUSIC)`, per-window brightness cap | 0 |
| `AppController.kt` | app | thin Android adapter: lifecycle, receivers, route and focus events, sensors → `HeadPose`, `Settings` persistence → `SessionController` | 0 |
| `Wiring.kt` | app | the one place that constructs real or stub components; swapped per `docs/wiring/WPn.md` at each merge | 0 |
| `contract/*.kt` | core | every cross-package type, interface and shared primitive (§2.3) | 0 |
| `contract/stub/*.kt` | core | working stubs, each specified in §2.3: `FakeClock`, `NullAudio`, `SineBank`, `SineCore`, `StubKits`, `KeyMapFixtures`, `PerfFixtures`, `SyntheticSpecs`, `PassThroughDsp`, `StubRoomDesigner`, `FixedRoom`, `StubVenue`, `StubScoreCompiler`, `StubMechanics`, `StubScenes`, `StubLibrary`, `StubUi`, `MemSettings` | 0 |
| `contract/android/*.kt` | app | `GlHost`, `OverlayHost` (need `android.view.View`); `CanvasPainter` (implements `Painter2D` on `android.graphics`) | 0 |
| `contract/stub/android/*.kt` | app | `StubGlHost` (clears black, logs GL info, draws a gilt test frame per eye), `StubOverlay` (title text in both eyes) | 0 |
| `testutil/AwtPainter.kt`, `testutil/AllocProbe.kt` | core (testFixtures) | `Painter2D` on `java.awt` for PNG review of texture recipes; per-thread allocation assertions | 0 |
| `system/ThermalPolicy.kt` | core | battery tenths + thermal status → level with hysteresis (pure, tested) | 0 |
| `system/Settings.kt` | app | typed SharedPreferences implementing `SettingsStore`, with change listeners | 0 |
| `system/ThermalGovernor.kt` | app | sticky `ACTION_BATTERY_CHANGED` + `PowerManager` listener → `ThermalPolicy`; fake temperature for tests; lifetime = the engine's | 0 |
| `system/DebugControl.kt` | app | the CONTROL receiver (§8.2), registered with permission `android.permission.DUMP` (only shell holds it) for the engine's lifetime | 0 |
| `system/PerfProbe.kt` | app | frame-hitch detector (> 120 ms), `majflt` of the HKAudio task from `/proc/self/task/<tid>/stat` field 12, `scaling_cur_freq` and `time_in_state` deltas, the 10 s `HKPerf` line | 0 |
| `system/SelfTest.kt` | app | `--ez selftest true`: PASS/FAIL lines (§8.3), including the on-device torn-read test | 0 |
| `system/SoakRecorder.kt` | app | `--ez soak true`: every 10 s one CSV row to `getExternalFilesDir(null)/soak.csv` (mode 0644), and the named soak plans of §8.5 run by its own timer (playlist, view rotation, storm loop), so an unplugged soak needs no adb | 0 |
| `system/MediaButtons.kt` | app | framework `android.media.session.MediaSession`, active while a movement is loaded; play/pause, next, previous → `UiAction`s; title metadata | 0 |
| `platform/TrackpadGestureEngine.kt` | app | copy of WanderQuest's engine + `cyttsp6` key-path filter + touch/key dedup of one firm click | 0 |
| `platform/BinocularSbsLayout.kt` | app | copy of MathCosmos's (95 lines) | 0 |
| `platform/DeviceInfo.kt` | app | RayNeo identity by manufacturer/brand/product, free space, `ActivityManager.MemoryInfo`, `Settings.Global.BOOT_COUNT` | 0 |
| `session/SessionController.kt` | core | the orchestration of §2.6: playback, playlists (from the chosen shelf), generation counter, instrument-switch sequence, per-work instrument memory, resume points, recently played, view → listener → `RoomDesign`, thermal → quality, sync test, seek-unit conversion; implements `CompanionCommands` | 12 |
| `session/StatusBoard.kt`, `session/FactsAssembler.kt`, `session/ListenerRooms.kt` | core | active `StatusItem`s and expiry; `UiFacts` assembly; listener poses per (instrument, view, framing) | 12 |
| `midi/SmfModel.kt` | core | `RawSmf`, `RawTrack` (parallel primitive arrays), `SmfLimits`, `SmfError`, `SmfResult` | 1 |
| `midi/SmfParser.kt` | core | bytes → `SmfResult`; tolerant, bounded, never throws | 1 |
| `midi/TempoMap.kt` | core | tick ↔ µs (PPQ and SMPTE), merged from all tracks; bar starts | 1 |
| `midi/ChannelMerge.kt` | core | k-way merge of all tracks and channels, tie order, drum drop | 1 |
| `midi/NotePairing.kt` | core | FIFO pairing per key, re-strike serialisation, hanging notes | 1 |
| `midi/PedalShaper.kt` | core | switch/continuous detection, lead-shifted slewed curves, dips, sostenuto edges, pedal-noise events | 1 |
| `midi/InstrumentAdapter.kt` | core | compass folding, harpsichord legato hold, per-instrument pedal policy | 1 |
| `midi/VoiceDemand.kt` | core | uncapped voice-demand simulation → `PerfInfo.voiceDemandP99/Max` (§3.14) | 1 |
| `midi/PerformanceBuilder.kt` | core | orchestrates the above into a `Performance` (§4.3) | 1 |
| `midi/SyntheticScores.kt` | core | the §4.5 test performances, compiled from `SyntheticSpecs` through the real builder | 1 |
| `midi/ScoreCompilerImpl.kt` | core | implements `ScoreCompiler` | 1 |
| `engine/EngineCore.kt` | core | implements `EngineCoreApi`: the per-block render graph (§3.15) | 2 |
| `engine/Sequencer.kt`, `engine/PendingEvents.kt` | core | event cursor, 1024-frame lookahead, sample-accurate scheduling, queued state events, pause rewind (§2.5, §3.15) | 2 |
| `engine/KeyState.kt` | core | per-key held/latched state, damper landings in output frames, damping level D(k), comb gates (0..1) and soft-feed flags | 2 |
| `engine/Voice.kt` | core | one sample playback: window staging, Hermite/linear loop, gain ramp, spectral low-pass, self-row accumulation | 2 |
| `engine/VoicePool.kt` | core | allocation, steal-ahead, kill slots, re-strike fades, the separate noise pool, per-key energy (mean-square) and self rows | 2 |
| `engine/StealPolicy.kt` | core | victim choice (pure function) | 2 |
| `engine/DamperModel.kt`, `engine/DecayTables.kt` | core | damping multipliers, spectral-damping targets, per-block tables (prepared off-thread) | 2 |
| `engine/EngineBench.kt` | core | the once-per-APK 2 s bench and `--ez bench true`: ns per voice-frame, comb-frame and stage, normalised to 2.0 GHz; sets the Q0 voice cap | 2 |
| `dsp/Biquad.kt`, `dsp/OnePole.kt`, `dsp/DspTables.kt` | core | RBJ biquads, one-poles, dB/coefficient tables, the SEND values | 3 |
| `dsp/ResonanceBank.kt` | core | 88 comb string resonators (4-way kernel, packed delay lines, dispersion allpasses); implements `ResonanceProcessor` | 3 |
| `dsp/RoomAcoustics.kt` | core | Sabine T60 per band, image sources, DRR, embedded-room compensation; implements `RoomDesigner` | 3 |
| `dsp/EarlyReflections.kt`, `dsp/FdnReverb.kt`, `dsp/DirectPath.kt` | core | the room chain parts (two tap sets crossfaded on a listener change) | 3 |
| `dsp/RoomChain.kt` | core | implements `RoomProcessor` (direct + ER + FDN) | 3 |
| `dsp/SoftBus.kt` | core | una corda / upright soft bus; implements `SoftBusProcessor` | 3 |
| `dsp/SpeakerEnhancer.kt`, `dsp/Limiter.kt`, `dsp/MasterChain.kt` | core | route voicing, virtual bass, look-ahead limiter, Padé clip; `MasterChain` implements `MasterProcessor` | 3 |
| `dsp/DspFactory.kt` | core | `fun create(sampleRate: Int): DspSet` | 3 |
| `kit/KitIndex.kt`, `kit/KitMapCodec.kt` | core | `map.json` (+ `env.bin`) → `KitIndex`, validated against the normative field table (§6.6) | 4 |
| `kit/KeyMapBuilder.kt` | core | (index, tuning, readyMask) → `KeyMap` (§3.5) | 4 |
| `kit/PcmCacheFormat.kt` | core | cache header, ready mask + per-unit CRC32 layout and checks | 4 |
| `kit/SampleStore.kt` | core | `FileChannel.map` read-only for HKAudio reads; positional `FileChannel.read` for prefetch (java.nio only) | 4 |
| `kit/MappedBank.kt` | core | implements `LoadedBank` over a `SampleStore` | 4 |
| `kit/SynthBank.kt` | core | production in-code additive "stand-in" bank (decoder-missing fallback) | 4 |
| `kit/DecodePlan.kt` | core | unit order, resume state, CRC verification plan, storage checks | 4 |
| `audio/AudioOutput.kt`, `audio/TrackSupervisor.kt` | app | implements `AudioControl`; owns the AudioTrack and the HKAudio loop; publishes clock and energy; parks when idle; rebuilds the track on `DEAD_OBJECT`; headroom-driven self-protection | 4 |
| `audio/RouteMonitor.kt`, `audio/AudioFocusGate.kt`, `audio/LatencyTuner.kt` | app | routed device → `RouteInfo` (routing listener); focus; buffer growth on the LOW_LATENCY fallback | 4 |
| `audio/Prefetcher.kt` | app | HKPrefetch (§3.4) | 4 |
| `audio/KitDecoder.kt`, `audio/DecoderProbe.kt`, `audio/VoicingScheduler.kt`, `audio/KitManager.kt` | app | Opus unit streams → PCM (MediaExtractor/MediaCodec, one codec reused), decoder offset probe, voicing policy, implements `KitService` | 4 |
| `audio/PlaybackService.kt` | app | `mediaPlayback` foreground service, **built but disabled** (`HK.USE_FG_SERVICE = false`); enabled only if the display-off T-UND/T-PF fail (§8.4) | 4 |
| `mech/Touch.kt` | core | Goebl/Askenfelt timing fits as 128-entry tables | 5 |
| `mech/KeyCursor.kt` | core | per-key governing-note cursors (step forward and back; re-seed on `VisTime.reseed`) | 5 |
| `mech/GrandAction.kt`, `mech/UprightAction.kt`, `mech/HarpsichordAction.kt` | core | per-key pose formulas (§5.7) | 5 |
| `mech/PedalPose.kt`, `mech/StringVisual.kt`, `mech/ExposureSampler.kt`, `mech/FocusTracker.kt` | core | pedals and shifts; string amplitude from energy; exposure of contacts; focus/centroid keys | 5 |
| `mech/MechanicsEvaluatorImpl.kt` | core | implements `MechanicsEvaluator` | 5 |
| `mesh/MeshBuilder.kt` | core | the shared mesh builder (conventions frozen in `contract/Scene.kt`): box, quad, extrusion, lathe, Catmull-Rom sweep, ribbon, spindle, raw `vertex`/`tri`, skin slot + one-hot lane, split above 65,535 vertices. WP0 ships box/quad/vertex/tri on day 0; WP7 completes it as its first merge (day 2) | 7 |
| `testutil/MeshRaster.kt` | core (testFixtures) | rasterises a `BakedMesh` to a PNG (java.awt + ImageIO) for geometry review before integration | 7 |
| `geom/StereoRig.kt`, `geom/CameraDirector.kt`, `geom/Springs.kt`, `geom/Mat4.kt` | core | off-axis frusta, view state + dip + springs, matrix helpers | 6 |
| `render/HkGlView.kt` | app | `GLSurfaceView` implementing `GlHost`; EGL chooser (MSAA fixed per session); Choreographer pacing | 6 |
| `render/StereoRenderer.kt` | app | `onDrawFrame`: reconcile desired scene → clock → `VisualClock` → mechanics → camera → uniforms once → two eyes; GL generation counter | 6 |
| `render/SceneAssembler.kt`, `render/UniformPacker.kt`, `render/SpriteBatch.kt`, `render/TextureUploader.kt`, `render/DipFader.kt`, `render/PedalInset.kt`, `render/SyncFlash.kt` | app | `BakedMesh` → merged VBO draw items and the per-view draw list; pose → vec4 lanes; dynamic sprites; recipes painted with `CanvasPainter` → textures; fade quad; inset; sync disc | 6 |
| `render/gl/GlKit.kt`, `render/gl/Programs.kt`, `render/gl/Shaders.kt` | app | `makeVbo`, `DynMesh`, compile helpers, `FRAG_PRECISION` (from MathCosmos); every program; GLSL ES 1.00 sources | 6 |
| `render/GazeCamera.kt`, `render/GlyphBoard.kt` | app | copied from MathCosmos with two changes: GazeCamera gains a `worldLocked` mode (no soft re-centre, ±60° yaw / +45° pitch clamps); GlyphBoard's label cap lowered to 32 and `release()` drops its cache without GL calls | 6 |
| `instrument/Keyboard.kt` | core | procedural keys for any compass (§5.4) | 7 |
| `instrument/GrandCase.kt`, `instrument/GrandActionMesh.kt`, `instrument/StringsMesh.kt` | core | grand case, lid, legs, lyre, pedals, plate, soundboard; hammers, dampers (lastDamper − 20), 13-slot action set; strings | 7 |
| `instrument/UprightModel.kt`, `instrument/HarpsichordModel.kt` | core | the other two instruments | 7 |
| `instrument/Anchors.kt` | core | camera, listener and key-position tables per instrument (§5.6) | 7 |
| `instrument/Instruments.kt` | core | `object Instruments { fun create(id, look, lastDamper): InstrumentScene }` | 7 |
| `instrument/tex/InstrumentTextures.kt` | core | `TextureRecipe`s painted through `Painter2D`: fallboard lettering, harpsichord papers, motto, soundboard flowers, rose | 7 |
| `venue/Konzertzimmer.kt` | core | the drawn `VenueGeometry` (returns the shared `contract/KonzertzimmerAcoustics` constant for acoustics), placements and room constants | 8 |
| `venue/RoomShell.kt`, `venue/Fixtures.kt` | core | shell, cove, trellis, web, cornice, dado; mirrors, windows, doors, panels, chairs, chandelier, sconces, candelabra, music stand | 8 |
| `venue/FlameLayout.kt`, `venue/FlameFieldImpl.kt` | core | ≈ 50 flames; flicker; CPU-culled mirror and floor reflections; crystal sparkle | 8 |
| `venue/LightBake.kt`, `venue/ProbeBake.kt`, `venue/VenueSceneImpl.kt` | core | per-vertex baked light; 128 × 64 reflection probe; implements `VenueScene` | 8 |
| `venue/tex/Atlas.kt` | core | `TextureRecipe`s: rocaille, trellis, cartouche and parquet atlas | 8 |
| `library/CatalogCodec.kt`, `library/ImportStore.kt`, `library/ImportRules.kt`, `library/LibraryIndex.kt`, `library/Sha1.kt` | core | `catalog.json` ↔ model; the app-owned import store (copies, dedupe, rejection records, `index.json` written atomically under one lock); defaults; merge; SHA-1 | 9 |
| `library/android/LibraryServiceImpl.kt` | app | implements `LibraryService` (AssetManager + dirs, `Os.stat` ownership check) | 9 |
| `companion/CompanionServer.kt`, `companion/NetInfo.kt` | app | NanoHTTPD routes, token, raw uploads; site-local IPv4 + `NetworkCallback` | 9 |
| `app/src/main/assets/companion.html` | app | the phone page | 9 |
| `ui/model/UiStateMachineImpl.kt`, `ui/model/MenuTree.kt`, `ui/model/HudModel.kt`, `ui/model/UiText.kt` | core | contexts and gesture routing; menu rows and cursor; HUD strings; **all user-facing text**, including the `StatusCode`/`RejectReason`/`FallbackReason`/`PerfWarning` → text and priority tables | 10 |
| `ui/OverlayViews.kt`, `ui/TitleCardView.kt`, `ui/HudView.kt`, `ui/MenuCardView.kt`, `ui/CardViews.kt`, `ui/PanelView.kt`, `ui/Styles.kt` | app | `OverlayHost` implementation and the passive views | 10 |
| `tools/pipeline/**` | (Python) | the asset pipeline (§6) | 11 |
| `app/src/main/assets/{instruments/**, midi/**, licenses/**, catalog.json, credits.txt}` | data | pipeline outputs | 11 |

**Files outside the Kotlin tree:** WP0 owns `settings.gradle.kts` (`include(":core", ":app")`), `build.gradle.kts`, `core/build.gradle.kts` (`kotlin("jvm")` applied by id without a version, which resolves from the `kotlin-gradle-plugin` 2.0.21 already on the classpath [M]; `java-test-fixtures`; tests run with `-XX:-DoEscapeAnalysis -XX:-EliminateAllocations`), `app/build.gradle.kts`, `gradle.properties` (`org.gradle.jvmargs=-Xmx1536m`, `kotlin.daemon.jvmargs=-Xmx1g`, `org.gradle.workers.max=2`), the wrapper, `AndroidManifest.xml`, `res/**` (rewrite `values/colors.xml`, which still carries MathCosmos comments; add `xml/backup_rules.xml` and `xml/data_extraction_rules.xml` from MathCosmos), `proguard-rules.pro`, `app/src/main/baseline-prof.txt`, `.gitignore`, `.gitattributes`, `docs/contracts-changelog.md`, `docs/plan-changelog.md`, `docs/contracts/map-json.md` (the §6.6 table, frozen with `contracts-v1`), `docs/wiring/`, `tools/{env.sh, wt.sh, ci.sh, check_purity.sh}`, `tools/device/*`. WP11 owns `tools/pipeline/**`, `docs/manifests/*`, `docs/DOWNLOADS.md`, `CREDITS.md`, `NOTICE`, `tools/pipeline/ledger.csv`, `core/src/test/resources/wp11/**` (fixtures used by other WPs: `map_fixture.json`, `env_fixture.bin`, `catalog_fixture.json`, `midi_facts_golden.json`, and after the download the real decoded test regions of §6.8). WP9 owns `assets/companion.html`. Dependencies: `androidx.core:core-ktx:1.15.0`, `androidx.appcompat:appcompat:1.7.0`, `org.nanohttpd:nanohttpd:2.3.1`, `androidx.profileinstaller:profileinstaller:1.4.0`; tests `junit:junit:4.13.2`, `org.json:json:20180813` (all in the Gradle cache [M]; nothing new is fetched to build). No reflection, no DI, no vendor SDK (only the `com.rayneo.mercury.app` meta-data).

### 2.3 Contracts: the Kotlin interfaces every agent codes against

WP0 writes all of `contract/**` in `:core` (plus `contract/android/**` in `:app`). **`contracts-v1`** is tagged as soon as every signature below and a trivial body for every stub compile (hours, not days); agents start from it. **`contracts-v1.1`** (within two days) adds the working primitive implementations and their tests and passes the M0 device gate; it changes bodies only. Growth after that follows the §7.1 rules (new interface members get default bodies, new constructor parameters are appended last with defaults, contract classes are always constructed with named arguments). Bodies are omitted below; where a contract file holds a concrete primitive (`AudioClock`, `VisualClock`, `HeadPose`, the rings, `PedalCurve`, `PedalMotion`, `KeyReturn`, `HarpsiTiming`, `TuningSpec`, `InstrumentProfile`, `QualityLadder`, `Placement`, `Conventions`, `KonzertzimmerAcoustics`, `Playlist`, `Pal`, `MaterialTable`), WP0 implements and tests it by `contracts-v1.1`.

**Units, frames and conventions (normative):**
- **Song time** in every API is µs of file time at rate 1 **including the 400 ms pre-roll**. Only WP10's text and the companion's JSON and query values (`positionSec`, `ms`) are display time; `SessionController` and `CompanionServer` convert.
- **Output frames** count frames accepted by `AudioTrack.write` since `AudioOutput.start()` (`framesAccepted`); a rebuilt track records `trackBaseFrame` = `framesAccepted` at its `play()`, and its timestamps are published as `trackBaseFrame + framePosition`.
- **Room frame:** x east, y up, z south, metres, origin at the floor centre. **Piano frame:** origin on the floor under the centre of the key fronts, x toward the treble, y up, z toward the player. **Yaw and azimuth** are radians about +y, 0 = room −z (north), positive toward +x (clockwise seen from above); a direction with yaw ψ is (sin ψ, 0, −cos ψ). `GazeCamera`'s head yaw uses the same sign (looking right is positive). `Conventions.yawOf(dir)` and `Conventions.forwardYaw(placement, view, framing, anchors)` are the only places this is computed.
- **Levels:** `envDb` is the normalised region's 10 ms RMS in dBFS (no `gainDb`); a voice's `LEVEL` is its linear gain excluding the raw-short scale; the 2⁻¹⁵ short-to-float scale is folded into the ramped per-sample gain only (§3.6). **Energy lanes** are linear RMS amplitude re full scale over one block (1.0 = 0 dBFS, RMS of (L² + R²)/2).

```kotlin
// ═══════════ contract/Ids.kt ═══════════
package com.tropicalstream.hammerklavier.contract

enum class InstrumentId(val key: String) {
    GRAND("grand"), UPRIGHT("upright"), HARPSICHORD("harpsichord");
    companion object { fun of(key: String): InstrumentId? = entries.firstOrNull { it.key == key } }
}
enum class ViewId { PLAYER, ACTION, HALL }                   // framing 0 = primary, 1 = second
enum class RoomLevel { SALON, STAGE, INSTRUMENT, PASSTHROUGH }
enum class OutputRoute { SPEAKER, WIRED, BLUETOOTH }         // WIRED includes USB
class RouteInfo(val route: OutputRoute, val key: String /* "speaker" | "wired" | "bt:<address>" */,
                val deviceType: Int, val name: String, val outputFlags: String /* from dumpsys-free API: "deep"|"fast"|"normal"|"" */)
enum class Gesture { TAP, DOUBLE, TRIPLE, FORWARD, BACK, UP, DOWN, SYSTEM_BACK }
enum class PedalMode { NONE, SWITCH, CONTINUOUS }
enum class ResonanceMode { OFF, NATURAL, RICH }
enum class ReverbMode { DRY, ROOM, RESONANT }
enum class SpeakerBass { AUTO, ON, OFF }
enum class Palette { SANSSOUCI_1747, STADTSCHLOSS_1747 }
enum class UprightFinish { WALNUT, MAHOGANY, EBONY }
enum class SoftKind { NONE, UNA_CORDA, HAMMER_RAIL }

// ═══════════ contract/Status.kt: producers emit codes; WP10's UiText owns every word and every priority ═══════════
enum class StatusCode { CRASH_LAST_SESSION, IMPORT_FAILED, IMPORT_PERMISSION, SCORES_DIR_FOREIGN, FALLBACK, AUDIO_UNAVAILABLE,
                        AUDIO_STOPPED, DISPLAY_REST, IMPORTED, NEW_BT_DEVICE }
enum class RejectReason { NOT_MIDI, TRUNCATED, BAD_HEADER, TOO_LARGE, TOO_MANY_EVENTS, NO_KEYBOARD_NOTES, DUPLICATE,
                          ZIP_LIMIT, ZIP_TRAVERSAL, PERMISSION_DENIED, IO_ERROR }
enum class FallbackReason { BANK_MISSING, DECODER_UNAVAILABLE, PROBE_FAILED, LOW_STORAGE }
enum class PerfWarning { HANGING_NOTES, FORMAT2_SEQUENTIAL, TRUNCATED_CHUNK, TRAILING_JUNK, RUNNING_STATUS_REPAIRED,
                         DRUMS_DROPPED, CHANNELS_MERGED, FOLDED, FINGER_PEDALLED }
class StatusItem(val code: StatusCode, val args: List<String>, val untilMs: Long)   // priority comes from UiText: lower number = higher priority

// ═══════════ contract/Constants.kt ═══════════
object HK {
    const val SR = 48_000
    const val BLOCK = 256                        // frames per render block = 5.333 ms
    const val TRACK_FRAMES = 4096                // AudioTrack buffer request = 85.3 ms
    const val PRE_ROLL_US = 400_000L             // every Performance starts with 400 ms of silence (≥ 230 ms × 1.05 × 1.5)
    const val LOOK_FRAMES = 1024                 // sequencer lookahead in OUTPUT frames (onset lead + 4′ stagger + steal-ahead)
    const val CLOCK_RECORDS = 256                // AudioClock ring: 1.37 s
    const val ENERGY_SLOTS = 256                 // EnergyRing: 1.37 s
    const val KEYS = 128
    const val LANES = 88                         // EnergyRing lane i = key 21 + i
    const val STOP_MAIN = 0; const val STOP_4FT = 1   // harpsichord: 0 = 8′, 1 = 4′; pianos use 0
    const val REG_8 = 1; const val REG_4 = 2          // registration bit mask
    const val NOISE_SLOTS = 12                   // release + pedal-noise pool, outside the voice cap
    const val VOICE_CAP_MIN = 64; const val VOICE_CAP_MAX = 128
    const val IDLE_PARK_MS = 10_000              // paused, no voices, room tail below −90 dBFS → track.pause() and park
    const val HEADROOM_MIN_FRAMES = 1536         // queued frames below this for 10 s → voice cap −8
    const val USE_FG_SERVICE = false             // §8.4: flipped only if the display-off tests fail
    const val CONTROL_ACTION = "com.tropicalstream.hammerklavier.CONTROL"
    const val COMPANION_PORT = 19112
    const val TAG_AUDIO = "HKAudio"; const val TAG_CLOCK = "HKClock"; const val TAG_RENDER = "HKRender"
    const val TAG_THERMAL = "HKThermal"; const val TAG_INPUT = "HKInput"; const val TAG_LOADER = "HKLoader"
    const val TAG_KIT = "HKKit"; const val TAG_LIB = "HKLib"; const val TAG_WEB = "HKWeb"
    const val TAG_PERF = "HKPerf"; const val TAG_SELFTEST = "HKSelfTest"; const val TAG_UI = "HKUi"; const val TAG_SOAK = "HKSoak"
}

// ═══════════ contract/Tuning.kt ═══════════
enum class Temperament(val label: String, private val cents: FloatArray) {   // cents from 12-TET, A = 0 (§3.18)
    EQUAL("Equal", floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
    WERCKMEISTER_III("Werckmeister III", floatArrayOf(11.7f, 2.0f, 3.9f, 5.9f, 2.0f, 9.8f, 0.0f, 7.8f, 3.9f, 0f, 7.8f, 3.9f)),
    KELLNER("Kellner", floatArrayOf(8.2f, -1.6f, 2.7f, 2.3f, -2.7f, 6.3f, -3.5f, 5.5f, 0.4f, 0f, 4.3f, -0.8f)),
    VALLOTTI("Vallotti", floatArrayOf(5.9f, 0.0f, 2.0f, 3.9f, -2.0f, 7.8f, -2.0f, 3.9f, 2.0f, 0f, 5.9f, -3.9f)),
    YOUNG_II("Young II", floatArrayOf(5.9f, -3.9f, 2.0f, 0.0f, -2.0f, 3.9f, -5.9f, 3.9f, -2.0f, 0f, 2.0f, -3.9f)),
    KIRNBERGER_III("Kirnberger III", floatArrayOf(10.3f, 0.5f, 3.4f, 4.4f, -3.4f, 8.3f, 0.5f, 6.8f, 2.4f, 0f, 6.4f, -1.5f)),
    LEHMAN("Lehman 2005 (one proposal)", floatArrayOf(5.9f, 3.9f, 2.0f, 3.9f, -2.0f, 7.8f, 2.0f, 3.9f, 3.9f, 0f, 3.9f, 0.0f)),
    MEANTONE_QUARTER("¼-comma meantone", floatArrayOf(10.3f, -13.7f, 3.4f, 20.5f, -3.4f, 13.7f, -10.3f, 6.8f, -17.1f, 0f, 17.1f, -6.8f));
    fun offsetCents(pitchClass: Int): Float = cents[pitchClass]              // 0 = C … 9 = A … 11 = B
}
class TuningSpec(val aHz: Float, val temperament: Temperament) {
    /** Target cents of key k relative to A440 equal temperament (pitch standard + temperament; the kit's stretch shape is added by KeyMapBuilder). */
    fun keyCents(key: Int): Float
    companion object { val A440_EQUAL: TuningSpec; val A415_WERCKMEISTER: TuningSpec }
}

// ═══════════ contract/InstrumentProfile.kt ═══════════
class InstrumentProfile(
    val id: InstrumentId, val displayName: String,
    val lowKey: Int, val highKey: Int, val lastDamper: Int,
    val keyDipMm: Float, val keyReturnMs: Float, val repeatMinMs: Float, val partialReturnMs: Float,
    val blowMm: Float, val letOffMm: Float, val checkMm: Float, val actionRatio: Float, val travelScale: Float,
    val usesSustain: Boolean, val usesSostenuto: Boolean, val softKind: SoftKind,
    val legatoHold: Boolean, val velocityGain: Boolean, val stops: Int,
    val restrikeTauUpMs: Float, val restrikeTauPedalMs: Float, val defaultTuning: TuningSpec) {
    val damperLagMs: Float           // DERIVED, real time: KeyReturn.damperLandMs(keyReturnMs) for pianos, HarpsiTiming.damperLandMs for the harpsichord
    fun stringsPerKey(key: Int): Int // grand: 1 for 21–28, 2 for 29–48, 3 for 49–108 (notes 1–8 / 9–28 / 29–88); upright 1/2/3 by the same bands; harpsichord 1 per stop. Shared by StringsMesh, KeyState and ResonanceBank
    fun withLastDamper(n: Int): InstrumentProfile                         // the bank's value is authoritative
    companion object {
        val GRAND: InstrumentProfile          // values in §3.7 table "Profiles"
        val UPRIGHT: InstrumentProfile
        val HARPSICHORD: InstrumentProfile
        fun of(id: InstrumentId): InstrumentProfile
    }
}

// ═══════════ contract/PhysicalCurves.kt (shared by audio and visuals: ONE implementation) ═══════════
object PedalMotion {
    const val DOWN_RAMP_MS = 70f; const val UP_RAMP_MS = 60f; const val SOFT_RAMP_MS = 60f   // song time (baked into the curves)
    const val LIFT_START = 0.33f                  // dampers leave the strings at 1/3 of travel (Redekop)
    const val CLEAR = 0.55f                       // dampers fully clear: sostenuto can catch every tab from here
    const val NOISE_MIN_SPACING_MS = 150f
    fun smoothstep(a: Float, b: Float, x: Float): Float
    fun damperLiftByPedal(p: Float): Float        // ((p - 0.33) / 0.67).coerceIn(0, 1)
    fun pedalDamping(p: Float): Float             // 1 - smoothstep(0.33, 0.55, p); 1 = fully damping
    fun keyDamperLift(hammerFrac: Float): Float   // ((h - 0.5) / 0.5).coerceIn(0, 1)
}
object KeyReturn {                                // grand and upright key release
    fun dip(dHeld: Float, x: Float): Float        // dHeld · (1 − smoothstep(0, 1, x)), x = (t − off) / keyReturn; starts and ends at rest speed
    const val LAND_X = 0.5247f                    // 1.08 · dip = 0.5 at x = 0.5247 (dHeld = 1)
    fun damperLandMs(keyReturnMs: Float): Float   // LAND_X · keyReturnMs: grand 18.4 ms, upright 26.2 ms (real time)
}
object HarpsiTiming {
    const val DIP_MM = 6.0f; const val PLUCK8_MM = 4.2f; const val PLUCK4_MM = 2.6f; const val JACK_RATIO = 1.0f
    const val CLOTH_MM = 1.5f; const val RETURN_MS = 55f                     // quadratic (gravity-like) return
    fun leadMs(vel: Int): Float                   // 40 - 25 * vel / 127: key start → 8′ pluck (real time)
    fun staggerMs(vel: Int): Float                // 0.2339 * leadMs(vel) = lead * (1 - (2.6/4.2)^(1/1.8)); 3.5–9.4 ms, real time
    fun depth(u: Float): Float                    // key depth fraction at normalised time u ∈ [0,1] to the 8′ pluck: 0.70 * u^1.8
    fun returnDip(dHeld: Float, t: Float): Float  // dHeld · (1 − (t / RETURN_MS)²), clamped at 0
    val quill8PassMs: Float                       // 55 · √(1 − 4.2/6) = 30.1 ms: 8′ tongue flick, jack-fall noise and release start
    val quill4PassMs: Float                       // 55 · √(1 − 2.6/6) = 41.4 ms
    val damperLandMs: Float                       // 55 · √(1 − 1.5/6) = 47.6 ms: the cloth touches the string
}

// ═══════════ contract/PedalCurve.kt ═══════════
class PedalCurve(@JvmField val us: LongArray, @JvmField val v: FloatArray) {   // piecewise linear, v ∈ [0, 1], song time
    val isEmpty: Boolean get() = us.isEmpty()
    fun valueAt(t: Long): Float                                           // binary search; 0 before the first point
    class Cursor {                                                        // preallocated by its owner; rebindable, allocation-free
        fun bind(c: PedalCurve); fun seek(t: Long); fun advanceTo(t: Long): Float }   // advanceTo O(1) amortised
    fun nextCrossing(fromUs: Long, level: Float, rising: Boolean): Long   // Long.MAX_VALUE if none
    companion object { val EMPTY: PedalCurve }
}

// ═══════════ contract/Performance.kt ═══════════
class PerfInfo(val title: String?, val lowKey: Int, val highKey: Int, val noteCount: Int, val maxPolyphony: Int,
    val pedalMode: PedalMode, val sustainEvents: Int, val softEvents: Int, val sostenutoEvents: Int,
    val folded: Int, val mergedChannels: Int, val droppedDrumNotes: Int, val fingerPedalled: Boolean,
    val voiceDemandP99: Int, val voiceDemandMax: Int,                 // uncapped simulation (§3.14)
    val warnings: List<PerfWarning>)

class Performance(
    val id: String, val generation: Int, val instrument: InstrumentId, val lastDamper: Int,
    val durationUs: Long,                                   // includes PRE_ROLL_US; last key-up + 1.5 s
    @JvmField val onUs: LongArray, @JvmField val offUs: LongArray,  // per note, sorted by (onUs, key). onUs = hammer contact / 8′ pluck; offUs = key release starts
    @JvmField val key: ByteArray, @JvmField val vel: ByteArray, @JvmField val flags: ByteArray,
    @JvmField val keyFirst: IntArray,                       // size 129 (CSR): notes of key k = keyNotes[keyFirst[k] until keyFirst[k+1]]
    @JvmField val keyNotes: IntArray,                       // note indices per key, sorted by onUs, never overlapping
    val sustain: PedalCurve, val soft: PedalCurve, val sostenuto: PedalCurve,
    @JvmField val latchUs: LongArray, @JvmField val latchLo: LongArray, @JvmField val latchHi: LongArray,  // sostenuto masks, keys 0..63 / 64..127
    @JvmField val evUs: LongArray, @JvmField val ev: IntArray,  // audio event stream sorted by (evUs, type)
    @JvmField val barUs: LongArray, val info: PerfInfo) {
    val noteCount: Int get() = onUs.size
    fun eventIndexAtOrAfter(us: Long): Int
    fun barAt(us: Long): Int                                // 1-based
    fun latchIndexAt(us: Long): Int                         // -1 = none active
    companion object {
        const val F_RESTRIKE = 1; const val F_FOLDED = 2
        // Event types; numeric order = tie order at equal time. Real-time offsets (damper lag, 4′ stagger) are applied by the
        // Sequencer in OUTPUT frames, independent of the rate; events carry only score times.
        const val EV_KEY_UP = 1          // arg = note; at offUs. The damper lands damperLagMs (real time) later unless held
        const val EV_LATCH = 2           // arg = latch index (sostenuto mask change)
        const val EV_PEDAL_NOISE = 3     // arg: bit0 1 = down / 0 = up, bits 1..2 = speed class 0..3
        const val EV_PLUCK4_RETIRED = 4  // reserved; the 4′ voice is started by EV_NOTE_ON at −staggerFrames(vel)
        const val EV_NOTE_ON = 5         // arg = note; at onUs
        const val EV_END = 15
        fun type(e: Int) = e ushr 28
        fun arg(e: Int) = e and 0x0FFFFFFF
        fun pack(type: Int, arg: Int) = (type shl 28) or arg
    }
}

// ═══════════ contract/ScoreApi.kt ═══════════
enum class SyntheticScore { SYNC_CLICK, SCALE, CHORD_STORM_64, PEDAL_HALF, SOSTENUTO, UNA_CORDA, REPEAT_15, FOLD, CRESCENDO_C4 }
object SyntheticSpecs { fun notes(kind: SyntheticScore): ScoreSpec }   // WP0: the note and controller lists of §4.5 (shared by PerfFixtures and WP1)
class ScoreSpec(val onUs: LongArray, val offUs: LongArray, val key: ByteArray, val vel: ByteArray,
                val cc64: PedalCurve, val cc66: PedalCurve, val cc67: PedalCurve)   // raw controller curves (unshaped)
class CompileOptions(val legatoHold: Boolean = true, val legatoCapMs: Int = 1500, val flatVelocity: Int = 0)  // >0 replaces every velocity
sealed class CompileResult { class Ok(val perf: Performance) : CompileResult(); class Failed(val reason: RejectReason, val detail: String, val byteOffset: Int) : CompileResult() }
class ScoreFacts(val ok: Boolean, val error: RejectReason?, val title: String?, val durationSec: Float,
    val lowKey: Int, val highKey: Int, val noteCount: Int, val hasSustain: Boolean, val hasSoft: Boolean,
    val hasSostenuto: Boolean, val pedalMode: PedalMode, val channels: Int, val warnings: List<PerfWarning>)
interface ScoreCompiler {                                   // pure, any thread, never throws
    fun sniff(head: ByteArray): Boolean                     // "MThd" at 0, or RIFF…RMID
    fun inspect(bytes: ByteArray): ScoreFacts
    fun compile(bytes: ByteArray, id: String, generation: Int, profile: InstrumentProfile,
                opts: CompileOptions = CompileOptions()): CompileResult
    fun synthetic(kind: SyntheticScore, profile: InstrumentProfile, generation: Int): Performance
}

// ═══════════ contract/Clock.kt ═══════════
class ClockSample {
    @JvmField var songUs = 0L        // song time heard at the asked instant (µs incl. pre-roll)
    @JvmField var rate = 1f; @JvmField var playing = false
    @JvmField var epoch = 0          // engine: bumps on seek, new performance, instrument swap
    @JvmField var session = 0        // clock: bumps on every AudioClock.reset() (start, un-park, track rebuild)
    @JvmField var generation = -1    // Performance.generation being heard
    @JvmField var registration = 3   // effective harpsichord register mask of the heard block
    @JvmField var heardFrame = 0L    // output frame reaching the DAC at the asked instant (clamped, see §2.5)
    @JvmField var valid = false      // false until the first block of this session
    @JvmField var fromTimestamp = false
}
interface SongClock { fun sample(nanoTime: Long, out: ClockSample) }    // allocation-free, any thread
class CoreClockState {                                                 // state at the START of the block just rendered
    @JvmField var songUs = 0L; @JvmField var rate = 1f; @JvmField var playing = false
    @JvmField var epoch = 0; @JvmField var generation = -1; @JvmField var registration = 3
    @JvmField var endedGeneration = -1                                 // set once when EV_END's tails are done (§3.15)
    @JvmField var idle = false                                         // paused (or no performance), no voices, room tail inactive
}
class ClockStats { @JvmField var tsAccepted = 0; @JvmField var tsRejected = 0; @JvmField var clockMiss = 0
    @JvmField var latFrames = 0; @JvmField var fsFit = 48000f; @JvmField var driftP99Frames = 0f; @JvmField var session = 0 }
class AudioClock(sampleRate: Int = HK.SR, records: Int = HK.CLOCK_RECORDS) : SongClock {   // WP0 implements (§2.5)
    fun reset()                                                        // before every play() of a new, rebuilt or un-parked track
    fun publishBlock(blockStartFrame: Long, st: CoreClockState)        // HKAudio, after each accepted write
    fun publishTimestamp(frame: Long, nanoTime: Long): Boolean         // HKAudio, every 16 blocks and while idle; false = rejected
    fun publishEstimate(framesAccepted: Long, latencyFrames: Int, nanoTime: Long)   // until the first accepted timestamp of a session
    override fun sample(nanoTime: Long, out: ClockSample)
    fun stats(out: ClockStats)
}
class VisTime {                                                        // one per frame, GLThread
    @JvmField var tUs = 0L                                             // filtered heard song time: monotone within (session, epoch, generation)
    @JvmField var exposeFromUs = 0L; @JvmField var exposeToUs = 0L     // contacts in (from, to] are drawn in this frame (each exactly once)
    @JvmField var reseed = true                                        // cursors must re-seed and exposure state is cleared
    @JvmField var playing = false; @JvmField var rate = 1f; @JvmField var epoch = 0; @JvmField var generation = -1
    @JvmField var registration = 3; @JvmField var heardFrame = 0L
}
class VisualClock { fun update(s: ClockSample, nowNanos: Long, out: VisTime) }   // WP0 implements (§2.5); allocation-free
class HeadPose {                                                       // one AtomicLong: yaw and ω as float bits
    fun write(yawRad: Float, omegaRadPerS: Float)                      // main, from GazeCamera's sensor callback
    fun yaw(): Float; fun omega(): Float                               // any thread
}

// ═══════════ contract/Rings.kt (WP0 implements, with tests; payloads in atomic arrays, §2.1 rule 2) ═══════════
fun interface CommandHandler { fun on(code: Int, l: Long, f: Float, ref: Any?) }
class CommandRing(capacityPow2: Int = 256) {                          // SPSC: main → HKAudio
    fun offer(code: Int, l: Long = 0L, f: Float = 0f, ref: Any? = null): Boolean   // false = full (counted)
    fun drain(max: Int, h: CommandHandler): Int                       // h is a long-lived object (EngineCoreApi itself), never a lambda
    val dropped: Int
}
class EnergyRing(slots: Int = HK.ENERGY_SLOTS) {                      // seqlock per slot (AtomicIntegerArray)
    fun reset()
    fun write(blockStartFrame: Long, epoch: Int, lanes: FloatArray /*88, linear RMS*/)   // HKAudio
    fun read(heardFrame: Long, epoch: Int, out: FloatArray): Boolean  // GL: newest slot with frame ≤ heardFrame and same epoch; older than the oldest → oldest + miss
    val misses: Int
}
class VoiceCursorBoard(size: Int = 208) {                             // HKAudio writes (lazySet), HKPrefetch reads
    fun set(slot: Int, region: Int, frame: Int); fun clear(slot: Int); fun clearAll()
    fun get(slot: Int): Long                                          // -1 = empty; else (region shl 32) or frame
    val size: Int
}

// ═══════════ contract/Quality.kt ═══════════
class QualityProfile(val level: Int, val frameDivider: Int /* 0 = display rest */, val roomCap: RoomLevel,
    val mirrorFlames: Boolean, val crystals: Int, val brightnessCap: Float /* window screenBrightness; -1 = system */,
    val voiceCap: Int, val hermite: Boolean, val spectralDamping: Boolean, val dispersion: Boolean, val combs: Int, val fdnLines: Int,
    val voicingAllowed: Boolean)
object QualityLadder { const val MAX = 3; fun of(level: Int, q0Cap: Int): QualityProfile }   // table in §5.11; q0Cap from EngineBench

// ═══════════ contract/Bank.kt ═══════════
class BankInfo(val instrument: InstrumentId, val kit: String, val version: String, val sha1: String,
    val layers: Int, val stops: Int, val lastDamper: Int, val recordedAHz: Float, val aOffsetCents: Float,
    val damperT60: FloatArray /*128, s*/, val freeT60: FloatArray /*stops × 128, s*/, val inharmB: FloatArray /*128*/,
    val releaseCarriesTail: Boolean, val embeddedRoomDb: Float, val decodeOrder: IntArray,
    val isStub: Boolean, val fallback: FallbackReason?)
interface SampleReader {                                              // one per thread
    fun read(region: Int, fromFrame: Int, frames: Int, dst: ShortArray, dstOff: Int): Int   // HKAudio: from the mapping; stereo interleaved; zero-fills outside
    fun prefetch(region: Int, fromFrame: Int, frames: Int)            // HKPrefetch: positional FileChannel.read in 64 KiB chunks into one reused direct buffer (thread in Native state; never touches the mapping)
    val slowReads: Int                                                // bulk reads that took > 1 ms
}
interface LoadedBank {
    val info: BankInfo
    val generation: Int                                               // bumps per open; echoed in AudioStats and the prefetcher state
    val regionCount: Int
    fun frames(region: Int): Int
    fun onsetFrame(region: Int): Int                                  // attack index in source frames, ≈ 96
    fun thrFrame(region: Int): Int                                    // first frame above −40 dB re the region peak (T-ALIGN)
    fun envByte(region: Int, tenMs: Int): Int                         // 0..255 = −dBFS × 2 of the normalised region; 255 past the end
    fun newReader(): SampleReader
    @Volatile var readyMask: Long                                     // informational only (bit u = unit id u; 62 releases; 63 pedals); the engine never reads it
}
class KeyMap(                                                         // immutable, built off-thread; see §3.5. Index "sk" = (stop * layers + layer) * 128 + key
    val tuning: TuningSpec, val readyMask: Long, val layers: Int, val stops: Int,
    @JvmField val velLayerA: ByteArray, @JvmField val velLayerB: ByteArray,     // 128; B = -1 when not crossfading
    @JvmField val velGainA: FloatArray, @JvmField val velGainB: FloatArray,     // 128; crossfade weight × level-curve trim (linear)
    @JvmField val region: IntArray,                                   // sk → region, -1 none (never an unready region)
    @JvmField val rate: FloatArray, @JvmField val gain: FloatArray,   // sk: playback rate; 10^(gainDb/20) × seam trim × fallback trim
    @JvmField val onsetOut: IntArray,                                 // sk: round(onsetFrame / rate), output frames
    @JvmField val lpHz: FloatArray,                                   // sk: fixed seam low-pass for borrowed regions, 0 = none
    @JvmField val release: IntArray, @JvmField val releaseRate: FloatArray, @JvmField val releaseGain: FloatArray,  // stop * 128 + key
    @JvmField val pedalDown: IntArray, @JvmField val pedalUp: IntArray, @JvmField val pedalGain: Float,
    @JvmField val f0Hz: FloatArray, @JvmField val inharmB: FloatArray, @JvmField val strings: ByteArray)   // 128 (main stop)
sealed class KitState {
    object Missing : KitState()
    class Voicing(val fraction: Float, val playable: Boolean) : KitState()
    object Complete : KitState()
    class Fallback(val reason: FallbackReason) : KitState()
}
interface KitCallback {                                               // main thread
    fun onProgress(id: InstrumentId, fraction: Float)
    fun onPlayable(bank: LoadedBank)                                  // ≥ 1 sustain unit + releases + pedals ready
    fun onLayersChanged(bank: LoadedBank)                             // readyMask grew: rebuild the KeyMap
    fun onComplete(bank: LoadedBank)
    fun onFallback(bank: LoadedBank, reason: FallbackReason)          // stub or synthesized bank in use
}
interface KitService {
    fun state(id: InstrumentId): KitState
    fun info(id: InstrumentId): BankInfo?                             // map.json facts, available before any decoding
    fun open(id: InstrumentId, cb: KitCallback)                       // decodes if needed; callbacks on main
    fun keyMap(bank: LoadedBank, tuning: TuningSpec): KeyMap          // pure; call on HKLoader
    fun setPlaybackHint(playing: Boolean, activeId: InstrumentId?, q: QualityProfile, batteryTenths: Int)
    fun decodeWhenIdle(ids: List<InstrumentId>)
    fun release(id: InstrumentId)   // stop voicing and prefetching; drop references once AudioStats.bankGeneration and the prefetcher both show a newer bank. NEVER unmaps: the mapping lives until GC (clean, reclaimable pages; counted in RSS meanwhile)
    fun diagnostics(): Map<String, String> = emptyMap()
}

// ═══════════ contract/AudioApi.kt ═══════════
object Cmd {                                                          // CommandRing codes (l, f, ref as noted)
    const val SET_BANK = 1       // ref = prepared bank token from EngineCoreApi.prepareBank
    const val SET_KEYMAP = 2     // ref = prepared key-map token
    const val SET_PERF = 3       // ref = Performance?, l = startUs (−1 = the engine's own current song position), f = 1 autoplay
    const val PLAY = 4
    const val PAUSE = 5          // l = fade ms (60 default; 300 when leaving the app)
    const val SEEK = 6           // l = song µs
    const val RATE = 7           // f = 0.5..1.5
    const val QUALITY = 8        // ref = QualityProfile
    const val ROOM = 9           // ref = RoomDesign, l = glide ms
    const val MIX = 10           // ref = MixSettings
    const val ROUTE = 11         // l = OutputRoute.ordinal, f = measured output latency ms (for yaw prediction)
    const val DUCK = 12          // f = gain
    const val REGISTRATION = 13  // l = mask; applied at the block boundary and published in CoreClockState
    const val VOICE_CAP = 14     // l = cap (AudioOutput's self-protection)
    const val BENCH = 15         // l = seconds
    const val RESET = 16
}
class MixSettings(val reverb: ReverbMode, val resonance: ResonanceMode,
    val speakerBass: SpeakerBass, val masterDb: Float, val releaseNoises: Boolean = true, val pedalNoises: Boolean = true)
class AudioStats {
    @JvmField var voices = 0; @JvmField var voicesPeak = 0; @JvmField var noiseVoices = 0; @JvmField var stolen = 0; @JvmField var dropped = 0
    @JvmField var combsActive = 0; @JvmField var blockP50Us = 0; @JvmField var blockP99Us = 0; @JvmField var blockMaxUs = 0; @JvmField var cpuPct = 0f
    @JvmField var underruns = 0; @JvmField var slowReads = 0; @JvmField var bufferFrames = 0; @JvmField var voiceCap = 0
    @JvmField var headroomMinFrames = 0; @JvmField var parked = false; @JvmField var trackRebuilds = 0; @JvmField var energyMiss = 0
    @JvmField var epoch = 0; @JvmField var generation = -1; @JvmField var bankGeneration = -1; @JvmField var bankStub = false
    @JvmField var fastTrack = false; @JvmField var tid = 0                // HKAudio kernel tid, for the majflt probe
}
interface AudioListener {                                             // main; posted with preallocated Runnables
    fun onEnded(generation: Int); fun onOverload(newCap: Int); fun onEngineError(code: StatusCode, detail: String)
    fun onRouteChanged(route: RouteInfo) {}
}
interface AudioControl {                                             // main thread; WP4 AudioOutput
    val clock: SongClock
    val energy: EnergyRing
    val route: RouteInfo
    fun start(); fun stop()                                          // idempotent; start() after stop() re-sends the last bank, key map and Performance and restores the paused position; stop joins ≤ 350 ms
    fun setBank(bank: LoadedBank, keyMap: KeyMap, profile: InstrumentProfile)   // 30 ms crossfade at a block boundary
    fun setKeyMap(keyMap: KeyMap)                                    // tuning/readiness change; new notes only
    fun setPerformance(p: Performance?, startUs: Long, autoPlay: Boolean)       // startUs −1 = keep the engine's position
    fun play(); fun pause(fadeMs: Int = 60); fun seek(us: Long); fun setRate(rate: Float)
    fun setQuality(q: QualityProfile); fun setRoom(d: RoomDesign, glideMs: Int); fun setMix(m: MixSettings)
    fun setRegistration(mask: Int); fun setDuck(gain: Float); fun bench(seconds: Int)
    fun setListener(l: AudioListener?); fun stats(out: AudioStats); fun clockStats(out: ClockStats)
    fun diagnostics(): Map<String, String> = emptyMap()
}
interface EngineCoreApi : CommandHandler {                           // WP2 EngineCore; HKAudio except prepare*. on() = drained commands
    fun prepareBank(bank: LoadedBank, keyMap: KeyMap, profile: InstrumentProfile): Any   // any thread; allocates tables
    fun prepareKeyMap(keyMap: KeyMap, info: BankInfo, profile: InstrumentProfile): Any   // any thread
    fun render(out: FloatArray /*2 × BLOCK interleaved*/, blockStartFrame: Long)
    fun clockState(out: CoreClockState)                              // for the block just rendered
    fun energy(outLanes: FloatArray /*88, linear RMS*/): Int         // for the block just rendered; returns its epoch
    fun stats(out: AudioStats)
    fun reset()
    fun debugOnset(out: LongArray /*[scheduledFrame, detectedFrame]*/): Boolean = false   // T-ALIGN hook (§8.4)
}
// Engine construction (frozen): EngineCore(dsp: DspSet, cursors: VoiceCursorBoard, head: HeadPose, sampleRate: Int = HK.SR)

// ═══════════ contract/Dsp.kt ═══════════
class ListenerPose(val earRoom: FloatArray /*3, m*/, val forwardYawRad: Float, val worldLocked: Boolean, val directWidth: Float)
class RoomDesign(
    @JvmField val erDelay: IntArray, @JvmField val erGainL: FloatArray, @JvmField val erGainR: FloatArray,  // 12 taps
    @JvmField val erBright: BooleanArray, val brightLpHz: Float, val dullLpHz: Float, val preDelayFrames: Int,
    val t60Low: Float, val t60Mid: Float, val t60High: Float,         // s at 125 Hz, 500 Hz–1 kHz, 4 kHz
    val reverbGain: Float,                                            // reverberant level re direct at 1 m, incl. ReverbMode and embedded-room compensation
    val erGain: Float,                                                // early-reflection send, incl. embedded-room compensation
    val directGain: Float, val airLpHz: Float, val width: Float,
    val worldLocked: Boolean, val sourceAzimuthRad: Float)            // azimuth of the source in the room (yaw convention above)
interface RoomDesigner {                                              // WP3 RoomAcoustics; pure, main/HKLoader
    fun design(g: VenueGeometry, placement: Placement, sourcePiano: FloatArray, listener: ListenerPose,
               mode: ReverbMode, benchDistanceM: Float, embeddedRoomDb: Float): RoomDesign
}
interface ResonanceProcessor {
    fun prepare(keyMap: KeyMap, info: BankInfo, profile: InstrumentProfile): Any      // off-thread tables: delays, allpass, dispersion, comb gain per (key, D step)
    fun apply(prepared: Any, glideMs: Int)                                             // HKAudio: swap, gliding delays and gains (200 ms on retune)
    fun setMode(mode: ResonanceMode, instrument: InstrumentId, maxActive: Int, dispersion: Boolean)   // WP3 owns SEND and UNA_CORDA_SEND
    fun process(mix: FloatArray /*mono voices, n*/, self: FloatArray /*88 × BLOCK: row i = key 21+i's own voices*/,
                selfRows: BooleanArray /*88: rows written this block*/, gate: FloatArray /*128, 0..1*/,
                softFeed: BooleanArray /*128*/, damping: FloatArray /*128, D*/, outL: FloatArray, outR: FloatArray, n: Int)  // adds into out
    fun energy(outMeanSquare: FloatArray /*88*/)                                         // ADDS each comb's mean-square of the last block
    val active: Int
    fun reset()
}
interface RoomProcessor {                                            // direct path + early reflections + FDN
    fun setDesign(d: RoomDesign, glideMs: Int); fun setLines(n: Int)
    fun setInputGain(g: Float, rampMs: Float)                        // pause/resume/seek fade of the room input
    fun process(inL: FloatArray, inR: FloatArray, outL: FloatArray, outR: FloatArray, n: Int, headYawRad: Float)  // writes out
    val tailActive: Boolean                                          // false once the tail is below -90 dBFS
    fun reset()
}
interface SoftBusProcessor { fun configure(kind: SoftKind); fun process(inL: FloatArray, inR: FloatArray, outL: FloatArray, outR: FloatArray, n: Int); fun reset() }  // adds
interface MasterProcessor {
    fun setRoute(r: OutputRoute); fun setSpeakerBass(m: SpeakerBass); fun setGain(linear: Float)
    fun process(l: FloatArray, r: FloatArray, n: Int, outInterleaved: FloatArray); fun reset() }
class DspSet(val resonance: ResonanceProcessor, val room: RoomProcessor, val soft: SoftBusProcessor, val master: MasterProcessor)

// ═══════════ contract/Venue.kt ═══════════
class AcousticMaterial(val name: String, val alpha: FloatArray /*125, 250, 500, 1k, 2k, 4k, 8k Hz*/)
class Surface(val name: String, val plane: Int /*0 floor 1 ceiling 2 N 3 S 4 E 5 W*/, val areaM2: Float, val material: AcousticMaterial)  // one plane per entry
class Placement(val originRoom: FloatArray /*3*/, val yawRad: Float) {   // piano frame → room frame
    fun toRoom(p: FloatArray, out: FloatArray)                          // out = origin + R_y(yaw) · p; R_y(θ): (x,y,z) → (x cosθ + z sinθ, y, −x sinθ + z cosθ)
}
class VenueGeometry(val widthM: Float, val depthM: Float, val corniceM: Float, val ceilingM: Float, val volumeM3: Float,
    val surfaces: List<Surface>, val erPlanes: FloatArray /*6: floor y, ceiling y (effective), N z, S z, E x, W x*/,
    val people: Int, val personSabins: FloatArray /*7*/, val airM: FloatArray /*7, 1/m*/,
    val placements: Map<InstrumentId, Placement>, val fixtureFootprints: FloatArray /*n × 4: xMin zMin xMax zMax*/)
object KonzertzimmerAcoustics { val GEOMETRY: VenueGeometry }         // WP0: the §3.12 plane × material table; WP8 returns it, WP3's tests use it
object Conventions {
    fun yawOf(dirRoom: FloatArray): Float                              // atan2(dx, −dz)
    fun forwardYaw(placement: Placement, earPiano: FloatArray, sourcePiano: FloatArray): Float   // listener faces the source
}

// ═══════════ contract/Mechanics.kt ═══════════
class MechanismPose {
    @JvmField val keyDip = FloatArray(128)     // 0..1 of full dip at the key front
    @JvmField val hammer = FloatArray(128)     // grand/upright: 0 rest .. 1 at string; harpsichord: 8′ jack rise 0..1 (1 = jack rail)
    @JvmField val jack4 = FloatArray(128)      // harpsichord 4′ jack rise 0..1
    @JvmField val escape = FloatArray(128)     // grand jack escape 0..1 (cutaway)
    @JvmField val damper = FloatArray(128)     // 0 on string .. 1 fully lifted
    @JvmField val tongue = FloatArray(128)     // harpsichord 8′ tongue deflection 0..1
    @JvmField val tongue4 = FloatArray(128)
    @JvmField val stringAmp = FloatArray(128)  // 0..1 visual vibration amplitude
    @JvmField val strikeAge = FloatArray(128)  // seconds since last drawn contact/pluck; 1e9 = none
    @JvmField val flash = BooleanArray(128)    // a contact falls in this frame's exposure window (sync disc, strike pulse start)
    @JvmField var sustain = 0f; @JvmField var soft = 0f; @JvmField var sostenuto = 0f
    @JvmField var shiftMm = 0f; @JvmField var hammerRailMm = 0f; @JvmField var registers = 3
    @JvmField var focusKey = 60f; @JvmField var centroidKey = 60f
    @JvmField var songUs = 0L; @JvmField var playing = false
}
interface MechanicsEvaluator {                                        // GLThread; allocation-free after bind
    fun bind(perf: Performance?, profile: InstrumentProfile)          // resets cursors; perf.lastDamper is authoritative
    fun evaluate(v: VisTime, energy: FloatArray? /*88 lanes, linear RMS*/, dtSec: Float, out: MechanismPose)   // registration from v
}

// ═══════════ contract/Scene.kt (pure mesh data from WP7/WP8, drawn by WP6) ═══════════
enum class VertexLayout(val floats: Int) { STATIC(12), SKINNED(17), STRING(16) }
// STATIC: pos3 nrm3 uv2 rgba4 · SKINNED: STATIC + slot1 (vec4 index) + lane4 (one-hot)
// STRING: pos3 (rest, along the string) dir3 (string direction) t1 (0..1 along speaking length) side1 (±1) slot1 lane4 rgb3
// ── MeshBuilder conventions (normative; mesh/MeshBuilder.kt implements them) ──
// • Triangles are CCW seen from the front (outside); normals are unit length and point outward; back-face culling is on.
// • UV: box = per face, u along the face's first edge and v along its second, in metres; extrude sides u = outline arc length (m),
//   v = y (m), caps (x, z) m; lathe u = angle/2π, v = profile arc length (m); sweep u = path arc length, v = profile arc length (m);
//   keys (Keyboard) key-local UV in mm: u across the key from its left edge, v along from its front (the bevel shader relies on it).
// • Ribbon (STATIC layout, RIBBON program): pos = path point, nrm3 = unit path tangent, uv = (side ±1, half-width m); the shader widens it
//   in screen space with 1.5–2 px AA.
// • color() takes UN-lifted sRGB 0..255 (the Pal tokens); shaders apply pow(c, 0.85). rgba stored as 0..1 floats.
// • Indices are ShortArray read as unsigned (i and 0xFFFF); a mesh above 65,535 vertices is split by build().
// • Skinned vertices: slot = vec4 index into uState, lane = one-hot component. KEY_ROT/HAMMER_ROT/DAMPER_LIFT/JACK*/TONGUE*: partIndex =
//   key − lowKey → slot = partIndex / 4, lane = partIndex % 4. PEDAL_ROT partIndex 0 soft/una corda, 1 sostenuto, 2 sustain (left to right).
//   ACTION_SET: slot/lane address the part's angle (vec4 13 + (6·s + p) / 4); uv = (s = slot 0..12, p = part type 0..5) selects the
//   header vec4 s and the pivot of part type p.
enum class SkinKind { STATIC, KEY_ROT, HAMMER_ROT, DAMPER_LIFT, JACK_LIFT, JACK4_LIFT, TONGUE_ROT, TONGUE4_ROT,
                      STRING, PEDAL_ROT, ACTION_SET, LID, SHIFT_X, HAMMER_RAIL, SOSTENUTO_ROT }
enum class ProgramId { LIT, LACQUER, SKINNED, STRING, RIBBON, SPRITE, DECAL, SECTION_CAP }
enum class MaterialId { LACQUER, WOOD_CASE, FLEMISH_CASE, IVORY, EBONY_KEY, BONE, KEY_FRONT, KEYLEVER, ACTION_WOOD,
    FELT, DAMPER_TOP, CLOTH_RED, LEATHER, QUILL, BRASS, PLATE, SOUNDBOARD, HARPSI_SOUNDBOARD, PAPER, STEEL, COPPER,
    GILT, GILT_EMISSIVE, PARQUET_POOL, CHAIR_FRAME, DAMASK, MIRROR_FRAME, WINDOW_FRAME, SECTION_CAP, EDGE_GILT }
class MaterialParams(val program: ProgramId, val specExp: Float, val f0: Float, val presenceFloor: Boolean, val emissive: Float, val rim: Boolean)
object MaterialTable { fun of(m: MaterialId): MaterialParams }       // the §5.8 table; WP6 reads it, WP7/WP8 choose materials from it
class BakedMesh(val name: String, val layout: VertexLayout, val vertices: FloatArray, val indices: ShortArray,
    val material: MaterialId, val skin: SkinKind,
    val levelMask: Int,          // bit RoomLevel.ordinal: drawn at that venue level
    val viewMask: Int,           // bit (view.ordinal * 2 + framing): drawn in that framing
    val clipped: Boolean,        // discarded by the Action cut plane
    val program: ProgramId,      // must equal MaterialTable.of(material).program unless skinned (SKINNED) or a string (STRING)
    val drawSlot: Int,           // the §5.3 row number; SceneAssembler merges meshes with equal (program, material, skin, texture, levelMask, viewMask, clipped, drawSlot)
    val texture: String? = null, val fadeNearM: Float = 0f, val fadeFarM: Float = 0f)   // distance fade (Stage level)
class CameraPose {
    @JvmField val pos = FloatArray(3); @JvmField val target = FloatArray(3)
    @JvmField var roomFrame = false                                   // true: pos/target are room-frame (Hall); false: piano frame
    @JvmField var vFovDeg = 34f; @JvmField var ipdScale = 0.6f; @JvmField var zeroParallaxM = 1.75f
    @JvmField var clipX = Float.NaN; @JvmField var lidLift = 0f       // set by the anchors; CameraDirector only applies springs and the dip
}
class InstrumentLook(val finish: UprightFinish, val edgeOverlay: Boolean)
class SkinParams(val p: Map<SkinKind, FloatArray>)                   // per kind (§5.8)
interface InstrumentAnchors {
    val keyX: FloatArray                                             // 128, m in the piano frame; NaN outside the compass
    val soundSource: FloatArray                                      // piano frame
    val benchEar: FloatArray                                         // piano frame
    fun camera(view: ViewId, framing: Int, focusKey: Float, centroidKey: Float, out: CameraPose)   // sets roomFrame, clipX, lidLift
    fun listener(view: ViewId, framing: Int, out: FloatArray): Boolean   // true = room frame (Hall), false = piano frame
}
interface Painter2D {                                                // pure 2D drawing API; CanvasPainter (app) and AwtPainter (tests) implement it
    fun begin(width: Int, height: Int)                               // transparent black
    fun fillPath(xy: FloatArray, closed: Boolean, rgba: Int); fun strokePath(xy: FloatArray, closed: Boolean, widthPx: Float, rgba: Int)
    fun fillRect(x: Float, y: Float, w: Float, h: Float, rgba: Int); fun fillCircle(cx: Float, cy: Float, r: Float, rgba: Int)
    fun linearGradient(x0: Float, y0: Float, x1: Float, y1: Float, rgba0: Int, rgba1: Int, rect: FloatArray)
    fun text(s: String, x: Float, y: Float, sizePx: Float, rgba: Int, serif: Boolean, italic: Boolean, centred: Boolean)
    fun blur(radiusPx: Float)                                        // whole-image soft bloom
    fun end(): ByteArray                                             // RGBA8888
}
class TextureRecipe(val name: String, val width: Int, val height: Int, val paint: (Painter2D) -> Unit)   // run on HKLoader
interface InstrumentScene {
    val id: InstrumentId; val anchors: InstrumentAnchors; val skin: SkinParams
    fun meshes(): List<BakedMesh>                                    // HKLoader
    fun textures(): List<TextureRecipe>
    fun packActionSet(pose: MechanismPose, xCutKey: Float, out: FloatArray /*136 = 34 vec4, §5.8*/)   // GLThread, allocation-free
}
class LightRig { @JvmField val pos = FloatArray(12); @JvmField val rgb = FloatArray(12); @JvmField var flicker = 1f }
interface FlameField {
    val maxSprites: Int
    fun update(tSec: Float, eyeRoom: FloatArray /*eye in the room frame: WP6 applies the Placement for piano-frame cameras*/,
               q: QualityProfile, level: RoomLevel, out: FloatArray /*8 per sprite: xyz size rgb alpha*/): Int
    fun lights(tSec: Float, out: LightRig)
}
interface VenueScene {
    val geometry: VenueGeometry
    fun meshes(palette: Palette): List<BakedMesh>
    fun textures(): List<TextureRecipe>
    fun flames(): FlameField
    fun bakeProbe(centerRoom: FloatArray, out: ByteArray /*128 * 64 * 4*/)
}
interface SceneFactory { fun instrument(id: InstrumentId, look: InstrumentLook, lastDamper: Int): InstrumentScene; fun venue(): VenueScene }
// mesh/MeshBuilder.kt (WP7; WP0 ships box/quad/vertex/tri on day 0):
//   class MeshBuilder(layout: VertexLayout, capacityVerts: Int) { part(slot, lane); color(rgb: IntArray, a = 1f); vertex(pos, nrm, uv): Int; tri(a, b, c);
//     box(…); quad(…); extrude(outlineXZ, y0, y1, capTop, capBottom); lathe(profileRY, segments, cx, cz); sweep(pathXYZ, profileXY, closed);
//     ribbon(pathXYZ, widthM); spindle(a, b, segments, rgb); build(name, material, skin, levelMask, viewMask, clipped, program, drawSlot): List<BakedMesh> }

// ═══════════ contract/Render.kt ═══════════
class RenderSettings(val stereoDepth: Float, val lookAround: Boolean, val roomOverride: RoomLevel?, val palette: Palette,
    val presenceFloor: Int, val displayLeadMs: Int /* for the current route */, val lifeSizeVFov: Float, val look: InstrumentLook,
    val msaa: Boolean /* read once at GL view creation; a change applies at the next launch */)
class RenderOverrides(val ipdScale: Float? = null, val vFovDeg: Float? = null)   // CONTROL, current framing
class RenderStats {
    @JvmField var fps = 0f; @JvmField var draws = 0; @JvmField var tris = 0; @JvmField var cpuUsP99 = 0
    @JvmField var hitches = 0; @JvmField var divider = 2; @JvmField var dipping = false; @JvmField var headYawRad = 0f
    @JvmField var lateP99Us = 0; @JvmField var lateFrames = 0 /* late > 8 ms */; @JvmField var glGeneration = 0; @JvmField var energyMiss = 0
}
interface RenderControl {                                             // main thread; queueEvent runnables only set fields (§2.1 rule 6)
    fun bind(clock: SongClock, energy: EnergyRing, mech: MechanicsEvaluator, scenes: SceneFactory)
    fun setPerformance(p: Performance?, profile: InstrumentProfile)   // kept in two slots keyed by generation; the slot matching clock.generation is drawn
    fun setInstrument(id: InstrumentId, look: InstrumentLook, lastDamper: Int)   // desired scene: built on HKLoader, uploaded in onDrawFrame under the next dip (instantly after a display rest)
    fun setView(v: ViewId, framing: Int)                              // a dip (instant after a display rest)
    fun setQuality(q: QualityProfile); fun setSettings(s: RenderSettings)
    fun setStereo(on: Boolean); fun setIdle(idle: Boolean); fun setSyncFlash(on: Boolean)
    fun setTitle(text: String?); fun setStageHidden(hidden: Boolean); fun setOverrides(o: RenderOverrides)
    fun recenter(); fun onResume(); fun onPause(); fun stats(out: RenderStats)
    fun diagnostics(): Map<String, String> = emptyMap()               // GL renderer, EGL config incl. EGL_SAMPLES, uniform vectors, GL generation
}

// ═══════════ contract/Palette.kt: the single source of colour (sRGB lit-peak, §5.9) ═══════════
object Pal { /* FLAME_CORE, FLAME_BODY, FLAME_HALO, GILT_HI, GILT_LIT, GILT_SHADE, GILT_EMISSIVE, BOISERIE_NEAR, STADTSCHLOSS_GREEN,
               PARQUET_POOL, EBONY_FLOOR, EBONY_RIM, EBONY_SPEC, IVORY, IVORY_SIDE, BONE, BRASS_HI, BRASS_MID, PLATE_GOLD,
               SOUNDBOARD, STEEL_HI, STEEL_BASE, COPPER, FELT, LEATHER, DAMPER_TOP, KEYLEVER, ACTION_WOOD, CLOTH_RED,
               SECTION_CAP, FLEMISH_CASE, FLEMISH_PAPER, HARPSI_SOUNDBOARD, OAK, WALNUT, MAHOGANY, HUD_TEXT, HUD_ACCENT
               — each an IntArray(3), values in §5.9 */ }

// ═══════════ contract/Library.kt ═══════════
class Source(val id: String, val credit: String, val licence: String, val licenceUrl: String, val sourceUrl: String,
    val licenceFile: String?, val performanceType: String, val tier: String, val exportAllowed: Boolean)
class Movement(val id: String, val workId: String, val title: String, val asset: String?, val file: String?,
    val sha1: String, val durationSec: Float, val lowKey: Int, val highKey: Int,
    val hasSustain: Boolean, val hasSoft: Boolean, val hasSostenuto: Boolean, val pedalMode: PedalMode)
class Work(val id: String, val composer: String, val composerShort: String, val title: String, val shortTitle: String,
    val catalogue: String?, val year: Int?, val era: String, val defaultInstrument: InstrumentId,
    val altInstruments: List<InstrumentId>, val sourceId: String, val tier: String, val velocityPolicy: String /*as-is|flat*/,
    val tuning: TuningSpec?, val movementIds: List<String>, val imported: Boolean)
class Shelf(val id: String, val title: String, val workIds: List<String>)
class LibraryModel(val shelves: List<Shelf>, val works: Map<String, Work>, val movements: Map<String, Movement>,
    val sources: Map<String, Source>, val startHere: List<String> /*movement ids*/)
class ImportResult(val ok: Boolean, val name: String, val movementId: String?, val reason: RejectReason?, val detail: String?)
class ImportScan(val added: Int, val rejected: List<ImportResult>, val scoresDirForeign: Boolean)
interface LibraryService {                                            // every method runs under one lock; index.json written to a temp file and renamed
    fun load(): LibraryModel                                          // HKLoader; bundled + imported
    fun readBytes(m: Movement): ByteArray                             // "asset:<path>" ids resolve directly (M2)
    fun rescan(): ImportScan
    fun importFile(tmp: java.io.File, relativeName: String): ImportResult
    fun importZip(tmp: java.io.File): List<ImportResult>
    fun delete(movementId: String): Boolean
    val scoresDir: java.io.File                                       // the adb drop folder (read-only input)
}
class Playlist {                                                      // WP0 implements (pure, tested)
    fun set(ids: List<String>, index: Int); val current: String?; val index: Int; val size: Int
    fun peekNext(): String?; fun next(): String?
    fun previous(positionSec: Float): String?                         // same id = restart (> 3 s in)
}
interface SettingsStore {                                             // pure view of Settings (WP0); MemSettings in tests
    fun getString(k: String, d: String): String; fun putString(k: String, v: String)
    fun getInt(k: String, d: Int): Int; fun putInt(k: String, v: Int)
    fun getFloat(k: String, d: Float): Float; fun putFloat(k: String, v: Float)
    fun getBool(k: String, d: Boolean): Boolean; fun putBool(k: String, v: Boolean)
}

// ═══════════ contract/Ui.kt ═══════════
enum class UiContext { TITLE, PLAYING, MENU, ADJUST, CARD, PANEL, REST }
enum class UiEvent { KIT_PLAYABLE, ENTERED, MOVEMENT_STARTED, VIEW_CHANGED, PAUSED, RESUMED, REST_ON, REST_OFF, IMPORTED }
class SettingsSnapshot(val tuning: Map<InstrumentId, TuningSpec>, val registration: Int, val reverb: ReverbMode,
    val resonance: ResonanceMode, val speakerBass: SpeakerBass, val roomOverride: RoomLevel?, val palette: Palette,
    val finish: UprightFinish, val stereoDepth: Float, val lookAround: Boolean, val edgeOverlay: Boolean?,
    val reverseSwipe: Boolean, val presenceFloor: Int, val avLeadMs: Map<String, Int> /* route key → ms */, val tempoPct: Int, val msaa: Boolean)
class UiFacts(val playing: Boolean, val positionUs: Long, val durationUs: Long, val movementId: String?, val bar: Int,
    val instrument: InstrumentId, val view: ViewId, val framing: Int, val kitStates: Map<InstrumentId, KitState>,
    val library: LibraryModel?, val settings: SettingsSnapshot, val nextTitle: String?, val quality: Int,
    val companionUrl: String?, val companionToken: String, val route: RouteInfo, val status: List<StatusItem>,
    val perfInfo: PerfInfo?, val firstRun: Boolean, val sessions: Int, val resumeTitle: String?,
    val recent: List<String> /* movement ids, newest first, ≤ 15 */, val shelfId: String?, val version: String, val debug: String?)
sealed interface UiAction {
    data object Enter : UiAction; data object PlayPause : UiAction; data object Next : UiAction; data object Previous : UiAction
    data class Play(val movementId: String, val shelfId: String? = null) : UiAction      // the playlist is built from that shelf
    data class Seek(val us: Long) : UiAction                                               // song µs incl. pre-roll
    data class SetView(val view: ViewId, val framing: Int) : UiAction
    data object Recenter : UiAction; data object ShowHud : UiAction; data object Leave : UiAction
    data class SetInstrument(val id: InstrumentId) : UiAction
    data class SetTempo(val pct: Int) : UiAction
    data class SetTuning(val instrument: InstrumentId, val tuning: TuningSpec) : UiAction
    data class SetRegistration(val mask: Int) : UiAction
    data class SetMix(val reverb: ReverbMode? = null, val resonance: ResonanceMode? = null, val speakerBass: SpeakerBass? = null) : UiAction
    data class SetSight(val room: RoomLevel? = null, val autoRoom: Boolean = false, val palette: Palette? = null,
        val finish: UprightFinish? = null, val stereoDepth: Float? = null, val lookAround: Boolean? = null,
        val edgeOverlay: Boolean? = null, val reverseSwipe: Boolean? = null, val msaa: Boolean? = null) : UiAction
    data class SetPresenceFloor(val level: Int) : UiAction
    data class SetAvLead(val ms: Int) : UiAction                      // stored for the current route key
    data class SyncTest(val on: Boolean) : UiAction                   // plays SYNC_CLICK through setPerformance + render.setSyncFlash
    data object Rescan : UiAction; data object RotateToken : UiAction
}
abstract class OverlayState                                           // concrete class is WP10's; opaque to WP0/WP12
interface UiStateMachine {                                            // WP10, pure
    val context: UiContext
    fun onGesture(g: Gesture, facts: UiFacts, nowMs: Long): List<UiAction>
    fun onEvent(e: UiEvent, facts: UiFacts, nowMs: Long)
    fun render(facts: UiFacts, nowMs: Long): OverlayState
}

// ═══════════ contract/Companion.kt ═══════════
interface CompanionCommands {                                         // invoked on main via post; SessionController implements it
    fun play(movementId: String, instrument: InstrumentId?); fun toggle(); fun next(); fun previous()
    fun seek(us: Long /* song µs; CompanionServer converts the display ms */); fun instrument(id: InstrumentId)
    fun view(v: ViewId, framing: Int); fun importsChanged()
    fun nowPlayingJson(): String                                      // thread-safe: returns a @Volatile snapshot refreshed at 1 Hz
}

// ═══════════ contract/android/Hosts.kt (:app) ═══════════
package com.tropicalstream.hammerklavier.contract.android
interface GlHost : RenderControl { val view: android.view.View }
interface OverlayHost { val view: android.view.View; fun show(state: OverlayState) }
class CanvasPainter : Painter2D                                       // android.graphics.Bitmap + Canvas
```

**Stub behaviour (normative; each is a working implementation, not a placeholder):**
- `FakeClock`: a `SongClock` advancing song time in real time from `play()`; `valid`, `fromTimestamp = false`. Drives visuals when no audio exists.
- `NullAudio : AudioControl`: silent; keeps the transport state and publishes it through a `FakeClock`; `route` = speaker.
- `SineCore : EngineCoreApi`: renders one sine partial per sounding note (f0 from the KeyMap or 440·2^((k−69)/12)) at −12 dBFS RMS × velocity/127, honours PLAY/PAUSE/SEEK/SET_PERF with the §2.5 timing rules (onset at the exact frame), publishes `CoreClockState` and energy lanes by the contract units. Lets WP4 run `AudioOutput` before WP2 exists.
- `SineBank : LoadedBank`: synthesized in memory: 3 roots per octave (keys 21 + 3i), `layers` × `stops` regions of 2 s stereo ShortArrays (8 harmonic partials, 1.2 s decay, a sharp onset at frame 96, peak −3 dBFS), `envByte` computed from the data, `thrFrame` exact. `KeyMapFixtures.forSineBank(layers, mode = HARD|XFADE, stops, readyMask)` returns the matching valid `KeyMap` (ET, zero stretch shape), so WP2 needs neither WP4's codec nor `map_fixture`.
- `PerfFixtures.build(notes: ScoreSpec, sustain = PedalCurve.EMPTY, soft = EMPTY, sostenuto = EMPTY, profile, generation)`: a fully valid `Performance` (pre-roll, sorted notes, CSR, latch arrays, packed events sorted by (evUs, type), bars every 2 s, `PerfInfo`), with pedal curves used as given (no shaping). `StubScoreCompiler.synthetic(kind)` = `PerfFixtures.build(SyntheticSpecs.notes(kind))` for all nine kinds; `compile()` returns `Failed(NOT_MIDI)` unless the bytes are one of the test twins, which it maps to the same specs.
- `StubKits : KitService`: every instrument is playable at once with `SineBank`; `info()` returns its `BankInfo`.
- `PassThroughDsp`: resonance adds nothing, room copies dry to out, soft bus adds, master applies gain and the Padé clip. `StubRoomDesigner` returns `FixedRoom.PLAYER`, a literal `RoomDesign` for the grand's Player listener computed once by the architect's §3.12 numbers; `StubVenue.GEOMETRY` = `KonzertzimmerAcoustics.GEOMETRY` with the §5.6 placements.
- `StubMechanics`: a travelling wave across the keys; `StubScenes`: a box keyboard with skinned keys (correct conventions), a floor pool and six flames; `StubLibrary`: resolves `asset:<path>`, `synth:<kind>` and `test:<file>` ids directly and lists the test twins as one shelf; `StubUi`: tap → PlayPause, swipes → views; `MemSettings : SettingsStore` in memory.
- WP1's `SyntheticScores` must equal `PerfFixtures` for the same kind in notes, CSR and events, with pedal curves within 1 ms and 0.02 (a WP1 test); the WP11 `.mid` twins must compile to the same arrays.

**Frozen constructors of concrete entry points** (built only by `Wiring.kt`; the integrator swaps a stub for the real class when its WP merges):

| Class (WP) | Constructor |
|---|---|
| `midi.ScoreCompilerImpl` (1) | `ScoreCompilerImpl()` |
| `engine.EngineCore` (2) | `EngineCore(dsp: DspSet, cursors: VoiceCursorBoard, head: HeadPose, sampleRate: Int = HK.SR)` |
| `dsp.DspFactory` (3) | `object DspFactory { fun create(sampleRate: Int): DspSet }`; `dsp.RoomAcoustics : RoomDesigner` is an `object` |
| `audio.AudioOutput` (4) | `AudioOutput(ctx: Context, core: EngineCoreApi, cursors: VoiceCursorBoard, head: HeadPose, settings: SettingsStore)` |
| `audio.KitManager` (4) | `KitManager(ctx: Context, voicer: ExecutorService, loader: ExecutorService)` |
| `mech.MechanicsEvaluatorImpl` (5) | `MechanicsEvaluatorImpl()` |
| `render.HkGlView` (6) | `HkGlView(ctx: Context, loader: ExecutorService, msaa: Boolean) : GLSurfaceView, GlHost` |
| `instrument.Instruments` + `venue.VenueSceneImpl` (7, 8) | `object Instruments { fun create(id: InstrumentId, look: InstrumentLook, lastDamper: Int): InstrumentScene }`; `VenueSceneImpl()`; WP0's `Wiring` wraps both as a `SceneFactory` |
| `library.android.LibraryServiceImpl` (9) | `LibraryServiceImpl(ctx: Context, compiler: ScoreCompiler)` |
| `companion.CompanionServer` (9) | `CompanionServer(port: Int, token: () -> String, library: LibraryService, model: () -> LibraryModel?, commands: CompanionCommands, page: () -> String, post: (Runnable) -> Unit) : NanoHTTPD`; `fun url(): String?` (null when no site-local IPv4) |
| `session.SessionController` (12) | `SessionController(audio: AudioControl, render: RenderControl, kits: KitService, library: LibraryService, compiler: ScoreCompiler, designer: RoomDesigner, scenes: SceneFactory, settings: SettingsStore, loader: Executor, post: (Runnable) -> Unit, nowMs: () -> Long)` |
| `ui.model.UiStateMachineImpl` (10) | `UiStateMachineImpl()` |
| `ui.OverlayViews` (10) | `OverlayViews(ctx: Context) : OverlayHost` |

### 2.4 Data flow

```
 assets/catalog.json ─┐                                  ┌───────── CompanionServer (NanoHTTPD :19112) ◄── phone (token)
 files/imports/*.mid ─┴─► LibraryService ──HKLoader──► SessionController (main, pure) ◄── Gestures → UiStateMachine → UiActions
 files/Scores/ (adb, read-only) ─ copy ─┘                │   ▲   ◄── AppController: CONTROL, ThermalGovernor, route, focus, MediaSession
                    HKLoader: bytes ─► ScoreCompiler ───┤   │
                                   (Performance, immutable, generation N)
           KitManager ─► HKVoicer: KitDecoder (unit streams) ─► pcm cache ─► SampleStore (mmap) ─► LoadedBank ─► KeyMap (HKLoader)
                                                        │
              ┌──── CommandRing (SPSC) ──────────────► HKAudio: AudioOutput loop → EngineCore.render
              │                                          Sequencer (1024-frame lookahead) → VoicePool → Voice×N + noise pool → dry/soft buses, self rows
              │                                          ResonanceBank(88 combs, mix − self) → SoftBus → RoomChain(HeadPose) → MasterChain → write
              │                                          AudioClock.publishBlock / publishTimestamp   EnergyRing.write
              │                                          VoiceCursorBoard ──► HKPrefetch (pread)
              ▼                                               │ time + 88 energies only
 RenderControl ──► GLThread: clock.sample(max(frameTime, now) + lead) → VisualClock → MechanicsEvaluator.evaluate(perf, VisTime, energy)
                            → CameraDirector → InstrumentScene/VenueScene uniforms → eye 0 → eye 1   (Overlay Views ◄─ main, ≤ 1 Hz)
 SessionController: view change → InstrumentAnchors.listener → RoomDesigner.design(VenueGeometry) → AudioControl.setRoom
 GazeCamera (sensor callback, main) ──► HeadPose (atomic) ──► HKAudio (every block) and GLThread
```

The same immutable `Performance` goes to HKAudio and GLThread. Neither can be ahead of the other: both read the score, and the audio output owns time.

### 2.5 The clock and lookahead model

**Master clock: output frames.** HKAudio counts `framesAccepted`: the sum of `AudioTrack.write`'s return values since `AudioOutput.start()`, including the silent priming block; a short write advances it by what was accepted and the rest of the block is written on the next iteration; a negative return goes to the track supervisor (§3.1). A rebuilt track records `trackBaseFrame = framesAccepted` at its `play()`, and its timestamps are published as `trackBaseFrame + framePosition`. `AudioTrack.flush()` is never called; `AudioTrack.pause()` is called only to park the idle engine (§3.1). **`AudioClock.reset()` runs before every `play()`** of a new, rebuilt or un-parked track: it clears the head and every record, drops the timestamp anchor, re-enters estimate mode and bumps `session`, so no reader can pair a new frame domain with an old record or anchor.

**Song position without drift.** The engine keeps the song position in **song frames** (48 kHz frames of file time) as 32.32 fixed point in a `Long`; a playing block advances it by exactly `256 · rateFixed` (`rateFixed = round(r · 2³²)`), a paused block by 0. Conversions to µs happen only when publishing `CoreClockState` and when comparing with event times (in `Double`). No rounding accumulates: an onset 10 minutes in lands on the same frame as one 1 s in (T2.1).

**Block records.** After each accepted write, HKAudio publishes a record for that block into `AudioClock`'s 256-entry ring (1.37 s, well beyond the ≈ 140 ms write-to-DAC path on the speaker [M:rev] and the 250–400 ms of Bluetooth): `(F_k = block start frame, S_k = song µs at block start, r_k, playing_k, epoch_k, generation_k, registration_k)` plus the head index. Records and the anchor are `AtomicLongArray` slots under per-record versions (§2.1 rule 2); the anchor `(tsFrame, tsNanos)` is itself a two-long record with its own version.

**Timestamps.** Every 16 blocks (85 ms), and while idle, HKAudio calls `AudioTrack.getTimestamp`. A timestamp is **accepted** only if its frame position advanced, its `nanoTime` is newer than the anchor's, and the implied rate lies within ±0.5% of the running fit `fs_fit` (least squares over the last 32 accepted pairs, seeded with 48,000); otherwise it is counted in `tsRejected` and the old anchor stays. Each accepted timestamp logs `HKClock lat=` (`framesAccepted − H(now)`, ms), which is also stored per route class as that route's **latency allowance**. Until the first accepted timestamp of a session, the clock runs on an **estimate**: `tsFrame = framesAccepted − latencyAllowance(route)`, `tsNanos = now`, re-published every block (`fromTimestamp = false`). The allowance defaults to 6,720 frames (140 ms) for the speaker and wired routes and 14,400 frames (300 ms) for Bluetooth until measured.

**Reading, for an instant n** (`AudioClock.sample`, allocation-free, any thread):
```
H(n) = tsFrame + (n − tsNanos) · fs_fit / 1e9                         // frame reaching the DAC at n
H(n) = min(H(n), newestF + BLOCK)                                    // nothing beyond the last written block is known
find the newest record k with F_k ≤ H(n)   (walk back ≤ 256; retry ≤ 3 on a version change, else keep the previous sample)
if H(n) is older than the oldest record: use the oldest record, count clockMiss
S(n) = playing_k ? S_k + (H(n) − F_k) · r_k · 1e6 / 48000 : S_k
out.songUs = S(n); rate, playing, epoch, generation, registration from record k; session; heardFrame = H(n)
```
This is exact across pause (the picture stops when the sound stops), seek (the old position is shown until the new audio is heard) and tempo changes; a PAUSE or SEEK not yet written can never be extrapolated through.

**What the renderer does.** In `onDrawFrame` it samples at `n = max(frameTimeNanos, System.nanoTime()) + displayLeadMs(route)` (the Choreographer time can be a vsync or more stale when the GL thread was busy; `late = nanoTime − frameTimeNanos` is shown on the debug line and frames with late > 8 ms are counted), reads `EnergyRing.read(heardFrame, epoch)`, and passes the sample through the **`VisualClock`**, which keeps the picture monotone within a `(session, epoch, generation)` and gives each frame its exposure window:
```
on a change of session, epoch or generation, or on the first frame:  t = raw, window empty, reseed
expected = (n − n_prev) · r  (0 while paused);  d = raw − t_prev
d < −60 ms or d − expected > 250 ms   → reseed at raw (a latency step, a Bluetooth switch, a skipped stretch)
d < 0                                 → t = t_prev                              (hold until real time catches up)
0 ≤ d ≤ expected                      → t = t_prev + d                          (follow; covers underruns: raw is clamped, so t stops)
d > expected                          → t = t_prev + expected + 0.1 · (d − expected)   (slew forward corrections at 10% per frame)
exposure: span = clamp(t − t_prev, 0, 100 ms); w = max(w_prev, t + span / 2); window = (w_prev, w]   (empty after a reseed and while paused)
```
Every contact falls in exactly one frame's window, centred on that frame on average, whatever the frame spacing (§5.7). `MechanicsEvaluator.evaluate` computes each key's pose from its **governing note** (§5.7): the hammer reaches the string exactly at `onUs`, the instant the audio thread's sampled onset sounds [R:mechanics §0, §1.2]. Mechanical durations are real time, so they are converted to song time with `× r`.

**Sample-accurate events inside the block.** At the start of a block the `Sequencer` dispatches every event whose output frame offset `k = round((evSongFrames − S₀) / r)` (from the block start) is below `BLOCK + HK.LOOK_FRAMES` (1,280 frames). Real-time offsets are added in **output frames**, independent of the rate: the 4′ voice of a harpsichord note starts `round(staggerMs(vel) · 48)` frames before its 8′ voice; a damper lands `round(damperLagMs · 48)` frames after its `EV_KEY_UP`. A voice starts reading frame 0 of its region at output frame `k − onsetOut` (`onsetOut = round(onsetFrame / rate)` from the KeyMap), so its sampled attack lands exactly on the event frame; start delays may span several blocks. **State events** (key-up, latch, pedal noise, damper landings) are queued with their absolute output frame and **applied only in the block that contains that frame**. The lookahead exists for three things only: onsets that start before their frame, the 4′ stagger (≤ 451 frames) and steal-ahead (§3.14).

**Transport across pending work.** On PAUSE, SEEK, SET_PERF and SET_BANK every voice whose start delay has not elapsed returns to IDLE **without a fade** (it has produced no output), and queued state events are dropped. On PAUSE the sequencer cursor also **rewinds to the lowest event index that has not taken effect** (a queued state event or a note-on whose voice had not started); on resume, note-ons whose voices had already started are skipped once (a ≤ 64-entry set built at the pause). Damper-landing countdowns count playing frames only. So a pause issued 1–255 frames before an onset is heard only after resume, exactly when the resumed clock reaches `onUs`, and never inside the pause fade (T2.7).

**Pre-roll.** Every `Performance` begins 400 ms before its first event (`PRE_ROLL_US`), so the key of a v20 note at t = 0 can travel its full 230 ms × 1.05 (upright) × 1.5 (maximum tempo) = 362 ms before it sounds [P:delivery §2.4]. Displayed times subtract it.

### 2.6 How SessionController wires a session

`SessionController` (WP12, pure) does everything below on main; `AppController` (WP0) only forwards Android events to it.
1. **App start** (`HammerklavierApp`): `Wiring` builds the singletons; `SessionController` loads `Settings`; HKLoader loads the `LibraryModel`; `KitManager.open(GRAND)` starts voicing; the companion server and governor start; the activity shows the title card. On the first launch of an APK version, `EngineBench` runs for 2 s silently on the title card and stores the Q0 voice cap (§3.14).
2. **KIT_PLAYABLE:** build the grand `KeyMap` for its tuning (HKLoader) → `audio.setBank(bank, keyMap, profile.withLastDamper(bank.info.lastDamper))`; the card offers Enter.
3. **playMovement(id, instrument?, startUs, shelfId):** HKLoader reads bytes, compiles a `Performance` (generation + 1) with the effective profile and the work's `velocityPolicy`; main: `audio.setPerformance(perf, startUs, autoPlay = true)`, `render.setPerformance(perf, profile)` (the renderer keeps the previous Performance bound until the clock reports the new generation, so the old piece's keys keep moving while it is still audible), `render.setTitle(catalogue title)`, playlist built from `shelfId`, recently played updated, `UiEvent.MOVEMENT_STARTED`, MediaSession metadata; HKLoader then pre-compiles the next playlist item.
4. **View change:** `render.setView(v, f)` (dip) and, at the same time, `RoomAcoustics.design(venue.geometry, placement, anchors.soundSource, listener(v, f), mix.reverb, benchDistance, bank.info.embeddedRoomDb)` → `audio.setRoom(design, 500)`.
5. **Instrument switch:** `audio.pause(30)` → `kits.open(new)` (instant if cached; else playable after its first unit) → KeyMap → new Performance from the same bytes (HKLoader) → `audio.setBank` → `audio.setPerformance(perf, startUs = −1, autoPlay = wasPlaying)` (the engine resumes at its **own** frozen song position, so no note between the heard and the written position is struck twice) → new room design (placement differs). Independently, `render.setInstrument(id, look, bank.info.lastDamper)` and `render.setPerformance(perf, profile)`: the renderer builds the scene on HKLoader and swaps it under its next dip, or on the first frame after a display rest. Audio never waits for GL.
6. **Tuning change:** new `KeyMap` on HKLoader → `audio.setKeyMap` (new notes only). **Registration change:** `audio.setRegistration(mask)` only (through the CommandRing); the engine applies it at a block boundary and publishes it in `CoreClockState`, so the jacks slide in the picture when the change is heard.
7. **Thermal level:** `QualityLadder.of(level, q0Cap)` → `audio.setQuality`, `render.setQuality`, the window brightness cap, `kits.setPlaybackHint`; Q3 → `UiEvent.REST_ON` and the status line.
8. **Head yaw:** `GazeCamera`'s sensor callback writes yaw and angular velocity to `HeadPose`; HKAudio reads it every block and predicts it forward by the measured output latency (§3.12). Nothing is polled through main. **Every 1 s:** stats, HUD facts, `nowPlayingJson` snapshot, resume point every 5 s.
9. **Sync test** (`UiAction.SyncTest`): `SYNC_CLICK` is compiled like any score and played with `setPerformance`; `render.setSyncFlash(true)` draws the disc from `pose.flash[69]`. There is no engine-level click generator.

---

## 3. Audio engine

### 3.1 Output stage (WP4 `AudioOutput`, `TrackSupervisor`, `RouteMonitor`)

```kotlin
AudioTrack.Builder()
  .setAudioAttributes(AudioAttributes.Builder().setUsage(USAGE_MEDIA).setContentType(CONTENT_TYPE_MUSIC).build())
  .setAudioFormat(AudioFormat.Builder().setEncoding(ENCODING_PCM_FLOAT).setSampleRate(48000)
                  .setChannelMask(CHANNEL_OUT_STEREO).build())
  .setTransferMode(MODE_STREAM)
  .setBufferSizeInBytes(max(minBytes, HK.TRACK_FRAMES * 2 * 4))          // 4096 frames = 85.3 ms
  .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_NONE)                   // normal mixer, 960-frame periods
  .build()
// HKAudio: Thread.MAX_PRIORITY + Process.setThreadPriority(THREAD_PRIORITY_URGENT_AUDIO)
// clock.reset(); energy.reset(); cursors.clearAll(); trackBaseFrame = framesAccepted
// play() FIRST, then prime one silent block (SpyHunt rule: priming before play() can hang at MAX_PRIORITY)
// loop { ring.drain(16, core); core.render(out, framesAccepted); n = write(out, 0, 512, WRITE_BLOCKING)
//        n < 0 → supervisor; else framesAccepted += n / 2 (a short write re-writes the rest next); publish… }
```

- **Why `PERFORMANCE_MODE_NONE`:** everything is sequenced, so the only latency a user feels is tap → pause, already 300 ms from tap disambiguation. The normal mixer drains 960 frames per 20 ms, so HKAudio wakes ≈ 50 times a second and renders ≈ 4 blocks per wake instead of ≈ 250 wakes on the FastMixer, and the 85 ms buffer absorbs scheduling jitter when the device is warm. A/V sync comes from `getTimestamp`, not from low latency. The self-test logs the routed device, its type and the output the track landed on (`getRoutedDevice()`, the `AudioTrack` latency and buffer size) so a landing on the `DEEP_BUFFER` output [M:rev] is visible.
- **Fallback:** if the builder throws, or T-UND shows glitches on this path, build with `PERFORMANCE_MODE_LOW_LATENCY` and the same 4096-frame request, and enable `LatencyTuner` (grows `bufferSizeInFrames` by one block per new underrun, up to 16 blocks). If no track can be built after two attempts, the app runs silently with visuals driven by `FakeClock` and the status line reads `Audio output unavailable`.
- **Publishing per block:** `AudioClock.publishBlock(blockStartFrame, core.clockState)`, `EnergyRing.write(blockStartFrame, epoch, lanes)`; every 16 blocks (and every wake while idle) `getTimestamp` → `publishTimestamp`, `getUnderrunCount` → stats. Until the session's first accepted timestamp, `publishEstimate(framesAccepted, latencyAllowance(route), now)` every block.
- **Headroom and self-protection:** at each wake `queued = framesAccepted − (trackBaseFrame + getPlaybackHeadPosition())`. If its minimum over a rolling 10 s stays below `HK.HEADROOM_MIN_FRAMES` (1,536 frames, 32 ms), AudioOutput sends `Cmd.VOICE_CAP` with the cap − 8 (not below 32), posts `onOverload` and logs it; it restores one step after 60 s with the minimum ≥ 3,072 frames. Render time is logged, never used for this decision: the CPU governor settles at 1.1–1.5 GHz for a steady ≈ 45%-duty thread [M:rev], which stretches blocks harmlessly.
- **Kept from SpyHunt:** every voice is killed on `start()` so a stale tail cannot replay; `stop()` joins ≤ 350 ms with a generation guard; one app-owned AudioTrack (SoundPool and looping MediaPlayer are banned on this device [R:engine_reuse §4.3]).
- **Render errors:** an exception inside `core.render` is caught, the block is written as silence and `core.reset()` runs once; a second exception within 10 s stops audio and posts `onEngineError(AUDIO_STOPPED, reason)`. The app never crashes from HKAudio.
- **Output errors (`TrackSupervisor`):** `write` returning `ERROR_DEAD_OBJECT` or `ERROR_INVALID_OPERATION` (audioserver restart, some Bluetooth switches), or 16 consecutive failed `getTimestamp` calls while playing, → release the track and build a new one (at most 3 times within 10 s): `clock.reset()`, `energy.reset()`, `trackBaseFrame = framesAccepted`, `play()`, prime, continue from the engine's song position (the engine is not reset). After the third failure: `onEngineError(AUDIO_STOPPED, "output lost")`.
- **Idle and park:** paused (or nothing loaded), no voices, and `room.tailActive == false` → the loop writes zeros without calling the DSP. After `HK.IDLE_PARK_MS` (10 s) of that, `track.pause()` and HKAudio parks on a condition variable, so AudioFlinger's mixer, the HAL and the speaker amplifier can reach their 2 s standby [M:rev] and the glasses can suspend. **Un-park** on PLAY, SEEK, SET_PERF or a bank change: `clock.reset()`, `track.play()`, prime one silent block, estimate until the next accepted timestamp. Parking is invisible: the paused picture is the last sample, and the new session's first frame re-seeds it.
- **Focus and route:** `AudioFocusGate` requests `AUDIOFOCUS_GAIN` on play; `CAN_DUCK` → `setDuck(0.3)`; transient loss → pause; permanent loss → pause and park. `RouteMonitor` uses `track.addOnRoutingChangedListener` and `track.getRoutedDevice()` (not device-added callbacks): built-in speaker → `SPEAKER`; wired headset, headphones, USB → `WIRED`; Bluetooth A2DP/LE → `BLUETOOTH` with key `bt:<address>`. A change sends `Cmd.ROUTE` (with the route's latency allowance), switches the voicing (§3.13), and notifies `onRouteChanged` so the renderer uses that route's display lead.
- **Display off:** with the display asleep the app is in the top-sleeping group: `SCHED_SP_BACKGROUND` means HighEnergySaving, LowIoPriority and 40 ms timer slack [M:rev]. HKAudio blocks in `write` and is unaffected by slack; HKPrefetch is scheduled by song time with slack margin (§3.4). T-UND and T-PF run with the display asleep (§8.4); if they fail, `HK.USE_FG_SERVICE` enables `PlaybackService`, a `mediaPlayback` foreground service with a notification, started while the activity is resumed (Android 12 forbids starting it from the background) and stopped on pause or at the end of the playlist item.

### 3.2 Sample formats and sizes

| Stage | Format | Grand HD (16 layers) | Grand standard (6 layers) | Upright (3 layers) | Harpsichord 8′+4′ |
|---|---|---|---|---|---|
| APK: `assets/instruments/<id>/u/<unit>.opus` (stored uncompressed, `noCompress += "opus"`), plus `map.json` and `env.bin` | **One Ogg Opus stream per decode unit**: the unit's regions concatenated, each behind 40 ms of silence, their stream start frame and length in `map.json`; 48 kHz stereo, **112 kb/s VBR**, `-application audio -frame_duration 60`. Each region peak-normalised to −3 dBFS before encoding; `gainDb` restores its level | 18 streams (16 layers, releases, pedals); 572 regions, ≈ 4,150 s of audio incl. gaps, **≈ 58 MB** [E] | 8 streams; 272 regions, 1,586 s, ≈ 22.2 MB | 5 streams; 166 regions, 921 s, ≈ 12.9 MB | 3 streams (8′, 4′, releases of both); 108 regions, 336 s, ≈ 4.7 MB |
| PCM cache `noBackupFilesDir/pcm/<id>-<sha8>.pcm` (`sha8` = the first 8 hex digits of `map.json`'s `sha1`) | 16-bit LE interleaved stereo, 48 kHz; every region starts on a 4 KiB boundary | **≈ 755 MiB** | 288 MiB | 167 MiB | 61 MiB |
| RAM | the cache is **mmap'd read-only**: clean, file-backed, evictable, never on the Java heap. A mapping lives until its buffer is garbage-collected (Java cannot unmap safely), so it is treated as process-lifetime; ≈ 1.3 GiB of virtual space for all kits is harmless on this arm64-only device | resident 80–200 MiB while playing [E] | 60–150 MiB | 40–100 MiB | 20–50 MiB |

Trim model (from [R:sampled-instruments §6]): sustains 14 s at A0 falling linearly to 3 s at C8 (upright 12 → 3 s, harpsichord 8 → 3 s), or earlier where the 50 ms RMS falls below −75 dBFS; releases 0.4 s (grand), 1.0 s (upright), 0.6 s (harpsichord); pedal noises 4.5 s down, 0.5 s up; every region ends in a 300 ms raised-cosine fade. The same trim model feeds the voice-demand simulation (§3.14). Total cache with HD: ≈ 983 MiB; standard: ≈ 516 MiB (19 GB free [M]). `allowBackup="false"` plus MathCosmos's backup-rule XMLs keep the cache out of backups.

Rejected: 16-bit FLAC in the APK (≈ 285 MB for 6 layers); float PCM in RAM (576 MiB for the 6-layer grand; heap limit 192 MB, `low_ram`); decoding Opus per voice at play time (one decoder per voice is impossible; the Qualcomm FLAC decoder allows only 2 instances [R:sampled-instruments §5]); one Opus file per region (572 files cost 2.4–4.9 s of extractor and codec setup alone, and 20 ms packets triple the Codec2 work items).

### 3.3 Voicing: decode once, progressively, resumably, never while playing (WP4)

- **Where and how:** HKVoicer drives `c2.android.opus.decoder` by name (falling back to `createDecoderByType("audio/opus")`). The decoder runs in the **`media.swcodec` process** (pid 1007 [M:rev]), not the app, so HKVoicer's priority does not throttle it; the voicer's duty cycle is therefore measured in wall time per stream and budgeted against both processes (§8.4 counts `media.swcodec` too). One `MediaExtractor` per stream (`assets.openFd(path)` → `setDataSource(fd, off, len)`, possible because the asset is uncompressed) and **one codec reused across streams** with `stop()`/`configure()`/`start()` (every stream has the same format; `flush()` is not relied on across OpusHeads). While nothing plays and the kit is not yet playable, **two** codec instances decode the first playable set in parallel.
- **Writes without writeback storms:** output goes through a 64 KiB direct buffer with `FileChannel.write(buf, pos)` into the pre-sized cache. After every ≤ 4 MiB written, `pcmChannel.force(false)` and a sleep of ≥ 2× the write time, so dirty pages never pile up on the eMMC [M:rev].
- **Cache files:** `<id>-<sha8>.pcm` with a header padded to a 4 KiB multiple (magic `HKPCM2`, schema, the SHA-1 of `map.json`, region count, per-region byte offset and frame count), pre-sized with `setLength` so the mapping never changes; `<id>-<sha8>.ready` = 8-byte little-endian ready mask + 64 CRC32s (one per unit) + the `Settings.Global.BOOT_COUNT` at the last write; `<id>-<sha8>.ok` written last. **Completing a unit:** `pcmChannel.force(false)` → compute its CRC32 → write `.ready` to a temp file, `force(true)`, rename. `/data` is f2fs with `fsync_mode=nobarrier` [M:rev], so after an unclean reboot a `.ready` bit alone proves nothing: **on open**, if `.ok` is missing or `BOOT_COUNT` differs from the stored value, the newest two ready units (each ≤ ≈ 50 MiB) are CRC-checked (≈ 0.3 s) and any that fail are re-voiced. Stale files for another SHA are deleted. A new APK with a changed kit re-voices only that kit.
- **DecoderProbe:** once per `Build.FINGERPRINT`, decode `assets/instruments/probe.opus` (0.5 s of silence with a one-sample click at frame 4800, encoded with the kit settings) and record `offset = argmax|x| − 4800` (expected 0 if the decoder drops the 312-frame pre-skip, 312 if not). `KitDecoder` removes `offset` frames from the start of every stream (prepends zeros if negative), then cuts each region at its `streamStart` for exactly `frames`. |offset| > 960 is a probe failure → `SynthBank` fallback.
- **Units and order.** Every sustain region belongs to a decode unit; releases are unit 62, pedals unit 63 (one stream each). The kit is **playable after its first unit plus releases and pedals** (≈ 300 s of audio for the grand). `KeyMapBuilder` never returns an undecoded region; it substitutes the nearest ready layer on the level curve (§3.5).

  | Kit | Decode order (units) |
  |---|---|
  | Grand HD | v10, releases, pedals, v13, v7, v16, v4, v1, v8, v11, v5, v14, v2, v9, v12, v6, v15, v3 (after six units it equals the standard kit) |
  | Grand standard | v10, releases, pedals, v13, v7, v16, v4, v1 |
  | Upright | mf (vl1), releases, pedals, f (vl2), pp (dyn1) |
  | Harpsichord | 8′, releases (both stops), 4′ |

- **Policy (`VoicingScheduler`):** voicing runs **only when nothing plays**: on the title card, while paused, between movements, and with the display asleep while paused; it needs `QualityProfile.voicingAllowed` (Q0) and battery < 37.5 °C, at background priority, one stream at a time after the playable set. Playback start makes it yield within one region (≤ 50 ms), `force()` what it wrote and park. Other kits voice after the active one and after ≥ 10 s idle. A killed decode resumes at the first unit not in `.ready`. The governor's temperature keeps updating with the display off (§5.11).
- **Storage check:** before decoding, require free space ≥ remaining cache bytes + 64 MiB. Otherwise decode a **reduced grand** of units v4 and v13 only (present in both kits) and show `Reduced grand: low storage`.
- **Speed is measured, not assumed.** The M1 stub set includes a 60 s Opus asset with the kit encoding; `--ez bench true` reports its decode speed (× real time) and the per-stream setup cost, and T-DEC repeats it on the real kits. The table's estimates assume ≥ 60× real time and ≤ 40 ms setup per stream.

| Kit | Full decode [E] | Playable after [E] | Test |
|---|---|---|---|
| Grand HD | ≈ 70 s | ≈ 5 s | T-DEC: playable ≤ 8 s, complete ≤ 120 s of idle time |
| Grand standard | ≈ 26 s | ≈ 5 s | playable ≤ 8 s, complete ≤ 45 s |
| Upright | ≈ 15 s | ≈ 6 s | complete ≤ 30 s |
| Harpsichord | ≈ 6 s | ≈ 4 s | complete ≤ 12 s |

If decoding measures below 25× real time, the documented fallback is a pure-Java Opus decoder (Concentus, BSD) in the voicer, which needs a separate download approval; nothing else changes.

### 3.4 Keeping HKAudio free of page faults

1. **On kit open:** HKLoader pre-reads the first 150 ms of every ready region (≈ 16 MiB for the HD grand) with positional reads. Whole-kit `MappedByteBuffer.load()` is **not** used for the pianos: `MemAvailable` ≈ 2.6 GB [M:rev] would admit it, but it reads 755 MiB (3–4 s on eMMC, competing with the first notes) and puts the RSS near 1 GB on a `low_ram` device. Only a kit ≤ 96 MiB (the harpsichord) may be preloaded, and only when `!MemoryInfo.lowMemory` and `availMem − mappedBytes > 4 × MemoryInfo.threshold`.
2. **HKPrefetch, scheduled by song time while playing:** it wakes whenever its lookahead would fall below 1.5 s plus 100 ms of slack (the display-off timer slack is 40 ms [M:rev]), at most every 50 ms. For each active voice it reads `(region, frame)` from the `VoiceCursorBoard` and prefetches the next 300 ms; for every note with `onUs ∈ [S, S + 1.6 s]` it asks the **same `KeyMap`** the audio thread uses which region(s) will sound and prefetches their first 400 ms plus the first 200 ms of the release region. After a seek, the heads of the next 1.6 s at once. **Prefetch is `SampleReader.prefetch`: positional `FileChannel.read(buf, pos)` in 64 KiB chunks into one reused direct buffer.** The thread is in Native state during the syscall, so a slow eMMC read never holds up an ART suspend point (a mapped-memory touch would stall GC checkpoints for every thread, HKAudio and GLThread included); the page cache fills the same way, and HKAudio then meets at most minor faults (with fault-around).
3. **Measurement:** every bulk read on HKAudio is timed (`slowReads` > 1 ms), and `PerfProbe` reads the audio task's `majflt` (field 12 of `/proc/self/task/<tid>/stat`). T-PF requires Δmajflt = 0 and slowReads = 0 after the first 60 s, with the display on and asleep; T-UND-FIRSTRUN covers the first minutes after a cleared cache.
4. **If T-PF fails:** double the lookahead (3 s) and pre-read 250 ms heads. If it still fails, the documented v1.1 fallback is realism's design: the first 250 ms of every region resident in heap `ShortArray`s and tails streamed by a thread at audio priority into per-voice rings.

### 3.5 KeyMap: layers, velocity, frequency-based sample choice (WP4 `KeyMapBuilder`, pure)

**Velocity layers.** `velRef` is a property of the sample: the midpoint of its own split in the source mapping, the same in both grand kits.

| Kit | Mode | Layers and splits (velRef) |
|---|---|---|
| Grand HD | HARD (one sample per note; no crossfade) | Salamander's own 16 splits: 1–26, 27–34, 35–36, 37–43, 44–46, 47–50, 51–56, 57–64, 65–72, 73–80, 81–88, 89–96, 97–104, 105–112, 113–120, 121–127 [R:sampled-instruments §1.1]; velRef = midpoints (14, 31, 36, 40, 45, 49, 54, 61, 69, 77, 85, 93, 101, 109, 117, 124) |
| Grand standard | XFADE ±4 velocity | v1 1–30, v4 31–46, v7 47–64, v10 65–88, v13 89–112, v16 113–127; velRef = the HD midpoints of those layers: 14, 40, 54, 77, 101, 124 |
| Upright | XFADE ±6 | pp (dyn1) 1–40, mf (vl1) 41–83, f (vl2) 84–127; velRef 20, 62, 105 |
| Harpsichord | single layer per stop | – ; ±1 dB deterministic hash of the note index (engine) |

**The level curve (pipeline, `map.json` `levelCurve`).** Natural recorded levels are restored (normalisation is undone by `gainDb`). For each root, the attack loudnesses (A-weighted RMS 0–150 ms after the onset) of its layers are fitted with isotonic (non-decreasing) regression followed by a 3-point smooth, then a 3-tap median across roots, the correction clamped to ±2.5 dB. The resulting layer-centre loudnesses, anchored at each layer's velRef, define a **piecewise-linear target level in dB versus velocity** (extended beyond the outer centres at 0.39 dB per step [R:mechanics §1.1]). The trim of velocity v on layer L is `target(v) − loudness(L)`, so the level is continuous across every split by construction; the report lists each segment's slope and flags any below 0 or above 1 dB/step for L-3. If a kit's spread between its softest and loudest layer centres is < 12 dB (a normalised pack), the target follows 0.39 dB/step.

**Crossfade law.** The pipeline measures the correlation of adjacent onset-aligned layers over 0–300 ms per root; a boundary whose median correlation is above 0.5 (expected: same key, same modes, similar phases) uses **equal gain** (wA + wB = 1), otherwise equal power; stored per boundary in `xfadeLaw`. T4.2 checks that the summed level across each zone stays within ±0.5 dB of the level curve.

**Frequency, not note number, chooses the sample.** The pipeline separates the recording's pitch standard from its tuning shape: `aOffsetCents = fit(69)` and `stretchCents[k] = fit(k) − aOffsetCents` (the stretch *shape*, 0 at A4), where `fit` is the smooth curve through the regions' measured pitches (§6.5); `recordedAHz = 440 · 2^(aOffsetCents/1200)` is reported and used nowhere else. Each region carries its measured native pitch. For key k, with `c(k) = TuningSpec.keyCents(k)` (pitch standard + temperament vs A440 ET):
```
targetCents(k) = 100·k + c(k) + stretchCents[k]
nativeCents_r  = 100·root_r + pitchCents_r          // measured sounding pitch of region r (partial-series fit, §6.5), re A440 ET
region(k)      = argmin over the layer's candidate regions of |targetCents(k) − nativeCents_r|   (ties → smaller |k − root|)
rate(k)        = 2^((targetCents(k) − nativeCents_r) / 1200)
f0Hz[k]        = 440 · 2^((targetCents(k) − 6900) / 1200)   // what the voices sound at; the combs use it
onsetOut(k)    = round(onsetFrame_r / rate(k))              // output frames from voice start to the sampled onset
```
So at A415 the harpsichord (and the pianos) play the sample one key lower with a shift of about −1.27 cents (plus the temperament and the shape difference), exactly as a transposing harpsichord does, and the timbre is kept [P:realism §3.4]; and if the harpsichord turns out to have been recorded at A415, the default setting plays its own roots almost unshifted and A440 uses the root one key higher: whatever `recordedAHz` is, the output pitch is right. All `pow` calls happen here, once per tuning or readiness change, on HKLoader. Largest shifts: grand ±1.5 semitones (roots every 3), upright mf/f ±1, upright pp ±2, harpsichord 8′ ±1 everywhere (keys 29–89), 4′ up to ±2 (its 26 roots are irregular, taken from the VCSL SFZ) and up to +5 on keys 85–89.

**Borrowed regions (harpsichord 8′, keys 86–89).** A stop's candidates are its own regions plus, where its own best shift would exceed one semitone, the regions of another stop flagged `borrowable` in `map.json`: the 4′ roots at keys 74 and 76 sound pitches 86 and 88, so 8′ keys 86–89 shift by at most ±1 semitone. A borrowed region gets the seam trims fitted by the pipeline at the key-84 boundary: `seamGainDb` (in `KeyMap.gain`) and a fixed one-pole low-pass `seamLpHz` (in `KeyMap.lpHz`, applied by the voice's spectral stage). Key 85 keeps its own 8′ root (+1 semitone).

**Not-yet-voiced layers:** a layer whose unit is not ready maps to the nearest ready layer by |velRef difference| (ties → louder); its gain follows the level curve (`target(v) − loudness(used layer)`), clamped ±6 dB. A 4′ unit not ready → no 4′ voice.

**Releases and pedals:** releases per (stop, key): the grand's `rel<n>` exact per key, the upright's nearest of 45 roots, the harpsichord's per-stop jack falls; the same rate rule. Pedal noises: round-robin lists (grand 2 + 2, upright 4 + 4); `pedalGain` from the kit (grand −20 dB, the SFZ value).

### 3.6 Voices and the inner loop (WP2)

```kotlin
class Voice {                                      // preallocated: VOICE_CAP_MAX 128 + 64 kill slots + 12 noise slots = 204
    @JvmField var state = IDLE                     // PENDING (start delay not elapsed), PLAYING, FADING, KILL, RELEASE_NOISE, PEDAL_NOISE, HANDOFF
    @JvmField var key = 0; @JvmField var stop = 0; @JvmField var region = -1; @JvmField var bus = DRY  // or SOFT
    @JvmField var note = -1; @JvmField var vel = 0; @JvmField var startFrame = 0L
    @JvmField var pos = 0L                         // 32.32 fixed-point frames relative to winStart
    @JvmField var inc = 0L                         // rate · 2^32
    @JvmField var winStart = 0
    @JvmField val win = ShortArray(2 * (1024 + 4)) // 1024 stereo frames + 1 guard before, 3 after
    @JvmField var startDelay = 0                   // output frames of silence before frame 0 is read (may span blocks)
    @JvmField var level = 0f                       // LEVEL = KeyMap.gain × velGain × hash × damp × fade (linear, excluding 2^-15)
    @JvmField var g = 0f; @JvmField var gTarget = 0f     // per-sample ramped gain = LEVEL × 2^-15 (the only place the short scale appears)
    @JvmField var damp = 1f                        // damping / fade envelope, updated per block
    @JvmField var keyDown = true
    @JvmField var attackDb = 0f; @JvmField var levelDb = 0f     // for culling, stealing, gating, energy
    @JvmField var lpA = 0f; @JvmField var lpFc = 18000f; @JvmField var lpL = 0f; @JvmField var lpR = 0f; @JvmField var spectral = false
    @JvmField var selfRow = -1                     // key − 21 when this key's comb is gated open or soft-fed this block
}
```

Per block, per voice: (1) if `pos + 256·rate ≥ WIN − 3`, re-centre the window with one bulk `SampleReader.read` (≈ 4 KiB memcpy) and shift `pos`; publish `(region, frame)` to the cursor board with `lazySet`; (2) `level = KeyMap.gain · velGain · hash · damp · fade`, `gTarget = level · 2⁻¹⁵`, `dg = (gTarget − g)/(256 − startDelay)`; (3) the loop:

```kotlin
var p = pos; var gg = g
for (i in startDelay until 256) {                                  // Hermite (Q0, Q1)
    val ip = (p ushr 32).toInt(); val fr = (p and 0xFFFFFFFFL).toFloat() * 2.3283064e-10f
    val b = (ip + 1) shl 1
    val lm = win[b - 2].toFloat(); val l0 = win[b].toFloat(); val l1 = win[b + 2].toFloat(); val l2 = win[b + 4].toFloat()
    val rm = win[b - 1].toFloat(); val r0 = win[b + 1].toFloat(); val r1 = win[b + 3].toFloat(); val r2 = win[b + 5].toFloat()
    val lc1 = 0.5f * (l1 - lm); val lc2 = lm - 2.5f * l0 + 2f * l1 - 0.5f * l2; val lc3 = 0.5f * (l2 - lm) + 1.5f * (l0 - l1)
    val rc1 = 0.5f * (r1 - rm); val rc2 = rm - 2.5f * r0 + 2f * r1 - 0.5f * r2; val rc3 = 0.5f * (r2 - rm) + 1.5f * (r0 - r1)
    var sl = (((lc3 * fr + lc2) * fr + lc1) * fr + l0) * gg
    var sr = (((rc3 * fr + rc2) * fr + rc1) * fr + r0) * gg
    if (spectral) { lpL += lpA * (sl - lpL); lpR += lpA * (sr - lpR); sl = lpL; sr = lpR }   // hoisted into a second loop copy
    outL[i] += sl; outR[i] += sr
    if (selfRow >= 0) self[selfBase + i] += 0.5f * (sl + sr)       // hoisted into loop copies too: the key's own mono contribution
    gg += dg; p += inc
}
```
- **Linear** (Q2, Q3): the same loop with two taps per channel. A voice with `inc == 1L shl 32` uses a copy loop without interpolation. The `spectral` and `selfRow` branches are compiled as separate loop copies, not per-sample tests.
- `startDelay` then becomes 0 (or is reduced by 256 if it spanned the block). **Level:** `levelDb = envDb(region, absFrame / 480) + LIN2DB(level)` with `absFrame = winStart + (pos ushr 32)` (the source frame now being read) and `envDb = −envByte / 2`; both by table (`ENV_DB`, `LIN2DB`, 256 entries each). `attackDb` is `levelDb` in the voice's first block after its onset. The voice dies when `levelDb < −80 dBFS` or its region ends. **Energy:** the voice adds `ENV_POW[envByte] · level²` (mean-square re full scale) to its key's lane accumulator.
- **Noise pool:** release and pedal-noise voices use their own 12 slots (not counted in the voice cap); when all 12 are busy, the oldest one takes a kill slot.

### 3.7 Profiles, envelopes and dampers

**Profiles** (`contract/InstrumentProfile`, WP0 fills these):

| Field | Grand | Upright | Harpsichord |
|---|---|---|---|
| lowKey–highKey | 21–108 | 21–108 | 29–89 |
| lastDamper (default; the bank's value wins) | 88 | 90 | 127 (all damped) |
| damperLagMs (derived, real time: the damper lands after note-off) | 18.4 (`KeyReturn.damperLandMs(35)`) | 26.2 (`KeyReturn.damperLandMs(50)`) | 47.6 (`HarpsiTiming.damperLandMs`); release start at the 8′ quill pass, 30.1 ms |
| keyDipMm / keyReturnMs / repeatMinMs / partialReturnMs | 10.16 / 35 / 67 / 12 | 10.0 / 50 / 143 / 40 | 6.0 / 55 (gravity-like) / 60 / 30 |
| blow / let-off / check (mm), actionRatio | 47 / 1.5 / 15, 5.0 | 47 / 3.2 / 16, 4.9 | –, jack = 1.0 × key |
| travelScale | 1.0 | 1.05 | (HarpsiTiming) |
| sustain / sostenuto / soft | yes / yes / UNA_CORDA | yes / no / HAMMER_RAIL | no / no / NONE |
| legatoHold / velocityGain / stops | no / yes / 1 | no / yes / 1 | yes / no / 2 |
| restrike τ (pedal up / down) | 60 / 200 ms | 60 / 200 ms | 30 / 30 ms |
| default tuning | A440 Equal | A440 Equal | A415 Werckmeister III |
| damper T60 `T60d(n)` (s), shipped in `map.json` `damperT60` | formula (the `rel*` files are mechanical noises): `0.12 + 1.2·((88−n)/67)²`: C6 0.12, C4 0.33, C2 0.84, A0 1.3 [R:mechanics §9 R3] | fitted from the release samples' early decay, clamped to 0.5–2× (grand × 1.2) | fitted likewise, clamped to 0.5–2× `0.10 + 0.15·(88−n)/59` |
| free T60 `T60f(n)` (s), for combs and visuals, shipped per stop in `freeT60` | `min(30, 6.24·10^(−0.0275(n−60)))` | fitted from the sustain samples, default grand × 0.8 | fitted per stop from the sustain samples; defaults 8′ `20 · (f/f₂₉)^−0.45` (20 s → 4.2 s at key 89), 4′ `0.8 · 20 · (2f/f₂₉)^−0.45` |
| releaseCarriesTail | no | **yes** | **yes** |

The formulas live once in the pipeline (which writes every array into `map.json`) and once in `contract/InstrumentProfile` for `SynthBank` and the stub kit; a WP11 test checks the stub kit's arrays against the Kotlin defaults within 1%.

**The attack and natural decay are the sample.** The engine adds only damping, once per block:
```
D(k) (damping strength 0..1) = 0                  if k > lastDamper           (grand 89–108 always free [R:mechanics §4.1])
                             = 0                  if keyDown(k)               (NOTE_ON … damper landing: EV_KEY_UP + damperLag frames)
                             = 0                  if sostenuto-latched(k)
                             = pedalDamping(p)    otherwise                   (continuous half-pedal, R12)
damp *= DAMP[k][round(32·D)],   DAMP[k][j] = exp(−6.91 · (256/48000) · (j/32) / T60d(k))    // 128 × 33 table, prepared off-thread
```
- **Spectral damping (Q0, Q1):** the first block a voice sees D > 0.05, its one-pole low-pass engages. The cutoff target is `fcT = 18000 · (min(18000, 6·f0(k)) / 18000)^D`, read from a prepared 128 × 33 table `FCT[k][round(32·D)]`; each block `lpFc = min(lpFc, glide(lpFc → fcT, 40 ms))`; the coefficient comes from a 256-entry log-spaced table. The cutoff **never re-opens**: felt kills the upper partials first, and re-pedalling cannot bring them back [P:realism §3.5].
- **Re-pedalling (R13):** if D returns to 0, `damp` stops falling and the voice continues its natural decay from the current level; the low-pass stays where it is.
- **Handoff for kits whose releases carry the damped tail** (`releaseCarriesTail`: the VCSL upright and harpsichord, whose release recordings contain the damper or jack noise, the damped string and the recording room [R:sampled-instruments §2–3]): at the release trigger with **full** damping (the upright: its damper landing with D = 1 at that frame; the harpsichord, always fully damped: each stop's quill pass), the release voice starts level-matched to the sustain voice (`releaseGain = levelDb(sustain now) − envDb(release onset)`, clamped −30…+6 dB, by table) and the sustain voice crossfades out over 30 ms (`HANDOFF`), as an SFZ release trigger does; the T60d multiplier is used only for partial damping (half pedal, a damper brushing the strings) and for dampers landing from the pedal. The upright's release starts at the damper landing (26.2 ms); the harpsichord's 8′ at its quill pass (30.1 ms) and 4′ at its own (41.4 ms), and the cloth is drawn touching at 47.6 ms, the instant `KeyState` sets D = 1 (comb gate closed).
- **Calibration (pipeline):** `T60d` is fitted only from the **early decay** of each release sample: the first 10–15 dB after the damper-landing transient (or the level match between the sustain envelope and the start of the release), never the late room tail (the harpsichord takes are `_Far` microphones). A fit outside 0.5–2× the formula fails the build and the report names the room-tail knee. The grand uses the R3 formula, checked by ear (L-3).

### 3.8 Pedals

| Pedal | Source | Audio | Visual (same curve) |
|---|---|---|---|
| Sustain (CC64) | `perf.sustain`, shaped by `PedalShaper` (§4.3): a switch-type down edge ramps 0 → 1 over 70 ms crossing 0.33 at the MIDI time (starts 23 ms early); an up edge ramps 1 → 0 over 60 ms crossing 0.33 at the MIDI time; overlapping ramps make a dip where the dampers brush the strings. The curves are in song time, read identically by audio and visuals, so they agree at every tempo (at 50 % the pedal moves at half speed, as a player's foot would) | `D` via `pedalDamping(p)`; comb gating via D | pedal angle 5°·p; dampers lift from ⅓ of travel |
| Sostenuto (CC66), grand | `EV_LATCH` events: at each rising edge through 0.5 the builder latches the keys whose dampers are up (held keys, plus every key ≤ lastDamper only if sustain ≥ 0.55, i.e. dampers fully clear, `PedalMotion.CLEAR`); cleared on the falling edge | latched keys get D = 0 | middle pedal down; latched dampers stay up; sostenuto rail 45° → 90° |
| Una corda (CC67), grand | `perf.soft` (60 ms ramp crossing 0.5 at the event) | a note-on with `soft ≥ 0.5` goes to the **soft bus** (−2.5 dB, high shelf −4 dB at 2.2 kHz on keys with ≥ 2 strings, −1.5 dB on single-strung keys 21–28; one shared stereo biquad per class) **and, on keys with ≥ 2 strings only, feeds its own comb** at `UNA_CORDA_SEND` (the unstruck string driven through the bridge; calibrated alone, expected ≈ −48 dB, §3.11) | keyboard and action slide 2.5 mm toward the treble over 60 ms |
| Soft (CC67), upright | `perf.soft` | soft bus: −4 dB, shelf −2 dB at 3 kHz; no comb feed | hammer rail 22 mm toward the strings; hammer rest moves with it |
| Upright middle pedal | ignored | – | static |
| Harpsichord | CC64 becomes legato hold in the builder (§4.4); CC66/67 ignored; pedal curves empty | – | no pedals drawn; the `finger-pedalled` pill shows |

### 3.9 Re-strikes, release samples and pedal noises

- **Re-strike (R10):** a note-on for a key that already has voices sends them to `FADING` with τ = `restrikeTauUpMs` if the pedal is below 0.33, else `restrikeTauPedalMs` (per-block multipliers from `DecayTables`). At most 3 voices per key; a fourth sends the oldest to a kill slot (5 ms fade). The audio re-strikes at the MIDI rate even below the profile's `repeatMinMs`: it plays what the file says, and the picture draws the fastest physical repetition (§5.7).
- **Release samples fire when the damper actually lands** (EV_KEY_UP + `damperLagMs · 48` output frames): for the grand, a release voice starts at that frame if `key ≤ lastDamper`, the key is not latched and `pedalDamping(p) > 0.5`, with gain `relGain · VEL07[vel] · max(0.25, EXPAGE[age])` (tables: `vel^0.7`, 128 entries; `e^(−age/3 s)`, 64 entries over 0–6 s; age = key-up − onUs); if the damper does **not** land (pedal held, latched, undamped treble) the release plays at −9 dB: the key-return noise only. For tail-carrying kits (upright, harpsichord) the release is the handoff of §3.7 when the damping is full; when the damper does not land, no release plays (its recording is inseparable from the damped tail). The harpsichord plays each active stop's jack-fall at every key-up at that stop's quill pass (8′ 30.1 ms, 4′ 41.4 ms, real time), as the handoff of §3.7.
- **When the sustain pedal lifts,** the dampers land together: only the pedal-up noise plays (it contains the collective landing), never 30 separate releases; the strings are damped by the T60d multiplier.
- **Pedal noises (R11):** `EV_PEDAL_NOISE` from `PedalShaper` where the curve crosses 0.33 (down → `pedalD`, up → `pedalU`), round-robin, gain `pedalGain × {0.35, 0.55, 0.8, 1.0}[speedClass]`, at least 150 ms apart (enforced in the builder).
- Release and pedal voices live in the 12-slot noise pool (§3.6), outside the voice cap.

### 3.10 Harpsichord stops, stagger and registration

- 8′ and 4′ are separate voices, both started by `EV_NOTE_ON` (the MIDI time is the 8′ pluck). The 4′ voice starts `round(staggerMs(vel) · 48)` **output frames** earlier (3.5 ms at v127 to 9.4 ms at v0, real time at every tempo): the 4′ jack plucks at 2.6 mm of key travel, the 8′ at 4.2 mm, and the key accelerates (u^1.8), so the rows pluck one after another, as real registers do [P:realism §3.9]. The same function drives the visual jacks.
- The registration mask arrives only through the CommandRing (`Cmd.REGISTRATION`), is applied at a block boundary to new notes (like moving a register slide by hand) and is published in `CoreClockState.registration`, from which the picture slides the jacks when the change is heard. Default 8′+4′.

### 3.11 Sympathetic resonance: 88 comb string resonators (WP3 `ResonanceBank`)

One comb filter per string (keys 21–108; the harpsichord uses 29–89) models its round trip, so it resonates at the string's partials. This gives, with one mechanism: held keys ringing when related notes are played with the pedal up (R7), the pedal-down "open piano" halo including low strings sounding at their upper partials (R8), the always-ringing undamped treble (R9), and una corda's aftersound.

```
delay:      N(k) = fs / f0Hz[k] − dLP(k) − dDisp(k);  M = floor(N − 0.5) integer samples, d = N − M ∈ [0.5, 1.5)
            first-order allpass for the fraction: η = (1 − d) / (1 + d)            (flat magnitude)
dispersion: keys ≤ 59 at Q0–Q1: two identical first-order allpass sections with a coefficient from (f0, B) (Rauhala–Välimäki),
            so partial n rings at n·f0·√(1 + B·n²) as the real string's does; dLP and dDisp are their phase delays at f0
loop:       one-pole low-pass a(k) = exp(−2π·fc(k)/fs),  fc(k) = clamp(40·f0Hz[k], 2500, 12000) Hz   [D, tuned in L-7];
            without dispersion (Q2, or keys ≥ 60 if T3.1 shows a residual): fc ≤ n_max·f0, n_max = √(2(2^(5/1200) − 1)/B),
            so only partials within ≈ 5 cents of harmonic ring
gain:       g(k) = min(0.9995, 10^(−3·N(k) / (fs · T60(k)))),  T60(k) = T60free(k)·0.8^(1−D)·(T60d(k)·0.5)^D
            → prepared table G[k][round(32·D)] (128 × 33, with the KeyMap); per block a table read and a linear glide
input:      x_k = SEND · gate[k] · (mix − self[k])  +  UNA_CORDA_SEND · softFeed[k] · self[k]
            mix = 0.5·(dryL + dryR + softL + softR) of the VOICES only (no comb → comb feedback); self[k] = key k's own voices (§3.6)
output:     4 pan groups by register (−0.6, −0.2, +0.2, +0.6, constant power, bass left as the player hears it), added to the dry bus
```
- **No self-feed.** A comb tuned to its own sample's f0 has its fundamental on a peak ≈ 0.44 Hz wide with gain 1/(1 − g) ≈ +45.6 dB at C4 (N ≈ 183.5, T60 ≈ 5.0 s), finer than any tuning error, so feeding a key's comb with its own voice re-blooms every held note ≈ 24 dB into its decay. Comb k therefore hears **the mix minus key k's own voices**: `VoicePool` accumulates a per-key mono self row (preallocated 88 × 256 floats, written only for keys with an open gate or a soft feed and a sounding voice). Una corda is the only deliberate self-feed, on keys with ≥ 2 strings, calibrated on its own (expected send ≈ −48 dB, not −26).
- **Gates and sends (split ownership):** `KeyState` (WP2) computes `gate[k]` ∈ 0..1 (0 when D(k) ≥ 0.999, else 1 − D(k); harpsichord: 1 while the key is held, else 0) and `softFeed[k]` (the key's newest voice started on the soft bus and `stringsPerKey(k) ≥ 2`). `ResonanceBank` (WP3) owns the dB values in `DspTables`: `SEND` Natural −30 dB, Rich −24 dB (grand); −32 / −26 dB (upright); harpsichord −34 dB; `UNA_CORDA_SEND` ≈ −48 dB [E]. T2.8 tests the gates, T3.1 the dB values.
- **Tuning:** combs use `KeyMap.f0Hz` (the recorded stretch shape × the current temperament and pitch) and `KeyMap.inharmB` (the pipeline's partial-series fit, §6.5), so they ring where the samples' strings do. Coefficients are prepared off-thread in `prepare()` and swapped with `apply(prepared, glideMs)` (200 ms glide on a retune).
- **Active set:** a comb is processed only while `gate > 0`, `softFeed`, or its peak |y| over the last block > −90 dBFS; inactive combs are skipped and their buffers zeroed once. `maxActive`: Q0/Q1 88, Q2 44 (held keys first, then highest energy), Q3 0.
- **Kernel:** four combs per loop iteration with all state in locals, so their independent loop-carried chains (fraction allpass, loop low-pass, dispersion) fill the in-order A55's pipeline instead of serialising (one comb at a time costs ≈ 30–40 cycles per comb-frame [E, review analysis]). All delay lines are packed in one `FloatArray`, each a power-of-two length ≥ N + 256 (≈ 250 KiB in total), masked indexing.
- **Energy:** per comb per block, the mean-square of its mono output from every 8th sample, halved (its constant-power pan spreads y² over two channels), is **added as mean-square** to its lane; `EngineCore` takes one `sqrt` per lane before `EnergyRing.write`, so the strings on screen shimmer in sympathy and stop when the dampers land.
- **Calibration targets** (JVM offline renders, WP3; first with `SineBank` harmonic voices, then with the real decoded regions WP11 exports after the download (C3, C4 and C5 at v10 and v13, and the una corda C4), whose stretched, inharmonic partials sit off a harmonic comb's peaks; re-checked on the glasses with the real kit at M2 and M5, then L-7 by ear): (1) pedal down, C4 v80 struck alone: the C5 comb's RMS over 0.5–1.5 s is −28 ± 4 dB re the C4 voice's RMS over the same window; (2) una corda C4 v64: the C4 comb is −12 ± 4 dB re the dry voice over 1–3 s; (3) pedal up, C3 held and decayed 24 dB, C4 struck: the C3 comb rings at ≥ −40 dB re the C4 peak; (4) C3 held alone for 10 s, pedal up and pedal down, Rich: the C3 comb stays ≥ 30 dB below the C3 voice and the summed envelope falls monotonically.

### 3.12 The room: one geometry for sight and sound (WP3 `RoomAcoustics`, `RoomChain`; WP8 `Konzertzimmer`; WP0 `KonzertzimmerAcoustics`)

**One description, two users.** `KonzertzimmerAcoustics.GEOMETRY` (a `contract` constant, WP0) holds the surfaces the renderer draws, with acoustic materials, one plane per entry; WP8's `Konzertzimmer` returns exactly this object and T8.1 checks its drawn areas against it; WP3's tests use the same constant. `RoomAcoustics.design()` turns it plus the current listener into a `RoomDesign`. The scene is "an evening at Sanssouci": 18 seated guests, silk drapes drawn over the three windows.

**Design room [D]** (the real dimensions were not found [R:visual_design §1.3]): 10.5 m (x, east) × 8.0 m (z, south) floor; cornice 4.6 m; quarter-round cove r = 1.1 m; ceiling 5.7 m; **V = 469.7 m³**; total surface ≈ 363 m²; mean free path 4V/S = 5.18 m (15.1 ms). Image-source planes (`erPlanes`): floor y = 0, ceiling y = 5.3 m (effective, cove), N wall z = −4.0, S wall z = +4.0, E wall x = +5.25, W wall x = −5.25.

| Plane | Surface | Area m² | Material | α 125 | 250 | 500 | 1k | 2k | 4k | 8k |
|---|---|---|---|---|---|---|---|---|---|---|
| 0 floor | floor | 84.00 | oak parquet on joists | .15 | .11 | .07 | .06 | .06 | .07 | .07 |
| 1 ceiling | ceiling and cove | 108.60 | plaster on lath | .14 | .10 | .06 | .05 | .04 | .03 | .03 |
| 2 N | boiserie | 29.76 | wood panelling on battens | .25 | .15 | .10 | .08 | .07 | .07 | .07 |
| 2 N | 3 pier mirrors (1.3 × 3.4) | 13.26 | mirror glass on wall | .08 | .06 | .04 | .03 | .02 | .02 | .02 |
| 2 N | 2 Pesne panels (1.1 × 2.4) | 5.28 | canvas | .10 | .10 | .10 | .10 | .10 | .10 | .10 |
| 3 S | boiserie | 25.35 | wood panelling on battens | .25 | .15 | .10 | .08 | .07 | .07 | .07 |
| 3 S | 3 windows behind drawn drapes (1.5 × 3.9) | 17.55 | silk drapes, drawn | .07 | .31 | .49 | .75 | .70 | .60 | .60 |
| 3 S | 2 pier glasses (0.9 × 3.0) | 5.40 | mirror glass on wall | .08 | .06 | .04 | .03 | .02 | .02 | .02 |
| 4 E | boiserie | 25.22 | wood panelling on battens | .25 | .15 | .10 | .08 | .07 | .07 | .07 |
| 4 E | double door (1.5 × 3.2) | 4.80 | panelled wood | .14 | .10 | .06 | .08 | .10 | .10 | .10 |
| 4 E | 2 Pesne panels + supraporte (1.5 × 1.0) | 6.78 | canvas | .10 | .10 | .10 | .10 | .10 | .10 | .10 |
| 5 W | boiserie · door · 2 Pesne + supraporte | 25.22 · 4.80 · 6.78 | as E | | | | | | | |
| – | audience, 18 seated | – | sabins per person | .30 | .40 | .50 | .55 | .60 | .60 | .60 |
| – | air (4mV), m in 1/m | – | 20 °C, 50% RH | .0001 | .0002 | .0005 | .0010 | .0024 | .0062 | .0215 |

Walls total 170.2 m² (boiserie 105.55, windows 17.55, mirrors 18.66, doors 9.60, paintings 18.84). Sabine `T60 = 0.161·V / (Σ S·α + N·A_person + 4mV)` gives ≈ **1.15 s (125 Hz), 1.43, 1.69 (500 Hz), 1.61, 1.59, 1.43, 0.93 s (8 kHz)** [E: computed by the architect; the test recomputes them]. FDN targets: `t60Low` = T(125), `t60Mid` = mean(T(500), T(1k)) ≈ 1.65 s, `t60High` = T(8k). Critical distance `r_c = 0.057·√(Q·V/T)` with Q = 2 → **1.36 m**. Changing a material changes the sound. Each early reflection uses its plane's area-weighted α.

**Listeners and levels** (ears and sources from §5.6): Player 1.59 m from the soundboard (DRR −1.3 dB), Action cutaway ≈ 0.77 m (+4.9 dB), Overhead ≈ 1.30 m (+0.4 dB), Hall row 3 4.93 m (−11.2 dB).
- **Direct path (`DirectPath`):** `directGain = clamp(r_bench / r, 0.25, 1.6)`; air absorption one-pole `fc = clamp(18000 − 1600·(r − 1.6), 9000, 18000)` Hz; mid/side width Player 1.0, Action 0.8 (both framings), Hall 0.4. In the Hall (`worldLocked`), the direct and early sum is balanced by `pan = SIN[sourceAzimuth − yawPred]` (constant power, 1024-entry table), where HKAudio reads `HeadPose` every block and predicts it forward by the route's measured output latency: `yawPred = yaw + clamp(ω · lat, ±15°)`, smoothed by a 20 ms one-pole. So the piano stays where it stands when the head turns, instead of swinging with the head for the 140–400 ms of output latency.
- **Early reflections (`EarlyReflections`):** 6 first-order image sources (floor, ceiling, N, S, E, W) and 6 second-order (floor–ceiling, ceiling–floor, N–S, S–N, E–W, W–E) of the soundboard centre, for the current ear. Each tap: delay `(d_img − d_direct)·fs/343` frames (≥ 1), gain `erGain · (d_direct/d_img)·Π √(1 − α500(plane))`, pan from its azimuth relative to the listener's forward (constant power). Taps on planes with area-weighted α4k < 0.15 are **bright** (one shared low-pass at 12 kHz), the rest **dull** (5 kHz). **On a listener change two 12-tap sets run and crossfade old → new over the 500 ms glide** (delays never jump), and the FDN input crossfades between the old and new pre-delay taps.
- **Late reverb (`FdnReverb`):** 8 lines of 853, 1031, 1277, 1471, 1693, 1951, 2203, 2521 frames (17.8–52.5 ms, mutually prime, around the 15 ms mean free path); fast 8×8 Hadamard (24 add/sub, × 1/√8); per line gain `10^(−3·d_i/(fs·t60Mid))` (prepared), a first-order low shelf for `t60Low` and a one-pole low-pass for `t60High` (Jot); lines 2 and 5 modulated ±12 samples at 0.31 and 0.47 Hz (table sine, linear-interpolated read) to break up metallic ringing on piano; pre-delay = the earliest ER delay; input = mono of the direct output after pre-delay, spread with alternating signs; output lines 0/2/4/6 → L, 1/3/5/7 → R with alternating signs. **Energy-normalised:** steady input RMS x yields diffuse output RMS x ± 1 dB for T60 ∈ [0.8, 2.5] s, so `reverbGain` is physical: `reverbGain = (r_bench / r_c) × mode × embedded`, the same at every seat (the diffuse field), with mode Dry −6 dB, Room 0 dB, Resonant +3 dB and T60 × 1.2. Q3 uses the first 4 lines (4×4 Hadamard).
- **Embedded room (per kit):** the VCSL harpsichord takes are `_Far` microphones and the Knight releases carry a room tail, while the Salamander pair is close and nearly dry. The pipeline measures each kit's embedded direct-to-reverberant ratio and early decay time (`embeddedRoomDb`, `embeddedEdtS`, §6.5); `RoomAcoustics` reduces the reverb and early-reflection sends so that embedded plus simulated reverberant power meets the listener's DRR target: `R_sim = max(R_target − R_embedded, 0.1·R_target)`, `embedded = √(R_sim / R_target)` (power ratios reverberant/direct). The three instruments then sound as if they stood at the same distance.
- **Pause** fades only the room input over 60 ms (`setInputGain`), so the tail rings on naturally.
- **Convolution is rejected:** a 1.7 s stereo IR by partitioned FFT is ≈ 25–45% of a core in ART, and there is no measured, licensed IR of this room [P:realism §3.10].

### 3.13 Master bus (WP3 `SoftBus`, `SpeakerEnhancer`, `Limiter`, `MasterChain`)

Order per block: voices → dry and soft buses → combs added to dry → `SoftBus` (soft → dry) → `RoomChain` (direct + ER + FDN) → master gain × duck → `SpeakerEnhancer` → `Limiter` → Padé soft clip → interleave.

| Route | Voicing |
|---|---|
| `SPEAKER` (built-in temple speakers; nothing below ≈ 150 Hz [R:engine_reuse §4.2]) | 2nd-order Butterworth high-pass 110 Hz; **virtual bass**: mono sum → 2nd-order low-pass 150 Hz → full-wave rectify → DC-block → band-pass 150–450 Hz (2nd-order HP + LP) → −6 dB, added to both channels; +3 dB peaking at 250 Hz (Q 0.9); −1.5 dB high shelf at 7 kHz. Constants tuned in L-2 |
| `WIRED`, `BLUETOOTH` | 1st-order high-pass at 20 Hz only |
| Speaker bass setting | Auto = virtual bass on the speaker route only; On = always; Off = never (the EQ stays) |

- **Master gain:** default −8 dB. The calibration (a v127 C-major triad at C4 with the pedal down peaks at −6 ± 2 dBFS before the limiter, in the Player design) is an **M5 integration test owned by WP12** (`LevelCalibrationTest`: real `EngineCore` + real `DspFactory` + the Player `RoomDesign`, real regions once exported), because it depends on WP3's direct gain (up to +4 dB in Action), reverb, combs and speaker peaking; the resulting `masterDb` default goes into `Settings`. WP2's T2.9 checks only relative levels with `PassThroughDsp`.
- **Limiter:** 1 ms (48-frame) look-ahead peak limiter, sliding maximum by a monotone deque in fixed arrays, ceiling −1 dBFS, gain ramps to target over the look-ahead, 120 ms exponential release (prepared per-block coefficient); then SpyHunt's Padé `softClip` as the final safety.

### 3.14 Voice cap, stealing and self-protection

- **Cap from the device, not a guess.** `EngineBench` (WP2) runs once per APK version on the title card (2 s, silent) and on `--ez bench true`, measures ns per Hermite voice-frame and normalises it to 2.0 GHz with the frequency read during the run (`nsVoice₂`); the Q0 cap is `C = clamp(round8(0.25 × 20,833 / nsVoice₂), 64, 128)` (≈ 96 at the estimated 55 ns [E]; 64 until the bench has run). Q1–Q3 use 0.75 C, 0.625 C and 0.5 C, rounded to 8, with floors 48 / 40 / 32 (§5.11). The cap counts PLAYING, FADING and PENDING voices; a crossfade note counts as two.
- **Demand is measured too.** `PerformanceBuilder` (WP1, `VoiceDemand`) simulates the uncapped pool for every Performance (the kit's trim lengths from §3.2, up to 3 voices per key, crossfades, the −80 dB cull from the §3.7 decay laws) and stores the p99 and maximum in `PerfInfo`; a WP1 report test prints the table for the whole catalogue (`build/voice_demand.txt`), and the realism estimate of 60–90 voices in pedalled Beethoven [P:realism] is checked against it. T-CPU logs `stolen` per movement on op. 53 iii and op. 57 iii, and L-7 listens for steals.
- **Noise pool:** release and pedal-noise voices have their own 12 slots (§3.6), so pedal washes keep their tails and noises never steal music.
- **Steal ahead, never ramp inside a block.** Onsets are dispatched up to `LOOK_FRAMES` (1,024 frames) before their block, so when the pool is full the stealer picks victims **at dispatch** and moves them into **kill slots** (C/2 of them; each fades over 5 ms = 240 frames and is free again by the next block). If the kill slots run out, the remaining onsets of that dispatch wait in a pending list (≤ 64) and are allocated in the next block, still before their voice starts (any event is first seen ≥ 1,024 frames ahead, and its voice starts at most 559 frames before its frame). There is no in-block-ramp path. An onset that still finds no slot is dropped and counted (`dropped`; 0 in every test score).
- **Victim:** the lowest `score = levelDb + classBonus − 2·age_s`, with `classBonus` FADING −40, damping (D > 0) −20, pedal-held (D = 0, key up) −10, key down +20. A voice younger than 50 ms is stolen only if nothing else exists.
- **Self-protection** (independent of the thermal ladder) is driven by **buffer headroom**, in `AudioOutput` (§3.1): the cap drops by 8 (not below 32) when the queued frames stay below 1,536 for 10 s, and is restored after 60 s at ≥ 3,072. Each change is logged.

### 3.15 The per-block graph and transport (WP2 `EngineCore.render`)

```
render(out, blockStartFrame):
  record CoreClockState (songUs from the 32.32 song-frame position S0, rate, playing, epoch, generation, registration, idle, endedGeneration)
  if playing: sequencer.dispatch(k < BLOCK + LOOK_FRAMES)       // exact output-frame offsets; voices PENDING until k − onsetOut (4′: − staggerFrames)
              state events → PendingEvents at their absolute frame; steal-ahead into kill slots
              EV_KEY_UP → key up now, damper landing queued at +damperLagFrames; EV_LATCH → mask; EV_PEDAL_NOISE → noise voice;
              EV_NOTE_ON → main voice (+ 4′ voice if REG_4, + restrike fades); EV_END → end countdown
  pendingEvents.apply(this block)                              // key-ups' landings, latches, noises whose frame falls in this block
  p_sus = sustainCursor.advanceTo(S0); p_soft = …; keyState.update(p_sus, latch) → D[128], gate[128], softFeed[128]
  voicePool.render(dryL/R, softL/R, self rows, D, quality)     // damping multipliers, spectral LP, fades, levelDb, cursors, mean-square lanes
  mix = 0.5·(dry + soft) → resonance.process(mix, self, selfRows, gate, softFeed, D, dryL, dryR, 256)
  soft.process(softL, softR → dryL, dryR)
  room.process(dryL, dryR, wetL, wetR, 256, yawPred(HeadPose))
  master.process(wetL, wetR, 256, out)
  lanes[i] = sqrt(voiceMeanSquare[i] + combMeanSquare[i])      // linear RMS re full scale; the one sqrt per lane
  S0 += playing ? 256 · rateFixed : 0
```

| Command | Behaviour |
|---|---|
| Play / pause | **Pause:** pending voices → IDLE without a fade and queued state events dropped; the sequencer cursor rewinds to the lowest event index that has not taken effect (§2.5); sounding voices fade to 0 over `l` ms (60 by default, 300 when leaving the app) and then freeze (positions kept); the room input fades over 60 ms and the tail rings on; the clock freezes, so the picture stops exactly when the sound stops. **Resume:** the frozen chord continues with a 60 ms fade-in; the rewound events are dispatched again (already-started note-ons skipped once) |
| Seek | pending voices → IDLE; sounding voices fade over 10 ms; combs cleared; room input faded; queued events dropped; cursor by binary search; `epoch++`; pedal cursors, sostenuto latch and held-key state rebuilt at the target (state, not sound); notes that began before the target are not restarted (their keys still show as down) |
| Tempo | `setRate(0.5..1.5)`: only the song clock changes, not pitch; damper lag, 4′ stagger and every drawn mechanical duration stay real-time |
| New performance | pending voices → IDLE; old voices fade 30 ms; `epoch++`; generation from the Performance; `startUs = −1` keeps the engine's own song position (instrument switch) |
| Instrument switch | `SET_BANK` at a block boundary with a 30 ms crossfade to silence (pending voices → IDLE: their regions belong to the old bank); the new Performance follows (§2.6) |
| Registration | `Cmd.REGISTRATION` at the block boundary; new notes only; published in `CoreClockState` |
| End of piece | `EV_END` at the last key-up; `endedGeneration` is set once every voice is below −80 dB or 4 s have passed, and AudioOutput posts `onEnded(generation)` exactly once. The next Performance is already compiled, so the gap is the natural tails plus 1.5 s |

### 3.16 CPU budget

The A55 cores run in one frequency domain whose cap was 1.5 GHz at review time (not worn, 26 °C) [M:rev]; a steady ≈ 45%-duty thread lets the governor settle at 1.1–1.5 GHz. Costs are therefore budgeted in **cycles per unit-frame** [E] and shown at 1.5 GHz; one output frame lasts 20,833 ns at 48 kHz, so % of one core = ns per frame ÷ 20,833, and "normalised" = the same work on a 2.0 GHz core. `EngineBench` replaces every row with a measurement on day one of M1, reporting ns per stage normalised to 2.0 GHz, and T-CPU repeats it at every audio milestone.

| Stage | cycles per unit-frame [E] | Q0 worst units (C = 96) | ns/frame at 1.5 GHz | % of a 1.5 GHz core |
|---|---|---|---|---|
| Voice, Hermite stereo, gain ramp, self row | 110 | 96 | 7,040 | 33.8 |
| Spectral-damping low-pass (damping voices) | 16 | 48 | 512 | 2.5 |
| Window refills (bulk copy, amortised) | 6 | 96 | 384 | 1.8 |
| Noise pool (releases, pedal noises) | 110 | 12 | 880 | 4.2 |
| Comb resonator (4-way kernel, self subtraction, dispersion on 39 of 88) | 22 | 88 | 1,291 | 6.2 |
| Soft-bus shelf | 40 | 1 | 27 | 0.1 |
| Direct path + 2 × 12 early taps (during a crossfade) | 240 | 1 | 160 | 0.8 |
| FDN, 8 lines, 3-band, 2 modulated | 500 | 1 | 333 | 1.6 |
| Speaker enhancer + limiter + clip + interleave | 260 | 1 | 173 | 0.8 |
| Sequencer, pending events, key state, lanes (amortised) | 150 | – | 100 | 0.5 |
| **Q0 worst case (cap-bound pedalled storm)** | | | **≈ 10,900** | **≈ 52% (≈ 39% normalised)** |
| Worst single block while stealing (+ 48 kill slots) | | | ≈ 14,400 | block ≈ 3.7 ms of 5.33 |
| Typical op. 106 (≈ 30 voices, 4 noises, ≈ 60 combs) | | | ≈ 4,300 | ≈ 21% (≈ 15% normalised) |
| Q1 worst (72 voices) | | | ≈ 8,900 | ≈ 43% (≈ 32%) |
| Q2 worst (60 voices linear at 70 cycles, 44 combs without dispersion, no spectral) | | | ≈ 5,000 | ≈ 24% (≈ 18%) |
| Q3 worst (48 voices linear, no combs, 4-line FDN) | | | ≈ 3,600 | ≈ 17% (≈ 13%) |

**Pass lines (T-CPU):** HKAudio CPU time from `/proc/self/task/<tid>/stat` (utime + stime) over 60 s, multiplied by the mean frequency from the `time_in_state` deltas and divided by 2.0 GHz: ≤ 42% under `storm64` and ≤ 20% on op. 106; block p99 ≤ 3.2 ms at the frequency actually run (logged with it); queued frames never below 1,536 after warm-up; 0 underruns. `top -H` is logged alongside, unnormalised. **If over, step down by measured value:** `EngineBench` reports ns saved per stage for each step (Hermite → linear at Q0, dispersion off, combs 44, cap − 8), and the step with the most ns saved per unit of audible loss (L-7 ranks them) goes first; each is one constant in `QualityLadder`.

### 3.17 Memory budget

| Item | Size | Kind |
|---|---|---|
| Grand HD PCM mapping | 755 MiB virtual | file-backed, clean, evictable; process-lifetime |
| Resident while playing | 80–200 MiB [E] | page cache (counts in RSS) |
| Pre-read attack heads (150 ms × 572 regions) | ≈ 16 MiB | page cache |
| Voices (204 × 2,056 shorts) | 0.8 MiB | heap |
| Self rows (88 × 256 floats) | 0.09 MiB | heap |
| `env` tables (HD grand, 1 byte per 10 ms) | 0.4 MiB | heap |
| Comb delay lines (one packed array) | ≈ 0.25 MiB | heap |
| FDN, 2 × ER, pre-delay, buses | 0.35 MiB | heap |
| AudioClock (256 records) + EnergyRing (256 × 88 lanes) | ≈ 0.11 MiB | heap |
| KeyMap, decay, comb-gain and spectral tables | ≈ 0.1 MiB | heap |
| Largest Performance (op. 106 iv ≈ 20k notes) + the pre-built next one + the previous one still bound on GL | ≈ 3.6 MiB | heap |
| Catalogue + import index | < 1 MiB | heap |
| **Resident mesh arrays and texture RGBA of the current instrument and venue** (kept for GL-context-loss re-upload, §5.1) | ≤ 8 MiB + ≤ 6 MiB | heap |
| GlyphBoard (≤ 32 labels) | ≤ 8 MB | GPU |
| Textures (atlas 1024² RGBA, probe 128×64, instrument textures) | ≤ 8 MB | GPU |
| VBOs | ≤ 6 MB | GPU |
| **Budgets** | **Java heap ≤ 48 MiB; PSS excluding mapped-file pages ≤ 200 MiB; RSS ≤ 450 MiB** | T-MEM |

### 3.18 Temperament and pitch

`TuningSpec.keyCents(k) = 1200·log2(aHz/440) + temperament.offsetCents(k mod 12)`; offsets in cents from equal temperament, normalised so A = 0 [R:mechanics §10]:

| Temperament | C | C♯ | D | E♭ | E | F | F♯ | G | G♯ | A | B♭ | B |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Equal | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| Werckmeister III | +11.7 | +2.0 | +3.9 | +5.9 | +2.0 | +9.8 | 0.0 | +7.8 | +3.9 | 0 | +7.8 | +3.9 |
| Kellner | +8.2 | −1.6 | +2.7 | +2.3 | −2.7 | +6.3 | −3.5 | +5.5 | +0.4 | 0 | +4.3 | −0.8 |
| Vallotti | +5.9 | 0.0 | +2.0 | +3.9 | −2.0 | +7.8 | −2.0 | +3.9 | +2.0 | 0 | +5.9 | −3.9 |
| Young II | +5.9 | −3.9 | +2.0 | 0.0 | −2.0 | +3.9 | −5.9 | +3.9 | −2.0 | 0 | +2.0 | −3.9 |
| Kirnberger III | +10.3 | +0.5 | +3.4 | +4.4 | −3.4 | +8.3 | +0.5 | +6.8 | +2.4 | 0 | +6.4 | −1.5 |
| Lehman 2005 ("one proposal") | +5.9 | +3.9 | +2.0 | +3.9 | −2.0 | +7.8 | +2.0 | +3.9 | +3.9 | 0 | +3.9 | 0.0 |
| ¼-comma meantone | +10.3 | −13.7 | +3.4 | +20.5 | −3.4 | +13.7 | −10.3 | +6.8 | −17.1 | 0 | +17.1 | −6.8 |

Pitch standards: A440 (0 cents), A430 (−39.80), A415 (−101.27), A392 (−199.98). Temperament and pitch are stored per instrument; a work in the catalogue may set its own (none do by default). A change builds a new `KeyMap` (new notes only; sounding strings keep their pitch) and re-tunes the combs over 200 ms (`ResonanceProcessor.apply(prepared, 200)`, with `prepareKeyMap(keyMap, info, profile)`). The samples keep their own inharmonicity and the recorded stretch shape; the recording's own pitch standard is removed (§3.5).

### 3.19 Failure modes and fallbacks (why it works on first install)

| Failure | Detection | Fallback | User sees |
|---|---|---|---|
| `map.json` missing or invalid (e.g. downloads not approved yet) | `KitMapCodec` validation against §6.6 | APK stub bank `assets/instruments/stub/` (30 notes × 1 layer, ≈ 0.5 MB Opus) | `Stand-in tones: sample bank not installed` |
| Opus decoder missing or throwing, or DecoderProbe fails | `DecodeResult.Failed` / probe | `SynthBank` (additive partials with two-stage decay, built in code, no decoder) | `Stand-in tones: decoder unavailable` |
| Decoding far slower than planned | M1 decode bench, T-DEC | Concentus fallback (separate approval); until then voicing takes longer | progress |
| Not enough storage | free-space check | reduced grand (units v4 + v13) | `Reduced grand: low storage` |
| Decode interrupted | `.ready` mask / no `.ok` | resume at the first missing unit | progress again |
| Unclean reboot mid-voicing (thermal reboot) | `.ok` missing or `BOOT_COUNT` changed | CRC32 of the newest units; failed units re-voiced | progress again |
| Page faults on HKAudio | `majflt` > 0 after warm-up | lookahead 3 s, 250 ms heads; then the v1.1 streamer fallback | – |
| `PERFORMANCE_MODE_NONE` refused or glitchy | builder throws / T-UND | `LOW_LATENCY` + `LatencyTuner` | – |
| No AudioTrack at all | builder throws twice | silent app, visuals on `FakeClock` | `Audio output unavailable` |
| `write` < 0 / timestamps dead (audioserver restart, BT switch) | `TrackSupervisor` | rebuild the track (≤ 3 in 10 s), clock reset, same song position | – (or `Audio stopped: output lost`) |
| Exception in `render` | try/catch in the loop | silent block + one `reset()`; a second within 10 s stops audio | `Audio stopped: <reason>` |
| Display-off underruns | T-UND/T-PF display asleep | `HK.USE_FG_SERVICE` → `PlaybackService` | a media notification |
| Uncaught exception anywhere | `Thread.setDefaultUncaughtExceptionHandler` | `files/crash.txt`, shown on next launch | status line, priority 1 |
| Corrupt or hostile import | `ScoreCompiler.inspect` | rejection recorded in `index.json` by (path, size, mtime) | status line with the reason |
| Pushed file unreadable (adb mode bits) | `EACCES` on copy; `st_uid` check of `Scores/` | skipped, recorded | `Permission denied: run push_scores.sh` |
| GL context lost | `onSurfaceCreated` after the first | GL generation + 1; re-upload from resident arrays under a dip | a dip |
| EGL config without MSAA | chooser fallback chain | no MSAA; analytic bevels and ribbon AA carry the look | – |

---

## 4. MIDI and library

### 4.1 SMF parser (WP1 `SmfParser`: pure, tolerant, bounded, never throws)

- **Container:** `MThd` with header length ≥ 6 (extra bytes skipped); RIFF `RMID` wrappers unwrapped; `.kar` accepted (lyrics ignored); non-`MTrk` chunks skipped by length; a chunk length running past EOF is clamped to the bytes present with a warning; trailing junk → warning.
- **Formats:** 0 and 1; format 2 plays its tracks one after another (warning).
- **Division:** PPQ when positive; SMPTE when negative (−24, −25, −29 = 29.97, −30 fps × ticks per frame) → constant µs per tick, tempo metas ignored.
- **Events:** VLQ ≤ 4 bytes (a longer one ends that track with a warning); **running status** for channel messages, cancelled by meta and sysex per the specification; in tolerant mode a data byte where a status was expected reuses the last channel status with a warning (many hand-made files depend on it); correct lengths (1 data byte for 0xC and 0xD); sysex `F0`/`F7` skipped by length; stray `F8`–`FE` skipped; an unknown status byte resyncs to the next status byte.
- **Metas kept:** `51` tempo (0 ignored), `58` time signature, `59` key signature, `03` track name, `02` copyright, `01` text (first 8 kept for the title guess), `2F` end (a missing end is accepted).
- **Channel events kept:** note on/off (on with velocity 0 = off); CC64, CC66, CC67; CC120/CC123 (all sound/notes off) become note-offs for that channel's open notes. **Ignored:** program change, CC7, CC11, pitch bend, aftertouch (a piano has no volume knob; hand balance is in the velocities). Channel 10 (index 9) is dropped unless it is the only channel with notes.
- **Limits** (`SmfLimits`): 8 MiB, 2,000,000 events, 256 tracks. Exceeding one returns `Failed(TOO_LARGE | TOO_MANY_EVENTS)`.
- **Result:** `SmfResult.Ok(RawSmf)` or `Failed(error, detail, byteOffset)`; `RawTrack` holds parallel primitive arrays (no event objects). The tempo map is built from every track (some format-1 files put tempo outside track 0).

### 4.2 Tempo map

Piecewise-linear tick → µs over tempo segments (initial 500,000 µs per quarter), cumulative so there is no drift; `tickToUs` by binary search. Bar starts from the `58` events (default 4/4) feed the HUD's `bar N` and `Performance.barUs`. Tempo scaling at run time is the transport rate, not a rebuild; the Performance stays in file time.

### 4.3 Performance builder (WP1 `PerformanceBuilder`), once per (file, instrument), on HKLoader, ≤ 60 ms for 20k notes (T1.6)

1. **Merge.** All channel events from all tracks as absolute ticks, stably sorted by `(tick, class, track, file order)` with class order **meta < note-off < controller < note-on**, so a re-strike at the same tick is a re-strike, not a stuck key, and a pedal change at the tick of a new chord applies before the chord. Ticks → µs, then **+ `PRE_ROLL_US`** (400 ms).
2. **Pedal curves (`PedalShaper`).** For CC64, CC66 and CC67 separately, the maximum across channels, 0–127 → 0–1.
   - **Mode:** CONTINUOUS if the file sends ≥ 8 distinct values strictly between 0 and 127, else SWITCH (threshold 64).
   - **Switch:** a down edge becomes a 0 → 1 ramp over 70 ms placed so it **crosses 0.33 at the event time** (it starts 23 ms early, because the MIDI time marks the acoustic event: the dampers leaving the strings); an up edge a 1 → 0 ramp over 60 ms crossing 0.33 at the event time. When ramps overlap (an up and a down less than 60 ms apart) the curve follows the earlier ramp to the intersection and then the later one, making a **dip** that may stay above 0: the dampers brush the strings and partly damp, as in a quick real re-pedal.
   - **Continuous:** values used directly, linear between events, slew-limited to 70 ms full travel, no lead.
   - **Soft (CC67):** 60 ms ramps crossing 0.5 at the event. **Sostenuto (CC66):** edges at crossings of 0.5.
   - **Pedal noises:** an `EV_PEDAL_NOISE` at each 0.33 crossing of the sustain curve (down or up), speed class 0–3 from the slope at the crossing (switch files → class 2), at least 150 ms apart.
   - A pedal the profile does not use becomes `PedalCurve.EMPTY`. The curves stay in song time (both audio and visuals read them, §3.8).
3. **Note pairing and re-strike serialisation.** `(channel, key)` note-offs match their note-ons first-in first-out. All channels drive **one keyboard**. A note-on for a key still down ends the previous note at `newOn − 2 ms` (but never before `prevOn + 20 ms`) and sets `F_RESTRIKE`; that note's own note-off is then consumed. Zero-length notes get 30 ms. Notes without a note-off end at their track's end or at the last event + 1 s (warning `HANGING_NOTES`). The visual lead of such re-strikes is bounded at evaluation time (§5.7), not here.
4. **Range.** Notes outside `profile.lowKey..highKey` (21–108; harpsichord 29–89) are **folded by octaves** into range with `F_FOLDED`; a folded note that collides with a sounding note on that key within 20 ms is merged (higher velocity kept). Folds are counted in `info.folded`.
5. **Instrument policy** (`InstrumentAdapter`):

   | | Grand | Upright | Harpsichord |
   |---|---|---|---|
   | CC64 | sustain | sustain | **legato hold (finger pedalling):** while the pedal is down, each note-off is delayed to the next pedal release, capped at 1.5 s and never past the next onset on that key; then the curve is dropped |
   | CC66 | sostenuto | ignored | ignored |
   | CC67 | una corda | soft (hammer rail) | ignored |
   | Velocity | layer + gain | layer + gain | key lead and 4′ stagger only; `flatVelocity` never applied |

6. **Sostenuto latches.** At each rising edge, the mask = keys whose dampers are lifted: keys between `onUs` and `offUs + damperLagMs · r` (held or still landing), plus every key ≤ `lastDamper` **only if sustain ≥ 0.55** (dampers fully clear; between 0.33 and 0.55 the blade cannot catch the tabs). The falling edge sets the mask to 0. Emitted as `EV_LATCH` with an index into `latchUs/Lo/Hi`.
7. **Events.** `EV_NOTE_ON` at `onUs` (on the harpsichord it also starts the 4′ voice, `staggerMs · 48` output frames earlier, in the Sequencer); `EV_KEY_UP` at `offUs` (the Sequencer lands the damper `damperLagMs · 48` output frames later); `EV_LATCH`; `EV_PEDAL_NOISE`; `EV_END` at the last key-up. No event carries a real-time offset, so tempo changes cannot move dampers or 4′ plucks against the picture. Sorted by `(evUs, type)`, so at equal times key-ups precede note-ons.
8. **Indexes.** Per-key CSR arrays (`keyFirst`, `keyNotes`), `barUs`, and `PerfInfo` (range, pedal mode and counts, folds, channels merged, drums dropped, maximum polyphony, finger-pedalled, **voice demand p99 and maximum** from `VoiceDemand` (§3.14), warnings as `PerfWarning` codes). `durationUs = last key-up + 1.5 s`.
9. **`flatVelocity`:** when the catalogue marks a work `velocityPolicy: "flat"` (a Sankey file with constant velocity) and the instrument is a piano, every velocity becomes 72.

A Performance never changes after it is built. An instrument switch re-parses the same bytes (milliseconds) and resumes at the engine's own song position.

### 4.4 Files written for another instrument

| Case | Handling |
|---|---|
| A pedalled piano file (Krueger BWV 846) on the harpsichord | legato hold replaces the pedal; out-of-range notes fold; no pedal drawn; pill `finger-pedalled` |
| A harpsichord file (Sankey, no pedal) on the grand | played as written without pedal; velocities honoured, or 72 if the catalogue says `flat` |
| Two-manual file (Goldberg) on any instrument | channels merge onto one keyboard; same-key collisions serialise (step 3). Sankey himself recorded the two-manual variations on one manual [R:repertoire §4.5] |
| Organ texture / pedal-board parts | folded; BWV 565 is not in the catalogue |
| Imported file with General MIDI drums | channel 10 dropped (pill `drums dropped` via `PerfWarning.DRUMS_DROPPED`) |
| Multi-instrument GM file | every non-drum channel plays on the one keyboard; pill `7 channels merged` |

### 4.5 Synthetic scores (`SyntheticSpecs` in the contract; WP1 `SyntheticScores` compiles them through the real builder; WP11 writes byte twins under `assets/midi/test/`)

| Kind | Content | Used by |
|---|---|---|
| `SYNC_CLICK` | A4 v118, 100 clicks; click i at `600 ms · i + (hash(i) mod 34) ms` (a pseudo-random 0–33 ms offset, so clicks fall at every phase of a video frame) | A/V sync card, T-SYNC |
| `SCALE` | chromatic 21–108, velocities 20 → 127 | T-ALIGN, M1 |
| `CHORD_STORM_64` | 64 new notes per 250 ms across the range, pedal down, 3 min | T-CPU, T2.2, T2.10 |
| `PEDAL_HALF` | C major chords under continuous CC64 ramps 0 → 127 → 0 | half-pedal checks, M1 |
| `SOSTENUTO` | held C2 + CC66, then staccato chords above; one CC66 press at sustain 0.4 | latch checks (T1.3) |
| `UNA_CORDA` | C4 v64 with and without CC67 | soft bus, comb calibration |
| `REPEAT_15` | 15 notes per second on one key, at v20, v64 and v110 | repetition, ExposureSampler (T5.7, T5.10) |
| `FOLD` | notes 12–120 | folding |
| `CRESCENDO_C4` | repeated C4, v10 → v127 in steps of 1 | L-3 layer smoothness |

**Names used by CONTROL and the smoke scripts** (one table; nothing else resolves):

| `--es play` value | Resolves to |
|---|---|
| `synth:sync`, `synth:scale`, `synth:storm64`, `synth:pedalhalf`, `synth:sostenuto`, `synth:unacorda`, `synth:repeat15`, `synth:fold`, `synth:crescendo` | `ScoreCompiler.synthetic(SyntheticScore.X)` |
| `test:<file>` | `assets/midi/test/<file>.mid` (the twins above plus `format0`, `format1`, `smpte25`, `running_status`) |
| `asset:<path>` | `assets/<path>` directly (works from M2 on, before the catalogue exists) |
| anything else | a catalogue or imported movement id, e.g. `beethoven.op106.4` |

A WP1 test compiles each `.mid` twin and requires the same note, CSR and event arrays as its `SyntheticScore`.

### 4.6 Catalogue JSON (`assets/catalog.json`, generated by WP11, never edited by hand)

```json
{
  "schema": 1,
  "generated": "2026-10-01T12:00:00Z",
  "startHere": ["bach.bwv846.krueger.1", "bach.bwv772-786.1", "bach.bwv988.1", "scarlatti.k141.1", "handel.hwv430.1",
                "rameau.tambourin.1", "cpebach.h220.1", "mozart.k331.3", "mozart.k545.1", "beethoven.woo59.1",
                "beethoven.op27-2.1", "beethoven.op13.2", "beethoven.op106.1"],
  "shelves": [ { "id": "beethoven", "title": "Beethoven", "works": ["beethoven.op10-1", "…"] } ],
  "sources": {
    "krueger": { "credit": "Performance: Bernd Krueger · piano-midi.de · CC BY-SA 3.0 DE",
                 "licence": "CC-BY-SA-3.0-DE", "licenceUrl": "https://creativecommons.org/licenses/by-sa/3.0/de/deed.en",
                 "sourceUrl": "http://www.piano-midi.de", "licenceFile": "licenses/CC-BY-SA-3.0-DE.txt",
                 "performanceType": "step-sequenced", "tier": "A", "exportAllowed": false },
    "sankey":  { "credit": "Harpsichord: John Sankey · johnsankey.ca/harpsichord.html · free-copy notice",
                 "licence": "SANKEY", "licenceUrl": "https://www.johnsankey.ca/copyright.html",
                 "sourceUrl": "https://www.johnsankey.ca/harpsichord.html", "licenceFile": "licenses/SANKEY.txt",
                 "performanceType": "performed", "tier": "A", "exportAllowed": false }
  },
  "works": [ {
    "id": "beethoven.op106", "composer": "Ludwig van Beethoven", "composerShort": "Beethoven",
    "title": "Piano Sonata No. 29 in B-flat major, op. 106 “Hammerklavier”", "shortTitle": "Sonata op. 106 “Hammerklavier”",
    "catalogue": "op. 106", "year": 1818, "era": "classical", "shelf": "beethoven",
    "defaultInstrument": "grand", "altInstruments": ["upright"], "source": "krueger", "tier": "A",
    "velocityPolicy": "as-is", "tuning": null,
    "movements": [ { "id": "beethoven.op106.1", "title": "I. Allegro",
      "asset": "midi/krueger/beethoven/beethoven_hammerklavier_1.mid", "sha1Hex": "…", "sha1b32": "QK4QBBMFW4SWQETC4NJ5SG2ZBTZPBFXK",
      "bytes": 74228, "durationSec": 593.1, "lowKey": 22, "highKey": 105, "notes": 9123,
      "hasSustain": true, "hasSoft": false, "hasSostenuto": false, "pedalMode": "switch",
      "folds": { "harpsichord": 412 } } ]
  } ]
}
```

- **Hand-written** (in `tools/pipeline/catalog_src.json`): shelves, ids, titles, composers, years, eras, default and alternative instruments, sources, tuning overrides, Sankey entry → movement mapping (`tools/pipeline/sankey_map.tsv`).
- **Measured by the pipeline, never typed:** `sha1Hex` (always; this is `Movement.sha1`) and, for Krueger, `sha1b32` (matching the manifest), `bytes`, `durationSec` (excluding pre-roll), `lowKey`, `highKey`, `notes`, `has*`, `pedalMode`, `folds`, `velocityPolicy` (`flat` when ≥ 95% of note-ons share one velocity).
- **Bundled MIDI stays byte-identical** (Sankey requires it; for Krueger it keeps the app a *Sammelwerk*, not an adaptation) and is parsed at run time [R:repertoire §3].
- **`MidiCorpusTest`** (WP1, JVM) re-derives every measured field with the app's own parser from `core/src/test/resources/wp11/midi_facts_golden.json` (written by WP11's independent `smf_stats.py`) and fails on any disagreement (duration ±1 ms, ranges and counts exact).
- **Imported index** `filesDir/imports/index.json` (app-owned; written to a temp file and renamed): `{schema: 2, items: [{movementId, workId, file (`<sha1>.mid` in `filesDir/imports/`), source (`upload` or the `Scores/` relative path), sourceSize, sourceMtime, originalName, sha1Hex, bytes, addedAt, folder, title, composer, durationSec, lowKey, highKey, hasSustain, hasSoft, hasSostenuto, pedalMode, defaultInstrument, lastInstrument}], rejected: [{source, size, mtime, reason, detail}], deleted: [{source, size, mtime}]}`. Source `"user"`: no credit line, `exportAllowed` false, never uploaded. Work ids `user.<folder-or-file-slug>`, movement ids `user.<sha1-10>`. Deleting `index.json` triggers a full rescan of `Scores/` (uploads keep their copies and are re-indexed from them).

### 4.7 The catalogue: 70 works

From [R:repertoire §4]; durations and ranges are measured by the pipeline. Default: G grand, U upright, H harpsichord. Movement ids are `<workId>.<n>` (1-based; Sankey zips by entry order in `sankey_map.tsv`). All MIDI paths are under `assets/midi/`.

| # | Work id | Work | Shelf | Def. | Source · file(s) |
|---|---|---|---|---|---|
| 1 | `bach.bwv992` | Bach, Capriccio sopra la lontananza del fratello dilettissimo, BWV 992 | bach-young | H | Sankey `misc1.zip` |
| 2 | `bach.bwv914` | Bach, Toccata in E minor, BWV 914 | bach-young | H | Sankey `910-916.zip` |
| 3 | `bach.bwv772-786` | Bach, Fifteen Two-Part Inventions, BWV 772–786 | bach-teaching | H | Sankey `772-786.zip` (15) |
| 4 | `bach.bwv787-801` | Bach, Fifteen Sinfonias, BWV 787–801 | bach-teaching | H | Sankey `787-801.zip` (15) |
| 5 | `bach.bwv846.krueger` | Bach, WTC I No. 1 in C, BWV 846 (pianistic, pedalled) | bach-wtc | G | Krueger `krueger/bach/bach_846.mid` |
| 6 | `bach.bwv847.krueger` | Bach, WTC I No. 2 in C minor, BWV 847 | bach-wtc | G | Krueger `bach_847.mid` |
| 7 | `bach.bwv850.krueger` | Bach, WTC I No. 5 in D, BWV 850 | bach-wtc | G | Krueger `bach_850.mid` |
| 8 | `bach.wtc1.sankey` | Bach, The Well-Tempered Clavier, Book I (complete) | bach-wtc | H | Sankey `846-869.zip` |
| 9 | `bach.wtc2.sankey` | Bach, The Well-Tempered Clavier, Book II Nos. 1–12 | bach-wtc | H | Sankey `870-881.zip` |
| 10 | `bach.bwv816` | Bach, French Suite No. 5 in G, BWV 816 | bach-suites | H | Sankey `812-817.zip` |
| 11 | `bach.bwv807` | Bach, English Suite No. 2 in A minor, BWV 807 | bach-suites | H | Sankey `806-811.zip` |
| 12 | `bach.bwv825` | Bach, Partita No. 1 in B-flat, BWV 825 | bach-suites | H | Sankey `825-830.zip` |
| 13 | `bach.bwv826` | Bach, Partita No. 2 in C minor, BWV 826 | bach-suites | H | Sankey `825-830.zip` |
| 14 | `bach.bwv971` | Bach, Italian Concerto in F, BWV 971 | bach-suites | H | Sankey `sankey/bach/bwv971.mid` |
| 15 | `bach.bwv974` | Bach, Concerto in D minor after Marcello, BWV 974 | bach-suites | H | Sankey `972-987.zip` |
| 16 | `bach.bwv903` | Bach, Chromatic Fantasia and Fugue, BWV 903 | bach-suites | H | Sankey `misc3.zip` |
| 17 | `bach.bwv988` | Bach, Goldberg Variations, BWV 988 | bach-suites | H | Sankey `bwv988.zip` |
| 18 | `handel.hwv430` | Handel, Suite in E, HWV 430: "The Harmonious Blacksmith" | handel | H | IMSLP #365752 (Gouin, CC BY-SA 4.0), **user click** |
| 19 | `scarlatti.k001` | Scarlatti, Sonata K. 1 in D minor | scarlatti | H | Sankey `scarlatti.zip` |
| 20 | `scarlatti.k009` | Scarlatti, Sonata K. 9 in D minor | scarlatti | H | 〃 |
| 21 | `scarlatti.k027` | Scarlatti, Sonata K. 27 in B minor | scarlatti | H | 〃 |
| 22 | `scarlatti.k087` | Scarlatti, Sonata K. 87 in B minor | scarlatti | H | 〃 |
| 23 | `scarlatti.k096` | Scarlatti, Sonata K. 96 in D | scarlatti | H | 〃 |
| 24 | `scarlatti.k141` | Scarlatti, Sonata K. 141 in D minor | scarlatti | H | 〃 |
| 25 | `scarlatti.k159` | Scarlatti, Sonata K. 159 in C | scarlatti | H | 〃 |
| 26 | `scarlatti.k208` | Scarlatti, Sonata K. 208 in A | scarlatti | H | 〃 |
| 27 | `scarlatti.k380` | Scarlatti, Sonata K. 380 in E | scarlatti | H | 〃 |
| 28 | `scarlatti.k466` | Scarlatti, Sonata K. 466 in F minor | scarlatti | H | 〃 |
| 29 | `couperin.barricades` | F. Couperin, Les Barricades mystérieuses | french | H | Madore `madore/couperin_barricades.mid`, **conditional** |
| 30 | `rameau.tambourin` | Rameau, Tambourin | french | H | Commons (Frantz) `commons/rameau_tambourin_frantz.mid` |
| 31 | `rameau.plaintes` | Rameau, Les Tendres Plaintes | french | H | Mutopia `mutopia/rameau_tendres_plaintes.mid` |
| 32 | `rameau.sauvages` | Rameau, Les Sauvages | french | H | Mutopia `mutopia/rameau_sauvages.mid` |
| 33 | `rameau.poule` | Rameau, La Poule | french | H | IMSLP #340106 (Gouin), **user click** |
| 34 | `cpebach.h220` | C.P.E. Bach, Solfeggietto in C minor, H. 220 | galant | H | Commons (Nieb) `commons/cpe_bach_solfeggietto_nieb.mid` |
| 35 | `cpebach.h288` | C.P.E. Bach, Rondo in E-flat, H. 288 | galant | G | Mutopia `mutopia/cpe_bach_rondo_h288.mid` |
| 36 | `haydn.xvi7` | Haydn, Sonata in C, Hob. XVI:7 | galant | H | Krueger `haydn_7_1..3` |
| 37 | `haydn.xvi8` | Haydn, Sonata in G, Hob. XVI:8 | galant | H | Krueger `haydn_8_1..4` |
| 38 | `haydn.xvi9` | Haydn, Sonata in F, Hob. XVI:9 | galant | H | Krueger `haydn_9_1..3` |
| 39 | `haydn.xvi33` | Haydn, Sonata in D, Hob. XVI:33 | haydn | G | Krueger `haydn_33_1..3` |
| 40 | `haydn.xvi35` | Haydn, Sonata in C, Hob. XVI:35 | haydn | G | Krueger `haydn_35_1..3` |
| 41 | `haydn.xvi40` | Haydn, Sonata in G, Hob. XVI:40 | haydn | G | Krueger `hay_40_1..2` |
| 42 | `haydn.xvi43` | Haydn, Sonata in A-flat, Hob. XVI:43 | haydn | G | Krueger `haydn_43_1..3` |
| 43 | `mozart.k281` | Mozart, Sonata in B-flat, K. 281 (performance capture) | mozart | G | Commons (Bednarek, CC0) |
| 44 | `mozart.k311` | Mozart, Sonata in D, K. 311 | mozart | G | Krueger `mz_311_1..3` |
| 45 | `mozart.k330` | Mozart, Sonata in C, K. 330 | mozart | G | Krueger `mz_330_1..3` |
| 46 | `mozart.k331` | Mozart, Sonata in A, K. 331 ("Rondo alla Turca") | mozart | G | Krueger `mz_331_1..3` |
| 47 | `mozart.k332` | Mozart, Sonata in F, K. 332 | mozart | G | Krueger `mz_332_1..3` |
| 48 | `mozart.k333` | Mozart, Sonata in B-flat, K. 333 | mozart | G | Krueger `mz_333_1..3` |
| 49 | `mozart.k545` | Mozart, Sonata in C, K. 545 "facile" | mozart | G (U alt.) | Krueger `mz_545_1..3` |
| 50 | `mozart.k570` | Mozart, Sonata in B-flat, K. 570 | mozart | G | Krueger `mz_570_1..3` |
| 51 | `mozart.k397` | Mozart, Fantasia in D minor, K. 397 | mozart | G | Mutopia `mutopia/mozart_k397.mid` |
| 52 | `mozart.k457` | Mozart, Sonata in C minor, K. 457 | mozart | G | Mutopia `mutopia/mozart_k457_1..3.mid` |
| 53 | `mozart.k265` | Mozart, 12 Variations on "Ah vous dirai-je, Maman", K. 265 | mozart | G | Commons (Bednarek, PD) |
| 54 | `clementi.op36` | Clementi, Six Sonatinas, op. 36 | clementi | G (U alt.) | Krueger `clementi_opus36_1_1 … 6_2` (17) |
| 55 | `beethoven.op10-1` | Beethoven, Sonata No. 5 in C minor, op. 10/1 | beethoven | G | Krueger `beethoven_opus10_1..3` |
| 56 | `beethoven.op13` | Beethoven, Sonata No. 8 "Pathétique", op. 13 | beethoven | G | Krueger `pathetique_1..3` |
| 57 | `beethoven.op22` | Beethoven, Sonata No. 11 in B-flat, op. 22 | beethoven | G | Krueger `beethoven_opus22_1..4` |
| 58 | `beethoven.op27-2` | Beethoven, Sonata No. 14 "Moonlight", op. 27/2 | beethoven | G | Krueger `mond_1..3` |
| 59 | `beethoven.op31-2` | Beethoven, Sonata No. 17 "Tempest", op. 31/2, I–II | beethoven | G | Mutopia `mutopia/beethoven_op31_2_1..2.mid` |
| 60 | `beethoven.op49-2` | Beethoven, Sonata No. 20 in G, op. 49/2 | beethoven | G (U alt.) | Mutopia `mutopia/beethoven_op49_2_1..2.mid` |
| 61 | `beethoven.op53` | Beethoven, Sonata No. 21 "Waldstein", op. 53 | beethoven | G | Krueger `waldstein_1..3` |
| 62 | `beethoven.op57` | Beethoven, Sonata No. 23 "Appassionata", op. 57 | beethoven | G | Krueger `appass_1..3` |
| 63 | `beethoven.op81a` | Beethoven, Sonata No. 26 "Les Adieux", op. 81a | beethoven | G | Krueger `beethoven_les_adieux_1..3` |
| 64 | `beethoven.woo59` | Beethoven, Für Elise, WoO 59 | beethoven | G (U alt.) | Krueger `elise` |
| 65 | `beethoven.op90` | Beethoven, Sonata No. 27 in E minor, op. 90 | beethoven | G | Krueger `beethoven_opus90_1..2` |
| 66 | `beethoven.op106` | Beethoven, Sonata No. 29 "Hammerklavier", op. 106 (the title track) | beethoven | G | Krueger `beethoven_hammerklavier_1..4` |
| 67 | `beethoven.op111-1` | Beethoven, Sonata No. 32 in C minor, op. 111, I | beethoven | G | Mutopia `mutopia/beethoven_op111_1.mid` |
| 68 | `beethoven.op33` | Beethoven, Bagatelles op. 33 Nos. 1 and 4 | beethoven | G | Commons (Bednarek, PD) |
| 69 | `beethoven.op126` | Beethoven, Six Bagatelles, op. 126 (performance capture) | beethoven | G | Commons (Bednarek, CC0) |
| 70 | `beethoven.op129` | Beethoven, Rondo a capriccio "Rage over a lost penny", op. 129 | beethoven | G | Mutopia `mutopia/beethoven_op129.mid` |

**Start here** (13 movements, in order): BWV 846 (Krueger) · Invention No. 1 · Goldberg Aria · Scarlatti K. 141 · Handel "Harmonious Blacksmith" (skipped if absent) · Rameau Tambourin · Solfeggietto · Mozart K. 331/iii · K. 545/i · Für Elise · Moonlight i · Pathétique ii · Hammerklavier i.

**Validation fails the build** if a harpsichord-default file needs > 2% of its notes folded, a Krueger SHA-1 does not match, a Sankey entry's bytes differ from the zip, or a duration differs from the site listing by > 10% (known exceptions: Clementi op. 36/5 ii and Haydn XVI:7 [R:repertoire §9]). Tier-C engravings (Mutopia K. 457, op. 111 i) are listed in `build/listen.txt` for L-6 and dropped if they sound mechanical.

### 4.8 Import paths and rules (WP9)

| Path | Where the bytes land | Validation |
|---|---|---|
| Companion `POST /api/upload` | the handler reads exactly `content-length` bytes into `cacheDir/upload-<n>.tmp` → `ImportStore.save` → `filesDir/imports/<sha1>.mid` | `content-length` checked first: ≤ 4 MiB per MIDI; zip ≤ 20 MiB, ≤ 200 entries, no `..` or absolute paths |
| `tools/device/push_scores.sh` into `Scores/` (+ rescan) | read in place (read-only input) and copied byte-for-byte to `filesDir/imports/<sha1>.mid` | ≤ 8 MiB (parser limit); unreadable → `PERMISSION_DENIED` |

- **Every file:** `ScoreCompiler.sniff` (MThd or RIFF RMID) → `inspect` must be `ok` with ≥ 1 keyboard note → SHA-1 dedupe (`already imported as «title»`) → copied byte-for-byte (never re-encoded or handled as text) into the app-owned store → `index.json` updated (written to a temp file and renamed). Display names keep Unicode letters and digits and `._ ()-` (the rest becomes `_`, ≤ 120 chars); the stored file is named by its SHA-1.
- **Rejected files** stay where they are; the rejection is recorded in `index.json` by (source path, size, mtime) with its `RejectReason` and parser offset, so it is not inspected again until it changes; the companion reply, the Import panel and the status line carry the reason.
- **Rescan** (resume, Library open, CONTROL): new or changed files in `Scores/` are inspected once and copied; entries whose source file disappeared stay imported (the copy is the app's); `Delete` removes the copy and its index entry and, for a pushed file, records it as deleted by (path, size, mtime) so the next rescan does not import it again.
- **Concurrency:** every `LibraryService` method runs under one lock; an upload during a rescan leaves a consistent `index.json` (T9.2).
- The Imported shelf header shows the companion URL or `no Wi-Fi: use push_scores.sh`.

### 4.9 Playlist and resume

`contract/Playlist` (WP0): the queue of movement ids, built by `SessionController` from the shelf the movement was chosen on (movement → rest of its work → remaining works on that shelf; Start here and Recently played play their lists). `previous(positionSec)` returns the same id (restart) when more than 3 s in. `SessionController` pre-compiles the next item's Performance on HKLoader when a movement starts. The resume point `(movementId, instrument, songUs incl. pre-roll, shelfId, playlist, index)` is saved every 5 s, on pause and when the app is left, and offered on the title card as `Tap to continue: «title»`. The recently played list (≤ 15 movement ids) is saved with it.

---

## 5. Rendering

### 5.1 GL context, EGL configuration and pacing (WP6)

- `setEGLContextClientVersion(2)` (the proven low-heat path [R:engine_reuse §2.1]; the device returns ES 3.2, but only ES 2.0 and GLSL ES 1.00 are used). `preserveEGLContextOnPause = true`.
- **Desired state, reconciled on the GL thread.** `RenderControl` calls only set fields of a desired state (instrument, look, lastDamper, the two Performance slots, view, framing, quality, settings, overrides) through `queueEvent` runnables that make **no GL calls** (GLSurfaceView runs queued events even while paused, when no context may be current). `onDrawFrame`, where a context is current, reconciles: a changed instrument or venue is built on HKLoader (meshes and texture recipes) and uploaded under the next dip; during display rest or with the display off the change is applied on the first frame after wake, without a dip. Audio never waits for this.
- **Context loss.** A GL generation counter: every `onSurfaceCreated` after the first bumps it, discards every cached VBO, texture and program handle **without** calling `glDelete*`, recompiles the programs and re-uploads the current instrument and venue from their **resident** mesh arrays and texture RGBA (kept on the heap on purpose, ≤ 8 + 6 MiB, §3.17); the frame stays black (a dip) until the upload is done. `GlyphBoard.release()` drops its cache without GL calls and labels are re-rasterised. Tested with `--ez glreset true` (debug build), which recreates the GLSurfaceView and so forces a new context.
- **EGL chooser:** RGB888 + depth 24 + 4× MSAA, then RGB888 + depth 24, then the default; the chosen config and its `EGL_SAMPLES` are logged `HKRender cfg=…`. MSAA is a property of the window config, fixed when the surface is created (ES 2.0 has no `glDisable(GL_MULTISAMPLE)`), so it is **fixed for the session**: the `Antialiasing` setting (default On when a 4× config exists) is read when the GL view is created and applies at the next launch. It is not on the quality ladder. **No stencil** (mirror flames are culled on the CPU).
- **State:** clear colour pure black (0,0,0) = transparent; depth test, back-face culling (CCW front faces, §2.3) and blending on. `GL_MAX_VERTEX_UNIFORM_VECTORS` logged at start (the engine needs ≥ 64 and asserts ≥ 128).
- **Pacing** (MathCosmos pattern [R:engine_reuse §2.2]): `RENDERMODE_WHEN_DIRTY`; a Choreographer callback calls `requestRender()` every Nth vsync; `removeFrameCallback` before `postFrameCallback` in `onResume` (WanderQuest); `dt` clamped to 0–0.05 s; `FRAME HITCH` logged when raw dt > 120 ms.

| State | Divider | fps |
|---|---|---|
| Playing, dipping, or head moving > 0.2° per frame, Q0–Q1 | 2 | 30 |
| Q2 | 3 | 20 |
| Idle (paused, no dip, head still) at Q0–Q2 | 6 | 10 (flames still flicker) |
| Q3 display rest, or `onPause` | – | none: one black frame, then pacing stops (music continues) |

### 5.2 Frame order and the stereo rig

`onDrawFrame` simulates once and draws twice:
1. Reconcile the desired state (§5.1).
2. `clock.sample(max(frameTimeNanos, System.nanoTime()) + displayLeadMs(route))` → `ClockSample`; `late = nanoTime − frameTimeNanos` recorded; `energy.read(heardFrame, epoch, e)` (a miss is counted and the strings fall back to the analytic envelope).
3. `visualClock.update(sample, now, vis)` (§2.5): monotone `tUs`, the exposure window, `reseed`.
4. Choose the Performance slot whose generation equals `vis.generation` (the previous piece stays bound until the new one is heard); if neither matches, draw the rest pose. On `vis.reseed` or a slot change: `mech.bind` re-seeds the cursors.
5. `mech.evaluate(vis, e, dt, pose)`.
6. `CameraDirector.update(dt, pose, gaze)` → `CameraPose` (the dip state machine and springs, §5.6).
7. `UniformPacker` packs pose lanes and the action set; `FlameField.update` fills the sprite buffer (the eye is converted to the room frame with the Placement for piano-frame cameras); `DipFader` alpha.
8. For eye 0 and eye 1: `glViewport(e·w/2, 0, w/2, h)`, `StereoRig.eye(e)`, the draw list (§5.3), GlyphBoard labels, the fade quad.

**Stereo:** parallel eyes with an **off-axis frustum** (SpyHunt's `perspectiveOffAxis`), not toe-in: IPD 63 mm × the framing's IPD scale × the Stereo depth setting; the frustum shift is `eyeShift · near / zeroParallax`; near 0.05 m, far 30 m; world units are metres. Frame axes: `right = forward × worldUp`, then `up = right × forward`, in that order (the reverse mirrored the look-around on this hardware).
**Gaze:** `GazeCamera` copied from MathCosmos (game rotation vector, smoothing) with its one permitted change: a `worldLocked` mode (Hall) with no soft re-centre and ±60° yaw / +45° pitch clamps, unit-tested; elsewhere the one-minute soft re-centre stays and it gives ±5° of parallax (look direction × 0.15, clamped). Triple-tap recentres. Its sensor callback also writes yaw and angular velocity to `HeadPose` for the audio (§3.12); `RenderStats.headYawRad` is for display only.
**`--ez mono true`** (with `am start -S`, or delivered to `onNewIntent`) or an emulator: one full-width eye for screenshots.

### 5.3 Scene composition, draw list and budgets

There is no general scene graph. `SceneAssembler` turns the `BakedMesh` lists from the current `InstrumentScene` and `VenueScene` into static VBOs once (on a dip), **merging meshes with equal (program, material, skin, texture, levelMask, viewMask, clipped, drawSlot)**, and builds an ordered draw list per (view, framing, venue level, quality) sorted by `drawSlot`: opaque venue → opaque instrument → skinned parts → strings (alpha) → additive gilt, flames, reflections, sparkles, strike pulses → glyphs → fade quad. T7.8/T8.8 check that the distinct merge keys per framing stay within the counts below.

| # (`drawSlot`) | Draw (per eye) | Program | Shown in | Triangles [E] |
|---|---|---|---|---|
| 1 | Room shell, floor, parquet pool (vertex light baked from ≈ 50 flames) | lit + decal | levels ≥ Stage (distance-faded 3.5–6 m at Stage) | 6k |
| 2 | Gilt ribbons: trellis, frames, cornice, web | ribbon (screen-space AA 1.5–2 px, emissive floor) | Salon; Stage within the fade | 3k |
| 3 | Cove cartouches and crests (atlas decals) | decal | Salon | 1k |
| 4 | Chairs, 3 rows × 6 | lit | Hall | 5k |
| 5 | Chandelier arms, sconces, candelabra | ribbon + lit | Hall; Stage (the pair behind the instrument) | 1.3k |
| 6 | Flames, halos, mirror images (CPU-culled), floor images at 25%, crystal sparkle | sprite (additive, one dynamic buffer) | all; mirror images Q0 only; crystals Hall Q0–Q1 | 1.1k |
| 7 | Case, legs, lyre or stand | lacquer (rim, probe, floor, Blinn × 4 lights) | all | 3.6k |
| 8 | Lid (own transform; removed in Overhead) | lacquer | all but Overhead | 0.6k |
| 9 | Plate, soundboard, bridges, pins (harpsichord: soundboard, rose, nut, registers) | lit | Action, Hall | 2k |
| 10 | Keys, all in one skinned draw (harpsichord 61 keys) | skinned KEY_ROT + bevel | all | 2.2k |
| 11 | Hammers (harpsichord: 8′ jacks + tongues); upright hammer rail | skinned HAMMER_ROT / JACK_LIFT / HAMMER_RAIL | Action, Hall | 3.5k |
| 12 | Harpsichord 4′ jacks + tongues | skinned JACK4_LIFT | Action, Hall (harpsichord) | 1.2k |
| 13 | Dampers (lastDamper − 20 on the grand: 68 for the Salamander kit; the upright's under the strike line) | skinned DAMPER_LIFT | Action | 1.7k |
| 14 | Strings: steel and wound in one draw, colour per vertex | string (spindle) | Action, Hall | 7.3k |
| 15 | Pedals (grand, upright) and the sostenuto rail | skinned PEDAL_ROT / SOSTENUTO_ROT | Player, Hall | 0.4k |
| 16 | Action set: 13 slots × key lever, capstan, wippen, jack, repetition lever, knuckle, backcheck, damper underlever (upright/harpsichord equivalents) | skinned ACTION_SET | Action cutaway | 4.8k |
| 17 | Section caps where the cut plane meets the case | sectionCap | Action cutaway | 0.2k |
| 18 | Feature-edge overlay | ribbon | Passthrough, or Edge overlay On | 1k |
| 19 | GlyphBoard labels (≤ 3): note name in the cutaway, title on the music desk (`setTitle`) | glyph | per framing | – |
| 20–21 | Pedal inset (Player follow, grand/upright): pedals + floor pool, scissor + viewport, mono, bottom-right 200 × 150 px (the HUD pills move to the top right in this framing) | lacquer + skinned | Player follow | 0.8k |
| 22 | Sync disc (A/V sync card, from `pose.flash[69]`) and the dip/rest fade quad | fade | as needed | – |

| Framing | Draws per eye [E] | Triangles per eye [E] |
|---|---|---|
| Player | 16 | ≈ 22k |
| Player follow | 18 | ≈ 23k |
| Action cutaway | 18 | ≈ 31k |
| Action overhead | 16 | ≈ 27k |
| Hall (Salon) | 21 | ≈ 37k |
| **Budget (hard, logged)** | **≤ 28** | **≤ 45k** |

**GL-thread CPU:** ≈ 3 ms per frame [E] (mechanics ≈ 0.1 ms, packing ≈ 40 µs, driver ≈ 2.5 ms for ≈ 40 draws); limit 6 ms.
**Mirror reflections without a stencil:** each flame is reflected through each of the 5 mirror planes; a reflected sprite is emitted only if the segment from the eye to its virtual image crosses that mirror's rectangle (≈ 250 point-in-rectangle tests per frame). One N–S bounce between facing mirrors at Q0 only.

### 5.4 Procedural meshes (WP7 instruments, WP8 venue): zero image assets

Everything is built from code on HKLoader into `BakedMesh` FloatArrays with `mesh/MeshBuilder` (conventions in §2.3); the GL thread only uploads. Textures are `TextureRecipe`s painted through the pure `Painter2D` (rocaille atlas, parquet, fallboard lettering "HAMMERKLAVIER", harpsichord papers, motto, soundboard flowers, rose): `CanvasPainter` paints them on the device, `AwtPainter` in JVM tests (PNG review). No photographs, no trademarks, no downloads. WP7 and WP8 review their geometry before integration with `testutil/MeshRaster` (PNG per mesh and framing).

| Part | Build (dimensions from [R:visual_design §2], [R:mechanics §2–§6]) |
|---|---|
| **Keyboard (all)** | Equal slots at the back (octave ÷ 12: 13.71 mm for the 164.5 mm piano octave, 13.25 mm for the 159 mm harpsichord octave); naturals with equal heads at the front (23.5 / 22.7 mm), tails cut round the sharps (C, F cut right; E, B left; D, G, A both; the end keys full); natural visible length 148 mm; sharps 92 mm long, 12 mm high, tops tapered to 10.5 mm. **Bevels are analytic in key-local UV** (mm across and along the key; smoothstep over 0.8 mm), never geometric gaps (0.1–0.5 px, they would shimmer). Each key rotates about its balance line (grand 259.5 mm behind the front; full dip 2.24°) |
| **Grand (C5, 200 × 149 × 101 cm)** | Rim from the Catmull-Rom plan points (0,0) (1,0) (1,.16) (.93,.30) (.80,.45) (.70,.58) (.66,.70) (.62,.82) (.52,.94) (.35,1) (.12,.99) (0,.93) scaled to 1.49 × 2.00 m, extruded 0.30 m (rim bottom 0.66 m); one-piece lid hinged on the spine, 38° stick; three tapered legs with casters; lyre with three brass pedals (tips z −0.22, y 0.09, 9 cm apart); gold plate as a slab outlined by ribbons round its lightening holes; dim spruce soundboard; bass bridge overstrung at 18°. **Strings 228** (keys 21–28 single wound, 29–48 bichord wound, 49–108 trichord, 49–53 wound; `stringsPerKey`). **Hammers 88** (heads 50 → 30 mm tall, 133 mm strike radius); **dampers `lastDamper − 20`** from the bank (68 for Salamander: keys 21–88; wooden head over felt, wire); the 13-slot action set. Key tops 0.715 m |
| **Upright (U3, 131 × 153 × 65 cm)** | Case with top lid, fallboard, knee board; upper front panel (0.76–1.25 m) removed in Overhead; vertical overstrung strings; hammers throw horizontally (strike line 1.08 m; 47 mm blow, 3.2 mm let-off, 16 mm check); **underdamper action: the damper contact line 50 mm below the strike line (1.03 m)**, the damper levers between the hammer shanks and the strings, lifted by the wippen's damper spoon and by the pedal rod, the heads swinging toward the player (`DAMPER_LIFT` axis +z); in Overhead the dampers show below and behind the hammer heads; hammer rail; three pedals (y 0.07, z −0.20); walnut / mahogany / ebony. (Correction to [R:visual_design §4.3], which placed the damper row above the strike line: that is the overdamper action of old British uprights; a U3 is underdamped, as [R:mechanics §5] says) |
| **Harpsichord (a Flemish-style single manual, 228 × 93 × 26 cm [D])** | Straight-sided case with bentside, painted exterior (FLEMISH_CASE), block-printed paper bands inside, lid with the motto *MUSICA LAETITIAE COMES MEDICINA DOLORUM* (public domain), turned oak stand; 61 keys FF–f‴ (36 naturals × 22.7 mm = 0.818 m) between two 40 mm cheek blocks inside 12 mm case sides, bone naturals and black-stained sharps, arcaded key fronts; **6 mm dip**; **122 jacks** (8′ back row, 4′ front row; jack rise = key travel at the jack, ratio 1.0), each with tongue, quill and cloth damper; two register slides; 61 8′ strings and 61 4′ strings on their own bridge; painted soundboard with a gilt rose. Not claimed to be the sampled instrument |

**Vertex formats** (`VertexLayout`): STATIC 12 floats (pos 3, normal 3, uv 2, rgba 4); SKINNED 17 (STATIC + slot 1 + one-hot lane 4); STRING 16 (rest pos 3, direction 3, t along the speaking length 1, side ±1 1, slot 1, lane 4, rgb 3). Meshes over 65,535 vertices are split; indices are read as unsigned. Triangle budgets (T7.1): grand ≤ 26k, upright ≤ 22k, harpsichord ≤ 20k; venue ≤ 18k (T8.4).

### 5.5 The venue: Konzertzimmer build sheet (WP8; [R:visual_design §1.3])

Room frame: x east (long axis), z south (toward the garden windows), y up, origin at the floor centre. 10.5 × 8.0 m, cornice top 4.6 m, cove r = 1.1 m, ceiling 5.7 m [D].

| Element | Geometry | Colour (lit peak, sRGB) |
|---|---|---|
| Wall fields | **not drawn** at Salon (the wearer's room stands in for the white walls); only a candle-lit glow within 1.2 m of a flame, capped at BOISERIE_NEAR | (70,58,44) cap |
| Floor | oak panel parquet, 1 m squares with diagonal fillets, multiplied by light-pool falloffs (r ≈ 2.6 m) | (150,100,55) at pool centre → 0 |
| Dado, pilasters | dado 0.90 m; pilaster strips 0.35 m on a 1.8 m bay pitch carrying vertical gilt trellis with vine leaves | gilt (226,168,78), leaf highlight (255,222,150) |
| Mirrors (N wall) | 3 pier mirrors at x = −3, 0, +3, glass 1.3 × 3.4 m from 0.9 m, arched top with a 0.6 m rocaille crest; S pier glasses 0.9 × 3.0 m at x = ±1.5 | glass black (transparent) except reflected flames |
| Pesne panels | 6 (2 on the N wall at x = ±1.5, 1.1 × 2.4 m; 2 on each short wall beside the door); canvas dark | frame gilt |
| Doors | double doors centred on E and W walls, 1.5 × 3.2 m, supraporte 1.5 × 1.0 m | gilt lines |
| Windows (S wall) | 3 French windows at x = −3, 0, +3, 1.5 × 3.9 m, segmental heads, 3 × 6 panes; panes transparent (the real world is the night garden) | frames (120,96,64), bars (70,56,38) |
| Cornice, cove | 0.30 m cornice with gilt lip; cove cartouches every ≈ 2 m (hound, hare, stag, putto with horn) as gilt relief decals, corner cartouches starting the trellis "towers" | (226,168,78); relief (206,150,66) + emissive (48,32,13) |
| Ceiling | trellis from each corner converging on a central spider web of 16 spokes and 9 turns with a rosette (counts read off a photo when building) | (212,160,74), fading with distance from the chandelier |
| Chandelier | from the rosette; candle ring y ≈ 3.3 m; 12 + 6 candles; ≈ 160 crystals; gilt-bronze arms | crystals flash to (255,248,230) |
| Sconces, candelabra | 2-arm girandoles on each N mirror frame (12 flames), on the S pier glasses (4), beside the doors (4); 2 floor candelabra of 5 lights flanking the instrument at 1.6 m; 2 music-desk candles | flames §5.9 |
| Music stand | Frederick's (Kambly) beside the keyboard, low-poly | gilt |
| Audience | 3 rows × 6 rococo chairs, pitch 0.62 m, rows at z = +1.4, +2.35, +3.3; one merged mesh | frames (170,124,58), damask seats (80,30,26) |
| Lighting | ≈ 50 flames; static parts baked per vertex from all flames at load; ≤ 4 dynamic point lights (chandelier aggregate, two nearest candelabra, nearest sconce group); flicker ±4% global at 6–10 Hz plus hashed per-sprite ±8% brightness, ±15% height | – |

**Palettes:** "Sanssouci 1747" (default, white and gold); "Stadtschloss 1747" (the room where Bach played: wall fields near flames drawn at a dim celadon (46,70,48)). Same geometry.

### 5.6 Views, cameras, listeners and placements (WP7 `Anchors`, WP6 `CameraDirector`)

Piano frame: origin on the floor under the centre of the key fronts; x toward the treble, y up, z toward the player (§2.3 conventions). Values from [R:visual_design §4.6], harpsichord adapted to the single manual. `InstrumentAnchors.camera()` fills the whole `CameraPose`: position, target, FOV, IPD scale, zero parallax, **`roomFrame`** (true for the Hall rows), `clipX` (Action cutaway) and `lidLift` (Overhead); `CameraDirector` only applies the springs and the dip, and converts a piano-frame pose to the room with the Placement.

| Instrument | Framing | Camera position | Target | vFOV | IPD × | Zero parallax |
|---|---|---|---|---|---|---|
| Grand / upright | Player | (−0.10, 1.30, 1.55) | (0, 0.50, −0.12) | 34° | 0.6 | 1.75 m |
| Grand / upright | Player follow | (x_c, 1.15, 0.62) | (x_c, 0.70, −0.08) | 30° | 0.5 | 0.95 m |
| Harpsichord | Player | (−0.06, 1.22, 1.05) | (0, 0.62, −0.10) | 34° | 0.6 | 1.25 m |
| Harpsichord | Player follow | (x_c, 1.10, 0.55) | (x_c, 0.66, −0.08) | 30° | 0.5 | 0.85 m |
| Grand | Action cutaway | (x_cut + 0.95, 0.95, 0.30) | (x_cut, 0.76, −0.24) | 22° | 0.35 | 1.1 m |
| Upright | Action cutaway | (x_cut + 1.05, 1.00, 0.10) | (x_cut, 0.93, −0.20) | 26° | 0.35 | 1.1 m |
| Harpsichord | Action cutaway | (x_cut + 0.60, 0.93, −0.02) | (x_cut, 0.86, −0.42) | 24° | 0.3 | 0.73 m |
| Grand | Action overhead (lid off) | (0, 1.95, 0.55) | (0, 0.84, −0.90) | 44° | 0.5 | 1.8 m |
| Upright | Action overhead (top lid open, upper panel off) | (1.05, 1.40, 0.75) | (0, 1.06, −0.28) | 36° | 0.5 | 1.5 m |
| Harpsichord | Action overhead (lid and jack rail off) | (0, 1.85, 0.45) | (0, 0.80, −0.95) | 44° | 0.5 | 1.8 m |
| Grand, harpsichord (room frame) | Hall wide | (0.4, 1.20, 3.0) | (0, 1.65, −1.9) (M5; was 1.95) | 40° | 1.0 | 4.9 m |
| Grand, harpsichord (room frame) | Hall life-size | (0.4, 1.20, 3.0) | (0, 1.05, −1.9) | 18.27° (CONTROL `--ef fov`) | 1.0 | 4.9 m |
| Upright (room frame) | Hall wide / life-size | (0.4, 1.20, 3.0) | (−1.6, 1.60, −2.65) / (−1.8, 1.00, −2.85) | 40° / 18.27° | 1.0 | 6.0 m |

- The harpsichord Player framing was re-checked for the 0.818 m FF–f‴ keyboard (the research computed 0.795 m for FF–e‴): at ≈ 1.21 m the 44.4° horizontal field spans 0.99 m, so the keyboard fills 83% of the width; unchanged.
- **Tracking:** `x_cut` follows `pose.focusKey` (the highest sounding key) with a ±2-semitone dead band and a critically damped spring (ω = 4 rad/s); only actions within ±6 notes of `x_cut` are drawn, inactive ones dimmed to 40%. `x_c` follows `pose.centroidKey` with ω = 6 rad/s, capped at 0.6 m/s [R:visual_design §4.1].
- **Dip transition** (`CameraDirector`): 250 ms fade-out (a full-screen quad multiplies the frame toward black), cut the camera, lid (`lidLift` 0/1), clip plane (`clipX`), venue level and listener, 250 ms fade-in. Queue depth 1. After a display rest the cut is immediate.

**Placements (piano frame → room frame, `Placement.toRoom`: out = origin + R_y(yaw)·p, where R_y(θ) maps (x, y, z) to (x·cosθ + z·sinθ, y, −x·sinθ + z·cosθ)):**

| Instrument | Origin (room, m) | Yaw | Result |
|---|---|---|---|
| Grand | (−1.00, 0, −1.90) | −90° | keyboard at the west, tail east at x = +1.0; bentside and lid face the audience (south); the centre N mirror behind |
| Harpsichord | (−1.10, 0, −1.90) | −90° | as the grand |
| Upright | (−2.20, 0, −2.95) | +25° | against the N wall with a ≈ 13 cm gap (its treble back corner at z = −3.86), turned toward the audience, seen three-quarter on |

A test (T8.9) places every case corner of every instrument and requires it ≥ 0.05 m inside all four walls and ≥ 0.05 m clear of every fixture footprint in `VenueGeometry.fixtureFootprints` (candelabra, music stand, chairs, door swings).

**Listeners (for §3.12) and sound sources:**

| Instrument | Sound source (piano frame) | Player (both framings) | Action cutaway | Action overhead | Hall (room frame) |
|---|---|---|---|---|---|
| Grand | (0, 0.90, −1.00) | (0, 1.20, 0.55) | (0.30, 1.00, −0.30) | (0, 1.60, 0.10) | (0.4, 1.20, 3.0) |
| Upright | (0, 1.00, −0.45) | (0, 1.20, 0.55) | (0.30, 1.05, 0.05) | (0.50, 1.45, 0.40) | (0.4, 1.20, 3.0) |
| Harpsichord | (0, 0.85, −1.00) | (0, 1.15, 0.50) | (0.20, 0.95, −0.20) | (0, 1.55, 0.10) | (0.4, 1.20, 3.0) |

Direct width: Player 1.0, Action 0.8, Hall 0.4; `worldLocked` only in the Hall. `benchDistanceM` = the Player listener's distance to the source. The listener faces the source: `ListenerPose.forwardYawRad = Conventions.forwardYaw(placement, ear, source)`; a contract test checks that in the grand's Player pose the source azimuth is ≈ 0 and the treble (piano +x, room south) lies to the listener's right.

### 5.7 Animation models (WP5, pure; evaluated from song time every frame, never tweened)

All times in song µs; **mechanical durations are real time** and become song time by `× r` (at rate 0.5 a 35 ms key return still takes 35 ms of real time, i.e. 17.5 ms of song time). The input is the `VisTime` of §2.5 (monotone `tUs`, exposure window, `reseed`, registration). Per-key cursors step forward when `t ≥ tStart_{j+1}` and **back** while `t < tStart_j` (so a held or slewed clock never draws a held key at rest), and are re-seeded by binary search on `reseed`. Each evaluation is O(1) amortised.

**Governing note and lead (all instruments).** For note j on a key, with `j−1` the previous note on the same key:
```
avail_j = on_j − max(on_{j−1} + c(n)·r, off_{j−1})                               (∞ for the first note on the key)
lead_j  = min(tt(v_j)·r, max(on_j − max(on_{j−1} + c(n)·r, off_{j−1} + partialReturnMs·r),  min(12 ms·r, avail_j)))
tStart_j = on_j − lead_j
```
Note j governs from `tStart_j` until `tStart_{j+1}`, which is never before `max(on_j + c(n)·r, off_j)`: a note's contact and key release are always drawn by the pose model, and the next descent never begins before the previous key-up. The stroke of note j starts from `d0` (the dip at `tStart_j`, part-way up after a partial return) and is compressed to the available lead, so the hammer still reaches the string exactly at `on_j`. When `on_j − on_{j−1} < repeatMinMs·r`, the picture draws the **fastest physical repetition**: the key rises only by its partial return and the hammer is relaunched from the repetition lever (grand: from the checked height 0.68; upright: from half the blow, a stated approximation because its jack cannot reset that fast) rather than from rest. The audio plays every re-strike the file contains (§3.9).

**Grand:**
```
HV = clamp(10^((v − 57.96)/71.3), 0.25, 7) m/s ;  s = smoothstep(2.0, 4.5, HV)                    [R:mechanics §1.1–1.2]
tt = clamp((1−s)·98.57·HV^−0.7147 + s·65.19·HV^−0.7268, 20, 230) · travelScale      (ms, key start → contact, isolated stroke)
tb = (1−s)(19.09·HV^−0.3936 − 12.30) + s(59.57·HV^−0.1131 − 51.19)                   (ms, key bed re contact)
ff = min(20, (1−s)·1.63·HV^−1.403 + s·3.04·HV^−1.581)                                (ms, free flight)
tStart = on − lead (above) ;  tBottom = on + tb·r ;  tEscape = on − min(ff·r, 0.5·lead)
key:    u = (t − tStart)/(tBottom − tStart);  pressed = u^1.8;  struck = 1.32u (u<0.25) | 0.33 (u<0.40) | 0.33 + 0.67(u−0.40)/0.60
        dip = d0 + (1 − d0)·((1−s)·pressed + s·struck)
        held: dip = 1;  release from offUs: dip = KeyReturn.dip(dHeld, (t − off)/(keyReturnMs·r))   (smoothstep: starts and ends at rest speed)
hammer (fraction of the 47 mm blow):
        t < tEscape:          h = min(actionRatio·dip·keyDipMm, blow − letOff) / blow   (from the repetition height on a fast repetition)
        tEscape ≤ t < on:     linear to 1.0 at on (free flight)
        on ≤ t < on + c(n)·r: 1.0, contact c(n) = 4·0.2^((n−21)/87) ms
        rebound:              falls at 0.45·HV to (blow − check)/blow = 0.68 (caught by the backcheck) while the key is down
        release:              h = min(0.68, actionRatio·dip·keyDipMm/blow) → rest
escape: 1 between tEscape and the key's half return
damper: held: max(keyDamperLift(min(1, 1.08·dip)), damperLiftByPedal(sustain(t)), latched ? 1 : 0), drawn as 0–6 mm
        release: key part = lift0 · (1 − smoothstep(0, 1, (t − off)/(damperLagMs·r))), lift0 = keyDamperLift(min(1, 1.08·dHeld)):
        it touches the string exactly at off + damperLagMs·r, the frame at which the audio's damping starts
        (damperLagMs = KeyReturn.damperLandMs(keyReturnMs), one implementation in PhysicalCurves; T5.2 checks ±1 ms)
pedals: angle = 5°·p (sustain, sostenuto, una corda); shiftMm = 2.5·smoothstep(0, 1, soft(t)); sostenuto rail 45° → 90°
```

| Velocity | Key starts before the sound (isolated) | Key reaches the bed |
|---|---|---|
| 20 | 230 ms | 18.6 ms after |
| 64 | 86 ms | 5.4 ms after |
| 110 | 20 ms | 1.9 ms before |

**Upright:** `travelScale` 1.05; hammers throw horizontally; check level (47 − 16)/47 = 0.66; key return 50 ms (smoothstep), damper lands 26.2 ms after the key-up; the jack resets only after 80% of key return (repetition ≥ 143 ms; faster repetitions use the approximation above); soft pedal: hammer rest = `soft·22/47` of the blow, the throw scaled to the rest, the hammer rail drawn moving with it (`HAMMER_RAIL`); dampers (under the strike line) swing toward the player by up to 6 mm.

**Harpsichord** (key dip 6 mm, jack rise = key travel, `HarpsiTiming`):
```
tt = HarpsiTiming.leadMs(v)·r bounded by the lead rule above ;  tStart = on − tt ;  u = (t − tStart)/tt
dip = HarpsiTiming.depth(u) = 0.70·u^1.8 (u ≤ 1: reaches the 8′ pluck depth 4.2/6 at on) ; then 0.70 + 0.30·min(1, (t − on)/(0.45·tt)) to the bed
jacks: hammer[k] = jack4[k] = dip (1.0 = against the jack rail)
pluck: the 8′ quill bends its string as dip approaches 0.70 and releases at on; the 4′ at dip 0.433, i.e. at on − staggerMs(v)·r
       (the same function the audio uses, in real time)
release from offUs: dip = HarpsiTiming.returnDip(dHeld, (t − off)/r): a quadratic, gravity-like fall over 55 ms (starts at rest speed);
       the 8′ quill passes back under its string at off + 30.1 ms·r (its tongue deflects 0 → 1 → 0 over 25 ms·r; the release sound
       starts at this frame), the 4′ at off + 41.4 ms·r
damper (cloth on the jack): clamp(dip · 6 mm / 1.5 mm, 0, 1); it touches the string at off + 47.6 ms·r (HarpsiTiming.damperLandMs)
disengaged register (from VisTime.registration, i.e. when the change is heard): its jacks are drawn slid 1.5 mm sideways (quills miss); no pluck flash
```

**Strings (`StringVisual`):** from the EnergyRing (linear RMS lanes): `amp = clamp((20·log10(e) + 60)/60, 0, 1)` (−60 … 0 dBFS → 0 … 1), so the struck string moves with its own voice, and the strings on screen stop when the dampers land and shimmer when they ring in sympathy [P:realism §2.3]. If no slot matches the heard frame and epoch, fall back to the analytic two-stage envelope `A0(HV)·(0.8·e^(−3t/τ) + 0.2·e^(−t/τ))`, τ = T60f/6.91, damped by `e^(−6.91·dt·D/T60d)` (per-frame table lookups). Drawn as a spindle ribbon of half-width `max(0.6 px, 0.6 px + amp·W)` with W = 2.5 px (cutaway), 3.5 px (overhead, an honest ×4–6 exaggeration noted in the About text), 1.5 px (Hall), alpha `min(1, 1.2 px / halfWidth)` (wider and dimmer, like a real blur), a 2–12 Hz single-mode wobble (lower for longer strings, slightly different per string of a unison), and a strike pulse travelling outward from the strike point for 150 ms (`strikeAge`). Copper for wound strings, pale steel for plain.

**`ExposureSampler`:** a contact or pluck at `onUs` is drawn in the one frame whose window `(exposeFromUs, exposeToUs]` contains it (§2.5): hammer forced to 1.0, strike pulse started, `pose.flash[k]` set. The windows tile song time without gaps or overlaps and are centred on their frames (half a frame ahead, half behind), so no strike is ever invisible, none is drawn twice, and the average display offset is zero at 30 and at 20 fps. While paused, after a re-seed and on the first frame the window is empty, so nothing is forced and hammers are never pinned to the strings through a pause.

**`FocusTracker`:** `focusKey` = the highest key that is down or struck within 0.5 s (held ≥ 1.5 s before moving down); `centroidKey` = the mean of sounding keys weighted by `stringAmp`. Springs live in `CameraDirector`.

### 5.8 Batching, uniforms and shaders (WP6)

- **Skinning** [R:visual_design §5.3]: each moving-part mesh is one VBO; every vertex carries `slot` (a vec4 index) and a one-hot `lane` vec4; the vertex shader reads `dot(uState[int(slot)], lane)` (no dynamic component indexing, which ES 2.0 does not guarantee). `partIndex = key − lowKey` for per-key parts; pedals 0 soft/una corda, 1 sostenuto, 2 sustain (left to right).
- **Uniform array per draw:** `uniform vec4 uState[34]` (136 floats) loaded by `UniformPacker` with the right pose lane (keyDip, hammer, jack4, damper, tongue, tongue4, stringAmp, strikeAge) or the action-set block from `InstrumentScene.packActionSet`. **Every skinned program also reads `uShiftX`** (una corda: keys, hammers, action set and the grand's key-frame parts slide 0–2.5 mm; dampers, strings and case do not) **and `uRailM`** (upright hammer rail: hammer rest and rail parts move 0–22 mm toward the strings). ≤ 20 `glUniform4fv` per frame (≈ 9 KB); no `glBufferData` in the steady state; nothing allocated per frame.
- **ACTION_SET block (136 floats):** vec4 0–12 = one header per slot s: `(xM = keyX of the slot's key, dim (1 or 0.4), key, 0)`; vec4 13–32 = 13 × 6 part angles in radians, part types p = 0 key lever (with capstan and backcheck), 1 wippen, 2 jack, 3 repetition lever, 4 hammer shank (with knuckle), 5 damper underlever (upright: damper lever; harpsichord: 0 jack, 1 tongue, 2 register offset); vec4 33 spare. A vertex's slot/lane address its angle (`13 + (6·s + p)/4`), its uv = (s, p) selects the header and `uPivot[p]`. `SkinParams[ACTION_SET]` = 6 × (pivotY, pivotZ, axisSign). A WP7 test packs a known pose; a WP6 test decodes it with the shader's arithmetic; both must agree.
- **`SkinParams`** per kind (floats): `KEY_ROT [pivotY, pivotZ, maxRad]`, `HAMMER_ROT [pivotY, pivotZ, blowRad]`, `DAMPER_LIFT [travelM, axisX, axisY, axisZ]` (grand: up; upright: toward the player), `JACK_LIFT` / `JACK4_LIFT [travelM, registerOffsetM]`, `TONGUE_ROT` / `TONGUE4_ROT [pivotY, pivotZ, maxRad]`, `PEDAL_ROT [pivotY, pivotZ, maxRad]`, `SOSTENUTO_ROT [pivotY, pivotZ, rad45, rad90]`, `HAMMER_RAIL [travelM]`, `LID [hingeX, hingeY, hingeZ, stickRad]`, `SHIFT_X [maxM]`, `STRING [widthPxCutaway, widthPxOverhead, widthPxHall]`, `ACTION_SET [6 × (pivotY, pivotZ, axisSign)]`.
- **Programs** (`Shaders.kt`, GLSL ES 1.00, `FRAG_PRECISION` header from MathCosmos): `lit` (baked vertex light + ≤ 4 Blinn lights + gamma lift), `lacquer` (presence floor, Fresnel rim, candle speculars exponent 96, probe reflection), `skinned` (all SkinKinds, cut-plane `discard`, key bevels, presence floor), `string` (screen-space spindle, min width, pulse), `ribbon` (screen-space AA lines, emissive floor), `sprite` (additive), `decal`, `sectionCap`, `glyph` (GlyphBoard's own), `fade`. The largest uses ≈ 68 vec4 (well under 128).
- **`MaterialTable`** (contract; program for static meshes, uniforms for all): LACQUER → lacquer (spec 96, F0 0.05, presence floor, rim); WOOD_CASE, FLEMISH_CASE, PLATE, SOUNDBOARD, HARPSI_SOUNDBOARD, PAPER, CHAIR_FRAME, DAMASK, PARQUET_POOL, WINDOW_FRAME, BRASS → lit (spec 24–64 per material, no floor); IVORY, EBONY_KEY (presence floor), BONE, KEY_FRONT, KEYLEVER, ACTION_WOOD, FELT, DAMPER_TOP, CLOTH_RED, LEATHER, QUILL → lit when static, skinned when moving; STEEL, COPPER → string; GILT, EDGE_GILT → ribbon (emissive floor 0.2), GILT_EMISSIVE → decal; MIRROR_FRAME → ribbon; SECTION_CAP → sectionCap. A static mesh's `program` must match its material's; skinned meshes use SKINNED and strings STRING (T7.8).

### 5.9 Colour on the waveguide (black is transparent)

1. **Black instruments are reflectors, not dark colours** [R:visual_design §3.2]: presence floor `rgb = max(lit, uFloor)` on instrument surfaces only (default (22,18,15), calibrated with the Display floor card); Fresnel rim `pow(1 − N·V, 3) × (120,78,40) × 0.8`; candle Blinn highlights from 4 lights; a **baked 128 × 64 equirectangular probe** of the room's flames and gilt, `refl = probe(reflect(−V,N)) × schlick(F0 = 0.05)`, so candle streaks slide over the lid as the head moves; the optional feature-edge overlay (dim gilt 1.5 px ribbons). Walnut is the upright's default finish. The Flemish harpsichord's bone naturals are bright and legible by themselves.
2. **Surfaces:** textures and vertex colours lifted with `pow(c, 0.85)` [R:engine_reuse §2.5]; no surface wider than ≈ 40 px above 220; 255 reserved for flame cores, sparkles and specular pinpoints; blue kept low.
3. **The room is sparse light:** gilt ribbons with emissive floor, flames and their reflections, floor pools, chair silhouettes. Shadows are holes that read only because a lit floor pool surrounds them, so the pool is always drawn.
4. **Venue levels** (Auto picks by view; the thermal cap overrides; the user overrides both):

| Level | What is drawn | APL target | Auto for |
|---|---|---|---|
| Salon | all gilt, all flames + mirror images, chandelier with sparkle, floor pools, chairs, wall glow near flames | ≤ 12% | Hall |
| Stage | instrument, floor pool (r 2.6 m), the two candelabra, the mirror bay behind with its sconces; everything else fades by distance `1 − smoothstep(3.5, 6.0, d)` | ≤ 9% | Player, Action |
| Instrument | instrument + contact pool | ≤ 6% | – |
| Passthrough | instrument only, edge overlay on, presence floor raised | ≤ 5% | daylight |

**Palette** (`contract/Pal`, sRGB lit-peak [R:visual_design §3.5] plus the Flemish tokens [D]):

| Token | RGB | Use |
|---|---|---|
| FLAME_CORE / BODY / HALO | 255,244,214 / 255,190,90 / 255,140,50 | candle sprites (halo alpha 0.18, r ≈ 12 cm) |
| GILT_HI / LIT / SHADE / EMISSIVE | 255,222,150 / 226,168,78 / 96,64,26 / 48,32,13 | rocaille, trellis, frames, stand |
| BOISERIE_NEAR (cap) · STADTSCHLOSS_GREEN | 70,58,44 · 46,70,48 | wall glow near flames |
| PARQUET_POOL | 150,100,55 | pool centre |
| EBONY_FLOOR / RIM / SPEC | 22,18,15 / 120,78,40 / 255,214,160 | lacquer, black keys |
| IVORY / IVORY_SIDE · BONE | 232,214,178 / 170,150,118 · 226,212,182 | piano naturals · harpsichord naturals |
| BRASS_HI / MID · PLATE_GOLD · SOUNDBOARD | 224,172,84 / 140,98,40 · 196,150,72 · 176,138,84 (shadowed ≤ 0.25) | pedals · plate · soundboard |
| STEEL_HI / BASE · COPPER | 210,210,214 / 120,120,126 · 214,136,70 | strings |
| FELT · LEATHER · DAMPER_TOP | 236,230,212 · 176,128,80 · 150,112,70 | hammers, dampers |
| KEYLEVER / ACTION_WOOD · CLOTH_RED · SECTION_CAP | 214,186,140 / 196,158,104 · 180,40,40 · 200,170,120 + gilt outline | Action view |
| FLEMISH_CASE · FLEMISH_PAPER · HARPSI_SOUNDBOARD | 156,86,52 · 214,190,142 (× 0.6) · 214,184,128 (× 0.7) with flowers 200,70,60 / 90,120,190 / 90,140,70 and a gilt rose 226,176,86 | harpsichord |
| OAK · WALNUT · MAHOGANY | 168,116,62 · 150,98,56 · 150,72,40 | harpsichord stand, upright finishes |
| HUD_TEXT / HUD_ACCENT | 255,236,200 / 240,190,100 | overlays |

### 5.10 Text in the scene

2D text is Android Views (§1.8). In 3D, `GlyphBoard` (copied from MathCosmos; label cap lowered from 96 to 32, ≤ 3 new labels rasterised per frame, bloom pass): the note name beside the cutaway hammer (`C4`, sized to 16 px at that depth, placed beside, never over, the subject) and the work title on a sheet on the music desk in the Player view (`drawOriented`; the catalogue title from `RenderControl.setTitle`, not the MIDI track name). The fallboard lettering "HAMMERKLAVIER" is baked into the instrument texture. No maker's trademark appears anywhere.

### 5.11 The quality ladder (one governor for picture and sound, WP0)

Inputs: battery temperature from the sticky `ACTION_BATTERY_CHANGED` intent (tenths °C) plus the `PowerManager` thermal status listener, inside `runCatching`. **The governor's lifetime is the engine's** (registered when AudioOutput or KitManager start, unregistered when both are stopped), so the temperature keeps updating with the display off, where the voicer's 37.5 °C gate, `setPlaybackHint`, the voice cap and the display-off soak depend on it; only the render-side reactions (fps, room cap, brightness) wait for the activity to be resumed. The thermal status reads 0 on this device even when hot [R:engine_reuse §0], so battery temperature does the work. Each relaxation is one step at a time. C is the Q0 voice cap from `EngineBench` (§3.14).

| Level | Enter (battery) or status | Relax below | fps | Room cap | Mirror flames | Crystals | Brightness cap | Voices | Interp. | Spectral damping | Combs (dispersion) | FDN lines | Voicing |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **Q0** | < 39.0 °C | – | 30 | Salon | on | 160 | system | C | Hermite | on | 88 (on) | 8 | allowed when not playing (< 37.5 °C) |
| **Q1** | ≥ 39.0 °C or MODERATE | 37.5 °C | 30 | Salon | off | 60 | system | 0.75 C (≥ 48) | Hermite | on | 88 (on) | 8 | paused |
| **Q2** | ≥ 42.0 °C or SEVERE | 40.5 °C | 20 | Stage | off | 0 | 0.6 | 0.625 C (≥ 40) | linear | off | 44 (off) | 8 | paused |
| **Q3 display rest** | ≥ 44.0 °C or CRITICAL | 42.5 °C | GL paused, waveguide transparent; status `Resting the display to cool · music continues` | – | – | – | – | 0.5 C (≥ 32) | linear | off | 0 | 4 | paused |

- The ladder sheds decoration before frame rate (keys move fast; at 20 fps a forte key reads as a jump). MSAA is not on the ladder (it is fixed for the session, §5.1).
- The brightness cap is `window.attributes.screenBrightness` on the activity's window (not a system setting); −1 restores the user's brightness.
- `HKThermal status=… battery=…C -> Q2` is logged on every change, also with the display off; `▲ warm` shows at Q ≥ 2; `--ei quality N` forces a level, `-1` returns to automatic; `--ei faketemp 405` feeds the governor 40.5 °C.

---

## 6. Asset pipeline, download manifest and credits (WP11)

### 6.1 Environment and layout

**Only what is on the Mac [M]:** Python 3.14 standard library + numpy 2.4.3, Homebrew `ffmpeg`/`ffprobe` (libopus; resampling, when needed, with `aresample=48000:resampler=swr:filter_size=64:cutoff=0.97` because there is no soxr). **No pip installs** (no scipy, soundfile, mido, jsonschema, PIL). Tests run with `python3 -m unittest discover tools/pipeline/tests`. Output is deterministic: the same inputs give byte-identical kit files.

```
tools/pipeline/
  common.py          paths; polite sequential HTTP (urllib; User-Agent "Hammerklavier-pipeline/1.0 (+github tropicalstream)");
                     retries; resumable .part files; sha1 hex/base32; TSV readers
  fetch.py           --manifest samples|midi --tier core|hd [--group G] [--dry-run]; one file at a time; ≥ 2 s between
                     Wayback/CDX requests (CDX returns 503 under parallel load), 0.3 s for GitHub raw; checks bytes
                     (samples) and SHA-1 base32 (Krueger); Krueger: primary URL first, Wayback raw on HTTP 418;
                     REFUSES to run until tools/pipeline/APPROVED contains the approved manifest id (§6.2)
  sfz.py             Salamander Data/*.txt ($OFF offsets in vel_XX.txt, tune_nat.txt, tune_ret.txt) and VCSL SFZ regions
                     (sample, lokey, hikey, pitch_keycenter, lovel, hivel, amp_veltrack, ampeg_release, trigger)
  audio.py           ffmpeg → float32 numpy pipes; onset; partial-series fit (FFT peaks → f0 and inharmonicity B);
                     A-weighted attack loudness (FFT weighting); 10 ms RMS envelope; Schroeder and early-decay T60;
                     isotonic regression (PAV); layer correlation; direct-to-reverberant ratio
  kit_build.py       --kit grand-hd|grand-std|upright|harpsichord|stub → assets/instruments/<id>/{map.json, env.bin, u/*.opus}
                     (one Ogg Opus stream per decode unit) + build/kit-<id>-report.txt
  export_test_regions.py  after the download: C3, C4, C5 at v10 and v13 and the una corda C4 → core/src/test/resources/wp11/real/
  make_stub_bank.py  30 roots × 1 layer × 1.2 s additive partials (12 partials, B = 0.0004, two-stage decay) → instruments/stub/,
                     plus a 60 s decode-bench stream with the kit encoding (instruments/stub/u/bench.opus)
  make_probe.py      0.5 s silence + one-sample click (0.5 FS) at frame 4800, kit encoding → instruments/probe.opus
  make_test_midis.py our own CC0 test files → assets/midi/test/ (§4.5 twins + format0/format1 pair, smpte25, running_status)
  midi_extract.py    lists every Sankey zip (writes build/sankey_listing.txt), extracts the entries named in
                     sankey_map.tsv byte-for-byte, records each entry's SHA-1
  smf_stats.py       an independent stdlib SMF reader (≈ 150 lines) → build/midi_stats.json and
                     core/src/test/resources/wp11/midi_facts_golden.json (for WP1's MidiCorpusTest)
  build_catalog.py   catalog_src.json + midi_stats → assets/catalog.json (checks §4.7); a Start-here-only partial catalogue
                     for M2 with --partial
  build_credits.py   ledger.csv → assets/licenses/*, assets/credits.txt, CREDITS.md, NOTICE
  check_ledger.py    every file under assets/instruments and assets/midi has a ledger row; modified=no files match their source SHA-1
  size_report.py     asset and APK size table; fails above the caps in §6.10
  catalog_src.json   hand-written shelves, ids, titles, composers, defaults, sources (from §4.7)
  sankey_map.tsv     zip → entry → movement id (filled after the listing)
  ledger.csv         one row per shipped asset
  tests/             test_onset.py, test_partials.py, test_opus_roundtrip.py, test_mapjson.py, test_smf_stats.py, test_ledger.py,
                     test_stub_determinism.py, test_levelcurve.py, test_defaults_match_kotlin.py
tools/cache/         downloads (git-ignored; already in .gitignore)
```

**Git:** `.gitattributes` (WP0, on WP11's request) puts `app/src/main/assets/instruments/**/*.opus`, `**/*.bin` and `core/src/test/resources/wp11/real/**` in LFS (the MathCosmos practice); commit kits only at milestones (GitHub's free LFS is 1 GB storage and 1 GB/month bandwidth). MIDI is small and goes in plain git. APKs are never committed.

### 6.2 The download gate (nothing is fetched before this)

WP11's first action is to put `docs/DOWNLOADS.md` (the manifest table and total) to the user with this request, verbatim, and wait:

> **Hammerklavier needs to download its instrument recordings and music files. Please approve one sample option and the MIDI list.**
> **Samples, option HD (recommended):** 884 files, 1,345,758,735 bytes (1,283.4 MiB), from github.com/sfzinstruments/SalamanderGrandPiano (CC-BY 3.0, declared public domain by the author), github.com/sgossner/VCSL and github.com/sgossner/VSCO-2-CE (CC0). Gives the grand all 16 recorded dynamic layers. Full list: `docs/manifests/samples-v2.tsv`.
> **Samples, option Standard:** 584 of those files (the rows marked `core`), 889,470,255 bytes (848.3 MiB). Gives a 6-layer grand.
> **MIDI and licence texts:** 131 files, ≈ 5.6 MB, from piano-midi.de (or its Wayback copies), jsbach.net, johnsankey.ca, upload.wikimedia.org, mutopiaproject.org and creativecommons.org. Full list: `docs/manifests/midi.tsv`.
> **Two more need you:** IMSLP asks visitors to click "I understand" before downloading. If you want Handel's "Harmonious Blacksmith" and Rameau's *La Poule*, open https://imslp.org/wiki/Special:ImagefromIndex/365752 and https://imslp.org/wiki/Special:ImagefromIndex/340106, click through, and put the two .mid files in `tools/cache/midi/imslp/`.
> **Optional:** Couperin's *Les Barricades mystérieuses* has no stated licence; it is left out unless you get David Madore's permission.

The approval is recorded in `tools/pipeline/APPROVED` (`samples-hd` or `samples-standard`, and `midi`). Until then M0–M1 run on the synthesized stand-in bank and the test MIDIs, and every visual milestone works without a download. Nothing else is downloaded: every build dependency is already in the Gradle cache (§2.2), and the Concentus fallback (§3.3) would need its own approval.

### 6.3 Download manifest: samples (`docs/manifests/samples-v2.tsv`, 884 rows: tier, group, need, filename, trigger key, bytes, licence, URL, remark)

Every URL was built from the repository trees; the research checked the 6-layer files with HEAD (200, sizes match) [R:sampled-instruments §4]; the 10 extra layers' sizes come from the GitHub tree API (read 2026-09-22, not truncated: 480 sustain files, 726,974,711 B). `#` is URL-encoded as `%23`, spaces as `%20`, commas as `%2C`, `'` as `%27`.

**Salamander Grand Piano V3** — base `https://raw.githubusercontent.com/sfzinstruments/SalamanderGrandPiano/master/` — licence CC-BY 3.0 (author declared public domain 2022-03-04). Roots (30, scientific octaves, C4 = 60): A0 C1 D#1 F#1 A1 C2 D#2 F#2 A2 C3 D#3 F#3 A3 C4 D#4 F#4 A4 C5 D#5 F#5 A5 C6 D#6 F#6 A6 C7 D#7 F#7 A7 C8 (MIDI 21, 24, 27 … 105, 108).

| Tier | Group | Files | Bytes | Paths |
|---|---|---|---|---|
| core | grand-sustain | 180 | 270,686,231 | `Samples/<root>v<L>.flac` for L ∈ {1, 4, 7, 10, 13, 16} |
| **hd** | grand-sustain-hd | 300 | 456,288,480 | `Samples/<root>v<L>.flac` for L ∈ {2, 3, 5, 6, 8, 9, 11, 12, 14, 15} |
| core | grand-release | 88 | 5,609,688 | `Samples/rel1.flac` … `Samples/rel88.flac` (rel n = MIDI 20 + n) |
| core | grand-pedal | 4 | 1,802,873 | `Samples/pedalD1.flac`, `pedalD2.flac`, `pedalU1.flac`, `pedalU2.flac` |
| core | grand-docs | 26 | 54,201 | `Data/{hammer,notes,pedal,region,str_res,tune_nat,tune_ret}.txt`, `Data/vel_01.txt` … `vel_16.txt`, `LICENSE`, `README.md`, `Salamander Grand Piano V3.sfz` |

Not fetched: the `harmL/harmS/harmV3` string-resonance samples (the 88 combs replace them).

**VCSL "Upright Piano, Knight"** — base `https://raw.githubusercontent.com/sgossner/VCSL/master/Chordophones/Zithers/Upright%20Piano%2C%20Knight/` — CC0 1.0. Roots (45, Yamaha octave naming, C3 = 60): A-1 B-1 C#0 D#0 F0 G0 A0 B0 C#1 D#1 F1 G1 A1 B1 C#2 D#2 F2 G2 A2 B2 C#3 D#3 F3 G3 A3 B3 C#4 D#4 F4 G4 A4 B4 C#5 D#5 F5 G5 A5 B5 C#6 D#6 F6 G6 A6 B6 C7 (MIDI 21, 23 … 107, 108).

| Tier | Group | Files | Bytes | Paths |
|---|---|---|---|---|
| core | upright-sustain | 90 | 364,952,496 | `Sustains/Player_vl1_rr1_<root>.wav`, `Sustains/Player_vl2_rr1_<root>.wav` |
| core | upright-release | 45 | 46,315,218 | `Releases/Player_rel_rr1_<root>.wav` |
| core | upright-pedal | 8 | 8,495,984 | `Pedal/On/Player_PedOn_000…003.wav`, `Pedal/Off/Player_PedOff_000…003.wav` |
| core | upright-docs | 2 | 560 | `Info.txt`, `PitchValueConversionChart.txt` |
| core | vcsl-docs | 2 | 11,413 | `https://raw.githubusercontent.com/sgossner/VCSL/master/LICENSE`, `…/README.md` |

**VSCO-2 CE "Upright Piano"** (the same piano's pp layer) — base `https://raw.githubusercontent.com/sgossner/VSCO-2-CE/master/` — CC0 1.0.

| Tier | Group | Files | Bytes | Paths |
|---|---|---|---|---|
| core | upright-sustain-pp | 23 | 65,298,056 | `Keys/Upright%20Piano/Player_dyn1_rr1_000.wav`, `_002`, … `_044` (even numbers; 000 = MIDI 21, 002 = 25, … 044 = 108) |
| core | upright-pp-docs | 4 | 8,951 | `Keys/Upright%20Piano/Info.txt`, `Keys/Upright%20Piano/MappingChart.txt`, `Readme.txt`, `LICENSE` |

**VCSL "Harpsichord, Flemish"** — base `https://raw.githubusercontent.com/sgossner/VCSL/master/Chordophones/Zithers/Harpsichord%2C%20Flemish/` — CC0 1.0. Roots and ranges are taken from the VCSL SFZ, not the file names (the 4′ files are named by sounding pitch, an octave above their key).

| Tier | Group | Files | Bytes | Paths |
|---|---|---|---|---|
| core | harpsichord-8ft | 28 | 65,844,716 | `Sustains/Low/HarpsiRH_Low_Far_<n>_rr1.wav`, n ∈ F#0 G#0 A#0 C1 D1 E1 F#1 G#1 A#1 C2 D2 E2 F#2 G#2 A#2 C3 D3 E3 F#3 G#3 A#3 C4 D4 E4 F#4 G#4 A#4 C5 (keys 30, 32 … 84) |
| core | harpsichord-8ft-release | 28 | 8,180,150 | `Releases/Low/HarpsiRH_LowRel_Far_<n>_rr1.wav`, same 28 |
| core | harpsichord-4ft | 26 | 45,570,610 | `Sustains/High/HarpsiRH_High_Far_<n>_rr1.wav`, n ∈ F#1 G#1 A#1 C#2 E2 F#2 G#2 A#2 C#3 E3 F#3 G#3 C#4 D4 E4 F#4 G#4 A#4 C5 D5 E5 F#5 G#5 A#5 C6, plus `HarpsiRH_High_Far_A3_rr2.wav` (keys 30, 32, 34, 37, 40, 42, 44, 46, 49, 52, 54, 56, 57, 61, 62, 64, 66, 68, 70, 72, 74, 76, 78, 80, 82, 84) |
| core | harpsichord-4ft-release | 26 | 6,587,614 | `Releases/High/HarpsiRH_HighRel_Far_<n>_rr1.wav`, the same 26 notes (A3 is `rr1`) |
| core | vcsl-sfz-maps | 4 | 51,494 | `https://raw.githubusercontent.com/sgossner/VCSL/sfz/Chordophones/Zithers/` + `Upright%20Piano%2C%20Knight.sfz`, `Harpsichord%2C%20Flemish%20-%20Full.sfz`, `Harpsichord%2C%20Flemish%20-%204%27.sfz`, `Harpsichord%2C%20Flemish%20-%208%27.sfz` |

Not fetched: the English harpsichord's lute stop and its keyswitch SFZ (another instrument).

| Totals | Files | Bytes | MiB |
|---|---|---|---|
| Standard (tier core) | 584 | 889,470,255 | 848.3 |
| HD extra (tier hd) | 300 | 456,288,480 | 435.2 |
| **HD total** | **884** | **1,345,758,735** | **1,283.4** |

### 6.4 Download manifest: MIDI and licence texts (`docs/manifests/midi.tsv`, 134 rows)

Downloaded into `tools/cache/midi/<group>/`; single files are copied byte-for-byte to the `assets/midi/…` path shown; zips are extracted entry-by-entry per `sankey_map.tsv`.

**Sankey** — licence: Sankey free-copy notice (copy and distribute unmodified originals with the notice) [R:repertoire §3].

| URL | Bytes | Contents |
|---|---|---|
| http://www.jsbach.net/midi/sankey/772-786.zip | 32,246 | Inventions |
| http://www.jsbach.net/midi/sankey/787-801.zip | 32,102 | Sinfonias |
| http://www.jsbach.net/midi/sankey/846-869.zip | 145,153 | WTC I |
| http://www.jsbach.net/midi/sankey/870-881.zip | 75,637 | WTC II 1–12 |
| http://www.jsbach.net/midi/sankey/806-811.zip | 110,534 | English Suites |
| http://www.jsbach.net/midi/sankey/812-817.zip | 59,590 | French Suites |
| http://www.jsbach.net/midi/sankey/825-830.zip | 110,497 | Partitas |
| http://www.jsbach.net/midi/sankey/910-916.zip | 86,068 | Toccatas |
| http://www.jsbach.net/midi/sankey/972-987.zip | 151,298 | Concerto transcriptions |
| http://www.jsbach.net/midi/sankey/bwv988.zip | 58,273 | Goldberg Variations |
| http://www.jsbach.net/midi/sankey/misc1.zip | 68,757 | BWV 963, 965, 966, 967, 989, **992**, 993 |
| http://www.jsbach.net/midi/sankey/misc3.zip | 45,305 | BWV 894, 895, 899, 900, 901, **903** |
| http://www.jsbach.net/midi/sankey/bwv971.mid → `sankey/bach/bwv971.mid` | 54,302 | Italian Concerto |
| https://www.johnsankey.ca/data/scarlatti.zip | 1,434,446 | K. 1–555 (10 bundled) |
| https://www.johnsankey.ca/copyright.html → `licenses/SANKEY.txt` (text verbatim) | – | the notice |

**Wikimedia Commons** (descriptive User-Agent) — base `https://upload.wikimedia.org/wikipedia/commons/`

| Path → asset | Bytes | Licence |
|---|---|---|
| `7/7a/Ludwig_van_Beethoven_-_Bagatelles_Op._126.mid` → `commons/beethoven_op126_bednarek.mid` | 185,829 | CC0 |
| `5/5b/W._A._Mozart%2C_Piano_Sonata_No._3%2C_K_281.mid` → `commons/mozart_k281_bednarek.mid` | 112,952 | CC0 |
| `4/47/K265_%28Ah_vous_dirai-je%2C_Maman%29.mid` → `commons/mozart_k265_bednarek.mid` | 44,090 | PD-self |
| `a/a9/Beethoven_Op._33_no._1.mid` → `commons/beethoven_op33_1_bednarek.mid` | 9,014 | PD-self |
| `9/9d/Beethoven_Op._33_no._4.mid` → `commons/beethoven_op33_4_bednarek.mid` | 8,636 | PD-self |
| `0/00/C_P_E_Bach_Solfeggio.mid` → `commons/cpe_bach_solfeggietto_nieb.mid` | 7,605 | CC BY-SA 3.0 (dual GFDL; used under CC BY-SA 3.0) |
| `e/e0/RAMEAU_Tambourin.mid` → `commons/rameau_tambourin_frantz.mid` | 5,524 | PD |

**Mutopia** (public domain) — base `https://www.mutopiaproject.org/ftp/`

| Path → asset | Bytes |
|---|---|
| `RameauJP/plaintes/plaintes.mid` → `mutopia/rameau_tendres_plaintes.mid` | 6,061 |
| `RameauJP/sauvages/sauvages.mid` → `mutopia/rameau_sauvages.mid` | 7,171 |
| `BachCPE/cpe-bach-rondo/cpe-bach-rondo.mid` → `mutopia/cpe_bach_rondo_h288.mid` | 15,220 |
| `MozartWA/KV397/Fantasia/Fantasia.mid` → `mutopia/mozart_k397.mid` | 13,485 |
| `MozartWA/KV457/sonata1/sonata1.mid`, `sonata2/sonata2.mid`, `sonata3/sonata3.mid` → `mutopia/mozart_k457_1..3.mid` | 20,899 / 17,611 / 21,088 |
| `BeethovenLv/O31/LVB_Sonate_31no2_1/LVB_Sonate_31no2_1.mid`, `…_2/…_2.mid` → `mutopia/beethoven_op31_2_1..2.mid` | 26,263 / 14,632 |
| `BeethovenLv/O49/LVB_Sonate_49no2_1/LVB_Sonate_49no2_1.mid`, `…_2/…_2.mid` → `mutopia/beethoven_op49_2_1..2.mid` | 14,953 / 11,560 |
| `BeethovenLv/O111/lvb_sonate_111_1/lvb_sonate_111_1.mid` → `mutopia/beethoven_op111_1.mid` | 47,339 |
| `BeethovenLv/O129/beethoven_rondo_op129/beethoven_rondo_op129.mid` → `mutopia/beethoven_op129.mid` | 46,761 |

**User click / conditional:** IMSLP #365752 → `imslp/handel_hwv430_gouin.mid` (≈ 20 KB, CC BY-SA 4.0); IMSLP #340106 → `imslp/rameau_la_poule_gouin.mid` (≈ 20 KB, CC BY-SA 4.0); http://www.madore.org/~david/music/midi/couperin.mid → `madore/couperin_barricades.mid` (5,803 B, no stated licence: excluded unless permitted).

**Licence texts** → `assets/licenses/`: `CC-BY-SA-3.0-DE.txt` (https://creativecommons.org/licenses/by-sa/3.0/de/legalcode.txt; if that 404s, the text content of the HTML legalcode), `CC-BY-SA-4.0.txt` (…/by-sa/4.0/legalcode.txt), `CC-BY-SA-3.0.txt` (…/by-sa/3.0/legalcode.txt), `CC-BY-3.0.txt` (…/by/3.0/legalcode.txt), `CC0-1.0.txt` (…/publicdomain/zero/1.0/legalcode.txt).

**Krueger** (piano-midi.de, CC BY-SA 3.0 DE; 91 files). Primary `http://www.piano-midi.de/midis/<file>`; fallback `https://web.archive.org/web/<timestamp>id_/http://<host>/midis/<file>` with host `www.piano-midi.de` (www) or `piano-midi.de` (bare); every file must match its SHA-1 (base32 of the SHA-1 digest) [R:repertoire §8.6]. Asset path `midi/krueger/<file>`.

| # | File under `midi/krueger/` | Site size | Wayback timestamp | Host | SHA-1 (base32) |
|---|---|---|---|---|---|
| 1 | `bach/bach_846.mid` | 11 KB | 20051106043322 | www | `O5M3TGSHC4K3UAGV22HXFR5YHVD2EXJJ` |
| 2 | `bach/bach_847.mid` | 15 KB | 20051106042842 | www | `UHKM4GXAFTRLVO4REVDVPNRH4VKQF3VG` |
| 3 | `bach/bach_850.mid` | 12 KB | 20051106042906 | www | `ALJTLIPWUSBPP5MSUSSQITNYVZKBLXPR` |
| 4 | `beethoven/beethoven_opus10_1.mid` | 35 KB | 20130508074432 | bare | `7LEVCB3IYJSCGQG7DDJJZ2S6Z2N4BLOQ` |
| 5 | `beethoven/beethoven_opus10_2.mid` | 17 KB | 20130508065628 | bare | `R6QCZAICW7LN4STESHUUYO7FYBIIB4Y5` |
| 6 | `beethoven/beethoven_opus10_3.mid` | 28 KB | 20130508043202 | bare | `B72WJX6SUYKEYB456Y3RGFNNSXE5IJZS` |
| 7 | `beethoven/pathetique_1.mid` | 53 KB | 20130508073649 | bare | `WZC4E4PKNNKDCRRX2X3DYE7A4DJLXX6Y` |
| 8 | `beethoven/pathetique_2.mid` | 16 KB | 20130508071854 | bare | `MDLMZFGNP3ALDCC4ZPPIUKPG3FDCUZAD` |
| 9 | `beethoven/pathetique_3.mid` | 28 KB | 20130508044358 | bare | `X2RFPSNNA5XST6URUOYKL4XCQRMUTHZV` |
| 10 | `beethoven/beethoven_opus22_1.mid` | 51 KB | 20130508105431 | bare | `ZXRV3PZOHKSAPTCXMUBHGGNN3EBV3KWD` |
| 11 | `beethoven/beethoven_opus22_2.mid` | 21 KB | 20130508030027 | bare | `4LSGLBNO3N572Q5VMY7R6443ZY2GZJYZ` |
| 12 | `beethoven/beethoven_opus22_3.mid` | 19 KB | 20130508053758 | bare | `2Y6JTWNL7HF4XJFK3O3VUD76RMYJRDPR` |
| 13 | `beethoven/beethoven_opus22_4.mid` | 30 KB | 20130508052831 | bare | `ESY3QSLXATRCJ6KJ6DKTFKOASGWECTJP` |
| 14 | `beethoven/mond_1.mid` | 16 KB | 20130508075435 | bare | `Y25RL32DWPPMF4GQMCP7DNZNZVSLQ3GT` |
| 15 | `beethoven/mond_2.mid` | 10 KB | 20130508070725 | bare | `N5Z7FM4DP3ORT4UXOGQQMOPLIKK47YRE` |
| 16 | `beethoven/mond_3.mid` | 52 KB | 20130508031644 | bare | `DMXTWZCB5E3HEOQBAOHUCA2GHFMUTWVK` |
| 17 | `beethoven/waldstein_1.mid` | 80 KB | 20130508110718 | bare | `ATSPCVPY7O6IBFA52CKLZVSZOE4CQ7TB` |
| 18 | `beethoven/waldstein_2.mid` | 6 KB | 20130508022353 | bare | `V6NIPHYOZVZF7GP366KCFUGPBDUDP57U` |
| 19 | `beethoven/waldstein_3.mid` | 75 KB | 20130508052551 | bare | `EVLQLKB3YJRBMYE63ZBB73VGDRVMEGJG` |
| 20 | `beethoven/appass_1.mid` | 72 KB | 20130508061514 | bare | `3AM2U2MTQVDT6XU4VGB6TIVA5JPJ3QIJ` |
| 21 | `beethoven/appass_2.mid` | 21 KB | 20130508083008 | bare | `5CDCRDGTTM6HEMQIEGI7SCGP6ALBFENT` |
| 22 | `beethoven/appass_3.mid` | 67 KB | 20130508111722 | bare | `7ZN4JRHORRC4GABNKXBIH6JF4BTVBLFV` |
| 23 | `beethoven/beethoven_les_adieux_1.mid` | 33 KB | 20130508032400 | bare | `GZF66UOA6YVUHNROBLTPU4BHLPEKAES3` |
| 24 | `beethoven/beethoven_les_adieux_2.mid` | 10 KB | 20130508102458 | bare | `YE2OGBD7XYZTP776DL5EGHT6GDMDI5T2` |
| 25 | `beethoven/beethoven_les_adieux_3.mid` | 45 KB | 20130508075019 | bare | `V6MTLJEAVZUEV2MVCVQA6QJXFRD5TS4V` |
| 26 | `beethoven/elise.mid` | 14 KB | 20130508080625 | bare | `ZNLHPDGBI4CWLSLRDXR4CQ2BOYOXK4H7` |
| 27 | `beethoven/beethoven_opus90_1.mid` | 24 KB | 20150905201019 | bare | `YYACJ6XP7TJJRMMZ2MPXAUJQ544V2M5N` |
| 28 | `beethoven/beethoven_opus90_2.mid` | 24 KB | 20160207035109 | bare | `W4UQIASGTMHZPD34Y2AVZJYRACOFZFJI` |
| 29 | `beethoven/beethoven_hammerklavier_1.mid` | 73 KB | 20130508100249 | bare | `QK4QBBMFW4SWQETC4NJ5SG2ZBTZPBFXK` |
| 30 | `beethoven/beethoven_hammerklavier_2.mid` | 19 KB | 20130508051020 | bare | `BH2NGHY4MC7A4QSPM3GK4P5RYYZKJOSH` |
| 31 | `beethoven/beethoven_hammerklavier_3.mid` | 45 KB | 20130508062502 | bare | `T37V3HPQTHPUMEB6WX4TND2EY6GLDFPP` |
| 32 | `beethoven/beethoven_hammerklavier_4.mid` | 80 KB | 20130508064355 | bare | `V56TACQCV443NPQFEZHFO6ML7MRVN7NT` |
| 33 | `mozart/mz_311_1.mid` | 29 KB | 20140518095320 | www | `F54K3B4ABUZY6KFAIYNLRF6GEPY6LCRM` |
| 34 | `mozart/mz_311_2.mid` | 16 KB | 20140630135947 | bare | `YR3T3SNEKHOVDI456WVEZNKQ4FRQNORH` |
| 35 | `mozart/mz_311_3.mid` | 35 KB | 20140630084141 | bare | `ALMKLHZQSSOGJVPTV5MHXHIAYA3DIGKZ` |
| 36 | `mozart/mz_330_1.mid` | 41 KB | 20140630150708 | bare | `QWKAEVNI6P2QQI6GZOSYHISX4GXFIAZM` |
| 37 | `mozart/mz_330_2.mid` | 18 KB | 20140630182055 | bare | `X723G2MDUT6655QIBRODQUFIJQKZNTVL` |
| 38 | `mozart/mz_330_3.mid` | 39 KB | 20140630213903 | bare | `35VS3S3SD2D55W7KULKK6VFQSCMPRTM6` |
| 39 | `mozart/mz_331_1.mid` | 60 KB | 20140630180722 | bare | `AVF25FF6V7KZX3KOS6LWUIK76OUNGPDR` |
| 40 | `mozart/mz_331_2.mid` | 31 KB | 20140630151555 | bare | `RIKXDQDW3U4ZIBNNYROP5B2RSQFHAAO3` |
| 41 | `mozart/mz_331_3.mid` | 26 KB | 20130802001702 | www | `FYBRQFUZ5Q6JEHUUZN3CZZA73DTTBV4W` |
| 42 | `mozart/mz_332_1.mid` | 51 KB | 20140630061445 | bare | `XUU7GCQGO3IVGNKNRBYCUPHR5RYYJH6R` |
| 43 | `mozart/mz_332_2.mid` | 15 KB | 20140630235839 | bare | `V2UYWL3CIAUJPTQHR3L4LLPPEIPLHT47` |
| 44 | `mozart/mz_332_3.mid` | 57 KB | 20140630195508 | bare | `XRJZZ5NRLNTVFCWLDNIIU7BIFVNPXNGV` |
| 45 | `mozart/mz_333_1.mid` | 51 KB | 20140701000254 | bare | `KPMUXOUS6N6IQCDEJMC5NI44ACMMS7ZI` |
| 46 | `mozart/mz_333_2.mid` | 27 KB | 20140630060056 | bare | `FLK4LGLJR5EFR2TUXHZ4IQSETFDDWDN2` |
| 47 | `mozart/mz_333_3.mid` | 34 KB | 20140630105727 | bare | `DU35CL5STND3MKNJZH6OAK2SVXZG2YSS` |
| 48 | `mozart/mz_545_1.mid` | 31 KB | 20131231042921 | www | `SFY2YZU5GGWE4SI5ZK5MFEHUJGKEHFOQ` |
| 49 | `mozart/mz_545_2.mid` | 18 KB | 20140630203248 | bare | `74JLSGEU5GI3WAQ3YRGMJWSVSJB2EKSM` |
| 50 | `mozart/mz_545_3.mid` | 10 KB | 20140630233313 | bare | `3T7GQRR3TAWPDR7UNOOUCIGK2PKPS4UW` |
| 51 | `mozart/mz_570_1.mid` | 44 KB | 20140630144045 | bare | `A74RW346RRDXSKNMSGTWFRCWPWWPE65H` |
| 52 | `mozart/mz_570_2.mid` | 22 KB | 20140630173009 | bare | `KJLF7GCHMPLLGHKSFHCSJZKOGN626Q5P` |
| 53 | `mozart/mz_570_3.mid` | 21 KB | 20140630221010 | bare | `WNVT433DHWMURD3TDY3NVLEDPUGU5N6W` |
| 54 | `haydn/haydn_7_1.mid` | 7 KB | 20130430164054 | bare | `CHDZ2REL5XREI4QIVLUGKEJINQNKUBDE` |
| 55 | `haydn/haydn_7_2.mid` | 10 KB | 20130430221114 | bare | `WMMDRVLUW7KQ6TN4KCQIYQDLKQLBBNZA` |
| 56 | `haydn/haydn_7_3.mid` | 9 KB | 20130430200435 | bare | `Q4SEPQKC5PON2IAD5HMSVSE7VDKMF6WO` |
| 57 | `haydn/haydn_8_1.mid` | 12 KB | 20130430171250 | bare | `Y2OW47P6S63LVDBD3Z6QCPCNX7HOVDEM` |
| 58 | `haydn/haydn_8_2.mid` | 5 KB | 20130430214450 | bare | `3VU7ZXFQHUPBPBNIDIJEYAOSY3QZTWNS` |
| 59 | `haydn/haydn_8_3.mid` | 5 KB | 20120509093917 | bare | `ARIN5NPL6CBRCYTIZFXPZXWWL56TJAH2` |
| 60 | `haydn/haydn_8_4.mid` | 5 KB | 20120509093718 | bare | `3H6OLBDVODAHDLJR6ZUGW2N2IEZZV7X4` |
| 61 | `haydn/haydn_9_1.mid` | 14 KB | 20130430194710 | bare | `3HMTT37E6SQLIS4Z46LEXVMES7FOH5EL` |
| 62 | `haydn/haydn_9_2.mid` | 14 KB | 20130430185934 | bare | `7AJXALQ3QBPPF45LJZGEAYPATN24QB4E` |
| 63 | `haydn/haydn_9_3.mid` | 6 KB | 20130430175352 | bare | `Z54AIIJELXLBWVC5MMF3WSJTVMFRTCKB` |
| 64 | `haydn/haydn_33_1.mid` | 31 KB | 20130430190359 | bare | `BBY2RL7GJPH55UVVSMPCOBQYYS47PAKH` |
| 65 | `haydn/haydn_33_2.mid` | 16 KB | 20130430193214 | bare | `YF3VN3EAKXFYO7AJ34TYLGI5Y3EFUKX3` |
| 66 | `haydn/haydn_33_3.mid` | 19 KB | 20130406002111 | bare | `FZECA2LMOAI62JRFTY7OZ6MBOA6EF2XN` |
| 67 | `haydn/haydn_35_1.mid` | 37 KB | 20130430193621 | bare | `QCGU6EXVO52BFMC6TS356546DKBOWGVS` |
| 68 | `haydn/haydn_35_2.mid` | 20 KB | 20130430190800 | bare | `SXJBR7SK75HPKKSESTTYH6ZY5OIX3JQO` |
| 69 | `haydn/haydn_35_3.mid` | 16 KB | 20130430170500 | bare | `TEIGERTZCWXY3JLOLLRD777V65S55GE7` |
| 70 | `haydn/hay_40_1.mid` | 31 KB | 20130430182101 | bare | `ODAJBSW74V4NQJGARR7PPNRNZS3OBSBP` |
| 71 | `haydn/hay_40_2.mid` | 22 KB | 20130430205652 | bare | `6PQWDGXQW4NG6XT2RBA7OOJ4CUTP2TWV` |
| 72 | `haydn/haydn_43_1.mid` | 31 KB | 20130430222037 | bare | `QTEH6YHV5G2HMZ2K4PLFNBIXTQKRULEJ` |
| 73 | `haydn/haydn_43_2.mid` | 10 KB | 20130430163001 | bare | `X3D47PFD2ZBUNXHXNKGS6Z5V3V2QNVBN` |
| 74 | `haydn/haydn_43_3.mid` | 25 KB | 20121020045544 | www | `GIX37RVO24MUTSIQ2AV4T34CYNI7KOT4` |
| 75 | `clementi/clementi_opus36_1_1.mid` | 12 KB | 20130508152609 | bare | `TRGR4ZHK2ARLFN6CHPAQUV5FJF6ITDWB` |
| 76 | `clementi/clementi_opus36_1_2.mid` | 5 KB | 20130508154142 | bare | `E4TIAMLFW34UWWZEASAC4QVZTOPLCC5H` |
| 77 | `clementi/clementi_opus36_1_3.mid` | 6 KB | 20130508180143 | bare | `DLKNB6JQKNHHZMO3V2DUQQBKHAJFLI2H` |
| 78 | `clementi/clementi_opus36_2_1.mid` | 15 KB | 20130508115336 | bare | `32NW4WFXKHNOEOOOAKKOPOKFBJCJDW7A` |
| 79 | `clementi/clementi_opus36_2_2.mid` | 4 KB | 20130508190728 | bare | `U6AOWGQTIPW5RKVPW6DLHS3EKPVAOCTN` |
| 80 | `clementi/clementi_opus36_2_3.mid` | 10 KB | 20130508164721 | bare | `YZDMHEQ7HUGOSWOZ6UYMLB627ZEAM2EY` |
| 81 | `clementi/clementi_opus36_3_1.mid` | 18 KB | 20130508165724 | bare | `WEOC4DLSI2TM2UYF4NMS7IEA4U6DYEHE` |
| 82 | `clementi/clementi_opus36_3_2.mid` | 4 KB | 20130508135349 | bare | `MWKALLJHTERQYL2TE5JWV6C5IGALFHNG` |
| 83 | `clementi/clementi_opus36_3_3.mid` | 9 KB | 20130508124108 | bare | `AUWDLWTACXQNYMS7DPYTXX4QNCXMPJBS` |
| 84 | `clementi/clementi_opus36_4_1.mid` | 18 KB | 20130508191351 | bare | `QRBKHAY44RUWSWNBFSGTLJVOYRREMRVS` |
| 85 | `clementi/clementi_opus36_4_2.mid` | 6 KB | 20130508120257 | bare | `Z6CI76SVEJDBSVBHGPMO247CKDE7W6ZU` |
| 86 | `clementi/clementi_opus36_4_3.mid` | 6 KB | 20130508140234 | bare | `YWFEU5KBSUC534XXQDPBP5H5DBSKEQIJ` |
| 87 | `clementi/clementi_opus36_5_1.mid` | 26 KB | 20130508135927 | bare | `YKR5I26JDGMIGUUKVUT7OZPIYKL4RKSV` |
| 88 | `clementi/clementi_opus36_5_2.mid` | 8 KB | 20130508170503 | bare | `QRWNGKQOOBGDFLWREZ5ZMFBE4VQHTCNB` |
| 89 | `clementi/clementi_opus36_5_3.mid` | 16 KB | 20130508182629 | bare | `3DJPGVZHAZBISMIC5GOF6HQQIWAQNC4L` |
| 90 | `clementi/clementi_opus36_6_1.mid` | 32 KB | 20110719072835 | www | `3L6CMD66X2ZEAYZLPU3KVMFA7VW7GRRD` |
| 91 | `clementi/clementi_opus36_6_2.mid` | 15 KB | 20110808193302 | bare | `QR4MXGLLYBWV4CMQPSQWZRX2RULX35WR` |

### 6.5 What `kit_build.py` does to every sample

1. **Decode** to float32 stereo at 48 kHz (`ffmpeg -i in -f f32le -ac 2 -ar 48000 -`); resample (swr) only if `ffprobe` shows another rate (the VCSL rate and depth are unverified [R:sampled-instruments §2.1]); a mono source is duplicated to stereo.
2. **Trim the start:** Salamander by its per-file `$OFF` offsets from `Data/vel_NN.txt` (15–36 ms of dead air), VCSL by the first sample above −50 dB re peak within 200 ms; then refine the hammer/pluck onset to the sample (energy envelope first difference) and keep a **96-frame (2 ms) pre-roll**; store the measured `onsetFrame` and `thrFrame` (first frame above −40 dB re the region peak, for T-ALIGN).
3. **Trim the tail** per the trim model (§3.2), with the −75 dBFS early end and a 300 ms raised-cosine fade.
4. **Pitch and inharmonicity:** FFT peaks over 0.3–1.3 s are fitted with `f_n = n·f0·√(1 + B·n²)` (robust least squares over the first 6–24 partials by register), giving an **unbiased f0** (autocorrelation is biased on inharmonic bass notes) and `B` → `pitchCents` (measured sounding pitch re 100·root on A440 ET) and `inharmB` per root, interpolated to all 128 keys. **Tuning shape:** a smooth fit (robust 2nd-order polynomial in key) through the regions' `pitchCents` gives `fit(k)`; `aOffsetCents = fit(69)` (reported with `recordedAHz`; this settles the harpsichord's 415-vs-440 question and changes nothing else) and **`stretchCents[k] = fit(k) − aOffsetCents`** (the shape only). **Railsback check:** the shape must run ≈ −10 to −30 cents at A0, ≈ +15 to +45 at C8 and be non-decreasing above C4 (pianos; the harpsichord only non-decreasing ±3 cents). For Salamander the report also fits `pitchCents + tune_nat` and `pitchCents + tune_ret` (`tune_ret` runs +13 → −38 cents and may flatten or invert the stretch [R:sampled-instruments §1.1]); the raw fit is used unless it fails the check, then `tune_nat`'s; all three are listed for L-3. Any region more than 5 cents off the shape (after `aOffset`) is listed; the rate corrects it anyway, because the KeyMap uses each region's own measured pitch (§3.5).
5. **Loudness and the level curve:** A-weighted RMS over 0–150 ms after the onset; the natural level is kept (`gainDb` restores it after normalisation); isotonic regression per root, 3-point smooth, 3-tap median across roots, corrections clamped ±2.5 dB (§3.5) → the per-stop `levelCurve` (layer-centre dB at velRef). The report lists every correction and each segment's slope for L-3. **Crossfade law:** the correlation of adjacent onset-aligned layers over 0–300 ms per root → `xfadeLaw` per boundary (equal gain above 0.5).
6. **Damper T60** (upright, harpsichord): fitted from each release sample's **early decay only** (the first 10–15 dB after the damper-landing transient; or the level match between the sustain envelope and the start of the release), per root → interpolated per key → `damperT60[128]`; a value outside 0.5–2× the §3.7 formula fails the build and the report names the room-tail knee. **Free T60** per stop from each sustain sample's decay before the trim → `freeT60[stop][128]`, logged against the defaults. **Upright `lastDamper`:** keys whose release samples ring on (release decay T60 > 1 s, the string is undamped) are undamped; `lastDamper` = one below the lowest such key; any disagreement with the SFZ's 10 s releases (which would give 100) is logged; if the evidence is inconclusive, clamp to 86–92. **Harpsichord release timing:** each release's damping knee is reported; it should lie 10–30 ms after the release onset (40–60 ms after the key-up the picture draws), else the release onset offset is adjusted. The grand's arrays come from the §3.7 formulas, written into `map.json` all the same.
7. **Embedded room:** from the release tails and late sustain decay, the kit's direct-to-reverberant ratio and early decay time → `embeddedRoomDb`, `embeddedEdtS` (Salamander close pair: expected ≥ +20 dB; VCSL `_Far` takes: lower).
8. **Seam trims** (harpsichord): the level and brightness difference between the 8′ region of key 84 and the 4′ regions borrowed for 8′ keys 86–89 → `seamGainDb`, `seamLpHz` on those 4′ regions (flagged `borrowable`).
9. **Normalise** each region to **−3 dBFS** peak (so the 16-bit cache keeps full resolution on pp layers, with room for the codec's transient overshoot).
10. **Pack and encode per decode unit:** the unit's regions in id order, each behind 40 ms of silence, their `streamStart` and `frames` recorded; `ffmpeg -f f32le -ar 48000 -ac 2 -i - -c:a libopus -b:a 112k -vbr on -application audio -frame_duration 60 u/<unit>.opus`. Releases and pedals are one stream each.
11. **Verify the round trip:** ffmpeg decode of every stream; each region's length = `frames`, onset at `onsetFrame ± 1`, decoded peak ≤ −0.5 dBFS. A region whose peak overshoots is re-normalised by its measured overshoot + 0.5 dB (its `gainDb` compensates) and its unit re-encoded; a second failure fails the build.
12. **Write** `env.bin` (10 ms RMS over both channels of the normalised region: 1 byte per 10 ms from region frame 0, `round(−dBFS × 2)` clamped 0–255) and `map.json` (§6.6).
13. **After the download** (`export_test_regions.py`): C3, C4 and C5 at v10 and v13 and the una corda C4, decoded and trimmed as above, as 16-bit mono 4 s test resources (≈ 2.7 MB, LFS) for WP3's calibration and WP12's level test.

### 6.6 `map.json` (schema 2) and `env.bin`

The normative field table below is frozen with `contracts-v1` as `docs/contracts/map-json.md`; WP11's Python validator (T11.3) and WP4's `KitMapCodec` (T4.1) are both written from it, and `map_fixture.json` + `env_fixture.bin` (a 3-root, 2-layer toy kit, roots 58/60/62, ET, zero shape) ship with it on day 1.

| Field | Type | Unit / values | Req. | Notes |
|---|---|---|---|---|
| `schema` | int | 2 | yes | |
| `instrument` | string | `grand` · `upright` · `harpsichord` · `stub` | yes | |
| `kit` | string | `grand-hd` · `grand-std` · `upright` · `harpsichord` · `stub` | yes | |
| `version` | string | `YYYY.MM.n` | yes | |
| `sha1` | hex | SHA-1 over every unit stream in unit-id order, then `env.bin` | yes | its first 8 hex digits name the PCM cache file |
| `mode` · `xfadeSteps` · `xfadeLaw` | string · int · string[] | `HARD`/`XFADE` · velocity steps · per boundary `gain`/`power` | yes | `xfadeLaw` empty for HARD |
| `lastDamper` | int | MIDI key | yes | measured (upright), SFZ (grand), 127 (harpsichord) |
| `aOffsetCents` · `recordedAHz` | float · float | cents re A440 · Hz | yes | reported; `recordedAHz` used nowhere else |
| `pedalGainDb` | float | dB | yes | |
| `releaseCarriesTail` | bool | – | yes | upright and harpsichord true |
| `embeddedRoomDb` · `embeddedEdtS` | float · float | dB (direct / reverberant) · s | yes | |
| `layers[]` | object | `{index, velLo, velHi, velRef, unit}` | yes | splits cover 1–127 without gaps |
| `stops[]` | object | `{index, name: main \| 8' \| 4'}` | yes | |
| `levelCurve[stop][]` | object | `{vel, db}` at each layer's velRef | yes | §3.5 |
| `units[]` | object | `{id 0..61 sustain \| 62 releases \| 63 pedals, label, order, file "u/<id>.opus", frames (decoded stream length), sha1}` | yes | `readyMask` bit u = unit id u |
| `regions[].id` · `kind` | int · string | `sustain` · `release` · `pedalDown` · `pedalUp` | yes | ids dense from 0 |
| `regions[].unit` · `streamStart` · `frames` | int | unit id · frame of region frame 0 in the decoded stream · frames | yes | |
| `regions[].stop` · `layer` · `root` · `lo` · `hi` | int | stop index · layer index · MIDI root · key range | sustain: all; release: stop, root, lo, hi (layer −1); pedals: −1 | |
| `regions[].rr` | int | round-robin index | pedals (0..n−1), else 0 | |
| `regions[].onsetFrame` · `thrFrame` | int | source frames from region frame 0 | yes | |
| `regions[].pitchCents` | float | measured sounding pitch re 100·root, A440 ET | sustain, release | the KeyMap's `nativeCents` |
| `regions[].gainDb` | float | dB restoring the natural level | yes | |
| `regions[].envOffset` · `envCount` | int | byte index into `env.bin` · bytes (10 ms each, from region frame 0) | yes | |
| `regions[].borrowable` · `seamGainDb` · `seamLpHz` | bool · float · float | – · dB · Hz | optional (harpsichord 4′) | §3.5 |
| `stretchCents` | float[128] | cents, shape only (0 at key 69) | yes | |
| `inharmB` | float[128] | – | yes | |
| `damperT60` | float[128] | s | yes | formula values for the grand |
| `freeT60` | float[stops][128] | s | yes | |
| `releaseRule` | object | `{relGainDb, velExp, ageTauS, floor, heldDb}` | yes | |
| `credit` · `source` | string | – | yes | |

`KitMapCodec` validates: schema, unique dense ids, every unit file present in assets, `streamStart + frames` within the unit's `frames`, frames > 0, env ranges in bounds, velocity splits covering 1–127 without gaps per stop, array lengths, and the level curve non-decreasing.

### 6.7 MIDI processing, validation and the catalogue

1. `fetch.py --manifest midi` (Krueger SHA-1 checked; any mismatch fails).
2. `midi_extract.py`: list all zips → `build/sankey_listing.txt`; the WP11 agent fills `sankey_map.tsv` (zip, entry, movement id, title) from the listing and the §4.7 table; entries are copied byte-for-byte to `assets/midi/sankey/<zip-stem>/<entry>` and their SHA-1s recorded.
3. `smf_stats.py` over every asset → `build/midi_stats.json` (format, PPQ, tracks, channels, range, notes, CC64/66/67 counts, pedal mode, duration, velocity histogram, folds per instrument) and the golden file for `MidiCorpusTest`.
4. `build_catalog.py` → `assets/catalog.json` with the §4.7 checks (fails on missing assets, missing licence files, folding > 2% for a harpsichord default, SHA mismatch, duration disagreement outside the known exceptions); writes `build/listen.txt` (tier-C files for L-6).

### 6.8 Test and fallback assets (WP11, available on day 1 without any download)

`assets/instruments/stub/` (the stand-in bank, ≈ 0.5 MB, plus the 60 s decode-bench stream), `assets/instruments/probe.opus`, `assets/midi/test/*.mid` (the §4.5 twins plus `format0.mid`/`format1.mid` of the same music, `smpte25.mid`, `running_status.mid`), and in `core/src/test/resources/wp11/`: `map_fixture.json` + `env_fixture.bin` (3 roots 58/60/62 × 2 layers, ET, zero stretch shape, with the §6.6 fields), `catalog_fixture.json` (3 works covering every catalogue field, for WP9's T9.1 before the corpus exists), `midi_facts_golden.json` (grows when the real corpus arrives). After the download: `real/` (the §6.5 step 13 regions). Every other WP keeps its own fixtures in `core/src/test/resources/wp<N>/`.

### 6.9 Licence ledger and obligations

`tools/pipeline/ledger.csv`: `asset_path, bytes, sha1, source_name, source_url, licence_id, licence_url, credit, modified, how_modified, notes`, one row per shipped asset, published as `assets/licenses/SOURCES.csv`. MIDI rows are `modified=no`; sample rows `modified=yes: trimmed, resampled where needed, level-matched, normalised, Opus-encoded`.

| Licence | What the app does |
|---|---|
| CC BY-SA 3.0 DE (Krueger) | ship the original `.mid` bytes and parse at run time (a collection, not an adaptation); licence text in `assets/licenses/`; credit with name, title and source URI on the HUD for 8 s per movement and in Credits; no DRM; **no audio or video export feature** (a recording with animation would be an adaptation) |
| Sankey notice | notice verbatim in `SANKEY.txt`, fetched at download time; files byte-identical; no restriction placed on them; no audio export (his audio clause) |
| CC BY-SA 4.0 (Gouin), CC BY-SA 3.0 (Nieb) | credit + licence URI and text |
| CC-BY 3.0 (Salamander) | credit Alexander Holm (plus Markus Fiedler's retuning tables and kinwie's SFZ data), note the modification; `NOTICE` gives both readings ("CC-BY 3.0; declared public domain by the author, 2022") |
| CC0 / PD (VCSL, VSCO-2 CE, Bednarek, Frantz, Mutopia) | courtesy credit |
| Imports | played only; never uploaded; source `user` |

### 6.10 APK size budget

| Content | HD | Standard |
|---|---|---|
| Grand kit (Opus 112k + map + env) | ≈ 58.2 MB | ≈ 22.3 MB |
| Upright kit | ≈ 12.9 MB | ≈ 12.9 MB |
| Harpsichord kit | ≈ 4.7 MB | ≈ 4.7 MB |
| Stub bank + probe + the 60 s decode-bench stream | ≈ 1.4 MB | ≈ 1.4 MB |
| MIDI (70 works + test files) | ≈ 3.5 MB | ≈ 3.5 MB |
| `catalog.json`, credits, licences, companion page | ≈ 0.4 MB | ≈ 0.4 MB |
| Code: Kotlin, NanoHTTPD, androidx, baseline profile | ≈ 4 MB | ≈ 4 MB |
| Textures | 0 (all procedural at run time) | 0 |
| **Total** | **≈ 85 MB** (cap 100 MB; MathCosmos's 115 MB APK installs on this device) | **≈ 49 MB** (cap 60 MB) |

`size_report.py` enforces the cap for the approved option. The PCM cache (≈ 983 MiB HD, ≈ 516 MiB standard) is created on the glasses, never shipped.

### 6.11 CREDITS text (`assets/credits.txt`, the Credits panel, and `CREDITS.md`)

> **Hammerklavier** — a keyboard recital in the Konzertzimmer of Sanssouci, 1747.
>
> **Instruments**
> Grand piano: *Salamander Grand Piano V3* by Alexander Holm (Yamaha C5), licensed CC-BY 3.0 and declared public domain by the author in 2022. Retuning tables by Markus Fiedler; SFZ mapping data by kinwie (sfzinstruments). The samples were trimmed, level-matched, normalised and encoded to Opus for this app.
> Upright piano and harpsichord: *Versilian Community Sample Library (VCSL)* and *VS Chamber Orchestra 2: Community Edition*, Versilian Studios LLC / Sam Gossner; upright sampled by Simon Dalzell (Ivy Audio). CC0 1.0.
>
> **Music**
> Piano performances of Bach, Haydn, Mozart, Clementi and Beethoven by **Bernd Krueger**, source http://www.piano-midi.de, licensed under Creative Commons Attribution-ShareAlike 3.0 Germany (https://creativecommons.org/licenses/by-sa/3.0/de/deed.en). The files are included unmodified. Recordings or videos made from them are adaptations and must be shared under the same licence with this credit.
> Harpsichord performances of J.S. Bach and Domenico Scarlatti by **John Sankey**, © John Sankey, released for anyone to copy and play freely under his notice at https://www.johnsankey.ca/harpsichord.html (full text in licenses/SANKEY.txt). The files are included unmodified.
> Handel, *Suite in E major HWV 430*, and Rameau, *La Poule*: MIDI by **Pierre Gouin**, Les Éditions Outremontaises, Montréal, via IMSLP, CC BY-SA 4.0 (https://creativecommons.org/licenses/by-sa/4.0/). *(Shown only if installed.)*
> C.P.E. Bach, *Solfeggietto H. 220*: sequenced by **Shane Nieb**, via Wikimedia Commons, CC BY-SA 3.0 (https://creativecommons.org/licenses/by-sa/3.0/).
> Beethoven, *Bagatelles op. 126 and op. 33*; Mozart, *Sonata K. 281* and *Variations K. 265*: by **Michael Bednarek**, via Wikimedia Commons (CC0 / public domain).
> Rameau, *Tambourin*: **Ricardo André Frantz**, after the Mutopia Project, via Wikimedia Commons (public domain).
> Further pieces are engraved by volunteers of **The Mutopia Project** (https://www.mutopiaproject.org) and are in the public domain.
> *(Only if permitted:)* Couperin, *Les Barricades mystérieuses*: sequenced by **David Madore**.
>
> **The room** is a procedural evocation of the Konzertzimmer at Sanssouci (Knobelsdorff, Nahl, Hoppenhaupt, 1746–47) by candlelight; its dimensions are a design, not a survey. No photographs or trademarks are used.
>
> Code © tropicalstream. Licence texts: assets/licenses/.

---

## 7. Work packages, integration order and milestones

### 7.1 Rules of engagement

1. **File ownership never overlaps** (§2.2). An agent edits only its WP's files and tests. Anything it needs from another WP goes through the §2.3 contracts; a missing capability is a request to the owner (or to WP0 for a contract change), never an edit.
2. **Contracts first, in hours.** WP0 tags **`contracts-v1`** as soon as every §2.3 signature and a trivial body for every stub compile, and creates the worktrees; the working primitives, stubs and M0 gate follow as **`contracts-v1.1`** (bodies only). After that, contract changes need a dated entry in `docs/contracts-changelog.md` approved by WP0 and obey the **growth rules**: new interface members get default bodies; new constructor parameters are appended last with defaults; contract classes are always constructed with **named arguments**; nothing is removed or reordered. `tools/ci.sh --contracts` (run by WP0 after every changelog entry) compiles every open WP branch with main's `contract/**` checked over it and reports any branch that breaks.
3. **Worktrees and branches.** Each WP works in its own worktree created by **`tools/wt.sh <N> <slug>`**: `git worktree add ../hk-wp<N> -b wp<N>-<slug> contracts-v1`, then it writes `local.properties` (`sdk.dir=/opt/homebrew/share/android-commandlinetools`), because `local.properties` is git-ignored and `ANDROID_HOME`/`ANDROID_SDK_ROOT` are not set in this Mac's shell (a fresh worktree otherwise fails with "SDK location not found"). `tools/env.sh` exports `ANDROID_HOME` and is sourced by every script. **Every `tools/*` script finds its root with `git rev-parse --show-toplevel` and never uses an absolute project path**, so `run.sh` in `../hk-wp4` builds and installs `../hk-wp4`. The APK's `BuildConfig` carries the branch and commit hash; `HKSelfTest` prints them first, so every device result says which code it measured. The integrator merges in milestone order.
4. **Shared resources are serialised.** One pair of glasses and one 16 GB, 8-core Mac [M] serve 13 WPs. `tools/device/lock.sh` (a `python3` `fcntl.flock` wrapper; the Mac has no `flock(1)` [M]) wraps `run.sh`, `smoke.sh`, `soak.sh`, `cpu.sh`, `apl.sh` and `push_scores.sh`, so no two agents install, compile, broadcast or measure at once; it restores `device_wearing 0` on exit (`trap … EXIT`). **Only the integrator runs T-THERM, T-UND-long and T-CPU**, in scheduled device slots. `tools/ci.sh` takes one of **3 build slots** (the same lock with three slot files), so at most three Gradle builds run at once, each at `-Xmx1536m` with a 1 GiB Kotlin daemon and 2 workers.
5. **Only WP0 edits `MainActivity.kt`, `AppController.kt`, `HammerklavierApp.kt` and `Wiring.kt`.** Each WP ships `docs/wiring/WP<N>.md` listing exactly what WP0 must change to swap its stub for the real class (constructor call, listener hookups, settings), and the smoke steps that prove it.
6. **The CI gate** (`tools/ci.sh`) must pass before any merge: `tools/check_purity.sh` → `./gradlew :core:test :app:testDebugUnitTest :app:assembleRelease` → `python3 -m unittest discover tools/pipeline/tests` → `tools/pipeline/check_ledger.py` → `tools/pipeline/size_report.py`. A pure WP iterates with `./gradlew :core:test` alone. JVM tests run with `-XX:-DoEscapeAnalysis -XX:-EliminateAllocations`, so HotSpot's scalar replacement cannot hide allocations that ART would make.
7. **Commits** are authored as `tropicalstream <tropicalstream@users.noreply.github.com>` (`git config user.name tropicalstream && git config user.email tropicalstream@users.noreply.github.com` in the repository and in every worktree, which `wt.sh` sets). The user's personal address is never written into any file, commit or page.
8. **Done** means: the WP's listed tests pass, its budget lines in §0/§8 are met or measured on the glasses, its wiring note is written, and its milestone smoke passes.
9. **Kotlin traps:** block comments nest (never write `x/*` in a KDoc); after copying any tree run `rm -rf .gradle app/build core/build`; no `$` or backticks inside Kotlin raw strings holding HTML (the page lives in `assets/`).

### 7.2 The work packages

**WP0 · Shell, contracts, integration and device tooling (the integrator)**
- **Owns:** build files (`settings.gradle.kts`, `build.gradle.kts`, `core/build.gradle.kts`, `app/build.gradle.kts`, `gradle.properties`, wrapper), `AndroidManifest.xml`, `res/**`, `proguard-rules.pro`, `baseline-prof.txt`, `.gitignore`, `.gitattributes`; `HammerklavierApp.kt`, `MainActivity.kt`, `AppController.kt`, `Wiring.kt`; `contract/**` including `contract/stub/**` and `contract/android/**`; the `testutil` fixtures `AwtPainter` and `AllocProbe`; `system/*`; `platform/*`; `tools/{env.sh, wt.sh, ci.sh, check_purity.sh}`, `tools/device/*`; `docs/contracts-changelog.md`, `docs/plan-changelog.md`, `docs/contracts/map-json.md`, `docs/wiring/README.md`.
- **Build:** `app/build.gradle.kts` in the MathCosmos shape [R:engine_reuse §6.2] plus `androidResources { noCompress += listOf("opus","bin","json","mid","midi","kar") }`, `testOptions { unitTests.isReturnDefaultValues = true }`, the dependencies of §2.2, `base.archivesName = "Hammerklavier"`, `buildConfigField`s for branch and commit, and a **release build type signed with the debug keystore, `isDebuggable = false`, `isMinifyEnabled = false`** (so the DSP is measured as shipped). Manifest: `com.rayneo.mercury.app` meta-data, **no `ar_mode`**, two intent filters (MAIN+LAUNCHER and MAIN+`com.rayneo.intent.category.AR_APP`), `resizeableActivity="false"`, `singleTask`, landscape, `adjustNothing`, `hardwareAccelerated`, **the full `configChanges` list of §1.10**, **`allowBackup="false"`** with `fullBackupContent`/`dataExtractionRules` from MathCosmos; permissions INTERNET, ACCESS_WIFI_STATE, ACCESS_NETWORK_STATE, MODIFY_AUDIO_SETTINGS (and FOREGROUND_SERVICE + `foregroundServiceType="mediaPlayback"` on the disabled `PlaybackService`); no runtime prompts. Theme: black window background, fullscreen, `forceDarkAllowed=false`. `FLAG_KEEP_SCREEN_ON`, immersive flags in `onCreate` and `onResume`. `setVolumeControlStream(STREAM_MUSIC)`. Copy `TrackpadGestureEngine` (+ `cyttsp6` key filter + firm-click dedup), `BinocularSbsLayout`, the thermal governor logic (+ Q3, engine lifetime), the CONTROL receiver pattern (DUMP permission). `MediaButtons`, `SoakRecorder`, `KEYCODE_BACK`, the §1.10 lifecycle.
- **Day 0 (hours) → `contracts-v1`:** every §2.3 signature and trivial stub bodies; the shell skeleton; `tools/env.sh`, `wt.sh`, `ci.sh`, `check_purity.sh`, `device/lock.sh`; the 12 worktrees.
- **Days 1–2 → `contracts-v1.1`:** every primitive implemented and tested (`AudioClock`, `VisualClock`, `HeadPose`, `EnergyRing`, `CommandRing`, `VoiceCursorBoard`, `PedalCurve` with rebindable cursors, `PedalMotion`, `KeyReturn`, `HarpsiTiming`, `Temperament`/`TuningSpec`, `InstrumentProfile` values, `QualityLadder`, `Placement.toRoom`, `Conventions`, `KonzertzimmerAcoustics`, `MaterialTable`, `Playlist`, `Pal`, `SyntheticSpecs`); every stub working as specified in §2.3; the day-0 part of `mesh/MeshBuilder` (box, quad, vertex, tri) handed to WP7; the device scripts (§8); the M0 gate.
- **JVM tests:** `AudioClockTest` (10⁶ reads against a writer thread never see a torn record or a torn anchor, checksum field per record; `S(n)` exact for synthetic block records across pause, seek and a rate change; the estimate before the first timestamp; **a stop/start cycle with `reset()`, a short write, a negative write, a stale timestamp (older nanos), a rejected timestamp (rate off by 1%), H clamped at newestF + BLOCK, the oldest-record fallback counting `clockMiss`**), `VisualClockTest` (2 ms timestamp jitter, a 200 ms underrun, a +150 ms latency step, a −5 ms lead change, a first-timestamp correction of 30 ms: `tUs` monotone within a session/epoch/generation, error < 2 ms after 0.5 s except where a reseed is specified; exposure windows tile time exactly at 16/33/50 ms irregular spacing and are empty while paused), `EnergyRingTest`, `CommandRingTest` (10⁷ items, no loss or reordering, `dropped` counts), `VoiceCursorBoardTest`, `HeadPoseTest`, `PedalCurveTest` (cursor = binary search at 10⁵ random times; `nextCrossing`; `bind` allocates nothing), `PhysicalCurvesTest` (`pedalDamping(0.33) = 1`, `(0.55) = 0`, monotone; `damperLiftByPedal(0.33) = 0`; `staggerMs(127) = 3.51 ± 0.05`, `staggerMs(0) = 9.36 ± 0.05`; `KeyReturn.damperLandMs(35) = 18.4 ± 0.1`; HarpsiTiming quill 30.1 / 41.4 and damper 47.6 ms ± 0.1), `TuningTest` (Werckmeister III table; `keyCents` at A415 = −101.27 ± 0.01), `ConventionsTest` (grand Player: source azimuth ≈ 0, treble to the right; yaw sign matches GazeCamera), `PlaylistTest`, `QualityLadderTest`, `ThermalPolicyTest` (37 → 39.1 → 42.2 → 44.1 → 43.0 → 42.4 → 41.0 → 40.4 °C gives Q 0, 1, 2, 3, 3, 2, 2, 1), `StubContractTest` (every `PerfFixtures` Performance passes `PerformanceValidator`: sorted, CSR consistent, events sorted by (evUs, type), pre-roll; `SineCore` onset exact).
- **Device (M0 gate, `contracts-v1.1`):** installs and launches (release build, `run.sh` verifies the installed APK's md5 against the local one); the title text shows in both eyes (the screencap halves match); `adb shell input tap 320 240` logs `HKInput tap`; `input keyevent KEYCODE_DPAD_CENTER` logs one `tap`; `--es gesture double` and `--es gesture triple` log `double`/`triple`; `input swipe 200 240 500 240 120` logs `FORWARD`; **physical checks by the integrator:** one firm click on the right pad logs exactly one tap (not touch + key), a firm click on the left arm logs nothing, a physical double-tap logs `double`; `KEYCODE_BACK` at the root leaves the app; the CONTROL receiver echoes its extras from adb and ignores a broadcast from another app; `HKThermal` lines appear, also with the display asleep; `--ez selftest true` (via `am start -S` or a broadcast) prints the branch, commit, GL info and the torn-read result; `--ei faketemp 405` logs Q1.

**WP1 · MIDI and performance (pure)**
- **Owns:** `midi/*` and its tests (including a test-only `SmfWriter`).
- **Build:** §4.1–§4.5; implements `ScoreCompiler`; `VoiceDemand`.
- **Tests (JVM):**
  - **T1.1 parser** (hand-built byte arrays): format 0 with running status across notes; running status cancelled by meta and by sysex, and tolerant reuse with a warning; format 1 with tempo on track 2; SMPTE −25/40 = 1,000 µs per tick; sysex skip; unknown chunk skipped; truncated chunk clamped; VLQ `00`, `7F`, `81 00`, `FF FF FF 7F`, a 5-byte VLQ ends the track; vel-0 note-on = off; RMID unwrapped; channel-10 notes dropped; CC120 closes notes; limits exceeded → `Failed(TOO_LARGE | TOO_MANY_EVENTS)`; drums-only → `Failed(NO_KEYBOARD_NOTES)`; format 0 ≡ format 1 of the same music (identical Performance arrays).
  - **T1.2 tempo:** 480 PPQ at 120 bpm puts tick 960 at 1,000,000 µs (+ 400 ms pre-roll in the Performance); a tempo change at tick 480; bar starts in 3/4.
  - **T1.3 builder:** two channels overlapping on one key are serialised (gap ≥ 20 ms, `F_RESTRIKE`); zero-length → 30 ms; hanging notes end at track end or last event + 1 s with a warning; harpsichord key 96 folds to 84 and a colliding duplicate merges; sostenuto latch = held keys, all keys only when sustain ≥ 0.55, and a press at sustain 0.4 latches only the held keys; `EV_KEY_UP` at `offUs` exactly (no lag in the score); at equal times KEY_UP precedes NOTE_ON; no `EV_PLUCK4` emitted; `flatVelocity` → 72 on pianos only.
  - **T1.4 pedal shaper:** a switch down edge crosses 0.33 at the event time ± 0.1 ms and starts 23 ± 0.5 ms early; up/down 30 ms apart dip below 0.33 but stay above 0; continuous detection at ≥ 8 distinct values; pedal-noise events ≥ 150 ms apart.
  - **T1.5 adapter:** legato hold delays note-offs to the pedal-up, capped at 1.5 s and at the next same-key onset; pedals removed; upright drops CC66.
  - **T1.6 fuzz and speed:** 10,000 seeded mutations (bit flips, truncations, insertions) of every test file never throw and each parses in < 50 ms; op. 106 iv builds in ≤ 60 ms on the JVM.
  - **T1.7 `MidiCorpusTest`:** every bundled asset parses Ok and matches `midi_facts_golden.json` (duration ±1 ms, range and counts exact).
  - **T1.8 synthetic parity:** `SyntheticScores` equals `PerfFixtures` for every kind (notes, CSR, events; pedal curves within 1 ms and 0.02); every `.mid` twin compiles to the same arrays.
  - **T1.9 voice demand:** `VoiceDemand` on hand-computed cases (a held pedalled chord, a repeated key with 3-voice limit) is exact; a report test writes `build/voice_demand.txt` (p99/max per catalogue movement × default instrument).
- **Device:** none of its own (runs inside M1+).

**WP2 · Audio engine core (pure)**
- **Owns:** `engine/*` and tests, including `OfflineRender` (writes `core/build/renders/*.wav`) and `EngineBench`.
- **Build:** §2.5 (engine side), §3.5 (use of the KeyMap), §3.6–§3.10, §3.14–§3.16. Constructs and drives the `DspSet` through its interfaces (`PassThroughDsp` until WP3 merges). Uses `SineBank` + `KeyMapFixtures` and `StubScoreCompiler` (never `map_fixture`, never WP1 code).
- **Tests (JVM):**
  - **T2.1 timing:** a note with `onUs` = 1,000,000 µs, rate 1, `startUs` 0, puts its sampled onset exactly at output frame 48,000 (at several offsets within a block); at rate 0.5 at 96,000; an onset 10 minutes in, at r = 1, 0.95 and 1.37, lands exactly (drift-free song position); at playback rates 2^(±2/12) and 2^(5/12) the onset lands within ±1 frame (`onsetOut`); across a pause and a seek.
  - **T2.2 pool:** the cap is never exceeded; stealing order FADING → damping → pedal-held → key-down; a < 50 ms voice is stolen only when nothing else exists; ≤ 3 voices per key; steal-ahead: over the whole `CHORD_STORM_64` render at caps 64 and 128 the maximum sample-to-sample step of the output (after removing the notes' own attacks) is ≤ 0.02 FS and `dropped` = 0; noises never steal music voices.
  - **T2.3 dampers:** half pedal (p = 0.44) gives an effective T60 = T60d/D within 2%; keys > lastDamper never damp; latched keys D = 0; re-pedalling freezes the level; the spectral low-pass never re-opens; the damping onset falls `round(damperLagMs · 48)` frames after the key-up at r = 0.5, 1 and 1.5 (±1 frame); a tail-carrying kit hands off level-matched (no step > 1 dB in the summed envelope) at full damping only.
  - **T2.4 voice:** rate 1 output bit-identical to the source; rate 2^(1/12) FFT peak within 0.1 cent; window refills leave no discontinuity (max step ≤ source max step × 1.05); `levelDb` of a SineBank note tracks the RMS of its rendered output within 1 dB.
  - **T2.5 releases and noises:** a key-up with the pedal up plays the release at the rule gain; under the pedal at −9 dB (grand) or none (tail-carrying kits); a pedal lift plays only the pedal-up noise; pedal noise exactly once per 0.33 crossing.
  - **T2.6 harpsichord:** the 4′ onset precedes the 8′ by `round(staggerMs(v) · 48)` ± 1 frame at r = 0.5, 1 and 1.5 and at playback rates 2^(±2/12); a registration change (via `Cmd.REGISTRATION`) affects only new notes and appears in `CoreClockState`; RMS within 1 dB across v20–127.
  - **T2.7 transport:** pause fades voices and the room input over 60 ms and freezes positions; resume continues; seek does not retrigger held notes; instrument swap fades 30 ms; **a pause, seek, new performance or bank change issued 1–255 frames before an onset: that onset is never heard inside the fade; after a pause it is heard only after resume, exactly when the resumed clock reaches `onUs`; state events (key-up, latch) are applied in the block containing their frame, never earlier.**
  - **T2.8 comb gates:** `gate` = 1 − D, 0 when damped; `softFeed` only on keys with ≥ 2 strings under una corda; self rows are written exactly for gated keys with voices.
  - **T2.9 relative levels:** piano RMS monotone in velocity; the level curve continuous across every split (≤ 0.5 dB step) with `PassThroughDsp`.
  - **T2.10 allocation:** `AllocProbe` (ThreadMXBean, escape analysis off) shows 0 bytes on the render thread during 10 s of `CHORD_STORM_64` after warm-up, including command draining (the handler is `EngineCoreApi` itself) and listener posts.
  - **T2.11 energy:** a −12 dBFS-RMS SineBank C4 gives lane 39 = 0.25 ± 10% (StringVisual amp ≈ 0.8); a damped note's lane falls at T60d; a comb lane appears for a sympathetic string; a v80 note's lane is within 1 dB of its output RMS.
  - **T2.12 bench:** `EngineBench` reports ns per voice-frame, comb-frame and stage and derives the cap formula of §3.14.
- **Device:** M1 (with WP4), `--ez bench true` numbers, the on-device allocation count on HKAudio around 10 s of `storm64` (debug build), then T-CPU, T-UND, T-GC.

**WP3 · DSP (pure)**
- **Owns:** `dsp/*` and tests.
- **Build:** §3.11–§3.13; implements `ResonanceProcessor`, `RoomProcessor`, `SoftBusProcessor`, `MasterProcessor`, `RoomDesigner`; owns `SEND` and `UNA_CORDA_SEND`.
- **Tests (JVM):**
  - **T3.1 combs:** every feedback gain < 1 (a 10⁶-sample impulse decays); a comb tuned to C3 peaks within ±2 cents of its f0 and, with dispersion, within ±3 cents of the fitted partial series `n·f0·√(1+Bn²)` for n = 2–10 (±5 cents for n ≤ 6 on keys ≥ 60 without dispersion); retuning moves the peaks with a 200 ms glide; send 0 → exactly 0; a key's own self row contributes nothing to its comb (only una corda feeds it); the four calibration targets of §3.11, first with SineBank voices and then with the `wp11/real` regions; inactive combs cost nothing (bench); the 4-way kernel equals the scalar reference within 1e-6.
  - **T3.2 room design:** on `KonzertzimmerAcoustics.GEOMETRY`, Sabine T60 per band equals the formula within 1% and lies in 1.5–1.9 s at 500 Hz; image-source delays for a listener at the room centre equal the analytic values; DRR at 4.93 m = −11.2 ± 0.5 dB; changing a material changes T60; an `embeddedRoomDb` of +3 dB lowers `reverbGain` per §3.12.
  - **T3.3 FDN and ER:** Schroeder T60 within ±10% of `t60Mid` (500 Hz–2 kHz band) and ±15% of `t60High` (8 kHz); stable for 60 s of full-scale noise (no NaN, bounded); no 1 s FFT bin > 6 dB above its ⅓-octave neighbourhood; energy normalisation ±1 dB for T60 0.8–2.5 s; **a listener switch (bench → case → row 3) under steady noise produces no step above −60 dBFS** (two tap sets crossfaded, pre-delay crossfaded).
  - **T3.4 master:** a +6 dBFS sine → peak ≤ −1 dBFS; a +12 dBFS one-sample impulse caught by the look-ahead with no overshoot; biquads within 0.1 dB of the RBJ reference at 3 frequencies; speaker high-pass −3 dB at 110 Hz ± 10%; virtual bass Off = bit-exact bypass of its path; the yaw pan uses the table and a 20 ms smoother.
  - **T3.5:** zero allocation in every `process`; ns per frame per processor (feeds §3.16).

**WP4 · Audio I/O, clock publishing, kits and storage**
- **Owns:** `kit/*` (pure) and `audio/*` (Android) and tests.
- **Build:** §3.1–§3.5, §3.19; implements `AudioControl` and `KitService`; `SynthBank`; `TrackSupervisor`, `RouteMonitor`, `PlaybackService` (disabled).
- **Tests (JVM):** **T4.1** `map_fixture.json` parses and bad fixtures are rejected with reasons (every §6.6 rule); **T4.2** `KeyMapBuilder`, as properties: every (key, velocity) maps to a ready covering region; the output pitch (native × rate) is within 0.5 cent of `targetCents` for every key, at A440 and A415, for fixtures recorded at A440 and at A415; |shift| ≤ 1 semitone for every 8′ key 29–89 of the harpsichord fixture (borrowing included) and ≤ 1.5 on the grand; HARD layers match Salamander's splits for all 127 velocities; the summed crossfade level stays within ±0.5 dB of the level curve; with only v10 ready every velocity maps to v10 on the level curve; a 4′ unit not ready → no 4′ region; `f0Hz` and `onsetOut` equal the formulas; worked example (ET, zero shape, roots 58/60/62, pitchCents 0): at A415 key 61 uses root 60 with −1.27 ± 0.01 cents; **T4.3** `SampleStore` on a temp file: reads across a region end zero-fill; prefetch uses positional reads (no mapping access); a header SHA mismatch is rejected; readers on two threads do not interfere; **T4.4** `PcmCacheFormat` round trip including the CRC table and `BOOT_COUNT`; a flipped byte in a ready unit is detected on the verification path; **T4.5** `SynthBank` deterministic; **T4.6** `DecodePlan` resumes at the first missing unit, schedules CRC checks after a boot-count change, and computes the storage check.
- **Device:** M1 (`AudioOutput` + `SineCore`/`SynthBank`, then EngineCore); the decode bench; **T-DEC**, **T-ALIGN**, **T-RESUME** (force-stop and `adb reboot` variants), **T-PF** (display on and asleep), **T-CLOCK** (§8.4), the route switch (a headset plug and a Bluetooth connect switch voicing and lead), focus duck to 0.3, the LOW_LATENCY path forced with `--ez lowlatency true`, the DecoderProbe offset logged, T-UND on a Bluetooth headset (5 min) at M3.

**WP5 · Mechanism animation (pure)**
- **Owns:** `mech/*` and tests.
- **Build:** §5.7; implements `MechanicsEvaluator`.
- **Tests (JVM):** **T5.1** `Touch`: travel 230 ms at v20, 86 ± 1 at v64, 20 at v110; key bottom +5.4 ± 0.3 ms at v64; **T5.2** grand: `keyDip` exactly 0 one ms before `tStart` and positive just after; hammer = 1.0 at `on` ± 0.5 ms; checked at 0.68 while held; key returns in 35 ms real time (smoothstep); the drawn damper touches the string within 1 ms of `off + damperLagMs·r`; damper lifts from sustain > 0.33 and from the key; **at rate 0.5 real-time durations are unchanged (song-time durations halve)**; **T5.3** a re-strike from part-way up is continuous (no step > 0.05 dip per ms); **T5.4** harpsichord: dip 0.70 at `on`, 0.433 at `on − staggerMs(v)·r` ± 0.2 ms; the tongue flicks at `off + 30.1 ms·r` (the frame at which the release sound starts); the cloth touches at `off + 47.6 ms·r`; the return is quadratic (starts at rest speed); a disengaged register (from `VisTime.registration`) shows no pluck; **T5.5** upright: soft pedal moves the hammer rest to 22/47; repetition only after 80% return (the fast-repetition approximation below 143 ms); dampers under the strike line; **T5.6** strings: the energy mapping; the analytic fallback; reset on reseed; **T5.7** `ExposureSampler`: every note of `REPEAT_15` gets exactly one contact frame at steady 30 and 20 fps and at irregular 16/33/50 ms spacing; a pause 10 ms after a contact → no forced hammer on any later frame; after a reseed nothing is forced; **T5.8** seek invariance: sequential evaluation to t equals a fresh evaluation at t over 1,000 random t, and a sequence with backward steps of 5–60 ms (held by VisualClock, but the cursor must also step back) equals fresh evaluation; **T5.9** `evaluate` allocates nothing and takes ≤ 0.3 ms for 88 keys on the JVM (informational); **T5.10** repeated notes: `REPEAT_15` at v20, v64 and v110 on all three instruments with the ExposureSampler disabled: the analytic hammer (jack) is 1.0 within ±0.5 ms of every `on_j`, and `keyDip` never begins its next descent before `off_j`; **T5.11** pre-roll: at r = 1.5 a v20 first note on the upright shows `keyDip` = 0 at song time 0.

**WP6 · Render core**
- **Owns:** `geom/{StereoRig, CameraDirector, Springs, Mat4}.kt`, `render/**` (including the `GazeCamera` and `GlyphBoard` copies) and tests.
- **Build:** §5.1–§5.3, §5.6 (director, dip), §5.8, §5.10, the renderer side of §5.9 and §5.11; implements `GlHost`; the desired-state reconciliation, two Performance slots, GL generation, `VisualClock` use.
- **Tests:** JVM: **T6.1** a point at the zero-parallax distance projects to the same NDC x in both eyes and a nearer point has crossed disparity; `right = forward × up` for every framing; **T6.2** dip timing 250/250 ms with the cut at the midpoint, queue depth 1; follow spring capped at 0.6 m/s; the `x_cut` dead band; no dip after a display rest; **T6.3** `SceneAssembler` merges by the §2.3 key and builds every framing's draw list from `StubScenes` within 28 draws per eye and in `drawSlot` order; **T6.4** `UniformPacker` maps key k to lane `k − lowKey`; the ACTION_SET decode agrees with WP7's pack test; **T6.5** `GazeCamera` world-locked mode: no re-centre drift over 5 min of synthetic input, clamps ±60°/+45°. Device: **M-GL** (`StubScenes`' skinned keyboard in both eyes from `StubMechanics`), **T-FPS** (30 / 20 / 10 at dividers 2 / 3 / 6; no `FRAME HITCH` in 5 min; late > 8 ms counted), **T-GLRESET** (`--ez glreset true`: the scene returns within 1 s, no GL errors), the EGL config with `EGL_SAMPLES` and `GL_MAX_VERTEX_UNIFORM_VECTORS` logged, `--ez mono true` works, draws ≤ 28 per eye logged, zero GL-thread allocation after warm-up (`Debug.getThreadAllocCount` in a debug build), display rest pauses GL, **T-Q3SWITCH** (`--ei quality 3`, switch instrument: the sound continues within 2 s and the new instrument appears on the first frame after the rest).

**WP7 · Instrument models and the mesh builder (pure geometry + texture recipes)**
- **Owns:** `mesh/MeshBuilder.kt`, `testutil/MeshRaster.kt`, `instrument/**` and tests.
- **Build:** first merge (day 2): the complete `MeshBuilder` (§2.3 conventions) and `MeshRaster`. Then §5.4 (instruments), §5.6 tables (`Anchors`), the §5.9 colour tokens applied as vertex colours, `packActionSet`. Delivers keyboard + grand case + pedals (M3), grand action, hammers, dampers, strings (M4), upright and harpsichord (M7).
- **Tests (JVM):** **T7.0** `MeshBuilderTest`: valid one-hot lanes, unit normals, **winding agrees with normals (CCW outward) for every primitive**, the UV rules, index bounds with indices above 32,767 read as unsigned, split above 65,535 vertices, ribbon encoding; **T7.1** triangles: grand ≤ 26k, upright ≤ 22k, harpsichord ≤ 20k; **T7.2** `keyX` monotone with a 13.71 mm (piano) / 13.25 mm (harpsichord) pitch; 52 + 36 keys on the pianos, 36 + 25 on the harpsichord; sharps follow the cut-out rule; **T7.3** every skinned vertex has slot < 34 and a one-hot lane; **T7.4** `anchors.camera()` and `listener()` return the §5.6 tables exactly, with `roomFrame`, `clipX` and `lidLift` set; **T7.5** no NaNs; **T7.6** grand: 228 strings (8 / 40 / 180 by `stringsPerKey`), `lastDamper − 20` dampers (68 for 88), 88 hammers, case bounds 2.00 × 1.49 m; upright 1.31 m tall with the damper line 50 mm below the strike line; harpsichord 122 jacks and 122 strings, **case width ≥ keyboard width + 2 × (cheek + side)** (0.93 ≥ 0.818 + 0.104); **T7.7** `packActionSet` allocates nothing and is deterministic, and its layout matches §5.8 (paired with T6.4); **T7.8** every mesh's `program` matches `MaterialTable` (or SKINNED/STRING), and the distinct merge keys per framing stay within the §5.3 draw counts. Device: a mono screencap of every instrument × framing (18 shots) into `docs/shots/`; T-APL on them.

**WP8 · Venue (pure geometry + texture recipes)**
- **Owns:** `venue/**` and tests.
- **Build:** §5.5, the venue side of §5.9, `Konzertzimmer` (returns `KonzertzimmerAcoustics.GEOMETRY` for acoustics, the §5.6 placements and the fixture footprints), `FlameFieldImpl` (flicker, CPU mirror culling, floor reflections, crystals), `LightBake`, `ProbeBake`, `Atlas` recipes. Uses `MeshBuilder` from WP7's day-2 merge (the day-0 subset before that).
- **Tests (JVM):** **T8.1** the drawn areas and volume agree with the shared constant (floor 84.0, walls 170.2 ± 0.5 split per plane as §3.12, V = 469.7 ± 1); **T8.2** mirror culling (in front → visible; behind the wall plane → none; outside the reflected cone → none); **T8.3** flicker within ±4% globally and ±8% per sprite; **T8.4** venue triangles ≤ 18k; **T8.5** 50 ± 2 flames; **T8.6** probe 128 × 64 with bounded energy; **T8.7** `FlameField.update` allocates nothing; **T8.8** merge keys per framing within the §5.3 counts; **T8.9** every case corner of every placement ≥ 0.05 m inside all four walls and clear of every fixture footprint. Device: T-APL (Hall ≤ 12%, Stage ≤ 9%); the Hall screencap reviewed against the build sheet; look-around in the Hall.

**WP9 · Library, import and companion**
- **Owns:** `library/**`, `companion/**`, `assets/companion.html` and tests.
- **Build:** §1.5–§1.7, §4.6, §4.8; implements `LibraryService`, `CompanionServer`. At M2: `CatalogCodec` and a bundled-only `LibraryServiceImpl` that also resolves `asset:`, `synth:` and `test:` ids.
- **Tests (JVM):** **T9.1** `catalog_fixture.json` parses (every field; shelves; every movement asset exists in the fixture list), and later the full `catalog.json` (70 works); **T9.2** `ImportStore`: display-name sanitising (`../`, Unicode kept, 200-char names), non-MIDI rejected and recorded by (path, size, mtime) without touching the source, SHA-1 duplicates, zip limits (201 entries, > 20 MiB, traversal), subfolder → one work in filename order, byte-identical copies into the app store, `index.json` rebuilt after deletion, an unreadable source → `PERMISSION_DENIED`, **an upload during a rescan leaves a consistent `index.json`**; **T9.3** the default-instrument rule; **T9.4** `CompanionServer` on 127.0.0.1: `GET /` contains no token; every `/api/*` without the token → 403, with it → 200; ten bad tokens → 429; `/api/library` is UTF-8 JSON ("Für Elise", "Händel" survive); a raw upload named `x-hk-name: H%C3%A4ndel%20Suite.mid` is saved under "Händel Suite" with identical bytes; `content-length` 5 MiB → 413 before any body is read; unknown path → 404; a handler exception → JSON 500. Device: upload from a phone on the same Wi-Fi (token typed once); `curl` upload from the Mac (smoke M6); `push_scores.sh` + rescan shows the file under Imported; `no Wi-Fi` shown with Wi-Fi off and the URL appearing when Wi-Fi returns; `/api/state` answers in < 50 ms while playing with 0 underruns.

**WP10 · UI and overlays**
- **Owns:** `ui/**` and tests.
- **Build:** §1.2–§1.5, §1.8, §1.9 (the UI side); implements `UiStateMachine`, `OverlayHost`; owns every user-facing word, including the `StatusCode`/`RejectReason`/`FallbackReason`/`PerfWarning` → text and priority tables.
- **Tests (JVM):** **T10.1** the complete §1.3 table as a parameterised test (context × gesture → actions, including `SYSTEM_BACK`); a double-tap never also yields a tap action; Reverse swipe flips; **T10.2** menus: rows, paging by 7, back, Adjust clamps (tempo 50–150 %, seek within the movement), the instrument sub-list with voicing %; **T10.3** first run: the title waits for `KIT_PLAYABLE`, tap → `Enter`, the hint for the first 3 sessions, `Tap to continue`; **T10.4** HUD strings (times exclude the pre-roll, credit for 8 s, pills, status priority order: lower number wins; every `StatusCode` has a text); **T10.5** cards (swatches 8–68 step 4; A/V lead ±5 ms within 0–400, stored per route key). Device: legibility at 22/18/14 px in both eyes (screencaps, **including the Player follow framing with the pedal inset and the pills at the top right**); HUD invalidations ≤ 1 Hz (logged); **T-5MIN** scripted walkthrough; L-5.

**WP11 · Asset pipeline and data**
- **Owns:** `tools/pipeline/**`, `docs/manifests/*`, `docs/DOWNLOADS.md`, `app/src/main/assets/{instruments/**, midi/**, licenses/**, catalog.json, credits.txt}`, `core/src/test/resources/wp11/**`, `CREDITS.md`, `NOTICE`. Asks WP0 for the `.gitattributes` LFS lines.
- **Build:** §6. First action: the §6.2 approval request with `docs/DOWNLOADS.md`. Day 1 (no download): stub bank (+ the decode-bench stream), probe, test MIDIs, map fixture, catalogue fixture, golden file for the test MIDIs, all tools and their tests. M2: the grand kit and a Start-here-only partial `catalog.json`; later the full corpus, the other kits and the real test regions.
- **Tests (python):** **T11.1** onset within 1 frame, f0 within 1 cent and B within 10% on synthetic inharmonic signals; **T11.2** Opus round trip of a packed unit preserves every region's length and onset and keeps peaks ≤ −0.5 dBFS; **T11.3** the `map.json` validator (hand-written from the §6.6 table) accepts every kit and the fixture and rejects each broken variant; **T11.4** ledger covers every asset, MIDI SHA-1s unchanged; **T11.5** `size_report.py` within the cap; **T11.6** the catalogue's measured fields present and folding ≤ 2% per default instrument; **T11.7** the stub bank is byte-identical across two runs; **T11.8** `smf_stats` agrees with the test MIDIs' known contents; **T11.9** the level curve is continuous and non-decreasing; the stretch shape passes the Railsback check; **T11.10** the stub kit's `damperT60`/`freeT60` match the Kotlin defaults within 1%. Reports: `build/kit-*-report.txt` (onset jitter ≤ 1 frame, tuning residual ≤ 5 cents, level corrections within ±2.5 dB, no clipping, `aOffset`/`recordedAHz`, the three Salamander stretch fits, damper T60 fits with their knees, `lastDamper` evidence, embedded room, seam trims).

**WP12 · Session orchestration (pure)**
- **Owns:** `session/**` and tests (including the integration test `LevelCalibrationTest`).
- **Build:** §2.6 and §4.9: `SessionController` (playback, playlists from the chosen shelf, generation counter, the instrument-switch sequence, per-work instrument memory, resume points, recently played, view → listener → `RoomDesign`, thermal → quality, the sync test, seek-unit conversion, `CompanionCommands`), `StatusBoard` (active status codes and expiry), `FactsAssembler` (`UiFacts`), `ListenerRooms`. `AppController` (WP0) stays a thin Android adapter.
- **Tests (JVM, against the stubs):** **T12.1** playing from a shelf queues movement → rest of work → rest of that shelf; Start here and Recently played play their lists; `Previous` > 3 s restarts; **T12.2** the instrument switch sends pause(30) → setBank → setPerformance(startUs = −1) and never waits for the renderer; **T12.3** generation increases on every new Performance; the next item is pre-compiled; **T12.4** a view change sends exactly one `setRoom` with the §5.6 listener and the kit's `embeddedRoomDb`; **T12.5** status items expire and are ordered by UiText's priorities; **T12.6** seek units: `UiAction.Seek`, companion `ms` and CONTROL `--el seek` all land on the same song µs (pre-roll included); **T12.7** resume point round trip; **T12.8** `LevelCalibrationTest` (M5): real `EngineCore` + real `DspFactory` + the Player design: the v127 C-major triad with the pedal peaks at −6 ± 2 dBFS pre-limiter; the resulting `masterDb` is written to `Settings` defaults.

### 7.3 Day 0 and days 1–2 (WP0)

1. **Hours:** `git config` identity; `.gitignore` kept (it already ignores `tools/cache/`, builds, APKs, credentials); `.gitattributes`; `settings.gradle.kts` with `:core` and `:app`; `tools/env.sh`, `tools/wt.sh`, `tools/ci.sh`, `tools/check_purity.sh`, `tools/device/lock.sh`; every §2.3 signature with trivial stub bodies; `docs/contracts/map-json.md`; `./gradlew :core:test :app:assembleDebug` green → commit → tag **`contracts-v1`** → `tools/wt.sh` for the 12 worktrees → hand each agent its WP section of this plan.
2. **Days 1–2:** the shell (`HammerklavierApp` singletons, `MainActivity`, `AppController` adapter, `Wiring` with stubs); the primitives with tests; working stubs; the day-0 `MeshBuilder` subset; `system/*`; `platform/*`; `tools/device/*` (§8) → `tools/ci.sh` green → `tools/device/run.sh` → M0 gate on the glasses → commit → tag **`contracts-v1.1`**; every WP rebases (bodies only).

### 7.4 Milestones (every one ends with `tools/ci.sh` green, `tools/device/run.sh` installing the release build and verifying it, `tools/device/smoke.sh M<n>` passing, and a short on-glasses check)

| M | Name | Merged | What runs on the glasses | Gate |
|---|---|---|---|---|
| **M0** | Shell | WP0 (`contracts-v1.1`) | title card in both eyes (`StubOverlay`), gesture echo, CONTROL echo, thermal log (also display off), self-test with branch/commit, GL info and the torn-read test, `StubGlHost` gilt test frame per eye | WP0 device tests |
| **M1** | First sound | WP1, WP2, WP3 (limiter; others pass-through), WP4 (`AudioOutput`, clock, park, supervisor, `SynthBank`), WP11 (test MIDIs, stub bank, bench stream, probe) | `--es play synth:scale`, `synth:pedalhalf`, `synth:storm64` sound on the stand-in bank; the debug overlay shows voices, block times, headroom, clock source and misses; audio keeps playing when the sleep button turns the display off | `--ez bench true` numbers recorded (voice cap set; decode × real time and setup per stream); T-UND 5 min = 0 with the display on and 5 min asleep; HKAudio < 15% (normalised) on `synth:scale`; T-GC; the drift line: p99 \|eᵢ\| ≤ 1 ms and `fs_fit` within 0.5% of 48 kHz; T-CLOCK |
| **M2** | Real grand | WP11 (grand kit after approval + Start-here MIDIs + partial catalogue), WP4 (decoder, cache, mmap, prefetch, DecoderProbe, KeyMap), WP1 (corpus), WP3 (FDN with `FixedRoom.PLAYER`), WP9 (`CatalogCodec`, bundled-only `LibraryServiceImpl`) | first-run voicing with progress (debug overlay), then `asset:midi/krueger/bach/bach_846.mid` and Für Elise on the Salamander grand with release and pedal noises and room | T-DEC, T-ALIGN, T-PF (majflt 0, display on and asleep), T-RESUME (force-stop and reboot), **T-UND-FIRSTRUN**; T-CPU (storm64 ≤ 42% normalised, p99 ≤ 3.2 ms, headroom ≥ 1,536); comb calibration re-checked with the real kit; battery ≤ 39 °C after 10 min of storm64 |
| **M3** | Keys move | WP5 (grand keys, hammers, pedals), WP6, WP7 (keyboard, grand case, pedals) | the Player view animated from the audio clock; Follow framing with the pedal inset; dips between framings | T-SYNC at Q0 and Q2 on the speaker (lead calibrated, \|median\| ≤ 20 ms each) and **on a Bluetooth headset** (its route's lead); T-UND 5 min on Bluetooth; T-FPS; T-GLRESET; draws ≤ 28; L-1 (presence floor) |
| **M4** | Hammers hit strings | WP7 (grand action set, hammers, dampers, strings), WP5 (ExposureSampler, strings from energy, repetition), WP2 (EnergyRing lanes), WP10 (`UiStateMachine` for the ring and framings only) | swipe Player ↔ Action with the dip; cutaway and overhead; hammers strike on the sound; dampers stop strings; `synth:repeat15` visible | T-SYNC in the Action view; every strike drawn exactly once at 30 and 20 fps; swipe-to-first-fade < 100 ms |
| **M5** | The room | WP8, WP3 (combs, RoomAcoustics, ER, direct path, soft bus, speaker enhancer), WP6 (venue draws, flames, CPU mirrors, probe), WP12 (`LevelCalibrationTest`) | the Hall view with candles, mirrors, chandelier and look-around; sound follows the view (bench → case → row 3) and stays put when the head turns; sympathetic halo under the pedal | T-APL (all views); **first T-THERM** (30 min unplugged, op. 106 on the grand, view rotation); T-CPU with combs; comb calibration re-checked; L-2 (speaker voicing), L-7 (resonance) |
| **M6** | Library, menus, import | WP9, WP10 (all), WP11 (the full corpus, `catalog.json`, credits), WP12 (all) | title card → Start here; Transport, Library and More menus; all 70 (or 67–69) works; companion upload from a phone and from `curl`; `push_scores.sh` + rescan; a malformed file rejected with its reason; the Back key and leaving the app | T-5MIN; T-IMPORT; T-LEAVE; L-5 (first-use test); L-6 (tier-C listening) |
| **M7** | Three instruments | WP11 (upright and harpsichord kits), WP7 (upright, harpsichord models), WP5 (upright, harpsichord mechanics), WP2 (stops, stagger, legato, handoff), WP10 (Sound menu: temperament, pitch, registration) | instrument switch mid-piece; harpsichord at A415 Werckmeister III with the key remap; jacks, tongues and registers; Goldberg on the harpsichord, K. 545 on the upright | T-DEC for both kits; the A415 remap logged for key 69 (root 68, shift ≈ −1.27 cents + temperament + shape, output pitch within 0.5 cent of target) and T4.2's properties on the real maps; T-Q3SWITCH; L-4 (temperaments); instrument switch < 2 s when cached |
| **M8** | Soak and release candidate | all WPs (fixes), WP0 (Credits/About panels wired, baseline profile, README), WP11 (ledger complete) | everything | **45-min unplugged T-THERM** and the max-brightness run, T-UND 30 min (display on and asleep), T-UND 5 min on Bluetooth, T-SYNC on Bluetooth, T-MEM, T-START; `smoke.sh all`; listening L-1…L-8 signed off with the user; commit and push |

### 7.5 Dependency graph

```
day 0 (hours) ── WP0 signatures + trivial stubs ──► tag contracts-v1 ──► 12 worktrees (tools/wt.sh)
days 1–2 ─────── WP0 primitives + working stubs + shell + M0 ──► tag contracts-v1.1 (everyone rebases, bodies only)
          │
wave A ───┼─ WP1 MIDI ──────────┐
(all from ├─ WP2 engine ────────┼──► M1 FIRST SOUND (stand-in bank)
 day 0,   ├─ WP4 audio I/O ─────┤
 against  ├─ WP3 DSP (limiter) ─┘
 stubs)   ├─ WP11 pipeline ─(user approval)─► grand kit ──► M2 REAL GRAND (+ WP9 catalogue codec)
          ├─ WP7 MeshBuilder (day 2) ──► WP8 rebases
          ├─ WP5 mech ──────────┐
          ├─ WP6 render core ───┼──► M3 KEYS MOVE ──► M4 HAMMERS (+ WP10 ring)
          ├─ WP7 grand model ───┘
          ├─ WP8 venue ─────────┬──► M5 THE ROOM (+ WP3 full, WP12 level test)
          ├─ WP9 library ───────┼──► M6 LIBRARY
          ├─ WP10 UI ───────────┤
          └─ WP12 session ──────┘
                                     └──► M7 THREE INSTRUMENTS ──► M8 SOAK + RC
```

The critical path is WP0 (hours) → (WP2 ∥ WP4) → M1 → approval → WP11 grand kit → M2. M1–M3 prove the clock, the thermals and A/V sync before content polish. If M1 misses its budget, WP2 and WP3 re-plan (the §3.16 measured step-down order) before M4.

---

## 8. Verification plan on the real glasses (serial `A06B4A96A733283`)

### 8.1 Build, install, launch (`tools/device/run.sh`)

```bash
ROOT=$(git rev-parse --show-toplevel); . "$ROOT/tools/env.sh"          # ANDROID_HOME; never an absolute project path
S=A06B4A96A733283; PKG=com.tropicalstream.hammerklavier
APK="$ROOT/app/build/outputs/apk/release/Hammerklavier-release.apk"
"$ROOT/tools/device/lock.sh" -- bash -c "
  '$ROOT/tools/ci.sh' &&
  adb -s $S install -r '$APK' &&
  [ \"\$(adb -s $S shell md5sum \$(adb -s $S shell pm path $PKG | sed 's/package://') | cut -d' ' -f1)\" = \"\$(md5 -q '$APK')\" ] &&
  adb -s $S shell cmd package compile -m speed -f $PKG &&
  adb -s $S shell am start -S -n $PKG/.MainActivity"
# bench sessions off the head (the launcher force-stops apps ~2 s after launch otherwise); every bench script restores it:
#   trap 'adb -s $S shell settings put global device_wearing 0' EXIT
adb -s $S shell settings put global device_wearing 1 && adb -s $S shell wm dismiss-keyguard
# flat single view for screenshots (am start -S restarts the singleTask activity so the extra is read):
adb -s $S shell am start -S -n $PKG/.MainActivity --ez mono true
adb -s $S logcat -s HKAudio HKClock HKRender HKThermal HKInput HKLoader HKKit HKLib HKWeb HKPerf HKSelfTest HKUi HKSoak AndroidRuntime:E
```

- **Always pass `-s`, always chain with `&&`** so a failed build never installs a stale APK [R:engine_reuse §6.5]. `run.sh` **verifies the installed APK's md5** against the local file (a post-install reboot once rolled a sibling package back), and the self-test prints the branch and commit it measured.
- **The measured build is the release build** (debug keystore, `isDebuggable=false`) plus `cmd package compile -m speed -f` and `baseline-prof.txt` (`engine/**`, `dsp/**`, `kit/**`, `mech/**`, `render/**`, `midi/**`, applied by `profileinstaller`). A debuggable build distorts T-CPU. Debug builds are for development and for the allocation and GL-reset hooks only.
- **Importing on the device** is only `tools/device/push_scores.sh` (§1.7).
- After copying any tree: `rm -rf .gradle app/build core/build` before the first build.

### 8.2 CONTROL broadcasts (`adb -s $S shell am broadcast -a com.tropicalstream.hammerklavier.CONTROL …`)

The receiver is registered with `registerReceiver(r, filter, android.Manifest.permission.DUMP, null)` (plus `RECEIVER_EXPORTED` on API 33+), so only the shell (which holds DUMP) can drive it; it lives as long as the engine, so `rescan`, `faketemp` and `soak` work with the display off.

| Group | Extras |
|---|---|
| Views | `--ei view 0\|1\|2` (Player / Action / Hall) · `--ei framing 0\|1` |
| Playback | `--es play <name>` (the §4.5 name table: `synth:…`, `test:…`, `asset:…`, or a movement id such as `beethoven.op106.4`) · `--ez pause true` · `--ez resume true` · `--el seek <display ms>` (converted to song µs) · `--ef rate 0.8` · `--ez next true` · `--ez prev true` · `--ez leave true` (as the Back key at the root) |
| Instrument and sound | `--es instrument grand\|upright\|harpsichord` · `--es temperament WERCKMEISTER_III` · `--ef pitch 415` · `--ei registration 1\|2\|3` · `--es resonance OFF\|NATURAL\|RICH` · `--es reverb DRY\|ROOM\|RESONANT` · `--es speakerbass AUTO\|ON\|OFF` · `--ez lowlatency true` (forces the fallback track) |
| Display | `--ei quality -1\|0..3` · `--ei faketemp <tenths °C>` · `--ez recenter true` · `--ef fov 18.3` (current framing, `RenderOverrides`) · `--ef ipd 0.6` · `--ei lead <ms>` (current route) · `--ei floor 22` · `--es room SALON\|STAGE\|INSTRUMENT\|PASSTHROUGH\|AUTO` · `--es palette STADTSCHLOSS_1747` · `--ez msaa false` (stored; applies at the next launch) · `--ef brightness 0.6` (window cap; −1 = system) |
| UI and input | `--es gesture tap\|double\|triple\|fwd\|back\|up\|down\|sysback` (through `UiStateMachine`, as the pad) · `--es card floor\|sync` · `--ez menu true` · `--ez library true` |
| Library | `--ez rescan true` |
| Diagnostics | `--ez debug true` · `--ez selftest true` · `--ez bench true` · `--ez gcstats true` (logs `art.gc.gc-count`, `gc-time`) · `--ez sync true` (sync flash + `synth:sync`) · `--ez dump true` (state snapshot and every `diagnostics()` map to logcat) · `--ez soak true\|false` (the in-app CSV recorder) with `--es soakplan therm45\|bright\|rest10\|sleep20` (the scripted soak run by the app's own timer) · `--ez glreset true` (debug build: recreate the GL view) |

### 8.3 Self-test and smoke

`--ez selftest true` prints `HKSelfTest` lines starting with `version=… branch=… commit=…`, then `PASS|FAIL <item> <detail>` for: each kit's `map.json`; each PCM cache (valid, CRCs checked, or voiced in N s); the DecoderProbe offset; the catalogue (N works) and every movement asset present; 20 random bundled files compile for all three instruments; the AudioTrack (rate, buffer frames, performance mode, routed device type and name, the output it landed on, underruns, clock source and `lat`); GL (renderer string, `GL_MAX_VERTEX_UNIFORM_VECTORS`, config, `EGL_SAMPLES`); **the on-device torn-read test** (a writer thread publishing clock and energy records with a checksum field at 10 kHz, the GL thread reading, 60 s: 0 mismatches); the companion server (URL); free space; `crash.txt` absent; the `Scores/` ownership check.

`tools/device/smoke.sh M<n>` (under `lock.sh`) runs that milestone's CONTROL script (the `--es gesture` steps for M4+), grabs `adb exec-out screencap -p` per step into `build/smoke/M<n>/`, runs the self-test, and fails on any `FAIL`, `AndroidRuntime`, `FRAME HITCH`, non-zero `underruns` after warm-up, `majflt` growth, or non-zero `clockMiss`/`energyMiss` on the speaker. From M1 it includes **the clock cycle:** pause, `input keyevent KEYCODE_HOME` (onStop while paused → park), return, play; `HKClock fromTimestamp=true` and keys moving within 1 s. From M6: **leaving while playing** (`--ez leave true` and `KEYCODE_HOME` with the display on): the music fades and pauses; with the display asleep (`input keyevent KEYCODE_SLEEP`) it keeps playing.

### 8.4 Measurements and pass criteria

| Test | Procedure | Pass |
|---|---|---|
| **T-CPU** | `synth:storm64` 3 min, then 3 min of `beethoven.op106.4`, Q0 forced. `HKAudio stats voices= peak= cap= noise= stolen= dropped= combs= p50= p99= max= f= ur= slow= head=` every 10 s; `tools/device/cpu.sh` logs `top -H` for the app's threads **and `media.swcodec`**, `/proc/self/task/<tid>/stat` CPU time via the debug line, `scaling_cur_freq` and `time_in_state` deltas | HKAudio ≤ 42% (storm) and ≤ 20% (op. 106) of a 2.0 GHz core, normalised by the measured mean frequency; p99 ≤ 3.2 ms at the frequency run; queued frames ≥ 1,536 after warm-up; GLThread ≤ 18%; the app plus `media.swcodec` ≤ 120% of 400%; `stolen` per movement logged on op. 53 iii and op. 57 iii |
| **T-UND** | 30-min soak of op. 106 i–iv then WTC I fugues, once with the display on and once asleep (sleep button), from the in-app CSV; `AudioTrack.getUnderrunCount()` cross-checked with `dumpsys media.audio_flinger` | Δ = 0 after warm-up in both runs (if the asleep run fails: `HK.USE_FG_SERVICE`, then re-run) |
| **T-UND-FIRSTRUN** | clear data, launch, tap as soon as playable, play BWV 846 then a pedalled Beethoven movement while the rest of the grand is still unvoiced (voicing yields during playback and resumes at each pause); `majflt` logged | 0 underruns after the first note |
| **T-UND-BT** | 5 min of op. 106 iv on a Bluetooth headset (M3, M8) | 0 underruns; `lat` logged |
| **T-PF** | the T-UND runs; `slow=` and the HKAudio task's `majflt` in `HKPerf` | 0 after the first 60 s, display on and asleep; ≤ 3 in the first 10 s after a cold boot |
| **T-DEC** | clear data (or delete `noBackupFilesDir/pcm` in a debug build), launch, read `HKKit` timings; the M1 decode bench first | grand playable ≤ 8 s; complete ≤ 120 s (HD) / 45 s (standard) of idle time; upright ≤ 30 s; harpsichord ≤ 12 s; decode × real time and setup per stream logged, `media.swcodec` CPU logged |
| **T-ALIGN** | `synth:scale` and `test:scale` with `--ez debug true`; the `EngineCore.debugOnset` hook finds the first sample of each new voice above −40 dB re its expected peak in the **rendered master buffer** and compares its frame with the scheduled frame + `thrFrame/rate` | ±1 frame (±2 at non-unity playback rates) |
| **T-RESUME** | `am force-stop` mid-decode, relaunch; and **`adb reboot` mid-decode**, relaunch | resumes at the first missing unit; after the reboot the CRC check runs and any unit that fails is re-voiced; no silent notes (a C-major scale over every layer checked by the onset hook) |
| **T-CLOCK** | `synth:scale` playing: pause → `KEYCODE_HOME` → return → play; `adb shell killall audioserver` if permitted, else toggle Bluetooth on and off, while playing; a route change speaker → wired | fromTimestamp true within 1 s of every resume; keys move within 1 s; sound back within 1 s of an output loss (`trackRebuilds` counted); `clockMiss` = 0 on the speaker |
| **T-GC** | `--ez gcstats true` before and after 5 min of playback | ≤ 2 GCs; heap flat within 2 MiB |
| **T-MEM** | `adb -s $S shell dumpsys meminfo $PKG` and `/proc/<pid>/status` VmRSS at 5 and 30 min | Java heap ≤ 48 MiB; TOTAL PSS − mapped-file PSS ≤ 200 MiB; RSS ≤ 450 MiB |
| **T-FPS** | debug overlay and `HKRender fps= late=` in every framing | 30 ± 1 (Q0–Q1), 20 (Q2), 10 idle; no `FRAME HITCH`; frames with late > 8 ms ≤ 2% |
| **T-APL** | `tools/device/apl.sh <framing>`: `adb exec-out screencap -p` → `ffmpeg` to raw gray → `apl_meter.py` (numpy) mean Rec.709 luma over 1280 × 480; if screencap misses the GL layer, a frame from `scrcpy --record` | Player, Action ≤ 9%; Hall ≤ 12% |
| **T-START** | cold start with caches warm → first sound | ≤ 4 s |
| **T-THERM** | §8.5 | no reboot; never Q3; plateau ≤ 42 °C; target Q0 held 30 min |
| **T-SYNC** | §8.6 | \|median\| ≤ 20 ms at Q0 and at Q2 (each), p90 − p10 ≤ 33 ms |
| **T-5MIN** | scripted `--es gesture` walkthrough of §1.9 with a screencap per step | every screen reached; no FAIL |
| **T-IMPORT** | `curl --data-binary @test.mid -H "x-hk-token: …" -H "x-hk-name: H%C3%A4ndel.mid" http://<ip>:19112/api/upload`; a zip; a corrupt file; `push_scores.sh` with a subfolder `Op 109/`; **a file pushed with a bare `adb push` from a 0600 host file**; a push before the first launch (the script must launch the app first) | saved / rejected as specified, rejections recorded without touching the source; the 0600 file shows `Permission denied: run push_scores.sh`; the subfolder becomes one work; appears in Imported within 10 s |
| **T-LEAVE** | playing, display on: `KEYCODE_HOME`, `KEYCODE_BACK` at the root, launching another app; then the same with the display asleep | display on: fade and pause within 0.5 s, resume point saved; asleep: keeps playing to the end of the item |
| **T-GLRESET**, **T-Q3SWITCH** | §7.2 WP6 | as stated there |

### 8.5 Thermal soak (`tools/device/soak.sh 45`, integrator only)

1. Start cool: battery < 33 °C (`adb -s $S shell dumpsys battery | grep temperature`), room ≈ 22 °C, glasses worn or `device_wearing=1`, the user's normal brightness.
2. `--ez soak true` starts the **in-app recorder**: every 10 s one row to `getExternalFilesDir(null)/soak.csv` (battery °C, thermal status, Q, per-thread CPU ms from `/proc/self/task/*/stat` for HKAudio, GLThread, HKPrefetch, HKVoicer and main, fps, late frames, underruns, headroom minimum, `majflt`, window brightness, playing movement and position). Then **unplug the USB cable**: plugged in, `dumpsys battery` shows `USB powered: true, status 5` [M:rev] and the battery barely discharges, which is exactly the condition under which the known reboots did not happen.
3. The soak plan runs inside the app, so nothing needs adb while unplugged: `--ez soak true --es soakplan therm45` starts the recorder and the plan: op. 106 (35 min) then the Moonlight on the grand, rotating Player → Action cutaway → Action overhead → Hall every 5 min; the last 5 min loop `synth:storm64` (other plans: `bright` for the maximum-brightness run, `rest10`, `sleep20`).
4. Reconnect: `adb pull …/files/soak.csv build/soak-<date>.csv` and `adb shell cat /proc/uptime` (a reboot shows as a short uptime and a truncated CSV). Report: time to each Q transition, peak temperature, CPU per thread per level, fps per level, hitches, underruns.
5. **Pass:** no reboot or kill, never Q3, plateau ≤ 42 °C. **Target:** Q0 for ≥ 30 min at 22 °C. If the target fails, tighten in this order: idle fps, mirror flames, crystals, the Antialiasing default (off, at the next launch), combs 44, voice cap − 8, Hermite → linear at Q0; frame rate last.
6. Repeat **once at maximum brightness** (the X3 Pro's known reboot condition) and record where the Q2 brightness cap engages; repeat with Q3 forced for 10 min (the temperature slope must be flat or falling with the music playing); and with the display off (sleep button) for 20 min on a **playlist longer than 20 min** (WTC I fugues), confirming from the CSV that playback continued and the battery ends ≥ 3 °C cooler than the display-on soak. At M7, repeat on the harpsichord (Goldberg) and the upright. At M8 run one soak **from a cleared cache**, so first-run voicing (title card, pauses) is included; `cpu.sh` measures `media.swcodec` in a plugged-in preview run.

### 8.6 A/V sync

- **Goal:** the hammer meets the string exactly when the note sounds.
- **Stimulus:** `--ez sync true`: `synth:sync` (A4 v118, 100 clicks, 600 ms apart plus a pseudo-random 0–33 ms each) through the normal Performance path; the renderer draws a 60 px disc (255,244,214) at screen centre in the frame whose exposure window contains each contact (`pose.flash[69]`, the same mechanics state, not an audio event). The jitter spreads contacts over every phase of the frame, and the centred window removes the half-frame bias, so the median of ≥ 40 events is unbiased at 30 and at 20 fps.
- **Internal check:** with `--ez debug true` the overlay shows the clock source (`fromTimestamp`), `lat`, `tsRej`, `clockMiss` and the drift line: per accepted timestamp pair `eᵢ = frameᵢ − (frameᵢ₋₁ + (nanosᵢ − nanosᵢ₋₁) · fs_fit / 1e9)`, with `fs_fit` the running regression (the FastMixer reports ≈ 47,931 Hz [M:rev], ≈ 1,400 ppm below nominal, so nothing integrates a nominal 48 kHz). Pass: p99 |eᵢ| ≤ 1 ms and `fs_fit` within 0.5% of 48 kHz over 30 min.
- **Coarse (regression) check:** `scrcpy -s $S --record=build/sync.mkv --audio-source=output --time-limit=60`, then `python3 tools/device/avsync.py build/sync.mkv` (numpy: flash frames by luma, click onsets by envelope) → median and spread. scrcpy's own audio/video capture latencies differ, so this is a baseline, not the absolute offset.
- **Absolute check:** a phone filming the lens at 240 fps with the temple speaker in shot (or a piezo on the temple); count frames from flash to onset over ≥ 40 events.
- **Calibrate** with `--ei lead N` (or the A/V sync card) until the absolute median is within ±5 ms; the value is stored **per route class** (speaker, wired/USB, each Bluetooth device address) and the speaker's becomes the `displayLeadMs` default (starting value 30 ms). T-SYNC runs at **Q0 (30 fps) and Q2 (20 fps)**, |median| ≤ 20 ms at each with the one stored lead.
- **Bluetooth:** at M3 and M8, T-SYNC on a Bluetooth headset; log the `getTimestamp` latency (`lat`) against `track.bufferSizeInFrames` for each route. If this HAL's A2DP timestamps exclude the codec and link latency, the Bluetooth lead default becomes the measured offset (so the hammer never strikes 100–250 ms before the note), and the first use of a new device offers the card.
- **Look-ahead check:** in the Action view at `--ef rate 0.5`, `synth:pedalhalf` and `synth:repeat15` must show each key moving before its click and the hammer at the string on the click; at rate 0.5 the dampers land on the audio's damping onset (±3 ms, T2.3/T5.2 on the device through the onset hook).
- **Pass:** |median| ≤ 20 ms at Q0 and Q2 and p90 − p10 ≤ 33 ms (one frame).

### 8.7 Human listening and legibility checks (with the user, M3–M8)

| # | Check | Decides |
|---|---|---|
| L-1 | Display floor card in a lit and a dim room; the grand in Player and Hall | `presenceFloor` default; Edge overlay default |
| L-2 | Built-in speakers vs headset: Für Elise, Moonlight i, the Waldstein opening | speaker voicing and virtual-bass constants |
| L-3 | `synth:crescendo` on the grand (HD or standard) and the upright; the three Salamander stretch fits | whether any layer boundary needs a larger smoothing correction; the stretch source; the grand damper T60 by ear |
| L-4 | WTC I No. 1 and Scarlatti K. 141 on the harpsichord in Werckmeister III vs Equal, 8′+4′ vs 8′; keys 84–89 (the borrowed seam) | harpsichord defaults; seam trims |
| L-5 | A person new to the app, no instructions, 5 minutes | whether they reach all three views, pause, the Library and another instrument unaided; otherwise adjust the hints |
| L-6 | Tier-C engravings from `build/listen.txt` | keep or drop them |
| L-7 | Pedalled Moonlight i, the Waldstein rondo and a una corda passage; Resonance Off / Natural / Rich; any audible steals at the bench cap | comb `SEND`, `UNA_CORDA_SEND` and loop-filter defaults; the step-down order |
| L-8 | ABX of Opus 112 vs 128 kb/s on quiet decays (A0v1, C4v4, harpsichord 4′ top) | keep 112 kb/s, or rebuild at 128 (+8 MB) |

### 8.8 Input checks

- **Left-arm filter:** a firm click on the left arm produces no `HKInput tap` (the `cyttsp6` key-path filter; physical, M0).
- **Firm-click dedup:** one firm click on the right pad logs exactly one tap, never a touch tap plus a key tap (physical, M0).
- **Swipe direction:** toggle the system's natural-mode setting; if forward/back flip, More › Sight › Reverse swipe fixes it.
- **Double-tap:** 200 physical double-taps in a row never also toggle play/pause (the timing cannot be produced from the host, where every `adb shell input` call starts its own process; `--es gesture double` covers the state machine).
- **Back key:** in a menu it goes back one level; at the root it leaves the app with a fade.
- **Latching:** a very fast swipe moves a menu by exactly one row; a swipe during a dip is queued once.

---

## 9. Risk register and non-goals

### 9.1 Risks (L = likelihood, I = impact)

| # | Risk | L | I | Mitigation | Early signal · owner |
|---|---|---|---|---|---|
| 1 | Heat: audio + GL + a bright display reboot the glasses, as a sibling app did (unplugged, long sessions, max brightness) | M | H | one ladder Q0–Q3 across picture, sound and window brightness with Q3 display rest; governor alive with the display off; idle 10 fps; allocation-free loops; release build + AOT; APL caps; voicing only when nothing plays; parked output when idle; headroom-driven voice cap | **unplugged** T-THERM at **M5** (not at the end), a max-brightness run · WP0 |
| 2 | ART on the A55 at 1.1–1.5 GHz is slower than the cycle estimates | M | H | `EngineBench` on day one of M1 sets the voice cap (64–128) and the measured step-down order; CPU gated normalised by frequency, overload by buffer headroom | T-CPU at M1/M2/M5 · WP2, WP3 |
| 3 | Page faults in the mmap'd cache stall HKAudio (eMMC, writeback, low IO priority with the display off) | M | H | no whole-kit `load()`; `pread` prefetch scheduled by song time; no voicing during playback; chunked `force()` writes; 85 ms buffer; `majflt` and `slowReads`; the documented streamer fallback | T-PF (display on and asleep), T-UND-FIRSTRUN at M2 · WP4 |
| 4 | The user declines the HD download | M | L | the standard 6-layer grand with ±4 crossfade is a supported configuration of the same code (only `map.json` differs) | approval · WP11 |
| 5 | `PERFORMANCE_MODE_NONE` float track misbehaves on this HAL, lands on DEEP_BUFFER, or dies on audioserver restarts | L | M | one-line fallback to LOW_LATENCY + `LatencyTuner` (`--ez lowlatency true`); routed output logged; `TrackSupervisor` rebuilds on `DEAD_OBJECT` | T-UND, T-CLOCK at M1 · WP4 |
| 6 | Opus decoder offset, speed or availability differ; `media.swcodec` is slow or hot | L | M | DecoderProbe; exact frame counts; packed unit streams with 60 ms frames; one reused codec; measured decode bench at M1; Concentus fallback; `SynthBank` fallback | decode bench, T-DEC, T-ALIGN · WP4 |
| 7 | The combs sound metallic, phasey or double the sample's own sustain | M | M | no self-feed (mix − self); allpass tuning to the measured f0; dispersion from the fitted B; per-register loop filter; calibration on real regions; Off/Natural/Rich | T3.1, L-7 · WP3 |
| 8 | FDN metallic ringing on piano | M | M | mutually prime delays; two modulated lines; the mode-flatness test | T3.3 · WP3 |
| 9 | Black lacquer and ebony keys vanish on the waveguide | H | M | the reflector recipe; walnut upright default; bone harpsichord naturals; presence-floor card; edge overlay | L-1 at M3 · WP6, WP7 |
| 10 | Stereo discomfort (off-axis frusta, IPD scales, 0.5 s dips) | M | M | per-framing IPD and zero parallax; `--ef ipd`; Stereo depth setting; mono fallback | M3 on-head review · WP6 |
| 11 | A/V misalignment from unknown display latency or Bluetooth link latency | M | M | reset, filtered, clamped clock; centred exposure; per-route lead; jittered sync stimulus at 30 and 20 fps; Bluetooth T-SYNC | T-SYNC at M3/M4/M8 · WP0, WP6 |
| 12 | Layer steps audible (standard kit) or smoothing artefacts (HD) | M | L | natural levels + a continuous level curve; the measured crossfade law; the report lists corrections | L-3 · WP11 |
| 13 | VCSL sample rate, depth, maker or pitch standard differ from assumptions | M | L | ffprobe + swr; partial-series f0; the pitch standard separated from the stretch shape, so any `recordedAHz` plays in tune; the drawn harpsichord is not claimed to be the sampled one | kit report · WP11 |
| 14 | piano-midi.de blocks the Mac (HTTP 418, confirmed by research) | H | L | Wayback raw URLs with SHA-1 verification | fetch log · WP11 |
| 15 | Sankey zip entry names differ from expectations | M | L | list first, then fill `sankey_map.tsv`; byte-identical extraction | M6 · WP11 |
| 16 | Licence breach by modification or export | L | H | original MIDI bytes parsed at run time; ledger gate; credit HUD; no export feature | T11.4 · WP11 |
| 17 | Messy or hostile imports | H | L | bounded parser that never throws; fuzzing; limits; rejections recorded with reasons | T1.6, T9.2 · WP1, WP9 |
| 18 | Input ambiguity (double-tap also pausing, left-arm click as tap, natural-mode flip, one click as touch + key) | M | M | 300 ms resolver; `cyttsp6` key filter; dedup; Reverse swipe; physical checks | §8.8 · WP0, WP10 |
| 19 | The low-RAM device kills the app while the display is off | M | L | resume point every 5 s; RSS budget; no whole-kit preload; the foreground service kept as a measured fallback | T-MEM · WP0 |
| 20 | Built-in speakers lose the bass | H | M | high-pass + 250 Hz lift + virtual bass on the speaker route; headphones recommended on the title card | L-2 · WP3 |
| 21 | The Adreno uniform limit is lower than assumed, or MSAA is missing | L | M | log `GL_MAX_VERTEX_UNIFORM_VECTORS` and `EGL_SAMPLES`; ≤ 68 vec4 per shader; chooser fallbacks; analytic AA | M-GL · WP6 |
| 22 | Screencap misses the GL layer (APL unmeasurable) | M | L | scrcpy frame grabs | T-APL · WP0 |
| 23 | Parallel agents drift from the contracts, or one breaks others' branches | M | M | contracts-v1 in hours with specified stubs; growth rules with defaults and named arguments; `ci.sh --contracts`; the `:core` JVM module; only WP0 edits the wiring | CI on every merge · WP0 |
| 24 | Git LFS quota (1 GB storage / month bandwidth) | M | L | commit kits only at milestones; raw sources never in git | – · WP11 |
| 25 | First-run storage (≈ 1 GiB of cache for HD) | L | M | free-space check; reduced grand fallback; 19 GB free today | T-DEC · WP4 |
| 26 | An unclean reboot leaves unwritten cache pages marked ready (f2fs `nobarrier`) | M | M | `force()` before the ready bit; per-unit CRC32; verification after a `BOOT_COUNT` change | T-RESUME with `adb reboot` · WP4 |
| 27 | Agents measure the wrong APK or collide on the one pair of glasses | M | M | `wt.sh` + `env.sh`; root from `git rev-parse`; md5 check after install; commit hash in the self-test; `lock.sh`; integrator-only soaks | every device result · WP0 |
| 28 | Imports pushed with adb are unreadable or lock the app out of its folder | H | L | `push_scores.sh` (launch first, chmod); read-only input copied into the app store; ownership check and clear status | T-IMPORT · WP9, WP0 |

### 9.2 Non-goals for v1

- **A fortepiano** (Silbermann, Stein, Walter) or a clavichord: no redistributable samples. Never faked by filtering another set. The fourth slot opens only for a licensed multisample (e.g. with Dore Mark's written permission for his Clementi 1808).
- The harpsichord lute stop, una corda samples, the Salamander `harm*` resonance samples (replaced by the combs), Accurate-Salamander (a later drop-in with the same `map.json` schema), the upright practice rail.
- Physical-modelling synthesis and convolution reverb.
- **Audio or video export**, screen recording and sharing (Sankey's clause and the CC BY-SA 3.0 DE moving-image rule).
- Live MIDI input (USB or Bluetooth keyboards) and playing the virtual keyboard; the engine's sample clock makes it the natural v2.
- Learning features: falling notes, score display, fingering, hand colouring, MIDI editing or quantising, loops and practice modes.
- CC7/CC11 dynamics, pitch bend and General MIDI multi-instrument playback (all non-drum channels play the one keyboard).
- A fourth "Inside" view (it is Action's second framing); FOV animation and camera fly-through transitions (the dip is used); a pianist figure.
- Other rooms (Amalienburg), fresco ceilings, painting reproductions, any downloaded image.
- The complete 555 Scarlatti sonatas, the MAESTRO/SMD non-commercial packs, Beethoven op. 109, op. 110, op. 111/ii and op. 31/2/iii (no clean source); users can import them.
- **Background playback without a UI:** leaving the app with the display on pauses the music; with the display asleep it plays to the end of the current playlist item. A foreground media service exists in the code but stays disabled unless the display-off tests require it. Voiced programme notes (the glasses have no TTS engine).
- 6-DoF head position, hand tracking, camera use; the vendor MercurySDK UI (the only vendor dependency is the `com.rayneo.mercury.app` meta-data flag).

---

## 10. Review log

The architecture review of 2026-09-22 raised 108 issues (7 blockers, 50 major, 51 minor; some raised twice by different reviewers, which the table marks "= Rn"). Every blocker and major issue is resolved; every minor issue is resolved or, where noted, resolved differently. "Resolved differently" means the problem is fixed but not exactly by the fix proposed, with the reason. The last rows list defects the architect found while revising.

| # | Sev. | Where | Issue | Resolution |
|---|---|---|---|---|
| R1 | blocker | §2.3 Clock, §2.5, §1.10, §3.1 | `AudioClock` had no reset; its frame domain was undefined across track restarts, freezing the keyboard or jumping it forward | `AudioClock.reset()` added and called before every `play()` (start, un-park, rebuild): clears head, records and anchor, re-enters estimate mode, bumps `session`. `framesAccepted` counts `write()` returns (priming block, short and negative writes). `flush()` is never called; `pause()` only to park (R32). Timestamps are polled while idle. AudioClockTest adds stop/start, a short write, a stale timestamp; smoke adds the pause → home → return → play cycle (§2.5, §3.1, §7.2 WP0, §8.3) |
| R2 | major | §2.1 rule 2, §2.3 rings | Seqlocks with plain payload fields are not ordered on ART/ARMv8; the timestamp pair was not under any seqlock | All seqlocked payloads (records, anchor, 88 energy lanes) live in `AtomicLongArray`/`AtomicIntegerArray` accessed by `get`/`set`; the anchor is its own two-long versioned record; an on-device torn-read test (10 kHz writer, GL reader, checksums, 60 s, 0 mismatches) is in the self-test (§2.1, §2.3, §8.3) |
| R3 | major | §2.5, §5.7, §2.3 | Heard song time was not monotone (estimate allowance, raw re-anchoring, underruns, BT steps, lead changes, extrapolation past the last record) | `sample()` clamps H to newestF + BLOCK; timestamps accepted only if advancing and within ±0.5% of a running fit (`tsRejected`); the estimate uses the measured per-route latency; a `VisualClock` contract primitive holds, follows, slews (10%/frame) or re-seeds (< −60 ms, > +250 ms); key cursors step back; VisualClockTest with jitter, underrun, +150 ms step, −5 ms lead change (§2.3, §2.5, §5.7, §7.2) |
| R4 | major | §2.3, §3.1, §1.10, §8.6 | Ring depths (64 records, 32 slots) inherited from a LOW_LATENCY design do not cover the ≈ 140 ms speaker path or Bluetooth | 256 records and 256 energy slots (1.37 s); oldest-record fallback counted as `clockMiss`/`energyMiss` and failing smoke on the speaker; `HKClock lat` logged per timestamp; routed device and output logged in the self-test; display lead per route class (speaker, wired, each BT address) with a calibration offer on a new BT device; T-SYNC and 5-min T-UND on BT at M3 and M8 (§2.3, §2.5, §3.1, §8.4, §8.6) |
| R5 | major | §2.5, §3.15, T2.7 | Lookahead-dispatched voices were wrong across PAUSE/SEEK/SET_PERF (attacks heard in the fade, notes lost on resume, state events early) | Pending voices return to IDLE without a fade; queued state events are dropped; PAUSE rewinds the cursor to the lowest event not yet in effect and skips already-started note-ons once; state events are applied only in their block; T2.7 extended (1–255 frames before an onset) (§2.5, §3.15, §7.2 WP2) |
| R6 | major | §5.2, §5.7, T5.7 | `ExposureSampler` used a nominal frame span: hammers pinned through a pause, strikes dropped or doubled at irregular spacing | The span is the actual `t − t_prev` (clamped 0–100 ms), 0 when paused, after a reseed and on the first frame; windows are a watermark so each contact is drawn exactly once; T5.7 adds the pause-after-contact and 16/33/50 ms cases (§2.5, §5.7) |
| R7 | major | §5.7, §4.3, §3.7 | Repeated notes: the isolated-stroke lead let note j+1 take over the key before note j struck or was released | The lead is bounded by the previous note on the key (`on_j − max(on_{j−1} + c·r, off_{j−1} + partialReturn·r)`, floor 12 ms·r but never before `off_{j−1}`); the stroke is compressed from d0 to hit at `on`; below `repeatMinMs` the fastest physical repetition is drawn; the audio keeps the MIDI rate (stated); T5.10 on REPEAT_15 at v20/v64/v110, all instruments, sampler off (§3.7, §3.9, §5.7) |
| R8 | major | §8.6, §1.4, §5.7 | The sync measurement was phase-locked to the frame rate and the exposure window one-sided (up to +25 ms bias at 20 fps) | Centred exposure windows; the disc flashes from the exposure (`pose.flash`), not `strikeAge < 33 ms`; clicks 600 ms plus a pseudo-random 0–33 ms, 100 events (≥ 40 used); T-SYNC at Q0 and Q2 with \|median\| ≤ 20 ms each (§2.5, §4.5, §5.7, §8.6) |
| R9 | minor | §2.3, §2.5, T2.1 | Song time in rounded Long µs drifted 62.5 µs/s | The engine keeps song frames in 32.32 fixed point (ΔS = 256·rateFixed exactly) and converts to µs only when publishing; T2.1 adds an onset 10 min in at r = 1, 0.95, 1.37 (§2.5, §7.2 WP2) |
| R10 | minor | §2.5, T2.6 | Onset alignment ignored the playback rate (up to 24 frames early at +5 semitones) | `onsetOut = round(onsetFrame / rate)` per (stop, layer, key) in the KeyMap; start delay `k − onsetOut`; T2.1/T2.6 at rates 2^(±2/12) and 2^(5/12) (§2.3, §3.5, §7.2) |
| R11 | minor | §5.7, §4.3, §3.10 | The drawn damper landed before the audio damped; lag and 4′ stagger were baked in song time so they scaled with 1/r | `EV_KEY_UP` at `offUs`; the Sequencer adds `damperLagMs·48` output frames and starts the 4′ `staggerMs·48` frames early, independent of r; `damperLagMs` is derived from the key-return curve in `PhysicalCurves`; the drawn damper touches exactly at `off + damperLagMs·r`; tests at r = 0.5, 1, 1.5 (±3 ms) (§2.3, §2.5, §3.10, §4.3, §5.7) |
| R12 | minor | §2.6 step 6 | Registration reached GL immediately but audio 100–150 ms later | Registration goes only through the CommandRing (`Cmd.REGISTRATION`), is published in `CoreClockState` → `ClockSample.registration` → `VisTime`; the evaluator reads it (§2.3, §2.6, §3.10) |
| R13 | minor | §2.6 steps 3, 5, §5.2 | `sameSongUs` was undefined (double strikes on a switch); keys snapped to rest while the old piece was still audible | `setPerformance(startUs = −1)` resumes at the engine's own frozen position; the GL keeps two Performance slots keyed by generation and draws the one being heard (§2.3, §2.6, §5.2) |
| R14 | minor | §3.12, §2.6 step 8 | World-locked pan lagged the head by the whole polling + ring + output chain | GazeCamera's sensor callback writes yaw and ω to a `HeadPose` atomic; HKAudio reads it every block and predicts by the measured output latency (ω·lat clamped ±15°, 20 ms smoothing); `Cmd.HEAD_YAW` and main-thread polling removed (§2.3, §2.6, §3.12) |
| R15 | minor | §5.1, §5.2 | A stale `frameTimeNanos` added jitter | `n = max(frameTimeNanos, nanoTime()) + lead`; `late` on the debug line; frames with late > 8 ms counted in T-FPS (§2.5, §5.2, §8.4) |
| R16 | minor | §2.3 PRE_ROLL, §5.7 | 300 ms pre-roll too short at fast tempos (362 ms needed) | `PRE_ROLL_US = 400_000`; the HUD subtracts it; T5.11 at r = 1.5 (§2.3, §2.5, §7.2 WP5) |
| R17 | minor | §8.4 T-ALIGN, §8.6, M1 | T-ALIGN measured block-granular model energy; the drift line was undefined and would fail on the 47,931 Hz FastMixer | T-ALIGN detects the onset in the rendered master buffer through `EngineCoreApi.debugOnset` against `thrFrame`; drift = per-pair residual against a running `fs_fit`; gate p99 \|eᵢ\| ≤ 1 ms and `fs_fit` within 0.5% (§2.3, §7.4, §8.4, §8.6) |
| R18 | major | §3.11, §3.8, T2.8, T3.1 | Each comb was fed by its own voice: +45 dB peak gain re-blooms every held note | Comb k hears mix − key k's own voices (per-key self rows in VoicePool); una corda is the only self-feed, calibrated alone (≈ −48 dB); a fourth calibration target (C3 alone, 10 s, ≥ 30 dB below, monotone envelope) (§3.6, §3.11) |
| R19 | major | §3.16, §3.14, T-CPU | The budget assumed a fixed 2.0 GHz; the governor runs 1.1–1.5 GHz, so wall-time lines and self-protection misfire | Self-protection from buffer headroom (< 1,536 queued frames for 10 s); T-CPU logs frequency and normalises CPU time to 2.0 GHz; p99 line 3.2 ms at the frequency run; the table re-derived in cycles at 1.5 GHz (§3.1, §3.14, §3.16, §8.4) |
| R20 | major | §3.3, §3.4, T-PF, T-UND | Voicing during playback floods eMMC writeback and starves prefetch | Voicing only when nothing plays (title card, pauses, between movements, display asleep while paused); writes in ≤ 4 MiB chunks with `force()` and a sleep; T-UND-FIRSTRUN added (§3.3, §8.4) |
| R21 | major | §3.4, T-START, T-MEM | Whole-kit `load()` of 755 MiB on every open (3–4 s, RSS ≈ 1 GB on a low-RAM device) | Removed for the pianos; head pre-read + score-driven prefetch; preload only for a kit ≤ 96 MiB when `!lowMemory` and `availMem − mapped > 4 × threshold`; RSS ≤ 450 MiB in T-MEM (§3.4, §3.17, §8.4) |
| R22 | major | §3.14, §3.16 | 8 kill slots: the 9th steal in a block used a clicking in-block ramp; the budget omitted the kill slots | Steal ahead at dispatch (1,024-frame lookahead); kill slots = cap/2; a pending list allocates the overflow in the next block; the in-block ramp removed; T2.2 max step 0.02 FS over the whole storm; the budget counts the stealing block (§2.3, §3.14, §3.16, §7.2 WP2) |
| R23 | major | §3.14, T-CPU | A 64-voice cap with noises in the pool would steal constantly in pedalled Beethoven | Offline voice-demand simulation in `PerformanceBuilder` (p99/max in `PerfInfo`, catalogue report); the Q0 cap set by `EngineBench` (clamp to 64–128; factor 0.25 rather than the proposed 0.30 so the cap-bound storm stays within the 42% normalised line); releases and pedal noises in their own 12-slot pool; steals logged on op. 53 iii and op. 57 iii; L-7 listens (§3.6, §3.14, §5.11, §8.4) |
| R24 | major | §3.3, §3.2, §6.5 | One Opus file and one extractor per region: setup alone costs 2.4–4.9 s; 60× real time was a guess | One Ogg Opus stream per decode unit (18 for HD) with region offsets in `map.json`; `-frame_duration 60`; one reused codec (stop/configure/start); two decoders for the first playable set while idle; a decode bench at M1; `media.swcodec` in `cpu.sh`; a T-THERM from a cleared cache (§3.2, §3.3, §6.5, §8.4, §8.5) |
| R25 | major | §3.3, T-RESUME | `.ready` could mark units whose pages never reached the eMMC (f2fs `nobarrier`) | `force()` before the ready bit, `.ready` written temp + `force` + rename; a CRC32 per unit; verification of the newest units when `.ok` is missing or `BOOT_COUNT` changed; T-RESUME with `adb reboot` (§3.3, §8.4) |
| R26 | minor | §3.4 | Touching mapped pages from HKPrefetch stalls ART suspend points for every thread | `SampleReader.prefetch` = positional `FileChannel.read` in 64 KiB chunks into one reused direct buffer (Native state); the mapping only on HKAudio's read path (§2.3, §3.4) |
| R27 | minor | §3.11, §3.16 | One-comb-at-a-time kernel on an in-order A55 is ≈ 30–40 cycles per comb-frame; fixed step-down order | 4-way interleaved kernel with state in locals; one packed power-of-two delay array (≈ 250 KiB); `EngineBench` reports ns per stage and the step-down order is chosen by measured ns saved per quality lost (§3.11, §3.16) |
| R28 | minor | §2.1 rule 5 | Several formulas needed libm per block (comb gain, spectral cutoff, env → linear, release gain, pan) | The tables are specified: comb gain and spectral coefficient per (key, D step), env byte → gain/power, `vel^0.7`, `exp(−age/3)`, a sine table, dB ↔ linear (§2.1, §3.6, §3.7, §3.9, §3.11, §3.12) |
| R29 | minor | §3.12, T3.3 | Early-reflection delays and the FDN pre-delay jumped on a listener change | Two 12-tap sets crossfaded over the 500 ms glide; the FDN input crossfades between pre-delay taps; T3.3 listener switch under noise, no step above −60 dBFS (§3.12, §7.2 WP3) |
| R30 | minor | §3.11, T3.1 | SEND calibrated on harmonic synthetic voices is wrong on real, stretched partials | WP11 exports real decoded regions (C3/C4/C5 at v10 and v13, the una corda C4) as test resources; targets met on them; re-checked on the glasses at M2 and M5 before L-7 (§3.11, §6.5 step 13, §6.8) |
| R31 | minor | §2.3, T2.10 | Allocation traps: `drain(…, core)` did not type-check, cursors bound per curve, Runnable posts, HotSpot escape analysis hides allocations | `EngineCoreApi : CommandHandler`; `PedalCurve.Cursor.bind()`; preallocated Runnables with `@Volatile` payloads; JVM tests with `-XX:-DoEscapeAnalysis -XX:-EliminateAllocations`; an on-device HKAudio allocation count around `storm64` (§2.1, §2.3, §7.1, §7.2 WP2) |
| R32 | minor | §3.1 Idle | Writing zeros forever while paused keeps the audio path and amplifier awake | After 10 s idle (paused, no voices, tail below −90 dBFS) `track.pause()` and park; un-park goes through `clock.reset()` + prime + estimate (reconciled with R1) (§2.3, §3.1) |
| R33 | minor | §6.5 steps 7, 9 | Opus overshoot would fail the ≤ −0.5 dBFS check with −1 dBFS normalisation | Normalise to −3 dBFS; a failing region is re-normalised by its overshoot + 0.5 dB and re-encoded; the check stays (§3.2, §6.5) |
| R34 | minor | §2.3 KitService.release | Explicit unmapping can segfault; GC unmapping made `release` undefined | `release` stops voicing/prefetch and drops references only after `AudioStats.bankGeneration` and the prefetcher show a newer bank; explicit unmapping forbidden; pages clean, counted in RSS until GC (§2.3, §3.2) |
| R35 | major | §1.7, §4.8, T-IMPORT | adb-pushed files are shell-owned with host modes: subfolders unreadable, rejections cannot be moved, a pre-launch push locks the app out | `tools/device/push_scores.sh` (launch first, push, `chmod -R a+rwX`, rescan) is the only documented way; pushed files are read-only input copied into `filesDir/imports/<sha1>.mid`; rejections recorded by (path, size, mtime); `Permission denied: run push_scores.sh`; `st_uid` check on launch; T-IMPORT adds a 0600 file and a subfolder (§1.7, §4.8, §8.4) |
| R36 | major | §5.11, §1.10 | The governor was tied to `onResume`/`onPause`, so thermal protection was off with the display off | The governor (and the CONTROL receiver) live as long as the engine; only render-side reactions wait for resume; a display-off `faketemp` test (§2.2, §5.11, §7.2 WP0) |
| R37 | major | §1.10, §9.2, §1.3 | `onStop` kept playing when the user left the app; `onResume` did not restart a stopped engine; no Back handling | Keep playing only when `!isInteractive`; otherwise fade, pause, save; `KEYCODE_BACK` mapped (double-tap in menus, leave at the root); `onResume` calls `audio.start()` idempotently and re-sends bank, key map and Performance; T-LEAVE and a smoke step (§1.3, §1.10, §8.3, §8.4) |
| R38 | major | §7.2 WP0 manifest, §2.6 | No `configChanges`: a configuration change built a second engine; no `allowBackup=false` | The full `configChanges` list; the engine as process singletons in `HammerklavierApp`; stop on `onDestroy` when finishing; the cache in `noBackupFilesDir/pcm`; `allowBackup="false"` + MathCosmos backup rules (§1.10, §2.2, §3.2, §7.2 WP0) |
| R39 | major | §2.6 step 5, §2.3 RenderControl, §5.1 | Audio resumed only in a GL callback that never fires in display rest; GL uploads queued while paused silently no-op | Audio switches bank and Performance on its own; the renderer keeps a desired state reconciled in `onDrawFrame`; `queueEvent` runnables never make GL calls; after a rest the swap is instant; T-Q3SWITCH (§2.1 rule 6, §2.6, §5.1, §7.2 WP6) |
| R40 | major | §5.1, §3.17 | Context loss could not be recovered (arrays freed, handles dead) | A GL generation counter; handles discarded without `glDelete`; programs recompiled; meshes and texture RGBA kept resident (≤ 8 + 6 MiB, budgeted); `GlyphBoard.release()` without GL; T-GLRESET with `--ez glreset` (§3.17, §5.1, §7.2 WP6) |
| R41 | major | §5.1, §5.11, §8.5 | "MSAA only at Q0" cannot be implemented in ES 2.0 on a GLSurfaceView | MSAA fixed for the session (setting read at GL view creation, applies next launch); removed from the ladder and the soak's tightening order (the default may be flipped there instead); `EGL_SAMPLES` logged (§2.3, §5.1, §5.11, §8.5) |
| R42 | major | §3.1, §2.5, §3.19 | No handling of `write` < 0 / `DEAD_OBJECT`; route detection by device-added callbacks | `TrackSupervisor` rebuilds the track (≤ 3 tries in 10 s), keeps `framesAccepted`, records `trackBaseFrame`, resets the clock (bumping `session`); routes from `addOnRoutingChangedListener` + `getRoutedDevice()`; T-CLOCK with `killall audioserver` or a BT toggle (§2.3, §2.5, §3.1, §3.19, §8.4) |
| R43 | major | §8.5, §5.11, §9.1 | The soak ran plugged in (battery barely discharging) at normal brightness; the display-off leg ended with the movement | In-app `SoakRecorder` CSV started by CONTROL; the soak runs unplugged and is read back with `adb pull` + uptime; one run at maximum brightness; a window brightness cap in the ladder (0.6 at Q2); the display-off leg uses a > 20 min playlist confirmed from the CSV (§5.11, §8.5) |
| R44 | major | §3.3, T-CPU, T-THERM | Opus decoding runs in `media.swcodec`, invisible to the app's CPU accounting; a codec per region | `media.swcodec` added to `cpu.sh` and the T-CPU line (app + swcodec ≤ 120%); duty cycle by wall time; one codec reused per kit across unit streams; Concentus documented as a fallback (needs its own approval). The in-app CSV cannot read another process's `/proc`, which is acceptable because no voicing runs during playback (§3.3, §8.4, §8.5) |
| R45 | major | §3.3, §3.19, T-RESUME | = R25 | As R25; the CRC table lives in `.ready` with the stored `BOOT_COUNT` |
| R46 | major | §2.5, §1.4, §8.6 | One global lead calibrated on the speaker while headphones (Bluetooth) are recommended | = R4: per-route lead keyed on the routed device (BT per address); the card calibrates the current route; T-SYNC on BT at M3 with `lat` vs buffer size logged; if BT timestamps exclude link latency the BT default becomes the measured offset (§1.4, §8.6) |
| R47 | major | §1.10, T-UND, T-PF, M1 | With the display asleep the app is top-sleeping (low IO priority, 40 ms slack); tests ran with the display on | T-UND and T-PF also with the display asleep (in-app CSV); HKPrefetch scheduled by song time with 100 ms slack; a `mediaPlayback` foreground service is built and enabled only if those tests fail (`HK.USE_FG_SERVICE`), started while resumed, stopped on pause or end (§3.1, §3.4, §8.4) |
| R48 | minor | §3.1 Idle, §1.10 | = R32 (5–10 s idle, the 2 s standby never reached) | As R32 (10 s) |
| R49 | minor | §1.6 token | The token was injected into the open page, so it protected nothing; the library was public | The page is served without a token; the user types the 8-character token shown on the glasses once (localStorage); every `/api/*` needs it; rotation from the Import panel; 429 after 10 bad tokens (§1.4, §1.6, §7.2 WP9) |
| R50 | minor | §1.6 `/api/upload` | NanoHTTPD multipart decodes names as US-ASCII and buffers the whole body before a 413 | Raw `application/octet-stream` POST per file with `x-hk-name` (UTF-8, URI-encoded); `content-length` checked before reading; the handler reads the body itself; T9.4 adds a non-ASCII name (§1.6, §4.8) |
| R51 | minor | §8.2 CONTROL | The exported receiver let any app force quality, temperature or playback; dead with the display off | Registered with the DUMP permission (+ `RECEIVER_EXPORTED` on 33+), shell-only; engine lifetime (§2.2, §8.2) |
| R52 | minor | §1.4, §1.6 | The URL did not follow network changes; "works with the display off" was over-promised | `NetworkCallback` refreshes the URL and HUD; the guarantee is stated as "while music plays or the display is on" (no wakelock) (§1.6) |
| R53 | minor | §1.3, §1.4 | No MediaSession: headset buttons went to another app, which then took focus | `system/MediaButtons.kt`: a framework `MediaSession` active while a movement is loaded, play/pause/next/previous → UiActions, metadata; released with the engine (§1.3, §2.2) |
| R54 | minor | §3.17, KitService.release | = R34 (no deterministic unmap) | As R34: mappings are process-lifetime (≈ 1.3 GiB virtual, harmless on arm64) |
| R55 | minor | §7.2 M0, §8.1, §8.8 | Host-timed double taps, keyevent-only click tests, ignored `am start` extras, unverified installs, `device_wearing` left set | Double/triple via `--es gesture`; physical double-tap and firm-click dedup checks at M0; `am start -S`; `run.sh` compares md5 of the installed APK; every bench script traps `device_wearing 0` (§7.2 WP0, §8.1, §8.8) |
| R56 | minor | §1.8, §5.3 | HUD pills overlapped the pedal inset in Player follow | In Player follow the pills move to the top right under the tuning line; the Follow framing is in WP10's legibility screencaps (§1.8, §5.3, §7.2 WP10) |
| R57 | major | §3.5, §6.5, T4.2, M7 | The recording's pitch standard leaked into the stretch curve, so A415/A440 could be a semitone off; roots chosen by nominal key | The pipeline ships the stretch **shape** (`fit − fit(69)`) and each region's measured pitch; `targetCents = 100k + c(k) + shape[k]`, the root minimising \|target − native\|, `rate` and `f0Hz` from target; T4.2 and M7 rewritten as properties at A440 and A415 for fixtures recorded at either. **Resolved differently in one detail:** `nativeCents` is the region's raw measured pitch, not "after tune_ret" (with tune_ret folded in, a sample would never be retuned); Salamander's `tune_ret` only competes as a shape candidate (R71) (§3.5, §6.5, §7.2 WP4, §7.4) |
| R58 | major | §3.6, §3.15, §5.7 | `levelDb` counted damp and `gainDb` twice and included 1/32768 (every voice culled at once); energy lanes were power, not amplitude | = R79/R80: `envDb` = normalised region dBFS; `LEVEL` excludes the short scale; `levelDb = envDb(absFrame/480) + 20·log10(LEVEL)`; lanes = √(Σ voice mean-square + comb mean-square); tests: levelDb vs output RMS ±1 dB, v80 lane vs RMS ±1 dB, comb and voice at equal RMS give equal lanes (§2.3, §3.6, §3.15, §7.2 WP2) |
| R59 | major | §1.1, §5.4, §3.5, About | The drawn FF–f‴ instrument was claimed to be the sampled one; keys 85–89 shifted up to +5 semitones | Called "a Flemish-style single manual" everywhere; the 8′ on keys 86–89 borrows the 4′ roots at keys 74/76 (≤ 1 semitone) with seam gain and brightness trims fitted at key 84; only the 4′ on 85–89 shifts further; T4.2: no 8′ key beyond ±1 semitone (§0, §1.1, §3.5, §5.4, §6.5) |
| R60 | major | §1.1, §5.4 | The 80 cm case was narrower than its own 0.818 m keyboard | Case 228 × 93 × 26 cm (keyboard + two 40 mm cheek blocks + 12 mm sides); T7.6 checks width ≥ keyboard + 2 × (cheek + side); the harpsichord Player framing re-checked (83% of the view width) (§1.1, §5.4, §5.6) |
| R61 | major | §3.7, §6.5, §3.9 | Damper T60 fitted from the room tail; tail-carrying releases doubled the damped tail | Fit only the early decay (10–15 dB after the landing), clamp 0.5–2× the formula or fail with the knee reported; kits with `releaseCarriesTail` hand off (level-matched release, sustain crossfaded out over 30 ms) at full damping; T60d only for partial damping (§3.7, §3.9, §6.5) |
| R62 | major | §3.11, T3.1, §6.5 | Harmonic combs ring tens of cents off real inharmonic partials; autocorrelation f0 is biased | Partial-series fit of f0 and B in the pipeline (`inharmB[128]`); two first-order dispersion allpasses per comb for keys ≤ 59 at Q0–Q1; at Q2 (or if a residual remains) the loop low-pass is capped at `n_max·f0`; T3.1 checks peaks against the fitted series (§3.11, §6.5) |
| R63 | major | §5.4 upright | Dampers above the strike line described an overdamper action | Underdamper: the contact line 50 mm below the strike line (1.03 m), levers between the hammer shanks and the strings, lifted by the damper spoon, swinging toward the player; Overhead shows them below and behind the hammers; the correction to the visual research recorded (§5.4, §5.7, §5.8) |
| R64 | major | §5.6 placements | The upright's case went 21 cm through the N wall | Origin (−2.20, 0, −2.95) (13 cm gap); the upright Hall camera retargeted; T8.9 checks every case corner of every placement against the walls and fixture footprints (added to `VenueGeometry`) (§2.3, §5.6, §7.2 WP8) |
| R65 | minor | §3.7, §5.7 harpsichord | Key and jack returned faster than gravity; audio damping ≈ 30 ms early | A quadratic, gravity-like 55 ms return; the instants derived once in `HarpsiTiming` and shared with the audio: 8′ quill pass 30.1 ms (tongue flick, release start), 4′ 41.4 ms, cloth touch 47.6 ms (recomputed for the 6 mm dip and 1.0 jack ratio of R75, instead of the proposed 27/40 ms); T5.4 asserts the release starts on the flick frame (§2.3, §3.7, §5.7) |
| R66 | minor | §5.7 key release | = R11 (easeOut landed the damper early and started the key at top speed) | `KeyReturn.dip` uses smoothstep (landing at x = 0.5247: 18.4 / 26.2 ms), `damperLagMs` derived from it in `PhysicalCurves`, T5.2 checks ±1 ms (§2.3, §5.7) |
| R67 | minor | §5.4, T7.6 | "70 dampers" contradicted the bank's lastDamper 88 (68 dampers) | Dampers = `lastDamper − 20` from the bank (68 for Salamander); `Instruments.create` takes `lastDamper`; T7.6 asserts the formula (§2.3, §5.3, §5.4) |
| R68 | minor | §3.7, §6.5 upright | lastDamper from the SFZ's 10 s releases gives 100 (E7) | Undamped keys decided by the release samples' measured decay (> 1 s T60 = undamped); disagreements with the SFZ logged; inconclusive → clamp 86–92; the upright's dampers drawn from the result (§1.1, §6.5) |
| R69 | minor | §3.5 velocity | The 0.39 dB/step trim mixed unrelated quantities; standard and HD velRefs differed | velRef is the sample's own split midpoint (HD midpoints in both grand kits; upright 20/62/105); a continuous piecewise-linear level curve through the restored layer-centre loudnesses gives each velocity's trim; slopes reported for L-3 (§3.5, §6.5) |
| R70 | minor | §3.5 XFADE, T4.2 | Equal power bulges +3 dB for correlated layers | Correlation measured per boundary; equal gain above 0.5, else equal power (`xfadeLaw`); T4.2 checks the summed level across each zone (§3.5, §6.5, §6.6) |
| R71 | minor | §6.5 step 4 | `tune_ret` could flatten or invert the stretch unnoticed | A Railsback assertion on the shape; the report compares raw, `tune_nat` and `tune_ret` fits; raw is used unless it fails, then `tune_nat`; L-3 listens (§6.5, §8.7) |
| R72 | minor | §3.12 | Two rooms on the `_Far` harpsichord against the dry Salamander | `embeddedRoomDb`/`embeddedEdtS` measured per kit; `RoomAcoustics` reduces the reverb and ER sends so embedded + simulated meets the DRR target (§2.3, §3.12, §6.5) |
| R73 | minor | §3.8, §4.3 sostenuto | Latching every damper at sustain > 0.33 caught tabs the blade cannot reach | All dampers latch only at sustain ≥ 0.55 (`PedalMotion.CLEAR`); T1.3 and `SOSTENUTO` include a press at 0.4 (§3.8, §4.3, §4.5) |
| R74 | minor | §3.8, §3.11 una corda | The comb aftersound was fed on single-strung keys | `UNA_CORDA_SEND` only on keys with ≥ 2 strings (`InstrumentProfile.stringsPerKey`, shared with `StringsMesh`); a smaller soft-bus shelf on single strings (§2.3, §3.8, §3.11) |
| R75 | minor | §2.3 HarpsiTiming, §3.7 | DIP 7 mm and jack 1.25× contradicted the research; the 4′ T60 law and the endpoints were wrong | DIP 6.0 mm, jack ratio 1.0 (stagger unchanged, depth 0.70 at the pluck); free T60 fitted per stop from the sustain samples, with defaults ∝ f^−0.45 for both choirs (20 s → 4.2 s on the 8′, matching the research endpoints) (§2.3, §3.7, §5.4, §5.7, §6.5) |
| R76 | blocker | §7.1 rule 3, §8.1 | Worktrees could neither build (no `local.properties`, no `ANDROID_HOME`) nor deploy their own code (absolute path in `run.sh`) | `tools/wt.sh` writes `local.properties`; `tools/env.sh` exports `ANDROID_HOME` and is sourced everywhere; every script uses `git rev-parse --show-toplevel`; branch and commit in `BuildConfig` and the self-test header (§7.1, §8.1, §8.3) |
| R77 | major | §0 bet 7, §7.2, §8.4, §8.5 | One pair of glasses and a 16 GB Mac oversubscribed by 11 agents | `tools/device/lock.sh` (python `fcntl`, since the Mac has no `flock`) around every device script, restoring `device_wearing`; integrator-only soaks and thermal/CPU tests; Gradle at −Xmx1536m, Kotlin daemon 1 GiB, 2 workers, 3 build slots; pure code in a JVM-only `:core` module tested with `./gradlew :core:test` (§2.2, §7.1) |
| R78 | blocker | §2.2/§2.3 stubs, §7.2 | Stubs were named but not specified; cross-WP tests needed another WP's code | `SineCore`, `PerfFixtures`, `SyntheticSpecs`, `KeyMapFixtures.forSineBank`, `StubScoreCompiler.synthetic` (all nine kinds) and a behaviour paragraph per stub; WP2 no longer uses `map_fixture`; WP1's `SyntheticScores` must match `PerfFixtures` (T1.8) (§2.3, §7.2 WP1/WP2) |
| R79 | blocker | §3.6, §2.3 Bank, §6.5 | Voice level units contradicted each other (silence at M1) | Frozen in the contract: `envDb` without `gainDb`; `KeyMap.gain` = 10^(gainDb/20) × seam × fallback; `levelDb = envDb(region, absFrame/480) + 20·log10(LEVEL)` with `absFrame = winStart + (pos ushr 32)`; the 2⁻¹⁵ scale only in the ramped per-sample gain; WP2 test levelDb vs rendered RMS ±1 dB (§2.3, §3.6) |
| R80 | blocker | §2.3 EnergyRing, §3.15, §3.11, §5.7 | Energy lane units undefined and mismatched between WP2, WP3 and WP5 | Contract: a lane is linear RMS re full scale over one block; producers add mean-square (combs too); one `sqrt` per lane in `EngineCore`; cross-WP golden test: −12 dBFS C4 → lane 39 = 0.25 ± 10%, StringVisual amp ≈ 0.8 (§2.3, §3.11, §3.15, §7.2 WP2) |
| R81 | blocker | §2.3 BakedMesh, §5.3, §5.8 | No program or draw group on meshes; no material → program table; no merge rule | `program: ProgramId` and `drawSlot` on `BakedMesh`; `MaterialTable` in the contract; `SceneAssembler` merges on (program, material, skin, texture, levelMask, viewMask, clipped, drawSlot); T7.8/T8.8 check merge keys per framing against the §5.3 counts (§2.3, §5.3, §5.8) |
| R82 | blocker | §2.3 MeshBuilder, §5.1, §5.4 | MeshBuilder conventions unspecified (winding, UV, ribbon encoding, colour lift); no raw vertex API; no way to see geometry before integration | Conventions frozen in `contract/Scene.kt` (CCW outward, UV per primitive and key-local mm, ribbon nrm = tangent and uv = (side, half-width), un-lifted sRGB, unsigned indices); public `vertex()`/`tri()`; `testutil/MeshRaster` PNG review; MeshBuilderTest checks winding vs normals and indices above 32,767; the builder itself moves to WP7 (R87) (§2.3, §5.4, §7.2 WP7) |
| R83 | major | §2.3 SkinParams, §5.8, §5.3 | ACTION_SET layout, pedal indices, hammer rail, sostenuto rail, upright damper travel and SHIFT_X scope undefined | The 34-vec4 block defined (13 slot headers x/dim/key, 13 × 6 part angles, uv = (slot, part)); `SkinParams[ACTION_SET]` per part type; pedal indices 0/1/2; `HAMMER_RAIL` and `SOSTENUTO_ROT` kinds; `DAMPER_LIFT` with an axis; `uShiftX` and `uRailM` in every skinned program with the parts they move; paired pack/decode tests (§2.3, §5.8, §7.2 WP6/WP7) |
| R84 | major | §2.3 Dsp, §3.12, §5.6 | Yaw sign, azimuth zero, camera frame, clipX/lidLift ownership and FlameField's eye frame undefined | Contract conventions (yaw about +y, 0 = north, + toward east; same sign as GazeCamera); `Conventions.forwardYaw`; `CameraPose.roomFrame`; anchors set clipX and lidLift, the director only springs and dips; WP6 converts the eye to the room frame; a contract test: grand Player source azimuth ≈ 0, treble to the right (§2.3, §5.6) |
| R85 | major | §3.11, §3.15, T2.8 | Two WPs owned the comb send | `gate` is 0..1 (1 − D) plus a separate `softFeed` flag from WP2; WP3 owns `SEND` and `UNA_CORDA_SEND`; T2.8 tests gates, T3.1 dB values (§2.3, §3.11) |
| R86 | major | §2.3 EngineCoreApi, §3.1, §3.15 | No ended, overload or idle signal from the engine | `CoreClockState.endedGeneration` and `idle` (plus `registration`); overload is now AudioOutput's own headroom decision (R19); WP2 tests EV_END → endedGeneration after the tails; WP4 tests `onEnded` fires exactly once (§2.3, §3.1, §3.15) |
| R87 | major | §7.2 WP0, §7.3, §2.6 | WP0 was a serial bottleneck and the orchestration had no tests | `contracts-v1` in hours (signatures and trivial stubs), `contracts-v1.1` with the primitives and M0; MeshBuilder moved to WP7 (WP0 ships box/quad/vertex/tri); a new **WP12** owns a pure `session/SessionController` (playlists, generations, instrument switch, listener → room, status collection, facts, resume) with JVM tests; `AppController` is a thin adapter (§2.2, §2.6, §7.2, §7.3) |
| R88 | major | §2.3 preamble, §7.1 rule 2 | "Signatures only grow" is unsafe in Kotlin across branches | Growth rules: default bodies for new interface members, new constructor parameters last with defaults, named arguments for contract classes; `tools/ci.sh --contracts` compiles every open branch against main's contracts (§2.3, §7.1) |
| R89 | major | §2.3 RenderControl, §8.2, §8.3 | Missing setters (registration, title, stage hiding, overrides) and diagnostics | Added `setTitle`, `setStageHidden`, `setOverrides(RenderOverrides(ipdScale, vFovDeg))` and `diagnostics()` (also on `KitService` and `AudioControl`). **Resolved differently:** no `setRegistration` on RenderControl (registration travels with the clock, R12) and no runtime MSAA override (R41) (§2.3, §8.2, §8.3) |
| R90 | major | §2.3 UiAction.Play, §1.5 | Playlists need the shelf; Recently played had no data | `UiAction.Play(movementId, shelfId)`; `UiFacts.recent` (≤ 15) and `shelfId`, maintained by SessionController and saved in Settings (§1.5, §2.3, §4.9) |
| R91 | major | §2.3 Cmd.SYNC_TEST, §8.6 | An engine click generator would never flash the mechanics disc | `SYNC_TEST` removed from `Cmd` and `AudioControl`; the sync test is `SYNC_CLICK` through `setPerformance` plus `render.setSyncFlash(true)` (§2.3, §2.6 step 9) |
| R92 | major | §5.7, T5.2, §2.5, §4.3 | Rate semantics contradicted (T5.2 vs the × r rule); real-time lags baked into song time | T5.2 rewritten (real-time durations unchanged at rate 0.5, song-time halve); damper lag and 4′ stagger applied by the Sequencer in output frames; pedal ramps documented as song-time curves read identically by audio and visuals (§3.8, §4.3, §5.7, §7.2 WP5) |
| R93 | major | §6.6, §2.3 LoadedBank | The WP11 → WP4 `map.json` interface was an elided example; `readyMask` semantics and mutability unclear | A normative field table in §6.6 frozen as `docs/contracts/map-json.md` with the fixture; `readyMask` bit u = unit id u (62 releases, 63 pedals), `@Volatile` and informational only; the KeyMap snapshot is the only gate the engine uses (§2.3, §6.6) |
| R94 | major | §7.4 M2 | M2's gate needed the catalogue, the library and a room design that land later | `StubLibrary`/`LibraryServiceImpl` resolve `asset:` ids; WP11 ships a Start-here partial catalogue and WP9 a bundled-only `CatalogCodec` + `LibraryServiceImpl` at M2; `FixedRoom.PLAYER` and `StubVenue.GEOMETRY` in `contract/stub` (§2.3, §4.5, §7.4) |
| R95 | major | §2.2 purity, §5.4 textures | Pure scene packages had to return Canvas-drawn textures; `purity_dirs.txt` was ambiguous | **Resolved differently:** textures are pure `TextureRecipe`s painted through a pure `Painter2D` (`CanvasPainter` on the device, `AwtPainter` in tests) instead of an injected factory, so no scene constructor needs Android; purity is enforced by the JVM-only `:core` module plus a grep for fully qualified `android.`/`androidx.`/`java.awt` (§2.2, §2.3, §5.4) |
| R96 | minor | §2.3 frozen constructors | No HKLoader executor passed; agents would spawn threads | `HkGlView(ctx, loader, msaa)` and `KitManager(ctx, voicer, loader)`; one executor created by `HammerklavierApp` (§2.1, §2.3) |
| R97 | minor | §2.2, §1.8, T10.4 | User text produced by five WPs; priority direction unstated | `StatusCode`, `RejectReason`, `FallbackReason`, `PerfWarning` enums with arguments; producers emit codes; WP10's `UiText` maps codes to text and priority; lower number = higher priority (§1.8, §2.3, §7.2 WP10) |
| R98 | minor | §4.8, §2.3 LibraryService | Thread safety undefined across NanoHTTPD and HKLoader | One lock around every method; `index.json` written temp + rename; T9.2 upload during a rescan (§2.3, §4.8) |
| R99 | minor | §7.4 M1, §8.2, §4.5 | Test score names did not resolve (`test/chords_pedal`, mixed forms) | One name table: `synth:<kind>`, `test:<file>`, `asset:<path>`, movement ids; `chords_pedal` dropped (M1 uses `synth:pedalhalf`); WP1 checks each twin compiles to its SyntheticScore (§4.5, §7.4, §8.2) |
| R100 | minor | §2.3 EnergyRing, §3.1 | = R4 (32 slots too few for larger buffers) | 256 slots; misses counted in `RenderStats.energyMiss` and on the debug line |
| R101 | minor | §3.13, T2.9 | Master gain calibrated with pass-through DSP, invalid once WP3 merges | `LevelCalibrationTest` at M5 owned by WP12 with the real `DspFactory` and the Player design; T2.9 keeps relative checks only (§3.13, §7.2 WP12) |
| R102 | minor | §3.12, contract Surface | Multi-plane surface rows; no plane coordinates in `VenueGeometry`; fixture copies could diverge | A plane × material area table (§3.12); `erPlanes` in `VenueGeometry`; one shared constant `KonzertzimmerAcoustics.GEOMETRY` that WP8 returns and WP3 tests (§2.3, §3.12) |
| R103 | minor | §2.3 prepareKeyMap, ResonanceProcessor.apply | Retuning lacked BankInfo and profile; no glide | `prepareKeyMap(keyMap, info, profile)`; `apply(prepared, glideMs)` with a 200 ms retune glide (§2.3, §3.18) |
| R104 | minor | T4.2, §7.4 M7 | The −1.27 cent check held only in ET with zero stretch and a root-60 fixture | The worked example states ET, zero shape, roots 58/60/62; the M7 log checks key 69 and the properties of R57 (§6.6, §7.2 WP4, §7.4) |
| R105 | minor | §8.4 T-ALIGN | = R17 | As R17: the onset of the new voice in the rendered buffer via `debugOnset` |
| R106 | minor | §5.2 Gaze, §3.12 | The verbatim GazeCamera re-centres softly and clamps ±1.75 rad, contradicting the world-locked Hall | One permitted change: a `worldLocked` mode without soft re-centre, clamped ±60° / +45°, unit-tested (T6.5) (§2.2, §5.2) |
| R107 | minor | §2.5, seek APIs, §4.9 | Seek and position units (with or without pre-roll) unstated | Contract rule: every API carries song µs including the pre-roll; only WP10 text and companion JSON/query values are display time; SessionController and CompanionServer convert; T12.6 (§2.3, §1.6, §7.2 WP12) |
| R108 | minor | T9.1, §2.2 test resources | T9.1 needed a catalogue that exists only after M6; WP9 could not own fixtures | WP11 ships `catalog_fixture.json` on day 1; each WP owns `core/src/test/resources/wp<N>/` (§2.2, §6.8) |
| A1 | architect | §2.2 dependencies | `androidx.profileinstaller:1.4.1` is not in the Gradle cache (1.3.0, 1.3.1, 1.4.0 are) [M] | Use 1.4.0, so the build needs no download |
| A2 | architect | §7.1 | macOS has no `flock(1)` [M] | `lock.sh` uses `python3` `fcntl.flock` |
| A3 | architect | §2.2 `:core` | Applying the Kotlin JVM plugin could need a plugin-marker download | Apply `org.jetbrains.kotlin.jvm` by id without a version; it resolves from `kotlin-gradle-plugin` 2.0.21, already on the build classpath and in the cache [M] |
| A4 | architect | §1.10, §3.1 | R1's "never call `pause()`" conflicted with R32/R48's parking | Reconciled: `pause()` only to park; every un-park is a new clock session (reset + estimate) |
| A5 | architect | §3.7 harpsichord | With tail-carrying releases the harpsichord's audio "damper landing" and the drawn cloth needed one story | The release (handoff) starts at the 8′ quill pass (30.1 ms); the cloth is drawn touching at 47.6 ms; the pipeline reports each release's damping knee and adjusts its onset if it falls outside 40–60 ms after key-up (§3.7, §6.5) |
| A6 | architect | §0, §6.2 | The approval request had no single artefact | `docs/DOWNLOADS.md` holds the final manifest table and total shown to the user; the MIDI total is ≈ 5.6 MB (sum of the listed sizes), not 5.5 |
