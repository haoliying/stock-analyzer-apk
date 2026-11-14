#!/usr/bin/env python3
"""Production model definition — the exact function that will be ported to Kotlin.
Kept in its own module so experiments can import it without running a script."""
import numpy as np
from strategy import load_all, build_baseline, MIN_HIST, COST, _nan, g

ATR_KNEE, ATR_PEN = 0.020, 150.0
H = 20

stocks = load_all()
DATES = sorted({str(d) for F in stocks for d in F["date"][MIN_HIST:]})
CUTOFF = DATES[int(len(DATES) * 0.7)]
BASELINE = build_baseline(stocks)


def prod(F, t):
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
        s -= max(0.0, a - ATR_KNEE) * ATR_PEN
    return float(np.clip(s, 0, 100)), True


def rows_for(fn):
    """(score, date, net 20d return, excess, unbuyable_flag) for every t -> t+1+H."""
    out = []
    for F in stocks:
        n = int(F["n"])
        c, o = F["close"], F["open"]
        lim = 0.097 if F["code"].startswith(("30", "68")) else 0.047
        for t in range(MIN_HIST, n - 1):
            s, ok = fn(F, t)
            if not ok:
                continue
            k = t + 1 + H
            if k >= n:
                continue
            eo = o[t + 1]
            if eo <= 0:
                continue
            r = (c[k] / eo - 1.0) - COST
            d = str(F["date"][t])
            e = BASELINE.get((d, H))
            out.append((s, d, r, r - e if e is not None else np.nan,
                        F["pct"][t] > 0 and eo >= c[t] * (1 + lim) * 0.995))
    return out
