#!/usr/bin/env python3
"""Iteration 15: the top score band underperforms in EVERY config tried.
Hypothesis: they are the tame, crowded, low-volatility uptrends. Split the
buy-eligible population by ATR% and check both halves."""
import numpy as np
from collections import defaultdict
from model import CUTOFF, _nan, g, stocks, MIN_HIST, COST, H, BASELINE


def make(pen, knee=0.020):
    def fn(F, t):
        c = F["close"][t]
        ma10, ma20, ma60, sl = g(F, "ma10", t), g(F, "ma20", t), g(F, "ma60", t), g(F, "ma20_slope", t)
        if _nan(ma20) or _nan(ma60) or _nan(sl) or _nan(ma10):
            return 0.0, False
        s = 50.0
        up = c > ma20 > ma60
        dn = c < ma20 < ma60
        if up:
            s += 22 if sl > 0.015 else (14 if sl > 0.0 else 6)
        elif dn:
            s -= 22 if sl < -0.015 else (14 if sl < 0.0 else 6)
        else:
            s -= 3
        s += 4 if c > ma10 > ma20 else -2
        a = g(F, "atr_pct", t)
        if not _nan(a):
            s -= max(0.0, a - knee) * pen
        return float(np.clip(s, 0, 100)), True
    return fn


def build(fn):
    rows = []
    for F in stocks:
        n = int(F["n"])
        c, o = F["close"], F["open"]
        lim = 0.097 if F["code"].startswith(("30", "68")) else 0.047
        for t in range(MIN_HIST, n - 1):
            s, ok = fn(F, t)
            if not ok:
                continue
            k = t + 1 + H
            eo = o[t + 1]
            if k >= n or eo <= 0:
                continue
            d = str(F["date"][t])
            b = BASELINE.get((d, H))
            if b is None:
                continue
            raw = (c[k] / eo - 1.0) - COST
            rows.append((s, d, raw, raw - b,
                         F["pct"][t] > 0 and eo >= c[t] * (1 + lim) * 0.995,
                         float(g(F, "atr_pct", t))))
    return rows


for pen, buy in ((150.0, 70.0), (200.0, 68.0)):
    rows = build(make(pen))
    dates = sorted({x[1] for x in rows})
    h1 = set(dates[:len(dates) // 2])
    print(f"\n=== pen={pen:.0f}, buy>={buy:.0f}: 20d outcome by ATR%/day bucket ===")
    print(f"  {'atr bucket':>16s} {'n':>6s} {'dates':>6s} {'exc':>7s} {'h1':>7s} {'h2':>7s} "
          f"{'p05':>7s} {'>10L':>6s}")
    for lo, hi in ((0.0, 0.0125), (0.0125, 0.0175), (0.0175, 0.0225), (0.0225, 0.0275),
                   (0.0275, 0.035), (0.035, 9.9)):
        bd = defaultdict(list)
        for s, d, raw, exc, unb, a in rows:
            if s >= buy and not unb and lo <= a < hi:
                bd[d].append((raw, exc))
        per = [np.mean([x[1] for x in v]) for v in bd.values() if len(v) >= 3]
        tot = sum(len(v) for v in bd.values())
        if len(per) < 20:
            print(f"  {lo:.4f}-{hi:.4f}  样本不足 n={tot}")
            continue
        a1 = [np.mean([x[1] for x in v]) for d, v in bd.items() if len(v) >= 3 and d in h1]
        a2 = [np.mean([x[1] for x in v]) for d, v in bd.items() if len(v) >= 3 and d not in h1]
        allraw = np.array([x[0] for v in bd.values() for x in v])
        print(f"  {lo:.4f}-{hi:.4f} {len(allraw):>6d} {len(per):>6d} {np.mean(per)*100:>+6.2f}% "
              f"{np.mean(a1)*100:>+6.2f}% {np.mean(a2)*100:>+6.2f}% "
              f"{np.percentile(allraw,5)*100:>+6.1f}% {(allraw<-0.10).mean()*100:>5.1f}%")
