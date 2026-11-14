#!/usr/bin/env python3
"""Iteration 5: finalize the score->advice mapping.
The core model is fixed (prod in iter4). Here we only choose the bands, using
(a) how much better the ADD band is than the market, (b) how much WORSE the
REDUCE band is, (c) whether scores >=75 are even reachable."""
import numpy as np
from model import prod, rows_for, DATES, CUTOFF, BASELINE

H = 20
# ---- collect every valid (score, date, forward net return, excess) once
rows = rows_for(prod)
S = np.array([x[0] for x in rows])
D = np.array([x[1] for x in rows])
R = np.array([x[2] for x in rows])
E = np.array([x[3] for x in rows])
UNBUY = np.array([x[4] for x in rows])
IS_OOS = D >= CUTOFF
print(f"n={len(S)}  score pct: " + " ".join(
    f"p{p}={np.percentile(S,p):.0f}" for p in (1, 5, 10, 25, 50, 75, 90, 95, 99)))
print(f"  >=75: {(S>=75).mean()*100:.2f}%   >=70: {(S>=70).mean()*100:.2f}%   "
      f">=68: {(S>=68).mean()*100:.2f}%   <=35: {(S<=35).mean()*100:.2f}%")


def stat(mask):
    m = mask & (~UNBUY)
    if m.sum() < 100:
        return None
    r, e = R[m], E[m]
    o = m & IS_OOS
    return dict(n=int(m.sum()), exc=np.nanmean(e), avg=r.mean(), win=(r > 0).mean() * 100,
                p05=np.percentile(r, 5), big=(r < -0.10).mean() * 100,
                oos_n=int(o.sum()), oos_exc=np.nanmean(E[o]) if o.sum() > 100 else np.nan,
                oos_avg=R[o].mean() if o.sum() > 100 else np.nan,
                oos_p05=np.percentile(R[o], 5) if o.sum() > 100 else np.nan)


print("\n=== ADD side: buy threshold sweep ===")
print(f"  {'th':>4s} {'n':>7s} {'exc':>7s} {'avg':>7s} {'win':>6s} {'p05':>7s} {'>10L':>6s} "
      f"{'| oos_n':>8s} {'oos_exc':>8s} {'oos_avg':>8s} {'oos_p05':>8s}")
for th in (60, 62, 64, 66, 68, 70, 72, 75, 80):
    a = stat(S >= th)
    if a:
        print(f"  {th:>4d} {a['n']:>7d} {a['exc']*100:>+6.2f}% {a['avg']*100:>+6.2f}% "
              f"{a['win']:>5.1f}% {a['p05']*100:>+6.1f}% {a['big']:>5.1f}% | "
              f"{a['oos_n']:>8d} {a['oos_exc']*100:>+7.2f}% {a['oos_avg']*100:>+7.2f}% {a['oos_p05']*100:>+7.1f}%")

print("\n=== REDUCE side: sell threshold sweep (want avg/exc strongly NEGATIVE) ===")
print(f"  {'th':>4s} {'n':>7s} {'exc':>7s} {'avg':>7s} {'| oos_exc':>9s} {'oos_avg':>8s}")
for th in (20, 25, 30, 35, 40, 42, 45, 50, 55):
    a = stat(S <= th)
    if a:
        print(f"  {th:>4d} {a['n']:>7d} {a['exc']*100:>+6.2f}% {a['avg']*100:>+6.2f}% | "
              f"{a['oos_exc']*100:>+8.2f}% {a['oos_avg']*100:>+7.2f}%")

print("\n=== spread test: ADD band minus REDUCE band (skill, not beta) ===")
for hi, lo in ((68, 42), (70, 40), (70, 35), (72, 35), (75, 30)):
    a = stat(S >= hi)
    b = stat(S <= lo)
    if a and b:
        print(f"  buy>={hi} vs sell<={lo}: full spread={(a['exc']-b['exc'])*100:+.2f}% "
              f"oos spread={(a['oos_exc']-b['oos_exc'])*100:+.2f}%")
