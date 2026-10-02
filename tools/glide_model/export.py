#!/usr/bin/env python3
"""Write a trained glide model as the app's asset, and test vectors for the Kotlin port.

Usage: export.py MODEL.pt OUT.bin [--vectors DEV.npz VECTORS.txt]

Format (little-endian): "SGM1", int32 conv, hidden, layers, then every tensor in this order as float16:
conv.weight [conv, FEATURES, 5], conv.bias, then per GRU layer and direction (forward, reverse):
weight_ih [3H, in], weight_hh [3H, H], bias_ih [3H], bias_hh [3H] (gates r, z, n as in PyTorch), then
out.weight [27, 2H], out.bias [27]. The vectors file holds a few dev strokes (u v t per point) and the
model's log-probabilities for them, which GlideModelTest checks the Kotlin model against.
"""
import argparse
import os
import struct
import sys

import numpy as np
import torch

sys.path.insert(0, os.path.dirname(__file__))
from glidenet import GlideNet  # noqa: E402


def main():
    ap = argparse.ArgumentParser(usage=__doc__)
    ap.add_argument("model")
    ap.add_argument("out")
    ap.add_argument("--vectors", nargs=2)
    args = ap.parse_args()
    ck = torch.load(args.model, map_location="cpu")
    model = GlideNet(**ck["config"])
    model.load_state_dict(ck["state"])
    model.eval()
    c = ck["config"]
    sd = model.state_dict()
    names = ["conv.weight", "conv.bias"]
    for layer in range(c["layers"]):
        for suffix in ("", "_reverse"):
            names += [f"gru.weight_ih_l{layer}{suffix}", f"gru.weight_hh_l{layer}{suffix}", f"gru.bias_ih_l{layer}{suffix}", f"gru.bias_hh_l{layer}{suffix}"]
    names += ["out.weight", "out.bias"]
    assert set(names) == set(sd), set(sd) ^ set(names)
    with open(args.out, "wb") as fh:
        fh.write(b"SGM1" + struct.pack("<iii", c["conv"], c["hidden"], c["layers"]))
        for n in names:
            fh.write(sd[n].numpy().astype("<f2").tobytes())
    print(f"wrote {args.out}: {sum(sd[n].numel() for n in names)} weights, {os.path.getsize(args.out)} bytes")

    if args.vectors:
        d = np.load(args.vectors[0])
        o = d["offsets"]
        # Weights as the app will see them: rounded to float16.
        with torch.no_grad():
            for n in names:
                sd[n].copy_(sd[n].half().float())
        model.load_state_dict(sd)
        with open(args.vectors[1], "w") as fh, torch.no_grad():
            for i in (0, 1, 7, 42, 123):
                p = d["points"][o[i]:o[i + 1]]
                logp = model(torch.from_numpy(p)[None], torch.tensor([len(p)]))[0].numpy()
                fh.write(f"stroke {len(p)}\n")
                for row in p:
                    fh.write(" ".join(f"{x:.6f}" for x in row) + "\n")
                for row in logp:
                    fh.write(" ".join(f"{x:.6f}" for x in row) + "\n")
        print(f"wrote {args.vectors[1]}")


if __name__ == "__main__":
    main()
