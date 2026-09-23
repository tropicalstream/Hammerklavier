# How the instruments move and sound: numbers for the simulation

Research reference for Hammerklavier (RayNeo X3 Pro). Topic: the numbers needed to animate keys, pedals,
hammers, dampers, jacks and strings from MIDI note and CC events, and to make the sample player behave like
the real mechanism. Compiled 2026-09-22.

**How claims are tagged**

- **[V]** verified: read in the cited source (quoted or paraphrased closely).
- **[D]** derived: computed by this report from [V] numbers. The arithmetic is shown.
- **[U]** UNVERIFIED: an engineering estimate, a secondary source I could not confirm, or a value I remember
  but could not reach online. Treat it as a tunable default.

Conventions: MIDI note n (A0 = 21, middle C = C4 = 60, C8 = 108). `t_on` is the MIDI note-on time. HV is the
hammer velocity at the string, in m/s. Times are in ms unless marked otherwise.

---

## 0. The one rule that drives the scheduler

**In a MIDI file, note-on is the sound onset, that is, hammer-string contact.** On the Bosendorfer SE, the
note-on is the moment of the trip point at the string. The hammer velocity is the average over the last
approximately 5 mm of hammer travel [V: Goebl & Bresin 2003]. Goebl et al. 2005 define hammer-string contact
as the instant that "conceptually" matches the MIDI note-on [V]. Hand-sequenced classical MIDI files are
authored the same way: the timestamp is when you hear the note.

A real key starts moving **20 to 230 ms before the sound** [V: Goebl 2005, "travel times ranged from 20 ms to
around 200 ms (up to 230 ms on the Steinway)"]. The player reads the file ahead, so every animation (key, wippen,
hammer, damper) and every pre-onset noise (finger-on-key, see section 9) must be **scheduled from t_on backwards**.
At 30 fps one frame is 33 ms. A fortissimo key therefore goes from rest to the keybed in less than one frame,
and a pianissimo key takes about 7 frames. Evaluate positions analytically at each frame time. Do not tween
per event.

---

## 1. MIDI velocity to hammer velocity to timing

### 1.1 Velocity to hammer velocity

- **Measured fit, Yamaha Disklavier grand** [V: Goebl & Bresin 2001, MOSART, Fig. 3]:
  `MIDI velocity = 57.96 + 71.3 * log10(HV)`. Inverted: **`HV = 10^((v - 57.96) / 71.3)` m/s**.
- **Bosendorfer SE cross-check** [V: Goebl 2005, section V]: "typical ... 40 and 60 MIDI velocity units (0.7 to
  1.25 m/s)". The Disklavier fit gives 0.56 and 1.07 m/s at those velocities, which is close [D].
- **Physical range** [V: Goebl 2005]: the softest tone is 0.18 m/s (50 dB peak SPL) and needs a pressed touch.
  The loudest is 6.8 m/s (110.4 dB) and needs a struck touch. A pressed touch does not reach much above
  4 m/s. The maxima per key were C1 6.0 to 6.8 m/s and G6 6.6 to 7.8 m/s, because lighter treble hammers reach
  higher speeds.
- Askenfelt and Jansson describe "about 5 m/s" as forte and "about 1 m/s" as piano [V: KTH lecture, "motions"].
  The brief's figure of "0.5 to 6 m/s" is consistent with these.
- **Loudness per velocity step** [D]: peak SPL runs 50 to 110.4 dB over HV 0.18 to 6.8 m/s. That is 60.4 dB over
  1.577 decades, about **38 dB per decade of HV**. With 71.3 velocity units per decade, this is about **0.54 dB
  per MIDI velocity unit**. Goebl & Bresin 2001 report that SPL is roughly linear in MIDI velocity between 40 and
  100 [V]. Use 0.54 dB per step only for small gain trims inside one sample velocity layer. The layers themselves
  carry the timbre change.
- Higher notes are louder than lower notes at the same MIDI velocity [V: Goebl & Bresin 2003, Fig. 7].

### 1.2 Travel time, key-bottom time and free flight

Goebl, Bresin & Galembo 2005 (JASA 118(2):1154) fitted power curves to more than 2300 tones on three grands
[V, equations copied from Figs. 6 and 7 and Table I]. Times are relative to hammer-string contact:

| Piano | travel time, pressed | travel time, struck | key-bottom, pressed | key-bottom, struck | free flight, pressed | free flight, struck |
|---|---|---|---|---|---|---|
| Steinway C | 98.57 HV^-0.7147 | 65.19 HV^-0.7268 | 19.09 HV^-0.3936 - 12.30 | 59.57 HV^-0.1131 - 51.19 | 1.63 HV^-1.403 | 3.04 HV^-1.581 |
| Yamaha Disklavier | 89.41 HV^-0.5959 | 57.43 HV^-0.7748 | 14.63 HV^-0.4158 - 11.05 | 10.15 HV^-0.6825 - 3.74 | 2.78 HV^-1.266 | 5.27 HV^-1.384 |
| Bosendorfer SE290 | 89.96 HV^-0.5595 | 58.39 HV^-0.7377 | 11.59 HV^-0.4497 - 9.98 | 13.96 HV^-0.3559 - 10.15 | 3.21 HV^-1.353 | 4.32 HV^-1.404 |

- **Travel time (tt)** is measured from finger-key contact to hammer-string contact.
- **Key-bottom time** is t_keybed - t_on. Positive means the key reaches the keybed **after** the sound.
  Measured extremes are +35 ms (Steinway) or +39 ms (Bosendorfer) for very soft notes, and -4 ms for very hard
  ones [V]. The paper's prose calls the typical range "3.5 to 0.5 ms before hammer-string". Its own Bosendorfer
  pressed curve gives +3.6 to +0.5 ms, which is after, over 0.7 to 1.25 m/s [D]. Trust the curve.
- **Free flight** runs from escapement to string. It is almost 0 for loud notes and up to 20 ms for very soft
  notes. For the Steinway it is almost zero above HV 1.5 m/s [V].
- **Pitch has no effect.** "The travel time curves were independent of pitch although lower keys have much
  greater hammer mass" [V].
- Touch changes timing more than the piano maker does. At the same HV, a struck key sounds 30 to 40 ms sooner
  than a pressed key [V].
- Askenfelt and Jansson, for comparison [V]: key start to key bottom takes about 25 ms at forte (5 m/s) and
  160 ms at piano (1 m/s). The hammer reaches the string 12 ms before key bottom at 1 m/s and 3 ms after key
  bottom at 5 m/s. A 3 mm change in blow distance shifts timing about as much as going from mezzo forte to piano
  [V: KTH "keybott"].

**Recommended model** [D]: use the Steinway fits for the grand. Blend from pressed to struck touch with
s = smoothstep(2.0, 4.5, HV). This is needed because notes softer than about 2 m/s can only be pressed and notes
above about 4 m/s can only be struck. Clamp HV to [0.25, 7.0] and tt to [20, 230] ms.

| vel | HV m/s | struck blend s | tt (key starts at t_on - tt) | key-bottom vs t_on | free flight |
|---|---|---|---|---|---|
| 20 | 0.29 | 0 | 230 | +18.6 | 9.1 |
| 30 | 0.41 | 0 | 188 | +14.9 | 5.8 |
| 40 | 0.56 | 0 | 149 | +11.7 | 3.7 |
| 50 | 0.77 | 0 | 118 | +8.8 | 2.3 |
| 64 | 1.22 | 0 | 86 | +5.4 | 1.2 |
| 70 | 1.48 | 0 | 75 | +4.1 | 0.9 |
| 80 | 2.04 | 0 | 59 | +2.1 | 0.6 |
| 90 | 2.81 | 0.25 | 43 | +0.8 | 0.4 |
| 100 | 3.89 | 0.85 | 26 | -0.3 | 0.3 |
| 110 | 5.37 | 1 | 20 | -1.9 | 0.2 |
| 127 | 7.0 (clamped) | 1 | 20 | -3.4 | 0.1 |

```kotlin
fun hammerVelocity(vel: Int): Float =                       // m/s, Goebl & Bresin 2001 Disklavier fit
    Math.pow(10.0, (vel - 57.96) / 71.3).toFloat().coerceIn(0.25f, 7.0f)
private fun smooth(a: Float, b: Float, x: Float): Float {
    val t = ((x - a) / (b - a)).coerceIn(0f, 1f); return t * t * (3 - 2 * t) }
fun keyTravelMs(vel: Int): Float {                          // finger-key -> hammer-string (Steinway C, Goebl 2005)
    val hv = hammerVelocity(vel); val s = smooth(2.0f, 4.5f, hv)
    val pr = 98.57f * Math.pow(hv.toDouble(), -0.7147).toFloat()
    val st = 65.19f * Math.pow(hv.toDouble(), -0.7268).toFloat()
    return ((1 - s) * pr + s * st).coerceIn(20f, 230f) }
fun keyBottomRelMs(vel: Int): Float {                       // + = key reaches keybed after the sound
    val hv = hammerVelocity(vel).toDouble(); val s = smooth(2.0f, 4.5f, hv.toFloat())
    val pr = 19.09 * Math.pow(hv, -0.3936) - 12.30
    val st = 59.57 * Math.pow(hv, -0.1131) - 51.19
    return ((1 - s) * pr + s * st).toFloat() }
fun freeFlightMs(vel: Int): Float {
    val hv = hammerVelocity(vel).toDouble(); val s = smooth(2.0f, 4.5f, hv.toFloat())
    return ((1 - s) * 1.63 * Math.pow(hv, -1.403) + s * 3.04 * Math.pow(hv, -1.581)).toFloat().coerceAtMost(20f) }
```

Upright, fortepiano and harpsichord have no comparable published fits [U]. Use the grand curves with multipliers:
upright tt x 1.0 to 1.1, Viennese fortepiano tt x 0.6 to 0.8 (shallower 4 to 6.5 mm dip, far lighter hammers),
harpsichord see section 6.

### 1.3 Key motion shape

- **Pressed or legato touch:** the key accelerates smoothly with no pause [V: KTH "motions"]. Use an ease-in,
  keyDisp = dip * u^1.8 with u = (t - t_start) / (t_bottom - t_start) [U exponent].
- **Struck or staccato touch:** the key "exhibits temporary stop at about a third of the key travel" after the
  finger impact [V]. Use a near-linear profile with a short stall at about 0.33 dip [U shape].
- **Peak key speed:** 0.3 to 0.5 m/s at mezzo forte, and "seldom exceed[s] 1 m/s" at forte [V].
- **Hammer versus key travel:** in the same time the hammer moves about 5 times as far as the key front
  [V: KTH "motions"]. Regulation numbers agree: 47 mm blow over (10 mm dip - about 1 mm aftertouch) gives 5.2 [D].

---

## 2. Keyboard geometry (modern)

| Item | Value | Tag / source |
|---|---|---|
| Octave span | 164 to 165 mm | [V] Wikipedia "Musical keyboard" |
| White key width at the front | about 23.5 mm (164.5 / 7) | [V] |
| Black key width | about 13.7 mm on average (164.5 / 12 = 13.71) | [V] width, [D] ratio |
| White key head (the part in front of the sharps) | 50 to 52 mm (Steinway, Bosendorfer). Stein fortepiano: 35 to 36 mm | [V] Cole via Kobb |
| Visible white key length | about 145 to 150 mm | [U] |
| Visible black key length | about 95 to 100 mm (visible white length minus head) | [U]/[D] |
| Black key height above the naturals | 12 mm (+0.5/-0) Yamaha G1 to C7; 12.5 mm G1 forum spec | [V] 88keys.sg / Yamaha |
| Key height, keybed to top of natural | 63 mm (Steinway Hamburg S to B), 65 mm (C, D), 64 mm (Yamaha), 66 to 68 mm (Kawai) | [V] |
| Key dip, white key at the front | Steinway 0.400 in (10.16 mm), range 0.390 to 0.420 in; Yamaha 10 mm; Kawai 10.1 to 10.3 mm | [V] |
| Aftertouch (travel past let-off) | 1/16 to 3/64 in (1.2 to 1.6 mm) Steinway; 0.8 to 1.2 mm Hamburg; 1.0 to 1.5 mm uprights | [V] |
| Key lever, grand | example: front to balance-rail hole 259.5 mm, front to capstan 398 mm; key ratio 0.50 typical (acceptable 0.47 to 0.60) | [V] Reyburn |
| Total grand key length | about 480 mm | [U] (blog source) |
| Static touch weight | down weight 46 to 52 g, up weight 20 g or more (Steinway) | [V] 88keys.sg |
| Key rotation at full dip | atan(10 / 259.5) = **2.2 degrees** about the balance rail | [D] |

**Rendering recipe.**

- Rotate each key about its own balance point. Do not translate it.
- Give black keys the same angle. At the front of a sharp, about 50 mm behind the white-key front, that gives
  about 8 mm of travel [D]. Technicians regulate sharps separately, so treat the sharp dip as tunable [U].
- For the key tails between the sharps, split each group evenly: C to E gives 5 tails over 70.5 mm (14.1 mm
  each), and F to B gives 7 tails over 94 mm (13.43 mm each). This is the usual two-group layout [U as an
  industry norm]. Draw black key tops narrower (about 9.5 to 11 mm) than their base [U].
- The front of a depressed key sits dip * 1 mm lower. With a dip of 10 mm on a 640x480 display, the travel is
  visible only in a close-up or a low camera. Add a small shadow or specular change so the motion reads.

---

## 3. Grand action (Erard double escapement)

### 3.1 The chain (what each part does, in order) [V: Goebl 2005 intro; Steinway Ch.2; KTH "timing"]

1. **Key** pivots on the balance rail. The **capstan** behind the balance point lifts the **wippen**.
2. The wippen carries the **jack** and the **repetition lever**. The jack pushes the **knuckle** (roller) under
   the hammer shank, and the **hammer** rotates up about its shank-flange center pin.
3. **Let-off.** The jack tender meets the **let-off button** (escapement dolly). The jack slips out from under
   the knuckle and the hammer flies free.
4. **Strike.** Contact lasts about 4 ms in the bass and less than 1 ms in the top treble. It gets shorter at
   louder dynamics because the felt stiffness is nonlinear [V: KTH "stricont"].
5. **Rebound.** If the key is still down, the **backcheck** on the key tail catches the hammer tail
   ("checking").
6. **Drop.** At let-off the hammer "drops" slightly, which is the first escapement. The repetition lever, stopped
   by the **drop screw**, holds the knuckle.
7. **Repetition.** When the key rises about halfway, 2 to 4 mm below its surface, the jack resets under the knuckle
   while the repetition spring holds the hammer up. The key can then strike again with only 6 to 8 mm of travel
   [V: Goebl 2005 section V]. A well-regulated grand can reset when the key has risen as little as one third
   [V via the US6153819 search summary].
8. **Repetition speed:** "a maximum of roughly 15 times per second in grands, versus seven times per second in
   uprights" [V: Yamaha Hub]. That is a period of about 67 ms on a grand and about 143 ms on an upright [D].

### 3.2 Regulation numbers (use these for geometry)

| Item | Steinway (Hamburg / NY) | Yamaha G1 to C7 | Kawai | Source |
|---|---|---|---|---|
| Blow distance (hammer at rest to string) | 47 mm; 1 3/4 in for S/M/L/O/A/B, 1 7/8 in (47.6 mm) for C/D | 48 mm | 46 mm (range 42 to 47 mm) | [V] |
| Let-off (hammer to string at escapement) | 1.5 / 1.0 / 1.0 mm bass/mid/treble (Hamburg); NY 1/32 to 1/16 in | 3 mm bass to 1.5 mm treble | 2.0 / 1.5 / 1.0 mm | [V] |
| Drop below let-off | at most 1/16 in (1.6 mm); Hamburg 2.0 mm | let-off + 2.0 mm | 2.0 / 1.5 / 1.0 mm | [V] |
| Backcheck (hammer caught this far below string) | 15 mm (Hamburg); 1/2 to 5/8 in (NY) | 16 mm bass to 14 mm treble | 15 mm | [V]; Igrec: 12 to 15 mm |
| Initial damper lift | when the hammer is at half its travel (about 23 to 24 mm) | "24 mm (half of blow distance)" | - | [V] Yamaha; Redekop |
| Wippen center pin to shank center pin | - | 112.5 mm | - | [V] |
| Height of repetition lever above jack | - | 0.2 mm | - | [V] |

- **Hammer strike radius** (shank center pin to strike point, the "hanging distance"): about 5 1/4 in (133 mm);
  about 5 1/8 in to the center of the molding. Knuckle about 16 to 18 mm from the center pin [U: PTG list
  archive / forum, not read directly].
- **Hammer rotation** over the full blow distance: 47 / 133 rad, about **20 degrees** [D]. At rest the shank lies
  roughly parallel to the strings, 47 mm below them, and the crown meets the string moving almost vertically
  [U geometry].
- **Hammer mass:** "largest bass hammers may weigh around 11 grams. The smallest treble hammers ... as little as
  3.5 grams" [V: Conklin, KTH]. Interpolate log-linearly: `mass(n) = 11 * (3.5/11)^((n-21)/87)` g [D].
- **Strike point:** bass about 1/8 of the speaking length, falling gradually to about A4 (note 49), then more
  steeply to 1/12 to 1/17 in the top treble [V: Conklin "whereshould"]. The text's historical ideal is 1/7 to 1/9.
- **Hammer shank motion:** a slow "backwash" at about 50 Hz and a "ripple" at about 400 Hz [V]. Both are
  invisible at 30 fps. Ignore them.

### 3.3 Hammer and key state machine (per key)

```
REST --(t_on - tt)--> RISING      key follows keyDisp(t); hammer h = 5.0 * keyDisp, capped at blow - letOff
RISING --(t_on - freeFlight)--> FREE     hammer coasts at HV (gravity -9.81 m/s^2 is negligible except pp)
FREE --(t_on)--> CONTACT         h = blow; lasts contactMs(n) = 4.0 * (0.2)^((n-21)/87) ms [D from KTH]
CONTACT --> REBOUND              hammer returns down at about 0.3 to 0.6 * HV [U]
REBOUND --> CHECKED (key still down)     hammer held at blow - backcheck (about 15 mm below string)
CHECKED --(note-off)--> RELEASING        key rises; at 50% rise the damper lands (grand); hammer lifts to the
                                         "drop" height on the repetition lever, then falls to rest
RELEASING --(next note-on arrives before the key is fully up)--> RISING from the current height
                                         (repetition: allowed once the key has risen at least 1/3 to 1/2)
```

- **Key return after release:** grand key, unbraked, about **35 ms** from bottom to rest; about 38 ms when braked
  by a solenoid [V: US6153819, player-piano patent]. Use an ease-out of 35 to 45 ms for a grand
  [V/U uprights 40 to 60 ms].
- **MIDI note-off** marks the start of the release. The grand damper lands at about the midpoint of the key's
  rise [D, from damper timing at half travel], so the sound starts to be damped about **15 to 20 ms** after
  note-off [D].

---

## 4. Dampers and pedals (grand)

### 4.1 Damper lift

- **From the key:** the damper starts to lift when the hammer is halfway to the string, about 23 mm of a 46 mm
  blow. "Damper lifts at 1/2 hammer travel and not 1/2 key dip" [V: Redekop]. Yamaha specifies 24 mm
  (half the blow) [V].
- Damper head lift at full key dip is about 4 to 5 mm. This is estimated from the key-tail rise, about
  0.85 x dip, with the damper engaging from half travel [U/D].
- **From the pedal:** "Pedal lifts damper at one-third of its travel"; the "lift tray at rest should be about
  2 mm below underlevers" [V: Redekop]. The damper up-stop rail sits 0.5 mm (black keys) to 1 mm (white keys)
  above the underlevers [V]. A key lifts its damper further when the pedal is already down [V].
- **Half pedal:** the half-pedal zone is "much closer to the 'up' pedal position", where the dampers are only
  barely off the strings. At about half to three quarters of full depression the dampers are fully clear, and the
  piano sounds the same as at full pedal [V: Cincinnati Note, a pianist's blog].
- **Sustain pedal travel:** the numbers I found were unusable. One search summary gave about 1/4 in (6 mm) of
  free play before the dampers move [U]. Total tip travel is probably about 15 to 20 mm [U]. Pedal angle is
  atan(travel / about 200 mm lever), roughly 4 to 6 degrees [U].
- **Undamped top.** Steinway B has 20 undamped notes. A 6'4" Grotrian has 17. A Baldwin SD-10's last damper is
  key 68 (E6) [V: Piano World thread]. Kawai says the top ~18 notes are undamped on its grands and hybrids
  [V: Kawai FAQ]. Pianoteq exposes this as "Last damper: all keys with MIDI note number greater than this value
  have no damper" [V].
  **Default: last damper = MIDI 88 (E6) for the grand, 90 (F#6) for the upright** [D/U]. Some pianos use a
  bevelled top damper that damps only 2 of 3 strings, to smooth the transition [V: forum].

### 4.2 Sostenuto (grand middle pedal, CC66) [V: Wikipedia "Piano pedals"; Redekop]

The sostenuto "holds up only dampers that were already raised at the moment that it was depressed". A rail with
a blade catches the tabs on the raised dampers. Redekop's regulation: the blade sits at 45 degrees and about 2 mm
from the tabs at rest, and at 90 degrees when engaged. Steinway perfected it in 1874.

Implementation: on the CC66 rising edge, latch the set of notes whose dampers are currently lifted. Release the
latch on the falling edge. If the sustain pedal is down at that moment, all dampers are up and all are latched
[U: follows from the mechanism, not read in a source]. Notes above the last damper are unaffected.

### 4.3 Una corda (grand soft pedal, CC67) [V: Steinway Ch.2; Wikipedia "Soft pedal"; Euphonics 7.3]

- **Mechanism.** The whole keyframe and action shift to the right. The Steinway keyframe shift screw is set "so
  that when the keyframe is in its shifted position, the left string of the trichords is missed by the hammer".
  Some players prefer a partial shift, which only brings a different, unworn part of the felt into play.
- **Shift distance:** about one unison string spacing, roughly 2 to 3 mm [U; I found no published number].
  Animate the keyboard and action sliding right over about 60 ms [U].
- **Sound.** The loudness difference "is not very great". The main effect is that the tone is "less percussive
  and dominated by the aftersound". The unstruck string is driven through the bridge, which lengthens the
  aftersound [V: Euphonics 7.3]. The struck strings also meet unworn, softer felt and sound duller [V].
- Use una corda samples if the pack has them. Otherwise apply about -2 to -4 dB, a gentle high-shelf cut and a
  slightly raised late-decay level [U].

### 4.4 CC mapping (grand)

```kotlin
// p = pedal travel 0..1 (CC/127). Switch-type files send only 0 or 127.
fun damperLiftByPedal(p: Float) = ((p - 0.33f) / 0.67f).coerceIn(0f, 1f)          // [V] onset at 1/3
fun pedalDampingFactor(p: Float) = 1f - smooth(0.33f, 0.55f, p)                    // 1 = fully damping [U] zone width
fun keyDamperLift(keyFrac: Float) = ((keyFrac - 0.5f) / 0.5f).coerceIn(0f, 1f)     // [V] onset at half travel
// A string is free if the note is above lastDamper, or the key lift > 0, or the pedal is past its zone,
// or sostenuto latched it. Otherwise it is damped with strength pedalDampingFactor(p).
```

Animate a 0 to 127 jump over about 50 to 80 ms of pedal motion, not instantaneously [U].

---

## 5. Upright action

| Item | Value | Source |
|---|---|---|
| Blow distance | 1 3/4 to 1 7/8 in (45 to 47 mm) in studio uprights; as little as 1 1/2 in (39 mm) in spinets | [V] Igrec; Spurlock: "1-3/4 in or more" |
| Key dip | 3/8 in and up (9.5 mm or more) for consoles and larger; 10.5 mm for most Asian pianos; 7/16 in for spinets | [V] Spurlock |
| Let-off | 1/8 in (3.2 mm) | [V] Spurlock |
| Checking | 5/8 in (16 mm) from the strings | [V] Spurlock |
| Aftertouch | 0.040 to 0.060 in (1.0 to 1.5 mm) | [V] Igrec |
| Damper lift from the key (spoon on the wippen) | the damper just moves when the hammer is at 1/2 blow | [V] Spurlock |
| Pedal damper lift | limited to about the same as the spoon lift | [V] Spurlock |
| Repetition | about 7 per second | [V] Yamaha Hub |

- **The hammer throws horizontally** at vertical strings. The hammers are returned by springs, not gravity
  [V: Yamaha Hub], so there is no double escapement on most uprights and repetition is slower. The jack needs a
  near-full key return before it resets under the butt [V: Spurlock "lost motion"; U quantitatively].
- **Dampers** press on the strings from the hammer side under spring force. On modern "underdamper" actions they
  sit below the strike line [U placement]. The pedal pushes a lift rod that swings all damper levers away from
  the strings.
- **Soft pedal** raises the hammer rail so the hammers rest "halfway to the strings". Pedal travel is blocked at
  one half of the blow distance [V: Spurlock]. Tone colour barely changes [V: Wikipedia].
  - Model it as a shorter throw with about -3 to -6 dB [U].
  - Moving the hammers forward creates lost motion: the key moves slightly before the hammer engages. Animate the
    hammer rail moving about 22 mm toward the strings [D: half of 45 mm].
- **Middle pedal variants** [V: Wikipedia "Piano pedals"; Yamaha Hub]:
  - the **practice or mute rail** (a felt strip drops between hammers and strings), which is the most common;
  - **bass-only sustain**;
  - a second half-blow pedal that latches;
  - a true sostenuto, rare outside Steinway and Bechstein.
  Default for the app: mute rail. Model it as a heavy low-pass plus about -15 to -20 dB [U].

---

## 6. Harpsichord

### 6.1 Mechanism [V: Fletcher & Beebe; CBH jack page; Dave Law; Sankey]

- **Jack parts:** each key carries one **jack** per choir. The jack holds a pivoting **tongue** returned by a
  spring (historically hog bristle, now wire or monofilament). The tongue carries a **plectrum** (crow or raven
  quill, now usually Delrin or Celcon). A **felt damper** in a slot on the jack rests on the string.
- **Pluck.** The key raises the jack, the damper leaves the string, and the plectrum bends the string until it
  slips off. The **jack rail** (padded) stops the jack. The key dip is set by adjusting that rail.
- **Release.** The jack falls by gravity. The tongue swings back so the quill flips under the string. The damper
  then "stifles the vibration so that the sound ceases in a fraction of a second".
- **Registers.** The upper guide (register) slides to put a whole row of jacks in or out of reach.
  - The **buff stop** is a batten of leather or felt pads pressed against the 8' strings beside the nut. It gives
    a mellow, lute-like tone.
  - The French **shove coupler** slides a manual to couple the two keyboards.
  - Taskin, in 1768, added **peau de buffle** (buff leather plectra) and **genouilleres** (knee levers) to change
    registration [V: Wikipedia "Pascal Taskin"].
- **Stagger.** The rows pluck one after another, not together: 4' first, then back 8', then front 8'. "A key-dip
  of 8 mm is enough to accommodate five staggered plucking heights" [V: Dave Law]. An unverified search summary
  of Beebe's paper gives about 2.6 mm (4') and 4.2 mm (8') of key travel before the pluck [U].
- **Key dip:** "8 mm on the lower manual, but only 6 mm the upper (measured at the front of the naturals)"; 6 mm
  for a single manual. The coupler has 1.5 mm of free play [V: Dave Law].
- **Keys and jacks** [V: Sankey]:
  - key levers about 25 cm long (single manual) and up to 50 cm (double);
  - key balance about 1:1 in the 18th century (1.35 for 16th-century Flemish);
  - jacks under 5 g (historical) and 6 g (plastic);
  - force to pluck typically under 150 g; one instrument was reduced from 160 g to 130 g with all registers on;
  - historical octave spans 156 to 174 mm; the author's instrument has 159 mm.
  An 18th-century French octave is about 6 1/4 in (159 mm) [V: Irvin].
- **Compass:**
  - late French double (Taskin): **FF to f''' = MIDI 29 to 89** (61 notes), with 8' 8' 4' and buff
    [V: Wikipedia];
  - "The largest harpsichords have a range of just over five octaves" [V];
  - the Ruckers reproduction in Fletcher & Beebe: G1 to D6.
- **Plucking fraction** (distance from the nut / sounding length): 0.45 down to 0.33 in the treble, about 0.09 in
  the extreme bass, and about 0.04 for a lute register [V: search summary of the Beebe and Fletcher literature;
  Fig. 8.4 shows the same trend]. The bass is plucked proportionally closer to the nut, which gives brighter bass
  and more uniform loudness [V].

### 6.2 Sound behaviour [V: Fletcher & Beebe]

- **Loudness does not depend on how hard the key is pressed** [V: Wikipedia; Fletcher]. On the Ruckers
  reproduction, measured at 2 m: 8' 70 +/- 5 dB(A), 4' 68 +/- 3, both together 72 +/- 5. A piano at mezzo forte
  gives about 80 dB(A), and the pianist controls roughly 70 to 90 dB(A).
- **Implementation:** ignore velocity for gain. At most add about +/-1 dB of humanising and a small timing effect
  [U]. Volume changes only through registration.
- **Decay to inaudibility** (key held) falls with pitch: about (1/f)^0.25 for the 8' choir and (1/f)^0.45 for the
  4'. The plot runs about 20 s in the bass to about 3 to 5 s in the treble [V slopes; U endpoints read off the
  figure]. The decay is two-stage: a fast initial decay, then slower beating between the two polarisation modes
  [V: "Decay patterns of harpsichord strings", JASA 39(6) abstract].
- **Release:** damping takes a "fraction of a second" [V]. Use a release T60 of 0.10 to 0.25 s, longest in the
  bass [U]. The jack's fall makes a faint click as the quill passes under the string, and the damper lands
  [U; include it if the sample pack has release samples].

### 6.3 What to do with a piano MIDI file on the harpsichord

- **CC64 (sustain):** a harpsichord has no pedal. By default, **ignore CC64**. Offer "legato hold" as a
  non-historical option that delays each note-off until the pedal lifts, capped at about 1.5 s [U].
- **CC67 / CC66:** ignore them, or map CC67 to the **buff stop** as a user option [U design].
- **Velocity:** use it only for animation lead time.
- **Pre-onset lead:** the key has to travel to the pluck point, about 2.6 to 4.2 mm of a 6 to 8 mm dip. Estimate
  the lead as 15 to 40 ms depending on velocity [U]. The jack rises about 1:1 with the key front, to its rail.
- **Notes outside FF to f''':** fold them by octaves into range [U design].

---

## 7. Early pianos (Bach to Beethoven)

| Instrument | Action | Compass | Stops / levers | Key / touch | Source |
|---|---|---|---|---|---|
| Silbermann fortepiano, 1746 to 1749 (Bach played these at Potsdam, 1747) | Copy of Cristofori's action; hammers on a separate rail, pushed up (**Stossmechanik**) | **FF to e''' (MIDI 29 to 88)** | **Hand stops**: dampers raised, una corda (the keyboard slides), "harpsichord" (Pantalon ivory strips) | two strings per note, iron [V: search summary] | [V] fortepiano.eu; Wikipedia (Silbermann) |
| Anton Walter, c. 1782 (Mozart bought a Walter c. 1782) | Viennese **Prellzungenmechanik**: the hammer sits in a *Kapsel* on the key, its beak catches under a sprung *Prelle* (escapement), and a backcheck catches it; the hammer head points toward the player | **FF to f''' (TMW c.1782)**; Mozart's piano: **FF to g''' (MIDI 29 to 91)** | **Knee levers**: damper lift and moderator. The TMW c.1782 instrument had hand levers splitting bass and treble dampers; Mozart's own piano may have had only hand levers originally and was altered c.1800 | 2 strings, 3 from a' upward; leather-covered hammers; pitch a1 = 430 (or 415) in reconstructions | [V] Greifenberger; Wikipedia "Anton Walter"; Robert Brown |
| Viennese pianos in general | as above | 5 octaves up to 1801 | knee lever for dampers; moderator (cloth between hammer and string) | **key dip 4 to 6.5 mm** (Steinway D: 10.5 to 11 mm); sounding c3 quietly: Stein about 3 mm at 23 g vs Steinway about 9 mm at 81 g (about 1:10 energy) | [V] Kobb, citing Michael Cole; CBH |
| English (Broadwood) | Stossmechanik with escapement and check, "deeper touch" | Broadwood 1817 (Beethoven's): 6 octaves, 2 pedals | pedals (una corda, damper) | louder, heavier | [V] Wikipedia "Fortepiano"; Beethoven sources |
| Erard 1803 (Beethoven's) | French/English | 5 1/2 octaves | 4 pedals | three strings per note | [V] search summary (Maene interview) |

- Knee levers came in "around 1765", replacing the hand stop, and Mozart praised them [V: Wikipedia
  "Piano pedals"].
- Viennese touch needed "only about a fourth" of the force of a modern piano [V: Wikipedia "Fortepiano"].

**MIDI mapping for the "early" instrument** [U design, historically motivated]:

- CC64 drives the **damper knee lever**. It is historically right for Mozart and Beethoven. Draw it as a lever
  under the keyboard that swings up about 15 to 25 degrees [U].
- CC67 engages the **moderator**: a cloth strip slides between hammers and strings, giving about -6 to -10 dB and
  a strong high cut [U].
- CC66 is ignored.
- Viennese dampers are less effective than modern ones, so the bass rings slightly after release. Use a damper
  T60 of about 1.5 to 2 times the modern grand value [U].
- Hammer-string contact is shorter and brighter with leather-covered hammers [U qualitative].

---

## 8. Strings

### 8.1 Counts, wound strings and overstringing

- **Strings per note:** one per note in the bass, two in the tenor, three in the treble [V: Wikipedia "Piano"].
  A typical split is bass (single) A0 to F1 (MIDI 21 to 29), tenor (bichords) F#1 to G2 (30 to 43), and treble
  (trichords) G#2 to C8 (44 to 108). "Just where these sections begin and end depends on the scale" [V: search
  summary of a technician source]. That gives 9 + 28 + 195 = **232 strings** [D]. The Steinway D is said to have
  trichords reaching into the bass, so its total is higher [U].
- **Wound strings:** bass strings are copper-wound on a steel core [V]. Treble wire is Swedish steel in "twelve
  whole and one-half sizes" on the Steinway D [V: Daynes].
- **Overstringing.** The bass strings run in a separate, higher plane on their own bridge, crossing over the
  tenor. This gives more length to the bass and a smoother tenor-to-bass transition [V: Wikipedia "Piano"].
  An upright is also overstrung, diagonally.
- **Longest string:** Steinway D, 79 1/4 in (201 cm) from agraffe to bridge [V: Daynes].
- **Total tension:** more than 20 tons (180 kN) on a modern grand [V: Wikipedia "Piano"].
- **Speaking lengths** of a mid-size grand used in a physics model: C2 1.23 m, C4 0.63 m, C7 0.10 m
  [V: Bensa et al. 2003, Table I]. The plain-wire section scales by about **1.85 per octave**, giving about 5 cm
  at C8 [D].

### 8.2 Per-register reference (grand)

The formulas are in section 11. Lengths below MIDI 44 are wound strings: interpolate from about 1.3 to 1.4 m at
the break to 2.0 m at A0 on a concert grand, or cap at about 1.1 to 1.3 m for a small grand or upright [U].

| Note | strings | damper | speaking length | hammer mass | contact | strike d/L | T60, one string, key held |
|---|---|---|---|---|---|---|---|
| A0 (21) | 1 | yes | about 2.0 m (D) | 11.0 g | 4.0 ms | 1/8 | about 30 s (capped) |
| C2 (36) | 2 | yes | about 1.2 to 1.6 m | 9.0 g | 3.0 ms | 1/8 | 28 s |
| C3 (48) | 3 | yes | about 1.17 m | 7.7 g | 2.4 ms | 1/8 | 13 s |
| C4 (60) | 3 | yes | 0.63 m | 6.6 g | 1.9 ms | 1/8 | 6.2 s |
| C5 (72) | 3 | yes | 0.34 m | 5.6 g | 1.6 ms | 1/8.5 | 2.9 s |
| C6 (84) | 3 | yes | 0.18 m | 4.8 g | 1.2 ms | 1/10.7 | 1.4 s |
| F6 (89) | 3 | **no** | 0.14 m | 4.5 g | 1.1 ms | 1/11.6 | 1.0 s |
| C7 (96) | 3 | no | 0.10 m | 4.1 g | 1.0 ms | 1/12.8 | 0.6 s |
| C8 (108) | 3 | no | 0.05 m | 3.5 g | 0.8 ms | 1/15 | 0.3 s |

- **T60** is derived from Bensa's loss terms. For the fundamental, sigma = b1 + b2 (pi/L)^2 and T60 = 6.91 / sigma
  [D]. That gives C2 27.6 s, C4 6.24 s and C7 0.61 s, which fits log-linear
  `T60(n) = 6.24 * 10^(-0.0275 (n-60))` s, capped at 30 s.
- This agrees with the often-quoted "about 30 s for the lowest note to 1/2 s for the highest" [U; source not
  found].
- Use these values for animating string vibration only. The samples carry the real decay. Better still, measure
  T60 from each loaded sample at startup.

### 8.3 Vibration behaviour

- **Two-stage decay** [V: Weinreich, KTH; Euphonics 7.3]:
  - The strings of a unison start in phase (the symmetric mode). Their bridge forces add, so the energy decays
    fast: this is the loud **prompt sound**.
  - Small differences in mistuning and hammer contact move energy into the antisymmetric mode. Its bridge forces
    cancel, so it decays very slowly: this is the **aftersound**.
  - Immobilising one string of a vibrating pair made the level "jump up by close to 20 dB".
- **Mistuning:** below about 0.3 Hz the strings lock to a common frequency and there are no beats; the mistuning
  sets the aftersound level instead. Above that, beats appear; the paper's example is 0.64 Hz [V]. Tuners leave
  small random mistuning in unisons [V: Kirk 1959, via Weinreich]. At C4, 0.3 Hz is about 2 cents [D].
- **Sustain pedal effect:** raising all dampers lengthens the decay of partials in the middle register, not in the
  bass or treble. It also distorts the two-stage decay and the beating [V: Lehtonen et al. 2007, abstract].
- **Part-pedalling:** a damper that only just touches the string gives three phases [V: Lehtonen, Askenfelt &
  Valimaki 2009, abstract]:
  1. free vibration;
  2. damper-string contact, with rapid decay and a timbre change from nonlinear amplitude limiting, strongest in
     the bass;
  3. free decay again, at a lower rate.
- **Spectrum by register:** C2 has 50 to 60 harmonics up to about 5 kHz; C4 has 20 to 30 up to about 7 kHz;
  C6 has fewer than 10 up to about 10 kHz [V: Russell, Penn State]. A bass hammer on C4 cuts the spectrum off near
  4 kHz [V: KTH].
- **String amplitude:** I found no measured value. The mid-register peak at forte is probably at most about 1 mm,
  and a few mm in the bass [U]. Igrec's worked example uses +/-1 mm [V as an illustration only]. Soundboard
  displacement at the bridge is about 6 micrometres at most, between 80 and 300 Hz [V: search summary of
  Askenfelt & Jansson].

### 8.4 How to visualise vibration plausibly (30 fps, VBO, one draw per string group)

Real frequencies (27 to 4186 Hz) alias badly at 30 fps, so do not animate the true waveform [D].

1. **Envelope band.** Draw each vibrating string as a thin, bright, translucent "lens". Its half-width is
   `w(x,t) = A(t) * g * sin(pi*x/L)`. Here A(t) is the envelope,
   `A0(HV) * (0.8*exp(-3t/tau) + 0.2*exp(-t/tau))`, with tau = T60(n)/6.91, and g is a visual gain of 5 to 20
   [U constants]. This reads like a motion-blurred string, which is what the eye sees in a real piano.
2. **Low "stroboscopic" wobble.** Add a single-mode sway at a visual frequency of 2 to 12 Hz, lower for longer
   strings. Give each string in a unison a slightly different wobble rate, which hints at beating.
3. **Strike pulse.** At t_on, send a short bright pulse outward from the strike point (d/L from the table) toward
   the agraffe and the bridge, fading over about 150 ms. It shows the traveling-wave physics
   [V: KTH "stringvib" describes the pulses reflecting inverted].
4. **Damping.** When the damper lands, scale A(t) by the damped decay (section 9, R3). A sympathetically
   resonating undamped string gets a faint band at about -30 dB of the struck note [U].
5. **Colour.** Black is transparent on the waveguide display. Use warm bright copper for wound bass and pale gold
   or steel for plain wire, and brighten the band with amplitude.

---

## 9. Sound behaviour a sample player must model

The feature list below is what the commercial benchmarks expose: Pianoteq 9, Garritan CFX and Synthogy Ivory II
[V from their manuals and pages]. Each rule notes whether the samples already contain the behaviour and gives a
concrete rule for the Kotlin AudioTrack engine. Keep everything table-driven, as the thermal rule requires.

| # | Behaviour | Evidence | Implementation rule |
|---|---|---|---|
| R1 | Velocity layers and timbre | CFX has 20 dynamic levels; Ivory II "up to 18 ... with Sample Interpolation" [V] | Pick layers by MIDI velocity, crossfade neighbours; trim within a layer at 0.54 dB per velocity step [D §1.1] |
| R2 | Hammer attack thump ("string precursor") | "characterizes the 'attack thump' ... independent of touch type" [V Goebl 2005] | Already in the samples |
| R3 | Damper release, register dependent | Pianoteq has "Damping duration"; the damper is least effective in the bass [V]; bass dampers cannot stop horizontal vibration alone [V Igrec] | On damper contact (note-off + about 15 ms, grand) apply a release envelope with `damperT60(n) = 0.12 + 1.2*((88-n)/67)^2` s for n <= 88 (C6 0.12 s, C4 0.33 s, C2 0.84 s, A0 1.3 s) [U: calibrate against the pack's release samples]. Notes above the last damper keep their natural decay |
| R4 | Key-release noise | Pianoteq "Key release noise"; early studies' "upper noises" when the key is released, e.g. the damper hitting the strings [V] | Play a release sample (key and damper landing) at note-off, level scaled by the note's age and velocity. Ivory triggers release samples "based on note length and velocity" [V] |
| R5 | Keybed thump | "Bodengerausch (keybed noise)" [V] | Optional quiet thud at t_on + keyBottomRelMs(v); louder for struck (ff) keys [U level] |
| R6 | Finger-key noise, struck touch only | the "touch precursor" "precedes the actual tone by 20 to 30 ms" and is present only when the key is hit from above [V] | For v >= about 95 schedule a soft click at t_on - 25 ms. This is possible only because the file is read ahead |
| R7 | Sympathetic resonance, keys held | "slight re-articulation of strings that are undampered ... when harmonically related notes are being played"; CFX applies it only with the sustain pedal up [V] | For each held, undamped note, a resonator tuned to it is excited by the other voices' partials that fall within a few cents [U]. Cheap: at most 16 two-pole resonators, fed by the dry mix at about -30 dB |
| R8 | Sustain resonance, pedal down | "level of sympathetic ringing when the sustain pedal is depressed" [V CFX]; pedal-down lengthens mid-register decay [V Lehtonen 2007] | While pedal-down, feed the mix into a small bank of string resonators (or a short "soundboard + open strings" feedback network) at about -24 to -30 dB, and slightly lengthen mid-register sustain [U]. Budget: at most 5% of one core |
| R9 | Undamped top and duplex scale | top ~18 to 20 notes always free [V]; Pianoteq "Duplex scale ... from the undamped string parts" [V] | Notes above the last damper never get R3; they also receive R7 energy regardless of the pedal |
| R10 | Re-striking a sounding note | physically the hammer hits a moving string [U]; repetition happens with the key partly up [V] | New voice starts; the old voice of the same key fades with tau about 60 ms when the pedal is up, or about 200 ms when it is down (bounds polyphony) [U standard sampler practice] |
| R11 | Sustain pedal noises | Pianoteq: "whoosh when all dampers rise together, as well as when they fall"; CFX: separate pedal noises for all 3 pedals [V] | Pedal-down noise at the moment the dampers pass the 1/3 point; pedal-up noise when they land. The level can follow pedal speed [U] |
| R12 | Half pedal (continuous CC64) | Pianoteq pedal is "progressive, it allows the so-called partial pedals"; Ivory has a "Half Pedaling response curve"; CFX lacks it [V] | Damping strength = pedalDampingFactor(p) (§4.4). Interpolate a voice's decay in log-T60 between free and damped [D] |
| R13 | Re-pedaling | CFX "Re-Pedaling ... sustains the release of a note rather than the attack" [V] | If the pedal goes down while a voice is in its damper release, freeze its release at the current level and continue the natural decay from there. Do not restart it |
| R14 | Una corda | real soft-pedal sample sets (Ivory, CFX) [V]; less percussive, more aftersound [V Euphonics] | Use una corda samples if present; otherwise §4.3 fallback |
| R15 | Sostenuto | §4.2 | Latch logic only; no extra DSP |
| R16 | Tuning and temperament | Pianoteq offers Werckmeister III, Meantone, Well temperament and others, plus Diapason (A) and stretching [V] | Per-voice playback-rate multiplier (§10). The samples already contain inharmonicity and stretch, so apply only offsets per pitch class |
| R17 | Stereo image and lid | Ivory "Lid Position"; CFX mic perspectives [V] | Out of scope for this topic; pan by key position, bass left |

---

## 10. Pitch and temperament

**Pitch standards**

- A = 415 Hz is the Baroque-ensemble convention [V: Wikipedia "Concert pitch"; Fletcher: "standard Baroque pitch
  A4 = 415 Hz"].
- French Baroque: Versailles chapel 1795 was A = 390 [V]. The HIP convention is 392 [U].
- A = 430 is the "Classical" convention. The Walter reconstruction was tuned to a1 = 430 Hz [V: Greifenberger].
  Measured historical forks vary widely: Handel 1740 was 422.5 Hz [V].
- A = 440 was recommended in 1939, adopted as ISO 16 in 1955, and reaffirmed in 1975 [V].

Offsets from A440 are 415: -101.27 cents, 430: -39.80, 392: -199.98 [D]. A pack recorded at A415 plays at A440
with a rate factor of 2^(101.27/1200) = 1.0602.

**Frequency of note n:** `f(n) = A_ref * 2^((n-69)/12) * 2^(off[n mod 12]/1200)`, with the offsets below.
Rate multiplier for a sample recorded at note n_s and A440 ET: `2^((n - n_s)/12) * (A_ref/440) * 2^(off/1200)`.

**Temperament offsets from 12-TET**, in cents, normalised so that **A = 0** (so A_ref stays exact) [D]. The
numbers were computed from the standard definitions: Pythagorean comma 23.460 cents, syntonic comma 21.506,
schisma 1.954. They reproduce Wikipedia's Werckmeister III and Vallotti tables [V check].

| Temperament | C | C# | D | Eb | E | F | F# | G | G# | A | Bb | B |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Equal | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| Werckmeister III (1/4 PC on C-G, G-D, D-A, B-F#) | +11.7 | +2.0 | +3.9 | +5.9 | +2.0 | +9.8 | 0.0 | +7.8 | +3.9 | 0 | +7.8 | +3.9 |
| Vallotti (1/6 PC on F-C-G-D-A-E-B) | +5.9 | 0.0 | +2.0 | +3.9 | -2.0 | +7.8 | -2.0 | +3.9 | +2.0 | 0 | +5.9 | -3.9 |
| Young II (1/6 PC on C-G-D-A-E-B-F#) | +5.9 | -3.9 | +2.0 | 0.0 | -2.0 | +3.9 | -5.9 | +3.9 | -2.0 | 0 | +2.0 | -3.9 |
| Kirnberger III (1/4 SC on C-G-D-A-E, schisma on F#-C#) | +10.3 | +0.5 | +3.4 | +4.4 | -3.4 | +8.3 | +0.5 | +6.8 | +2.4 | 0 | +6.4 | -1.5 |
| Kellner "Bach" (1/5 PC on C-G-D-A-E, B-F#) | +8.2 | -1.6 | +2.7 | +2.3 | -2.7 | +6.3 | -3.5 | +5.5 | +0.4 | 0 | +4.3 | -0.8 |
| Lehman 2005 "Bach" (1/6 PC F-C-G-D-A-E; 1/12 PC C#-G#-D#-A#; A#-F 1/12 wide) | +5.9 | +3.9 | +2.0 | +3.9 | -2.0 | +7.8 | +2.0 | +3.9 | +3.9 | 0 | +3.9 | 0.0 |
| 1/4-comma meantone (wolf G#-Eb) | +10.3 | -13.7 | +3.4 | +20.5 | -3.4 | +13.7 | -10.3 | +6.8 | -17.1 | 0 | +17.1 | -6.8 |

The same temperaments as cents above C, for reference: Werckmeister III is 0, 90.2, 192.2, 294.1, 390.2, 498.0,
588.3, 696.1, 792.2, 888.3, 996.1, 1092.2 [V matches Wikipedia].

**Suggested defaults** [U, musicological judgement]:

- Harpsichord: A415 with Werckmeister III or Vallotti. Offer 1/4-comma meantone for 17th-century repertoire and
  Lehman or Kellner as "Bach" options. The Lehman reading is disputed; label it "one proposal".
- Fortepiano: A430 with Vallotti or Young II.
- Grand and upright: A440 equal temperament.

---

## 11. Reference functions (Kotlin, all in one place)

```kotlin
object Mech {
    // --- geometry (grand) ---
    const val KEY_DIP_MM = 10.16f; const val AFTERTOUCH_MM = 1.0f
    const val BLOW_MM = 47f; const val LETOFF_MM = 1.5f; const val DROP_MM = 1.5f; const val CHECK_MM = 15f
    const val FRONT_TO_BALANCE_MM = 259.5f; const val HAMMER_RADIUS_MM = 133f          // [V], [V], [U]
    val KEY_MAX_DEG = Math.toDegrees(Math.atan((KEY_DIP_MM / FRONT_TO_BALANCE_MM).toDouble())).toFloat() // 2.24
    const val ACTION_RATIO = (BLOW_MM - LETOFF_MM) / (KEY_DIP_MM - AFTERTOUCH_MM)       // about 5.0 [D]
    fun hammerAngleDeg(hMm: Float) = Math.toDegrees(Math.asin((hMm / HAMMER_RADIUS_MM).toDouble())).toFloat()
    fun hammerMassG(n: Int) = 11f * Math.pow(3.5 / 11.0, (n - 21) / 87.0).toFloat()     // [D from V endpoints]
    fun contactMs(n: Int) = 4f * Math.pow(0.2, (n - 21) / 87.0).toFloat()                // [D]
    fun strikeRatio(n: Int) = if (n <= 69) 1f / 8f else 1f / (8f + 7f * (n - 69) / 39f)  // [V trend, D curve]
    fun stringsPerNote(n: Int) = when { n <= 29 -> 1; n <= 43 -> 2; else -> 3 }         // [V typical]
    fun speakingLenM(n: Int, lMax: Float = 2.0f) =
        (0.63 * Math.pow(1.85, -(n - 60) / 12.0)).toFloat().coerceAtMost(lMax)          // [D]; wound bass: interpolate
    fun freeT60s(n: Int) = (6.24 * Math.pow(10.0, -0.0275 * (n - 60))).toFloat().coerceAtMost(30f) // [D]
    fun damperT60s(n: Int, lastDamper: Int = 88) =
        if (n > lastDamper) Float.POSITIVE_INFINITY
        else 0.12f + 1.2f * ((88 - n) / 67f).coerceAtLeast(0f).let { it * it }             // [U] calibrate
    const val KEY_RETURN_MS_GRAND = 35f; const val KEY_RETURN_MS_UPRIGHT = 50f          // [V], [U]
    const val REPEAT_MIN_MS_GRAND = 67f; const val REPEAT_MIN_MS_UPRIGHT = 143f         // [D from V]
}
// Schedule for a note-on at tOn (ms) with velocity v:
//   tStart = tOn - keyTravelMs(v); tEscape = tOn - freeFlightMs(v); tBottom = tOn + keyBottomRelMs(v)
//   hammer h(t) = min(ACTION_RATIO * keyDisp(t), BLOW - LETOFF) until tEscape, then coast to BLOW at tOn
//   damper (grand) lifts when h > BLOW/2, i.e. keyDisp > about 4.7 mm; falls at 50% of the key's return
```

---

## 12. UNVERIFIED items (need a measurement or a better source)

1. Una corda shift distance, estimated 2 to 3 mm. No published number was found; it is set so the left string of
   each trichord is missed.
2. Grand sustain pedal: total tip travel (estimated 15 to 20 mm), the 1/4 in free-play figure, and the pedal angle.
   The "lift starts at 1/3 of travel" figure is verified.
3. Damper head lift heights (estimated 4 to 6 mm), and damper release T60 by register (the `damperT60` curve is an
   estimate). Calibrate both against the chosen sample pack's release samples.
4. Hammer strike radius (about 133 mm), rebound speed (0.3 to 0.6 HV), and upright hammer geometry.
5. Visible key lengths (about 150 mm white, 95 to 100 mm black), black key top width, and the sharp dip.
6. String vibration amplitude in mm, and the "30 s lowest to 0.5 s highest" decay claim. The Bensa-derived T60s
   are verified arithmetic on one model instrument, not a survey.
7. Harpsichord: pluck-point key travel (2.6 and 4.2 mm, from a 403-blocked paper), the absolute decay endpoints,
   release T60, and pre-onset lead time.
8. Fortepiano: the Walter damper behaviour and knee-lever angles, and travel-time multipliers. Mozart's piano's
   original configuration is itself contested (altered c. 1800).
9. Upright travel times and key return (no published fits); middle-pedal and soft-pedal level changes.
10. Exact string counts and section boundaries for any specific model, such as the Steinway D total.
11. Level constants for sympathetic and sustain resonance (-24 to -30 dB) and for pedal noises.

---

## 13. Sources

- Goebl, Bresin & Galembo (2005), "Touch and temporal behavior of grand piano actions", JASA 118(2):1154-1165.
  Preprint: https://ofai.at/papers/oefai-tr-2005-01.pdf (read in full)
- Goebl & Bresin (2003), "Measurement and reproduction accuracy of computer-controlled grand pianos", JASA
  114(4):2273. Preprint: https://ofai.at/papers/oefai-tr-2003-04.pdf
- Goebl & Bresin (2001), "Are computer-controlled pianos a reliable tool in music performance research?", MOSART:
  https://ofai.at/papers/oefai-tr-2001-27.pdf (source of the log fit)
- Goebl, Bresin & Galembo (2003), "The piano action as the performer's interface", SMAC 03:
  https://ofai.at/papers/oefai-tr-2003-15.pdf
- Askenfelt & Jansson, "From touch to string vibrations", KTH *Five Lectures on the Acoustics of the Piano*:
  https://www.speech.kth.se/music/5_lectures/askenflt/askenflt.html (timing, keybott, stricont, motions,
  stringvib, basmidtr)
- Conklin, "Piano design factors", KTH lectures:
  https://www.speech.kth.se/music/5_lectures/conklin/thehammers.html and .../whereshould.html
- Weinreich, "The coupled motion of piano strings", KTH lectures:
  https://www.speech.kth.se/music/5_lectures/weinreic/strings.html and .../mistuned.html
- Wogram, decay: https://www.speech.kth.se/music/5_lectures/wogram/decay.html
- Grand Piano Regulation Specifications (Kawai, Steinway Hamburg/NY, Yamaha):
  https://88keys.sg/wp-content/uploads/2023/03/Grand-Piano-Regulation-Specifications.pdf
- Steinway World-Wide Technical Reference Guide, Grand Regulation (Ch. 2):
  https://www.indyptg.org/PTG/SteinwayServiceManuals/Chp2.pdf
- Mario Igrec, *Pianos Inside Out*, Ch. 5 sample (2013): https://www.pianosinsideout.com/regbig.pdf
- Fred Redekop, "Grand Piano Dampers: An Everyday Guide" (Seattle PTG):
  https://seattleptg.org/wp-content/uploads/2024/06/Grand-Piano-Dampers.pdf
- Doug Wood, "Grand Damper Regulation": https://seattleptg.org/wp-content/uploads/2024/06/GrandDampersDougWood.pdf
- "Vertical Regulation" (Spurlock Specialty Tools): https://spurlockspecialtytools.com/index_htm_files/vreg.pdf
- Reyburn Pianoworks, keyboard evaluation (key ratio): https://www.reyburnpianoworks.com/evaluation
- US Patent 6,153,819 (key return 35 ms / 38 ms): https://patents.google.com/patent/US6153819
- Yamaha Hub, grand vs upright (15/s vs 7/s; upright pedals):
  https://hub.yamaha.com/pianos/p-acoustic/whats-the-difference-between-a-grand-piano-and-an-upright-piano/
- Kawai FAQ, undamped top notes:
  https://www.kawai-global.com/support/faq/why-do-the-upper-18-notes-of-my-kawai-digital-piano-sustain-without-using-the-damper-pedal/
- Piano World, "undamped notes":
  https://forums.pianoworld.com/ubbthreads.php/ubb/printthread/Board/1/main/16659/type/thread.html
- Pianoteq 9 User Manual: https://www.modartt.com/user_manual?product=pianoteq&lang=en
- Garritan CFX manual: https://usermanuals.garritan.com/CFXConcertGrand/Content/sympathetic_resonance.htm and
  .../pedaling.htm. Sound On Sound CFX review: https://www.soundonsound.com/reviews/garritan-cfx-concert-grand
- Synthogy Ivory II: https://synthogy.com/products/ivory-ii-grand-pianos
- Cincinnati Note, half-pedal: http://cincinnatinote.blogspot.com/2014/09/a-new-pedaling-technique.html
- Bensa, Bilbao, Kronland-Martinet & Smith (2003), JASA 114(2):1095, Table I:
  https://www.math.kent.edu/~zheng/62262/piano_2.pdf
- Lehtonen, Penttinen, Rauhala & Valimaki (2007), JASA 122(3):1787 (abstract), and Lehtonen, Askenfelt &
  Valimaki (2009), JASA-EL 126(2):EL49 (abstract)
- Woodhouse, Euphonics 7.3, "Multiple strings and double decays":
  https://euphonics.org/7-3-multiple-strings-and-double-decays/
- D. Russell, "Hammer nonlinearity, dynamics and the piano sound": https://www.acs.psu.edu/drussell/Piano/Dynamics.html
- Dannenberg (2006), "The Interpretation of MIDI Velocity", ICMC (the square-law survey of synths; abstract only)
- Fletcher & Beebe, "Harpsichord and Clavichord" (chapter):
  https://phys.unsw.edu.au/music/people/publications/Acoustics%20of%20the%20Harpsichord%20and%20Clavichord.pdf
- Dave Law, "Harpsichord Building: Preparing the action for voicing":
  https://www.harpsichord.org.uk/wp-content/uploads/2015/04/voicing.pdf
- John Sankey, "The Keyboard of a Harpsichord": https://johnsankey.ca/action.html
- CBH Technical Library, jack: https://www.hpschd.nu/tech/act/jack.html; fortepiano:
  https://www.hpschd.nu/tech/epf/fp.html
- P. Y. Irvin, "Key Sizes of Early Keyboard Instruments": https://www.pyirvin.com/Early_keyboard_key_sizes.html
- C. Kobb, "Viennese piano technique of the 1820s", Music & Practice vol. 4 (citing Michael Cole):
  https://www.musicandpractice.org/volume-4/viennese-piano-technique-of-the-1820s-and-implications-for-todays-pianists/
- Greifenberger Institut, Walter c. 1782: https://www.greifenberger-institut.de/en/reverse-engineering/Walter-1782.php
- Robert A. Brown, fortepiano after Walter: https://www.fortepiano.at/en/fortepiano/fortepiano-after-anton-walter/
- fortepiano.eu, Silbermann 1749: https://www.fortepiano.eu/gottfried-silbermann-fortepiano/
- Daynes Music, Steinway D specifications: https://daynesmusic.com/steinway-model-d-detailed-specifications/
- Wikipedia: Musical keyboard, Soft pedal, Piano pedals, Harpsichord, Pascal Taskin, Anton Walter, Fortepiano,
  Piano, Concert pitch, Werckmeister temperament, Vallotti temperament

No sample packs, archives or MIDI files were downloaded for this report. The PDFs above were read as web pages
through the fetch tool.
