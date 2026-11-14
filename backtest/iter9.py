#!/usr/bin/env python3
"""Iteration 9: what makes the score>=74 cluster lose? Find the observable
condition, so the fix is a real rule (not a cap that breaks the scale)."""
import numpy as np
from collections import defaultdict
from model import prod, rows_for, CUTOFF, BASELINE, stocks, MIN_HIST, COST, g, _nan, H

# score + features + forward excess for every signal
recs = []
for F in stocks:
    n = int(F["n"])
    c, o = F["close"], F["open"]
    lim = 0.097 if F["code"].startswith(("30", "68")) else 0.047
    for t in range(MIN_HIST, n - 1):
        s, ok = prod(F, t)
        if not ok:
            continue
        k = t + 1 + H
        if k >= n:
            continue
        eo = o[t + 1]
        if eo <= 0 or (F["pct"][t] > 0 and eo >= c[t] * (1 + lim) * 0.995):
            continue
        d = str(F["date"][t])
        b = BASELINE.get((d, H))
        if b is None:
            continue
        recs.append(dict(s=s, d=d, ex=(c[k] / eo - 1.0) - COST - b,
                         ret20=g(F, "ret20", t), ret60=g(F, "ret60", t),
                         atr=g(F, "atr_pct", t), adx=g(F, "adx", t),
                         pos55=g(F, "pos55", t), sl60=g(F, "ma60_slope", t),
                         sl20=g(F, "ma20_slope", t), obv=g(F, "obv_slope", t),
                         w_pct=g(F, "boll_w_pct", t), rsi=g(F, "rsi", t)))

strong = [r for r in recs if r["s"] >= 70]
print(f"score>=70 population: {len(strong)}")


def bucket(key, edges, pop):
    print(f"\n  by {key}:")
    for i in range(len(edges) - 1):
        lo, hi = edges[i], edges[i + 1]
        sel = [r for r in pop if r[key] is not None and not _nan(r[key]) and lo <= r[key] < hi]
        if len(sel) < 200:
            continue
        per, a, b = [], [], []
        bydate = defaultdict(list)
        for r in sel:
            bydate[r["d"]].append(r["ex"])
        alldates = sorted(bydate)
        h1 = set(alldates[:len(alldates) // 2])
        for d, v in bydate.items():
            if len(v) < 2:
                continue
            m = float(np.mean(v))
            per.append(m)
            (a if d in h1 else b).append(m)
        if len(per) < 20:
            continue
        p = np.array(per)
        print(f"    [{lo:>7.3f},{hi:>7.3f}) n={len(sel):>6d} dates={len(p):>4d} "
              f"exc={p.mean()*100:+6.2f}% h1={np.mean(a)*100:+6.2f}% h2={np.mean(b)*100:+6.2f}% "
              f"pos%={(p>0).mean()*100:3.0f}%")


for k, e in (("ret20", [-1, 0.0, 0.06, 0.12, 0.20, 0.30, 1e9]),
             ("ret60", [-1, 0.0, 0.15, 0.30, 0.50, 1e9]),
             ("atr", [0, 0.010, 0.015, 0.020, 0.030, 1e9]),
             ("adx", [0, 15, 22, 30, 40, 100]),
             ("pos55", [0, 0.5, 0.75, 0.9, 1.01]),
             ("sl20", [-1, 0.0, 0.02, 0.04, 0.06, 1e9]),
             ("sl60", [-1, 0.0, 0.03, 0.06, 0.10, 1e9]),
             ("rsi", [0, 55, 62, 70, 100])):
    bucket(k, e, strong)

print("\n=== same buckets on the WHOLE population (is the effect specific to strong names?) ===")
for k, e in (("ret20", [-1, -0.10, -0.04, 0.0, 0.06, 0.12, 0.20, 0.30, 1e9]),
             ("adx", [0, 15, 22, 30, 40, 100])):
    bucket(k, e, recs)
