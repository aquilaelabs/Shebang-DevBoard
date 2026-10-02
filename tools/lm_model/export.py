#!/usr/bin/env python3
"""Write a trained next-word model as the app's asset, with test vectors for the Kotlin port.

Usage: export.py LM.pt OUT.bin [--vectors VECTORS.txt] [--futo TEST.jsonl]

The word table (shared by input and output) is stored as int8 with one float scale per word, everything
else as float16. Format (little-endian): "SNW1", int32 vocab, emb, hidden; the words (uint16 length + UTF-8
each); the word table's scales (float32 [V]) then rows (int8 [V, E]); then as float16: weight_ih [4H, E],
weight_hh [4H, H], bias_ih [4H], bias_hh [4H] (gates i, f, g, o as in PyTorch), proj.weight [E, H],
proj.bias [E], output bias [V].

It also prints how the model as stored (int8 table, float16 rest) scores on the held-out sentences, beside
the trained one, and writes a few sentences with the stored model's best next words and their
log-probabilities, which NextWordModelTest checks the Kotlin model against.
"""
import argparse
import os
import struct
import sys

import torch
import torch.nn.functional as F

sys.path.insert(0, os.path.dirname(__file__))
from train import WORDS, WordLSTM, evaluate, heldout_sets  # noqa: E402

SENTENCES = ["i think it", "thank you for the", "can we meet at the", "the weather is", "where are you"]


def main():
    ap = argparse.ArgumentParser(usage=__doc__)
    ap.add_argument("lm")
    ap.add_argument("out")
    ap.add_argument("--vectors")
    ap.add_argument("--futo")
    args = ap.parse_args()
    ck = torch.load(args.lm, map_location="cpu")
    c = ck["config"]
    assert c["layers"] == 1, "the app reads one LSTM layer"
    model = WordLSTM(**c)
    model.load_state_dict(ck["state"])
    model.eval()
    vocab = ck["vocab"]
    sd = model.state_dict()

    emb = sd["emb.weight"]
    scale = emb.abs().max(dim=1).values.clamp(min=1e-8) / 127.0
    q = torch.round(emb / scale[:, None]).clamp(-127, 127).to(torch.int8)
    half = lambda t: t.half().float()
    stored = {
        "emb.weight": q.float() * scale[:, None],
        "lstm.weight_ih_l0": half(sd["lstm.weight_ih_l0"]), "lstm.weight_hh_l0": half(sd["lstm.weight_hh_l0"]),
        "lstm.bias_ih_l0": half(sd["lstm.bias_ih_l0"]), "lstm.bias_hh_l0": half(sd["lstm.bias_hh_l0"]),
        "proj.weight": half(sd["proj.weight"]), "proj.bias": half(sd["proj.bias"]), "bias": half(sd["bias"]),
    }
    assert set(stored) == set(sd), set(sd) ^ set(stored)

    with open(args.out, "wb") as fh:
        fh.write(b"SNW1" + struct.pack("<iii", len(vocab), c["emb"], c["hidden"]))
        for w in vocab:
            b = w.encode("utf-8")
            fh.write(struct.pack("<H", len(b)) + b)
        fh.write(scale.numpy().astype("<f4").tobytes())
        fh.write(q.numpy().tobytes())
        for n in ["lstm.weight_ih_l0", "lstm.weight_hh_l0", "lstm.bias_ih_l0", "lstm.bias_hh_l0", "proj.weight", "proj.bias", "bias"]:
            fh.write(sd[n].numpy().astype("<f2").tobytes())
    print(f"wrote {args.out}: {len(vocab)} words, {os.path.getsize(args.out)} bytes")

    ids = {w: i for i, w in enumerate(vocab)}
    dictionary = {l.split("\t")[0].lower() for l in open(WORDS, encoding="utf-8")}
    sets = heldout_sets(args.futo)
    device = torch.device("cpu")
    before = evaluate(model, device, sets, ids, vocab, dictionary)
    model.load_state_dict(stored)
    after = evaluate(model, device, sets, ids, vocab, dictionary)
    for (label, n, t1, t3), (_, _, s1, s3) in zip(before, after):
        print(f"{label} ({n} words): trained in three {100 * t3:.2f}%, as stored {100 * s3:.2f}%")

    if args.vectors:
        with open(args.vectors, "w", encoding="utf-8") as fh, torch.no_grad():
            for s in SENTENCES:
                x = torch.tensor([[1] + [ids.get(w, 2) for w in s.split()]])
                logits, _ = model(x)
                logp = F.log_softmax(logits[0, -1], dim=-1)
                top = logp.topk(5)
                fh.write(s + "\t" + " ".join(f"{vocab[i]}:{v:.5f}" for v, i in zip(top.values.tolist(), top.indices.tolist())) + "\n")
        print(f"wrote {args.vectors}")


if __name__ == "__main__":
    main()
