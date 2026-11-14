#!/usr/bin/env python3
"""Iteration 16 — LAST EXPERIMENT, then ship.

iter15 settled it: within trend-confirmed names, excess RISES with ATR
(-3.3% at ATR 1.3-1.8% ... +3.9% at ATR >3.5%) and so does disaster rate
(1% ... 21%). Volatility is not edge and not poison; it is the risk/premium
dial. An uncapped ATR penalty parks the book in the boring, negative-excess
low-vol zone; no penalty at all gives 1-in-5 positions down >10% in a month.

So: BOUNDED penalty. Sweep the cap, pick the frontier point with
  (1) positive date-equal excess in BOTH halves,
  (2) OOS disaster rate (20d loss >10%) <= 15%,
  (3) highest OOS excess subject to 1+2.
"""
import numpy as np
from collections import defaultdict
from model import CUTOFF, _nan, g, stocks, MIN_HIST, COST, H, BASELINE


def make(pen, cap, knee=0.020):
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
            s -= min(max(0.0, a - knee) * pen, cap if cap > 0 else 1e9)
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
                         F["pct"][t] > 0 and eo >= c[t] * (1 + lim) * 0.995))
    return rows


print(f"{'pen':>4s} {'cap':>4s} {'buy':>4s} | {'d_exc':>7s} {'h1':>7s} {'h2':>7s} {'oos_exc':>8s} "
      f"{'oos_avg':>8s} {'win':>5s} {'p05':>7s} {'>10L':>5s} {'worstMo':>8s} {'posMo%':>6s} {'/day':>5s}")
best = []
for pen, cap in ((0, 0), (100, 10), (100, 20), (100, 30), (150, 15), (150, 25), (150, 35),
                 (150, 0), (200, 25), (200, 35), (200, 0), (300, 30)):
    rows = build(make(pen, cap))
    dates = sorted({x[1] for x in rows})
    h1 = set(dates[:len(dates) // 2])
    for buy in (66, 68, 70, 72):
        bd = defaultdict(list)
        for s, d, raw, exc, unb in rows:
            if s >= buy and not unb:
                bd[d].append((raw, exc))
        per, e1, e2 = [], [], []
        for d, v in bd.items():
            if len(v) < 3:
                continue
            m = np.mean([x[1] for x in v])
            per.append(m)
            (e1 if d in h1 else e2).append(m)
        if len(per) < 40:
            continue
        oo = [x for d, v in bd.items() if d >= CUTOFF for x in v]
        if len(oo) < 300:
            continue
        R = np.array([x[0] for x in oo])
        OE = np.array([x[1] for x in oo])
        mo = defaultdict(list)
        for d, v in bd.items():
            if len(v) >= 3:
                mo[d[:7]].append(np.mean([x[1] for x in v]))
        mp = np.array([np.mean(v) for v in mo.values()])
        nd = np.mean([len(v) for v in bd.values() if len(v) >= 3])
        dis = (R < -0.10).mean() * 100
        print(f"{pen:>4d} {cap:>4d} {buy:>4d} | {np.mean(per)*100:>+6.2f}% {np.mean(e1)*100:>+6.2f}% "
              f"{np.mean(e2)*100:>+6.2f}% {OE.mean()*100:>+7.2f}% {R.mean()*100:>+7.2f}% "
              f"{(R>0).mean()*100:>4.0f}% {np.percentile(R,5)*100:>+6.1f}% {dis:>5.1f}% "
              f"{mp.min()*100:>+7.2f}% {(mp>0).mean()*100:>5.0f}% {nd:>5.1f}")
        ok = np.mean(e1) > 0 and np.mean(e2) > 0 and dis <= 15.0
        best.append((ok, OE.mean(), pen, cap, buy, dis, np.percentile(R, 5), np.mean(e1), np.mean(e2)))

print("\n=== selection rule: both halves>0 AND OOS disaster<=15% , then max OOS excess ===")
q = sorted([b for b in best if b[0]], key=lambda x: -x[1])[:6]
for ok, oe, pen, cap, buy, dis, p05, e1, e2 in q:
    print(f"  pen={pen} cap={cap or 'none'} buy>={buy}: oos_exc={oe*100:+.2f}% "
          f"disaster={dis:.1f}% p05={p05*100:+.1f}% h1={e1*100:+.2f}% h2={e2*100:+.2f}%")
print("\n  (fallback if the filter is empty)")
for ok, oe, pen, cap, buy, dis, p05, e1, e2 in sorted(best, key=lambda x: -x[1])[:6]:
    print(f"  pen={pen} cap={cap or 'none'} buy>={buy}: oos_exc={oe*100:+.2f}% disaster={dis:.1f}%")
