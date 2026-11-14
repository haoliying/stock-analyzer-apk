#!/usr/bin/env python3
"""Iteration 4: lock the production config and measure it with the app's REAL
advice thresholds (STRONG_BUY>=75, BUY>=60, SELL<=25..), not arbitrary ones.
Also tests the position-advice path, which is what the user actually follows."""
import numpy as np
from strategy import load_all, build_baseline, MIN_HIST, COST, _nan, g, v_old
from iter2 import V_core

H = 20
stocks = load_all()
DATES = sorted({str(d) for F in stocks for d in F["date"][MIN_HIST:]})
CUTOFF = DATES[int(len(DATES) * 0.7)]
BASELINE = build_baseline(stocks)

# ---- production model, exactly as it will be written in Kotlin
ATR_KNEE, ATR_PEN = 0.020, 150.0


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


def old_advice_score(F, t):
    sc, ok = v_old(F, t)
    return float(sc), bool(ok)


def gather(fn, mapping):
    """mapping(score)->side (1 add / -1 reduce / 0 nothing)."""
    sigs = []
    for si, F in enumerate(stocks):
        n = int(F["n"])
        c, o = F["close"], F["open"]
        lim = 0.097 if F["code"].startswith(("30", "68")) else 0.047
        for t in range(MIN_HIST, n - 1):
            sc, ok = fn(F, t)
            if not ok:
                continue
            side = mapping(sc)
            if side == 0:
                continue
            eo = o[t + 1]
            if eo <= 0:
                continue
            if side > 0 and F["pct"][t] > 0 and eo >= c[t] * (1 + lim) * 0.995:
                continue
            k = t + 1 + H
            if k >= n:
                continue
            sigs.append((str(F["date"][t]), side, (c[k] / eo - 1.0) - COST))
    return sigs


OLD_MAP = lambda s: 1 if s >= 60 else (-1 if s <= 40 else 0)          # app's generatePositionAdvice
NEW_MAP = lambda s: 1 if s >= 68 else (-1 if s <= 42 else 0)          # production mapping


def desc(label, sigs, side):
    sel = [x for x in sigs if x[1] == side]
    if len(sel) < 50:
        print(f"    {label:34s} (n<50)")
        return
    v = np.array([x[2] for x in sel])
    e = np.array([x[2] - BASELINE.get((x[0], H), np.nan) for x in sel if (x[0], H) in BASELINE])
    o = np.array([x[2] for x in sel if x[0] >= CUTOFF])
    wins = (v > 0).mean() * 100
    print(f"    {label:34s} n={len(sel):<6d} exc={e.mean()*100:+5.2f}% avg={v.mean()*100:+5.2f}% "
          f"win={wins:4.1f}% p05={np.percentile(v,5)*100:+5.1f}% >10%loss={(v<-0.10).mean()*100:4.1f}%"
          + (f" | OOS avg={o.mean()*100:+5.2f}% win={(o>0).mean()*100:4.1f}% p05={np.percentile(o,5)*100:+5.1f}%" if o.size > 50 else ""))


old = gather(old_advice_score, OLD_MAP)
new = gather(prod, NEW_MAP)
tot_old = sum(1 for F in stocks for _ in range(MIN_HIST, int(F["n"]) - 1))

print("=== position-advice path, using the app's own score bands ===")
print(f"  old app: 加仓 {sum(1 for x in old if x[1] > 0)/tot_old*100:.1f}% of stock-days, "
      f"减仓 {sum(1 for x in old if x[1] < 0)/tot_old*100:.1f}%  -> speaks "
      f"{sum(1 for x in old)/tot_old*100:.1f}% of the time")
print(f"  new app: 加仓 {sum(1 for x in new if x[1] > 0)/tot_old*100:.1f}% of stock-days, "
      f"减仓 {sum(1 for x in new if x[1] < 0)/tot_old*100:.1f}%  -> speaks "
      f"{sum(1 for x in new)/tot_old*100:.1f}% of the time")
print("\n  加仓/BUY advice — what happened next 20d:")
desc("OLD  score>=60 -> 加仓", old, 1)
desc("NEW  score>=68 -> 加仓", new, 1)
print("  减仓/SELL advice — stock should then underperform (avg<0 is correct):")
desc("OLD  score<=40 -> 减仓", old, -1)
desc("NEW  score<=42 -> 减仓", new, -1)

print("\n=== new model, broken out by score band (is the ordering monotone?) ===")
for lo, hi, lab in ((0, 30, "<=29 强烈减仓"), (30, 42, "30-41 建议减仓"), (42, 58, "42-57 观望"),
                    (58, 68, "58-67 轻仓/持有"), (68, 80, "68-79 建议加仓"), (80, 101, ">=80 强烈加仓")):
    sel = [x for x in gather(prod, lambda s, a=lo, b=hi: 1 if a <= s < b else 0) ]
    if len(sel) < 50:
        print(f"    {lab:16s} (n<50)")
        continue
    v = np.array([x[2] for x in sel])
    e = np.array([x[2] - BASELINE.get((x[0], H), np.nan) for x in sel if (x[0], H) in BASELINE])
    print(f"    {lab:16s} n={len(sel):<6d} avg={v.mean()*100:+5.2f}% exc={e.mean()*100:+5.2f}% "
          f"win={(v>0).mean()*100:4.1f}% p05={np.percentile(v,5)*100:+5.1f}%")

print("\n=== same band test on the OLD model (does ITS ordering work?) ===")
for lo, hi, lab in ((0, 25, "<=24"), (25, 40, "25-39"), (40, 60, "40-59"),
                    (60, 75, "60-74"), (75, 101, ">=75")):
    sel = gather(old_advice_score, lambda s, a=lo, b=hi: 1 if a <= s < b else 0)
    if len(sel) < 50:
        print(f"    {lab:16s} (n<50)")
        continue
    v = np.array([x[2] for x in sel])
    e = np.array([x[2] - BASELINE.get((x[0], H), np.nan) for x in sel if (x[0], H) in BASELINE])
    print(f"    {lab:16s} n={len(sel):<6d} avg={v.mean()*100:+5.2f}% exc={e.mean()*100:+5.2f}% "
          f"win={(v>0).mean()*100:4.1f}% p05={np.percentile(v,5)*100:+5.1f}%")

print("\n=== recent regime only (>= 2026-04-23), old vs new, 加仓 ===")
for lab, sigs in (("old", old), ("new", new)):
    sel = [x for x in sigs if x[1] > 0 and x[0] >= CUTOFF]
    if len(sel) > 50:
        v = np.array([x[2] for x in sel])
        print(f"  {lab}: n={len(sel)} avg={v.mean()*100:+5.2f}% win={(v>0).mean()*100:4.1f}% "
              f"p05={np.percentile(v,5)*100:+5.1f}% p25={np.percentile(v,25)*100:+5.1f}% "
              f">10%loss={(v<-0.10).mean()*100:4.1f}% worst={v.min()*100:+.1f}%")
