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
