#!/usr/bin/env python3
"""Iteration 6: robustness check on the BUY threshold.

iter5 made 70 look like a peak, but the structural score is DISCRETE
(50 +/- {6,14,22} +/- {2,4} - ATR penalty), so signals pile up on a handful of
values and the sample clusters on a handful of DATES. Effective N is much
smaller than raw n. Here every signal date gets equal weight and results are
split in half, so we only ship a threshold that is stable, not lucky.
"""
import numpy as np
from collections import defaultdict
from model import prod, rows_for, CUTOFF

rows = rows_for(prod)
by_date = defaultdict(list)
for s, d, r, e, unb in rows:
    by_date[d].append((s, r, e, unb))

print("=== score histogram (top values) ===")
hist = defaultdict(int)
for s, d, r, e, unb in rows:
    hist[round(s)] += 1
for v, c in sorted(hist.items(), key=lambda kv: -kv[1])[:12]:
    print(f"  score~{v:3d}: {c:6d} ({c/len(rows)*100:4.1f}%)")

print("\n=== threshold sweep, DATE-EQUAL-WEIGHTED excess ===")
print(f"  {'th':>4s} {'dates':>6s} {'exc(d)':>8s} {'exc(raw)':>8s} "
      f"{'h1exc':>7s} {'h2exc':>7s} {'h1d':>6s} {'h2d':>6s}")
dates = sorted(by_date)
mid = len(dates) // 2
h1set, h2set = set(dates[:mid]), set(dates[mid:])
for th in (60, 64, 66, 68, 70, 72, 74):
    for unb_only in (True,):
        per, raw = [], []
        h1, h2, hd1, hd2 = [], [], set(), set()
        for d, lst in by_date.items():
            sel = [x for x in lst if x[0] >= th and not x[3]]
            if len(sel) < 3:
                continue
            m = float(np.nanmean([x[2] for x in sel]))
            if not np.isfinite(m):
                continue
            raw.extend([x[2] for x in sel])
            per.append(m)
            if d in h1set:
                h1.append(m); hd1.add(d)
            else:
                h2.append(m); hd2.add(d)
        if len(per) < 30:
            continue
        print(f"  {th:>4d} {len(per):>6d} {np.mean(per)*100:>+7.2f}% "
              f"{np.mean(raw)*100:>+7.2f}% {np.mean(h1)*100:>+6.2f}% "
              f"{np.mean(h2)*100:>+6.2f}% {len(hd1):>6d} {len(hd2):>6d}")

print("\n=== same for the REDUCE side (want date-equal NEGATIVE) ===")
print(f"  {'th':>4s} {'dates':>6s} {'exc(d)':>8s} {'h1exc':>7s} {'h2exc':>7s}")
for th in (30, 35, 40, 42, 45, 50):
    per, h1, h2 = [], [], []
    for d, lst in by_date.items():
        sel = [x[2] for x in lst if x[0] <= th]
        if len(sel) < 3:
            continue
        m = float(np.nanmean(sel))
        if not np.isfinite(m):
            continue
        per.append(m)
        (h1 if d in h1set else h2).append(m)
    if len(per) < 30:
        continue
    print(f"  {th:>4d} {len(per):>6d} {np.mean(per)*100:>+7.2f}% "
          f"{np.mean(h1)*100:>+6.2f}% {np.mean(h2)*100:>+6.2f}%")

print("\n=== per-date excess: BUY(>=68) vs REDUCE(<=42), distribution over dates ===")
for lab, mask in (("BUY>=68", lambda s: s >= 68), ("BUY>=70", lambda s: s >= 70),
                  ("RED<=42", lambda s: s <= 42)):
    per = []
    for d, lst in by_date.items():
        sel = [x[2] for x in lst if mask(x[0]) and not x[3]]
        if len(sel) >= 5:
            per.append(np.nanmean(sel))
    per = np.array(per)
    print(f"  {lab:9s} dates={len(per):4d} mean={per.mean()*100:+.2f}% "
          f"median={np.median(per)*100:+.2f}% share_positive={(per>0).mean()*100:4.1f}% "
          f"iqr=[{np.percentile(per,25)*100:+.2f}%,{np.percentile(per,75)*100:+.2f}%]")

print(f"\nOOS cutoff date = {CUTOFF}, total dates={len(dates)}")
