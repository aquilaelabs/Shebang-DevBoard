#!/usr/bin/env python3
"""Does the next-word model choose between glide candidates better than the three-word model?

Usage: glide_context.py LM.pt DEV.jsonl TEST.jsonl

DEV and TEST are FutoSwipesTest.futoDump dumps (each swipe's candidates with their stroke cost "ac", which
includes the glide model's, and the three-word model's cost "lm", plus the words before it in its sentence).
Each candidate is scored ac + a * lm + b * nn, nn being the next-word model's -ln P(word | sentence so far);
a and b are chosen on DEV, then TEST is scored once against the shipped ac + 0.75 * lm.
"""
import argparse
import json
import math
import os
import re
import sys

import torch
import torch.nn.functional as F

sys.path.insert(0, os.path.dirname(__file__))
from train import WordLSTM  # noqa: E402

WORD = re.compile(r"[A-Za-z]+(?:['’][A-Za-z]+)*")


def load(path):
    return [json.loads(l) for l in open(path, encoding="utf-8")]


def nn_costs(model, device, ids, swipes):
    """Per swipe, -ln P(candidate | the words before) for each candidate."""
    out = []
    unk = ids["<unk>"]
    with torch.no_grad():
        for o in swipes:
            before = [w.lower().replace("’", "'") for w in WORD.findall(o["before"])]
            x = torch.tensor([[1] + [ids.get(w, unk) for w in before]], device=device)
            logits, _ = model(x)
            logp = F.log_softmax(logits[0, -1].float(), dim=-1)
            # A word outside the model's vocabulary shares <unk>'s share; a little less likely than that.
            out.append([-(logp[ids[c["w"]]].item() if c["w"] in ids else logp[unk].item() - 2.0) for c in o["cands"]])
    return out


def accuracy(swipes, costs, a, b):
    right = []
    for o, nn in zip(swipes, costs):
        if not o["cands"]:
            right.append(False)
            continue
        best = min(range(len(o["cands"])), key=lambda k: o["cands"][k]["ac"] + a * o["cands"][k]["lm"] + b * nn[k])
        right.append(o["cands"][best]["w"] == o["word"])
    return right


def main():
    ap = argparse.ArgumentParser(usage=__doc__)
    ap.add_argument("lm")
    ap.add_argument("dev")
    ap.add_argument("test")
    args = ap.parse_args()
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    ck = torch.load(args.lm, map_location=device)
    model = WordLSTM(**ck["config"]).to(device)
    model.load_state_dict(ck["state"])
    model.eval()
    ids = {w: i for i, w in enumerate(ck["vocab"])}
    dev, test = load(args.dev), load(args.test)
    dev_c, test_c = nn_costs(model, device, ids, dev), nn_costs(model, device, ids, test)
    base = sum(accuracy(dev, dev_c, 0.75, 0.0)) / len(dev)
    print(f"dev: shipped (a 0.75, b 0) {100 * base:.2f}%")
    best = (base, 0.75, 0.0)
    for a in [0.0, 0.25, 0.5, 0.75]:
        for b in [0.25, 0.5, 0.75, 1.0]:
            acc = sum(accuracy(dev, dev_c, a, b)) / len(dev)
            print(f"dev: a {a} b {b}: {100 * acc:.2f}%")
            if acc > best[0]:
                best = (acc, a, b)
    print(f"chosen on dev: a {best[1]} b {best[2]} ({100 * best[0]:.2f}%)")
    old = accuracy(test, test_c, 0.75, 0.0)
    new = accuracy(test, test_c, best[1], best[2])
    fixed = sum(n and not o for n, o in zip(new, old))
    broke = sum(o and not n for n, o in zip(new, old))
    m, k = fixed + broke, min(fixed, broke)
    p = min(1.0, 2 * sum(math.comb(m, i) for i in range(k + 1)) / 2 ** m) if m else 1.0
    print(f"test ({len(test)}): shipped {100 * sum(old) / len(test):.2f}%, with the next-word model {100 * sum(new) / len(test):.2f}%; "
          f"fixed {fixed}, broke {broke}, sign test p = {p:.2g}")


if __name__ == "__main__":
    main()
