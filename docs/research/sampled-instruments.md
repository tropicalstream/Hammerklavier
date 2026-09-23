# Hammerklavier: sampled instruments you can ship in the APK

Research date: 2026-09-22. Nothing was downloaded. Sizes come from GitHub tree API blob sizes, archive.org metadata, or HTTP HEAD `Content-Length`, and they are exact unless marked otherwise. Anything I could not check is marked **UNVERIFIED**.

The per-file download list is in `docs/research/sample-download-manifest.tsv`: 685 rows with group, need, filename, trigger key, bytes, licence, URL and remark.

---

## 0. Decisions in one table

| Voice | Recommended set | Licence | Why |
|---|---|---|---|
| Grand piano | **Salamander Grand Piano V3** (Yamaha C5), the sfzinstruments FLAC build (48 kHz / 24-bit), cut to 30 notes × 6 velocity layers, plus 88 key-release noises and 4 pedal noises | CC-BY 3.0. The author declared it public domain on 4 March 2022, so keep the credit anyway | Best free real grand: 16 layers, release and pedal samples, and each file can be fetched on its own |
| Upright piano | **VCSL "Upright Piano, Knight"** (sampled by Simon Dalzell / Ivy Audio for VSCO 2 Pro). 45 notes (every whole tone) × 2 layers, plus 45 releases and 8 pedal on/off noises. Optional softest layer from **VSCO-2 CE "Upright Piano"**, the same piano (23 notes) | CC0 1.0 | The only free upright with a close recording, several layers, release samples **and** pedal noise that is also redistributable |
| Bach-era keyboard | **VCSL "Harpsichord, Flemish"**: 8′ (28 notes) + 4′ (26 notes), each with its own release ("jack fall") samples. Optional: **VCSL "Harpsichord, English"** lute stop | CC0 1.0 | A real double-strung 8′+4′ harpsichord with releases, range FF–c‴ |
| 4th: fortepiano | **No redistributable set exists.** The best lead is Dore Mark's *Clementi 1808* (free to download, **no licence stated**), so you would need his written permission | n/a | See §3.4 |
| Container / codec | **Ogg Opus, 96–112 kbps stereo, 48 kHz**, encoded with the Mac's ffmpeg (libopus). The lossless fallback is 16-bit FLAC | n/a | The glasses have c2.android.opus, vorbis and flac decoders (checked on the device, §5). The mixer runs at 48 kHz |

Download totals: **786.0 MiB required** (557 files) and **103.0 MiB optional** (128 files). The estimated in-APK audio for all three voices is about **34 MB as Opus 96k** (about 45 MB at 128k). As 16-bit FLAC it would be about 285 MB (§6).

---

## 1. Grand piano

### 1.1 Salamander Grand Piano V3 (recommended)

- **Instrument / recording:** Yamaha C5 grand. Two AKG C414 microphones in an AB pair about 12 cm above the strings, recorded at 48 kHz / 24-bit. 16 velocity layers, sampled every minor third from the lowest A. Hammer-noise releases are sampled chromatically in one layer. String-resonance releases are sampled every minor third in three layers. Two pedal-down and two pedal-up samples. Author: Alexander Holm. Retuned tables by Markus Fiedler. The SFZ remap is by kinwie. (Sources: sfzinstruments README, archive.org description.)
- **Licence:** The distributions carry **CC-BY 3.0 Unported**, which requires attribution and has no share-alike. On his blog ("Salamander GrandPiano" page, rytmenpinne.wordpress.com), the author says that as of 4.3.2022 (European date, so 4 March 2022) the library is public domain. To be safe, credit "Salamander Grand Piano V3 by Alexander Holm, CC-BY 3.0" on the About screen and in `NOTICE`. That satisfies either reading. Bundling it in an APK is fine, and nothing flows back onto the app's code. The sfzinstruments README also asks that anything derived from *their SFZ mapping* say so; we only reuse offsets and tuning numbers, so credit kinwie as well.
- **Where to get it** (all verified):

| Distribution | Format | Size (bytes) | URL |
|---|---|---|---|
| sfzinstruments GitHub, `master` (**use this: one file at a time**) | FLAC 48k/24, 480 sustain + 88 rel + 69 harm + 4 pedal | whole repo about 731 MB (API `size` 730,959 KB). Samples total 748,451,483 | https://github.com/sfzinstruments/SalamanderGrandPiano (raw: `https://raw.githubusercontent.com/sfzinstruments/SalamanderGrandPiano/master/Samples/<file>`, with `#` URL-encoded as `%23`) |
| FreePats, same FLAC build as a tarball | SFZ+FLAC | 741,757,374 | https://freepats.zenvoid.org/Piano/SalamanderGrandPiano/SalamanderGrandPiano-SFZ+FLAC-V3+20200602.tar.gz |
| FreePats WAV 48k/24 | SFZ+WAV | 1,257,837,016 | …/SalamanderGrandPianoV3+20161209_48khz24bit.tar.xz |
| FreePats WAV 44.1k/16 | SFZ+WAV | 412,313,804 | …/SalamanderGrandPianoV3+20161209_44khz16bit.tar.xz |
| FreePats SF2 | SF2 | 310,397,984 | …/SalamanderGrandPiano-SF2-V3+20200602.tar.xz |
| archive.org 48k/24 | tar.bz2 | 1,448,107,335 | https://archive.org/details/SalamanderGrandPianoV3 |
| archive.org 44.1k/16 | tar.bz2 | 488,713,261 | same item |
| archive.org Ogg Vorbis | tar.bz2 | 78,010,482 | same item (already lossy, so do not re-encode it) |
| **Accurate-Salamander V6.2RC2** (remaster: all 88 notes retuned by a piano tuner, velocity continuity fixed, noise removed) | SFZ+WAV 48k/24 | "1.6 GB" per the site (Google Drive, size **UNVERIFIED**) | https://www.ir.isas.jaxa.jp/~cyamauch/AccurateSalamander/ (CC-BY 3.0) |

- **File naming** (sfzinstruments build, verified from the tree):
  - Sustain: `<Note><Octave>v<1..16>.flac`, with scientific octaves (C4 = MIDI 60). The 30 notes are A0 C1 D#1 F#1 A1 … F#7 A7 C8, which are MIDI 21, 24, 27 … 105, 108. 30 × 16 = 480 files, 726,974,711 bytes. One layer across all notes is 38.2 MB (v1) to 48.8 MB (v16).
  - Key-release noise: `rel1.flac` … `rel88.flac` = MIDI 21 … 108, one layer. 88 files, 5,609,688 bytes, about 64 KB each.
  - String-resonance releases: `harmL<note>` (velocity ≥ 45), `harmS<note>` (velocity ≤ 44) and `harmV3<note>` (a separate group), 23 notes each, A0…D#6 every minor third. 69 files, 14,009,758 bytes.
  - Pedal: `pedalD1/pedalD2` (pedal down, about 825–833 KB, a long resonant "whoomp") and `pedalU1/pedalU2` (pedal up, 68–78 KB). Two round robins each, at −20 / −19 dB in the SFZ.
  - Mapping data: `Data/region.txt` (each sampled note covers ±1 semitone). `Data/vel_01..16.txt` give **per-file leading-silence offsets** in samples at 48 kHz, for example A0v16 = 1754 (36 ms) and C4v16 = 727 (15 ms). Users complain of 20–25 ms of dead air, so trim with these offsets. `Data/tune_ret.txt` holds the "Retuned" cents per region, from +13 in the bass down to −38 at the top. The SFZ velocity splits for v1..v16 are 1–26, 27–34, 35–36, 37–43, 44–46, 47–50, 51–56, 57–64, 65–72, 73–80, 81–88, 89–96, 97–104, 105–112, 113–120, 121–127. The default `amp_veltrack` is 73 %.
  - **Keys 89–108 (F#6 and up) are undamped** on the real piano. The SFZ gives them a 3–4 s release instead of a damper cut. Our engine must do the same.
- **Phone kit** (the manifest's `grand-*` groups): 30 notes × layers **v1, v4, v7, v10, v13, v16** (180 files, 270,686,231 B), all 88 `rel*` (5,609,688 B), 4 `pedal*` (1,802,873 B), and the text data (54,201 B). Optional: `harmL*` + `harmS*` (46 files, 10,076,858 B) for the ring you hear when the pedal is down. Suggested velocity splits for 6 layers: 1–30 / 31–46 / 47–64 / 65–88 / 89–112 / 113–127, with about 73 % veltrack and a crossfade of about 4 velocity steps. A leaner 5-layer kit (v1, v5, v9, v12, v16) is 225,743,017 B.
- **Quality notes:** Users rank it alongside paid libraries (Pianoworld thread). The close AB pair gives an intimate, fairly bright sound, which suits a venue reverb added afterwards. Known faults: the leading silence (fixed by the offsets) and some tuning and layer-to-layer unevenness. Those are exactly what Accurate-Salamander fixes. If the user will take a 1.6 GB Google Drive download, the Accurate-Salamander V6.2RC2 WAVs are a drop-in upgrade under the same licence. The file layout inside that zip is **UNVERIFIED**.

### 1.2 Other grand candidates

| Set | Licence | Content | Size | Verdict |
|---|---|---|---|---|
| **VCSL "Grand Piano, Steinway B"** ("Joachim's Piano", files `JHPiano_*`) | CC0 | 42 notes every whole tone (A#0–G#7, MIDI 22–104) × 3 layers (vl2–4). **Separate pedal-down (`Sus`) and pedal-up (`NoSus`) recordings**, plus 99 releases up to D6. Samples are normalised, so dynamics must come from a velocity curve | Sus 770,082,030 + NoSus 540,116,370 + Rel 29,524,344 B (WAV) | Best CC0 alternative, and the only one with real pedal-down resonance. Fewer layers than Salamander. Keep as a fallback if CC-BY attribution were ever unwanted |
| VCSL "Grand Piano, Kawai" | CC0 | 38 notes, 4 layers, 84 releases | 443.8 + 16.1 MB WAV | OK. Notes are spaced unevenly |
| University of Iowa MIS Steinway B | "may be downloaded and used for any projects, without restrictions" | Every note Bb0–C8 × pp/mf/ff (260 AIFF files; a couple of notes are missing). Recorded 2001: 2 × Neumann KM84 8″ above the strings, 16-bit/44.1 kHz DAT. No releases or pedal. Files are untrimmed | about 1.34 GB (sum of the page's listed sizes) | Fallback only. DAT-era 16-bit, 3 dynamics |
| Headroom Piano (Bengt Nilsson, Yamaha C3) | CC-BY 4.0 | 30 notes × 5 layers × 2 microphones, no releases | 156 MB FLAC (GitHub sfzinstruments/BengtNilsson.HeadroomPiano) | Good. No release or pedal samples |
| FreePats YDP Grand (Yamaha Disklavier Pro) | CC-BY 3.0 | SF2 only | 36,737,684 B | Small, lower fidelity |
| Ivy Audio *Piano in 162* | Free to use, **redistribution forbidden** | Steinway B, 5 layers, 2 round robins | 4.9 GB | ❌ |
| Maestro Concert Grand (Mats Helgesson) | custom | Yamaha CF-3 | 268 MB | ❌ licence does not permit redistribution (**UNVERIFIED**, not pursued) |

---

## 2. Upright piano

### 2.1 VCSL "Upright Piano, Knight" + VSCO-2 CE "Upright Piano" (recommended)

- **Provenance:** Sampled by Simon Dalzell of Ivy Audio for the VSCO 2 Pro sample set (VCSL `Info.txt`). Sampled every whole tone from the lowest A. The **Knight** name is from VCSL; the exact maker and model (probably Alfred Knight, England) are **UNVERIFIED**.
- **Licence:** CC0 1.0 (repository `LICENSE`, README: "no royalties, no credit"). VSCO-2 CE is also CC0, with a request to credit Versilian Studios / Sam Gossner and Ivy Audio / Simon Dalzell and not to sell the samples on their own. Shipping them inside a free app is fine.
- **Files (VCSL `master`):**
  - `Sustains/Player_vl{1,2}_rr1_<Note>.wav`: 45 notes A-1 … C7 **in Yamaha octave naming** (A-1 = MIDI 21, C7 = MIDI 108). 90 files, 364,952,496 B. VCSL velocity split: vl1 0–83, vl2 84–127. The SFZ gives keys 101–109 a 10 s release (undamped treble).
  - `Releases/Player_rel_rr1_<Note>.wav`: 45 files, 46,315,218 B (about 1 MB each, so long releases with the damper and room tail).
  - `Pedal/On/Player_PedOn_000..003.wav` and `Pedal/Off/Player_PedOff_000..003.wav`: 8 files, 8,495,984 B. Four round robins each, fired on CC64 crossing 64.
- **Optional softer layer:** VSCO-2 CE `Keys/Upright Piano/Player_dyn1_rr1_000..044(even).wav`, 23 files, 65,298,056 B, sampled every major third (MappingChart: 000 = 21, 002 = 25 … 044 = 108). File sizes show that VSCO-2 CE `dyn2`/`dyn3` are byte-identical to VCSL `vl1`/`vl2`, so VCSL adds notes (whole tones) and `dyn1` adds a pp layer. With it, the kit has three layers: pp (major thirds) / mf / f.
- **Sample rate / bit depth: UNVERIFIED.** The VCSL rules say 44.1 or 48 kHz and 16 or 24-bit. Check with `ffprobe` after download and resample to 48 kHz in the build step if needed.

### 2.2 Other upright candidates

| Set | Licence | Content | Size | Verdict |
|---|---|---|---|---|
| FreePats **Upright Piano KW** (Kawai upright, living room, 2017, **one Zoom H1 handheld recorder** at the player's head) | CC0 1.0 | 2 layers (vL ≤ 80, vH ≥ 81). About 30 + 37 notes, roughly every minor third. **Bass notes are looped.** No release or pedal samples | SFZ+FLAC 33,294,287 B (`UprightPianoKW-SFZ+FLAC-20220221.7z`); WAV 72,182,294; SF2 28,832,466; "small" 3,092,715. GitHub freepats/upright-piano-KW | Usable fallback. Consumer microphone, no releases |
| VCSL "Upright Piano, Yamaha" (= VS Upright No. 1, remastered) | CC0 | Only **13 notes** (C and G of each octave) × 3 layers × round robins, 71 releases | 241.6 + 6.5 MB | ❌ too sparse (shifts of ±3.5 semitones) |
| KeyPleezer LivingRoom Upright "micro" | sfzinstruments lists CC-BY-SA 4.0, **but the vendor EULA forbids any redistribution** | 132 FLAC files, 2 layers × pedal on/off | 107 MB | ❌ conflicting licence, so treat it as not redistributable |
| Pianobook uprights (Florence, Helsinki 1967, Kamoe…) | Pianobook EULA: no redistribution of samples you do not own | n/a | n/a | ❌ unless each author gives written permission |
| freesound beskhu "Upright piano multisamples" (Choiseul) | per-sound licence, **UNVERIFIED** | only about 12 notes A3–G#4, Zoom recorder | n/a | ❌ incomplete |

---

## 3. Bach-era keyboard

### 3.1 VCSL harpsichords (recommended source: CC0)

All five harpsichords are single-velocity (as harpsichords are). Every stop has its own **release ("jack fall" / damper) samples**. Files are named in **Yamaha octave naming** (C3 = MIDI 60): the VCSL SFZ maps `A#0` to key 34.

| Set (VCSL folder) | Registers | Sampled notes → key range | Releases | Size (WAV) |
|---|---|---|---|---|
| **Flemish** (`HarpsiRH_*_Far`, maker **UNVERIFIED**) | **8′** (`Sustains/Low`, 28 notes F#0…C5, every whole tone) and **4′** (`Sustains/High`, 26 notes, sounding an octave higher). VCSL ships 8′, 4′, 8′+4′ ("Full") and keyswitch SFZs | keys 29–84 (FF–c‴) | 28 (8′) + 26 (4′) | 65,844,716 + 45,570,610 + 8,180,150 + 6,587,614 B |
| English (Zuckermann kit, `ZuckermannKitHarpsi_*`) | **Normal 8′** and **Lute** (buff) stop | keys 34–89 | 28 Normal + 26 Lute | 54.4 + 27.6 + 7.0 + 5.0 MB |
| French (`Harpsi2_*`) | one stop | keys 24–84 | 28 | 87.8 + 9.4 MB |
| Italian (`Harpsichord_stop1_*`) | one stop | keys 29–84 | 31 | 62.5 + 12.4 MB |
| "Unk" (`Harpsi4_*`) | one stop, **chromatic, every key** | keys 29–89 (FF–f‴, 61 keys) | 61 (all the same length, so probably cut to a fixed length) | 150.5 + 12.9 MB |

**Pick:** Flemish 8′ + 4′ with releases. That is the double-strung Franco-Flemish sound that German instruments of Bach's time (Hass, Mietke, Zell) grew out of. You get three registrations: 8′, 4′ and 8′+4′ together (play both samples). Add the English **lute stop** as an optional colour for the "buff" registration.

Two things to check once downloaded: the pitch standard (A = 440 or 415, **UNVERIFIED**) and the sample rate. Harpsichord realism also depends on **temperament**. Pitch is just playback rate, so the engine can apply per-pitch-class cents for Werckmeister III / Kellner / Vallotti at no cost.

### 3.2 Soni Musicae Blanchet 1720 and "Small Italian" (excluded)

These are excellent and widely praised (Blanchet: one manual, two 8′ stops and a lute stop, one sample per note lasting 4–15 s, releases in the Kontakt version only. Recorded at A = 415 and shifted to 440. SF2 zip 65,849,152 B, Kontakt zip 152,715,388 B). But the licence (blanchet-Licence-en.html) **forbids modifying the samples, putting them in any commercial application, or redistributing them without permission**. You could only use them with written permission from Eric Bricet and Jean-Yves Garet (sonimusicae@free.fr).

### 3.3 Clavichord

There is no redistributable multisampled clavichord. The only freesound hit is a 16-sound "Clavicordio" pack (Thalamus_Lab), CC-BY-NC and incomplete; the "CS-80 clavichord" results are a synthesiser.

### 3.4 Fortepiano (Silbermann / Stein / Walter): none redistributable

I searched sfzinstruments, VCSL/VSCO, FreePats, Musical Artifacts (JSON API), freesound, KVR, VI-Control and Pianobook, and found no CC0 or CC-BY fortepiano multisample.

- **Clementi 1808** (San Francisco State University collection), sampled by **Dore Mark** in 2023. It has 4 velocity layers per the Soundfonts 4U description, release samples and damper-pedal noise; felt hammers were fitted in a 1960s restoration. It is an SF2 of 248 MB (Soundfonts 4U) and a 1.44 GB SFZ/Kontakt zip (pianoclack.com thread 908). **No licence is stated.** A Hugging Face mirror labels the whole collection CC-BY-NC-SA 4.0, but the mirror has no authority to license it. It is also an English-action London piano, not Viennese. Usable **only with Dore Mark's written permission**.
- The commercial options (Edition Beurmann / REALSAMPLES Pianoforte, Bolder Sounds BOB Fortepiano, Pianoteq historical models) cannot be redistributed.
- Recommendation: ship three instruments now. Offer a fourth, "Fortepiano (Clementi 1808)", only if the user asks for and gets permission. Do not fake a fortepiano by filtering the Salamander, because the brief asks for real samples.

---

## 4. Download manifest (for the approval request)

Full per-file list: `docs/research/sample-download-manifest.tsv`. Every URL there was built from the repository trees, and samples were checked with HEAD (HTTP 200, `Content-Length` matches the tree size).

| Group | Need | Files | Bytes | Source URL pattern | Licence |
|---|---|---|---|---|---|
| grand-sustain (30 notes × v1,4,7,10,13,16) | required | 180 | 270,686,231 | `raw.githubusercontent.com/sfzinstruments/SalamanderGrandPiano/master/Samples/<N>v<L>.flac` | CC-BY 3.0 / PD |
| grand-release `rel1..88` | required | 88 | 5,609,688 | same repo | CC-BY 3.0 / PD |
| grand-pedal `pedalD1,D2,U1,U2` | required | 4 | 1,802,873 | same repo | CC-BY 3.0 / PD |
| grand-docs (README, LICENSE, .sfz, Data/*.txt) | required | 26 | 54,201 | same repo | CC-BY 3.0 |
| grand-string-resonance `harmL*`, `harmS*` | optional | 46 | 10,076,858 | same repo | CC-BY 3.0 / PD |
| upright-sustain (Knight vl1, vl2) | required | 90 | 364,952,496 | `raw.githubusercontent.com/sgossner/VCSL/master/Chordophones/Zithers/Upright%20Piano%2C%20Knight/Sustains/…` | CC0 |
| upright-release | required | 45 | 46,315,218 | …/Knight/Releases/… | CC0 |
| upright-pedal (On ×4, Off ×4) | required | 8 | 8,495,984 | …/Knight/Pedal/{On,Off}/… | CC0 |
| upright + VCSL docs | required | 4 | 11,973 | VCSL master | CC0 |
| upright-sustain-pp (VSCO-2 CE dyn1) + docs | optional | 27 | 65,307,007 | `raw.githubusercontent.com/sgossner/VSCO-2-CE/master/Keys/Upright%20Piano/…` | CC0 |
| harpsichord-8ft + releases (Flemish Low) | required | 56 | 74,024,866 | …/Harpsichord%2C%20Flemish/{Sustains,Releases}/Low/… | CC0 |
| harpsichord-4ft + releases (Flemish High) | required | 52 | 52,158,224 | …/Harpsichord%2C%20Flemish/{Sustains,Releases}/High/… | CC0 |
| harpsichord-lute + releases (English) | optional | 54 | 32,583,948 | …/Harpsichord%2C%20English/{Sustains,Releases}/Lute/… | CC0 |
| VCSL SFZ maps (Knight, Flemish Full/4′/8′, English keyswitch) | 4 req + 1 opt | 5 | 68,393 | `raw.githubusercontent.com/sgossner/VCSL/sfz/…` | CC0 |
| **Total** | | **685** | **932,147,960** (required 824,163,248 = 786.0 MiB, optional 107,984,712 = 103.0 MiB) | | |

Bulk alternatives, if one file each is preferred over about 700 requests:

- **VCSL_Keys.zip** (all 5 VCSL pianos + 5 harpsichords as FLAC, 1,466 samples): 655,370,475 B, https://versilian-studios.com/Distro/VCSL_Keys.zip (HEAD verified, Last-Modified 2023-10-11). Its internal layout is **UNVERIFIED**. It covers every VCSL group above plus the Steinway B and Kawai, but not the VSCO-2 CE dyn1 layer.
- The FreePats Salamander FLAC tarball (741,757,374 B) instead of the 272 single Salamander files (278 MB).

---

## 5. Can Android 12 on the glasses decode these to PCM? Yes (checked on the device)

I read these over adb from the RayNeo X3 Pro (`A06B4A96A733283`, Android 12). All reads were read-only.

- Codecs (`/apex/com.android.media.swcodec/etc/media_codecs.xml`, `/vendor/etc/media_codecs_google_c2_audio.xml`, `/vendor/etc/media_codecs_c2_audio.xml`):
  - `c2.android.flac.decoder`: sample rates 1–655,350, up to 8 channels.
  - `c2.qti.flac.sw.decoder`: 8–192 kHz, **at most 2 concurrent instances**. It may be picked first by `createDecoderByType`, so decode one file at a time or request `c2.android.flac.decoder` by name.
  - `c2.android.opus.decoder`: **48 kHz only**, 6–510 kbps.
  - `c2.android.vorbis.decoder`: 8–96 kHz, 32–500 kbps.
- Audio output (`dumpsys media.audio_flinger`): mixer at **48,000 Hz**, HAL PCM 16-bit, float processing. So author all assets at **48 kHz**; that also avoids the 48 → 44.1 downsampler, which Android's docs say has no low-pass filter.
- Android's documented support: FLAC decode since 3.1 (container `.flac`, 16-bit recommended, 24-bit is not dithered). Vorbis in Ogg on all versions. Opus decode since 5.0, Ogg container supported. `minSdk 29` covers Ogg Opus via `MediaExtractor`.
- The Mac's ffmpeg has **libopus** and FLAC but only the experimental built-in Vorbis encoder (no libvorbis), and no flac/sox/oggenc/opusenc. So **Ogg Opus** is the lossy choice that needs no new install.
- Pipeline: at first launch, `MediaExtractor` → `MediaCodec` reads each Opus file once and writes 16-bit interleaved PCM. Avoid AAC, whose encoder priming delay hurts attack timing. The Opus decoder drops the 312-sample pre-skip itself.

---

## 6. Estimated in-APK size and RAM

Trim model: piano sustain 14 s at A0 falling linearly to 3 s at C8 (upright 12 → 3 s, harpsichord 8 → 3 s). Leading silence cut with the Salamander offsets. 300 ms fade-out. Releases 0.4 s (grand), 1.0 s (upright), 0.6 s (harpsichord). Pedal 4.5 s down, 0.5 s up. All stereo at 48 kHz. These durations are targets, not measured source lengths. Real sustain lengths are **UNVERIFIED** and estimated from FLAC sizes: Salamander A0v16 is 2.86 MB of 24-bit FLAC, about 16 s.

| Kit | Files | Audio sec | Opus 96k | Opus/Vorbis 128k | FLAC 16-bit (≈100 kB/s) | Decoded PCM16 stereo |
|---|---|---|---|---|---|---|
| Grand, Salamander 6 layers | 272 | 1575 | 18.0 MB | 24.0 MB | ≈150 MB | 288 MB |
| Grand, Salamander 5 layers | 242 | 1320 | 15.1 MB | 20.1 MB | ≈126 MB | 242 MB |
| Upright, Knight 2 layers | 143 | 744 | 8.5 MB | 11.4 MB | ≈71 MB | 136 MB |
| Upright + pp layer | 166 | 914 | 10.5 MB | 13.9 MB | ≈87 MB | 167 MB |
| Harpsichord, Flemish 8′+4′ | 108 | 332 | 3.8 MB | 5.1 MB | ≈32 MB | 61 MB |
| + English lute | 162 | 490 | 5.6 MB | 7.5 MB | ≈47 MB | 90 MB |
| **All three (6L grand, pp upright, with lute)** | 600 | 2979 | **≈34 MB** | ≈45 MB | ≈284 MB | 545 MB total, **≤ 288 MB per instrument** |

**RAM note** (read from the device): `ro.config.low_ram=true`, `dalvik.vm.heapgrowthlimit=192m` (512m with largeHeap), about 2.6 GB available, 19 GB free on `/data`. Decoded samples must not live on the Java heap. Suggested approach:

1. At first launch of an instrument, decode it once to `files/pcm/<instrument>.pcm`.
2. Map that file with `FileChannel.map` (read-only; it lives in reclaimable page cache).
3. Load only the active instrument.
4. Pre-touch the first ~150 ms of every sample so note-ons never page-fault on the audio thread. For the grand that is about 272 × 0.15 s × 192 kB/s ≈ 8 MB.

That keeps working memory under about 300 MB and leaves the kernel free to evict tails. If memory is still tight, drop to 5 grand layers or cap bass tails at 10 s.

---

## 7. Attribution text for the About screen / NOTICE

- Grand piano: *Salamander Grand Piano V3* by Alexander Holm (CC-BY 3.0; declared public domain by the author, 2022). Retuned tables by Markus Fiedler; SFZ mapping data by kinwie (sfzinstruments).
- Upright piano and harpsichords: *Versilian Community Sample Library (VCSL)* and *VS Chamber Orchestra 2: Community Edition*, Versilian Studios LLC / Sam Gossner. Upright sampled by Simon Dalzell (Ivy Audio). CC0 1.0.

## 8. Sources

sfzinstruments/SalamanderGrandPiano (README, LICENSE, Data/*.txt, tree API) · archive.org/metadata/SalamanderGrandPianoV3 · freepats.zenvoid.org/Piano/acoustic-grand-piano.html · rytmenpinne.wordpress.com/sounds-and-such/salamander-grandpiano · ir.isas.jaxa.jp/~cyamauch/AccurateSalamander · github.com/sgossner/VCSL (master + sfz branches, README, LICENSE) · versilian-studios.com/vcsl-keys · github.com/sgossner/VSCO-2-CE (Readme.txt, Keys/Upright Piano/Info.txt, MappingChart.txt) · github.com/freepats/upright-piano-KW · theremin.music.uiowa.edu/MISpiano.html and MIS.html · sfzinstruments.github.io/pianos (+ headroom_piano, livingroom_upright_micro_sfz) · keypleezer.com EULA · pianobook.co.uk terms · sonimusicae.free.fr (blanchet1-en, blanchet-Licence-en, petititalien-en) · pianoclack.com/forum/d/908 · huggingface.co/datasets/projectlosangeles/soundfonts4u · musical-artifacts.com API · freesound.org searches · developer.android.com/media/platform/supported-formats · on-device adb reads (codec XMLs, audio_flinger, getprop, df).
