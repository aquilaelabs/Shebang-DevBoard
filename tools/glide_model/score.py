#!/usr/bin/env python3
"""Re-rank the glide decoder's candidates with the glide model, and measure it.

Usage: score.py MODEL.pt TUNE.jsonl TEST.jsonl

TUNE and TEST are dumps from FutoSwipesTest.futoDump (each swipe with the decoder's candidates, their
stroke costs and language-model costs). Each candidate is scored

    decoder:  ac + 0.75 * lm                     (what ships)
    combined: ac + 0.75 * lm + w * nll           (nll: the model's CTC cost for the candidate's keys)
    model:    nll + l * lm                        (the model in place of the decoder's stroke cost)

The weights are chosen on TUNE only, then TEST is scored once with them, with the count of swipes fixed and
broken against the decoder and a two-sided sign test.
"""
import argparse
import json
import math
import os
import sys

import numpy as np
import torch
import torch.nn.functional as F

sys.path.insert(0, os.path.dirname(__file__))
from glidenet import GlideNet  # noqa: E402
from prep import key_sequence, resample  # noqa: E402


def load(path):
    out = []
    with open(path) as fh:
        for line in fh:
            o = json.loads(line)
            pts = resample(np.array(o["u"], dtype=np.float64), np.array(o["v"], dtype=np.float64), np.array(o["t"], dtype=np.float64))
            cands = [c for c in o["cands"] if key_sequence(c["w"])]
            out.append((o["word"], pts, cands))
    return out


def model_costs(model, device, swipes):
    """Per swipe, the CTC cost of each candidate's keys (nan where the stroke has fewer points than keys)."""
    model.eval()
    costs = []
    with torch.no_grad():
        for word, pts, cands in swipes:
            if pts is None or not cands:
                costs.append(np.full(len(cands), np.nan))
                continue
            p = torch.from_numpy(pts).to(device)[None]
            n = torch.tensor([len(pts)], device=device)
            logp = model(p, n)  # [1, T, 27]
            seqs = [key_sequence(c["w"]) for c in cands]
            L = max(len(s) for s in seqs)
            keys = torch.zeros(len(seqs), L, dtype=torch.long, device=device)
            for i, s in enumerate(seqs):
                keys[i, : len(s)] = torch.tensor(s) + 1
            klen = torch.tensor([len(s) for s in seqs], device=device)
            lp = logp.expand(len(seqs), -1, -1).transpose(0, 1).float()
            nll = F.ctc_loss(lp, keys, n.expand(len(seqs)), klen, blank=0, reduction="none", zero_infinity=False)
            c = nll.cpu().numpy()
            c[~np.isfinite(c)] = np.nan
            costs.append(c)
    return costs


def pick(swipes, costs, score):
    """The word each swipe reads as under [score](cand, nll)."""
    out = []
    for (word, pts, cands), nll in zip(swipes, costs):
        if not cands:
            out.append("")
            continue
        best, bw = math.inf, ""
        for c, m in zip(cands, nll):
            s = score(c, 1e4 if np.isnan(m) else m)
            if s < best:
                best, bw = s, c["w"]
        out.append(bw)
    return out


def accuracy(swipes, picks):
    return sum(p == s[0] for p, s in zip(picks, swipes)) / len(swipes)


def sign_test(fixed, broke):
    m, k = fixed + broke, min(fixed, broke)
    tail = sum(math.comb(m, i) for i in range(k + 1))
    return min(1.0, 2 * tail / 2 ** m) if m else 1.0


def main():
    ap = argparse.ArgumentParser(usage=__doc__)
    ap.add_argument("model")
    ap.add_argument("tune")
    ap.add_argument("test")
    args = ap.parse_args()
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    ck = torch.load(args.model, map_location=device)
    model = GlideNet(**ck["config"]).to(device)
    model.load_state_dict(ck["state"])

    tune, test = load(args.tune), load(args.test)
    tune_c, test_c = model_costs(model, device, tune), model_costs(model, device, test)
    decoder = lambda c, m: c["ac"] + 0.75 * c["lm"]
    base_tune = accuracy(tune, pick(tune, tune_c, decoder))
    print(f"tune: decoder {100 * base_tune:.2f}%")
    best = (base_tune, "decoder", decoder)
    for w in [0.1, 0.2, 0.3, 0.5, 0.75, 1.0, 1.5, 2.0, 3.0]:
        f = (lambda w: lambda c, m: c["ac"] + 0.75 * c["lm"] + w * m)(w)
        a = accuracy(tune, pick(tune, tune_c, f))
        print(f"tune: combined w {w}: {100 * a:.2f}%")
        if a > best[0]:
            best = (a, f"combined w {w}", f)
    for l in [0.2, 0.35, 0.5, 0.75, 1.0]:
        f = (lambda l: lambda c, m: m + l * c["lm"])(l)
        a = accuracy(tune, pick(tune, tune_c, f))
        print(f"tune: model alone, lm {l}: {100 * a:.2f}%")
        if a > best[0]:
            best = (a, f"model alone, lm {l}", f)
    print(f"chosen on tune: {best[1]} ({100 * best[0]:.2f}%)")

    base = pick(test, test_c, decoder)
    new = pick(test, test_c, best[2])
    fixed = sum(n == s[0] and b != s[0] for n, b, s in zip(new, base, test))
    broke = sum(b == s[0] and n != s[0] for n, b, s in zip(new, base, test))
    print(f"test ({len(test)} swipes): decoder {100 * accuracy(test, base):.2f}%, {best[1]} {100 * accuracy(test, new):.2f}%; "
          f"fixed {fixed}, broke {broke}, sign test p = {sign_test(fixed, broke):.2g}")
    examples = [(s[0], b, n) for n, b, s in zip(new, base, test) if n != b][:20]
    for w, b, n in examples:
        print(f"  meant {w}: decoder {b}, now {n}")


if __name__ == "__main__":
    main()
