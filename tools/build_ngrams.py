#!/usr/bin/env python3
"""Build the word model used for glide context, suggestions and predictions.

Usage: tools/build_ngrams.py /path/to/eng_sentences.tsv[.bz2] [--cv DIR] [--cv-weight N] [--tatoeba-weight N]
                             [--tech FILE] [--tech-weight W] [--exclude FILE.jsonl ...]

Inputs
  - Tatoeba per-language export (id <TAB> lang <TAB> text), CC BY 2.0 FR, https://tatoeba.org
  - optionally, Common Voice's English sentence collection (--cv: a folder of its server/data/en/*.txt, one
    sentence per line), CC0, https://github.com/common-voice/common-voice; counted with weight --cv-weight
    against --tatoeba-weight (integers, default 1 and 2: the shipped model)
  - optionally, technical documentation as sentences (--tech: tools/tech_corpus.py's output, "name<TAB>text"
    per line), counted with weight --tech-weight (a fraction: it gives development and computer words their
    counts and contexts without outweighing everyday English); every sentence whose CRC-32 % 50 == 7 is held
    out (heldout_tech.tsv) and never counted
  - --exclude: jsonl files with a "sentence" field (the FUTO swipe dataset's test and dev splits): those
    sentences are never counted, so benchmarks on them are not tested on text the model learned from
  - app/src/main/assets/dict/en_words.txt and pack_*.txt (the word lists); only their words are modelled.

Outputs
  - app/src/main/assets/dict/en_ngrams.bin     the model, format below
  - app/src/test/resources/glide/heldout_sentences.tsv
        sentences never counted into the model, for the glide benchmark (id <TAB> text)
  - app/src/test/resources/glide/heldout_tech.tsv
        technical sentences never counted, for the technical benchmarks (crc <TAB> text), when --tech is given

Tokenising: lowercase, curly apostrophes folded to "'", words are [a-z]+('[a-z]+)*.
Sentence punctuation (. ! ?) starts a new sentence; other punctuation is ignored; digits and
words outside the word list break the context (the next word backs off to its unigram).

Every sentence whose Tatoeba id % 50 == 7 is held out (about 2%) and never counted.

Tatoeba's English sentences use "Tom" and "Mary" as the default names: "tom" is 3% of all words,
which would make a glide decoder see "tom" everywhere. Counts involving each (and its 's form) are
scaled down so it is as frequent as "john" in the same corpus.

Format (big-endian, as java.io.DataInputStream reads it):
  magic "SDNG", int version = 2
  int V                                  vocabulary size; ids 1..V, id 0 is the sentence start
  V x { UTF word, varint unigramCount }  words in id order (writeUTF: u16 length + UTF-8)
  varint totalUnigrams
  (V + 1) x context records, id 0 first:
      varint total      c(v): bigram tokens with this context, before pruning
      varint types      N1+(v): distinct followers, before pruning
      varint kept       entries stored (count >= MIN_COUNT)
      kept x { varint followerIdDelta, varint count }   followers ascending, delta from previous
  (version 2) trigram section:
  varint P                               word pairs with followers stored
  P x { varint first, varint second, varint total, varint types, varint kept,
        kept x { varint followerIdDelta, varint count } }
      pairs in ascending (first, second) order; first may be 0 (the sentence start). Only pairs seen at
      least TRI_MIN_CONTEXT times are stored, with followers seen TRI_MIN_COUNT times, the most frequent
      TRI_MAX_FOLLOWERS of them.
"""
import argparse
import bz2
import collections
import glob
import json
import os
import random
import re
import struct
import sys
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..")
WORDS = os.path.join(ROOT, "app", "src", "main", "assets", "dict", "en_words.txt")
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "dict", "en_ngrams.bin")
HELDOUT = os.path.join(ROOT, "app", "src", "test", "resources", "glide", "heldout_sentences.tsv")
HELDOUT_TECH = os.path.join(ROOT, "app", "src", "test", "resources", "glide", "heldout_tech.tsv")

MIN_COUNT = 2
TRI_MIN_CONTEXT = 20
TRI_MIN_COUNT = 3
TRI_MAX_FOLLOWERS = 24
BI_MAX_FOLLOWERS = 400
HOLDOUT_MOD, HOLDOUT_REM = 50, 7
HELDOUT_SAMPLE = 3000
DEFAULT_NAMES = (("tom", "tom's"), ("mary", "mary's"))
NAME_REFERENCE = "john"

TOKEN = re.compile(r"[a-z]+(?:'[a-z]+)*|[0-9]+|[.!?]+")


def varint(n):
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def utf(s):
    b = s.encode("utf-8")
    return struct.pack(">H", len(b)) + b


def tokens(text):
    """Yields words and None for a context break; "." style tokens yield "<s>"."""
    for t in TOKEN.findall(text.lower().replace("’", "'").replace("‘", "'")):
        if t[0] in ".!?":
            yield "<s>"
        elif t[0].isdigit():
            yield None
        else:
            yield t


def sequences(tatoeba, heldout, cv_dir, excluded, tech=None, heldout_tech=None):
    """Yields (weight_key, tokens of one sentence): "t" for Tatoeba, "c" for Common Voice, "x" for the technical text."""
    opener = bz2.open if tatoeba.endswith(".bz2") else open
    with opener(tatoeba, "rt", encoding="utf-8") as fh:
        for line in fh:
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 3 or parts[1] != "eng":
                continue
            sid, text = int(parts[0]), parts[2]
            if sid % HOLDOUT_MOD == HOLDOUT_REM:
                heldout.append((sid, text))
                continue
            yield "t", list(tokens(text))
    if cv_dir:
        for name in sorted(os.listdir(cv_dir)):
            if not name.endswith(".txt"):
                continue
            with open(os.path.join(cv_dir, name), encoding="utf-8") as fh:
                for line in fh:
                    text = line.strip()
                    if not text or text in excluded:
                        continue
                    yield "c", list(tokens(text))
    if tech:
        with open(tech, encoding="utf-8") as fh:
            for line in fh:
                text = line.rstrip("\n").split("\t", 1)[-1].strip()
                if not text or text in excluded:
                    continue
                crc = zlib.crc32(text.encode("utf-8"))
                if crc % HOLDOUT_MOD == HOLDOUT_REM:
                    if heldout_tech is not None:
                        heldout_tech.append((crc, text))
                    continue
                yield "x", list(tokens(text))


def main():
    ap = argparse.ArgumentParser(usage=__doc__)
    ap.add_argument("tatoeba")
    ap.add_argument("--cv")
    ap.add_argument("--cv-weight", type=int, default=1)
    ap.add_argument("--tatoeba-weight", type=int, default=2)
    ap.add_argument("--tech")
    ap.add_argument("--tech-weight", type=float, default=0.0)
    ap.add_argument("--exclude", nargs="*", default=[])
    ap.add_argument("--out", default=OUT)
    ap.add_argument("--words", default=WORDS)
    ap.add_argument("--heldout", default=HELDOUT)
    args = ap.parse_args()
    weight = {"t": args.tatoeba_weight, "c": args.cv_weight, "x": args.tech_weight}

    excluded = set()
    for f in args.exclude:
        with open(f, encoding="utf-8") as fh:
            for line in fh:
                try:
                    excluded.add(json.loads(line)["sentence"].strip())
                except (ValueError, KeyError):
                    pass
    print(f"excluding {len(excluded)} sentences")

    # The regular words and every pack, so a pack word has its counts whenever its pack is on.
    vocab_words = set()
    for path in [args.words] + sorted(glob.glob(os.path.join(os.path.dirname(args.words), "pack_*.txt"))):
        with open(path, encoding="utf-8") as fh:
            for line in fh:
                w = line.split("\t")[0].lower()
                if w:
                    vocab_words.add(w)

    # Pass 1: words and pairs.
    uni = collections.Counter()
    bi = collections.Counter()
    heldout = []
    heldout_tech = []
    for key, toks in sequences(args.tatoeba, heldout, args.cv, excluded, args.tech, heldout_tech):
        wt = weight[key]
        if wt == 0:
            continue
        prev = "<s>"
        for t in toks:
            if t == "<s>":
                prev = "<s>"
                continue
            if t is None or t not in vocab_words:
                prev = None
                continue
            uni[t] += wt
            if prev is not None:
                bi[(prev, t)] += wt
            prev = t

    # Tone down Tatoeba's default names, each to the reference name's frequency.
    name_scale: dict[str, float] = {}
    ref = uni.get(NAME_REFERENCE, 1)
    for group in DEFAULT_NAMES:
        top = uni.get(group[0], 0)
        if top > ref:
            f = ref / top
            for n in group:
                name_scale[n] = f
                if n in uni:
                    uni[n] = max(1, int(uni[n] * f))
            print(f"scaled {group} by {f:.4f} to match '{NAME_REFERENCE}' ({ref})")

    def rounded(counter):
        for key in list(counter):
            c = round(counter[key])
            if c > 0:
                counter[key] = c
            else:
                del counter[key]

    def scaled(counter):
        for key in list(counter):
            f = 1.0
            for k in key:
                f *= name_scale.get(k, 1.0)
            if f < 1:
                c = int(counter[key] * f)
                if c > 0:
                    counter[key] = c
                else:
                    del counter[key]

    scaled(bi)
    # A fractional weight leaves fractional counts: the model stores whole ones.
    rounded(uni)
    rounded(bi)

    words = sorted(uni)  # ids 1..V
    wid = {w: i + 1 for i, w in enumerate(words)}
    wid["<s>"] = 0
    V = len(words)

    total = collections.Counter()
    types = collections.Counter()
    kept: dict[int, list[tuple[int, int]]] = collections.defaultdict(list)
    for (v, w), c in bi.items():
        vi = wid[v]
        total[vi] += c
        types[vi] += 1
        if c >= MIN_COUNT:
            kept[vi].append((wid[w], c))
    # Contexts with very many followers keep the most frequent ones (totals and types stay exact).
    for vi in list(kept):
        if len(kept[vi]) > BI_MAX_FOLLOWERS:
            kept[vi] = sorted(kept[vi], key=lambda e: -e[1])[:BI_MAX_FOLLOWERS]

    # Pass 2: followers of the pairs seen often enough to be worth a trigram.
    frequent = {k for k, c in bi.items() if c >= TRI_MIN_CONTEXT}
    tri = collections.Counter()
    for key, toks in sequences(args.tatoeba, [], args.cv, excluded, args.tech, None):
        wt = weight[key]
        if wt == 0:
            continue
        p2, p1 = None, "<s>"
        for t in toks:
            if t == "<s>":
                p2, p1 = None, "<s>"
                continue
            if t is None or t not in vocab_words:
                p2, p1 = None, None
                continue
            if p2 is not None and p1 is not None and (p2, p1) in frequent:
                tri[(p2, p1, t)] += wt
            p2, p1 = p1, t
    scaled(tri)
    rounded(tri)

    out = bytearray()
    out += b"SDNG" + struct.pack(">i", 2) + struct.pack(">i", V)
    for w in words:
        out += utf(w) + varint(uni[w])
    out += varint(sum(uni.values()))
    stored = 0
    for vi in range(V + 1):
        entries = sorted(kept.get(vi, []))
        out += varint(total[vi]) + varint(types[vi]) + varint(len(entries))
        last = 0
        for fid, c in entries:
            out += varint(fid - last) + varint(c)
            last = fid
        stored += len(entries)
    # Trigram section.
    tri_total = collections.Counter()
    tri_types = collections.Counter()
    tri_kept = collections.defaultdict(list)
    for (a, b, w), c in tri.items():
        if a not in wid or b not in wid:
            continue
        pair = (wid[a], wid[b])
        tri_total[pair] += c
        tri_types[pair] += 1
        if c >= TRI_MIN_COUNT:
            tri_kept[pair].append((wid[w], c))
    pairs = sorted(p for p in tri_kept if tri_kept[p])
    out += varint(len(pairs))
    tri_stored = 0
    for pair in pairs:
        entries = sorted(sorted(tri_kept[pair], key=lambda e: -e[1])[:TRI_MAX_FOLLOWERS])
        out += varint(pair[0]) + varint(pair[1]) + varint(tri_total[pair]) + varint(tri_types[pair]) + varint(len(entries))
        last = 0
        for fid, c in entries:
            out += varint(fid - last) + varint(c)
            last = fid
        tri_stored += len(entries)
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "wb") as fh:
        fh.write(out)
    print(f"wrote {args.out}: {V} words, {sum(uni.values())} tokens, {stored} bigrams "
          f"(of {len(bi)}), {len(pairs)} trigram contexts with {tri_stored} followers, {len(out) / 1024:.0f} KiB")

    # Held-out sentences for the benchmark: modern, fully in-vocabulary, 3 to 12 words.
    rng = random.Random(20260930)
    rng.shuffle(heldout)
    chosen = []
    for sid, text in heldout:
        toks = list(tokens(text))
        if any(t is None for t in toks):
            continue
        words_only = [t for t in toks if t is not None and t != "<s>"]
        if any(t not in vocab_words for t in words_only):
            continue
        if toks.count("<s>") > 1 or not (3 <= len(words_only) <= 12):
            continue
        if any(t in DEFAULT_NAMES for t in words_only):
            continue
        chosen.append((sid, " ".join(words_only)))
        if len(chosen) >= HELDOUT_SAMPLE:
            break
    os.makedirs(os.path.dirname(args.heldout), exist_ok=True)
    with open(args.heldout, "w", encoding="utf-8") as fh:
        fh.write("# Tatoeba sentences (https://tatoeba.org, CC BY 2.0 FR), held out of en_ngrams.bin.\n")
        fh.write("# id\\tlowercased words\n")
        for sid, text in chosen:
            fh.write(f"{sid}\t{text}\n")
    print(f"wrote {len(chosen)} held-out sentences to {args.heldout}")

    # Held-out technical sentences, for measuring the technical words in context: fully in-vocabulary, 3 to 20 words.
    if args.tech:
        rng.shuffle(heldout_tech)
        tech_chosen = []
        for crc, text in heldout_tech:
            toks = list(tokens(text))
            if any(t is None for t in toks) or toks.count("<s>") > 1:
                continue
            words_only = [t for t in toks if t != "<s>"]
            if not (3 <= len(words_only) <= 20) or any(t not in vocab_words for t in words_only):
                continue
            tech_chosen.append((crc, " ".join(words_only)))
            if len(tech_chosen) >= HELDOUT_SAMPLE:
                break
        with open(HELDOUT_TECH, "w", encoding="utf-8") as fh:
            fh.write("# Technical documentation sentences (THIRD_PARTY_NOTICES.md, \"Technical documentation\"), held out of en_ngrams.bin.\n")
            fh.write("# crc32\\tlowercased words\n")
            for crc, text in tech_chosen:
                fh.write(f"{crc}\t{text}\n")
        print(f"wrote {len(tech_chosen)} held-out technical sentences to {HELDOUT_TECH}")


if __name__ == "__main__":
    main()
