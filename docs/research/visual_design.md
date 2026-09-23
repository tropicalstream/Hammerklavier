# Hammerklavier: visual design research

**Scope:** the baroque venue, the instruments and the four swipe views, all designed for the RayNeo X3 Pro waveguide.
**Date:** 2026-09-22.
**Status:** research only. Nothing was downloaded. The only device access was two read-only `adb shell dumpsys`/`getprop` queries (§5.1).

Every number here carries one of these tags:

- **[V]** verified from a source listed in §7, or on the device itself.
- **[D]** a design value chosen for the procedural build. It is not a measurement.
- **UNVERIFIED** plausible but not confirmed. Treat it as a guess.

Colours are 8-bit sRGB in display space, which matches how the sibling engines shade (they have no sRGB framebuffer).

---

## 0. Decisions this report supports

1. **The venue is Frederick the Great's Konzertzimmer at Sanssouci, decorated in 1746–47 and rendered by candlelight.**
   - It is a surviving white-and-gold rococo music room [V].
   - It still holds a 1746 Silbermann fortepiano, one of the pair that stood in the Potsdam Stadtschloss, where Bach played for the King in May 1747 [V].
   - A second palette reproduces the lost Stadtschloss Konzertzimmer (c.1744: green walls, gilded carving, chinoiseries) [V].
   - **Köthen is rejected.** Its Spiegelsaal was built in 1822 and is neoclassical, a century after Bach left [V].
2. **The design idea: the room is a gilded trellis in the air.** Nahl designed the Konzertzimmer to "dissolve" its walls into a garden pavilion: a gilded lattice with vines, rising from the four corners to a spider's web in the middle of the ceiling [V]. On a waveguide the wall fields can literally dissolve into the wearer's real room. What stays is what candlelight would show: gilt, mirrors, flames and pools of light on the floor. This also keeps the average picture level (APL) under the vendor's ~13% guidance.
3. **Instruments.** All share one key and action kit.
   - **Grand:** a 200 cm Yamaha C5-size grand by default, to match the Salamander C5 samples [V]. A 274 cm Steinway D-size case is an option.
   - **Upright:** a 131 cm Yamaha U3-size upright.
   - **Harpsichord:** a French double after F.-E. Blanchet (c.1733–40).
   - **Silbermann:** the Potsdam fortepiano (1746/47).
   - **Correction to the brief:** the Potsdam Silbermanns have **ivory naturals and ebony sharps**, so they are *not* reverse-coloured [V, SPSG record V 12]. Reverse colours (ebony naturals, bone sharps) belong to the French harpsichord [V] and to the Viennese Walter fortepiano [V].
4. **Handling black on the waveguide.** Black lacquer and ebony are rendered as *reflectors*, not as dark colours:
   - Fresnel rim light.
   - Candle specular highlights.
   - A tiny baked light-probe reflection.
   - A calibrated "presence floor" (default (22,18,15), confirmed on device with a test card).
   - An optional thin gilt feature-edge overlay.

   Shadows become holes that only read because a lit floor pool surrounds them. There are four venue levels: Salon, Stage, Instrument and Passthrough.
5. **Views** (swipe cycles Player → Action → Inside → Hall). Camera positions, FOVs and pixel sizes were computed for 640×480 per eye (§4).
   - **Player:** the whole keyboard and the pedals in one frame. White keys are 11 px wide. The 10 mm key dip shows as a 4–5 px drop. A "follow" sub-mode shows three octaves with 28 px white keys.
   - **Action:** a sliding cutaway at about 1.07 px/mm. Hammer travel is about 48 px.
   - **Inside:** the lid lifted off, looking down the length of the string bed.
   - **Hall:** from the third row, with head look-around, plus an optional life-size (orthoscopic) mode.
6. **Budgets.**
   - The glasses report **Adreno 621, OpenGL ES 3.2** [V, adb], so instancing, texture fetch in the vertex shader, uniform buffers and `GL_OVR_multiview2` are all present.
   - The recommended baseline stays ES-2.0-compatible. All moving parts (88 keys, 88 hammers, ~70 dampers, ~230 strings) are *static* vertex buffers skinned in the vertex shader from per-part uniform arrays. That is about 4 draw calls and about 1.5 KB of uniforms per frame for every moving part.
   - Target: ≤35 draws and ≤45k triangles per eye.

---

## 1. The venue

### 1.1 Candidates evaluated

| Candidate | Date | Bach link | What survives | Verdict |
|---|---|---|---|---|
| **Köthen, Spiegelsaal** (Ludwigsbau) | Built **1822** by Gottfried Bandhauer, opened 4 Jan 1823. Neoclassical: 1,057 coffers, 780 mirrors, stucco marble [V] | Bach was Kapellmeister at Köthen 1717–23 [V], but the hall **post-dates him by a century** [V] | Intact; today's Bachfesttage venue | **Reject.** It is neither baroque nor Bach's room. It would be a historical error presented as fact. |
| **Potsdam Stadtschloss, Konzertzimmer** | c.**1744**. Green walls, gilded carvings, colourful chinoiseries; decoration by J. S. Nahl; Kambly music stand [V] | **Bach played the Silbermann here before Frederick II in May 1747** [V]. This is the Musical Offering room. | Interior **burned in 1945** [V]. The palace was rebuilt only as the Landtag exterior. | **Use as the palette variant "Stadtschloss 1747".** No room geometry survives to copy. |
| **Sanssouci, Konzertzimmer** | **1746–47.** Knobelsdorff architect; Nahl designs; Hoppenhaupt woodwork; Merck stucco; Ebenhech sculpture [V] | Holds the **1746 Silbermann (inv. V 13)**, which was originally in the Stadtschloss with its twin [V]. Frederick played flute here. Menzel painted the scene (1852), with C.P.E. Bach at the keyboard [V]. | **Intact**, and very well photographed | **Recommend as the base.** |
| Café Zimmermann, Leipzig | House built 1717; Collegium Musicum 1720–41, Bach directing from 1729 [V] | Strong | **Destroyed Dec 1943** [V]. No interior record found. | Reject: we would be inventing it. |
| Amalienburg, Munich | 1734–39, Cuvilliés. Round Spiegelsaal of **16 wall bays: 4 windows or doors, 2 passages, 10 mirror fields** [V]. Silver on blue [V]. | None | Intact | Keep as a later option. Rotational symmetry makes it the cheapest room to build. Silver on blue reads cool, not warm. |
| Würzburg Residenz, Kaisersaal | Tiepolo ceiling frescoes **1751/52** [V] | None | Intact | Reject. It is after 1750, far too large, and its fresco would blow the APL budget. |

### 1.2 Why Sanssouci suits a waveguide

- **Mirrors multiply the candles.** The walls alternate **mirrors with painted panels by Antoine Pesne** showing Ovid's *Metamorphoses* [V]. One snippet counts six paintings [V, single source]. On a waveguide, mirrors cost almost nothing to fake: we reflect only the points of light (§3.4). They also turn about 50 flames into a few hundred, which is the whole historical reason for mirrors in a candlelit room.
- **The ceiling is line art, not a fresco.**
  - Gilded **trellis with vines**, configured like **four towers rising from the corners** and ending in a **spider's web at the centre** [V].
  - The cove carries **hunting scenes: dogs chasing hares and deer, putti blowing horns and trumpets and spreading nets** [V].
  - Bright gold lines on nothing are ideal for the display.
  - The brief's "painted fresco oval" belongs to Würzburg-type halls. It would be a large, bright, textured area, which costs APL. We omit it.
- **The design intent is ours.** The room was meant to "optically dissolve spatial boundaries" [V]. We let the real world stand in for the white wall fields.
- **Menzel's "Flute Concert of Frederick the Great at Sanssouci"** (1852, 142×205 cm, Alte Nationalgalerie) is the look reference: a candlelit evening, chandelier-dominated. Menzel said he painted it "only because of the chandelier" [V]. The painting is out of copyright. Using a photograph of it as an in-app asset would need a download approval, so it is a reference only.

### 1.3 Build sheet: the Konzertzimmer (procedural, GL ES)

The real room's dimensions were **not found** (UNVERIFIED). Sanssouci's main block is **91.6 m long and 15.4 m deep**, with an exterior height of about 12.3 m [V]. The size below is a **[D] design room**, sized for the camera maths in §4. Do not present it as the real measurement.

**Room frame:** x = east (long axis), z = south (toward the garden windows), y = up, origin at the centre of the floor.

```
                        N wall (z = -4.0): mirrors opposite the windows
      +------+---M----+--P--+----M----+--P--+----M----+------+
  W   |  P   |         [ grand, lid open toward S ]          |  P   |  E
 door |  D   |          kb at west, tail east                |  D   | door
 wall |  P   |                                               |  P   | wall
      |            * chandelier (0, 3.3, 0)                          |
      |        row1  h h h h h h   z=+1.4                            |
      |        row2  h h h h h h   z=+2.35                           |
      |        row3  h h h h h h   z=+3.3  <- Hall camera            |
      +----W----+--pier(M)--+----W----+--pier(M)--+----W----+
                        S wall (z = +4.0): tall French windows
   M = mirror, P = Pesne painting panel, D = double door with supraporte, W = window, h = chair
```

| Element | Geometry [D unless marked] | Build method | Colour (lit peak, sRGB) |
|---|---|---|---|
| Room shell | 10.5 m (x) × 8.0 m (z). Cornice top at 4.6 m. Quarter-round cove of radius 1.1 m, so the ceiling sits at 5.7 m. | Box plus a swept cove profile of 8 segments | Wall fields are **not drawn** in Salon level (§3.3). They reappear only as candle-lit gradients near light sources. |
| Floor | Oak panel parquet ("Tafelparkett") in 1.0 m squares with diagonal fillets (UNVERIFIED that Sanssouci has this pattern) | One quad with a tiling 512² texture (ASTC is supported [V]), multiplied by the light-pool falloff (§3.4) | Oak (150,100,55) at the pool centre, falling to 0 at r ≈ 2.6 m |
| Dado / lambris | Height 0.90 m, panels 0.6 m wide, skirting 0.15 m | Extruded moulding strips | Gilt edge lines only |
| Wall bays | 1.8 m pitch on the long walls. Pilaster strips 0.35 m wide carrying vertical gilt trellis with vine leaves. | Ribbon quads 2–3 px wide + leaf sprites from the atlas | Gilt (226,168,78), leaf highlight (255,222,150) |
| Mirrors, N wall | 3 pier mirrors opposite the windows at x = −3.0, 0, +3.0. Glass 1.3 × 3.4 m, bottom at 0.9 m. Arched top with a rocaille crest 0.6 m high. | Glass quad (stencil mask) + frame ribbons + crest decal | Glass is black (transparent) apart from reflected light points |
| Pesne panels | 2 on the N wall (x = ±1.5, 1.1 × 2.4 m), 2 on each short wall beside the door: **6 total**, matching [V] | Rocaille frame decal + canvas | Canvas left dark (transparent) by default; or a dim warm vignette (60,44,30) with no figures |
| Doors | Double doors centred on the E and W walls, 1.5 × 3.2 m. Supraporte painting 1.5 × 1.0 m above each (the Dubois landscapes [V]). | Panelled box + gilt mouldings | Gilt lines; door leaves 0 |
| Windows, S wall | 3 French windows at x = −3.0, 0, +3.0, 1.5 × 3.9 m, segmental-arched head, glazing bars in 3×6 panes. Pier glasses 0.9 × 3.0 m on the piers at x = ±1.5. | Frame ribbons + mullion lines. The panes are transparent: the real world is the night garden. | Frames (120,96,64) near candles; bars (70,56,38) |
| Cornice | 0.30 m profile at 4.3–4.6 m, gilt lip line | Swept profile, 3 bands | Lip (226,168,78) |
| Cove | 1.1 m radius. A cartouche every ~2.0 m: hound + hare + stag + putto with horn, as gilt relief decals. Corner cartouches start the trellis "towers". | 20–24 alpha decals from one atlas | Gilt relief (206,150,66) with an emissive floor (48,32,13) |
| Ceiling | Flat field 8.3 × 5.8 m. Trellis lattice from each corner converging to a central **spider web: 16 radial spokes + 9 spiral turns**, with a rosette at the centre (UNVERIFIED counts; read them off a photo when building). | Line ribbons in one vertex buffer (~3k triangles) | Gilt (212,160,74), fading with distance from the chandelier |
| Chandelier | Hangs from the rosette; candle ring at y ≈ 3.3 m. Two tiers: 12 + 6 candles, ~160 crystal drops, gilt-bronze arms. | Arms as a line mesh; candles as flame sprites; crystals as sparkle sprites (§3.4) | Flames §3.5; crystals flash to (255,248,230) |
| Sconces (girandoles) | 2-arm, on each N-mirror frame (3 × 2 × 2 = 12 flames), on the S pier glasses (2 × 2 = 4) and beside the doors (4 × 1) | Gilt arm sprites + flames | as above |
| Candelabra | 2 floor candelabra, 5 lights each, flanking the instrument at ~1.6 m; 2 music-desk candles on grand, harpsichord and Silbermann | Mesh + flames | as above |
| Music stand | Frederick's music stand (Kambly, 1767 [V]) beside the keyboard, as a set piece | Low-poly mesh | Gilt |
| Audience | 3 rows × 6 rococo chairs: cabriole legs, gilt frames, damask seats. Chair pitch 0.62 m, rows at z = +1.4, +2.35, +3.3. | One merged static mesh (~5k triangles) | Frames (170,124,58); seats a dim damask red (80,30,26) |
| Instrument placement | Grand centred at (0, 0, −1.9), long axis along x, keyboard at the west. The bentside and lid therefore face the audience (south). The centre N mirror stands behind the instrument. | — | — |

**Total light sources:** about 50 flames. For shading, use up to 4 dynamic point lights: the chandelier as one aggregate light, the two nearest candelabra and the nearest sconce group. Everything static in the room gets its lighting baked per vertex from all ~50 flames at load time. Flicker is a global ±4% brightness noise at 6–10 Hz on the baked light, plus independent per-sprite flicker. No per-candle dynamic lights.

**Variants** (same geometry, different palette):

- **"Sanssouci 1747"** (default): white-and-gold. The white fields are invisible, so the gilt does the work.
- **"Stadtschloss 1747"**: wall fields drawn at a dim celadon green, (46,70,48) near candles and 0 in the distance. Add chinoiserie panel decals (a coloured atlas). This is the room where Bach played.
- **"Amalienburg"** (later): round, 16 bays, silver (205,210,220) on pale blue (40,60,90).

### 1.4 Assets needed

| Asset | Size | Source |
|---|---|---|
| Rocaille/trellis/vine atlas: C and S scrolls, shells, leaves, cartouches, crests, hunting reliefs | 1024² RGBA, ASTC 6×6 ≈ 0.45 MB | **Author procedurally** or hand-draw as SVG and rasterise at build time. No download. |
| Parquet tile | 512² | Procedural |
| Harpsichord soundboard painting (flowers, birds, scalloped blue border), lid landscape, gilt bands | 1024×512 each | Procedural or hand-painted. Photos of real instruments are copyrighted photographs; do not lift them. |
| Pesne and Menzel reproductions (optional) | — | Public-domain paintings, but a Wikimedia file is a **download needing user approval**. Record the exact file, licence and size first. Not required. |

---

## 2. The instruments

### 2.1 Shared keyboard kit (all dimensions in metres)

| Quantity | Modern piano | Source |
|---|---|---|
| Octave span | **164–165 mm** | [V] Wikipedia, Musical keyboard |
| Natural width at the front | ≈ **23.5 mm** (octave ÷ 7 = 23.57) | [V] |
| Sharp width | ≈ **13.7 mm** at the base; the top tapers to ~10.5 mm (UNVERIFIED) | [V] base / UNVERIFIED top |
| Natural visible length | 135–150 mm; build at 148 mm | UNVERIFIED |
| Sharp visible length / height above the naturals | ~92 mm / ~12 mm | UNVERIFIED |
| Key dip | **10.0 ± 0.1 mm** (grand), 10 mm (upright) | [V] |
| Key lever | ~480 mm long, pivoting on the balance rail about 60% back from the front (low-quality source) | UNVERIFIED |
| Historical octave span | 125–170 mm; Italian up to 174, German down to 156. French "6¼ in" ≈ **159 mm**; Ruckers "just under 6½ in" ≈ 164 mm. | [V] |

**Procedural key layout (realism detail).** Give every semitone an equal slot at the *back* of the keyboard: P = octave ÷ 12 = 13.75 mm. Sharps sit in their slots. The 7 naturals have equal 23.57 mm heads at the *front*, and their tails are cut out around the sharps (C, F: cut on the right; E, B: cut on the left; D, G, A: cut on both sides; A0 and C8 are full). This puts the sharps off-centre over the natural gaps, as they are on a real keyboard. It also gives the hammers an even 13.75 mm pitch.

Key motion is a rotation about the balance-rail line. A 10 mm drop at a front edge ~0.29 m from the pivot is **θ ≈ 2.0°** (UNVERIFIED pivot position).

Render key edges as **analytic bevels in key-local UV space**: a `smoothstep` darkening over the outer 0.8 mm of each key top. Do *not* rely on geometry gaps. The ~1 mm gaps are 0.1–0.5 px in every view and would shimmer without MSAA.

### 2.2 Concert grand (default: C5 size)

| Spec | Yamaha C5(X) size (default) | Steinway D size (option) |
|---|---|---|
| Length × width × height | **200 × 149 × 101 cm**, 350 kg [V] | **274 × 156 cm**, 480 kg [V] |
| Top of keys above floor | ~71.5 cm (D figure [V]; use it for both) | 71.5 cm [V] |
| Pedals | 3: una corda, sostenuto, damper [V] | 3 |
| Strings | ~226–243 depending on source (UNVERIFIED). Build for up to 240. Design split: notes 1–8 single wound, 9–28 bichord wound, 29–88 trichord (29–33 wound): 8 + 40 + 180 = **228** [D]. | same |
| Dampers | No dampers on roughly the top 18 notes. Steinway leaves the **18 highest notes, from G**, undamped; Mason & Hamlin the top 20 [V]. Build **70 dampers** (notes 1–70). | same |

**Plan outline [D]** (eyeballed from standard plan drawings; refine against a real plan). Coordinates are normalised with u = 0 at the spine (bass side) to 1 at the treble cheek, and v = 0 at the key fronts to 1 at the tail. Fit a Catmull-Rom spline through:

```
(0.00,0.00) (1.00,0.00) (1.00,0.16) (0.93,0.30) (0.80,0.45) (0.70,0.58)
(0.66,0.70) (0.62,0.82) (0.52,0.94) (0.35,1.00) (0.12,0.99) (0.00,0.93)
```

The spine is straight from (0,0.93) back to (0,0). The case is ≈0.30 m deep (UNVERIFIED), so the rim bottom sits at ~0.66 m and the keybed underside at ~0.63 m (UNVERIFIED).

**Parts**

- 3 tapered legs with brass casters; the lyre is centred under the keybed.
- Pedal feet: tips at z ≈ −0.22 m, y ≈ 0.09 m, spaced 9 cm [D].
- One-piece lid hinged on the spine with a front flap. It props up on the bentside: full stick ≈ 38–40° [D].
- Music desk. Fallboard lettered "HAMMERKLAVIER" in gilt. Never use a maker's trademark.

**Strings [D]**

- **Overstrung:** the bass bridge covers ~notes 1–20 at 15–20° across the tenor (UNVERIFIED angle).
- The speaking length roughly halves per octave in the treble and is foreshortened in the bass. C8 ≈ 50 mm; the longest bass strings are about 1.2–1.4 m, laid diagonally to fit a 2 m case.
- Tuning pins run in a band along the front; the plate is cast iron painted gold, with large lightening holes over the spruce soundboard.

**Hammers:** felt heads, 88. Height ~50 mm in the bass tapering to ~30 mm in the treble; thickness ~20 → ~10 mm (UNVERIFIED). Shanks ~130 mm [D].

**Colours**

- Case: polished ebony (§3.2).
- Plate (196,150,72).
- Soundboard (176,138,84) at a dim level, since it sits in shadow under the plate.
- Steel strings: base (120,120,126), highlight (210,210,214). Wound bass strings (214,136,70).
- Hammer felt (236,230,212).
- Damper heads: wooden top (150,112,70) over black felt.
- Brass pedals and casters: highlight (224,172,84), mid (140,98,40).
- Naturals (232,214,178), capped at this level because they are a large area. Sharps: ebony (§3.2).

### 2.3 Upright (U3 size)

| Spec | Value |
|---|---|
| Yamaha U3 | **131 cm tall, 153 cm wide, 65 cm deep, 235 kg** [V] |
| Yamaha U1 (option) | **121 × 153 × 62 cm** [V] |
| Action | Key dip 10 mm; **hammer blow 48 mm (1⅞")**; let-off 3 mm [V] |
| Soft pedal | Moves the hammer rail toward the strings, shortening the swing [V]. Build it as 48 → 26 mm [D]. |
| Middle pedal | Practice/muffler rail: a felt strip drops between the hammers and the strings [V]. A lovely visible motion. |
| Damper pedal | The lift rod swings every damper *forward*, off the strings, toward the player |

Geometry [D]:

- Key tops at 0.715 m.
- Action region y 0.72–1.20 m. Hammer strike line ~1.08 m; damper row just above at ~1.18 m.
- Strings vertical, overstrung.
- Upper front panel 0.76–1.25 m; knee board 0.10–0.62 m; pedals protrude from the bottom board at y ≈ 0.07, z ≈ −0.20.

**Finish:** ebony is the canonical finish, but walnut or mahogany is equally real and far easier to see on the waveguide. Ship a finish toggle: Ebony / Walnut (150,98,56) / Mahogany (150,72,40).

### 2.4 French double harpsichord (after Blanchet)

| Spec | Value |
|---|---|
| Reference original | **F.-E. Blanchet the Elder, Paris c.1740 (Yale):** 232.4 cm long, 90.8 cm wide, 26.0 cm deep (case); FF–e‴; **2 manuals, 2×8′ + 1×4′**; case "**painted with gold leaves, flowers and arabesques over a brownish green background**"; **two pastoral landscapes inside the lid**; soundboard **painted with flowers**, with a cast-metal rose; **Louis XV painted cabriole stand** [V] |
| Copy data (cross-check) | Blanchet 1733 copy 236 × 96 cm, FF–f‴, **ebony naturals, bone-topped sharps**; lower manual 8′+4′, upper 8′ + buff, shove coupler [V]. Neupert Blanchet 234 × 94 cm, 70 kg, "sharps pearwood covered by bone", wooden jacks with Delrin plectra [V]. |
| Key count | FF–e‴ = **60 keys (35 naturals)** [D, derived]. The keyboard is 35 × 22.7 mm ≈ **0.795 m** wide at a 159 mm octave. |
| Jacks | 3 per key (180 for 60 keys). Body about 152–178 mm long × 12.7 mm × 3.8–5 mm [V, maker specs]. Plectrum about **10 × 1.5 × 0.5 mm** [V]. Jack travel is about 1.15–1.35 × key travel [V]. Key levers 25 cm (single manual) up to 50 cm (double) [V]. |
| Key dip | ~6–8 mm (UNVERIFIED); build at 7 mm, so the jack rises about 8 mm |

**Jack cycle** [V, order]:

1. Key down: the jack rises in its register.
2. The quill plucks the string.
3. The jack stops against the padded jack rail.
4. On release the jack falls by gravity. The tongue swings back on its axle pin so the quill slips under the string without re-plucking, and a spring re-centres the tongue.
5. The cloth damper lands on the string.

A disengaged register moves sideways, so its quills miss the strings (still rising, silent). This is visible and historically right.

**Colours**

- Case ground: a "brownish green" (92,96,56) when lit; gold bands and arabesques (226,176,86).
- Inner case walls: painted paper floral bands.
- Soundboard ground (214,184,128), with flowers in red (200,70,60), blue (90,120,190) and green (90,140,70), a blue scalloped border and a gilt rose.
- Lid interior: landscape in bright pastel (a big APL cost, so dim it to about 60%).
- Stand: gilt (212,160,74).
- Keys: ebony naturals (§3.2); bone sharps (226,212,182); arcaded boxwood or paper key fronts (220,190,130).

No pedals. The "controls" are the register levers above the upper manual (exact position UNVERIFIED) and the shove coupler.

### 2.5 Silbermann fortepiano (the Potsdam instrument)

| Spec | Value |
|---|---|
| **SPSG V 12** (Neues Palais, 1747) | **H 94.3 × W 232.0 × D 96.1 cm.** Case **oak, polished with shellac**; spruce soundboard; **"Tasten: Elfenbein … schwarze Tasten: Ebenholz"** (ivory keys, ebony black keys); stand of **carved, gilded oak** (c.1765, Peter Schwitzer); pear-wood bridges and escapement jacks; walnut hitch rail; **leather** checks and hammer parts; beech hammer shanks; **rolled-paper hammer heads**; lime key levers [V, verbatim] |
| **SPSG V 13** (Sanssouci Konzertzimmer, 1746) | **94 × 232 cm**, oak case, spruce soundboard, walnut frame, **ivory and ebony keys**. Originally in the Stadtschloss. "Bach played it on his visit in May 1747." [V] |
| Compass / stops | The 1749 Nürnberg Silbermann (McNulty copy): **FF–e‴**, walnut, **hand-operated sustain, una corda and harpsichord stops**. The copy measures 235 × 126 × 35 cm, which does not match the SPSG width [V]. The Potsdam instruments' compass is UNVERIFIED; build FF–e‴. |

**Look:** a honey-coloured, shellac-polished oak case (168,116,62). Modern-colour keys: ivory (232,214,178), ebony sharps. Leather-covered hammers (176,128,80) on a Cristofori-type action. A gilded carved stand.

**No pedals.** Map MIDI CC64 to the damper-raising hand stop, which slides with a ~200 ms ease. Say plainly in the app that on this instrument the stop was set by hand, not pedalled.

### 2.6 Optional: early Viennese fortepiano (Walter), for Mozart and early Beethoven

- Neupert copy of A. Walter: **219 × 102 cm, 98 kg, F1–g3 (63 keys)**, **ebony naturals, bone-covered sharps**, **2 knee levers (forte, moderator)**, Viennese Prellmechanik, bichord with a trichord treble [V].
- Mozart's 1782 Walter is in Salzburg and is walnut-veneered [V, search summary].
- The action is fundamentally different: the hammer is mounted on the key and points toward the player. That makes it a separate Action-view model, so it is v2.

### 2.7 How the mechanisms move (drives "realistic" key, hammer, damper and pedal motion)

**Grand action timeline.** Regulation data: key dip 10 mm, blow distance 47 mm, let-off 2 mm, drop 1.5 mm, back-check 15 mm from the string [V].

| Event | Timing |
|---|---|
| Key starts moving | **t_on − T(v)**, where t_on is the audio onset (MIDI note-on). **T ≈ 25 ms at forte (~5 m/s hammer) to 160 ms at piano (~1 m/s)** [V, Askenfelt & Jansson 1990]. Interpolate on log-velocity. |
| Key bottom vs hammer contact | Contact comes **12 ms before key-bottom at piano**, **3 ms after at forte**, up to 40 ms before for very soft tones [V] |
| Damper begins to lift | When the key is **halfway down** [V] |
| Hammer | Rises about 4.7× key travel. Escapes 2 mm from the string, flies free, contacts at t_on, rebounds, and is **caught by the back-check** while the key is held. The repetition lever lifts it slightly. |
| Key release | ~60–100 ms return (UNVERIFIED). The damper lands at mid-return; the hammer drops to its rest rail. |

**Pedals.**

- **CC64 → damper pedal:** slew-limit to ~60–80 ms per full travel. The first ~20% of travel moves nothing (lost motion); the dampers move from 20% to 100% of travel [D]. Many MIDI files send only 0 or 127, so the slew is what makes it look physical.
- **CC67 → una corda:** the keyboard and action slide toward the treble so the hammers strike two of three strings [V]. Build a 2.5 mm slide (UNVERIFIED distance). **The whole keyboard visibly shifts:** a beautiful, real detail.
- **CC66 → sostenuto:** holds only the dampers that were already up [V].

**Frame-rate note.** At 30 fps (33 ms per frame) a forte key goes down within one frame, while a piano key takes ~5 frames. Compute state as an **analytic function of song time**, `state_k(t) = f(events)`, rather than integrating per frame. That makes it frame-rate independent and seekable, with no drift.

**Clocking.** Drive song time from the audio clock (`AudioTrack.getTimestamp`), plus ~1.5 frames of display-latency lead. Tune the lead on the device with a flash-and-click test (UNVERIFIED latency).

---

## 3. The waveguide problem

### 3.1 Constraints

- **Black is transparent.** A black pixel projects nothing [V, starter guide].
- **Dark tones are swallowed.** The sibling engine gamma-lifts textures, `c = pow(c, 0.85) * (0.75 + 0.45 * lift)` [V, `InnerCosmos StereoBodyRenderer.kt:3313`].
- **Brightness is power.** MicroLED at **3000 nits to the eye**. Vendor guidance is **~30 fps UI and APL < 13%**, with sustained draw above 500 mA counting as thermal trouble [V].
- A fully lit white-and-gold room would push APL to ~40%+ and hide the wearer's surroundings. So the venue must be *sparse light*, not a lit box.

### 3.2 The black-instrument recipe (lacquer and ebony)

In life, a black grand is seen almost entirely through what it reflects. Render that:

1. **Presence floor.** `rgb = max(lit, uFloor)` on instrument surfaces only; never on the background. Default `uFloor` = **(22,18,15)**, within the brief's suggested 18–25. Whether 22 is actually visible on this waveguide is **UNVERIFIED**. Calibrate it with the test card in §3.6. If it is swallowed, raise it to about 32–40 or rely on items 2–5.
2. **Fresnel rim light.** `rim = pow(1.0 - max(dot(N,V),0.0), 3.0) * RIM`, with RIM = (120,78,40) × 0.8. Every silhouette gets a warm edge.
3. **Candle highlights.** Blinn-Phong from the ≤4 dynamic lights, exponent 96, colour (255,214,160). Lid tops, rim tops, the fallboard and the legs get sharp streaks.
4. **Baked light-probe reflection.** At load time, splat the room's flames, the chandelier and the gilt bands as Gaussian blobs and lines into a **128×64 equirectangular texture** centred on the instrument. In the fragment shader: `R = reflect(-V, N)`, then `refl = texture2D(uProbe, equirect(R)) * schlick(F0 = 0.05)`. Streaks of reflected candles now slide over the lid as the head moves, which is the single strongest "this is a real black piano" cue. The sibling's GazeCamera look-around makes it parallax.
5. **Feature-edge overlay (toggle).** A static line mesh of the case's feature edges (rim top, lid edges, cheeks, leg silhouettes, pedal outlines), drawn as 1.5 px screen-space ribbons in dim gilt (110,80,42) with depth test and polygon offset. This is the legibility fallback. It is visible even in daylight passthrough.
6. **Interior is naturally bright.** The gold plate, spruce, steel strings, white felt and ivory all read without tricks. The black parts are the case exterior, the lid underside (use the probe again) and the damper felts. Damper felts are hidden under wooden heads (150,112,70).
7. **Shadows as holes.** The piano's contact shadow and the dark underside render as black, and therefore as transparency. That only reads correctly when a **lit floor pool** (§3.4) surrounds them, so always draw the pool.
8. **Harpsichord ebony naturals.** Floor (26,21,18). A polished sheen line along each key from the probe. The arcaded key fronts (220,190,130) form a bright row, and a pressed natural reads as a **gap in that row** (its front drops 7 mm). The pale lime or pine key-lever wood shows as a sliver beside a depressed key's neighbours.

### 3.3 Venue levels (user setting; the thermal governor may step down)

| Level | What is drawn | Target APL |
|---|---|---|
| **Salon** (default in Hall view) | All gilt: trellis, cove, frames, crests. All flames plus their mirror reflections. Chandelier with sparkle. Floor pools. Chair silhouettes. Wall fields only as a candle-lit glow within ~1.2 m of each flame, capped at (70,58,44). | ≤ 12% |
| **Stage** (default in Player, Action and Inside) | Instrument + floor pool (r = 2.6 m) + the two candelabra + the mirror bay behind the instrument with its sconces. Everything else fades by distance: `alpha = 1 − smoothstep(3.5, 6.0, d)`. | ≤ 9% |
| **Instrument** | Instrument + contact pool | ≤ 6% |
| **Passthrough** | Instrument only, with the edge overlay on and the presence floor raised, for daylight | ≤ 5% |

APL is measured, not guessed: see §3.6.

### 3.4 Cheap light: flames, mirrors, crystals, floor pools

- **Flame sprite.** Three additive layers: core (255,244,214), body (255,190,90) and halo (255,140,50) at alpha 0.18 with a Gaussian falloff to r ≈ 12 cm. Per-flame flicker is a hashed noise, ±15% height and ±8% brightness. One draw for every flame.
- **Mirror reflections.** Draw the mirror-glass quads into the **stencil buffer** (request an 8-bit stencil in the EGL config). For each mirror plane, reflect every flame position through the plane and draw the reflected sprites with the stencil test on, at 70% intensity with a slight warm tint: 50 flames × ~5 mirrors ≈ 250 sprites, 1 draw. Optionally add one second-order bounce between the facing N mirrors and S pier glasses for the "infinite gallery" look.
- **Crystal sparkle.** ~160 point sprites in the chandelier. Brightness is `pow(max(0, dot(reflect(-L, n_i), V)), 40)` with a hashed per-crystal normal `n_i`, so the crystals twinkle as the head moves. One draw.
- **Floor pools.** A decal quad per pool (chandelier, candelabra) with a radial falloff, multiplied by the parquet texture. It also carries **reflected flame sprites below the floor plane** at 25%, because polished parquet reflects candles.
- **Gilt is partly emissive.** `gilt = lit + emissive`, with emissive = (48,32,13). Far ornaments stay faintly legible without lighting and cost almost no APL, because they are thin.
- **Cap large areas.** No surface wider than ~40 px may exceed ~0.85 of full brightness (220). Pure 255 is reserved for flame cores, sparkles and spec pinpoints.
- **Warm bias.** Keep blue low throughout. This matches what reads well on these glasses. Whether this waveguide has blue-channel non-uniformity is UNVERIFIED.

### 3.5 Palette (single source of truth; sRGB, lit-peak values unless noted)

| Token | RGB | Use |
|---|---|---|
| FLAME_CORE / BODY / HALO | 255,244,214 / 255,190,90 / 255,140,50 | Candle sprites |
| LIGHT_CANDLE (multiplier) | 1.00, 0.66, 0.36 | Point-light colour |
| GILT_HI / LIT / SHADE / EMISSIVE | 255,222,150 / 226,168,78 / 96,64,26 / 48,32,13 | Rocaille, trellis, frames, stand |
| BOISERIE_NEAR (cap) | 70,58,44 | White wall only within ~1.2 m of a flame |
| STADTSCHLOSS_GREEN | 46,70,48 | Variant wall ground near flames |
| PARQUET_POOL | 150,100,55 | Pool centre |
| EBONY_FLOOR / RIM / SPEC | 22,18,15 / 120,78,40 / 255,214,160 | Lacquer, black keys, harpsichord naturals |
| IVORY / IVORY_SIDE | 232,214,178 / 170,150,118 | Naturals (piano, Silbermann) |
| BONE | 226,212,182 | Harpsichord and Walter sharps |
| BRASS_HI / MID | 224,172,84 / 140,98,40 | Pedals, casters, hinges |
| PLATE_GOLD | 196,150,72 | Cast-iron plate |
| SOUNDBOARD (shadowed) | 176,138,84 | Grand/upright soundboard |
| STEEL_HI / BASE, COPPER | 210,210,214 / 120,120,126, 214,136,70 | Strings |
| FELT / LEATHER | 236,230,212 / 176,128,80 | Hammers: modern / historical |
| DAMPER_TOP | 150,112,70 | Damper heads |
| KEYLEVER / ACTION_WOOD | 214,186,140 / 196,158,104 | Action view |
| CLOTH_RED | 180,40,40 | Bushings and punchings (Action view) |
| SECTION_CAP | 200,170,120 + gilt outline | Cut faces in the cutaway |
| HARPSI_GREEN / GOLD_BAND | 92,96,56 / 226,176,86 | Blanchet case |
| OAK_SHELLAC / WALNUT / MAHOGANY | 168,116,62 / 150,98,56 / 150,72,40 | Silbermann / Walter and upright options |
| HUD_TEXT / HUD_ACCENT | 255,236,200 / 240,190,100 | Captions, titles |

### 3.6 Two on-device checks to build first

1. **Visibility-floor card.** Show 16 warm swatches at levels 8…68 in steps of 4, plus a neutral row, on transparency. The wearer swipes to the dimmest swatch they can see against their room at their brightness setting. Store it as `presenceFloor` and use it as `uFloor`. (This is the MathCosmos `Calibration` pattern: one number, one swipe [V].)
2. **APL meter.** Capture a frame of each view and instrument with `adb -s A06B4A96A733283 exec-out screencap -p > view.png`. Average Rec.709 luma over the 1280×480 frame (`PIL.ImageStat`) and record it against the budgets in §3.3. Whether screencap captures the GL layer on this device is UNVERIFIED; scrcpy recording does work [V, InnerCosmos notes].

**Rough APL estimates to check [D]:**

- Player view: the ivory keyboard is ~12% of the frame at ~0.7, about 8.5% APL on its own. Hence the IVORY cap, and the room at Stage level.
- Inside view: plate + soundboard is ~45% of the frame. That is why the soundboard is shadowed (≤0.25) and the plate kept to ~0.45.

---

## 4. The views (swipe cycles; state kept per view)

### 4.0 Optics, scale and stereo

- **Optical field** from 30° DFOV at 4:3: **24.2° × 18.3°**, so **≈ 26.4 px per degree** [V spec, derived].
- **Render FOV is a per-view choice.** The sibling renders at a fixed 58° vertical, deliberately wider than the optics [V, `StereoMathRenderer.kt:449`]. Wider than optical *minifies* the world. Only the Hall view's "life-size" mode uses the optical FOV. Keep each view's FOV fixed (never animate it; the sibling's rule [V]) and move the camera instead.
- **Piano frame** for §4.1–4.3: origin on the floor under the centre of the key fronts; x toward the treble (the pianist's right), y up, z toward the pianist, so the instrument extends into −z. White key tops are at y = 0.715.
- **Stereo.** Use **parallel eye cameras with an asymmetric (off-axis) frustum shift**, placing zero parallax at the subject. This avoids the vertical disparity that toe-in produces (the sibling toes in by 35%). IPD 0.063 m × a per-view scale.

  Computed disparities:

  | View | Background | Disparity at full IPD | Disparity at scaled IPD |
  |---|---|---|---|
  | Player (keys at 1.75 m) | wall at 8 m | 22 px | 13 px at ×0.6 |
  | Action (cut plane 1.1 m) | 3 m | 45 px | 16 px at ×0.35 |
  | Hall (piano 4.9 m) | wall | 2.5 px | — |

  The Hall view's chandelier at 3.1 m shows −5 px. Keep everything within about ±26 px (≈1°). These scales are starting values to tune on the device.
- **Head look-around.** Reuse `GazeCamera` (the IMU game rotation vector [V]).
  - Hall view: world-locked, ±60° yaw and +45° pitch, so the wearer can look up at the chandelier and ceiling.
  - Other views: head-coupled parallax of ±5°, only enough to make the lacquer reflections and stereo feel alive.

### 4.1 PLAYER view

**Whole-keyboard mode (default).**

| Setting | Value |
|---|---|
| Camera | Grand/upright **(−0.10, 1.30, 1.55)** → target **(0, 0.50, −0.12)**, vFOV **34°** (hFOV 44.4°). This is where the bench would be, lifted and set back. Bench and pianist are not drawn. |
| Framing | The keyboard spans **x 28 → 607 px** at y ≈ 150–180. The **pedals sit at y ≈ 360**: the sight line passes under the keybed (it crosses the key-front plane at y ≈ 0.25 m, below the keybed at 0.63 m). The floor is at ~415. |
| Legibility | White key **11 px**; black key **6.3 px**; key dip **4.4 px** at the front edge; 18 mm pedal travel **5.5 px**. The brass highlight sliding along a tilting pedal makes that very visible. |
| Legibility aids (all physically real) | A pressed natural's front face drops and its bevel highlight vanishes. Neighbours' side faces are exposed. The una corda slides the whole keyboard sideways. |

**Follow sub-mode** (vertical swipe toggles, suggested).

| Setting | Value |
|---|---|
| Camera | **(x_c, 1.15, 0.62)** → **(x_c, 0.70, −0.08)**, vFOV **30°** |
| Coverage | Three octaves (0.5 m); white keys **27.8 px**, dip **9.6 px** |
| Tracking | x_c follows the centroid of the sounding notes with a ±2-semitone dead band and a critically damped spring (ω ≈ 6 rad/s), capped at 0.6 m/s. This avoids nausea. |
| Pedal inset | A **200×150 px picture-in-picture, bottom-right**: a floor-level camera (−0.35, 0.30, 0.55) → (0, 0.10, −0.22) at vFOV ~12°, framing the three pedal feet only. Rendered mono (identical in both eyes, at screen depth) with scissor + viewport: 2 draws. |

**Per instrument:**

- **Harpsichord:** camera (−0.06, 1.22, 1.05) → (0, 0.62, −0.10), vFOV 34°. The 0.795 m keyboard spans 54 → 585 px, naturals **15 px**, 7 mm dip **4.2 px**. Both manuals are visible; the upper sits ~6.5 cm higher and 8.5 cm back [D]. The register levers are near the top of the frame. Below the keys is the gilt stand apron (no pedals).
- **Silbermann:** as the harpsichord, but with the hand stops at the top of the frame.
- **Walter:** as the harpsichord, with the knee levers under the keybed at y ≈ 0.55–0.60.

### 4.2 ACTION view (cross-section cutaway)

**Concept.** A vertical clip plane at x = x_cut slices the instrument. Everything on the treble side of the plane is discarded: the case cheek, key slip, keybed and the other notes' actions. The cut faces get SECTION_CAP colour with a gilt outline, like a technical cutaway.

- **Clipping:** fragment `discard` against a plane uniform. GL ES has no user clip planes, and the discard cost is trivial at this pixel count.
- **Placement:** x_cut sits at the **highest sounding note** (usually the melody), so the melody's action is the one fully exposed at the front. Notes below it recede behind in a staggered row.
- **Row:** actions within ±6 notes are drawn; others fade out. Inactive actions dim to 40%. That is a legibility aid, not a physical claim.

| Instrument | Camera (relative to x_cut) | vFOV | Legibility |
|---|---|---|---|
| Grand | **(x_cut + 0.95, 0.95, 0.30)** → (x_cut, 0.76, −0.24) | **22°** | Key front at 62 px, key back at 528 px. Hammer travel **48.5 px**, key dip **12 px**, damper lift ~6 mm ≈ **6 px** (UNVERIFIED lift). A semitone neighbour is offset **8.7 px**, an octave below **~90 px**. |
| Upright | (x_cut + 1.05, 1.00, 0.10) → (x_cut, 0.93, −0.20) | 26° | Hammer swing 48 mm ≈ **43 px**, dip **9.7 px** |
| Harpsichord | (x_cut + 0.60, 0.93, −0.02) → (x_cut, 0.86, −0.42) | 24° | Close-up on the jack top: jack rise 8 mm ≈ **12.5 px**, plectrum **13 px** long, the three registers **19 px** apart, key pitch **11 px**. The key lever runs off the bottom edge (intended). |

**What the view must show:**

- **Grand:** key lever rocking on the balance rail; capstan; wippen; jack escaping at the let-off button; the repetition lever; the hammer flying, striking and being **caught by the back-check**; the damper underlever lifting the damper wire and head; the string's vibration blur (§5.3).
- **Upright:** the vertical action; the hammer swinging toward the vertical strings; the damper spoon swinging the damper away; the hammer rail moving under the soft pedal; the muffler rail under the practice pedal.
- **Harpsichord:** the jack rising in its register, quill pluck, jack-rail stop, the **tongue pivoting back on the return**, and the damper landing. Silent registers' quills miss the string.

**Optional label** in view: the note name, e.g. "C4", as a GlyphBoard billboard sized to 16 px.

### 4.3 INSIDE view (the lid lifted off; looking down the bed)

The lid **is lifted away** for this view (animated off, or ghosted as a gilt outline). A real open lid, hinged on the spine, shadows the bass side from above. The music desk also fades.

| Instrument | Camera | vFOV | What reads |
|---|---|---|---|
| Grand | **(0, 1.95, 0.55)** → **(0, 0.84, −0.90)**: standing behind the bench, leaning in | **44°** | Tuning-pin band on the bottom edge (x 22 → 594 px, y ≈ 468); tail at y ≈ 95; spine end (159,107); the bentside bows to the right. Note pitch at the front **6.2 px**. **Hammer rise ≈ 13 px** through the strike-line window: white felt flicking up. **Damper lift ≈ 1.7 px**, too small alone (see below). |
| Upright | (1.05, 1.40, 0.75) → (0, 1.06, −0.28), top lid open, upper front panel removed | 36° | Hammer row A0 (159 px) → C8 (605 px); hammer swing **≈ 17 px** (the 40° yaw converts the forward swing to screen motion); damper row above |
| Harpsichord | (0, 1.85, 0.45) → (0, 0.80, −0.95), lid and jack rail removed | 44° | Jack comb FF (134 px) → e‴ (507 px), the rose at mid-frame, the painted soundboard. Jack rise only **2.5 px**; string shimmer carries the event. |

**Legibility in this view comes from real phenomena, not from exaggerating geometry:**

1. **Hammers are white and move 13–17 px.** They are the primary event.
2. **Vibrating strings look like spindles.** A plucked or struck string, seen by eye, is a blurred spindle, not a moving line. Draw each string as a ribbon whose half-width grows by `A·sin(π t)` along its speaking length, with alpha scaled by `w0 / w`. It gets wider and dimmer, like the real blur, with no temporal aliasing at 30 fps (real frequencies are 27.5 Hz–4.2 kHz).
3. **Real amplitudes are sub-pixel.** Real string amplitudes are ~0.5–2 mm (UNVERIFIED), 1.6 px here. Exaggerate the blur **×4–6 in this view only**, and say so in the design notes.
4. **Dampers move as a row.** A sustain-pedal lift moves all 70 heads together, and a coherent 2 px motion of 70 objects is perceptible. Each lifted head also opens a thin **light gap** between felt and string: the string strip that was in the felt's shadow becomes lit.

### 4.4 HALL view (from the audience)

Room frame (§1.3). The instrument is at (0, 0, −1.9): keyboard to the audience's left, bentside and lid facing the audience, since the lid is hinged on the spine and opens toward the treble side.

| Mode | Camera | FOV | Result |
|---|---|---|---|
| **Wide** (default) | Row 3: **(0.4, 1.20, 3.0)** → **(0, 1.95, −1.9)** | vFOV **40°** (hFOV 51.8°) | Piano from x 160 → 457 px. The open lid and the N mirror behind it, up to the cornice (y ≈ 46–70). The front-row chair backs cover the piano's legs, as they would in a real salon. Rocaille at 20 cm ≈ 16–22 px. The chandelier is above the frame: look up. |
| **Life-size** (vertical-swipe toggle, suggested) | Same seat, target (0, 1.05, −1.9) | **vFOV 18.27°, the optical field** | Orthoscopic: the 2 m piano at 4.9 m spans 23° and fills the eye at true size and true distance. Rocaille at 20 cm ≈ 43 px, a candle flame ≈ 6 px. You turn your head to take in the room, as you would in the seat. |

**Must show:** the lid-open instrument with lacquer reflections; the mirror wall multiplying the flames; the chandelier (reached by head pitch); the floor pool and parquet reflection; chair silhouettes. Only this view's world-locked look-around exposes the whole room.

**Upright in the Hall:** stand it against the N wall, angled 30° toward the audience, so the front panel and keyboard are seen three-quarter on. (An upright in a 1747 room is anachronistic, but the user asked for it.)

### 4.5 Transitions

Swipe forward or back cycles the four views. Fly the camera between poses over ~0.9 s with ease-in-out and a fixed FOV (cross-blend the FOV only if the two views differ by more than 10°). Keep the instrument continuous so the spatial relationship is learned:

- **Player → Action:** dolly toward the treble cheek while the clip plane sweeps in from the treble end.
- **Action → Inside:** rise over the case as the lid lifts off.
- **Inside → Hall:** pull back to row 3; the lid comes down onto full stick.
- **Hall → Player:** glide to the bench.

### 4.6 Camera table (the build constants)

| View | Pos | Target | vFOV | IPD scale | Zero parallax |
|---|---|---|---|---|---|
| Player (grand/upright) | (−0.10, 1.30, 1.55) | (0, 0.50, −0.12) | 34° | 0.6 | 1.75 m |
| Player follow | (x_c, 1.15, 0.62) | (x_c, 0.70, −0.08) | 30° | 0.5 | 0.95 m |
| Player harpsichord / fortepiano | (−0.06, 1.22, 1.05) | (0, 0.62, −0.10) | 34° | 0.6 | 1.25 m |
| Pedal inset (mono) | (−0.35, 0.30, 0.55) | (0, 0.10, −0.22) | ~12° | 0 | — |
| Action grand | (x_cut+0.95, 0.95, 0.30) | (x_cut, 0.76, −0.24) | 22° | 0.35 | 1.1 m |
| Action upright | (x_cut+1.05, 1.00, 0.10) | (x_cut, 0.93, −0.20) | 26° | 0.35 | 1.1 m |
| Action harpsichord | (x_cut+0.60, 0.93, −0.02) | (x_cut, 0.86, −0.42) | 24° | 0.3 | 0.73 m |
| Inside grand | (0, 1.95, 0.55) | (0, 0.84, −0.90) | 44° | 0.5 | 1.8 m |
| Inside upright | (1.05, 1.40, 0.75) | (0, 1.06, −0.28) | 36° | 0.5 | 1.5 m |
| Inside harpsichord | (0, 1.85, 0.45) | (0, 0.80, −0.95) | 44° | 0.5 | 1.8 m |
| Hall wide (room frame) | (0.4, 1.20, 3.0) | (0, 1.95, −1.9) | 40° | 1.0 | 4.9 m |
| Hall life-size | (0.4, 1.20, 3.0) | (0, 1.05, −1.9) | 18.27° | 1.0 | 4.9 m |

All framings were checked with a projection script (pinhole camera, 640×480, per-eye centre). Tune them on the device.

---

## 5. Budgets

### 5.1 The GPU, verified on the glasses (read-only adb, 2026-09-22)

```
GLES: Qualcomm, Adreno (TM) 621, OpenGL ES 3.2 V@0819.0 (02/28/25)
ro.opengles.version = 196610 (ES 3.2)   ro.hardware.egl = adreno   ro.board.platform = neo
Physical size 1280x480, density 160
```

Relevant extensions present:

- `GL_OVR_multiview` / `GL_OVR_multiview2` (single-pass stereo)
- `GL_OES_vertex_array_object`
- `GL_EXT_multisampled_render_to_texture(2)` (cheap tile-resolved MSAA)
- `GL_OES_texture_float`, `GL_OES_texture_half_float`
- `GL_EXT_disjoint_timer_query` (GPU timing)
- `GL_KHR_texture_compression_astc_ldr`
- `GL_EXT_shader_framebuffer_fetch`

**Implication.** `setEGLContextClientVersion(3)` returns an ES 3.2 context in which every sibling `GLES20` call and GLSL ES 1.00 shader still works. ES 3 features (instancing, vertex texture fetch, UBOs) can be adopted selectively. No sibling app has exercised ES 3 on this device yet, so keep an ES 2 fallback path.

### 5.2 Frame budget at 30 fps stereo (both eyes: 1280×480 = 0.61 Mpx)

- **Fill rate is not the constraint:** 0.61 Mpx × ~3 overdraw × 30 fps ≈ 55 Mpx/s.
- **The constraint is CPU→GL call count.** The InnerCosmos thermal incident was ~400 draws per frame on client arrays; VBOs plus fewer draws fixed it [V].

| Item | Budget |
|---|---|
| Draw calls | **≤ 35 per eye** (≤ 70 per frame). Multiview could halve this, but costs a render-to-texture-array plus a blit pass; not needed at this count. |
| Triangles | **≤ 45k per eye.** Estimate: room ~18k (shell, trellis ribbons ~3k, decals ~1k, chairs ~5k, chandelier ~1.3k, flames 0.8k); grand case ~3.6k; keys ~2.2k; hammers ~3.5k; dampers ~1.7k; string ribbons ~3.7k; action-view extras ~4.8k. About 39k in all. |
| Textures | < 16 MB total. Rocaille atlas 1024² ASTC; parquet 512²; soundboard and lid 1024×512; light probe 128×64; glyph cache |
| Per-frame uploads | ~1.5 KB of uniforms (§5.3). No `glBufferData` in the steady state; no Kotlin allocations in `onDrawFrame` |
| Pacing | Choreographer at 30 fps (sibling pattern); thermal ladder below |

Draw list per eye (≈28):

1. Room static, opaque, baked light.
2. Trellis and gilt ribbons, additive.
3. Decals.
4. Chairs.
5. Mirror-glass stencil pass.
6. Reflected sprites.
7. Flames and halos.
8. Crystals.
9. Floor pool.
10. Case body (lacquer shader).
11. Lid.
12. Legs, lyre and pedals.
13. Plate and soundboard.
14. Keys: whites and blacks in one skinned draw.
15. Hammers.
16. Dampers.
17. Strings, steel.
18. Strings, wound.
19. Action parts (key lever, wippen, jack, repetition), about 3 draws.
20. Section caps.
21. Feature-edge overlay.
22. Pedal inset, 2 draws.
23. Glyph and HUD quads.

### 5.3 Moving parts without per-part draws: uniform-array skinning

Bake **all 88 keys into one static vertex buffer**. Do the same for all hammers, all dampers and all strings: one buffer each. Every vertex carries a part index; the vertex shader rotates or translates it by that part's state. It is GPU skinning with one bone per vertex.

**ES 2.0-safe packing.** GLSL ES 1.00 guarantees arbitrary uniform-array indexing in vertex shaders. Pack the per-part scalars four to a `vec4` and select the component with a per-vertex one-hot attribute. This avoids dynamic vector-component indexing, and avoids float arrays that pad one element per `vec4` slot.

```glsl
// keys.vert: one draw for all 88 keys
attribute vec3 aPos;      // metres, key at rest
attribute vec3 aNormal;
attribute float aSlot;    // key index / 4 (0..21)
attribute vec4 aSel;      // one-hot: which lane of uAngle[aSlot] drives this vertex
attribute vec2 aUv;       // key-local UV for analytic bevels
uniform vec4 uAngle[22];  // 88 key angles in radians (+ = front goes down)
uniform float uPivotY, uPivotZ;      // balance-rail line
uniform float uShiftX;               // una corda slide (grand)
uniform mat4 uMvp, uModel;
varying vec3 vN; varying vec3 vW; varying vec2 vUv;
void main() {
    float a = dot(uAngle[int(aSlot)], aSel);
    vec3 p = aPos - vec3(0.0, uPivotY, uPivotZ);
    float c = cos(a), s = sin(a);
    p = vec3(p.x + uShiftX, c * p.y - s * p.z, s * p.y + c * p.z) + vec3(0.0, uPivotY, uPivotZ);
    vec3 n = vec3(aNormal.x, c * aNormal.y - s * aNormal.z, s * aNormal.y + c * aNormal.z);
    vW = (uModel * vec4(p, 1.0)).xyz; vN = mat3(uModel) * n; vUv = aUv;
    gl_Position = uMvp * vec4(p, 1.0);
}
```

The same pattern covers the other parts:

- **Hammers:** a rotation about each hammer flange (`uHammer[22]`).
- **Dampers:** a translation along each damper wire (`uDamper[18]`).
- **Harpsichord:** jack rise `uJack[16]`, tongue angle `uTongue[16]` and register engagement flags.
- **Strings:** the spindle shader, `uAmp[22]` per note. Every string vertex carries (note slot, one-hot lane, t along the speaking length, side ±1, tangent), and the ribbon expands in screen space with a **minimum width of 1.2 px**. Real strings are sub-pixel and would otherwise vanish or crawl.

**Uniform totals:** each shader needs ≤ 23 `vec4` for state plus ~8 for matrices and lights. That is far under the ES 2.0 minimum of 128 vertex uniform vectors. Query `GL_MAX_VERTEX_UNIFORM_VECTORS` at start-up and log it; Adreno is typically ≥ 256 (UNVERIFIED on this unit).

**Per-frame CPU work:**

- Evaluate `state(t)` for ≤ 88 × 4 parts from event cursors, in primitive `FloatArray`s.
- Issue 5–8 `glUniform4fv` calls.

**Optional ES 3 variant:**

- One 128×4 `RGBA32F` "state texture", updated with a single `glTexSubImage2D` and read by vertex texture fetch in every shader, or a UBO.
- Instanced keys: 5 key-shape meshes, drawn with `glDrawElementsInstanced`.
- Either is fine. The skinning path above is the portable default.

**Anti-aliasing:**

- Try an **EGL config with 4× MSAA** first. Availability of a multisampled window config on this device is UNVERIFIED; `GL_EXT_multisampled_render_to_texture` is present as the FBO route.
- Thin geometry (key gaps, trellis, strings) is where 640×480 fails: it crawls when the head moves.
- Belt and braces: analytic bevels on keys, screen-space AA ribbons for every line (never raw `GL_LINES`; line width is driver-limited and not antialiased), and alpha-feathered decals.

### 5.4 Legibility rules at 640×480

| Item | Rule |
|---|---|
| Angular density | **26.4 px/deg** at the optical field. Minified views scale proportionally. |
| HUD text (in BinocularSbsLayout) | Titles **22 px** (≈ 0.83°), body **18 px**, never below **14 px**. Warm white (255,236,200) on nothing. No dark boxes: a box is invisible, and it only adds edges. |
| In-scene labels (GlyphBoard) | Billboards sized to a constant **16 px** tall (world height = 16 · d / f_px). MathCosmos found glyphs legible once sized; the rule is to keep labels beside the subject, not over it [V]. |
| Lines | Structural and feature edges **1.5–2.0 px**; strings ≥ 1.2 px with feathered alpha. Nothing important under 1 px. |
| Moving detail | A motion under ~3 px is not a readable event by itself. Pair it with a lighting change (bevel highlight lost, light gap, spec slide) or coherent group motion. Figures computed in §4: key dip 4.4–12 px, hammer 13–48 px, pedal 5.5 px, damper 1.7–6 px. |
| Brightness | Large areas ≤ 220; 255 only for pinpoints |
| Safe area | Keep critical content inside 600×440. Waveguide edge uniformity is UNVERIFIED; the sibling reserves the top quarter for the HUD and the bottom fifth for captions [V]. |

### 5.5 Thermal and quality ladder

Inherit the sibling governor: battery temperature plus PowerManager, stepping 30 → 20 → 15 fps [V].

| Quality | Changes |
|---|---|
| Q0 | Salon, mirror reflections, 160 crystals, MSAA on, 30 fps |
| Q1 | Stage level; reflections off; 60 crystals; MSAA off |
| Q2 | Instrument level; 20 fps |
| Q3 | Passthrough; 15 fps |

Battery-temperature thresholds (e.g. 40/43/46 °C) are **UNVERIFIED**. Reuse the sibling's numbers. APL is part of the heat budget because microLED power scales with lit pixels, so a Salon frame at 12% APL costs more display power than a Stage frame at 7%.

---

## 6. Open items to verify on the device

1. The visibility floor (§3.6 card). This decides `uFloor`, and whether lacquer needs the edge overlay by default.
2. APL of each view and instrument pair against §3.3.
3. Whether a 4× MSAA EGL window config exists, and `GL_MAX_VERTEX_UNIFORM_VECTORS`.
4. Stereo comfort of the IPD scales in §4.6. The waveguide's virtual image distance is unknown (UNVERIFIED).
5. Display latency for the audio-to-visual lead (§2.7).
6. Real-world dimension checks worth doing from photographs before building:
   - Konzertzimmer bay rhythm and mirror count.
   - Ceiling spoke count.
   - Grand plan outline.
   - Damper lift (≈6 mm assumed).
   - Harpsichord key dip.
   - Silbermann compass and hand-stop position.

---

## 7. Sources

**Venue**

- Köthen Spiegelsaal (1822, Bandhauer, 780 mirrors): [koethen-anhalt.de, Ludwigsbau](https://www.koethen-anhalt.de/de/ludwigsbau.html); [ak-lsa.de, Spiegelsaal](https://www.ak-lsa.de/objekt/schloss-koethen-spiegelsaal-neuinszenierung-koethen-anhalt/)
- Sanssouci Konzertzimmer (1746–47, Knobelsdorff, Nahl, Hoppenhaupt, Pesne, Dubois, Kambly): [Wikipedia (de), Schloss Sanssouci](https://de.wikipedia.org/wiki/Schloss_Sanssouci); [Wikipedia (en), Sanssouci](https://en.wikipedia.org/wiki/Sanssouci)
- Konzertzimmer ceiling (trellis, spider web, hunting cove): [Google Arts & Culture, Decke im Konzertzimmer](https://artsandculture.google.com/asset/decke-im-konzertzimmer-von-sanssouci-johann-august-nahl/CwGTeFVTXwcQZA?hl=de)
- Pesne's Ovid paintings for the concert room: [SPSG blog](https://www.spsg.de/blog/article/2016/05/29/eine-galante-szene-von-antoine-pesne); [museum-digital, Diana im Bade](https://brandenburg.museum-digital.de/object/7371?navlang=de)
- Potsdam Stadtschloss Konzertzimmer (c.1744, green walls, Bach 1747, burned 1945): [Wikipedia (de), Potsdamer Stadtschloss](https://de.wikipedia.org/wiki/Potsdamer_Stadtschloss)
- Menzel's *Flute Concert* (1852): [Wikipedia](https://en.wikipedia.org/wiki/Frederick_the_Great_Playing_the_Flute_at_Sanssouci)
- Amalienburg (16 bays, 10 mirrors): [süddeutscher-barock.ch](https://www.sueddeutscher-barock.ch/In-Werke/h-r/Nymphenburg_Amalienburg.html); [muenchen.de](https://www.muenchen.de/sehenswuerdigkeiten/orte/120418.html)
- Würzburg Kaisersaal frescoes (1751/52): [residenz-wuerzburg.de](https://www.residenz-wuerzburg.de/englisch/residenz/kaisers.htm); [wga.hu](https://www.wga.hu/html_m/t/tiepolo/gianbatt/5wurzbur/index.html)
- Café Zimmermann: [Wikipedia (de)](https://de.wikipedia.org/wiki/Zimmermannsches_Kaffeehaus)

**Instruments**

- Silbermann V 12 (materials, verbatim): [museum-digital SPSG 7516](https://brandenburg.museum-digital.de/object/7516?navlang=de)
- Silbermann V 13: [Google Arts & Culture](https://artsandculture.google.com/asset/hammerfl%C3%BCgel-gottfried-silbermann/aAGWIB4_YtCijw)
- Silbermann 1749 copy (FF–e3, hand stops): [fortepiano.eu](https://www.fortepiano.eu/gottfried-silbermann-fortepiano/)
- Silbermann instruments overview: [silbermann.org](https://silbermann.org/gottfried-silbermann/besaitete-tasteninstrumente/)
- Bach 1747 background: [Wikipedia, Gottfried Silbermann](https://en.wikipedia.org/wiki/Gottfried_Silbermann)
- Blanchet c.1740: [Yale School of Music collection](https://music.yale.edu/browse-collection/harpsichord-48761960)
- Blanchet copies: [John Phillips, French doubles](https://www.jph.us/instruments/french-harpsichords/); [Neupert Blanchet](https://www.jc-neupert.de/en/instruments/new-instruments/harpsichord-double-manual/new-instruments/harpsichord/harpsichord-double-manual/neupert-harpsichord-blanchet-2-detail)
- Ruckers key materials: [NMAH Ruckers virginal](https://americanhistory.si.edu/collections/object/nmah_606001)
- Jack motion: [hpschd.nu, How the harpsichord works](https://www.hpschd.nu/tech/act/jack.html)
- Harpsichord keyboard and jack figures: [John Sankey](https://johnsankey.ca/action.html); [pyirvin, early key sizes](https://www.pyirvin.com/Early_keyboard_key_sizes.html)
- Walter copy: [Neupert A. Walter](https://www.jc-neupert.de/en/component/virtuemart/new-instruments/fortepianos/fortepiano-a-walter-detail); [greifenberger-institut Walter 1782](https://www.greifenberger-institut.de/en/reverse-engineering/Walter-1782.php)
- Yamaha C5X (200 × 149 × 101 cm): [Chamberlain Music](https://www.chamberlainmusic.com/products/yamaha-c5x); [Yamaha CX series](https://uk.yamaha.com/en/musical-instruments/pianos/products/grand-pianos/cx-series/)
- Steinway D: [dimensions.com](https://www.dimensions.com/element/steinway-grand-piano-model-d); [Wikipedia, Steinway D-274](https://en.wikipedia.org/wiki/Steinway_D-274)
- Yamaha U1/U3: [Coach House Pianos](https://www.coachhousepianos.co.uk/blog/articles/yamaha-u1-vs-yamaha-u3/); [Mark Goodwin Pianos](https://markgoodwinpianos.co.uk/yamaha/yamaha-u3-piano-dimensions)
- Salamander = Yamaha C5, 16 velocity layers, 48 kHz / 24-bit: [studiorack/salamander-grand-piano](https://github.com/studiorack/salamander-grand-piano); [sfzinstruments/SalamanderGrandPiano](https://github.com/sfzinstruments/SalamanderGrandPiano)
- Keyboard dimensions: [Wikipedia, Musical keyboard](https://en.wikipedia.org/wiki/Musical_keyboard)
- Regulation (dip 10, blow 47, let-off 2, drop 1.5, check 15): [Grandwork key dip](https://grandwork.tools/blogs/chris/grand-regulation-key-dip-part-2); [professionalpianotunerlondon checklist](https://www.professionalpianotunerlondon.co.uk/post/grand-regulation-a-checklist-part-2)
- Upright blow 48 mm and let-off 3 mm: [piano-practice fundamentals, ch. 24](https://fundamentals-of-piano-practice.readthedocs.io/chapter1/ch1_procedures/II.24.html)
- Damper lifts at half key dip: [The Piano Deconstructed, action](https://www.piano.christophersmit.com/actionDetail.html)
- Undamped treble: [Kawai FAQ](https://www.kawai-global.com/support/faq/why-do-the-upper-18-notes-of-my-kawai-digital-piano-sustain-without-using-the-damper-pedal/); [Piano World forum, Steinway top 18 from G](https://forum.pianoworld.com/ubbthreads.php/topics/390731/how-many-high-notes-are-not-dampered-on-steinways.html)
- Pedal functions: [Wikipedia, Piano pedals](https://en.wikipedia.org/wiki/Piano_pedals)
- Key and hammer timing: [Askenfelt & Jansson 1990, JASA 88(1):52](https://pubs.aip.org/asa/jasa/article/88/1/52/626844/From-touch-to-string-vibrations-I-Timing-in-the); [Goebl, Bresin & Galembo 2005](https://iwk.mdw.ac.at/goebl/papers/Goebl-Bresin-Galembo_JASA2005_PianoAction.pdf)

**Platform**

- `/Users/me/Documents/FABLE_X3_STARTER_GUIDE.md` (spec sheet, APL < 13%, black = transparent)
- `/Users/me/Projects/MathCosmos/app/src/main/java/com/rayneo/mathcosmos/StereoMathRenderer.kt` (58° FOV, `EYE_OFFSET 0.035`, VBO meshes)
- `/Users/me/Projects/InnerCosmos/app/src/main/java/com/rayneo/innercosmos/StereoBodyRenderer.kt:3313` (gamma lift)
- On-device `dumpsys SurfaceFlinger` / `getprop` (Adreno 621, ES 3.2, extension list)
