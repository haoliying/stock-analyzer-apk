#!/usr/bin/env python3
"""Iteration 10: add the one factor the data insists on — 20d momentum — and make
the ATR penalty directional instead of global. Judged on date-equal excess at
each band, split-half stability, OOS tail, and how often the app speaks."""
import numpy as np
from collections import defaultdict
from model import prod, rows_for, CUTOFF, _nan, g

CLIP = lambda x, a, b: max(a, min(b, x))


def mom_step(F, t, hi=12, mid=7, flat=0, neg=-5, neg2=-10):
    r = g(F, "ret20", t)
    if _nan(r):
        return 0.0
    if r >= 0.20: return hi
    if r >= 0.12: return mid
    if r >= 0.0:  return flat
    if r >= -0.04: return neg
    return neg2


def mom_lin(F, t, sc=50.0):
    r = g(F, "ret20", t)
    return 0.0 if _nan(r) else CLIP(r, -0.15, 0.35) * sc


def make(extra=None, atr_directional=False):
    def fn(F, t):
        s, ok = prod(F, t)
        if not ok:
            return s, ok
        if atr_directional:
            # undo prod's global penalty, re-apply only outside confirmed uptrend
            a = g(F, "atr_pct", t)
            c, ma20, ma60 = F["close"][t], g(F, "ma20", t), g(F, "ma60", t)
            up = (not _nan(ma20)) and (not _nan(ma60)) and c > ma20 > ma60
            s += 0.0 if _nan(a) else max(0.0, a - 0.020) * 150.0
            if not up and not _nan(a):
                s -= max(0.0, a - 0.020) * 150.0
        if extra is not None:
            s += extra(F, t)
        return float(np.clip(s, 0, 100)), True
    return fn


def evaluate(fn, label):
    rows = rows_for(fn)
    by_date = defaultdict(list)
    for s, d, r, e, unb in rows:
        by_date[d].append((s, r, e, unb))
    alldates = sorted(by_date)
    h1 = set(alldates[:len(alldates) // 2])

    def dexc(lo=None, hi=None, side_buy=True):
        per, a, b, oos, n = [], [], [], [], 0
        for d, lst in by_date.items():
            sel = [x[2] for x in lst if not x[3] and
                   (lo is None or x[0] >= lo) and (hi is None or x[0] < hi)]
            if len(sel) < 3:
                continue
            m = float(np.nanmean(sel))
            if not np.isfinite(m):
                continue
            n += len(sel)
            per.append(m)
            (a if d in h1 else b).append(m)
            if d >= CUTOFF:
                oos.append(m)
        if len(per) < 20:
            return None
        return (float(np.mean(per)), float(np.mean(a)), float(np.mean(b)),
                float(np.mean(oos)) if oos else np.nan, len(per), n)

    tot = sum(len(l) for l in by_date.values())
    nb = sum(1 for l in by_date.values() for x in l if x[0] >= 70)
    ns = sum(1 for l in by_date.values() for x in l if x[0] <= 42)
    # tail of the buy book
    buys = [x[1] for l in by_date.values() for x in l if x[0] >= 70 and not x[3]]
    tail = ""
    if len(buys) > 500:
        v = np.array(buys)
        tail = f" p05={np.percentile(v,5)*100:+5.1f}% >10L={(v<-0.10).mean()*100:4.1f}%"
    r70 = dexc(70)
    r74 = dexc(74)
    rband = dexc(70, 74)
    rsell = dexc(None, 42, False)
    if not r70:
        print(f"{label:26s} too few")
        return
    print(f"{label:26s} buy>=70 d_exc={r70[0]*100:+5.2f}% (h1 {r70[1]*100:+5.2f} h2 {r70[2]*100:+5.2f} "
          f"oos {r70[3]*100:+5.2f}) | 70-73 d_exc={(rband[0]*100 if rband else float('nan')):+5.2f}%")
    print(f"{'':26s} >=74 d_exc={(r74[0]*100 if r74 else float('nan')):+5.2f}% "
          f"| 减仓<=42 d_exc={rsell[0]*100:+5.2f}% (h1 {rsell[1]*100:+5.2f} h2 {rsell[2]*100:+5.2f}) "
          f"| speaks {nb/tot*100:.1f}% buy / {ns/tot*100:.1f}% sell{tail}")


evaluate(prod, "prod (baseline)")
evaluate(make(extra=mom_step), "+mom20 step")
evaluate(make(extra=lambda F, t: mom_lin(F, t, 40.0)), "+mom20 lin x40")
evaluate(make(extra=lambda F, t: mom_lin(F, t, 70.0)), "+mom20 lin x70")
evaluate(make(atr_directional=True), "ATR pen only if !up")
evaluate(make(extra=mom_step, atr_directional=True), "mom20 step + dirATR")
