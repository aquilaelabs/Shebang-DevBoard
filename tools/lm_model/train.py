#!/usr/bin/env python3
"""Train the next-word model (an LSTM over words) and measure it as PredictionBenchmarkTest measures the
n-gram model: before each word of a held-out sentence that is in the app's word list, are its three best
guesses right (from the sentence so far)?

Usage: train.py CORPUS.npz OUT.pt [--futo TEST.jsonl] [--emb N] [--hidden N] [--layers N] [--vocab N]
                [--epochs N] [--lr F]

Run with a turn on the GPU: bb gpu run -- python-ml tools/lm_model/train.py ...
Held out: the first 2000 of app/src/test/resources/glide/heldout_sentences.tsv (Tatoeba sentences the corpus
never had), and the first 2000 distinct sentences of FUTO's test split (never in the corpus either).
"""
import argparse
import glob
import json
import math
import os
import re
import time

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F

ROOT = os.path.join(os.path.dirname(__file__), "..", "..")
HELDOUT = os.path.join(ROOT, "app", "src", "test", "resources", "glide", "heldout_sentences.tsv")
WORDS = os.path.join(ROOT, "app", "src", "main", "assets", "dict", "en_words.txt")
WORD = re.compile(r"[A-Za-z]+(?:['’][A-Za-z]+)*")


class WordLSTM(nn.Module):
    def __init__(self, vocab, emb=256, hidden=512, layers=1, dropout=0.2):
        super().__init__()
        self.config = dict(vocab=vocab, emb=emb, hidden=hidden, layers=layers)
        self.emb = nn.Embedding(vocab, emb)
        self.drop = nn.Dropout(dropout)
        self.lstm = nn.LSTM(emb, hidden, layers, batch_first=True, dropout=dropout if layers > 1 else 0.0)
        self.proj = nn.Linear(hidden, emb)
        # The output layer shares the embedding (tied): half the size, and better on small corpora.
        self.bias = nn.Parameter(torch.zeros(vocab))

    def forward(self, x, state=None):
        y, state = self.lstm(self.drop(self.emb(x)), state)
        logits = F.linear(self.proj(self.drop(y)), self.emb.weight, self.bias)
        return logits, state


def heldout_sets(futo):
    sets = []
    lines = [l.rstrip("\n").split("\t", 1)[1] for l in open(HELDOUT, encoding="utf-8") if l.strip() and not l.startswith("#")]
    sets.append(("Tatoeba", [l.split(" ") for l in lines[:2000]]))
    if futo and os.path.isfile(futo):
        seen = []
        have = set()
        with open(futo, encoding="utf-8") as fh:
            for line in fh:
                s = json.loads(line).get("sentence")
                if s and s not in have:
                    have.add(s)
                    seen.append(s)
                if len(seen) >= 3000:
                    break
        toks = [[w.replace("’", "'") for w in WORD.findall(s)] for s in seen]
        sets.append(("FUTO", [t for t in toks if t][:2000]))
    return sets


def evaluate(model, device, sets, ids, vocab, dictionary):
    """(label, words, top-1 rate, top-3 rate) per set."""
    model.eval()
    banned = torch.tensor([0, 1, 2, 3], device=device)
    out = []
    with torch.no_grad():
        for label, sentences in sets:
            n = top1 = top3 = 0
            for words in sentences:
                lw = [w.lower() for w in words]
                x = torch.tensor([[1] + [ids.get(w, 2) for w in lw]], device=device)
                logits, _ = model(x)
                logits[0, :, banned] = -1e9
                best = logits[0].topk(3, dim=-1).indices.cpu().numpy()
                for i, w in enumerate(lw):
                    if w not in dictionary:
                        continue
                    n += 1
                    g = [vocab[k] for k in best[i]]
                    top1 += g[0] == w
                    top3 += w in g
            out.append((label, n, top1 / n, top3 / n))
    return out


def main():
    ap = argparse.ArgumentParser(usage=__doc__)
    ap.add_argument("corpus")
    ap.add_argument("out")
    ap.add_argument("--futo")
    ap.add_argument("--emb", type=int, default=256)
    ap.add_argument("--hidden", type=int, default=512)
    ap.add_argument("--layers", type=int, default=1)
    ap.add_argument("--vocab", type=int, default=0, help="use only the N most frequent words (0: all)")
    ap.add_argument("--epochs", type=int, default=6)
    ap.add_argument("--batch", type=int, default=256)
    ap.add_argument("--bptt", type=int, default=64)
    ap.add_argument("--lr", type=float, default=2e-3)
    args = ap.parse_args()

    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    torch.manual_seed(1)
    d = np.load(args.corpus)
    vocab = bytes(d["vocab"]).decode().split("\n")
    tokens = d["tokens"].astype(np.int64)
    if args.vocab:
        vocab = vocab[: args.vocab]
        tokens = np.where(tokens >= args.vocab, 2, tokens)
    ids = {w: i for i, w in enumerate(vocab)}
    # The regular words and every pack.
    dictionary = set()
    for path in [WORDS] + sorted(glob.glob(os.path.join(os.path.dirname(WORDS), "pack_*.txt"))):
        with open(path, encoding="utf-8") as fh:
            dictionary |= {line.split("\t")[0].lower() for line in fh}
    sets = heldout_sets(args.futo)

    # One stream, cut into [batch] parallel rows, read [bptt] tokens at a time with the state carried on.
    rows = len(tokens) // args.batch
    stream = torch.from_numpy(tokens[: rows * args.batch].reshape(args.batch, rows)).to(device)
    model = WordLSTM(len(vocab), args.emb, args.hidden, args.layers).to(device)
    params = sum(p.numel() for p in model.parameters())
    print(f"device {device}; {len(tokens)} tokens, vocabulary {len(vocab)}; {params} parameters", flush=True)
    for label, n, t1, t3 in evaluate(model, device, sets, ids, vocab, dictionary):
        print(f"  untrained {label}: {n} words", flush=True)
    opt = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=1e-5)
    steps = args.epochs * ((rows - 1) // args.bptt)
    sched = torch.optim.lr_scheduler.OneCycleLR(opt, max_lr=args.lr, total_steps=steps, pct_start=0.05)
    for epoch in range(args.epochs):
        model.train()
        t0 = time.time()
        state = None
        total = 0.0
        count = 0
        for start in range(0, rows - 1 - args.bptt + 1, args.bptt):
            x = stream[:, start:start + args.bptt]
            y = stream[:, start + 1:start + 1 + args.bptt]
            if state is not None:
                state = tuple(s.detach() for s in state)
            logits, state = model(x, state)
            loss = F.cross_entropy(logits.reshape(-1, logits.shape[-1]), y.reshape(-1), ignore_index=0)
            opt.zero_grad(set_to_none=True)
            loss.backward()
            nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            opt.step()
            if sched.last_epoch < steps - 1:
                sched.step()
            total += loss.item()
            count += 1
        res = evaluate(model, device, sets, ids, vocab, dictionary)
        line = "; ".join(f"{label} first {100 * t1:.1f}%, in three {100 * t3:.1f}%" for label, n, t1, t3 in res)
        print(f"epoch {epoch + 1}/{args.epochs}: train ppl {math.exp(total / count):.1f}; {line}; {time.time() - t0:.0f} s", flush=True)
        torch.save({"state": model.state_dict(), "config": model.config, "vocab": vocab}, args.out)
    print(f"saved {args.out}")


if __name__ == "__main__":
    main()
