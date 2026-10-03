#!/usr/bin/env python3
"""Build app/src/main/assets/dict/en_words.txt from an unpacked SCOWL release.

Usage: tools/build_wordlist.py /path/to/scowl-2020.12.07 [max_level] [--upper-max N] [--proper-names N]
                                [--abbreviations N] [--extra FILE] [--out FILE]

Output: one `word<TAB>tier` per line, sorted by lowercase form, then within it the spelling to write first
(commonest tier, then all-lowercase): the order the app searches in, so it can skip sorting at load time.
The tier is the smallest SCOWL size level that contains the word (10 = most
common, 60 = rare). Words with apostrophes are dropped except the contraction
lists and the 's contractions of a closed set of pronouns and function words
("it's", "that's", "let's"): SCOWL files those among the possessives, and
possessives ("ability's") are noise for a keyboard.
SCOWL is redistributable under its permissive notice (see THIRD_PARTY_NOTICES.md).
"""
import argparse
import os
import sys

LEVELS = [10, 20, 35, 40, 50, 60]
# American English spelling; "english" lists are the shared core.
DIALECTS = ["english", "american"]
CATEGORIES = ["words", "upper", "contractions"]
# Two-letter abbreviations worth offering; the rest are mostly US state codes.
TWO_LETTER_ABBREVIATIONS = {"AI", "TV", "PC", "DJ", "ID", "UK", "IP", "IQ", "CD", "GB", "MB", "HR", "PR", "VR", "UI", "OS"}
# Stems whose 's form is a contraction ("it is", "let us"), not a possessive.
S_CONTRACTION_STEMS = {
    "it", "he", "she", "that", "there", "here", "what", "who", "where", "when", "why", "how", "let",
    "everyone", "everybody", "someone", "somebody", "nobody", "nothing", "something", "everything",
}


def is_s_contraction(word):
    return word.endswith("'s") and word[:-2].lower() in S_CONTRACTION_STEMS


def main():
    ap = argparse.ArgumentParser(usage=__doc__)
    ap.add_argument("root")
    # The defaults build the shipped list (see docs/decisions.md: "Names, brands and abbreviations").
    ap.add_argument("max_level", nargs="?", type=int, default=50)
    # Capitalised words (places, peoples, names) up to this level.
    ap.add_argument("--upper-max", type=int, default=50)
    # SCOWL's proper-names lists (brands, products, people) up to this level; 0 for none.
    ap.add_argument("--proper-names", type=int, default=50)
    # Abbreviations written in capitals ("URL", "API") up to this level; 0 for none.
    ap.add_argument("--abbreviations", type=int, default=50)
    # Our own list of words SCOWL lacks: word<TAB>tier, "#" comments; "" for none.
    ap.add_argument("--extra", default=os.path.join(os.path.dirname(__file__), "extra_words.txt"))
    ap.add_argument("--out")
    args = ap.parse_args()
    root = args.root
    max_level = args.max_level
    final = os.path.join(root, "final")
    words = {}
    for level in LEVELS:
        if level > max_level:
            break
        for dialect in DIALECTS:
            categories = list(CATEGORIES)
            if level <= args.proper_names:
                categories.append("proper-names")
            if level <= args.abbreviations:
                categories.append("abbreviations")
            for cat in categories:
                path = os.path.join(final, f"{dialect}-{cat}.{level}")
                if not os.path.exists(path):
                    continue
                with open(path, encoding="iso-8859-1") as fh:
                    for line in fh:
                        w = line.strip()
                        if not w:
                            continue
                        if "'" in w and cat != "contractions" and not is_s_contraction(w):
                            continue
                        if cat == "upper" and level > args.upper_max:
                            continue
                        # Only abbreviations written in capitals ("URL", not "Dr" or "etc"), and two-letter
                        # ones only when people type them (state codes would crowd short words).
                        if cat == "abbreviations" and not (w.isupper() and (len(w) >= 3 or w in TWO_LETTER_ABBREVIATIONS)):
                            continue
                        if not all(c.isalpha() or c == "'" for c in w):
                            continue
                        if w not in words:
                            words[w] = level
    if args.extra:
        with open(args.extra, encoding="utf-8") as fh:
            for line in fh:
                line = line.strip()
                if not line or line.startswith("#"):
                    continue
                w, tier = line.split("\t")
                if w not in words:
                    words[w] = int(tier)
    out_dir = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "dict")
    os.makedirs(out_dir, exist_ok=True)
    out = args.out or os.path.join(out_dir, "en_words.txt")
    with open(out, "w", encoding="utf-8") as fh:
        # Within one lowercase form, the spelling to write first: the commonest tier, then the all-lowercase
        # one ("wood" before the name "Wood"), as the app's Dictionary.parse orders it.
        for w in sorted(words, key=lambda w: (w.lower(), words[w], w != w.lower(), w)):
            fh.write(f"{w}\t{words[w]}\n")
    print(f"wrote {len(words)} words to {out}")


if __name__ == "__main__":
    main()
