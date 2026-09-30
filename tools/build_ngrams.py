#!/usr/bin/env python3
"""Build the unigram/bigram model used for glide context from Tatoeba's English sentences.

Usage: tools/build_ngrams.py /path/to/eng_sentences.tsv[.bz2]

Inputs
  - Tatoeba per-language export (id <TAB> lang <TAB> text), CC BY 2.0 FR, https://tatoeba.org
  - app/src/main/assets/dict/en_words.txt (the SCOWL word list); only its words are modelled.

Outputs
  - app/src/main/assets/dict/en_ngrams.bin     the model, format below
  - app/src/test/resources/glide/heldout_sentences.tsv
        sentences never counted into the model, for the glide benchmark (id <TAB> text)

Tokenising: lowercase, curly apostrophes folded to "'", words are [a-z]+('[a-z]+)*.
Sentence punctuation (. ! ?) starts a new sentence; other punctuation is ignored; digits and
words outside the word list break the context (the next word backs off to its unigram).

Every sentence whose Tatoeba id % 50 == 7 is held out (about 2%) and never counted.

Tatoeba's English sentences use "Tom" as the default name: it is 3% of all words, which would
make a glide decoder see "tom" everywhere. Counts involving "tom" and "tom's" are scaled down
so "tom" is as frequent as "john" in the same corpus. ("Mary" is not in the word list.)

Format (big-endian, as java.io.DataInputStream reads it):
  magic "SDNG", int version = 1
  int V                                  vocabulary size; ids 1..V, id 0 is the sentence start
  V x { UTF word, varint unigramCount }  words in id order (writeUTF: u16 length + UTF-8)
  varint totalUnigrams
  (V + 1) x context records, id 0 first:
      varint total      c(v): bigram tokens with this context, before pruning
      varint types      N1+(v): distinct followers, before pruning
      varint kept       entries stored (count >= MIN_COUNT)
      kept x { varint followerIdDelta, varint count }   followers ascending, delta from previous
"""
import bz2
import collections
import os
import random
import re
import struct
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..")
WORDS = os.path.join(ROOT, "app", "src", "main", "assets", "dict", "en_words.txt")
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "dict", "en_ngrams.bin")
HELDOUT = os.path.join(ROOT, "app", "src", "test", "resources", "glide", "heldout_sentences.tsv")

MIN_COUNT = 2
HOLDOUT_MOD, HOLDOUT_REM = 50, 7
HELDOUT_SAMPLE = 3000
DEFAULT_NAMES = ("tom", "tom's")
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


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    src = sys.argv[1]
    opener = bz2.open if src.endswith(".bz2") else open

    vocab_words = set()
    with open(WORDS, encoding="utf-8") as fh:
        for line in fh:
            w = line.split("\t")[0].lower()
            if w:
                vocab_words.add(w)

    uni = collections.Counter()
    bi = collections.Counter()
    heldout = []
    with opener(src, "rt", encoding="utf-8") as fh:
        for line in fh:
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 3 or parts[1] != "eng":
                continue
            sid, text = int(parts[0]), parts[2]
            if sid % HOLDOUT_MOD == HOLDOUT_REM:
                heldout.append((sid, text))
                continue
            prev = "<s>"
            for t in tokens(text):
                if t == "<s>":
                    prev = "<s>"
                    continue
                if t is None or t not in vocab_words:
                    prev = None
                    continue
                uni[t] += 1
                if prev is not None:
                    bi[(prev, t)] += 1
                prev = t

    # Tone down Tatoeba's default name.
    ref = uni.get(NAME_REFERENCE, 1)
    top = uni.get(DEFAULT_NAMES[0], 0)
    if top > ref:
        f = ref / top
        for n in DEFAULT_NAMES:
            if n in uni:
                uni[n] = max(1, int(uni[n] * f))
        for key in list(bi):
            if key[0] in DEFAULT_NAMES or key[1] in DEFAULT_NAMES:
                c = int(bi[key] * f)
                if c > 0:
                    bi[key] = c
                else:
                    del bi[key]
        print(f"scaled {DEFAULT_NAMES} by {f:.4f} to match '{NAME_REFERENCE}' ({ref})")

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

    out = bytearray()
    out += b"SDNG" + struct.pack(">i", 1) + struct.pack(">i", V)
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
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "wb") as fh:
        fh.write(out)
    print(f"wrote {OUT}: {V} words, {sum(uni.values())} tokens, {stored} bigrams "
          f"(of {len(bi)}), {len(out) / 1024:.0f} KiB")

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
    os.makedirs(os.path.dirname(HELDOUT), exist_ok=True)
    with open(HELDOUT, "w", encoding="utf-8") as fh:
        fh.write("# Tatoeba sentences (https://tatoeba.org, CC BY 2.0 FR), held out of en_ngrams.bin.\n")
        fh.write("# id\\tlowercased words\n")
        for sid, text in chosen:
            fh.write(f"{sid}\t{text}\n")
    print(f"wrote {len(chosen)} held-out sentences to {HELDOUT}")


if __name__ == "__main__":
    main()
