#!/usr/bin/env python3
"""Iteration 8: is the >=74 trap real, or a few unlucky dates? Decide, then freeze."""
import numpy as np
from collections import defaultdict
from model import prod, rows_for, CUTOFF, BASELINE, stocks, MIN_HIST, COST

rows = rows_for(prod)
by_date = defaultdict(list)
for s, d, r, e, unb in rows:
    by_date[d].append((s, r, e, unb))
dates = sorted(by_date)
h1set = set(dates[:len(dates) // 2])

print("=== per-band diagnostics, 20d, date-equal excess ===")
print(f"{'band':>10s} {'n':>7s} {'dates':>6s} {'d_exc':>8s} {'h1':>7s} {'h2':>7s} "
      f"{'pos%':>5s} {'oos_d':>6s} {'oos':>7s}")
for lo, hi in ((0, 25), (25, 35), (35, 45), (45, 55), (55, 65), (65, 70), (70, 74), (74, 101)):
    per, oos, n = [], [], 0
    for d, lst in by_date.items():
        sel = [x[2] for x in lst if lo <= x[0] < hi and not x[3]]
        if len(sel) < 3:
            continue
        m = float(np.nanmean(sel))
        if not np.isfinite(m):
            continue
        n += len(sel)
        per.append(m)
        if d >= CUTOFF:
            oos.append(m)
    if len(per) < 10:
        print(f"{lo:>4d}-{hi:<5d} (too few dates)")
        continue
    p = np.array(per)
    # split halves cleanly
    a, b = [], []
    for d, lst in by_date.items():
        sel = [x[2] for x in lst if lo <= x[0] < hi and not x[3]]
        if len(sel) < 3:
            continue
        m = float(np.nanmean(sel))
        if not np.isfinite(m):
            continue
        (a if d in h1set else b).append(m)
    print(f"{lo:>4d}-{hi:<5d} {n:>7d} {len(p):>6d} {p.mean()*100:>+7.2f}% "
          f"{np.mean(a)*100:>+6.2f}% {np.mean(b)*100:>+6.2f}% {(p>0).mean()*100:>4.0f}% "
          f"{len(oos):>6d} {np.mean(oos)*100:>+6.2f}%" if oos else
          f"{lo:>4d}-{hi:<5d} {n:>7d} {len(p):>6d} {p.mean()*100:>+7.2f}%")

print("\n=== the two candidate BUY thresholds across horizons (date-equal excess) ===")
for horizon in (5, 10, 20):
    acc = defaultdict(list)
    for F in stocks:
        n = int(F["n"])
        c, o = F["close"], F["open"]
        lim = 0.097 if F["code"].startswith(("30", "68")) else 0.047
        for t in range(MIN_HIST, n - 1):
            s, ok = prod(F, t)
            if not ok:
                continue
            k = t + 1 + horizon
            if k >= n:
                continue
            eo = o[t + 1]
            if eo <= 0:
                continue
            if F["pct"][t] > 0 and eo >= c[t] * (1 + lim) * 0.995:
                continue
            d = str(F["date"][t])
            b = BASELINE.get((d, horizon))
            if b is None:
                continue
            acc[d].append((s, (c[k] / eo - 1.0) - COST - b))
    line = f"  H={horizon:2d}: "
    for lab, mask in (("加仓>=70", lambda s: s >= 70), ("加仓>=75", lambda s: s >= 75),
                      ("减仓<=42", lambda s: s <= 42), ("减仓<=35", lambda s: s <= 35)):
        per = [np.mean([e for s, e in lst if mask(s)]) for d, lst in acc.items()
               if len([1 for s, e in lst if mask(s)]) >= 3]
        line += f"{lab}={np.mean(per)*100:+.2f}%({len(per)}d)  " if per else f"{lab}=n/a  "
    print(line)
