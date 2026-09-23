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
- **Test fixtures (`core/src/testFixtures/.../testutil`):** AllocProbe, AwtPainter.
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

## Remaining (days 1–2 → contracts-v1.1)

- AudioClock and VisualClock to the full §2.5 algorithms; the §7.2 WP0 JVM tests (AudioClockTest,
  VisualClockTest, EnergyRingTest, CommandRingTest, VoiceCursorBoardTest, HeadPoseTest,
  PedalCurveTest, PhysicalCurvesTest, TuningTest, ConventionsTest, PlaylistTest,
  QualityLadderTest, ThermalPolicyTest, StubContractTest incl. PerformanceValidator).
- `system/*` (ThermalPolicy (core), Settings, ThermalGovernor, DebugControl, PerfProbe, SelfTest,
  SoakRecorder, MediaButtons), `platform/TrackpadGestureEngine.kt` (WanderQuest copy + cyttsp6
  filter + firm-click dedup), `platform/DeviceInfo.kt`; the §1.10 lifecycle in full; CONTROL
  receiver; Wiring's VoiceCursorBoard/HeadPose singletons.
- `tools/device/*` (run.sh, smoke.sh, soak.sh, cpu.sh, apl.sh, push_scores.sh) and the M0 gate.
- Tag `contracts-v1` and create the worktrees: done by the auditor, not WP0 (this session).

## Decisions and deviations

See `docs/contracts-changelog.md` (2026-09-22) and `docs/plan-changelog.md` (2026-09-22).

## Test results

- `tools/gw :core:test :app:assembleDebug`: green (2026-09-22).
- `tools/gw :app:assembleRelease :app:testDebugUnitTest`: green.
- `tools/check_purity.sh`: OK (negative test with android/javax/java.awt references fails as
  expected).
- `tools/device/lock.sh`: exit codes propagate, a second holder waits, stdin is passed through.
