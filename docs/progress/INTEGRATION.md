# Integration log (WP0 integrator)

Device: RayNeo X3 Pro `A06B4A96A733283` (sdk 32, 4 × Cortex-A55, Adreno 621). Every number below is from the
release build (`isDebuggable=false`, `cmd package compile -m speed -f`, md5 verified by `tools/device/run.sh`).

## M1 First sound (2026-09-22/23): PASS, tag `milestone-M1`

### Merged (full branches, `--no-ff`)
| Branch | Tip | Notes |
|---|---|---|
| `wp11-assets` | 3089244 | kits (grand, upright, harpsichord, stub) through LFS (33 objects), catalogue, test MIDIs, fixtures, pipeline |
| `wp1-midi` | e89bd82 | `midi.ScoreCompilerImpl` |
| `wp2-engine` | 437cf8b | `engine.EngineCore`, EngineBench |
| `wp3-dsp` | a678af9 | `dsp.DspFactory`, `dsp.RoomAcoustics` |
| `wp4-audio` | 46ab13e | `audio.AudioOutput`, `audio.KitManager`, DecoderProbe, supervisor, SynthBank; one conflict (`docs/requests/WP4.md`, both kept) |

### Wired (`Wiring.kt`, per docs/wiring/WP1–4, 11)
- `compiler = ScoreCompilerImpl()`; `engine = EngineCore(DspFactory.create(HK.SR), cursors, head, HK.SR)`;
  `audio = AudioOutput(app, engine, cursors, head, settings)`; `kits = KitManager(app, voicer, loader) { kit.standIn }`;
  `designer = RoomAcoustics`. Library, scenes, mechanics, UI, GL host, overlay: still stubs.
- The full DspSet is used (the M1 row says "limiter; others pass-through"; every wiring note uses the full set and
  EngineBench needs the real stages). No `RoomDesign` is posted yet (WP12).
- **Stand-in bank:** `kit.standIn` (default true until M2) makes every instrument open the WP11 stub kit
  (30 regions, voiced from its PCM cache); `--ez standin false` + restart opens the real kits.
- **Interim `Playback` driver** (app package, until WP12's SessionController): kit callbacks → key map on HKLoader →
  `audio.setBank`; `--es play synth:<n>|test:<n>|asset:<path>`; `--ez bench true [--ei benchsecs n]` (decode bench on
  HKVoicer + EngineBench, voice cap from its `q0Cap`); `--ez stats true`; every 10 s `HKAudio stats …`, `HKClock drift …`
  and `HKPerf hkaudio thread=…% meanMHz=… normalised=…%` (HKAudio utime+stime from /proc, normalised by the mean
  policy0 frequency from `time_in_state` deltas). `--ez debug true` puts one audio line on the overlay (StubUi):
  voices/cap, p50/p99, headroom, clock source (ts/est), clockMiss, underruns.
- DebugControl keys added: `play`, `bench`, `benchsecs`, `lowlatency`, `standin`, `stats`; `gcstats` logs the heap.
- `tools/device/smoke.sh M1` → `tools/device/smoke_m1.sh`.

### Fixed during integration (details in docs/contracts-changelog.md, M1 entry)
1. **HKLoader crash on the glasses:** `NoSuchMethodError MappedByteBuffer.duplicate()` (`:core` compiled against the
   JDK 17 API links covariant `java.nio` overrides ART lacks). Calls go through `Buffer`/`ByteBuffer`;
   `tools/check_nio_linkage.sh` (in ci.sh) scans `:core` bytecode.
2. **pedalMode disagreement WP1 ↔ WP11** (bach_847/850: a single CC64 = 0): NONE; pipeline and golden regenerated.
3. **AudioClock:** the track's first timestamp is off the line and blocked every later pair (tsAcc = 1 for a whole
   session); now anchor-only, rate-checked against the anchor while the window is empty, reseed after 4 rejections;
   clockMiss counts only overwritten records (not the ~300 ms before a new session's first record).
4. **Headroom:** measured ahead of the DAC timestamp (the NONE route drained the client buffer in 7,680-frame pulls);
   the guard also trips on underrun growth or render load > 92 % (the DAC measure hides an overrun).
5. **Paused engine never idle:** voices frozen by a pause counted as active, so it never parked and rendered silence
   at ~27 % CPU; `idle` now ignores frozen voices (and is false while the bench runs, else a bench from parked
   never stepped).
6. **Main-thread stall ≈ 1 s** when the bank arrived: `prepareBank` now runs on `HKPrepare`.
7. **T-GC:** Qualcomm `BoostFramework$ScrollOptimizer.setVsyncTime` allocates ~1.5 KB per vsync delivered to a
   Choreographer (simpleperf, debug build): ~92 KB/s from PerfProbe's per-frame hitch detector and StubGlHost's pacing.
   Both now use main-thread Handler ticks: ~28–37 KB/s remain. Request to WP6 (docs/requests/WP6.md).
8. **Track mode (deviation from §3.1, plan owner):** `PERFORMANCE_MODE_NONE` lands on the DEEP_BUFFER output here
   (7,680-frame pulls, lat ≈ 14,300 frames ≈ 300 ms, drift p99 0.85–2.1 ms → the drift line failed). The LOW_LATENCY
   request lands on the primary output's FastMixer with a 4,096-frame buffer (the plan's intended size):
   lat 5,208 frames (108 ms), drift p99 0.28–0.54 ms. `audio.lowLatency` now defaults to true (`--ez lowlatency false`
   restores NONE).

### Gate results (build 2ca1e5b, 2026-09-23 00:15–00:27, plugged in, `device_wearing=1`)
| Item | Result | Pass line |
|---|---|---|
| `tools/ci.sh` | PASS (core + app unit tests, purity, nio linkage, 70 pipeline tests, ledger, size: APK 85.8 MB ≤ 100 MB) | green |
| `tools/device/run.sh` | installed, md5 verified, speed-compiled | |
| `tools/device/smoke.sh M1` | **PASS** (14 checks plus the FAIL/crash/hitch scan; self-test pass=11 fail=0 skip=1 (companion); tornRead 10.5 M reads, 0 mismatches) | no FAIL |
| synth:scale / pedalhalf / storm64 | all sound on the stand-in bank; compile 0.6–4.2 ms / 2.6–4.7 ms / 385 ms (46,080 notes) | sound |
| Debug overlay | voices, p50/p99, headroom, clock source, misses, underruns in both eyes (docs/shots/m1_*_debug.png) | shown |
| Audio with display asleep | stats with voices > 0 and parked=false between sleep and wake (smoke); 5 min asleep run below | keeps playing |
| `--ez bench true` decode | 60.1 s of Opus in 1.27–1.31 s = **46–48× real time**, setup **60–80 ms** (`c2.android.opus.decoder`); 24× / 120 ms in the slow state (below) | recorded |
| `--ez bench true` EngineBench | q0Cap **64** (cap set: clamp floor); ns per unit at 1.8 GHz: voice Hermite 112–114, linear 66, copy 48, spectral +37–40, comb 54–57, comb no-dispersion 54–55, soft 48–52, room 1,161–1,240 /frame, master 389–393 /frame | recorded |
| T-UND 5 min display on + 5 min asleep | **0 underruns** (73 stats lines, all 0), rebuilds 0, headroom min after warm-up **4,253** frames | 0 |
| HKAudio on synth:scale | **14.2–14.6 %** normalised (thread 39.6–39.7 % at 718–738 MHz mean); 12.2–12.8 % on the NONE track | < 15 % |
| T-GC (5 min playback, display on) | **1 GC** (56 ms); heap used 7.8 → 5.8 MiB after it. Asleep 5 min: 0 GCs, used 6.0 → 16.5 MiB uncollected (allocated 11 MB ≈ 36 KB/s) | ≤ 2 GCs; heap flat |
| Drift line | p99 \|eᵢ\| **0.28–0.54 ms** (median 0.35) over 73 ten-second readings; fs_fit within **±0.0085 %** of 48 kHz | ≤ 1 ms; ≤ 0.5 % |
| T-CLOCK | fromTimestamp=true 1 s after every play and after un-park (idle park, then resume); clockMiss **0**; `killall audioserver` not permitted for the shell; Bluetooth toggle and speaker→wired not exercised (see open) | ≤ 1 s; clockMiss 0 |

### Open issues (carried)
- **T-CLOCK output loss / route change** not exercised on the glasses: `killall audioserver` is refused
  ("Operation not permitted"), toggling Bluetooth is a device setting for the user to do, and there is no wired
  output. The rebuild path is unit-tested (`deadObjectRebuildsTheTrackAtTheSamePosition`). Do at M3 with the BT headset.
- **HOME kills the app:** the RayNeo launcher (Mercury `BackgroundAppManager`) force-stops the app ≈ 1 s after HOME with
  the display on, playing or paused. The §8.3 clock cycle uses the idle park instead. WP12/M6 (T-LEAVE) must save the
  resume point in `onStop` immediately. Sleep keeps the app alive and playing.
- **Bimodal device speed:** in some sessions everything (including EngineBench's own fixed loop) runs ~2× slower at the
  same reported 1.8–2.0 GHz with all cores pinned (bench voice 221–226 ns vs 112–114; storm64 97.7 % of a core and
  underruns vs 42 % normalised). No thermal cooling state active; SoC 40–44 °C in both. Likely memory-side (L3/DDR
  memlat) scaling; not controllable by the app; perf counters are not permitted. Now the guard steps the voice cap
  down on underruns. T-CPU at M2 must record which state it ran in.
- **T-CPU budget (M2):** storm64 on the stand-in bank is 42–43 % normalised in the fast state (limit 42 %), p99
  5.6–8.5 ms (limit 3.2 ms); on the LOW_LATENCY track the headroom guard stepped the cap 64 → 56 during storm64
  (render load > 92 %), with 0 underruns. Combs cost ≈ 100 cycles per comb-frame against §3.16's 22 (WP3 request 9 answered);
  the room is ≈ 0.3 ms per block.
- **EngineBench frequency normalisation** reads `scaling_cur_freq` once; its 2 ms slices often run while the governor
  sits lower, so `nsVoice2`/`q0Cap` swing (q0Cap stayed 64). WP2: use `time_in_state` deltas over the run.
- **T-GC asleep:** no GC in 5 min but the used heap before a GC includes garbage; confirm live-heap flatness at M2 with
  a forced-GC reading. The ~28–37 KB/s that remain are unattributed (below the profiler's sampling floor).
- Plan owner: §3.1 track mode (item 8 above), the M1 full-DspSet wiring, WP3 requests 1–4, 8, 10, WP1's
  `RejectReason.MALFORMED` and fold-merge change (next contracts round).

## M2 Real grand (2026-09-23): NOT PASSED (partial), no tag

### Merged
- `wp9-library` (`--no-ff`, 5 commits: CatalogCodec, ImportStore/Rules, LibraryIndex, LibraryServiceImpl,
  CompanionServer). WP4 (decoder, cache, mmap, prefetch, DecoderProbe, KeyMap), WP11 (grand-hd, upright, harpsichord
  kits in LFS: 33 LFS objects, grand 54 MB; Start-here MIDIs; partial `catalog.json` with 67 works) and WP1/WP3 were
  already on main from M1.

### Wired
- `Wiring.library = LibraryServiceImpl(app, compiler)` (bundled-only; the companion server stays unwired until M6).
- `kit.standIn` now defaults to **false**: the grand opens `assets/instruments/grand` (grand-hd, 572 regions,
  18 units). `--ez standin true` restores the stub bank.
- Debug overlay shows `voicing N%` while the kit voices; `HKKit grand: voicing N%` logged every 10 %.
- New DebugControl keys: `--ei wavdump <s>` (captures the next s seconds of the rendered master output on HKAudio
  into a preallocated array → `files/wav/capture.wav`, 16-bit, and logs peak / RMS / 100 ms-window levels);
  `--ez align true` (T-ALIGN: polls `EngineCore.debugOnset` for 40 s and logs the frame errors).
- `KitManager` logs the CRC verification after a BOOT_COUNT change or an unclean exit.

### Fixed during integration
1. **T-ALIGN off by 48 frames:** the master limiter's 48-frame look-ahead was not in the onset hook's scheduled
   frame. `MasterProcessor.latencyFrames` (default 0, MasterChain = `Limiter.LOOKAHEAD`) and the hook adds it.
   After: 0 frames. The clock still ignores it: the sound is heard 1 ms after the clock's song time (WP2, open).
2. **Comb kernel on the A55:** the 4-way kernel spills on ART. A 2-way kernel (`ResonanceBank.kernelWidth = 2`),
   peak/energy from every 8th frame, one group write when both combs share a register group, no self term when
   both self gains are 0: `nsComb` 64 → **40–42 ns** per comb-frame (fast state, 2.0 GHz); 1-way 53 ns, 4-way 63 ns.
   Tests: `fourWayKernelEqualsScalarReference`, new `twoAndFourWayKernelsAgree` (≤ 1e-6).
3. **Headroom guard sheds combs first** (§3.16 "by measured value": 44 combs save ≈ 1.8 µs/frame, a cap − 8 step
   ≈ 0.9 µs): the first two steps are combs 88 → 44 → 22 (dispersion off), then cap − 8 down to 32
   (`HeadroomGuard.COMB_STEPS`, `Cmd.VOICE_CAP` carries the comb step in `f`).
4. `CatalogCodecTest.fullCatalogueHas70Works` expected 13 Start-here items; §4.8 skips Handel when absent, so
   12–13 (the catalogue has 12).

### Gate results (builds f43fed7-dirty → this commit, 2026-09-23 00:41–01:43, plugged in, `device_wearing=1`)
| Item | Result | Pass line |
|---|---|---|
| `tools/ci.sh` | PASS (351 core tests, app tests, purity, nio linkage, pipeline, ledger, size: APK 85.9 MB) | green |
| First-run voicing with progress | `pm clear`, launch: overlay `voicing 1%` (docs/shots/m2_voicing_debug.png), releases + pedals in 2.1–2.5 s on HKVoicer2, v10 playable, then 15 more units, `complete` | shown |
| BWV 846 (`asset:midi/krueger/bach/bach_846.mid`) | plays on grand-hd (1,284 notes); noise voices 1–5 (releases, pedal), combs 88 with pedal, 0 underruns, majflt 0; 20 s capture: **peak −2.9 dBFS, RMS −17.4 dBFS**, 100 ms windows −22.3…−13.6 dB, 0 clipped, DC −0.0001, L/R correlation 0.18 (key pan + room) | sounds, with release/pedal noise and room |
| Für Elise (`elise.mid`) | plays; 20 s capture: **peak −6.5 dBFS, RMS −20.3 dBFS**, per-second RMS −24…−19 dB, 0 clipped, first sound 0.15 s (pre-roll) | sounds |
| **T-DEC** | playable **9.2 s** (fast state) / 10.4 s (slow), complete **148 s** / 145 s; units 25–31× real time while the M1 bench decodes the same codec at 47× (fast) / 23× (slow); setup 40–126 ms | **FAIL** (≤ 8 s, ≤ 120 s) |
| T-ALIGN | `synth:scale` / `test:scale`: 0 frames after fix 1 (48 before); only 1–2 isolated onsets per run are measurable (the hook arms only when nothing sounds) | ±1 frame (pass, thin sample) |
| T-UND-FIRSTRUN | cleared data, BWV 846 at playable, then pedalled Moonlight i with 15 units unvoiced: **0 underruns**, majflt 0, no voicing during playback | 0 underruns (pass) |
| T-PF | display on: majflt 0, slow 0 throughout; display asleep (BWV 846, 3 min): 1 majflt / 1 slow read in the first 10 s after sleep, then 0; after a cold boot: 2 in the first 10 s after launch and 2 at the first note | 0 after 60 s; ≤ 3 in first 10 s (pass, marginal) |
| T-RESUME force-stop | resumed at the first missing unit (mask 0xc000000000000200 → pending 12,6,…) | pass |
| T-RESUME reboot | 1st run: rebooted mid-unit 7; relaunch mask kept units 0,3,6,9,12,15,62,63, voicing resumed at unit 7, complete, no CRC failure (verification not logged in that build). 2nd run with the new CRC log line: the glasses did not come back on USB after `adb reboot` (01:43) | partial |
| **T-CPU** storm64 | fast state, 4-way combs: **55–65 % normalised**, p99 5.3–6.2 ms, 0 underruns, head ≥ 4,573 after warm-up; 2-way combs run hit the slow state mid-run (compile 410 → 830 ms): 75–92 %, 184 underruns while the guard stepped to combs 22 / cap 40 | **FAIL** (≤ 42 %, p99 ≤ 3.2 ms) |
| T-CPU op. 106 iv | 17–31 % normalised with 88 combs; 15–17 % (p99 2.9–3.3 ms) with the guard at 22 combs | FAIL at Q0 (≤ 20 %) |
| Comb calibration, real kit | `CombCalibrationTest.realRegions` passes on the WP11 real decoded regions (same SHA-1s as the kit), JVM | pass (JVM) |
| Battery after 10 min storm64 | 24.5 °C start and end (dumpsys battery) | ≤ 39 °C (pass) |

### Why M2 did not pass (precise)
- **T-CPU:** at Q0 the fixed cost alone is above budget. Measured per output frame at 2.0 GHz (fast state):
  88 combs × 40 ns = 3.5 µs, room 1.6–1.9 µs, master 0.6–0.7 µs, 64 Hermite voices × 112 ns = 7.2 µs →
  ≈ 13 µs = 62 % of a 20.8 µs frame. §3.16 budgets 22 cycles per comb-frame (measured ≈ 80) and 110 per voice
  (measured ≈ 224). The device's slow state doubles all of it. Meeting 42 % needs WP2/WP3 kernel work (voice
  kernel at ≈ 2× its budget, room at ≈ 5×) or a Q0 with fewer combs/voices (plan owner, L-7).
- **T-DEC:** one unit (256 s of audio) cannot be split, so playable = releases + pedals ∥ v10 at the unit rate;
  the unit rate is 30× against the bench's 47×. Suspected cause: `KitDecoder`'s sleep of 2× the write+force time
  after every 4 MiB, which also runs when nothing plays. Not changed (could not re-measure: device offline).

### Open issues (M2)
- Glasses offline after the second `adb reboot` (01:43): not listed by `adb devices` nor on USB. Needs a hand.
- WP4: voicing throttle (above); measure the unit rate with the sleep factor at 0.5 and with two codecs in parallel
  for the later units (complete ≤ 120 s).
- WP2: fold `MasterProcessor.latencyFrames` into the published song time (1 ms A/V offset); voice kernel cost.
- WP3: room (`nsRoom` 1.6–1.9 µs/frame vs §3.16's 0.33) and the 2-way comb kernel result.
- Plan owner: the Q0 budget and step-down order with these numbers; T-ALIGN needs isolated onsets (a
  `synth:sync`-style score) to get more than 1–2 samples per run.
- Carried from M1: bimodal device speed, T-CLOCK route change, HOME kills the app.

### M2 fix round (2026-09-23): first-run voicing deadlock
- Fixed: `VoicingScheduler.mayVoice` now lets the active kit voice its playable set while a performance is
  already playing (nothing can sound without it); the decoder yields during playback only once the plan is
  playable. `AppController` re-sends `setPlaybackHint(playing, GRAND, ...)` on every play/pause edge (500 ms
  poll) and `applyQuality` passes GRAND instead of null. Unit test added (PoliciesTest).
- Device (pm clear, play BWV 846 at +12 s, before v10): releases/pedals voiced, v10 voiced 4 s after play
  started (16x, 691 MHz), wavdump 20 s: peak -3.2 dBFS, RMS -18.0, 100 ms windows -21.1..-14.0, 0 clipped,
  0 underruns. Pause broadcast: voicing resumed at once, units voiced every ~14 s. An earlier run with no
  play reached `complete gen=1 stub=false`.
- Still open: T-DEC (14-15 s/unit at 691 MHz, ~8.5 s at 2 GHz; > 120 s total), T-CPU at Q0 (cpu 56-60%, p99
  4.55-4.75 ms with ~10 voices, 88 combs), storm64/T-PF/T-ALIGN/reboot not re-run; the idle overlay reads
  "paused" while the M1 playback driver plays (WP12 SessionController not wired). M2 not tagged.

## M3 Keys move (2026-09-23) — not tagged (gate partial)

### Merged
- `wp5-mech` (e49b628), `wp6-render` (636864b), `wp7-instruments` (1cd7a4b): all three merged with no conflicts.

### Wired
- `Wiring`: `mechanics()` = `mech.MechanicsEvaluatorImpl()` (per GL view); `glHost` = `render.HkGlView(ctx, loader, msaa, head)`
  (WP6 request 1: the Wiring `HeadPose` is passed); `scenes` = WP7 `Instruments.create` + `StubVenue` (WP8 not merged).
- `Playback.onPerformance` → `gl.setPerformance(perf, profile)` for every new Performance (the GL host never received one
  before); re-sent on `attach`. `setSettings` (displayLeadMs from `render.leadMs.speaker`, default 30), `setSyncFlash`,
  and `setIdle` (follows the clock on the 500 ms poll, so pacing drops to 10 fps when paused).
- CONTROL: `--ei lead N` (stores the speaker lead), `--ez sync true` (flash on + `synth:sync`), `--ez glreset true`
  (`HkGlView.resetContext()` on the same view, WP6 request 2).
- `HKRender fps= late= lateP99Us= hitches= divider= draws= maxDraws= tris= glGen= glErrors=` every 5 s while resumed (T-FPS).
- `PedalInset` camera moved closer ((0.14, 0.30, 0.12) → (0, 0.08, −0.28)): at WP6's 0.8 m the pedals were ~10 px wide in
  the 200 × 150 box; now ~40 px, clearly legible.
- New: `tools/device/smoke.sh M3` (→ `smoke_m3.sh`), `tools/device/avsync.py` (§8.6 coarse check on a scrcpy recording).

### Gate results (glasses A06B4A96A733283, release build, md5 verified)
| Check | Measured | Result |
|---|---|---|
| `tools/ci.sh` | PASS (one run hit a flaky `AudioOutputTest.stopAndStartRestoreThePausedPosition`, green on rerun) | pass |
| `smoke.sh M3` | all 9 checks PASS | pass |
| T-FPS | Q0 30.0, Q2 20.0, idle (paused) 9.9–10.0; hitches 0; late > 8 ms: 1 frame in ~2,100 | pass |
| draws ≤ 28 | max 15 per eye (Player overview 11, Follow 14–15 incl. inset); scene grand 19 items, 2.1 MiB resident | pass |
| T-GLRESET | `glGeneration=1` logged, no second `scene grand` line, glErrors 0, 30 fps within 1 s | pass |
| T-SYNC coarse (scrcpy, lead 30) | Q0 −42.4 ms (p90−p10 24.3), Q2 −41.4 ms (26.2) in the same session; later sessions Q0 −39.7 / −19.4, Q2 −18.3 (37.0) | Q0 ≈ Q2 (no frame-rate bias); spread ≤ 33 ms at Q0; **absolute not measured** |
| T-SYNC absolute (240 fps phone film) | not run: needs a camera and a hand | open |
| T-SYNC / T-UND on Bluetooth | not run: no headset paired/connected to the glasses | open |
| L-1 presence floor | needs the user | open |

Screencaps: `docs/shots/m3_player_overview.png` (whole keyboard, keys dipping, lid, lyre, brass pedals, identical eyes),
`docs/shots/m3_player_follow.png` (three octaves around the centroid, keys pressed, pedal inset bottom right of each eye).
Honest look: both framings render correctly in both eyes; the stub venue's floor is a flat bright tan (WP8 not merged,
APL is an M5 check); the StubOverlay title/status text sits over the keys (WP10 not merged); the one-frame sync disc was
not caught by screencap but is detected at every click in the scrcpy recordings (98/98).

### Why M3 is not tagged (precise)
- T-SYNC needs the absolute check (phone at 240 fps filming the lens) to calibrate `--ei lead`; scrcpy's audio capture
  latency varies ~20 ms between sessions, so it cannot give the absolute offset.
- T-SYNC and T-UND 5 min on a Bluetooth headset need a headset connected to the glasses.
- L-1 is a listening/looking check with the user.

### Open issues (M3)
- Flaky `AudioOutputTest.stopAndStartRestoreThePausedPosition` (AudioOutputTest.kt:255), WP4.
  Follow-up 2026-09-23: 5/5 isolated runs pass; under 8 CPU hogs it failed 1 run in ~15 (plus one
  `hkAudioLoopAllocatesNothingAfterWarmUp` failure), so it is load-timing; not yet diagnosed (failure XML not captured).
  tools/ci.sh re-run at 39f10b4: PASS. M3 still not tagged: remaining gate items need a 240 fps phone, a BT headset and the user.
- WP7 request (harpsichord action-set layout) still to be decided by the plan owner before M7.
- Pass the bank's `lastDamper` to `setInstrument` when WP12 lands (profile default 88 is correct for Salamander).

### M3 verifier fixes (2026-09-23)
- Status line said "paused" while playing: play/pause lands on the audio thread after the action, the overlay rendered first. AppController now watches the clock every 250 ms and re-renders on change. Verified: smoke player_overview.png reads "player · playing".
- Sync flash: the disc is lit only during an A4 contact's exposure window (one frame), so a single screencap misses it. The renderer now logs `sync flash frame n=` (smoke expects it: PASS), smoke_m3 takes a 12-shot burst, and a 60-shot burst caught 3 lit frames (centre pixel fff2d3 vs a57646): docs/shots/m3_sync_flash.png.
- Pedal inset camera moved closer (pos 0.03,0.24,0.02 -> target 0,0.07,-0.29, fov 34): the three pedals now fill the inset width (docs/shots/m3_player_follow.png).
- Re-run: ci PASS, run.sh md5 22e8f64e75bec5c0a528a562eff3834d, smoke M3 11/11 PASS, draws max 15/eye.
- Still open (not measurable here): T-SYNC absolute (240 fps camera + avsync.py), T-SYNC/T-UND on Bluetooth (no headset paired), L-1 and on-head stereo review (user). milestone-M3 not tagged.

## M4 Hammers hit strings (2026-09-23) — not tagged (gate partial: T-SYNC absolute)

### Merged
- `wp10-ui` (c77a79d): no conflicts. WP5 ExposureSampler/strings-from-energy, WP7 action set/hammers/dampers/strings and
  WP2 EnergyRing lanes were already on main from M3.

### Wired
- `Wiring.ui` = `ui.model.UiStateMachineImpl()`, `Wiring.overlay` = `ui.OverlayViews(ctx)` (WP10 wiring note).
- Facts: `kitStates` now carries `kits.state(GRAND)` (the title tap was refused with an empty map). Until WP12's
  SessionController lands, a CONTROL `play` sends `UiEvent.ENTERED` when the UI is on the title card; `UiAction.Enter` is logged.
- Pad / `--es gesture` swipes now drive the ring (Player -> Action -> Hall) and up/down the framings through the state machine.
- Fix (WP6 shaders): the Action cut plane was only honoured by the SKINNED and STRING programs, so the lacquered case, plate,
  soundboard, lettering and gilt edges were drawn whole and the cutaway showed only the side of the rim
  (first `m4_action_cutaway` shot). LIT, LACQUER, DECAL and RIBBON now pass model-space x and discard beyond `uClipX`
  (1e9 for unclipped items). No contract change.
- Instrumentation: `HKRender strike audit end|reset id= gen= notes= expected= drawn= sameKeySameFrame= fps=` (contacts whose
  onUs falls in each frame's exposure window vs `pose.flash` keys drawn), and `swipe to first fade ms=` (setView to the
  first dipping frame).
- New `tools/device/smoke.sh M4` (-> `smoke_m4.sh`).

### Gate results (glasses A06B4A96A733283, release build md5 506f6a9654974741fedef663a0c9beae)
| Check | Measured | Result |
|---|---|---|
| `tools/ci.sh` | PASS (in run.sh) | pass |
| `smoke.sh M4` | 8/8 PASS | pass |
| every strike drawn exactly once | synth:repeat15 (90 notes) in Action: Q0 30.0 fps expected 90 drawn 90; Q2 20.0 fps expected 90 drawn 90; sameKeySameFrame 0 (also the interrupted runs: 70/70, 63/63) | pass |
| swipe-to-first-fade < 100 ms | 8 swipes, max 64.2 ms (0.6–64 ms; measured from setView on main, pad recognition not included) | pass |
| draws <= 28 | max 19 per eye (Action cutaway 18, overhead 13, Hall 16, Player 11); no hitch, glErrors 0 | pass |
| T-SYNC in the Action view | not measured: needs the 240 fps phone film (scrcpy gives only the relative check, §M3) | open |

Screencaps (docs/shots/): `m4_title` (WP10 title card), `m4_player`, `m4_action_cutaway` / `m4_repeat15_3` (cutaway on C4:
keys, hammer row, the struck string lit, C4 label), `m4_action_overhead` (lid off, string bed, hammer row, struck strings
white, the rest grey), `m4_hall` (stub venue, WP8 at M5), `m4_player_back` (ring back to Player).
Honest look: the cutaway now reads as a section, but the section caps are flat beige slabs that look like untextured blocks
and the camera sits low enough that the key frame fills the lower third; the neighbouring actions recede but are small. The
overlay reads "bar 1" and "0:10 / 0:00" (duration 0): the facts are still the M1 minimal set until WP12.

### M4 rework after the independent verifier (2026-09-23)
Fixes, each re-checked on the glasses:
- **Section caps drawn at x = 0** (the "beige slabs" in `m4_repeat15_3`): the caps are built in the plane x = 0 and are meant
  to be drawn at the cut plane (MeshRaster already shifted them), but GL drew them unshifted, so they floated in the middle
  of the keyboard whenever the cut was near C4. LIT_VS gained `uShiftX` (0 for every other program); ItemDrawer sets it to
  `clipX - 0.5 mm` for SECTION_CAP. The caps now close the clipped key frame and belly rail at the cut. No contract change.
- **Cutaway blocked by the fallboard and music desk**: WP7 GrandCase split them (plus the key-well back) out of `grand.case`
  into `grand.fallboard` (slot 7, `VM.PLAYER_HALL`), lettering decal too; the music desk left `grand.lidstick`. Both Action
  framings drop them as a cutaway drawing would. Draws went down (max 18 per eye).
- **Overlay facts**: `durationUs` and `bar` come from the bound Performance (`durationUs`, `barAt(songUs)`), instrument from
  it too; the overlay now re-renders once a second (time line, bar, toast expiry). Shots read e.g. "bar 4", "0:11 / 3:47".
  WP12's FactsAssembler still replaces the stub facts at M6.
- **Overhead "no view label"**: the label is WP10's 1.5 s view toast; the old smoke shot was 11 s after the swipe. Smoke now
  shoots every view 1 s after its gesture (toast visible) plus a `_later` shot.
- **Smoke checks**: title card = `HKUi overlay context=TITLE titleCard=true` (logged on each overlay context change);
  dampers = new `HKRender damper audit` (a damper landing on a ringing string, sustain up, must bring the drawn amplitude
  under 0.1 within 1500 ms); two swipes now go through the real pad path (`input touchscreen swipe` -> TrackpadGestureEngine)
  and the renderer logs `fromPad ms=` from the MotionEvent's time to the first fade; HKInput logs `fingerMs` / `recogMs`.
- **APK md5**: the release APK embeds `BuildConfig.GIT_COMMIT`, so every commit changes the md5; the verifier's rebuild at
  3f51d5a differs from the number recorded before the final commit. run.sh verifies installed == local each time.

Gate re-run (main a5ac184, clean tree, glasses A06B4A96A733283): run.sh ci PASS, release md5 f644e6a0e775e11325ad2d844d2a4f0f installed and verified (a second ci.sh at the same commit gave the same md5); `smoke.sh M4` 13/13 PASS (second run: dampers 22 landed / 9 settled / 0 late, pad-to-fade max 31.7 ms, setView-to-fade max 28.2 ms).
| Check | Measured | Result |
|---|---|---|
| strikes drawn exactly once | repeat15 Q0 30.0 fps 90/90, Q2 20.0 fps 90/90, sameKeySameFrame 0 | pass |
| dampers stop strings | 24 landings on ringing strings, 12 settled under 0.1 (max 333 ms), 12 re-struck first, 0 late | pass |
| swipe-to-first-fade | 10 swipes, max 30.8 ms from setView; pad event to first fade 34.1 / 26.4 ms (recognition 16 / 8 ms) | pass |
| draws <= 28 | max 18 per eye; hitches 0, glErrors 0, no crash | pass |
| T-SYNC in the Action view | not measured (240 fps camera, headset, user) | open |

Honest look at the new shots: cutaway shows keys, hammers, struck string and the cut section (flat beige, a cut face by
design, §5.3 row 17); camera height is per the §5.6 table. Hall shows the piano small and low because the tabled camera aims
at the room, which is black until WP8's venue at M5 (flag for WP6/WP7 review then). Overhead now shows its label.

Still open: T-SYNC absolute (user + 240 fps camera + Bluetooth headset), so milestone-M4 stays untagged; `stageHidden` hook (M6);
Hall framing review with the M5 venue.

### Open issues (M4)
- T-SYNC absolute (Action view, speaker and Bluetooth) needs a 240 fps camera, a headset and the user; milestone-M4 not tagged.
- Overlay facts (duration, bar, movement) wait for WP12 FactsAssembler; `stageHidden` hook not yet wired (Display floor card, M6).
- Section-cap look and cutaway camera height: worth a WP7/WP6 pass before the user review.

## M5 The room (2026-09-23, integrator) — gate PARTIAL, not tagged

### Merged
- `wp8-venue` (fc4e3ce) and `wp12-session` (958cf30) into main (clean merges). WP3 (a678af9) was already on main.

### Wired
- `Wiring.scenes.venue()` = one lazy `venue.VenueSceneImpl` (StubVenue gone). `StereoRenderer.build` calls
  `FlameFieldImpl.setInstrumentOrigin(placement)` (WP8 request: light 3 = nearest N sconce group).
- Sound follows the view: `AppController.updateRoom()` on engine start and on every `SetView` — WP12
  `ListenerRooms.resolve` (the §5.6 ear, world-locked only in the Hall) → WP3 `RoomAcoustics.design` → `audio.setRoom(d, 500)`;
  logs `HKUi setRoom view= ear= worldLocked= width= direct= erGain= reverbGain= preDelay= t60Mid= az=`. Interim until
  SessionController takes over at M6 (it has the same code path).
- Soak: `SoakSource.play` now plays (catalogue ids mapped to bundled files until M6); new plan `therm30`
  (op. 106 i–iv chained, views rotating every 5 min). New `tools/device/smoke_m5.sh`, `apl.sh`, `apl_meter.py`.
- Fixes: `venue.fittings.gilt` moved to drawSlot 2 so it merges with `venue.gilt` (SceneAssemblerTest found Hall/Salon at
  29 draws); floor light pools now fall off to 0 by r = 2.6 m around each pool centre (§5.5 floor row) instead of a
  saturated plateau; flame sprites fade out closer than 0.5–1.2 m to the eye.

### Gate results (glasses A06B4A96A733283, main c39f36f, release md5 f786cd79696e3a676296f80c1d620810 installed and verified)
| Check | Measured | Result |
|---|---|---|
| `tools/ci.sh` | PASS at c39f36f | pass |
| `smoke.sh M5` venue/levels | Hall → SALON, Player/Action → STAGE; draws max 24 per eye; hitches 0, glErrors 0 | pass |
| sound follows the view | one setRoom per view change; Player ear (−1.55,1.20,−1.90) width 1.0 direct 1.00 preDelay 144; Action cutaway width 0.8 direct 1.60; overhead direct 1.21; Hall ear (0.40,1.20,3.00) worldLocked=true width 0.4 direct 0.32 erGain 0.54 reverbGain 0.63; design 0.3–0.7 ms (first 99 ms incl. anchors) | pass (logs); stays-put-on-head-turn not measurable over adb |
| T-APL | Player 22.6%, Action cutaway 24.8%, overhead 32.0% (≤ 9%); Hall 14.7% (≤ 12%). Before the floor fix: 30.2 / 31.3 / 33.7 / 15.2 | **FAIL** |
| T-THERM (30 min) | therm30 1882 s, 189 rows: Q0 throughout (never Q3), fps 30, underruns 0, late frames 50, no reboot; battery read 24.0 °C flat — **run plugged in** (status 5, USB powered): I cannot unplug the cable, so this is not the §8.5 unplugged run | partial |
| T12.8 LevelCalibrationTest | still @Ignore: needs a pre-limiter tap in MasterChain and a LoadedBank from exported real regions | open |
| T-CPU with combs, comb calibration, L-2, L-7, look-around | not run (L-* need the user; look-around needs head motion) | open |

APL breakdown (left eye, six 80-px bands): Player 12/14/27/30/27/26% — the lacquer case alone sits at ≈ 8% (presence
floor 22,18,15 after lift), the ivory keys strip ≈ 30%, the floor under the keyboard is still inside the west candelabra's
pool. Overhead: plate/soundboard (196,150,72) and a steel string bed fill the frame, plus a large lit glow on a surface near
the candelabra flame light (not a sprite). Budget ≤ 9% cannot be met by the venue alone: WP7 (plate/soundboard/key
brightness, "shadowed ≤ 0.25") and WP6 (LIT shading of venue surfaces near the 4 lights, the parquet texture is not sampled
by LIT_FS) must take a pass.

Screencaps (docs/shots/): `m5_hall`, `m5_hall_3`, `m5_player`, `m5_action_cutaway`, `m5_action_overhead`. Honest look at
the Hall: the gilt trellis, cornice, rocaille cartouches, girandoles and two candelabra read well against black; the
chandelier is cut off at the top of the frame; flames are tiny (candles read as white sticks); the N mirrors show
shattered-looking dark polygons over the glow (wall glow / reflection geometry artefact, WP8/WP6); the front-row chairs are
flat beige slabs across the bottom and are the main APL overshoot; the grand is small and low (the M4 Hall framing issue stands).

### Open issues (M5)
- T-APL fails in every view (numbers above) — WP6/WP7/WP8 pass needed; mirror artefact; chairs; chandelier framing.
- Unplugged T-THERM needs the user to pull the cable (`tools/device/soak.sh start therm30`, then `pull`); battery reading flat at 24.0 °C plugged.
- T12.8: pre-limiter peak tap (WP3 MasterChain) + real-region bank for the JVM test.
- T-CPU with combs re-run, L-2 / L-7 listening with the user; look-around and "stays put when the head turns".
- Soak CSV `movement` column is still "none" (WP12 facts at M6).

### M5 re-run (2026-09-23, integrator, second pass) — gate still PARTIAL, not tagged
- Fix: APL cap in `StereoRenderer` (black multiply over lit surfaces before flames/glyphs in Stage views; folded into the
  fade quad in the Hall, which is at the 28-draw limit). Gains: Player 0.37, cutaway 0.30, overhead 0.26, Hall 0.74.
- `tools/ci.sh` PASS; release md5 f7b37e535f80f56a44e588d912cb9056 installed and verified.
- `smoke.sh M5` PASS: T-APL Player 8.36%, Action cutaway 7.63%, overhead 8.54% (≤ 9%), Hall 11.09% (≤ 12%); draws max 24;
  0 hitches, 0 GL errors; setRoom per view unchanged (6 lines).
- Shots: `m5_hall_aplcap`, `m5_player_aplcap`, `m5_action_overhead_aplcap`. Honest look: the Player is dim but the keys,
  pedals and case read; the Hall mirrors still show dark shard polygons, the chandelier is still cropped, the flames
  still tiny, the front chairs flat slabs — the cap hides brightness, it does not fix those (WP8/WP6).
- Still blocking the tag: the unplugged 30-min T-THERM (user must unplug), T12.8, T-CPU with combs, comb calibration,
  L-2/L-7, look-around.
