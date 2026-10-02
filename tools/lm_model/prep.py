#!/usr/bin/env python3
"""Turn the sentence corpora into token arrays for training the next-word model.

Usage: prep.py TATOEBA.tsv[.bz2] CV_DIR OUT.npz [--exclude FUTO.jsonl ...] [--vocab N]

The same sentences and tokens as tools/build_ngrams.py (whose held-out Tatoeba sentences and the FUTO
sentences named by --exclude are never used), so the two models are measured on the same ground. The
vocabulary is the N most frequent corpus words that are in the app's word list; other words become <unk>,
digits (a context break for the n-grams) become <num>, and every sentence starts with <s>.

Output (numpy .npz): tokens int32 (all sentences end to end, each beginning with <s>), vocab (one word per
line, ids from 0: <pad>, <s>, <unk>, <num>, then words).
"""
import argparse
import collections
import os
import sys

import numpy as np

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import build_ngrams  # noqa: E402

SPECIALS = ["<pad>", "<s>", "<unk>", "<num>"]


def main():
    ap = argparse.ArgumentParser(usage=__doc__)
    ap.add_argument("tatoeba")
    ap.add_argument("cv")
    ap.add_argument("out")
    ap.add_argument("--exclude", nargs="*", default=[])
    ap.add_argument("--vocab", type=int, default=32000)
    args = ap.parse_args()

    words = set()
    with open(build_ngrams.WORDS, encoding="utf-8") as fh:
        for line in fh:
            words.add(line.split("\t")[0].lower())
    import json
    excluded = set()
    if args.exclude:
        for path in args.exclude:
            with open(path, encoding="utf-8") as fh:
                for line in fh:
                    s = json.loads(line).get("sentence")
                    if s:
                        excluded.add(s.strip())

    sentences = []
    counts = collections.Counter()
    for _key, toks in build_ngrams.sequences(args.tatoeba, [], args.cv, excluded):
        # One sentence may hold several (". " starts a new one); split them.
        cur = []
        for t in toks:
            if t == "<s>":
                if cur:
                    sentences.append(cur)
                cur = []
            else:
                cur.append(t)
        if cur:
            sentences.append(cur)
    for s in sentences:
        for t in s:
            if t is not None and t in words:
                counts[t] += 1
    vocab = SPECIALS + [w for w, _ in counts.most_common(args.vocab - len(SPECIALS))]
    ids = {w: i for i, w in enumerate(vocab)}
    out = []
    unk = 0
    total = 0
    for s in sentences:
        out.append(1)
        for t in s:
            total += 1
            if t is None:
                out.append(3)
            elif t in ids:
                out.append(ids[t])
            else:
                out.append(2)
                unk += 1
    np.savez(args.out, tokens=np.array(out, dtype=np.int32), vocab=np.frombuffer("\n".join(vocab).encode(), dtype=np.uint8))
    print(f"{args.out}: {len(sentences)} sentences, {total} words, {len(vocab)} in the vocabulary, {100 * unk / total:.2f}% <unk>")


if __name__ == "__main__":
    main()
