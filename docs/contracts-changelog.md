# Contracts changelog

Every change to `contract/**` (core `contract/`, `contract/stub/`; app `contract/android/`,
`contract/stub/android/`) and to `docs/contracts/map-json.md` gets a dated entry here, approved by
WP0 (PLAN §7.1 rule 2). After `contracts-v1` the **growth rules** apply: new interface members get
default bodies; new constructor parameters are appended last with defaults; contract classes are
always constructed with **named arguments**; nothing is removed or reordered. WP0 runs
`tools/ci.sh --contracts` after each entry. Requests from other WPs go in `docs/requests/WP<N>.md`
on their branch.

---

## 2026-09-22 · contracts-v1 (candidate) · WP0

Every type, interface, enum, constant and primitive signature of PLAN §2.3 is in place, in the
packages and files of §2.2, and every stub of the §2.2 stub rows exists and compiles. Where the
plan's text left a signature open (`…`, a stub with no signature) or could not be expressed
exactly in Kotlin, the choice below is now the contract.

### Could not be expressed exactly

1. **`LoadedBank.readyMask`** — Kotlin rejects `@Volatile` on an abstract (interface) property.
   The interface declares `var readyMask: Long`; every implementation backs it with a `@Volatile`
   field (`SineBank` does). Semantics unchanged: informational only, bit u = unit id u.

### Placement and naming choices

2. **`SyntheticSpecs`** lives in package `contract` (file `contract/ScoreApi.kt`), as the §2.3 code
   shows; §2.2 lists it in the stub row because WP0 owns and fills it. It also gains
   `NAMES: Map<SyntheticScore, String>` (the §4.5 names: `sync`, `scale`, `storm64`, `pedalhalf`,
   `sostenuto`, `unacorda`, `repeat15`, `fold`, `crescendo`), `kindOf(name)` (accepts `x`,
   `synth:x`, `test:x`) and `clickOffsetMs(i)`.
3. **`CanvasPainter`** is in `contract/android/CanvasPainter.kt` (same package as `Hosts.kt`).
4. **`Conventions.forwardYaw(placement, earPiano, sourcePiano)`** is the signature (the §2.3 code
   block). The preamble's `forwardYaw(placement, view, framing, anchors)` wording is not a second
   overload. A Hall listener (room frame) uses `yawOf(sourceRoom − earRoom)`.

### Normative details added to existing contracts

5. **Synthetic scores (§4.5) are frozen in the KDoc of `SyntheticSpecs`** (a table of the nine
   kinds). `ScoreSpec` times are **file time from 0, without the pre-roll**, all whole
   milliseconds, so WP11's `.mid` twins written at PPQ 500 and tempo 500,000 µs per quarter
   (1 tick = 1 ms, 4/4 → a bar = 2 s) are exact. Controller curves are raw steps (cc / 127 held
   until the next event: points (t, prev), (t, new)). Notes sorted by (onUs, key).
6. **Test twins are named after the §4.5 names:** `assets/midi/test/<name>.mid`, movement id
   `test:<name>` (e.g. `test:storm64`). WP11 writes them under these names.
7. **`InstrumentProfile`** gains `defaultDamperT60(key)` and `defaultFreeT60(stop, key)`: the §3.7
   formulas, "once in contract/InstrumentProfile for SynthBank and the stub kit" (T11.10 checks
   the stub kit against them). Grand `T60d` uses n = MIDI key clamped to 21..88 (this reproduces
   the table's C6 0.12 / C4 0.33 / C2 0.84 / A0 1.3). `displayName`: "Grand", "Upright",
   "Harpsichord · Bach era" (UiText owns what the user reads). The harpsichord's blow, let-off
   and check are 0 and its `actionRatio` is `HarpsiTiming.JACK_RATIO`.
8. **`Painter2D` colours are `0xAARRGGBB` ints** (Android `Color`, `java.awt.Color(argb, true)`);
   `end()` returns **straight-alpha** RGBA8888, rows from the top.
9. **`RoomDesign.t60High`** is the 8 kHz value (§3.12, T3.3); the §2.3 comment said 4 kHz.
10. **`BakedMesh`** gains read-only getters `vertexCount` and `triangleCount`.
11. **`MechanismPose.strikeAge`** starts at 1e9 ("none") instead of 0.
12. **`TuningSpec`** has value equality (`equals`/`hashCode` on aHz and temperament).
13. **`PedalCurve`**: 0 before the first point, the last value after the last; equal times make a
    step. `nextCrossing(from, level, rising)` = the first t ≥ from where the curve goes from below
    `level` to ≥ `level` (rising) or from above to ≤ (falling); the implicit 0 before the first
    point counts. `Cursor.advanceTo` re-seeks by binary search when time steps back.
14. **`Performance`**: `eventIndexAtOrAfter` = lower bound; `barAt` = number of bar starts ≤ t,
    at least 1; `latchIndexAt` = last latch entry ≤ t, −1 before the first.
15. **`QualityLadder` Q3** (display rest): `roomCap = STAGE`, `brightnessCap = 0.6` (the table has
    "–"; neither is drawn while GL is paused). `of(level, q0Cap)` clamps C to 64..128 and the level
    to 0..3; Q1/Q2/Q3 voice caps are round(0.75 / 0.625 / 0.5 · C) with floors 48 / 40 / 32.
16. **`MaterialTable`** values [D] (the §5.8 table gives programs, not every number): lit specular
    exponents WOOD_CASE 32, FLEMISH_CASE 24, PLATE 64, SOUNDBOARD 24, HARPSI_SOUNDBOARD 24,
    PAPER 24, CHAIR_FRAME 40, DAMASK 24, PARQUET_POOL 32, WINDOW_FRAME 32, BRASS 64, IVORY 48
    (floor), EBONY_KEY 64 (floor), BONE 40, KEY_FRONT 32, KEYLEVER 24, ACTION_WOOD 24, FELT 12,
    DAMPER_TOP 16, CLOTH_RED 12, LEATHER 16, QUILL 32; lit F0 0.04; LACQUER 96 / 0.05 / floor /
    rim; STEEL 64 and COPPER 48 (STRING, F0 0.5); GILT, EDGE_GILT, MIRROR_FRAME ribbon with
    emissive 0.2; GILT_EMISSIVE decal emissive 1.0; SECTION_CAP sectionCap.
17. **`KonzertzimmerAcoustics`** exposes its seven `AcousticMaterial`s and `PLACEMENTS` by name;
    `GEOMETRY.fixtureFootprints` is empty (the fixtures are WP8's; its drawn geometry carries
    them).
18. **`Pal`** tokens hold the base colour; the §5.9 use multipliers (FLEMISH_PAPER × 0.6,
    HARPSI_SOUNDBOARD × 0.7, SOUNDBOARD shadowed ≤ 0.25) are applied where the colour is used.

### Stub signatures (the §2.3 behaviour paragraph named them without signatures)

19. `FakeClock(nanos: () -> Long = { System.nanoTime() })`: `setPerformance(generation, startUs, endUs =
    Long.MAX_VALUE)` (bumps the epoch), `play()`, `pause()`, `seek(us)` (bumps the epoch),
    `setRate(r)`, `setRegistration(mask)`, `songUsNow()`, `atEnd()`, `isPlaying`,
    `currentGeneration`. Song time stops at `endUs`.
20. `NullAudio(fake: FakeClock = FakeClock())`: keeps the last bank, key map, profile, quality,
    room and mix for tests; `onEnded` fires once per generation when `stats()` is polled after the
    end.
21. `SineBank(layers = 2, stops = 1, instrument = GRAND, generation = 1)`: **roots at keys 21 + 3i,
    i = 0..29** (the formula and Salamander's minor-third spacing; "3 roots per octave" in the
    plan's text is superseded). Regions `stops × layers × 30`, index
    `SineBank.regionOf(stop, layer, rootIndex, layers) = (stop·layers + layer)·30 + rootIndex`;
    unit id u = stop·layers + layer; `readyMask` starts at −1 (all ready). Every region: 2 s stereo
    at 48 kHz, 8 harmonics (1/n, below 20 kHz), onset at frame 96, **1.2 s decay time constant**,
    50 ms end fade, peak −3 dBFS; one waveform per root shared by all layers, stops and banks.
    `SampleReader.read` returns the number of frames that came from the region (the rest is
    zero-filled). `BankInfo`: kit "sine", `embeddedRoomDb` 30 (dry), `isStub` true, T60s from the
    profile defaults.
22. `KeyMapFixtures.forSineBank(layers, mode: KeyMapFixtures.Mode = HARD, stops = 1, readyMask =
    −1L)`: equal velocity splits (layer j from 1 + round(127·j/layers)); XFADE ±4 linear; stop 1
    (the 4′) sounds key + 12; gains 1; no releases or pedals.
23. `PerfFixtures.build(notes, sustain = EMPTY, soft = EMPTY, sostenuto = EMPTY, profile,
    generation, id = "fixture", title = null)`: all inputs in **file time**; the rules applied
    (fold, re-strike, legato hold on the harpsichord, latches, pedal noises, event order
    (evUs, type, arg), EV_END at the last key-up, bars every 2 s from the pre-roll) are listed in
    its KDoc.
24. `StubScoreCompiler()`: `synthetic(kind)` = `PerfFixtures.build(spec, sustain = cc64, soft =
    cc67, sostenuto = cc66, …)`, id `synth:<name>`; `compile()` recognises a twin by the movement
    id (`test:<name>`, `synth:<name>`) or by bytes given to `registerTwin(bytes, kind)`.
25. `SineCore(sampleRate = HK.SR, maxVoices = 128)`; its prepared key-map token is
    `SineCore.Tables`; cosine phase so the onset frame carries the full amplitude.
26. `PassThroughDsp` is an object: `create(sampleRate = HK.SR): DspSet`, nested classes
    `Resonance`, `Room`, `Soft`, `Master`, and `pade(x)` = x(27 + x²)/(27 + 9x²), ±1 beyond |x| = 3.
27. `FixedRoom.PLAYER`: the literal below. `StubRoomDesigner` is an object returning it.
28. `StubVenue()` (a class; `StubVenue.GEOMETRY` = `KonzertzimmerAcoustics.GEOMETRY`),
    `StubFlames`, `StubScenes()`, `StubInstrumentScene(id)`, `StubAnchors(profile, pitch)`,
    `StubMechanics()`, `StubLibrary(readAsset, scoresDir, twinNames)`, `StubUi()` with
    `StubOverlayState(title, line)`, `MemSettings()`, `StubKits(post)`.
29. App: `StubGlHost(ctx) : GLSurfaceView, GlHost` and `StubOverlay(ctx) : OverlayHost`.

### Day-0 subset of `mesh/MeshBuilder` (WP7 owns the file from its first merge)

30. `MeshBuilder(layout, capacityVerts = 1024)`: `part(slot, lane)`, `color(rgb, a = 1f)`,
    `vertex(pos, nrm, uv): Int` and `vertex(px, py, pz, nx, ny, nz, u, v): Int`, `tri(a, b, c)`,
    `quad(p0, p1, p2, p3): Int` (CCW from the front, normal (p1 − p0) × (p3 − p0), u along p0→p1
    and v along p0→p3 in metres), `box(x0, y0, z0, x1, y1, z1)` (axis-aligned), `build(name,
    material, skin, levelMask, viewMask, clipped, program, drawSlot, texture = null, fadeNearM =
    0f, fadeFarM = 0f): List<BakedMesh>` (split above 65,535 vertices, names `name#1`, `name#2` …),
    `vertexCount`, `triangleCount`. STRING vertices pack `nrm` as the string direction and `uv` as
    (t, side).

### Bodies

31. Working bodies in contracts-v1: everything except **`AudioClock`** (keeps only the newest
    block and extrapolates at the nominal rate; no ring, fit, rejection or `clockMiss` yet) and
    **`VisualClock`** (passes the raw time through, held monotone, with simple exposure windows).
    Both get their §2.5 implementations and the §7.2 WP0 tests in contracts-v1.1 (bodies only).

### Tested with this entry (JVM, `tools/gw :core:test`)

32. `PedalCurve.nextCrossing` rounds an interpolated crossing to the **nearest µs** (float levels
    such as 0.33f would otherwise land 1 µs late under a ceiling).
33. JVM tests shipped with contracts-v1: PhysicalCurvesTest, TuningProfileTest, PedalCurveTest
    (cursor = binary search at 10⁵ times incl. backward steps; bind/advance allocate nothing),
    PerformanceHelpersTest, SmallPrimitivesTest (Playlist, QualityLadder, ThermalPolicy's
    37 → 39.1 → 42.2 → 44.1 → 43.0 → 42.4 → 41.0 → 40.4 °C sequence, Conventions for the grand's
    Player pose, KonzertzimmerAcoustics areas and T60, HeadPose, Pal, MaterialTable), RingsTest
    (CommandRing 2·10⁶ items SPSC without loss or reordering, drops counted, drain allocation-free;
    EnergyRing selection, misses and no torn reads under a concurrent writer; VoiceCursorBoard),
    StubContractTest (every synthetic kind × instrument passes `testutil/PerformanceValidator`;
    the §4.5 table; folds, latches, pedal noises, legato hold; twin recognition; SineCore onset
    exact at frame 48,000 (rate 1) and 96,000 (rate 0.5), held across a pause, −12 dBFS RMS and
    lane 39 = 0.25 at v127, allocation-free render, one end; SineBank and KeyMapFixtures;
    NullAudio's single onEnded), MeshBuilderTest (winding agrees with unit normals, one-hot lanes,
    the 65,535 split with unsigned indices, the stub keyboard).
34. `testutil/PerformanceValidator` (core test fixtures, WP0) is available to every WP's tests;
    `AllocProbe.assertNoAllocation` runs the block once as warm-up before measuring it.

### `FixedRoom.PLAYER` generator (the §3.12 numbers, run once)

```python
import math
V = 469.7
mats = {'oak': [.15,.11,.07,.06,.06,.07,.07], 'plaster': [.14,.10,.06,.05,.04,.03,.03],
        'wood': [.25,.15,.10,.08,.07,.07,.07], 'mirror': [.08,.06,.04,.03,.02,.02,.02], 'canvas': [.10]*7,
        'silk': [.07,.31,.49,.75,.70,.60,.60], 'door': [.14,.10,.06,.08,.10,.10,.10]}
surf = [(0,84.00,'oak'),(1,108.60,'plaster'),(2,29.76,'wood'),(2,13.26,'mirror'),(2,5.28,'canvas'),
        (3,25.35,'wood'),(3,17.55,'silk'),(3,5.40,'mirror'),(4,25.22,'wood'),(4,4.80,'door'),(4,6.78,'canvas'),
        (5,25.22,'wood'),(5,4.80,'door'),(5,6.78,'canvas')]
people, ps = 18, [.30,.40,.50,.55,.60,.60,.60]
air = [.0001,.0002,.0005,.0010,.0024,.0062,.0215]
T = [0.161*V/(sum(a*mats[m][b] for _,a,m in surf) + people*ps[b] + 4*air[b]*V) for b in range(7)]
def alpha(p, b):
    s = [(a, m) for pl, a, m in surf if pl == p]; return sum(a*mats[m][b] for a, m in s)/sum(a for a, m in s)
c, s = math.cos(math.radians(-90)), math.sin(math.radians(-90))
def room(p): return [-1.0 + p[0]*c + p[2]*s, p[1], -1.9 - p[0]*s + p[2]*c]
ear, src = room([0, 1.20, 0.55]), room([0, 0.90, -1.00])
dist = lambda a, b: math.dist(a, b); yaw = lambda d: math.atan2(d[0], -d[2])
dd = dist(ear, src); fwd = yaw([src[i]-ear[i] for i in range(3)])
planes = [0, 5.3, -4.0, 4.0, 5.25, -5.25]; axis = [1, 1, 2, 2, 0, 0]
def refl(p, pl): q = list(p); q[axis[pl]] = 2*planes[pl] - q[axis[pl]]; return q
for t in [[0],[1],[2],[3],[4],[5],[0,1],[1,0],[2,3],[3,2],[4,5],[5,4]]:
    p = src
    for pl in t: p = refl(p, pl)
    di = dist(ear, p); g = dd/di
    for pl in t: g *= math.sqrt(1 - alpha(pl, 2))
    th = (math.sin(yaw([p[i]-ear[i] for i in range(3)]) - fwd) + 1)*math.pi/4
    print(max(1, round((di-dd)*48000/343)), g*math.cos(th), g*math.sin(th), all(alpha(pl, 5) < 0.15 for pl in t))
rc = 0.057*math.sqrt(2*V/((T[2]+T[3])/2)); print(T, dd/rc)
```
