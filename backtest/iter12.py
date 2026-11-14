#!/usr/bin/env python3
"""Iteration 12: joint sweep of the stale-strength filter and the ATR penalty.

iter11 showed requiring real 20d movement lifts the buy book a lot, but loosens
the tail slightly. iter3 showed the ATR penalty is what protects the tail. Find
the pair that is good on BOTH, on out-of-sample data, not just on the mean.
"""
import numpy as np
from collections import defaultdict
from model import rows_for, CUTOFF, _nan, g, stocks, MIN_HIST, COST, H, BASELINE


def make(knee, pen, stale_th, stale_pen):
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
        r = g(F, "ret20", t)
        if not _nan(r) and r < stale_th:
            s -= stale_pen
        return float(np.clip(s, 0, 100)), True
    return fn


def stats(fn, buy_th=70.0, sell_th=42.0):
    by_date = defaultdict(lambda: {"b": [], "s": []})
    tot = 0
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
            tot += 1
            d = str(F["date"][t])
            b = BASELINE.get((d, H))
            if b is None:
                continue
            r = (c[k] / eo - 1.0) - COST
            unb = F["pct"][t] > 0 and eo >= c[t] * (1 + lim) * 0.995
            if s >= buy_th and not unb:
                by_date[d]["b"].append(r - b)
                by_date[d].setdefault("_raw", []).append(r)
            elif s <= sell_th:
                by_date[d]["s"].append(r - b)
    per_b, per_s, raw_b, oos_b = [], [], [], []
    for d, v in by_date.items():
        if len(v["b"]) >= 3:
            m = float(np.mean(v["b"]))
            per_b.append(m)
            if d >= CUTOFF:
                oos_b.extend(v["b"])
        if len(v["s"]) >= 3:
            per_s.append(float(np.mean(v["s"])))
    raw = []
    return (np.mean(per_b) if per_b else np.nan, np.mean(per_s) if per_s else np.nan,
            np.mean(oos_b) if oos_b else np.nan,
            np.percentile(oos_b, 5) if len(oos_b) > 200 else np.nan,
            np.mean(np.array(oos_b) < -0.10) * 100 if len(oos_b) > 200 else np.nan,
            np.mean(np.array(oos_b)) * 100 if oos_b else np.nan,
            len(per_b), tot)


print(f"{'knee':>5s} {'pen':>4s} {'stale':>5s} | {'d_exc':>7s} {'oos_exc':>7s} {'oos_avg':>7s} "
      f"{'oos_p05':>7s} {'oos>10L':>7s} {'减仓':>7s} {'dates':>5s}")
base = stats(make(0.020, 150.0, -9.9, 0.0))
print(f"{'0.020':>5s} {'150':>4s} {'none':>5s} | {base[0]*100:>+6.2f}% {base[2]*100:>+6.2f}% "
      f"{base[5]:>+6.2f}% {base[3]*100:>+6.1f}% {base[4]:>6.1f}% {base[1]*100:>+6.2f}% {base[6]:>5d}")
for knee, pen in ((0.015, 150), (0.020, 150), (0.020, 300), (0.020, 450), (0.025, 300)):
    for st in (0.0, 0.12, 0.16, 0.20):
        r = stats(make(knee, pen, st, 8.0))
        print(f"{knee:>5.3f} {pen:>4d} {st:>5.2f} | {r[0]*100:>+6.2f}% {r[2]*100:>+6.2f}% "
              f"{r[5]:>+6.2f}% {r[3]*100:>+6.1f}% {r[4]:>6.1f}% {r[1]*100:>+6.2f}% {r[6]:>5d}")
