#!/usr/bin/env python3
"""ledger.csv → assets/licenses/SOURCES.csv, assets/credits.txt, CREDITS.md and NOTICE (PLAN.md §6.9, §6.11).

    python3 tools/pipeline/build_credits.py

The text is PLAN §6.11. Lines for material that is not shipped are left out: the Gouin (IMSLP)
line appears only when its files are in the ledger, the Madore line only when permitted and
present, and each other music line only when its source has at least one row. Licence texts are
copied into assets/licenses/ by `midi_extract.py --copy`; this tool checks that every licence a
row uses has its text there (CC0 and PD need none, SANKEY is SANKEY.txt).
"""
import argparse
import os
import shutil
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import check_ledger  # noqa: E402
import common  # noqa: E402

TITLE = "Hammerklavier — a keyboard recital in the Konzertzimmer of Sanssouci, 1747."
INSTRUMENTS = [
    ("grand", "Grand piano: Salamander Grand Piano V3 by Alexander Holm (Yamaha C5), licensed CC-BY 3.0 and declared "
              "public domain by the author in 2022. Retuning tables by Markus Fiedler; SFZ mapping data by kinwie "
              "(sfzinstruments). The samples were trimmed, level-matched, normalised and encoded to Opus for this app."),
    ("vcsl", "Upright piano and harpsichord: Versilian Community Sample Library (VCSL) and VS Chamber Orchestra 2: "
             "Community Edition, Versilian Studios LLC / Sam Gossner; upright sampled by Simon Dalzell (Ivy Audio). CC0 1.0."),
]
MUSIC = [
    ("krueger", "Piano performances of Bach, Haydn, Mozart, Clementi and Beethoven by Bernd Krueger, source "
                "http://www.piano-midi.de, licensed under Creative Commons Attribution-ShareAlike 3.0 Germany "
                "(https://creativecommons.org/licenses/by-sa/3.0/de/deed.en). The files are included unmodified. "
                "Recordings or videos made from them are adaptations and must be shared under the same licence with "
                "this credit."),
    ("sankey", "Harpsichord performances of J.S. Bach and Domenico Scarlatti by John Sankey, © John Sankey, released "
               "for anyone to copy and play freely under his notice at https://www.johnsankey.ca/harpsichord.html "
               "(full text in licenses/SANKEY.txt). The files are included unmodified."),
    ("imslp", "Handel, Suite in E major HWV 430, and Rameau, La Poule: MIDI by Pierre Gouin, Les Éditions "
              "Outremontaises, Montréal, via IMSLP, CC BY-SA 4.0 (https://creativecommons.org/licenses/by-sa/4.0/)."),
    ("commons:nieb", "C.P.E. Bach, Solfeggietto H. 220: sequenced by Shane Nieb, via Wikimedia Commons, CC BY-SA 3.0 "
                     "(https://creativecommons.org/licenses/by-sa/3.0/)."),
    ("commons:bednarek", "Beethoven, Bagatelles op. 126 and op. 33; Mozart, Sonata K. 281 and Variations K. 265: by "
                         "Michael Bednarek, via Wikimedia Commons (CC0 / public domain)."),
    ("commons:frantz", "Rameau, Tambourin: Ricardo André Frantz, after the Mutopia Project, via Wikimedia Commons "
                       "(public domain)."),
    ("mutopia", "Further pieces are engraved by volunteers of The Mutopia Project (https://www.mutopiaproject.org) and "
                "are in the public domain."),
    ("madore", "Couperin, Les Barricades mystérieuses: sequenced by David Madore."),
]
ROOM = ("The room is a procedural evocation of the Konzertzimmer at Sanssouci (Knobelsdorff, Nahl, Hoppenhaupt, "
        "1746–47) by candlelight; its dimensions are a design, not a survey. No photographs or trademarks are used.")
FOOT = "Code © tropicalstream. Licence texts: assets/licenses/."
LICENCE_FILES = {"CC-BY-3.0": "CC-BY-3.0.txt", "CC-BY-SA-3.0": "CC-BY-SA-3.0.txt",
                 "CC-BY-SA-3.0-DE": "CC-BY-SA-3.0-DE.txt", "CC-BY-SA-4.0": "CC-BY-SA-4.0.txt",
                 "SANKEY": "SANKEY.txt", "CC0-1.0": "CC0-1.0.txt"}


def present_keys(rows):
    keys = set()
    for r in rows:
        a = r["asset_path"]
        if a.startswith("instruments/grand/"):
            keys.add("grand")
        elif a.startswith(("instruments/upright/", "instruments/harpsichord/")):
            keys.add("vcsl")
        elif a.startswith("midi/"):
            grp = a.split("/")[1]
            if grp == "commons":
                f = a.lower()
                keys.add("commons:" + ("nieb" if "nieb" in f else "bednarek" if "bednarek" in f else
                                       "frantz" if "frantz" in f else "other"))
            else:
                keys.add(grp)
    return keys


def credits_text(rows):
    keys = present_keys(rows)
    inst = [t for k, t in INSTRUMENTS if k in keys]
    music = [t for k, t in MUSIC if k in keys]
    out = [TITLE, ""]
    if inst:
        out += ["Instruments"] + inst + [""]
    if music:
        out += ["Music"] + music + [""]
    out += [ROOM, "", FOOT]
    return "\n".join(out) + "\n"


def credits_md(rows):
    keys = present_keys(rows)
    out = ["# Credits", "", "**" + TITLE + "**", ""]
    inst = [t for k, t in INSTRUMENTS if k in keys]
    music = [t for k, t in MUSIC if k in keys]
    if inst:
        out += ["## Instruments", ""] + ["- " + t for t in inst] + [""]
    if music:
        out += ["## Music", ""] + ["- " + t for t in music] + [""]
    out += ["## The room", "", ROOM, "", FOOT, "",
            "Every shipped asset, its source, licence and whether it was modified: `assets/licenses/SOURCES.csv` "
            "(generated from `tools/pipeline/ledger.csv`).", ""]
    return "\n".join(out)


def notice(rows):
    keys = present_keys(rows)
    out = ["Hammerklavier", "Copyright tropicalstream.", ""]
    if "grand" in keys:
        out += ["This product includes samples from the Salamander Grand Piano V3 by Alexander Holm,",
                "licensed CC-BY 3.0 (https://creativecommons.org/licenses/by/3.0/); declared public domain by the",
                "author (2022). Both readings apply: CC-BY 3.0; declared public domain by the author, 2022.",
                "Retuning tables by Markus Fiedler; SFZ mapping data by kinwie. The samples were modified",
                "(trimmed, level-matched, normalised, Opus-encoded).", ""]
    if "vcsl" in keys:
        out += ["Upright and harpsichord samples: VCSL and VSCO-2 CE, Versilian Studios LLC, CC0 1.0.", ""]
    if "krueger" in keys:
        out += ["MIDI performances by Bernd Krueger (piano-midi.de), CC BY-SA 3.0 DE, included unmodified.", ""]
    if "sankey" in keys:
        out += ["MIDI performances by John Sankey, under his free-copy notice (licenses/SANKEY.txt), included",
                "unmodified.", ""]
    out += ["See assets/credits.txt and assets/licenses/ for the full credits and licence texts."]
    return "\n".join(out) + "\n"


def build(rows, assets=common.ASSETS, root=common.ROOT):
    errs = []
    for lic in sorted({r["licence_id"] for r in rows}):
        f = LICENCE_FILES.get(lic)
        if f and lic != "CC0-1.0" and not os.path.isfile(os.path.join(assets, "licenses", f)):
            errs.append("licence text licenses/%s missing for %s" % (f, lic))
    if errs:
        return errs
    common.ensure_dir(os.path.join(assets, "licenses"))
    shutil.copyfile(check_ledger.LEDGER if os.path.isfile(check_ledger.LEDGER) else os.devnull,
                    os.path.join(assets, "licenses", "SOURCES.csv"))
    common.write_text(os.path.join(assets, "credits.txt"), credits_text(rows))
    common.write_text(os.path.join(root, "CREDITS.md"), credits_md(rows))
    common.write_text(os.path.join(root, "NOTICE"), notice(rows))
    return []


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.parse_args(argv)
    errs = build(check_ledger.read_ledger())
    for e in errs:
        print("credits: " + e, file=sys.stderr)
    if not errs:
        print("credits: assets/credits.txt, assets/licenses/SOURCES.csv, CREDITS.md, NOTICE written")
    return 1 if errs else 0


if __name__ == "__main__":
    sys.exit(main())
