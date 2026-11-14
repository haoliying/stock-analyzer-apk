#!/usr/bin/env python3
"""Iteration 17 — the 每日推荐 list is the feature the user actually follows, and
final_ship just showed the top-10 BY SCORE has NEGATIVE excess (-1.5%) even
though the whole >=68 book is positive (+0.7%). Ranking by raw score therefore
concentrates exactly the crowded trap names.

Test alternative selection rules for a 10-name daily list. Same protocol:
close(t) signal, open(t+1) entry, 20 sessions, net cost, excess vs same-day
universe mean, date-equal, out-of-sample split at 2026-04-23.
"""
import numpy as np
from collections import defaultdict
from model import CUTOFF, _nan, g, stocks, MIN_HIST, COST, H, BASELINE
from final_ship import ship

day = defaultdict(list)
for F in stocks:
    n = int(F["n"])
    c, o = F["close"], F["open"]
    lim = 0.097 if F["code"].startswith(("30", "68")) else 0.047
    for t in range(MIN_HIST, n - 1):
        s, ok = ship(F, t)
        if not ok:
            continue
        k = t + 1 + H
        eo = o[t + 1]
        if k >= n or eo <= 0:
            continue
        if F["pct"][t] > 0 and eo >= c[t] * (1 + lim) * 0.995:
            continue
        d = str(F["date"][t])
        b = BASELINE.get((d, H))
        if b is None:
            continue
        day[d].append(dict(s=s, ex=(c[k] / eo - 1.0) - COST - b,
                           a=float(g(F, "atr_pct", t)),
                           sl=float(g(F, "ma20_slope", t)),
                           r20=float(g(F, "ret20", t)),
                           adx=float(g(F, "adx", t)),
                           pos=float(g(F, "pos55", t)),
                           hist=float(g(F, "hist_d", t)),
                           volr=float(g(F, "volr20", t))))

RULES = {
    "R1 score": lambda v: sorted(v, key=lambda x: -x["s"]),
    "R2 score,prefer atr3.2%": lambda v: sorted(
        v, key=lambda x: (-x["s"], abs(x["a"] - 0.032)))[:40],
    "R3 atr in[2.5,4.5]+score": lambda v: sorted(
        [x for x in v if 0.025 <= x["a"] <= 0.045], key=lambda x: -x["s"]),
    "R4 atr in[2.5,4.5] & r20>0": lambda v: sorted(
        [x for x in v if 0.025 <= x["a"] <= 0.045 and x["r20"] > 0], key=lambda x: -x["s"]),
    "R5 atr in[2.5,4.5] & r20>5%": lambda v: sorted(
        [x for x in v if 0.025 <= x["a"] <= 0.045 and x["r20"] > 0.05], key=lambda x: -x["s"]),
    "R6 atr in[2.5,4.5] & r20>10%": lambda v: sorted(
        [x for x in v if 0.025 <= x["a"] <= 0.045 and x["r20"] > 0.10], key=lambda x: -x["s"]),
    "R7 atr window & slope>1.5%": lambda v: sorted(
        [x for x in v if 0.025 <= x["a"] <= 0.045 and x["sl"] > 0.015], key=lambda x: -x["s"]),
    "R8 atr window & volr20>1": lambda v: sorted(
        [x for x in v if 0.025 <= x["a"] <= 0.045 and x["volr"] > 1.0], key=lambda x: -x["s"]),
    "R9 composite rank": lambda v: sorted(v, key=lambda x: -(
        x["s"] + 10.0 * max(0.0, 1.0 - abs(x["a"] - 0.033) / 0.020)
        + 6.0 * min(max(x["r20"], -0.1), 0.25) * 40.0)),
    "R11 eligible,atr->3.2% only": lambda v: sorted(
        [x for x in v if x["s"] >= 66], key=lambda x: abs(x["a"] - 0.032)),
    "R12 all eligible (reference)": lambda v: sorted(
        [x for x in v if x["s"] >= 66], key=lambda x: -x["s"]),
}
TOPN = 10
print(f"top{TOPN} 每日推荐名单")
print(f"  {'rule':26s} {'dates':>5s} {'n/d':>5s} {'d_exc':>7s} {'h1':>7s} {'h2':>7s} "
      f"{'oos_exc':>8s} {'posM%':>6s} {'worstMo':>8s} {'p05':>7s} {'>10L':>5s}")
dates = sorted(day)
h1 = set(dates[:len(dates) // 2])
for lab, fn in RULES.items():
    per, e1, e2, oos_all, mo = [], [], [], [], defaultdict(list)
    cnt = []
    for d, v in day.items():
        picks = fn(v)[:TOPN]
        if len(picks) < 3:
            continue
        cnt.append(len(picks))
        m = float(np.mean([x["ex"] for x in picks]))
        per.append(m)
        (e1 if d in h1 else e2).append(m)
        if d >= CUTOFF:
            oos_all.extend([x["ex"] for x in picks])
        mo[d[:7]].append(m)
    if len(per) < 40:
        print(f"  {lab:26s} {len(per):>5d}  too few")
        continue
    p = np.array(per)
    mp = np.array([np.mean(v) for v in mo.values()])
    O = np.array(oos_all)
    allraw = np.array([x for x in per])
    print(f"  {lab:26s} {len(p):>5d} {np.mean(cnt):>5.1f} {p.mean()*100:>+6.2f}% "
          f"{np.mean(e1)*100:>+6.2f}% {np.mean(e2)*100:>+6.2f}% {O.mean()*100:>+7.2f}% "
          f"{(mp>0).mean()*100:>5.0f}% {mp.min()*100:>+7.2f}% "
          f"{np.percentile(allraw,5)*100:>+6.1f}% ")
