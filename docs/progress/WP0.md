# WP0 progress (shell, contracts, integration, device tooling)

Branch `main`, repository `/Users/me/Projects/Hammerklavier`. Plan: PLAN §7.2 WP0, §7.3.

## Day 0 → contracts-v1 candidate (2026-09-22): done

- **Build:** `settings.gradle.kts` (`:core`, `:app`); `core/build.gradle.kts` (kotlin jvm by id,
  java-test-fixtures, org.json compileOnly, junit + org.json for tests, test JVM
  `-XX:-DoEscapeAnalysis -XX:-EliminateAllocations`); `app/build.gradle.kts` (MathCosmos shape,
  noCompress opus/bin/json/mid/midi/kar, `unitTests.isReturnDefaultValues`, the §2.2
  dependencies, archivesName Hammerklavier, BuildConfig `GIT_BRANCH`/`GIT_COMMIT`, release signed
  with the debug keystore, not debuggable, not minified); `proguard-rules.pro`; manifest per §7.2
  WP0 and §1.10; `colors.xml` rewritten; `.gitattributes` (LF, binaries, LFS for kit opus/bin).
- **Contracts (`core/.../contract/*.kt`):** Ids, Status, Constants, Tuning, InstrumentProfile,
  PhysicalCurves, PedalCurve, Performance, ScoreApi (+ SyntheticSpecs data), Clock, Rings,
  Quality, Bank, AudioApi, Dsp, Venue (+ KonzertzimmerAcoustics, Conventions), Mechanics, Scene
  (+ MaterialTable), Render, Palette (Pal), Library (+ Playlist, SettingsStore), Ui, Companion.
  App: `contract/android/Hosts.kt` (GlHost, OverlayHost), `CanvasPainter.kt`.
- **Stubs (`contract/stub/*.kt`):** FakeClock, NullAudio, SineBank, SineCore, StubKits,
  KeyMapFixtures, PerfFixtures, PassThroughDsp, StubRoomDesigner, FixedRoom, StubVenue (+
  StubFlames), StubScoreCompiler, StubMechanics, StubScenes (+ StubInstrumentScene, StubAnchors),
  StubLibrary, StubUi (+ StubOverlayState), MemSettings; SyntheticSpecs is in `contract/`. App:
  `contract/stub/android/StubGlHost.kt`, `StubOverlay.kt`. All have working bodies except
  AudioClock and VisualClock (trivial, see contracts-changelog item 31).
- **Test fixtures (`core/src/testFixtures/.../testutil`):** AllocProbe, AwtPainter,
  PerformanceValidator.
- **`system/ThermalPolicy.kt`** (core, pure): the §5.11 enter/relax table with one-step relaxation.
- **`mesh/MeshBuilder.kt`** day-0 subset (part, color, vertex, tri, quad, box, build with split).
- **Shell:** HammerklavierApp (HKLoader/HKVoicer executors, crash.txt handler, Wiring,
  AppController), MainActivity (GL view + BinocularSbsLayout overlay, black, keep-screen-on,
  immersive, STREAM_MUSIC, `--ez mono true`, DPAD_CENTER/ENTER → tap, BACK → leave),
  AppController (thin adapter driving the stubs until WP12), Wiring (all stubs),
  `platform/BinocularSbsLayout.kt` (copy of MathCosmos's).
- **Tools:** `tools/env.sh`, `tools/wt.sh`, `tools/ci.sh` (+ `--contracts`),
  `tools/check_purity.sh` + `tools/purity_dirs.txt`, `tools/device/lock.sh`.
- **Docs:** `docs/contracts/map-json.md` (frozen §6.6), `docs/contracts-changelog.md`,
  `docs/plan-changelog.md`, `docs/wiring/README.md`.

## Days 1–2 → contracts-v1.1 (2026-09-22): done, M0 passed on the glasses

- **AudioClock** (full §2.5): 256 seqlocked records in `AtomicLongArray` (version, F, S, rate/playing/registration,
  epoch/generation, checksum), a seqlocked anchor (frame, nanos, fs_fit, flags, checksum), `reset()` (head 0, anchor
  dropped, estimate mode, session bump), timestamps accepted only if advancing and within ±0.5% of the fit measured
  against the oldest pair of the 32-pair least-squares window, `latFrames`, drift p99 over 128 pairs, `clockMiss`
  on the oldest-record fallback (H clamped to that record's start), H ≤ newestF + BLOCK. **VisualClock**: hold,
  follow, 10% slew, reseed (< −60 ms, > +250 ms, session/epoch/generation, invalid sample), tiling exposure windows,
  empty while paused and after a reseed. Both allocation-free (AllocProbe).
- Tests: `AudioClockTest` (11 cases incl. 10⁶ reads vs a writer: 0 torn records, 0 torn anchors), `VisualClockTest`
  (8), `CommandRingTest` now 10⁷ items. All other primitives were already implemented and tested on day 0.
- **system/**: Settings (SharedPreferences, listeners; replaces MemSettings in Wiring), ThermalGovernor (sticky battery
  + thermal listener, engine lifetime, faketemp/quality overrides, heartbeat line every 60 s), DebugControl (DUMP
  permission, echo line `HKUi CONTROL k=v`), PerfProbe (FRAME HITCH > 120 ms, majflt, cpufreq, time_in_state, 10 s
  HKPerf line), SelfTest (§8.3, 60 s torn-read test; `--ei selftestsecs N` added for short runs), SoakRecorder (CSV every
  10 s, plans therm45/bright/rest10/sleep20), MediaButtons (framework MediaSession).
- **platform/**: TrackpadGestureEngine (WanderQuest copy + cyttsp6 key-path filter + firm-click dedup, left-arm taps
  removed, `onGesture` contract sink), DeviceInfo.
- **Shell:** AppController owns the engine services (start at process start and every resume, stop only on finishing),
  applies the quality ladder (audio always; GL and brightness when resumed), handles the §8.2 extras it can without
  SessionController (gesture, view, framing, pause, resume, seek, rate, leave, quality, faketemp, recenter, brightness,
  debug, selftest, gcstats, dump, soak); the rest are logged `deferred (SessionController, WP12)`. MainActivity feeds
  touch/key/generic motion to the gesture engine, BACK → SYSTEM_BACK, forwards `am start` extras to CONTROL.
  Wiring holds the HeadPose and VoiceCursorBoard singletons.
- **tools/device/**: run.sh (ci, install, md5 check, compile speed, launch; `--no-ci`, `--mono`), smoke.sh M0 (streams
  logcat to `build/smoke/M0/logcat.txt` because the device ring is only 64 KiB; checks both eye halves match),
  push_scores.sh (§1.7), soak.sh (start/pull). Every adb sequence runs under lock.sh.

## M0 gate on A06B4A96A733283 (release build, md5 verified): PASS

tap (touch) · DPAD_CENTER → exactly one tap (key) · `--es gesture double/triple` · `input swipe` → FORWARD · CONTROL echo ·
HKThermal lines, `faketemp 405` → Q1, `faketemp 425` → Q2 with the display asleep · selftest: version/branch/commit,
GL info (Adreno 621, ES 3.2), torn-read 10.5 M reads 0 mismatches in 60 s, pass=9 fail=0 skip=3 (decoderProbe,
audiotrack, companion: stubs) · BACK at the root leaves the app · eye halves identical (0.00% differing).

## For the user: physical checks (cannot be done from adb)

1. One firm click on the RIGHT pad logs exactly one `HKInput tap` (not a touch tap plus a key tap).
2. A firm click on the LEFT arm logs nothing on HKInput.
3. A physical double-tap on the right pad logs `double` (and does not also toggle play/pause).
4. A broadcast from another app is ignored (enforced by the DUMP permission; needs a second app to prove).
Watch with: `adb -s A06B4A96A733283 logcat -s HKInput`.

## Remaining

- Tag `contracts-v1.1` (by the auditor/integrator on main, not this branch). cpu.sh and apl.sh (§8.4 measurements)
  arrive with the milestones that need them (M1/M5).

## Decisions and deviations

See `docs/contracts-changelog.md` (2026-09-22) and `docs/plan-changelog.md` (2026-09-22).

## Test results

- `tools/gw :core:test :app:assembleDebug`: green (2026-09-22); 37 JVM tests in 8 classes, 0
  failures (list in contracts-changelog item 33).
- `tools/ci.sh`: PASS (purity, `:core:test :app:testDebugUnitTest :app:assembleRelease`; the WP11
  pipeline steps SKIP until delivered).
- `tools/ci.sh --contracts` and `tools/wt.sh`, exercised in a throw-away clone: the worktree gets
  its branch, local.properties and identity; a contract rename that a branch uses is reported
  BROKEN and the temporary worktree is removed.
- `tools/check_purity.sh`: OK (negative test with android/javax/java.awt references fails as
  expected).
- `tools/device/lock.sh`: exit codes propagate, a second holder waits, stdin is passed through.

## Contract audit before tagging contracts-v1 (2026-09-22)

Line-by-line audit of `contract/**`, `contract/stub/**`, `contract/android/**` and
`contract/stub/android/**` against PLAN §2.3, the §2.2 table and the §10 review log (R1–R107),
plus the build files, the day-0 MeshBuilder subset and the tools. Every type, member, parameter,
default, nullability, constant, enum entry, package and file matches; every §2.2 stub exists
(SyntheticSpecs in `contract/ScoreApi.kt` as §2.3 shows); profile values match §3.7, the ladder
matches §5.11, the room table and placements match §3.12/§5.6, Pal matches §5.9. The only
departures are the ones already recorded in the contracts and plan changelogs. `check_purity.sh`
OK; `lock.sh` propagates exit codes; `tools/gw :core:test :app:assembleDebug` green. No gaps
needed fixing.
