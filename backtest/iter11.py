#!/usr/bin/env python3
"""Iteration 11: momentum ONE-SIDED.

iter10: a symmetric momentum bonus fixes the >=74 trap (+1.29%) but wrecks the
tail (p05 -15.2% -> -18.7%, >10% loss 12.2% -> 15.7%) because the bonus buys
into extended names. The user lost money on tails, not on averages, so test
penalizing only STALE strength (structure says up, but nothing is actually
moving) without rewarding already-run names.
"""
import numpy as np
from collections import defaultdict
from model import prod, rows_for, CUTOFF, _nan, g


def make(th, pen):
    def fn(F, t):
        s, ok = prod(F, t)
        if not ok:
            return s, ok
        r = g(F, "ret20", t)
        if not _nan(r) and r < th:
            s -= pen
        return float(np.clip(s, 0, 100)), True
    return fn


def evaluate(fn, label, base=None):
    rows = rows_for(fn)
    by_date = defaultdict(list)
    for s, d, r, e, unb in rows:
        by_date[d].append((s, r, e, unb))
    alldates = sorted(by_date)
    h1 = set(alldates[:len(alldates) // 2])

    def dexc(lo=None, hi=None):
        per, a, b, oos = [], [], [], []
        for d, lst in by_date.items():
            sel = [x[2] for x in lst if not x[3] and
                   (lo is None or x[0] >= lo) and (hi is None or x[0] < hi)]
            if len(sel) < 3:
                continue
            m = float(np.nanmean(sel))
            if np.isfinite(m):
                per.append(m)
                (a if d in h1 else b).append(m)
                if d >= CUTOFF:
                    oos.append(m)
        if len(per) < 20:
            return None
        return np.mean(per), np.mean(a), np.mean(b), (np.mean(oos) if oos else np.nan)

    tot = sum(len(l) for l in by_date.values())
    nb = sum(1 for l in by_date.values() for x in l if x[0] >= 70)
    buys = [(d, x[1]) for d, lst in by_date.items() for x in lst if x[0] >= 70 and not x[3]]
    v = np.array([r for _, r in buys]) if len(buys) > 500 else np.array([np.nan])
    vo = np.array([r for d, r in buys if d >= CUTOFF])
    if vo.size < 200:
        vo = np.array([np.nan])
    r70, r74, rs = dexc(70), dexc(74), dexc(None, 42)
    if not r70:
        print(f"{label:22s} too few")
        return
    print(f"{label:22s} >=70 {r70[0]*100:+5.2f}%(h1 {r70[1]*100:+5.2f} h2 {r70[2]*100:+5.2f} "
          f"oos {r70[3]*100:+5.2f}) | >=74 {('%+.2f%%'%(r74[0]*100)) if r74 else '   n/a '} "
          f"| 减仓<=42 {rs[0]*100:+5.2f}% | p05={np.percentile(v,5)*100:+5.1f}% "
          f">10L={(v<-0.10).mean()*100:4.1f}% | oos p05={np.percentile(vo,5)*100:+5.1f}% "
          f">10L={(vo<-0.10).mean()*100:4.1f}% | buy {nb/tot*100:4.1f}%d")


evaluate(prod, "prod")
for th in (0.08, 0.12, 0.16):
    for pen in (6, 10, 16):
        evaluate(make(th, pen), f"stale<{int(th*100)}% -{pen}")
