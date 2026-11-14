#!/usr/bin/env python3
"""Iteration 13: final choice among three shipped candidates, with the tail
measured on RAW returns (not excess) and a per-month stability table.

Universe caveat stated up front: the 288 names were selected by CURRENT market
cap, so any momentum tilt is flattered by survivorship — a crashed-out leader
is not in the sample. That is why the aggressive 20%+ momentum filter is
judged on its tail and its month-by-month consistency, not on its headline.
"""
import numpy as np
from collections import defaultdict
from model import CUTOFF, _nan, g, stocks, MIN_HIST, COST, H, BASELINE

CAND = {
    "A prod(no momentum)": (0.020, 150.0, None),
    "B stale<12% -8": (0.020, 150.0, 0.12),
    "C stale<16% -8": (0.020, 150.0, 0.16),
    "D stale<20% -8": (0.020, 150.0, 0.20),
    "E stale<12% -8 +ATR300": (0.020, 300.0, 0.12),
}


def score(knee, pen, stale_th):
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
        if stale_th is not None:
            r = g(F, "ret20", t)
            if not _nan(r) and r < stale_th:
                s -= 8.0
        return float(np.clip(s, 0, 100)), True
    return fn


CACHE = {}
for lab, cfg in CAND.items():
    rows = []
    fn = score(*cfg)
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
            d = str(F["date"][t])
            b = BASELINE.get((d, H))
            if b is None:
                continue
            raw = (c[k] / eo - 1.0) - COST
            unb = F["pct"][t] > 0 and eo >= c[t] * (1 + lim) * 0.995
            rows.append((s, d, raw, raw - b, unb))
    CACHE[lab] = rows

dates_all = sorted({d for r in CACHE["A prod(no momentum)"] for d in [r[1]]})
h1 = set(dates_all[:len(dates_all) // 2])

print(f"{'candidate':26s} {'buys/d':>6s} {'d_exc':>7s} {'h1':>7s} {'h2':>7s} {'oos_exc':>8s} "
      f"{'oos_raw':>8s} {'oos_p05':>8s} {'oos>10L':>8s} {'worst':>7s} {'减仓exc':>8s}")
for lab, rows in CACHE.items():
    bd = defaultdict(list)
    sd = defaultdict(list)
    for s, d, raw, exc, unb in rows:
        if s >= 70 and not unb:
            bd[d].append((raw, exc))
        elif s <= 42:
            sd[d].append(exc)
    per, per1, per2, oos_raw, oos_exc, sells = [], [], [], [], [], []
    for d, v in bd.items():
        if len(v) < 3:
            continue
        per.append(np.mean([x[1] for x in v]))
        (per1 if d in h1 else per2).append(np.mean([x[1] for x in v]))
        if d >= CUTOFF:
            oos_raw.extend([x[0] for x in v])
            oos_exc.extend([x[1] for x in v])
    for d, v in sd.items():
        if len(v) >= 3:
            sells.append(np.mean(v))
    if len(per) < 20:
        print(f"{lab:26s} too few")
        continue
    R = np.array(oos_raw)
    nd = sum(len(v) for v in bd.values()) / max(1, len([1 for v in bd.values() if len(v) >= 3]))
    print(f"{lab:26s} {nd:>6.1f} {np.mean(per)*100:>+6.2f}% {np.mean(per1)*100:>+6.2f}% "
          f"{np.mean(per2)*100:>+6.2f}% {np.mean(oos_exc)*100:>+7.2f}% {R.mean()*100:>+7.2f}% "
          f"{np.percentile(R,5)*100:>+7.1f}% {(R<-0.10).mean()*100:>7.1f}% {R.min()*100:>+6.1f}% "
          f"{np.mean(sells)*100:>+7.2f}%")

print("\n=== month-level: share of months where the buy book beat the universe ===")
for lab, rows in CACHE.items():
    bm = defaultdict(list)
    for s, d, raw, exc, unb in rows:
        if s >= 70 and not unb:
            bm[d[:7]].append(exc)
    per = [np.mean(v) for v in bm.values() if len(v) >= 5]
    if per:
        p = np.array(per)
        print(f"  {lab:26s} months={len(p):3d} pos={np.mean(p>0)*100:5.1f}% "
              f"median={np.median(p)*100:+5.2f}% worst_month={p.min()*100:+5.2f}%")

print("\n=== OOS tail by year-month (raw 20d return of the buy book) ===")
for lab, rows in CACHE.items():
    bm = defaultdict(list)
    for s, d, raw, exc, unb in rows:
        if s >= 70 and not unb and d >= CUTOFF:
            bm[d[:7]].append(raw)
    ks = sorted(bm)
    line = "  ".join(f"{k[2:]}:{np.mean(bm[k])*100:+5.1f}%" for k in ks if len(bm[k]) >= 20)
    print(f"{lab:24s} {line}")
