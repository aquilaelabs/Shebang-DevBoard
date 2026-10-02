#!/usr/bin/env python3
"""Turn a split of the FUTO swipe dataset into arrays for training the glide model.

Usage: prep.py SPLIT.jsonl OUT.npz [--limit N]

Each swipe is put in key pitches (x * 10 across, y * 3 down: the dataset's QWERTY is ten keys by three
rows, the geometry of this keyboard's text layout) and resampled to points SPACING apart along the path, so
a stroke reads the same whatever the touch screen's sampling rate. Per point: u, v and the time in ms since
the first. Per swipe: the keys of its word (0..25, apostrophes dropped, doubled letters once, as the
keyboard's trie spells a word) and a session number. Swipes of words with other characters, with fewer
points than keys or with more than MAX_POINTS are left out.

Output (numpy .npz): points [N,3] float32 (all swipes end to end), offsets [S+1], keys (all end to end)
int8, key_offsets [S+1], session [S] int32, words (the lowercase words, one per line, as bytes).
The data is not in the repository: https://huggingface.co/datasets/futo-org/swipe.futo.org (MIT).
"""
import argparse
import json
import multiprocessing
import sys

import numpy as np

SPACING = 0.25
MAX_POINTS = 200


def key_sequence(word):
    out = []
    prev = -1
    for ch in word:
        if ch == "'":
            continue
        if not ("a" <= ch <= "z"):
            return None
        c = ord(ch) - 97
        if c != prev:
            out.append(c)
        prev = c
    return out


def clean_word(raw):
    w = raw.lower().replace("’", "'")
    i, j = 0, len(w)
    while i < j and not (w[i].isalpha() or w[i] == "'"):
        i += 1
    while j > i and not (w[j - 1].isalpha() or w[j - 1] == "'"):
        j -= 1
    return w[i:j]


def resample(u, v, t):
    """Points SPACING apart along the path (the first point, then every SPACING, then the last)."""
    seg = np.hypot(np.diff(u), np.diff(v))
    s = np.concatenate([[0.0], np.cumsum(seg)])
    total = s[-1]
    if total < 1e-6:
        return None
    # Strictly increasing arc length for interpolation.
    keep = np.concatenate([[True], seg > 1e-9])
    s, u, v, t = s[keep], u[keep], v[keep], t[keep]
    at = np.arange(0.0, total, SPACING)
    if total - at[-1] > SPACING * 0.3:
        at = np.concatenate([at, [total]])
    return np.stack([np.interp(at, s, u), np.interp(at, s, v), np.interp(at, s, t)], axis=1).astype(np.float32)


def convert(line):
    o = json.loads(line)
    word = clean_word(o["word"])
    keys = key_sequence(word)
    if not keys or len(word) < 1:
        return None
    d = o["data"]
    if len(d) < 2:
        return None
    u = np.fromiter((p["x"] for p in d), dtype=np.float64, count=len(d)) * 10.0
    v = np.fromiter((p["y"] for p in d), dtype=np.float64, count=len(d)) * 3.0
    t = np.fromiter((p["t"] for p in d), dtype=np.float64, count=len(d))
    t = t - t[0]
    pts = resample(u, v, t)
    if pts is None or len(pts) < len(keys) or len(pts) > MAX_POINTS or len(pts) < 2:
        return None
    return pts, np.array(keys, dtype=np.int8), o.get("session", ""), word


def main():
    ap = argparse.ArgumentParser(usage=__doc__)
    ap.add_argument("split")
    ap.add_argument("out")
    ap.add_argument("--limit", type=int, default=0)
    args = ap.parse_args()

    points, keys, sessions, words = [], [], [], []
    offsets, key_offsets = [0], [0]
    session_ids = {}
    read = 0
    with open(args.split, encoding="utf-8") as fh, multiprocessing.Pool() as pool:
        lines = fh if not args.limit else (l for i, l in enumerate(fh) if i < args.limit)
        for r in pool.imap(convert, lines, chunksize=512):
            read += 1
            if read % 100000 == 0:
                print(f"  {read} read, {len(words)} kept", file=sys.stderr, flush=True)
            if r is None:
                continue
            pts, k, session, word = r
            points.append(pts)
            keys.append(k)
            offsets.append(offsets[-1] + len(pts))
            key_offsets.append(key_offsets[-1] + len(k))
            sessions.append(session_ids.setdefault(session, len(session_ids)))
            words.append(word)
    np.savez(
        args.out,
        points=np.concatenate(points),
        offsets=np.array(offsets, dtype=np.int64),
        keys=np.concatenate(keys),
        key_offsets=np.array(key_offsets, dtype=np.int64),
        session=np.array(sessions, dtype=np.int32),
        words=np.frombuffer("\n".join(words).encode("utf-8"), dtype=np.uint8),
    )
    print(f"{args.out}: {len(words)} swipes of {read}, {offsets[-1]} points, {len(session_ids)} sessions")


if __name__ == "__main__":
    main()
