#!/usr/bin/env python3
"""Build app/src/main/assets/dict/en_words.txt from an unpacked SCOWL release.

Usage: tools/build_wordlist.py /path/to/scowl-2020.12.07 [max_level]

Output: one `word<TAB>tier` per line, sorted by word (binary-searchable).
The tier is the smallest SCOWL size level that contains the word (10 = most
common, 60 = rare). Words with apostrophes are dropped except the contraction
lists; possessives ("ability's") are noise for a keyboard.
SCOWL is redistributable under its permissive notice (see THIRD_PARTY_NOTICES.md).
"""
import os
import sys

LEVELS = [10, 20, 35, 40, 50, 60]
# American English spelling; "english" lists are the shared core.
DIALECTS = ["english", "american"]
CATEGORIES = ["words", "upper", "contractions"]


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    root = sys.argv[1]
    max_level = int(sys.argv[2]) if len(sys.argv) > 2 else 60
    final = os.path.join(root, "final")
    words = {}
    for level in LEVELS:
        if level > max_level:
            break
        for dialect in DIALECTS:
            for cat in CATEGORIES:
                path = os.path.join(final, f"{dialect}-{cat}.{level}")
                if not os.path.exists(path):
                    continue
                with open(path, encoding="iso-8859-1") as fh:
                    for line in fh:
                        w = line.strip()
                        if not w:
                            continue
                        if "'" in w and cat != "contractions":
                            continue
                        if cat == "upper" and level > 35:
                            continue  # proper nouns beyond the common set are noise
                        if not all(c.isalpha() or c == "'" for c in w):
                            continue
                        if w not in words:
                            words[w] = level
    out_dir = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "dict")
    os.makedirs(out_dir, exist_ok=True)
    out = os.path.join(out_dir, "en_words.txt")
    with open(out, "w", encoding="utf-8") as fh:
        for w in sorted(words):
            fh.write(f"{w}\t{words[w]}\n")
    print(f"wrote {len(words)} words to {out}")


if __name__ == "__main__":
    main()
