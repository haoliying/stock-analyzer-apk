#!/usr/bin/env python3
"""Iteration 14: momentum is OUT (iter13: it only wins in blow-off months and
loses in every down month). Final tuning of the one knob that matters for a
user who lost money — how hard the volatility (ATR) penalty bites — and where
the BUY line sits.

OOS = dates >= 2026-04-23. d_exc / worstMo / posMo% are DATE-EQUAL EXCESS
(vs the same-day universe mean, so market beta is removed).
oos_avg / oos_p05 / oos>10L are RAW 20d outcomes.
"""
import numpy as np
from collections import defaultdict
from model import CUTOFF, _nan, g, stocks, MIN_HIST, COST, H, BASELINE


def score(knee, pen):
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


def build(knee, pen):
    fn = score(knee, pen)
    rows = []
    for F in stocks:
        n = int(F["n"])
        c, o = F["close"], F["open"]
        lim = 0.097 if F["code"].startswith(("30", "68")) else 0.047
        for t in range(MIN_HIST, n - 1):
            s, ok = fn(F, t)
            k = t + 1 + H
            eo = o[t + 1] if t + 1 < n else None
            if not ok or k >= n or not eo or eo <= 0:
                continue
            d = str(F["date"][t])
            b = BASELINE.get((d, H))
            if b is None:
                continue
            raw = (c[k] / eo - 1.0) - COST
            rows.append((s, d, raw, raw - b,
                         F["pct"][t] > 0 and eo >= c[t] * (1 + lim) * 0.995))
    return rows


print(f"{'knee':>5s} {'pen':>4s} {'buy':>4s} | {'d_exc':>7s} {'oos_exc':>8s} {'oos_avg':>8s} "
      f"{'oos_p05':>8s} {'>10L':>6s} {'worstMo':>8s} {'posMo%':>6s} {'buys/d':>6s}")
for knee, pen in ((0.020, 150), (0.020, 200), (0.020, 250), (0.020, 300), (0.025, 200), (0.015, 200)):
    rows = build(knee, pen)
    for buy in (66, 68, 70, 72):
        bd = defaultdict(list)
        for s, d, raw, exc, unb in rows:
            if s >= buy and not unb:
                bd[d].append((raw, exc))
        per = [np.mean([x[1] for x in v]) for v in bd.values() if len(v) >= 3]
        if len(per) < 20:
            print(f"{knee:>5.3f} {pen:>4d} {buy:>4d} | too few")
            continue
        oos_raw = np.array([x[0] for d, v in bd.items() if d >= CUTOFF for x in v])
        oos_exc = np.array([x[1] for d, v in bd.items() if d >= CUTOFF for x in v])
        mo = defaultdict(list)
        for d in bd:
            v = bd[d]
            if len(v) >= 3:
                mo[d[:7]].append(np.mean([x[1] for x in v]))
        mp = np.array([np.mean(v) for v in mo.values()])
        nd = np.mean([len(v) for v in bd.values() if len(v) >= 3])
        if oos_raw.size < 200:
            print(f"{knee:>5.3f} {pen:>4d} {buy:>4d} | {np.mean(per)*100:>+6.2f}%  oos too thin")
            continue
        print(f"{knee:>5.3f} {pen:>4d} {buy:>4d} | {np.mean(per)*100:>+6.2f}% "
              f"{oos_exc.mean()*100:>+7.2f}% {oos_raw.mean()*100:>+7.2f}% "
              f"{np.percentile(oos_raw,5)*100:>+7.1f}% {(oos_raw<-0.10).mean()*100:>5.1f}% "
              f"{mp.min()*100:>+7.2f}% {(mp>0).mean()*100:>5.0f}% {nd:>6.1f}")
    print("")
