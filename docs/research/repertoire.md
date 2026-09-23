# Hammerklavier: the bundled repertoire (early Bach to Beethoven)

Research date: 2026-09-22. Scope: which keyboard works from about 1700 to 1827 we can legally bundle as MIDI in the APK, where each file lives, what its licence requires in the app, and which pieces we still cannot cover.

Legend. **VERIFIED** means I read it on the source page, or an HTTP HEAD returned 200 with the stated size, on 2026-09-22. **UNVERIFIED** means I inferred it or could not check it without downloading the file. No MIDI file, archive or sample was downloaded. Only HTML pages were read and HEAD requests sent.

---

## 0. Decisions in one screen

| Decision | Why |
|---|---|
| **Piano backbone: Bernd Krueger, piano-midi.de** (91 files, 33 works, 7 h 24 min, about 2.3 MB). | These are the most expressive freely licensed classical piano MIDIs available. They are step-sequenced by hand with velocity, tempo shaping and sustain-pedal data. The licence is **CC BY-SA 3.0 DE** (VERIFIED on copy.htm). The set covers Haydn, Mozart, Clementi, three Bach preludes and fugues, and ten Beethoven works including the **Hammerklavier op. 106, all four movements**. |
| **Harpsichord backbone: John Sankey** (Bach and all 555 Scarlatti sonatas). | Real-time performances played on a MIDI keyboard by a professional harpsichordist. The notice permits free copying and distribution of the unmodified originals if the notice travels with every copy (VERIFIED on johnsankey.ca/copyright.html). The set covers the Inventions, Sinfonias, all of WTC I, WTC II 1–12, the Goldberg Variations, the Italian Concerto, BWV 903, the French and English Suites, the Partitas, the Toccatas and the early Capriccio BWV 992. |
| **Gap fillers**: Wikimedia Commons (Michael Bednarek's CC0 piano *performance captures*: Beethoven op. 126, Mozart K. 281; his public-domain Mozart K. 265 and Beethoven op. 33), Mutopia public-domain engravings (Rameau, C.P.E. Bach Rondo, Mozart K. 397 and K. 457, Beethoven op. 31/2 i–ii, op. 49/2, op. 111 i, op. 129), IMSLP / Pierre Gouin CC BY-SA 4.0 (Handel HWV 430, Rameau *La Poule*), Commons CC BY-SA 3.0 (C.P.E. Bach *Solfeggietto*). | Each item was checked one by one. None is non-commercial or no-derivatives. |
| **Not bundled**: MAESTRO and SMD (both **NC**), Kunst der Fuge (at most 10 files, no profit), GiantMIDI / ATEPP / Aria-MIDI (automatic transcriptions of commercial recordings), Craig Sapp's Humdrum sonatas (Mozart and Haydn are CC BY-NC-SA; the Beethoven repository has no licence), Classical Archives (subscription), MuseScore.com and 8notes (site terms of service). | Each of these has a licence or provenance problem for files we redistribute. The user can still load any of them through **Import**. |
| **Library size: 70 works** (69 confirmed, 1 conditional) in 13 categories, about 11–12 h of music. The MIDI assets come to **under 5 MB**. | This is at the upper edge of the 40–70 target. The last section lists what can be dropped to get closer to 60. |
| Default instrument: harpsichord for Bach (Sankey), Scarlatti, Couperin, Rameau, Handel, C.P.E. Bach's *Solfeggietto* and early Haydn (Hob. XVI:7–9). **Grand** for everything else. | The instruments research found **no redistributable fortepiano**, so "fortepiano if available" becomes grand for Haydn, Mozart and Clementi. The upright is offered as a "parlour" alternative for the teaching pieces (Clementi, K. 545, Für Elise). |

---

## 1. Access problems the download step must handle

1. **piano-midi.de blocks this Mac.** Every request returns `HTTP 418 I'm a teapot` with an empty body: HEAD or GET, from curl with any user agent and full browser headers, and from the Claude Browser pane. DNS resolves to 82.165.134.185 and there is no proxy. HTTPS returns 404. The block is probably by IP or network. The site is still alive, because the Wayback Machine captured it on 2026-08-13.
   **Fallback (VERIFIED):** raw Wayback URLs of the form `https://web.archive.org/web/<timestamp>id_/http://www.piano-midi.de/midis/<composer>/<file>.mid` serve the original bytes. HEAD returned 200 `audio/midi` for four spot-checked files, and each size matches the site's KB listing: hammerklavier_1 is 74,228 B (73 KB listed), mz_331_3 is 26,436 B (26 KB), clementi_opus36_1_1 is 11,381 B (12 KB) and haydn_35_1 is 37,264 B (37 KB). The manifest gives the capture with the **newest distinct digest**, which matches the 2011–2015 re-releases dated on the site. It also gives its **SHA-1 digest (base32)**. After download, check each file with `base64.b32encode(hashlib.sha1(data).digest())`. Try the primary URL first, because it may work from another network.
2. **IMSLP** `Special:ImagefromIndex/<id>` sends a JavaScript "friendly redirect" to a disclaimer page with an **"I understand"** button. Clicking it accepts terms, so **the user must click it**, or explicitly allow Claude to. After that the .mid file downloads. I did not click it, so the IMSLP file names and byte sizes are UNVERIFIED. The page does give the size and duration.
3. **Sankey Bach** files are plain HTTP zips on jsbach.net, and the **Sankey Scarlatti** set is one zip on johnsankey.ca. The file names inside the zips are UNVERIFIED because a HEAD request cannot list them.
4. **Wikimedia Commons** (upload.wikimedia.org) and **Mutopia** are plain HTTPS and HEAD returned 200. Commons expects a descriptive User-Agent.
5. The Wayback CDX API returned **503** under parallel load. Run downloads one at a time with a pause of 2 s or more.

---

## 2. Sources evaluated

| Source | What it is | Licence (VERIFIED unless marked) | Pedal data | Verdict |
|---|---|---|---|---|
| **piano-midi.de** (Bernd Krueger) | Hand step-sequenced "interpretations" made in Cakewalk Sonar with velocity, tempo and dynamics. They are not real-time performances: Krueger says on technic.htm that he entered them note by note. Format 1 (one track per channel) plus a Format 0 copy. Published 1996–2018; no updates planned. | **CC BY-SA 3.0 DE** since 2007-04-01 (faq.htm, copy.htm). Credit the name "Bernd Krueger" and the source "http://www.piano-midi.de". Distribution and public playback only under the same licence. | **Yes (strong secondary evidence).** The primanota project rebuilds its derivatives from the files' "note pitch/duration and pedal data", and these files were used to build the MAPS dataset. Confirm by counting CC64 events after download. Soft pedal (CC67): UNVERIFIED. | **BUNDLE, tier A.** No Scarlatti, Handel, Couperin, Rameau, C.P.E. Bach, Beethoven op. 31/109/110/111 or late Mozart. |
| **John Sankey** (jsbach.net, johnsankey.ca) | A Canadian harpsichordist who played the pieces into MIDI in real time on a MIDI keyboard in the 1990s ("with only a basement room, a MIDI keyboard, an early (8088) PC"). Bach: 15 zips plus one .mid. Scarlatti: all 555 sonatas in `data/scarlatti.zip`. | **Sankey free-copy notice** (copyright.html). Anyone may copy, distribute and play the files if the notice goes with every copy; a link to johnsankey.ca/harpsichord.html is enough online. No one may restrict further use, including by collection copyright. Only his **unmodified originals** may be passed on. Any **audio** distribution must be rendered with his own soundfont on a SoundBlaster-compatible system. | No. A harpsichord has no damper pedal. Whether the files carry key velocity or release velocity is UNVERIFIED. | **BUNDLE, tier A (harpsichord).** Keep the files byte-identical. **Turn off audio and video export for these tracks.** Warning: Sankey says an unnamed third party re-tempoed his Bach files and republished them as "piano" versions. Use only his originals from jsbach.net. |
| **Mutopia Project** | Volunteer LilyPond engravings. The MIDI is generated from the score, so it is quantised, with velocity taken from the dynamics. | Licence varies per piece: Public Domain, CC BY 2.5/3.0/4.0, or CC BY-SA 2.5/3.0/4.0. Every file I chose is **Public Domain**. | Only where the edition prints *Ped.* marks. LilyPond's `Piano_pedal_performer` emits CC64 for `\sustainOn`/`\sustainOff`, according to the lilypond-user list in 2020. Whether each chosen file has any is UNVERIFIED. | **BUNDLE, tier C**, for gaps only. Label these as engraved, not performed. |
| **Wikimedia Commons** | Mostly short excerpts made for Wikipedia. The useful full pieces are by **Michael Bednarek**: his 2026 uploads are described as "MIDI capture from piano performance" and released CC0, and his 2009 uploads are PD-self with the method not stated. Also Shane Nieb's *Solfeggietto* and Ricardo André Frantz's Rameau *Tambourin*. | Per file: CC0, PD-self, CC BY-SA 3.0 or GFDL, as shown in the table below. | The Bednarek captures probably have pedal (UNVERIFIED). The others have none. | **BUNDLE**: tier A for the Bednarek captures, tier B for the rest. |
| **IMSLP** | Per-work "Synthesized/MIDI" sections. Most useful are **Pierre Gouin**'s (Les Éditions Outremontaises, Montréal) harpsichord MIDIs. | Per file. The Gouin files are **CC BY-SA 4.0**. Many others are CC BY-NC-SA and are excluded. | None seen, since these are harpsichord sequences. | **BUNDLE, tier B**: HWV 430 (#365752) and *La Poule* (#340106). Needs the disclaimer click (§1.2). |
| **David Madore** (madore.org) | Pieces entered by hand in Cakewalk around 1998, with General Standard patches. | **No explicit licence.** He believes none of the files is under copyright, and he dedicated only his own compositions to the public domain. | No. | **CONDITIONAL** for *Les Barricades mystérieuses*, the only full Couperin keyboard MIDI I found under a non-NC licence. Email him for an explicit CC0 or drop it. |
| **Kunst der Fuge** | Around 19,000 files from many sequencers. | You may redistribute **at most 10 files** with a link back. Re-mastering, playback or redistribution "for profit" is forbidden. A subset of 2,192 files is CC BY-NC-SA. | Varies. | **EXCLUDE** from the bundle. The user may import files for personal use. |
| **MAESTRO v3.0.0** | About 200 h of Yamaha e-Competition performances on a Disklavier, captured with sustain, sostenuto and una corda pedals. The MIDI-only zip is 58,416,533 B (HEAD VERIFIED) at `https://storage.googleapis.com/magentadata/datasets/maestro/v3.0.0/maestro-v3.0.0-midi.zip`. | **CC BY-NC-SA 4.0**. | Yes (CC64, CC66, CC67). | **EXCLUDE** (NC). This is the best source for an optional "non-commercial study pack" or for import. |
| **SMD** (Saarland Music Data) | Student performances recorded on a Yamaha Disklavier, including Bach BWV 849, 871, 875 and 888, Beethoven op. 27/1, **op. 31/2** and **WoO 80**, Haydn **Hob. XVI:52**, and Mozart K. 265 and K. 398. | **CC BY-NC-SA 3.0**. | Yes (sustain and soft). | **EXCLUDE** (NC). It is optional because it fills several gaps. |
| **GiantMIDI-Piano** | About 10k automatic transcriptions of YouTube recordings. | The repository says CC BY 4.0, but the underlying performances belong to their performers and labels. The repository has been archived since 2025-04-08. | Yes (transcribed). | **EXCLUDE**, because of the provenance risk and transcription errors. ATEPP, Aria-MIDI and PianoCoRe (arXiv 2605.06627, which shows a CC BY 4.0 icon but combines several corpora; UNVERIFIED) are excluded for the same reason. |
| **PDMX** (Zenodo v6) | About 250k MuseScore scores marked CC0 or public domain. The dataset itself is CC BY 4.0. Files: `mxl.tar.gz` 1.9 GB, `PDMX.csv` 209.6 MB. **No MIDI** is included, so each score would need converting from MXL. | CC0/PD content, with the terms-of-service caveat that 31,221 files have a licence conflict. | Only if the score has pedal marks. | **Phase-2 gap filler only**, because the download is large. Quality varies by uploader. |
| **Craig Sapp Humdrum** (GitHub) | Complete encodings of all 32 Beethoven sonatas, the Mozart sonatas and the Haydn sonatas. | Mozart and Haydn: **CC BY-NC-SA 4.0** (LICENSE.txt). Beethoven: **no licence file**, which means all rights reserved. | Only from the score. | **EXCLUDE.** This would be the cleanest route to op. 109 and op. 110 if Sapp grants permission. |
| Classical Archives, MuseScore.com, 8notes, midiworld, bitmidi | | Subscription, site terms of service, or unknown provenance. | | **EXCLUDE.** |

---

## 3. What each licence obliges the app to do

1. **CC BY-SA 3.0 DE (Krueger).**
   - Every copy needs the licence text or its URI (§4a).
   - The credit must give the rights holder's name, the title and the source URI (§4c).
   - No technical protection measures (DRM) may stop users exercising the licence (§4a). A normal APK asset is fine.
   - Shipping the unmodified .mid files inside the app is a *Sammelwerk* (collection), so the app's code keeps its own licence.
   - **Converting the files at build time** (for example into a binary event format) creates an *Abwandlung* (adaptation). Those converted files must also be released CC BY-SA 3.0 DE, with a note that they were changed. **Simplest: ship the original .mid files and parse them at runtime.**
   - The licence names "Heranziehung … zur Vertonung von Laufbildern" (using the work as the soundtrack of moving images) as an adaptation. A **screen recording or video** of a Krueger piece with animated keys and hammers therefore has to be shared CC BY-SA 3.0 DE with the credit. Private viewing on the glasses is not a problem. If we add a "record clip" feature, it should stamp the credit onto the clip.
2. **Sankey.**
   - Put his notice in `assets/licenses/SANKEY.txt`. Copy it verbatim from https://www.johnsankey.ca/copyright.html at download time instead of retyping it.
   - Ship the files **byte-identical**: no re-save, no quantising, no merging of tracks.
   - Place no restriction on the files, which is automatic in a public GitHub repository.
   - **No audio or video export of Sankey tracks**, because his audio clause requires his own soundfont. Live playback on the device is not distribution. That reading is mine and UNVERIFIED legally.
   - Set `exportAllowed=false` in the catalogue for these tracks.
3. **CC BY-SA 4.0 (Gouin)** and **CC BY-SA 3.0 / GFDL (Nieb)**: credit plus licence URI. Adaptations must stay under the same licence.
4. **CC0 or public domain (Bednarek, Frantz, Mutopia PD)**: no obligation. Give a courtesy credit anyway.
5. **Imported files** are the user's responsibility. The app should only play them, and never upload them anywhere.

---

## 4. The catalogue (70 works)

Column key.
- **Default**: H = harpsichord (VCSL Flemish, range FF–c‴ = MIDI 29–84 per the instruments research), G = grand, U = upright.
- **Pedal**: whether the file has CC64 sustain events.
- **Tier**: A = performed, or deeply hand-shaped with pedal; B = hand-sequenced with some expression; C = engraving export (quantised).
- Durations come from the source page where given. `~` marks my estimate, to be replaced after the files are parsed.
- The "Source" column is shorthand. The full URLs are in the §7 manifest.
- Era tags: *baroque* up to about 1750, *galant* from about 1740 to 1770 (including *Empfindsamkeit*), *classical* from about 1770 to 1827.

### 4.1 "Start here" playlist (13 tracks)
Bach Prelude in C BWV 846 (#5) · Invention No. 1 BWV 772 (#3) · Goldberg Aria (#17) · Scarlatti K. 141 (#24) · Handel "Harmonious Blacksmith" (#18) · Rameau Tambourin (#30) · C.P.E. Bach Solfeggietto (#34) · Mozart Rondo alla Turca K. 331/iii (#46) · Mozart K. 545/i (#49) · Beethoven Für Elise (#64) · Moonlight i (#58) · Pathétique ii (#56) · Hammerklavier i (#66).

### 4.2 Bach: the young virtuoso
| # | Work | Files / movements | Era | Default | Source · Licence | Pedal | Duration | Tier / note |
|---|---|---|---|---|---|---|---|---|
| 1 | J.S. Bach, *Capriccio sopra la lontananza del fratello dilettissimo* in B-flat, **BWV 992** (c. 1704) | inside `misc1.zip` (split into movements: UNVERIFIED) | baroque | H | Sankey | no | ~11 min | A. Bach's earliest programmatic keyboard work, which answers the brief's "early Bach". |
| 2 | Toccata in E minor, **BWV 914** (c. 1710) | inside `910-916.zip`, which holds all seven toccatas | baroque | H | Sankey | no | ~7 min | A. The other six toccatas are in the same zip and can go in an extended set. |

### 4.3 Bach: teaching the keyboard
| # | Work | Files | Era | Default | Source · Licence | Pedal | Duration | Tier / note |
|---|---|---|---|---|---|---|---|---|
| 3 | Fifteen Two-Part Inventions, **BWV 772–786** (1723) | 15 files in `772-786.zip` (32,246 B) | baroque | H | Sankey | no | ~23 min | A. Alternative piano versions of Nos. 6–15 by M. Bednarek on Commons (PD-self; method UNVERIFIED). |
| 4 | Fifteen Three-Part Sinfonias, **BWV 787–801** | 15 files in `787-801.zip` (32,102 B) | baroque | H | Sankey | no | ~28 min | A |

### 4.4 Bach: The Well-Tempered Clavier
| # | Work | Files | Era | Default | Source · Licence | Pedal | Duration | Tier / note |
|---|---|---|---|---|---|---|---|---|
| 5 | WTC I No. 1, Prelude and Fugue in C major, **BWV 846** | `bach_846.mid` (prelude and fugue in one file) | baroque | **G*** | Krueger · CC BY-SA 3.0 DE | yes | 3:46 | A. *A pianistic, pedalled reading, so it defaults to grand, against the house rule, to show the pedal. Sankey's harpsichord version is in #8. |
| 6 | WTC I No. 2 in C minor, **BWV 847** | `bach_847.mid` | baroque | G* | Krueger | yes | 3:09 | A |
| 7 | WTC I No. 5 in D major, **BWV 850** | `bach_850.mid` | baroque | G* | Krueger | yes | 2:53 | A |
| 8 | **WTC Book I complete**, BWV 846–869 (1722) | 24 preludes and fugues in `846-869.zip` (145,153 B) | baroque | H | Sankey | no | ~1 h 50 | A |
| 9 | **WTC Book II Nos. 1–12**, BWV 870–881 (1742) | `870-881.zip` (75,637 B) | baroque | H | Sankey | no | ~60 min | A. Sankey stopped recording after No. 12. |

### 4.5 Bach: suites, partitas, concertos, variations
| # | Work | Files | Era | Default | Source · Licence | Pedal | Duration | Tier / note |
|---|---|---|---|---|---|---|---|---|
| 10 | French Suite No. 5 in G, **BWV 816** | from `812-817.zip` (59,590 B, all six suites) | baroque | H | Sankey | no | ~16 min | A |
| 11 | English Suite No. 2 in A minor, **BWV 807** | from `806-811.zip` (110,534 B, all six) | baroque | H | Sankey | no | ~20 min | A |
| 12 | Partita No. 1 in B-flat, **BWV 825** | from `825-830.zip` (110,497 B, all six) | baroque | H | Sankey | no | ~18 min | A |
| 13 | Partita No. 2 in C minor, **BWV 826** | same zip | baroque | H | Sankey | no | ~20 min | A |
| 14 | Italian Concerto in F, **BWV 971** (1735) | `bwv971.mid` (54,302 B) | baroque | H | Sankey | no | ~13 min | A. Sankey plays it on one manual with cross-hand technique. |
| 15 | Concerto in D minor after A. Marcello, **BWV 974** | from `972-987.zip` (151,298 B, all 16 concerto transcriptions) | baroque | H | Sankey | no | ~10 min | A. The famous Adagio. |
| 16 | Chromatic Fantasia and Fugue in D minor, **BWV 903** | from `misc3.zip` (45,305 B; also holds BWV 894, 895, 899, 900, 901) | baroque | H | Sankey | no | ~12 min | A. Alternative: Mutopia PD engraving (`bwv903fan.mid` 15,358 B and `bwv903fug.mid` 21,526 B, tier C). |
| 17 | **Goldberg Variations, BWV 988** (1741): Aria, 30 variations, Aria da capo | `bwv988.zip` (58,273 B, "Complete") | baroque | H | Sankey | no | ~45–75 min (repeats UNVERIFIED) | A. Sankey recorded the two-manual variations on a single manual. |

### 4.6 Handel
| # | Work | Files | Era | Default | Source · Licence | Pedal | Duration | Tier / note |
|---|---|---|---|---|---|---|---|---|
| 18 | Suite No. 5 in E, **HWV 430**: Air and variations, "The Harmonious Blacksmith" (1720) | IMSLP #365752, "For harpsichord (Gouin)", 0.02 MB | baroque | H | Pierre Gouin, Éditions Outremontaises 2015 · **CC BY-SA 4.0** | no | 4:06 | B. The 4:06 length suggests the Air and variations only (UNVERIFIED). |

### 4.7 Domenico Scarlatti (all from Sankey's `data/scarlatti.zip`, 1,434,446 B, all 555 sonatas, Kirkpatrick numbering)
Inside the zip the files are probably named `K001.mid`… by analogy with his `K001.mp3` naming (UNVERIFIED). All are baroque, default H, no pedal, tier A.

| # | Sonata | Key | ~Duration |
|---|---|---|---|
| 19 | **K. 1** | D minor | ~2.5 min |
| 20 | **K. 9** | D minor | ~3.5 min |
| 21 | **K. 27** | B minor | ~3 min |
| 22 | **K. 87** | B minor | ~5 min |
| 23 | **K. 96** | D major | ~5 min |
| 24 | **K. 141** | D minor | ~4 min |
| 25 | **K. 159** | C major | ~2.5 min |
| 26 | **K. 208** | A major | ~4 min |
| 27 | **K. 380** | E major | ~5 min |
| 28 | **K. 466** | F minor | ~6 min |

The other 545 sonatas can be offered as a single "complete Scarlatti" extended set, since they add only about 1.4 MB.

### 4.8 The French clavecinists
| # | Work | Files | Era | Default | Source · Licence | Pedal | Duration | Tier / note |
|---|---|---|---|---|---|---|---|---|
| 29 | F. Couperin, *Les Barricades mystérieuses* (Second livre, 6e ordre, 1717) | `couperin.mid` (5,803 B) | baroque | H | D. Madore · **no explicit licence** | no | 2:29 | B. **CONDITIONAL**: ask Madore for CC0, or drop the piece. |
| 30 | Rameau, *Tambourin* (Pièces de clavessin, 1724) | Commons `RAMEAU_Tambourin.mid` (5,524 B) | baroque | H | R. A. Frantz after Mutopia, with ornaments added · PD | no | ~1.5 min | B. The plain Mutopia version is also available (`tambourin.mid`, 5,124 B). |
| 31 | Rameau, *Les Tendres Plaintes* (1724) | Mutopia `plaintes.mid` (6,061 B) | baroque | H | Mutopia #414 · PD | no | ~3 min | C |
| 32 | Rameau, *Les Sauvages* (Nouvelles suites, c. 1728) | Mutopia `sauvages.mid` (7,171 B) | baroque | H | Mutopia #407 · PD | no | ~2.5 min | C. Alternative: Gouin, IMSLP #340107 (CC BY-SA 4.0, 2:18, tier B). |
| 33 | Rameau, *La Poule* (Nouvelles suites) | IMSLP #340106 (0.02 MB) | baroque | H | Gouin 2014 · CC BY-SA 4.0 | no | 4:33 | B. From the same set: *L'Enharmonique* (#340108) and *L'Égyptienne* (#340109), if wanted. |

### 4.9 Galant and Empfindsamkeit
| # | Work | Files | Era | Default | Source · Licence | Pedal | Duration | Tier / note |
|---|---|---|---|---|---|---|---|---|
| 34 | C.P.E. Bach, *Solfeggietto* in C minor, **H. 220** (Wq 117/2) | Commons `C_P_E_Bach_Solfeggio.mid` (7,605 B) | galant | H | Shane Nieb, sequenced in FL Studio · **CC BY-SA 3.0 / GFDL** | no | ~1.7 min | B |
| 35 | C.P.E. Bach, Rondo in E-flat, **H. 288** | Mutopia `cpe-bach-rondo.mid` (15,220 B) | galant | G | Mutopia #177 · PD | UNVERIFIED | ~5 min | C |
| 36 | Haydn, Sonata in C, **Hob. XVI:7** (1766) | `haydn_7_1..3` | galant | H | Krueger | yes | 3:37 | A. The site lists all three movements as "Allegro moderato 1:03", which is a site typo. |
| 37 | Haydn, Sonata in G, **Hob. XVI:8** | `haydn_8_1..4` | galant | H | Krueger | yes | 5:06 | A |
| 38 | Haydn, Sonata in F, **Hob. XVI:9** | `haydn_9_1..3` | galant | H | Krueger | yes | 6:05 | A |

On the harpsichord, the engine should turn CC64 into held notes (§6) for #36–38.

### 4.10 Haydn (mature)
| # | Work | Files | Default | Source | Pedal | Duration |
|---|---|---|---|---|---|---|
| 39 | Sonata in D, **Hob. XVI:33** (1778) | `haydn_33_1..3` | G | Krueger | yes | 14:54 |
| 40 | Sonata in C, **Hob. XVI:35** (1780) | `haydn_35_1..3` | G | Krueger | yes | 14:36 |
| 41 | Sonata in G, **Hob. XVI:40** (1784) | `hay_40_1..2` | G | Krueger | yes | 9:33 |
| 42 | Sonata in A-flat, **Hob. XVI:43** (1783) | `haydn_43_1..3` | G | Krueger | yes | 13:54 |

All are classical, tier A. The two greatest late sonatas, XVI:50 and XVI:52, are **gaps** (§5).

### 4.11 Mozart
| # | Work | Files | Default | Source · Licence | Pedal | Duration | Tier / note |
|---|---|---|---|---|---|---|---|
| 43 | Sonata in B-flat, **K. 281** (1774) | Commons `W._A._Mozart,_Piano_Sonata_No._3,_K_281.mid` (112,952 B) | G | M. Bednarek, "MIDI capture from piano performance" · **CC0** | likely (UNVERIFIED) | 11:13 | A. Human performance. |
| 44 | Sonata in D, **K. 311** | `mz_311_1..3` | G | Krueger | yes | 15:03 | A |
| 45 | Sonata in C, **K. 330** | `mz_330_1..3` | G | Krueger | yes | 23:37 | A |
| 46 | Sonata in A, **K. 331**, with the *Rondo alla Turca* | `mz_331_1..3` | G | Krueger | yes | 23:07 | A |
| 47 | Sonata in F, **K. 332** | `mz_332_1..3` | G | Krueger | yes | 23:45 | A |
| 48 | Sonata in B-flat, **K. 333** | `mz_333_1..3` | G | Krueger | yes | 25:01 | A |
| 49 | Sonata in C, **K. 545** "facile" | `mz_545_1..3` | G (U alt.) | Krueger | yes | 11:49 | A |
| 50 | Sonata in B-flat, **K. 570** | `mz_570_1..3` | G | Krueger | yes | 18:51 | A |
| 51 | Fantasia in D minor, **K. 397** | Mutopia `KV397/Fantasia/Fantasia.mid` (13,485 B) | G | Mutopia · PD | UNVERIFIED | ~6 min | C |
| 52 | Sonata in C minor, **K. 457** | Mutopia `KV457/sonata1..3.mid` (20,899 / 17,611 / 21,088 B) | G | Mutopia · PD | UNVERIFIED | ~19 min | C |
| 53 | 12 Variations on "Ah vous dirai-je, Maman", **K. 265** | Commons `K265_(Ah_vous_dirai-je,_Maman).mid` (44,090 B) | G | M. Bednarek · PD-self | UNVERIFIED | 11:51 | A or B (method not stated) |

All are classical.

### 4.12 Clementi
| # | Work | Files | Default | Source | Pedal | Duration |
|---|---|---|---|---|---|---|
| 54 | Six Sonatinas, **op. 36** Nos. 1–6 (1797) | 17 files `clementi_opus36_N_M` | G (U alt.) | Krueger | yes | 41:21 on the site. That includes a probable typo: 36/5 ii is listed at 4:55, the same as i, but the file is only 8 KB. |

Classical, tier A.

### 4.13 Beethoven
| # | Work | Files | Default | Source · Licence | Pedal | Duration | Tier / note |
|---|---|---|---|---|---|---|---|
| 55 | Sonata No. 5 in C minor, **op. 10/1** | `beethoven_opus10_1..3` | G | Krueger | yes | 16:40 | A |
| 56 | Sonata No. 8 in C minor, **op. 13 "Pathétique"** | `pathetique_1..3` | G | Krueger | yes | 18:53 | A. A PD alternative by Bednarek is on Commons. |
| 57 | Sonata No. 11 in B-flat, **op. 22** | `beethoven_opus22_1..4` | G | Krueger | yes | 19:45 | A |
| 58 | Sonata No. 14 in C-sharp minor, **op. 27/2 "Moonlight"**, all three movements | `mond_1..3` | G | Krueger | yes | 14:56 | A |
| 59 | Sonata No. 17 in D minor, **op. 31/2 "Tempest"**, **movements I–II only** | Mutopia `LVB_Sonate_31no2_1.mid` (26,263 B), `_2.mid` (14,632 B) | G | Mutopia · PD | UNVERIFIED | ~16 min | C. **The third movement is a gap** (the Mutopia `_3` URL returns 404). |
| 60 | Sonata No. 20 in G, **op. 49/2** | Mutopia `LVB_Sonate_49no2_1/_2.mid` (14,953 / 11,560 B) | G (U alt.) | Mutopia · PD | UNVERIFIED | ~8 min | C |
| 61 | Sonata No. 21 in C, **op. 53 "Waldstein"** | `waldstein_1..3` | G | Krueger | yes | 24:12 | A |
| 62 | Sonata No. 23 in F minor, **op. 57 "Appassionata"** | `appass_1..3` | G | Krueger | yes | 23:23 | A |
| 63 | Sonata No. 26 in E-flat, **op. 81a "Les Adieux"** | `beethoven_les_adieux_1..3` | G | Krueger | yes | 13:36 | A |
| 64 | *Für Elise*, **WoO 59** | `elise.mid` | G (U alt.) | Krueger | yes | 3:48 | A. A PD alternative by Bednarek is on Commons. |
| 65 | Sonata No. 27 in E minor, **op. 90** | `beethoven_opus90_1..2` | G | Krueger | yes | 13:07 | A |
| 66 | **Sonata No. 29 in B-flat, op. 106 "Hammerklavier"**, all four movements | `beethoven_hammerklavier_1..4` | G | Krueger | yes | 35:35 | A. **The title track.** |
| 67 | Sonata No. 32 in C minor, **op. 111**, **movement I only** | Mutopia `lvb_sonate_111_1.mid` (47,339 B) | G | Mutopia · PD | UNVERIFIED | ~9 min | C. **The Arietta is a gap.** |
| 68 | Bagatelles **op. 33** Nos. 1 (E-flat) and 4 (A) | Commons `Beethoven_Op._33_no._1.mid` (9,014 B), `…no._4.mid` (8,636 B) | G | M. Bednarek · PD-self | UNVERIFIED | 2:47 + 2:51 | A or B |
| 69 | **Six Bagatelles, op. 126** (1824) | Commons `Ludwig_van_Beethoven_-_Bagatelles_Op._126.mid` (185,829 B, all six in one file) | G | M. Bednarek, "MIDI capture from piano performance" · **CC0** | likely (UNVERIFIED) | ~18 min | A. Human performance of late Beethoven. |
| 70 | Rondo a capriccio in G, **op. 129** "Rage over a lost penny" | Mutopia `beethoven_rondo_op129.mid` (46,761 B) | G | Mutopia · PD | UNVERIFIED | ~6 min | C |

All are classical. Totals: the Krueger part is 7 h 24 min. The Sankey part is roughly 6 h 30 min to 7 h in the core, or about 30 h with the complete Scarlatti. Everything else adds about 2 h.

**To trim to about 60 works**, drop #4, #9, #25, #35, #37, #38, #57, #60, #68 and #29. These are secondary works or have weaker sources. They can stay available as extended sets.

---

## 5. Gaps: great works with no clean, bundleable MIDI found

| Work | What exists | Route if we want it |
|---|---|---|
| Beethoven **op. 109** and **op. 110** (complete), **op. 111/ii** Arietta, **op. 31/2/iii** | IMSLP: no MIDI for 109, 110 or 111 (VERIFIED). Commons: short excerpts only. Mutopia: 111/i and 31/2 i–ii only. Krueger: none. SMD has op. 31/2 as a human Disklavier performance (NC; movements UNVERIFIED). MAESTRO has all of these (NC). | (a) An optional NC pack from MAESTRO or SMD if the user confirms the app stays non-commercial. (b) Ask Craig Sapp for permission to convert his Humdrum Beethoven encodings. (c) Search PDMX (a 2.1 GB download) for a CC0 MuseScore engraving. (d) Transcribe a public-domain recording ourselves with a pedal-aware transcription model. Musopen lists Paul Pitman's op. 110, but its licence is UNVERIFIED because Musopen returned 403. |
| Beethoven **32 Variations WoO 80**, **Bagatelles op. 119** | SMD has WoO 80 (NC). | As above. |
| Mozart **K. 310, K. 475, K. 485, K. 511, K. 576** | IMSLP K. 310 page: no MIDI entries found. Sapp Mozart is NC. | MAESTRO (NC) or PDMX. |
| Haydn **Hob. XVI:50, XVI:52** | SMD has XVI:52 (NC). Sapp Haydn is NC. | NC pack or PDMX. |
| Couperin, *Le Tic-Toc-Choc*, a clean-licence *Barricades* | IMSLP *Barricades*: a CC BY-NC-SA MIDI (#371012), and CC BY 3.0 items from Jacobi that look like 2 MB audio renders, not MIDI (UNVERIFIED). Commons *Le Réveille-Matin*: vibraphone patch, no ornaments, rejected. | Ask Madore, or encode it ourselves in LilyPond. The piece is short (about 2.5 min) and public domain. |
| Rameau *Le Rappel des oiseaux*, *Gavotte et six doubles* | Not found on IMSLP, Mutopia or Commons. | Encode it ourselves, or use PDMX. |
| Handel Passacaille HWV 432, Sarabande HWV 437, Chaconne HWV 435 | Mutopia has only the minor Aylesford Pieces (PD). | Encode it ourselves, or use PDMX. |
| Bach **Toccata and Fugue BWV 565** | Mutopia has an organ engraving with a pedal-board part (`BachJS/BWV565/ToccataFugue/ToccataFugue.mid`, PD). No keyboard MIDI exists. | **Omit**, per the brief. The Busoni transcription is not available as MIDI. |
| **Jesu, Joy of Man's Desiring** | Myra Hess's piano arrangement is in copyright in the EU until the end of 2035 (she died in 1965). IMSLP has Pierre Gouin's **organ** arrangement, #431136, CC BY-SA 4.0, 2:35, with the chorale in the pedal. | Optional "arrangements" shelf, if the user accepts an organ texture played on the piano. It is not in the core. |
| **Air on the G string** (BWV 1068) | No keyboard MIDI (IMSLP #208793 and #281832 are orchestral or ensemble). | Omit. |

---

## 6. Integration notes for the engine (from the repertoire side)

- **Pedal on instruments without dampers.** Krueger's Bach and early Haydn files use CC64, and a harpsichord has no damper pedal. When the instrument is the harpsichord, convert CC64 into delayed note-offs (the "finger pedalling" a harpsichordist would use) and hide the pedal animation. On grand and upright, show the pedal moving.
- **Range.** The CC0 harpsichord samples cover MIDI 29–84 (FF–c‴). Scarlatti and some Krueger files may go higher, and Krueger's piano files certainly exceed that range. After download, compute each file's lowest and highest note. If a harpsichord-default file goes past 84, extend the top sample by pitch-shifting (up to +5 semitones) or fall back to grand, and log it.
- **Auto-pedal (optional)** for tier-C engravings on piano, generated at runtime from harmonic changes. Show "pedal: generated" in the UI. Doing this at runtime creates no adaptation that we redistribute.
- **Hands and tracks.** Krueger files are Format 1, so hand colouring can use their track split (right and left hand: UNVERIFIED). Sankey's two-manual pieces (Goldberg) put the manuals on separate channels, as Sankey explains on bach.html.
- **Catalogue JSON** fields: `id, composer, title, catalogue, movement, era, defaultInstrument, altInstruments, assetPath, source, sourceUrl, licence, licenceUrl, credit, performanceType (performed|step-sequenced|engraved), tier, hasSustain, hasSoft, noteRange, durationSec, exportAllowed`. Fill `hasSustain`, `noteRange` and `durationSec` by parsing the files at build time; do not trust this document for those.
- **Import.** Accept any .mid through the companion web server (NanoHTTPD, reusing WanderQuest's `CompanionServer.kt`) and through `adb push` to the app's external files directory. Mark imported files `licence=user`.

---

## 7. Attribution text for the Credits screen and `assets/licenses/`

Short credit on the now-playing card, by source:
- `Performance: Bernd Krueger · piano-midi.de · CC BY-SA 3.0 DE`
- `Harpsichord: John Sankey · johnsankey.ca/harpsichord.html · free-copy notice`
- `MIDI: Pierre Gouin (Éditions Outremontaises) · IMSLP · CC BY-SA 4.0`
- `Sequenced by Shane Nieb · Wikimedia Commons · CC BY-SA 3.0`
- `Performance: Michael Bednarek · Wikimedia Commons · CC0 / public domain`
- `Engraving: Mutopia Project · public domain`

Full Credits screen (draft):

> **Music**
> Piano performances of Bach, Haydn, Mozart, Clementi and Beethoven by **Bernd Krueger**, source http://www.piano-midi.de, licensed under Creative Commons Attribution-ShareAlike 3.0 Germany (https://creativecommons.org/licenses/by-sa/3.0/de/deed.en). The files are included unmodified. Recordings or videos made from them are adaptations and must be shared under the same licence with this credit.
> Harpsichord performances of J.S. Bach and Domenico Scarlatti by **John Sankey**. They are © John Sankey and released for anyone to copy and play freely under his notice at https://www.johnsankey.ca/harpsichord.html (full text in LICENSES/SANKEY.txt). The files are included unmodified.
> Handel, *Suite in E major HWV 430*, and Rameau, *La Poule*: MIDI by **Pierre Gouin**, Les Éditions Outremontaises, Montréal, via IMSLP, CC BY-SA 4.0 (https://creativecommons.org/licenses/by-sa/4.0/).
> C.P.E. Bach, *Solfeggietto H. 220*: sequenced by **Shane Nieb**, via Wikimedia Commons, CC BY-SA 3.0 (https://creativecommons.org/licenses/by-sa/3.0/).
> Beethoven, *Bagatelles op. 126 and op. 33*; Mozart, *Sonata K. 281* and *Variations K. 265*: by **Michael Bednarek**, via Wikimedia Commons (CC0 / public domain).
> Rameau, *Tambourin*: **Ricardo André Frantz**, after the Mutopia Project, via Wikimedia Commons (public domain).
> Further pieces are engraved by volunteers of **The Mutopia Project** (https://www.mutopiaproject.org) and are in the public domain.
> [conditional] Couperin, *Les Barricades mystérieuses*: sequenced by **David Madore**.

`assets/licenses/` should contain:
- `CC-BY-SA-3.0-DE.txt`, taken from the legalcode URL
- `CC-BY-SA-4.0.txt`
- `CC-BY-SA-3.0.txt`
- `SANKEY.txt`, verbatim from johnsankey.ca/copyright.html
- `SOURCES.csv`, the manifest below, with each file's SHA-1

---

## 8. Download manifest (for the approval request)

Everything below is **HEAD VERIFIED 200** unless marked otherwise. Sizes are in bytes where HEAD returned them, and taken from the source page otherwise. The core total is about **4.8 MB**: about 2.3 MB Krueger, about 1.0 MB Sankey Bach zips, 1.43 MB Sankey Scarlatti zip, and about 0.6 MB for the rest.

### 8.1 Sankey (Bach from jsbach.net; Scarlatti from johnsankey.ca). Licence: Sankey free-copy notice
| Local path | URL | Bytes | Last-Modified |
|---|---|---|---|
| `downloads/sankey/772-786.zip` (Inventions) | http://www.jsbach.net/midi/sankey/772-786.zip | 32,246 | 1998-01-02 |
| `downloads/sankey/787-801.zip` (Sinfonias) | http://www.jsbach.net/midi/sankey/787-801.zip | 32,102 | 1998-01-04 |
| `downloads/sankey/846-869.zip` (WTC I) | http://www.jsbach.net/midi/sankey/846-869.zip | 145,153 | 1997-10-25 |
| `downloads/sankey/870-881.zip` (WTC II 1–12) | http://www.jsbach.net/midi/sankey/870-881.zip | 75,637 | 1997-10-25 |
| `downloads/sankey/806-811.zip` (English Suites) | http://www.jsbach.net/midi/sankey/806-811.zip | 110,534 | 1997-10-25 |
| `downloads/sankey/812-817.zip` (French Suites) | http://www.jsbach.net/midi/sankey/812-817.zip | 59,590 | 1997-10-25 |
| `downloads/sankey/825-830.zip` (Partitas) | http://www.jsbach.net/midi/sankey/825-830.zip | 110,497 | 2005-09-14 |
| `downloads/sankey/910-916.zip` (Toccatas) | http://www.jsbach.net/midi/sankey/910-916.zip | 86,068 | 2005-09-14 |
| `downloads/sankey/972-987.zip` (Concerto transcriptions) | http://www.jsbach.net/midi/sankey/972-987.zip | 151,298 | 1998-02-03 |
| `downloads/sankey/bwv988.zip` (Goldberg) | http://www.jsbach.net/midi/sankey/bwv988.zip | 58,273 | 2005-09-14 |
| `downloads/sankey/misc1.zip` (BWV 963, 965, 966, 967, 989, **992**, 993) | http://www.jsbach.net/midi/sankey/misc1.zip | 68,757 | 1998-01-02 |
| `downloads/sankey/misc3.zip` (BWV 894, 895, 899, 900, 901, **903**) | http://www.jsbach.net/midi/sankey/misc3.zip | 45,305 | 1998-02-23 |
| `assets/midi/sankey/bach/bwv971.mid` (Italian Concerto) | http://www.jsbach.net/midi/sankey/bwv971.mid | 54,302 | 1998-02-07 |
| `downloads/sankey/scarlatti.zip` (K. 1–555) | https://www.johnsankey.ca/data/scarlatti.zip | 1,434,446 | 2022-08-24 (same size as the 2005 sankey.ws copy) |
| `assets/licenses/SANKEY.txt` | text copied from https://www.johnsankey.ca/copyright.html | – | – |
| optional, not in core: `802-805.zip` (Duets, 13,220 B), `misc2.zip` (48,677 B) | http://www.jsbach.net/midi/sankey/… | | |

Extract only the chosen entries into `assets/midi/sankey/…` without re-encoding them. Entry names inside the zips are UNVERIFIED.

### 8.2 Wikimedia Commons (licence per row)
| Local path | URL | Bytes | Licence |
|---|---|---|---|
| `assets/midi/commons/beethoven_op126_bednarek.mid` | https://upload.wikimedia.org/wikipedia/commons/7/7a/Ludwig_van_Beethoven_-_Bagatelles_Op._126.mid | 185,829 | CC0 |
| `assets/midi/commons/mozart_k281_bednarek.mid` | https://upload.wikimedia.org/wikipedia/commons/5/5b/W._A._Mozart%2C_Piano_Sonata_No._3%2C_K_281.mid | 112,952 | CC0 |
| `assets/midi/commons/mozart_k265_bednarek.mid` | https://upload.wikimedia.org/wikipedia/commons/4/47/K265_%28Ah_vous_dirai-je%2C_Maman%29.mid | 44,090 | PD-self |
| `assets/midi/commons/beethoven_op33_1_bednarek.mid` | https://upload.wikimedia.org/wikipedia/commons/a/a9/Beethoven_Op._33_no._1.mid | 9,014 | PD-self |
| `assets/midi/commons/beethoven_op33_4_bednarek.mid` | https://upload.wikimedia.org/wikipedia/commons/9/9d/Beethoven_Op._33_no._4.mid | 8,636 | PD-self |
| `assets/midi/commons/cpe_bach_solfeggietto_nieb.mid` | https://upload.wikimedia.org/wikipedia/commons/0/00/C_P_E_Bach_Solfeggio.mid | 7,605 | CC BY-SA 3.0 / GFDL |
| `assets/midi/commons/rameau_tambourin_frantz.mid` | https://upload.wikimedia.org/wikipedia/commons/e/e0/RAMEAU_Tambourin.mid | 5,524 | PD |

(HEAD requests were sent for op. 126 and op. 33. The other sizes come from the Commons API `imageinfo`.)

### 8.3 Mutopia (all Public Domain)
| Local path | URL | Bytes |
|---|---|---|
| `assets/midi/mutopia/rameau_tendres_plaintes.mid` | https://www.mutopiaproject.org/ftp/RameauJP/plaintes/plaintes.mid | 6,061 |
| `assets/midi/mutopia/rameau_sauvages.mid` | https://www.mutopiaproject.org/ftp/RameauJP/sauvages/sauvages.mid | 7,171 |
| `assets/midi/mutopia/cpe_bach_rondo_h288.mid` | https://www.mutopiaproject.org/ftp/BachCPE/cpe-bach-rondo/cpe-bach-rondo.mid | 15,220 |
| `assets/midi/mutopia/mozart_k397.mid` | https://www.mutopiaproject.org/ftp/MozartWA/KV397/Fantasia/Fantasia.mid | 13,485 |
| `assets/midi/mutopia/mozart_k457_1.mid` | https://www.mutopiaproject.org/ftp/MozartWA/KV457/sonata1/sonata1.mid | 20,899 |
| `assets/midi/mutopia/mozart_k457_2.mid` | https://www.mutopiaproject.org/ftp/MozartWA/KV457/sonata2/sonata2.mid | 17,611 |
| `assets/midi/mutopia/mozart_k457_3.mid` | https://www.mutopiaproject.org/ftp/MozartWA/KV457/sonata3/sonata3.mid | 21,088 |
| `assets/midi/mutopia/beethoven_op31_2_1.mid` | https://www.mutopiaproject.org/ftp/BeethovenLv/O31/LVB_Sonate_31no2_1/LVB_Sonate_31no2_1.mid | 26,263 |
| `assets/midi/mutopia/beethoven_op31_2_2.mid` | https://www.mutopiaproject.org/ftp/BeethovenLv/O31/LVB_Sonate_31no2_2/LVB_Sonate_31no2_2.mid | 14,632 |
| `assets/midi/mutopia/beethoven_op49_2_1.mid` | https://www.mutopiaproject.org/ftp/BeethovenLv/O49/LVB_Sonate_49no2_1/LVB_Sonate_49no2_1.mid | 14,953 |
| `assets/midi/mutopia/beethoven_op49_2_2.mid` | https://www.mutopiaproject.org/ftp/BeethovenLv/O49/LVB_Sonate_49no2_2/LVB_Sonate_49no2_2.mid | 11,560 |
| `assets/midi/mutopia/beethoven_op111_1.mid` | https://www.mutopiaproject.org/ftp/BeethovenLv/O111/lvb_sonate_111_1/lvb_sonate_111_1.mid | 47,339 |
| `assets/midi/mutopia/beethoven_op129.mid` | https://www.mutopiaproject.org/ftp/BeethovenLv/O129/beethoven_rondo_op129/beethoven_rondo_op129.mid | 46,761 |
| optional alternatives: `bwv903fan.mid` (15,358), `bwv903fug.mid` (21,526), `RameauJP/tambourin/tambourin.mid` (5,124) | https://www.mutopiaproject.org/ftp/BachJS/BWV903/… | |

### 8.4 IMSLP (CC BY-SA 4.0; the user must click the disclaimer)
| Local path | IMSLP file | Page size / duration | Work page |
|---|---|---|---|
| `assets/midi/imslp/handel_hwv430_gouin.mid` | https://imslp.org/wiki/Special:ImagefromIndex/365752 | 0.02 MB / 4:06 | https://imslp.org/wiki/Suite_in_E_major,_HWV_430_(Handel,_George_Frideric) |
| `assets/midi/imslp/rameau_la_poule_gouin.mid` | https://imslp.org/wiki/Special:ImagefromIndex/340106 | 0.02 MB / 4:33 | https://imslp.org/wiki/Nouvelles_suites_de_pi%C3%A8ces_de_clavecin_(Rameau,_Jean-Philippe) |
| optional: *Les Sauvages* (Gouin) | …/ImagefromIndex/340107 | 0.01 MB / 2:18 | same page |

### 8.5 Conditional
| Local path | URL | Bytes | Licence |
|---|---|---|---|
| `assets/midi/madore/couperin_barricades.mid` | http://www.madore.org/~david/music/midi/couperin.mid | 5,803 | none stated (ask the author) |

### 8.6 piano-midi.de (Bernd Krueger): 91 files, CC BY-SA 3.0 DE
Try the primary URL first. If it returns 418, use the Wayback raw URL and check the SHA-1 (base32).

| Local path | Primary URL (piano-midi.de) | Fallback URL (Wayback raw, byte-identical) | Wayback SHA-1 (base32) | Size (site) | Duration (site) | Licence |
|---|---|---|---|---|---|---|
| `assets/midi/krueger/bach/bach_846.mid` | http://www.piano-midi.de/midis/bach/bach_846.mid | https://web.archive.org/web/20051106043322id_/http://www.piano-midi.de/midis/bach/bach_846.mid | `O5M3TGSHC4K3UAGV22HXFR5YHVD2EXJJ` | 11 KB | 3:46 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/bach/bach_847.mid` | http://www.piano-midi.de/midis/bach/bach_847.mid | https://web.archive.org/web/20051106042842id_/http://www.piano-midi.de/midis/bach/bach_847.mid | `UHKM4GXAFTRLVO4REVDVPNRH4VKQF3VG` | 15 KB | 3:09 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/bach/bach_850.mid` | http://www.piano-midi.de/midis/bach/bach_850.mid | https://web.archive.org/web/20051106042906id_/http://www.piano-midi.de/midis/bach/bach_850.mid | `ALJTLIPWUSBPP5MSUSSQITNYVZKBLXPR` | 12 KB | 2:53 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/beethoven/beethoven_opus10_1.mid` | http://www.piano-midi.de/midis/beethoven/beethoven_opus10_1.mid | https://web.archive.org/web/20130508074432id_/http://piano-midi.de/midis/beethoven/beethoven_opus10_1.mid | `7LEVCB3IYJSCGQG7DDJJZ2S6Z2N4BLOQ` | 35 KB | 6:31 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/beethoven/beethoven_opus10_2.mid` | http://www.piano-midi.de/midis/beethoven/beethoven_opus10_2.mid | https://web.archive.org/web/20130508065628id_/http://piano-midi.de/midis/beethoven/beethoven_opus10_2.mid | `R6QCZAICW7LN4STESHUUYO7FYBIIB4Y5` | 17 KB | 6:32 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/beethoven/beethoven_opus10_3.mid` | http://www.piano-midi.de/midis/beethoven/beethoven_opus10_3.mid | https://web.archive.org/web/20130508043202id_/http://piano-midi.de/midis/beethoven/beethoven_opus10_3.mid | `B72WJX6SUYKEYB456Y3RGFNNSXE5IJZS` | 28 KB | 3:37 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/beethoven/pathetique_1.mid` | http://www.piano-midi.de/midis/beethoven/pathetique_1.mid | https://web.archive.org/web/20130508073649id_/http://piano-midi.de/midis/beethoven/pathetique_1.mid | `WZC4E4PKNNKDCRRX2X3DYE7A4DJLXX6Y` | 53 KB | 9:22 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/beethoven/pathetique_2.mid` | http://www.piano-midi.de/midis/beethoven/pathetique_2.mid | https://web.archive.org/web/20130508071854id_/http://piano-midi.de/midis/beethoven/pathetique_2.mid | `MDLMZFGNP3ALDCC4ZPPIUKPG3FDCUZAD` | 16 KB | 5:12 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/beethoven/pathetique_3.mid` | http://www.piano-midi.de/midis/beethoven/pathetique_3.mid | https://web.archive.org/web/20130508044358id_/http://piano-midi.de/midis/beethoven/pathetique_3.mid | `X2RFPSNNA5XST6URUOYKL4XCQRMUTHZV` | 28 KB | 4:19 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/beethoven/beethoven_opus22_1.mid` | http://www.piano-midi.de/midis/beethoven/beethoven_opus22_1.mid | https://web.archive.org/web/20130508105431id_/http://piano-midi.de/midis/beethoven/beethoven_opus22_1.mid | `ZXRV3PZOHKSAPTCXMUBHGGNN3EBV3KWD` | 51 KB | 6:39 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/beethoven/beethoven_opus22_2.mid` | http://www.piano-midi.de/midis/beethoven/beethoven_opus22_2.mid | https://web.archive.org/web/20130508030027id_/http://piano-midi.de/midis/beethoven/beethoven_opus22_2.mid | `4LSGLBNO3N572Q5VMY7R6443ZY2GZJYZ` | 21 KB | 5:21 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/beethoven/beethoven_opus22_3.mid` | http://www.piano-midi.de/midis/beethoven/beethoven_opus22_3.mid | https://web.archive.org/web/20130508053758id_/http://piano-midi.de/midis/beethoven/beethoven_opus22_3.mid | `2Y6JTWNL7HF4XJFK3O3VUD76RMYJRDPR` | 19 KB | 3:03 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/beethoven/beethoven_opus22_4.mid` | http://www.piano-midi.de/midis/beethoven/beethoven_opus22_4.mid | https://web.archive.org/web/20130508052831id_/http://piano-midi.de/midis/beethoven/beethoven_opus22_4.mid | `ESY3QSLXATRCJ6KJ6DKTFKOASGWECTJP` | 30 KB | 4:42 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/beethoven/mond_1.mid` | http://www.piano-midi.de/midis/beethoven/mond_1.mid | https://web.archive.org/web/20130508075435id_/http://piano-midi.de/midis/beethoven/mond_1.mid | `Y25RL32DWPPMF4GQMCP7DNZNZVSLQ3GT` | 16 KB | 6:02 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/beethoven/mond_2.mid` | http://www.piano-midi.de/midis/beethoven/mond_2.mid | https://web.archive.org/web/20130508070725id_/http://piano-midi.de/midis/beethoven/mond_2.mid | `N5Z7FM4DP3ORT4UXOGQQMOPLIKK47YRE` | 10 KB | 2:04 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/beethoven/mond_3.mid` | http://www.piano-midi.de/midis/beethoven/mond_3.mid | https://web.archive.org/web/20130508031644id_/http://piano-midi.de/midis/beethoven/mond_3.mid | `DMXTWZCB5E3HEOQBAOHUCA2GHFMUTWVK` | 52 KB | 6:50 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/beethoven/waldstein_1.mid` | http://www.piano-midi.de/midis/beethoven/waldstein_1.mid | https://web.archive.org/web/20130508110718id_/http://piano-midi.de/midis/beethoven/waldstein_1.mid | `ATSPCVPY7O6IBFA52CKLZVSZOE4CQ7TB` | 80 KB | 10:21 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/beethoven/waldstein_2.mid` | http://www.piano-midi.de/midis/beethoven/waldstein_2.mid | https://web.archive.org/web/20130508022353id_/http://piano-midi.de/midis/beethoven/waldstein_2.mid | `V6NIPHYOZVZF7GP366KCFUGPBDUDP57U` | 6 KB | 4:09 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/beethoven/waldstein_3.mid` | http://www.piano-midi.de/midis/beethoven/waldstein_3.mid | https://web.archive.org/web/20130508052551id_/http://piano-midi.de/midis/beethoven/waldstein_3.mid | `EVLQLKB3YJRBMYE63ZBB73VGDRVMEGJG` | 75 KB | 9:42 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/beethoven/appass_1.mid` | http://www.piano-midi.de/midis/beethoven/appass_1.mid | https://web.archive.org/web/20130508061514id_/http://piano-midi.de/midis/beethoven/appass_1.mid | `3AM2U2MTQVDT6XU4VGB6TIVA5JPJ3QIJ` | 72 KB | 9:22 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/beethoven/appass_2.mid` | http://www.piano-midi.de/midis/beethoven/appass_2.mid | https://web.archive.org/web/20130508083008id_/http://piano-midi.de/midis/beethoven/appass_2.mid | `5CDCRDGTTM6HEMQIEGI7SCGP6ALBFENT` | 21 KB | 6:13 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/beethoven/appass_3.mid` | http://www.piano-midi.de/midis/beethoven/appass_3.mid | https://web.archive.org/web/20130508111722id_/http://piano-midi.de/midis/beethoven/appass_3.mid | `7ZN4JRHORRC4GABNKXBIH6JF4BTVBLFV` | 67 KB | 7:48 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/beethoven/beethoven_les_adieux_1.mid` | http://www.piano-midi.de/midis/beethoven/beethoven_les_adieux_1.mid | https://web.archive.org/web/20130508032400id_/http://piano-midi.de/midis/beethoven/beethoven_les_adieux_1.mid | `GZF66UOA6YVUHNROBLTPU4BHLPEKAES3` | 33 KB | 5:42 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/beethoven/beethoven_les_adieux_2.mid` | http://www.piano-midi.de/midis/beethoven/beethoven_les_adieux_2.mid | https://web.archive.org/web/20130508102458id_/http://piano-midi.de/midis/beethoven/beethoven_les_adieux_2.mid | `YE2OGBD7XYZTP776DL5EGHT6GDMDI5T2` | 10 KB | 2:54 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/beethoven/beethoven_les_adieux_3.mid` | http://www.piano-midi.de/midis/beethoven/beethoven_les_adieux_3.mid | https://web.archive.org/web/20130508075019id_/http://piano-midi.de/midis/beethoven/beethoven_les_adieux_3.mid | `V6MTLJEAVZUEV2MVCVQA6QJXFRD5TS4V` | 45 KB | 5:00 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/beethoven/elise.mid` | http://www.piano-midi.de/midis/beethoven/elise.mid | https://web.archive.org/web/20130508080625id_/http://piano-midi.de/midis/beethoven/elise.mid | `ZNLHPDGBI4CWLSLRDXR4CQ2BOYOXK4H7` | 14 KB | 3:48 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/beethoven/beethoven_opus90_1.mid` | http://www.piano-midi.de/midis/beethoven/beethoven_opus90_1.mid | https://web.archive.org/web/20150905201019id_/http://piano-midi.de/midis/beethoven/beethoven_opus90_1.mid | `YYACJ6XP7TJJRMMZ2MPXAUJQ544V2M5N` | 24 KB | 4:53 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/beethoven/beethoven_opus90_2.mid` | http://www.piano-midi.de/midis/beethoven/beethoven_opus90_2.mid | https://web.archive.org/web/20160207035109id_/http://piano-midi.de/midis/beethoven/beethoven_opus90_2.mid | `W4UQIASGTMHZPD34Y2AVZJYRACOFZFJI` | 24 KB | 8:14 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/beethoven/beethoven_hammerklavier_1.mid` | http://www.piano-midi.de/midis/beethoven/beethoven_hammerklavier_1.mid | https://web.archive.org/web/20130508100249id_/http://piano-midi.de/midis/beethoven/beethoven_hammerklavier_1.mid | `QK4QBBMFW4SWQETC4NJ5SG2ZBTZPBFXK` | 73 KB | 9:53 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/beethoven/beethoven_hammerklavier_2.mid` | http://www.piano-midi.de/midis/beethoven/beethoven_hammerklavier_2.mid | https://web.archive.org/web/20130508051020id_/http://piano-midi.de/midis/beethoven/beethoven_hammerklavier_2.mid | `BH2NGHY4MC7A4QSPM3GK4P5RYYZKJOSH` | 19 KB | 2:15 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/beethoven/beethoven_hammerklavier_3.mid` | http://www.piano-midi.de/midis/beethoven/beethoven_hammerklavier_3.mid | https://web.archive.org/web/20130508062502id_/http://piano-midi.de/midis/beethoven/beethoven_hammerklavier_3.mid | `T37V3HPQTHPUMEB6WX4TND2EY6GLDFPP` | 45 KB | 12:44 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/beethoven/beethoven_hammerklavier_4.mid` | http://www.piano-midi.de/midis/beethoven/beethoven_hammerklavier_4.mid | https://web.archive.org/web/20130508064355id_/http://piano-midi.de/midis/beethoven/beethoven_hammerklavier_4.mid | `V56TACQCV443NPQFEZHFO6ML7MRVN7NT` | 80 KB | 10:43 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/mozart/mz_311_1.mid` | http://www.piano-midi.de/midis/mozart/mz_311_1.mid | https://web.archive.org/web/20140518095320id_/http://www.piano-midi.de/midis/mozart/mz_311_1.mid | `F54K3B4ABUZY6KFAIYNLRF6GEPY6LCRM` | 29 KB | 4:19 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/mozart/mz_311_2.mid` | http://www.piano-midi.de/midis/mozart/mz_311_2.mid | https://web.archive.org/web/20140630135947id_/http://piano-midi.de/midis/mozart/mz_311_2.mid | `YR3T3SNEKHOVDI456WVEZNKQ4FRQNORH` | 16 KB | 4:39 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/mozart/mz_311_3.mid` | http://www.piano-midi.de/midis/mozart/mz_311_3.mid | https://web.archive.org/web/20140630084141id_/http://piano-midi.de/midis/mozart/mz_311_3.mid | `ALMKLHZQSSOGJVPTV5MHXHIAYA3DIGKZ` | 35 KB | 6:05 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/mozart/mz_330_1.mid` | http://www.piano-midi.de/midis/mozart/mz_330_1.mid | https://web.archive.org/web/20140630150708id_/http://piano-midi.de/midis/mozart/mz_330_1.mid | `QWKAEVNI6P2QQI6GZOSYHISX4GXFIAZM` | 41 KB | 8:58 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/mozart/mz_330_2.mid` | http://www.piano-midi.de/midis/mozart/mz_330_2.mid | https://web.archive.org/web/20140630182055id_/http://piano-midi.de/midis/mozart/mz_330_2.mid | `X723G2MDUT6655QIBRODQUFIJQKZNTVL` | 18 KB | 6:36 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/mozart/mz_330_3.mid` | http://www.piano-midi.de/midis/mozart/mz_330_3.mid | https://web.archive.org/web/20140630213903id_/http://piano-midi.de/midis/mozart/mz_330_3.mid | `35VS3S3SD2D55W7KULKK6VFQSCMPRTM6` | 39 KB | 8:03 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/mozart/mz_331_1.mid` | http://www.piano-midi.de/midis/mozart/mz_331_1.mid | https://web.archive.org/web/20140630180722id_/http://piano-midi.de/midis/mozart/mz_331_1.mid | `AVF25FF6V7KZX3KOS6LWUIK76OUNGPDR` | 60 KB | 13:52 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/mozart/mz_331_2.mid` | http://www.piano-midi.de/midis/mozart/mz_331_2.mid | https://web.archive.org/web/20140630151555id_/http://piano-midi.de/midis/mozart/mz_331_2.mid | `RIKXDQDW3U4ZIBNNYROP5B2RSQFHAAO3` | 31 KB | 6:05 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/mozart/mz_331_3.mid` | http://www.piano-midi.de/midis/mozart/mz_331_3.mid | https://web.archive.org/web/20130802001702id_/http://www.piano-midi.de/midis/mozart/mz_331_3.mid | `FYBRQFUZ5Q6JEHUUZN3CZZA73DTTBV4W` | 26 KB | 3:10 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/mozart/mz_332_1.mid` | http://www.piano-midi.de/midis/mozart/mz_332_1.mid | https://web.archive.org/web/20140630061445id_/http://piano-midi.de/midis/mozart/mz_332_1.mid | `XUU7GCQGO3IVGNKNRBYCUPHR5RYYJH6R` | 51 KB | 9:31 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/mozart/mz_332_2.mid` | http://www.piano-midi.de/midis/mozart/mz_332_2.mid | https://web.archive.org/web/20140630235839id_/http://piano-midi.de/midis/mozart/mz_332_2.mid | `V2UYWL3CIAUJPTQHR3L4LLPPEIPLHT47` | 15 KB | 4:24 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/mozart/mz_332_3.mid` | http://www.piano-midi.de/midis/mozart/mz_332_3.mid | https://web.archive.org/web/20140630195508id_/http://piano-midi.de/midis/mozart/mz_332_3.mid | `XRJZZ5NRLNTVFCWLDNIIU7BIFVNPXNGV` | 57 KB | 9:50 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/mozart/mz_333_1.mid` | http://www.piano-midi.de/midis/mozart/mz_333_1.mid | https://web.archive.org/web/20140701000254id_/http://piano-midi.de/midis/mozart/mz_333_1.mid | `KPMUXOUS6N6IQCDEJMC5NI44ACMMS7ZI` | 51 KB | 10:30 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/mozart/mz_333_2.mid` | http://www.piano-midi.de/midis/mozart/mz_333_2.mid | https://web.archive.org/web/20140630060056id_/http://piano-midi.de/midis/mozart/mz_333_2.mid | `FLK4LGLJR5EFR2TUXHZ4IQSETFDDWDN2` | 27 KB | 8:43 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/mozart/mz_333_3.mid` | http://www.piano-midi.de/midis/mozart/mz_333_3.mid | https://web.archive.org/web/20140630105727id_/http://piano-midi.de/midis/mozart/mz_333_3.mid | `DU35CL5STND3MKNJZH6OAK2SVXZG2YSS` | 34 KB | 5:48 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/mozart/mz_545_1.mid` | http://www.piano-midi.de/midis/mozart/mz_545_1.mid | https://web.archive.org/web/20131231042921id_/http://www.piano-midi.de/midis/mozart/mz_545_1.mid | `SFY2YZU5GGWE4SI5ZK5MFEHUJGKEHFOQ` | 31 KB | 4:21 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/mozart/mz_545_2.mid` | http://www.piano-midi.de/midis/mozart/mz_545_2.mid | https://web.archive.org/web/20140630203248id_/http://piano-midi.de/midis/mozart/mz_545_2.mid | `74JLSGEU5GI3WAQ3YRGMJWSVSJB2EKSM` | 18 KB | 5:44 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/mozart/mz_545_3.mid` | http://www.piano-midi.de/midis/mozart/mz_545_3.mid | https://web.archive.org/web/20140630233313id_/http://piano-midi.de/midis/mozart/mz_545_3.mid | `3T7GQRR3TAWPDR7UNOOUCIGK2PKPS4UW` | 10 KB | 1:44 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/mozart/mz_570_1.mid` | http://www.piano-midi.de/midis/mozart/mz_570_1.mid | https://web.archive.org/web/20140630144045id_/http://piano-midi.de/midis/mozart/mz_570_1.mid | `A74RW346RRDXSKNMSGTWFRCWPWWPE65H` | 44 KB | 7:56 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/mozart/mz_570_2.mid` | http://www.piano-midi.de/midis/mozart/mz_570_2.mid | https://web.archive.org/web/20140630173009id_/http://piano-midi.de/midis/mozart/mz_570_2.mid | `KJLF7GCHMPLLGHKSFHCSJZKOGN626Q5P` | 22 KB | 7:35 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/mozart/mz_570_3.mid` | http://www.piano-midi.de/midis/mozart/mz_570_3.mid | https://web.archive.org/web/20140630221010id_/http://piano-midi.de/midis/mozart/mz_570_3.mid | `WNVT433DHWMURD3TDY3NVLEDPUGU5N6W` | 21 KB | 3:20 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/haydn/haydn_7_1.mid` | http://www.piano-midi.de/midis/haydn/haydn_7_1.mid | https://web.archive.org/web/20130430164054id_/http://piano-midi.de/midis/haydn/haydn_7_1.mid | `CHDZ2REL5XREI4QIVLUGKEJINQNKUBDE` | 7 KB | 1:03 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/haydn/haydn_7_2.mid` | http://www.piano-midi.de/midis/haydn/haydn_7_2.mid | https://web.archive.org/web/20130430221114id_/http://piano-midi.de/midis/haydn/haydn_7_2.mid | `WMMDRVLUW7KQ6TN4KCQIYQDLKQLBBNZA` | 10 KB | 1:03 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/haydn/haydn_7_3.mid` | http://www.piano-midi.de/midis/haydn/haydn_7_3.mid | https://web.archive.org/web/20130430200435id_/http://piano-midi.de/midis/haydn/haydn_7_3.mid | `Q4SEPQKC5PON2IAD5HMSVSE7VDKMF6WO` | 9 KB | 1:31 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/haydn/haydn_8_1.mid` | http://www.piano-midi.de/midis/haydn/haydn_8_1.mid | https://web.archive.org/web/20130430171250id_/http://piano-midi.de/midis/haydn/haydn_8_1.mid | `Y2OW47P6S63LVDBD3Z6QCPCNX7HOVDEM` | 12 KB | 2:02 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/haydn/haydn_8_2.mid` | http://www.piano-midi.de/midis/haydn/haydn_8_2.mid | https://web.archive.org/web/20130430214450id_/http://piano-midi.de/midis/haydn/haydn_8_2.mid | `3VU7ZXFQHUPBPBNIDIJEYAOSY3QZTWNS` | 5 KB | 0:57 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/haydn/haydn_8_3.mid` | http://www.piano-midi.de/midis/haydn/haydn_8_3.mid | https://web.archive.org/web/20120509093917id_/http://piano-midi.de/midis/haydn/haydn_8_3.mid | `ARIN5NPL6CBRCYTIZFXPZXWWL56TJAH2` | 5 KB | 1:27 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/haydn/haydn_8_4.mid` | http://www.piano-midi.de/midis/haydn/haydn_8_4.mid | https://web.archive.org/web/20120509093718id_/http://piano-midi.de/midis/haydn/haydn_8_4.mid | `3H6OLBDVODAHDLJR6ZUGW2N2IEZZV7X4` | 5 KB | 0:40 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/haydn/haydn_9_1.mid` | http://www.piano-midi.de/midis/haydn/haydn_9_1.mid | https://web.archive.org/web/20130430194710id_/http://piano-midi.de/midis/haydn/haydn_9_1.mid | `3HMTT37E6SQLIS4Z46LEXVMES7FOH5EL` | 14 KB | 2:24 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/haydn/haydn_9_2.mid` | http://www.piano-midi.de/midis/haydn/haydn_9_2.mid | https://web.archive.org/web/20130430185934id_/http://piano-midi.de/midis/haydn/haydn_9_2.mid | `7AJXALQ3QBPPF45LJZGEAYPATN24QB4E` | 14 KB | 2:50 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/haydn/haydn_9_3.mid` | http://www.piano-midi.de/midis/haydn/haydn_9_3.mid | https://web.archive.org/web/20130430175352id_/http://piano-midi.de/midis/haydn/haydn_9_3.mid | `Z54AIIJELXLBWVC5MMF3WSJTVMFRTCKB` | 6 KB | 0:51 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/haydn/haydn_33_1.mid` | http://www.piano-midi.de/midis/haydn/haydn_33_1.mid | https://web.archive.org/web/20130430190359id_/http://piano-midi.de/midis/haydn/haydn_33_1.mid | `BBY2RL7GJPH55UVVSMPCOBQYYS47PAKH` | 31 KB | 5:39 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/haydn/haydn_33_2.mid` | http://www.piano-midi.de/midis/haydn/haydn_33_2.mid | https://web.archive.org/web/20130430193214id_/http://piano-midi.de/midis/haydn/haydn_33_2.mid | `YF3VN3EAKXFYO7AJ34TYLGI5Y3EFUKX3` | 16 KB | 5:18 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/haydn/haydn_33_3.mid` | http://www.piano-midi.de/midis/haydn/haydn_33_3.mid | https://web.archive.org/web/20130406002111id_/http://piano-midi.de/midis/haydn/haydn_33_3.mid | `FZECA2LMOAI62JRFTY7OZ6MBOA6EF2XN` | 19 KB | 3:57 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/haydn/haydn_35_1.mid` | http://www.piano-midi.de/midis/haydn/haydn_35_1.mid | https://web.archive.org/web/20130430193621id_/http://piano-midi.de/midis/haydn/haydn_35_1.mid | `QCGU6EXVO52BFMC6TS356546DKBOWGVS` | 37 KB | 5:37 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/haydn/haydn_35_2.mid` | http://www.piano-midi.de/midis/haydn/haydn_35_2.mid | https://web.archive.org/web/20130430190800id_/http://piano-midi.de/midis/haydn/haydn_35_2.mid | `SXJBR7SK75HPKKSESTTYH6ZY5OIX3JQO` | 20 KB | 6:35 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/haydn/haydn_35_3.mid` | http://www.piano-midi.de/midis/haydn/haydn_35_3.mid | https://web.archive.org/web/20130430170500id_/http://piano-midi.de/midis/haydn/haydn_35_3.mid | `TEIGERTZCWXY3JLOLLRD777V65S55GE7` | 16 KB | 2:24 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/haydn/hay_40_1.mid` | http://www.piano-midi.de/midis/haydn/hay_40_1.mid | https://web.archive.org/web/20130430182101id_/http://piano-midi.de/midis/haydn/hay_40_1.mid | `ODAJBSW74V4NQJGARR7PPNRNZS3OBSBP` | 31 KB | 6:34 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/haydn/hay_40_2.mid` | http://www.piano-midi.de/midis/haydn/hay_40_2.mid | https://web.archive.org/web/20130430205652id_/http://piano-midi.de/midis/haydn/hay_40_2.mid | `6PQWDGXQW4NG6XT2RBA7OOJ4CUTP2TWV` | 22 KB | 2:59 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/haydn/haydn_43_1.mid` | http://www.piano-midi.de/midis/haydn/haydn_43_1.mid | https://web.archive.org/web/20130430222037id_/http://piano-midi.de/midis/haydn/haydn_43_1.mid | `QTEH6YHV5G2HMZ2K4PLFNBIXTQKRULEJ` | 31 KB | 6:18 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/haydn/haydn_43_2.mid` | http://www.piano-midi.de/midis/haydn/haydn_43_2.mid | https://web.archive.org/web/20130430163001id_/http://piano-midi.de/midis/haydn/haydn_43_2.mid | `X3D47PFD2ZBUNXHXNKGS6Z5V3V2QNVBN` | 10 KB | 2:29 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/haydn/haydn_43_3.mid` | http://www.piano-midi.de/midis/haydn/haydn_43_3.mid | https://web.archive.org/web/20121020045544id_/http://www.piano-midi.de/midis/haydn/haydn_43_3.mid | `GIX37RVO24MUTSIQ2AV4T34CYNI7KOT4` | 25 KB | 5:07 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/clementi/clementi_opus36_1_1.mid` | http://www.piano-midi.de/midis/clementi/clementi_opus36_1_1.mid | https://web.archive.org/web/20130508152609id_/http://piano-midi.de/midis/clementi/clementi_opus36_1_1.mid | `TRGR4ZHK2ARLFN6CHPAQUV5FJF6ITDWB` | 12 KB | 1:31 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/clementi/clementi_opus36_1_2.mid` | http://www.piano-midi.de/midis/clementi/clementi_opus36_1_2.mid | https://web.archive.org/web/20130508154142id_/http://piano-midi.de/midis/clementi/clementi_opus36_1_2.mid | `E4TIAMLFW34UWWZEASAC4QVZTOPLCC5H` | 5 KB | 1:08 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/clementi/clementi_opus36_1_3.mid` | http://www.piano-midi.de/midis/clementi/clementi_opus36_1_3.mid | https://web.archive.org/web/20130508180143id_/http://piano-midi.de/midis/clementi/clementi_opus36_1_3.mid | `DLKNB6JQKNHHZMO3V2DUQQBKHAJFLI2H` | 6 KB | 0:56 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/clementi/clementi_opus36_2_1.mid` | http://www.piano-midi.de/midis/clementi/clementi_opus36_2_1.mid | https://web.archive.org/web/20130508115336id_/http://piano-midi.de/midis/clementi/clementi_opus36_2_1.mid | `32NW4WFXKHNOEOOOAKKOPOKFBJCJDW7A` | 15 KB | 2:00 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/clementi/clementi_opus36_2_2.mid` | http://www.piano-midi.de/midis/clementi/clementi_opus36_2_2.mid | https://web.archive.org/web/20130508190728id_/http://piano-midi.de/midis/clementi/clementi_opus36_2_2.mid | `U6AOWGQTIPW5RKVPW6DLHS3EKPVAOCTN` | 4 KB | 1:06 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/clementi/clementi_opus36_2_3.mid` | http://www.piano-midi.de/midis/clementi/clementi_opus36_2_3.mid | https://web.archive.org/web/20130508164721id_/http://piano-midi.de/midis/clementi/clementi_opus36_2_3.mid | `YZDMHEQ7HUGOSWOZ6UYMLB627ZEAM2EY` | 10 KB | 1:34 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/clementi/clementi_opus36_3_1.mid` | http://www.piano-midi.de/midis/clementi/clementi_opus36_3_1.mid | https://web.archive.org/web/20130508165724id_/http://piano-midi.de/midis/clementi/clementi_opus36_3_1.mid | `WEOC4DLSI2TM2UYF4NMS7IEA4U6DYEHE` | 18 KB | 2:54 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/clementi/clementi_opus36_3_2.mid` | http://www.piano-midi.de/midis/clementi/clementi_opus36_3_2.mid | https://web.archive.org/web/20130508135349id_/http://piano-midi.de/midis/clementi/clementi_opus36_3_2.mid | `MWKALLJHTERQYL2TE5JWV6C5IGALFHNG` | 4 KB | 1:28 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/clementi/clementi_opus36_3_3.mid` | http://www.piano-midi.de/midis/clementi/clementi_opus36_3_3.mid | https://web.archive.org/web/20130508124108id_/http://piano-midi.de/midis/clementi/clementi_opus36_3_3.mid | `AUWDLWTACXQNYMS7DPYTXX4QNCXMPJBS` | 9 KB | 1:28 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/clementi/clementi_opus36_4_1.mid` | http://www.piano-midi.de/midis/clementi/clementi_opus36_4_1.mid | https://web.archive.org/web/20130508191351id_/http://piano-midi.de/midis/clementi/clementi_opus36_4_1.mid | `QRBKHAY44RUWSWNBFSGTLJVOYRREMRVS` | 18 KB | 3:19 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/clementi/clementi_opus36_4_2.mid` | http://www.piano-midi.de/midis/clementi/clementi_opus36_4_2.mid | https://web.archive.org/web/20130508120257id_/http://piano-midi.de/midis/clementi/clementi_opus36_4_2.mid | `Z6CI76SVEJDBSVBHGPMO247CKDE7W6ZU` | 6 KB | 1:45 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/clementi/clementi_opus36_4_3.mid` | http://www.piano-midi.de/midis/clementi/clementi_opus36_4_3.mid | https://web.archive.org/web/20130508140234id_/http://piano-midi.de/midis/clementi/clementi_opus36_4_3.mid | `YWFEU5KBSUC534XXQDPBP5H5DBSKEQIJ` | 6 KB | 1:48 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/clementi/clementi_opus36_5_1.mid` | http://www.piano-midi.de/midis/clementi/clementi_opus36_5_1.mid | https://web.archive.org/web/20130508135927id_/http://piano-midi.de/midis/clementi/clementi_opus36_5_1.mid | `YKR5I26JDGMIGUUKVUT7OZPIYKL4RKSV` | 26 KB | 4:55 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/clementi/clementi_opus36_5_2.mid` | http://www.piano-midi.de/midis/clementi/clementi_opus36_5_2.mid | https://web.archive.org/web/20130508170503id_/http://piano-midi.de/midis/clementi/clementi_opus36_5_2.mid | `QRWNGKQOOBGDFLWREZ5ZMFBE4VQHTCNB` | 8 KB | 4:55 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/clementi/clementi_opus36_5_3.mid` | http://www.piano-midi.de/midis/clementi/clementi_opus36_5_3.mid | https://web.archive.org/web/20130508182629id_/http://piano-midi.de/midis/clementi/clementi_opus36_5_3.mid | `3DJPGVZHAZBISMIC5GOF6HQQIWAQNC4L` | 16 KB | 2:56 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/clementi/clementi_opus36_6_1.mid` | http://www.piano-midi.de/midis/clementi/clementi_opus36_6_1.mid | https://web.archive.org/web/20110719072835id_/http://www.piano-midi.de/midis/clementi/clementi_opus36_6_1.mid | `3L6CMD66X2ZEAYZLPU3KVMFA7VW7GRRD` | 32 KB | 5:21 | CC BY-SA 3.0 DE |
| `assets/midi/krueger/clementi/clementi_opus36_6_2.mid` | http://www.piano-midi.de/midis/clementi/clementi_opus36_6_2.mid | https://web.archive.org/web/20110808193302id_/http://piano-midi.de/midis/clementi/clementi_opus36_6_2.mid | `QR4MXGLLYBWV4CMQPSQWZRX2RULX35WR` | 15 KB | 2:17 | CC BY-SA 3.0 DE |

---

## 9. After-download checklist
1. Check the SHA-1 of the Krueger files against §8.6. List the entries of the Sankey zips and map the Scarlatti K-numbers to file names.
2. Parse every file: format, PPQ, tracks and channels, note range, and counts of CC64, CC66 and CC67. Write `durationSec`, `hasSustain` and `noteRange` into `catalog.json`.
3. Confirm that the Clementi op. 36/5 ii and Haydn XVI:7 durations disagree with the site listing. Confirm whether the HWV 430 file holds the whole suite or only the Air.
4. Flag any harpsichord-default file with notes above MIDI 84 (§6).
5. Listen to the tier B and C items on the glasses. Drop anything that sounds mechanical enough to embarrass the library. The candidates are Mutopia K. 457 and op. 111/i.

## 10. Sources consulted
piano-midi.de copy.htm, faq.htm, technic.htm, bach.htm, beeth.htm, mozart.htm, haydn.htm and clementi.htm, all read via Wayback captures from 2025–2026 because the live site returns 418 to this machine · johnsankey.ca: copyright.html, bach.html, harpsichord.html, scarlattirec.html, my.html · jsbach.net/midi/midi_johnsankey.html · Mutopia make-table listings and FTP HEADs · Commons API (categories, the Bednarek upload list, file wikitext) · IMSLP work pages for HWV 430, H. 220, Couperin Livre 2, Rameau Nouvelles suites, Beethoven op. 31/2, 109, 110 and 111, BWV 147, BWV 1068 and BWV 988 · creativecommons.org CC BY-SA 3.0 DE legalcode · magenta.tensorflow.org/datasets/maestro · audiolabs-erlangen.de SMD · github.com/bytedance/GiantMIDI-Piano · zenodo.org/records/14648209 (PDMX) · github.com/craigsapp/{beethoven,mozart,haydn}-piano-sonatas · kunstderfuge.com notes (via search summary) · lists.gnu.org lilypond-user 2020-10/msg00385 · github.com/patakuti/primanota README.
