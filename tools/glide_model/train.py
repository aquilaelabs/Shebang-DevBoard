#!/usr/bin/env python3
"""Train the glide model on the FUTO swipe dataset's training split (arrays from prep.py).

Usage: train.py TRAIN.npz DEV.npz OUT.pt [--epochs N] [--batch N] [--hidden N] [--conv N] [--layers N]
                [--lr F] [--limit N]

Run it with a turn on the GPU: bb gpu run -- python-ml tools/glide_model/train.py ...
Each epoch prints the dev split's CTC loss and how many dev strokes read as exactly their word's keys with
no dictionary at all (the best key at each point, repeats and blanks removed), a check that it is learning.
"""
import argparse
import time

import numpy as np
import torch

from glidenet import GlideNet, cosine, count_parameters, ctc_nll


class Swipes:
    def __init__(self, path, limit=0):
        d = np.load(path)
        self.points = d["points"]
        self.offsets = d["offsets"]
        self.keys = d["keys"]
        self.key_offsets = d["key_offsets"]
        self.n = len(self.offsets) - 1 if not limit else min(limit, len(self.offsets) - 1)
        self.lengths = np.diff(self.offsets)[: self.n]

    def batch(self, ids, device, augment, rng):
        T = int(self.lengths[ids].max())
        L = int(max(self.key_offsets[i + 1] - self.key_offsets[i] for i in ids))
        pts = np.zeros((len(ids), T, 3), dtype=np.float32)
        keys = np.zeros((len(ids), L), dtype=np.int64)
        klen = np.zeros(len(ids), dtype=np.int64)
        for b, i in enumerate(ids):
            p = self.points[self.offsets[i]:self.offsets[i + 1]]
            if augment:
                # Another hand, another screen: the stroke a little elsewhere, larger or smaller, faster or slower.
                p = p.copy()
                scale = 1.0 + rng.normal(0, 0.03)
                p[:, 0] = (p[:, 0] - 5.0) * scale + 5.0 + rng.normal(0, 0.08)
                p[:, 1] = (p[:, 1] - 1.5) * scale + 1.5 + rng.normal(0, 0.08)
                p[:, 2] *= np.exp(rng.normal(0, 0.15))
            pts[b, : len(p)] = p
            k = self.keys[self.key_offsets[i]:self.key_offsets[i + 1]]
            keys[b, : len(k)] = k
            klen[b] = len(k)
        return (torch.from_numpy(pts).to(device), torch.from_numpy(self.lengths[ids].astype(np.int64)).to(device),
                torch.from_numpy(keys).to(device), torch.from_numpy(klen).to(device))

    def batches(self, size, shuffle, rng):
        """Batches of swipes of similar length (less padding), in random order when [shuffle]."""
        order = np.argsort(self.lengths, kind="stable")
        if shuffle:
            # Shuffle within windows of similar length, then shuffle the batches.
            w = size * 50
            order = np.concatenate([rng.permutation(order[i:i + w]) for i in range(0, len(order), w)])
        out = [order[i:i + size] for i in range(0, len(order), size)]
        if shuffle:
            out = [out[i] for i in rng.permutation(len(out))]
        return out


def evaluate(model, data, device, batch):
    model.eval()
    loss = 0.0
    exact = 0
    rng = np.random.default_rng(0)
    with torch.no_grad():
        for ids in data.batches(batch, False, rng):
            pts, lengths, keys, klen = data.batch(ids, device, False, rng)
            logp = model(pts, lengths)
            loss += ctc_nll(logp, lengths, keys, klen).sum().item()
            best = logp.argmax(-1).cpu().numpy()
            for b in range(len(ids)):
                seq = best[b, : int(lengths[b])]
                seq = seq[np.concatenate([[True], seq[1:] != seq[:-1]])]
                seq = seq[seq != 0] - 1
                want = keys[b, : int(klen[b])].cpu().numpy()
                if len(seq) == len(want) and (seq == want).all():
                    exact += 1
    return loss / data.n, exact / data.n


def main():
    ap = argparse.ArgumentParser(usage=__doc__)
    ap.add_argument("train")
    ap.add_argument("dev")
    ap.add_argument("out")
    ap.add_argument("--epochs", type=int, default=12)
    ap.add_argument("--batch", type=int, default=256)
    ap.add_argument("--hidden", type=int, default=128)
    ap.add_argument("--conv", type=int, default=96)
    ap.add_argument("--layers", type=int, default=2)
    ap.add_argument("--lr", type=float, default=2e-3)
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--dev-limit", type=int, default=20000)
    args = ap.parse_args()

    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    torch.manual_seed(1)
    rng = np.random.default_rng(1)
    train = Swipes(args.train, args.limit)
    dev = Swipes(args.dev, args.dev_limit)
    model = GlideNet(args.conv, args.hidden, args.layers).to(device)
    print(f"device {device}; {train.n} training swipes, {dev.n} dev; {count_parameters(model)} parameters", flush=True)
    opt = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=1e-4)
    steps = args.epochs * ((train.n + args.batch - 1) // args.batch)
    sched = torch.optim.lr_scheduler.LambdaLR(opt, lambda s: cosine(s, steps))
    step = 0
    for epoch in range(args.epochs):
        model.train()
        t0 = time.time()
        total = 0.0
        seen = 0
        for ids in train.batches(args.batch, True, rng):
            pts, lengths, keys, klen = train.batch(ids, device, True, rng)
            logp = model(pts, lengths)
            loss = ctc_nll(logp, lengths, keys, klen).mean()
            opt.zero_grad(set_to_none=True)
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), 5.0)
            opt.step()
            sched.step()
            step += 1
            total += loss.item() * len(ids)
            seen += len(ids)
        dev_loss, dev_exact = evaluate(model, dev, device, 512)
        print(f"epoch {epoch + 1}/{args.epochs}: train loss {total / seen:.4f}, dev loss {dev_loss:.4f}, dev keys exact {100 * dev_exact:.2f}%, "
              f"{time.time() - t0:.0f} s", flush=True)
        torch.save({"state": model.state_dict(), "config": model.config}, args.out)
    print(f"saved {args.out}")


if __name__ == "__main__":
    main()
