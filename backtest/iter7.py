#!/usr/bin/env python3
"""Iteration 7: fix the top-band collapse.

iter6: scores >=74 have NEGATIVE date-equal excess (-1.2%) while 70-73 is the
best zone. Those are stocks in a clean low-vol uptrend that have already run
far above MA20 — chasing them is the classic retail loss. Test an
over-extension (bias) penalty so the scale becomes monotone and the top band
stops being a trap.
"""
import numpy as np
from collections import defaultdict
from model import prod, rows_for, CUTOFF, BASELINE, _nan, g, MIN_HIST, COST, H, stocks


def make(knee, mult, use_ma60=False):
    def fn(F, t):
        s, ok = prod(F, t)
        if not ok:
            return s, ok
        c = F["close"][t]
        bias = c / g(F, "ma20", t) - 1.0
        if use_ma60:
            bias = max(bias, c / g(F, "ma60", t) - 1.0)
        if not _nan(bias):
            s -= max(0.0, bias - knee) * mult
        return float(np.clip(s, 0, 100)), True
    return fn


def date_stats(fn, extra=()):
    rows = rows_for(fn)
    by_date = defaultdict(list)
    for s, d, r, e, unb in rows:
        by_date[d].append((s, r, e, unb))
    out = {}
    for th in (68, 70, 72, 74, 76):
        per, raw = [], []
        for d, lst in by_date.items():
            sel = [x[2] for x in lst if x[0] >= th and not x[3]]
            if len(sel) < 3:
                continue
            per.append(np.nanmean(sel)); raw.extend(sel)
        out[th] = (np.mean(per) if per else np.nan, np.mean(raw) if raw else np.nan,
                   len(per), sum(len([1 for x in l if x[0] >= th]) for l in by_date.values()))
    # band monotonicity (date-equal)
    bands = [(0, 30), (30, 42), (42, 58), (58, 68), (68, 74), (74, 101)]
    bandvals = []
    for lo, hi in bands:
        per = []
        for d, lst in by_date.items():
            sel = [x[2] for x in lst if lo <= x[0] < hi]
            if len(sel) >= 3:
                per.append(np.nanmean(sel))
        bandvals.append(np.mean(per) if per else np.nan)
    return out, bandvals, bands


print(f"{'knee':>5s} {'mult':>5s} {'m60':>3s} | " +
      " ".join(f"{'>='+str(th):>8s}" for th in (68, 70, 72, 74, 76)) + " | bands 0-30/30-42/42-58/58-68/68-74/74+")
base, base_bands, bands = date_stats(prod)
print(f"{'none':>5s} {'0':>5s} {'-':>3s} | " +
      " ".join(f"{base[th][0]*100:>+7.2f}%" for th in (68, 70, 72, 74, 76)) + " | " +
      " ".join(f"{v*100:+5.2f}" for v in base_bands))
for knee in (0.06, 0.08, 0.10, 0.12, 0.15):
    for mult in (40, 80, 140):
        st, bv, _ = date_stats(make(knee, mult))
        print(f"{knee:>5.2f} {mult:>5d} {'-':>3s} | " +
              " ".join(f"{st[th][0]*100:>+7.2f}%" for th in (68, 70, 72, 74, 76)) + " | " +
              " ".join(f"{v*100:+5.2f}" for v in bv))
for knee in (0.08, 0.12):
    for mult in (80, 140):
        st, bv, _ = date_stats(make(knee, mult, use_ma60=True))
        print(f"{knee:>5.2f} {mult:>5d} {'Y':>3s} | " +
              " ".join(f"{st[th][0]*100:>+7.2f}%" for th in (68, 70, 72, 74, 76)) + " | " +
              " ".join(f"{v*100:+5.2f}" for v in bv))
