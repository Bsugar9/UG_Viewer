"""
Regenerates app/src/main/assets/guitar_chords.json from the chords-db project.

Source: https://github.com/tombatossals/chords-db (MIT licence)
         lib/guitar.json -> the built database
Rendering semantics follow the reference renderer in
https://github.com/tombatossals/react-chords (src/Chord):

  * frets[i] is ordered LOW string (6th, E2) first, HIGH string (1st, E4) last.
  * frets[i] == -1  muted string
  * frets[i] ==  0  open string
  * frets[i] >=  1  relative row: the value is an offset from baseFret, so a
                    value of v is drawn on row v and means baseFret + v - 1.
  * barres[]        fret values that are barred. The bar spans from the first
                    to the last string carrying that value.
  * baseFret        1 draws a nut, anything higher draws a "Nfr" label.

We keep only whole-chord voicings (slash voicings are dropped) and strip the
midi/validation fields, which cuts the payload from ~370 KB to ~160 KB.

Usage:  python tools/fetch_chords_db.py
"""

import json
import os
import urllib.request

RAW_URL = "https://raw.githubusercontent.com/tombatossals/chords-db/master/lib/guitar.json"
OUT_PATH = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "app", "src", "main", "assets", "guitar_chords.json",
)


def main() -> None:
    with urllib.request.urlopen(RAW_URL) as response:
        source = json.loads(response.read().decode("utf-8"))

    out = {
        "source": "tombatossals/chords-db (MIT) https://github.com/tombatossals/chords-db",
        "strings": source["main"]["strings"],
        "fretsOnChord": source["main"]["fretsOnChord"],
        "tuning": source["tunings"]["standard"],
        "keys": source["keys"],
        "chords": {},
    }

    shapes = 0
    for root, entries in source["chords"].items():
        kept = []
        for entry in entries:
            if "/" in entry["suffix"]:
                continue  # slash voicings, not needed for a chord reference
            positions = []
            for pos in entry["positions"]:
                item = {
                    "frets": pos["frets"],
                    "baseFret": pos["baseFret"],
                }
                if pos.get("fingers"):
                    item["fingers"] = pos["fingers"]
                if pos.get("barres"):
                    item["barres"] = pos["barres"]
                if pos.get("capo"):
                    item["capo"] = True
                positions.append(item)
                shapes += 1
            kept.append({"key": entry["key"], "suffix": entry["suffix"], "positions": positions})
        out["chords"][root] = kept

    os.makedirs(os.path.dirname(OUT_PATH), exist_ok=True)
    with open(OUT_PATH, "w", encoding="utf-8") as handle:
        json.dump(out, handle, separators=(",", ":"), ensure_ascii=False)

    print("wrote %d shapes to %s" % (shapes, OUT_PATH))


if __name__ == "__main__":
    main()
