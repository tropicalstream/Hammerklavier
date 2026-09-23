# `map.json` (schema 2) and `env.bin`: the normative field table

**Frozen with `contracts-v1` (2026-09-22).** Copied from PLAN §6.6. WP11's Python validator
(T11.3) and WP4's `KitMapCodec` (T4.1) are both written from this file; neither may accept a field
or value this table does not define. Changes go through `docs/contracts-changelog.md` (approved by
WP0) under the growth rules of PLAN §7.1: fields are only added, as optional, never removed or
re-typed.

`map_fixture.json` + `env_fixture.bin` (a 3-root, 2-layer toy kit, roots 58/60/62, ET, zero shape)
ship with it on day 1 under `core/src/test/resources/wp11/`.

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

## Notes that bind both sides (from PLAN §2.3 and §3.5)

- **`readyMask`** (in `LoadedBank` and `KeyMap`): bit u = unit id u; ids 0..61 are sustain units,
  62 the releases unit, 63 the pedals unit. It is informational in `LoadedBank`; the `KeyMap`
  snapshot is the only gate the engine uses.
- **Levels:** `envDb` (from `env.bin`) is the *normalised* region's 10 ms RMS in dBFS, without
  `gainDb`; `KeyMap.gain` = 10^(gainDb/20) × seam trim × fallback trim.
- **`env.bin` bytes:** one byte per 10 ms from region frame 0, value = −dBFS × 2 (0..255), 255
  past the end (`LoadedBank.envByte`).
- **Units vs regions:** `regions[].streamStart` is the frame of region frame 0 inside its unit's
  decoded stream; `units[].frames` is the decoded stream length.
