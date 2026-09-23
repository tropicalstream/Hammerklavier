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
