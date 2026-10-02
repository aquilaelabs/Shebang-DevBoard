"""The glide model: a small network that reads a resampled stroke and says, point by point, which key the
finger meant (or none), trained with CTC against the keys of the word glided. A candidate word's score is
the CTC likelihood of its keys given the stroke. Shared by train.py and score.py.
"""
import math

import torch
import torch.nn as nn
import torch.nn.functional as F

SPACING = 0.25

# Key centres in key pitches: ten keys across, three rows, as the keyboard's text layout and FUTO's QWERTY.
ROWS = ["qwertyuiop", "asdfghjkl", "zxcvbnm"]
ROW_START = [0.5, 1.0, 2.0]
KEY_U = [0.0] * 26
KEY_V = [0.0] * 26
for _r, _row in enumerate(ROWS):
    for _i, _c in enumerate(_row):
        KEY_U[ord(_c) - 97] = ROW_START[_r] + _i
        KEY_V[ord(_c) - 97] = _r + 0.5

FEATURES = 31
BLANK = 0  # CTC blank; key c is class c + 1


def features(pts, lengths):
    """[B, T, 3] padded points (u, v, ms) and [B] lengths -> [B, T, FEATURES]; zero beyond each length."""
    u, v, t = pts[..., 0], pts[..., 1], pts[..., 2]
    B, T = u.shape
    idx = torch.arange(T, device=pts.device)[None, :]
    mask = idx < lengths[:, None]
    last = (lengths - 1).clamp(min=0)[:, None]
    nxt = torch.minimum(idx + 1, last).expand(B, T)
    prv = (idx - 1).clamp(min=0).expand(B, T)
    # Direction of travel through the point, in steps of SPACING.
    du = (u.gather(1, nxt) - u.gather(1, prv)) / (2 * SPACING)
    dv = (v.gather(1, nxt) - v.gather(1, prv)) / (2 * SPACING)
    # How long the finger took around the point (ms per step): slow where a letter is meant.
    span = (nxt - prv).clamp(min=1).to(t.dtype)
    dwell = torch.log1p(((t.gather(1, nxt) - t.gather(1, prv)) / span).clamp(min=0)) / 4.0
    ku = torch.tensor(KEY_U, device=pts.device, dtype=pts.dtype)
    kv = torch.tensor(KEY_V, device=pts.device, dtype=pts.dtype)
    d2 = (u[..., None] - ku) ** 2 + (v[..., None] - kv) ** 2
    near = torch.exp(-d2 / (2 * 0.6 * 0.6))
    f = torch.cat([(u / 5 - 1)[..., None], (v / 1.5 - 1)[..., None], du[..., None], dv[..., None], dwell[..., None], near], dim=-1)
    return f * mask[..., None]


class GlideNet(nn.Module):
    def __init__(self, conv=96, hidden=128, layers=2, dropout=0.1):
        super().__init__()
        self.config = dict(conv=conv, hidden=hidden, layers=layers)
        self.conv = nn.Conv1d(FEATURES, conv, 5, padding=2)
        self.gru = nn.GRU(conv, hidden, layers, batch_first=True, bidirectional=True, dropout=dropout if layers > 1 else 0.0)
        self.out = nn.Linear(2 * hidden, 27)

    def forward(self, pts, lengths):
        """-> log-probabilities [B, T, 27] over (blank, a..z) at each point."""
        x = features(pts, lengths)
        x = F.relu(self.conv(x.transpose(1, 2))).transpose(1, 2)
        packed = nn.utils.rnn.pack_padded_sequence(x, lengths.cpu(), batch_first=True, enforce_sorted=False)
        y, _ = self.gru(packed)
        y, _ = nn.utils.rnn.pad_packed_sequence(y, batch_first=True, total_length=pts.shape[1])
        return F.log_softmax(self.out(y), dim=-1)


def ctc_nll(logp, lengths, keys, key_lengths):
    """Per-item CTC negative log-likelihood; keys are 0..25, padded [B, L]."""
    return F.ctc_loss(logp.transpose(0, 1).float(), keys + 1, lengths, key_lengths, blank=BLANK, reduction="none", zero_infinity=True)


def count_parameters(model):
    return sum(p.numel() for p in model.parameters())


def cosine(step, total, warmup=500):
    if step < warmup:
        return step / warmup
    return 0.5 * (1 + math.cos(math.pi * (step - warmup) / max(1, total - warmup)))
