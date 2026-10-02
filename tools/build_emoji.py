#!/usr/bin/env python3
"""Build app/src/main/assets/emoji/emoji.txt from Unicode's emoji-test.txt.

Usage: tools/build_emoji.py /path/to/emoji-test.txt

Source: https://www.unicode.org/Public/emoji/latest/emoji-test.txt (Unicode License V3; see
THIRD_PARTY_NOTICES.md). Keeps the fully-qualified emoji in Unicode's order, by group, leaving out the
skin-tone and hair-style variants (the panel offers each emoji once) and the Component group. Each emoji keeps
the Emoji version it arrived in, so the keyboard can skip ones the phone's font cannot draw.

Output: a line "= Group name" before each group ("#" starts the keycap emoji), then one "emoji<TAB>version" per line.
"""
import os
import re
import sys

SKIN = set(range(0x1F3FB, 0x1F400))
HAIR = set(range(0x1F9B0, 0x1F9B4))
LINE = re.compile(r"^([0-9A-F ]+?)\s*;\s*fully-qualified\s*#\s*(\S+)\s+E(\d+\.\d+)\s")


def main():
    src = sys.argv[1]
    out = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "emoji", "emoji.txt")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    group = None
    count = 0
    with open(src, encoding="utf-8") as fh, open(out, "w", encoding="utf-8") as w:
        for line in fh:
            if line.startswith("# group:"):
                group = line.split(":", 1)[1].strip()
                if group != "Component":
                    w.write(f"= {group}\n")
                continue
            if group in (None, "Component"):
                continue
            m = LINE.match(line)
            if not m:
                continue
            cps = [int(c, 16) for c in m.group(1).split()]
            if any(c in SKIN or c in HAIR for c in cps):
                continue
            w.write(f"{''.join(chr(c) for c in cps)}\t{m.group(3)}\n")
            count += 1
    print(f"wrote {count} emoji to {out}")


if __name__ == "__main__":
    main()
