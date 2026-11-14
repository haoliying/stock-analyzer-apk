#!/usr/bin/env python3
"""Iteration 2: refine the winner, measure tail risk (what actually loses money),
and sweep thresholds so the model stays quiet unless it has an edge."""
import numpy as np
from strategy import (load_all, trade_rows, build_baseline, HORIZONS, MIN_HIST,
                      COST, _nan, g)

stocks = None


# --------------------------------------------------------------- variants
def base_trend(F, t):
    c, ma20, ma60, sl = F["close"][t], g(F, "ma20", t), g(F, "ma60", t), g(F, "ma20_slope", t)
    if _nan(ma20) or _nan(ma60) or _nan(sl):
        return None
    return dict(c=c, ma20=ma20, ma60=ma60, sl=sl,
                up=c > ma20 > ma60, dn=c < ma20 < ma60,
                strong=sl > 0.015, mid=0.0 < sl <= 0.015)


def V_core(F, t):
    """Pure trend structure. No ADX, no RSI, no volume."""
    b = base_trend(F, t)
    if b is None:
        return 0.0, False
    s = 50.0
    if b["up"]: s += 22 if b["strong"] else (14 if b["mid"] else 6)
    elif b["dn"]: s -= 22 if b["sl"] < -0.015 else (14 if b["sl"] < 0 else 6)
    else: s -= 3
    if not _nan(g(F, "ma10", t)): s += 4 if b["c"] > g(F, "ma10", t) > b["ma20"] else -2
    return float(np.clip(s, 0, 100)), True


def make(extra=None, veto=None):
    def fn(F, t):
        s, ok = V_core(F, t)
        if not ok:
            return 0.0, False
        if veto is not None and veto(F, t):
            return 0.0, False if veto == vol_veto else s
        return float(np.clip(s + (extra(F, t) if extra else 0.0), 0, 100)), True
    return fn


def atr_veto(F, t, lim=0.045):
    a = g(F, "atr_pct", t)
    return not _nan(a) and a > lim

def vol_veto(F, t):
    """Skip names whose recent move was on collapsing participation."""
    v = g(F, "volr20", t)
    return not _nan(v) and v < 0.45

def adx_bonus(F, t):
    adx, pdi, mdi = g(F, "adx", t), g(F, "pdi", t), g(F, "mdi", t)
    if _nan(adx): return 0.0
    if adx > 25 and pdi > mdi: return 6.0
    if adx > 25 and mdi > pdi: return -6.0
    return -2.0 if adx < 15 else 0.0

def pullback_bonus(F, t):
    """Entry location: uptrend but price near MA20 = better price, less chase."""
    c, ma20 = F["close"][t], g(F, "ma20", t)
    if _nan(ma20) or ma20 <= 0: return 0.0
    dist = (c - ma20) / ma20
    if dist < 0: return 0.0
    if dist < 0.02: return 8.0          # riding the trend, not stretched
    if dist < 0.05: return 3.0
    if dist > 0.12: return -10.0        # 20% above MA20 = extended, do not chase
    if dist > 0.08: return -5.0
    return 0.0

def mom_bonus(F, t):
    r = g(F, "ret10", t)
    if _nan(r): return 0.0
    return 6.0 if r > 0.05 else (-6.0 if r < -0.05 else 0.0)

def obv_bonus(F, t):
    """Volume confirmation done as accumulation, not single-day spike."""
    o = g(F, "obv_slope", t)
    if _nan(o): return 0.0
    return 5.0 if o > 0 else -3.0

def break_bonus(F, t):
    if g(F, "brkdn20", t): return -12.0
    if g(F, "brk55", t) and g(F, "volr20", t) > 1.0: return 6.0
    return 0.0


def V_full(F, t):
    s, ok = V_core(F, t)
    if not ok:
        return 0.0, False
    return float(np.clip(s + adx_bonus(F, t) + pullback_bonus(F, t) + mom_bonus(F, t), 0, 100)), True


def V_full_obv(F, t):
    s, ok = V_full(F, t)
    if not ok:
        return 0.0, False
    return float(np.clip(s + obv_bonus(F, t), 0, 100)), True


def V_full_atr(F, t):
    s, ok = V_full(F, t)
    if not ok:
        return 0.0, False
    a = g(F, "atr_pct", t)
    if not _nan(a):
        if a > 0.05: s -= 12
        elif a > 0.035: s -= 5
        elif a < 0.02: s += 4
    return float(np.clip(s, 0, 100)), True


def V_full_brk(F, t):
    s, ok = V_full(F, t)
    return (float(np.clip(s + break_bonus(F, t), 0, 100)), True) if ok else (0.0, False)


def V_obv_atr(F, t):
    """OBV accumulation plus an ATR (volatility) penalty."""
    s, ok = V_full_atr(F, t)
    if not ok:
        return 0.0, False
    return float(np.clip(s + obv_bonus(F, t), 0, 100)), True


VARIANTS = {
    "core trend (baseline)": V_core,
    "core+ADX+pullback+mom": V_full,
    "  +OBV": V_full_obv,
    "  +ATR risk filter": V_full_atr,
    "  +breakdown veto": V_full_brk,
    "  +OBV+ATR": V_obv_atr,
}


# --------------------------------------------------------------- metrics
def stats(sel, baseline, h):
    vals = np.array([r[3][h] for r in sel if not np.isnan(r[3][h])], dtype=float)
    if vals.size < 30:
        return None
    exc = np.array([r[3][h] - baseline.get((r[0], h), np.nan) for r in sel
                    if not np.isnan(r[3][h]) and (r[0], h) in baseline], dtype=float)
    m = ~np.isnan(exc)
    return dict(
        n=vals.size,
        avg=vals.mean() * 100,
        exc=exc[m].mean() * 100 if m.any() else np.nan,
        win=(vals > 0).mean() * 100,
        p05=np.percentile(vals, 5) * 100,          # the losses that hurt
        p01=np.percentile(vals, 1) * 100,
        big_loss=(vals < -0.10).mean() * 100,      # % of trades down >10%
        worst=vals.min() * 100,
    )


def main():
    global stocks
    stocks = load_all()
    dates = sorted({str(d) for F in stocks for d in F["date"][MIN_HIST:]})
    cutoff = dates[int(len(dates) * 0.7)]
    baseline = build_baseline(stocks)

    # frequency
    print("=== signal frequency (BUY>=70 / SELL<=35) ===")
    for name, fn in list(VARIANTS.items()) + [("old_app", __import__("strategy").v_old)]:
        cnt = {1: 0, 0: 0, -1: 0}
        tot = 0
        for F in stocks:
            for t in range(MIN_HIST, int(F["n"]) - 1):
                sc, ok = fn(F, t)
                if not ok: continue
                tot += 1
                cnt[1 if sc >= 70 else (-1 if sc <= 35 else 0)] += 1
        print(f"  {name:24s} BUY={cnt[1]/tot*100:5.1f}% HOLD={cnt[0]/tot*100:5.1f}% SELL={cnt[-1]/tot*100:5.1f}%")

    print("\n=== BUY quality: full sample / OOS (excess vs same-day universe) ===")
    hdr = f"{'variant':24s} {'n':>6s} {'exc20':>7s} {'win':>5s} {'p05':>7s} {'>10%loss':>8s} | {'OOS n':>6s} {'OOSexc':>7s} {'OOSwin':>6s} {'OOSp05':>7s}"
    print(hdr)
    rows = {}
    for name, fn in VARIANTS.items():
        tr = []
        for F in stocks:
            tr.extend(trade_rows(F, fn, buy_th=70.0, sell_th=35.0))
        longs = [r for r in tr if r[1] > 0]
        oos = [r for r in longs if r[0] >= cutoff]
        s20, o20 = stats(longs, baseline, 20), stats(oos, baseline, 20)
        if not s20:
            continue
        rows[name] = (s20, o20)
        oo = f"{o20['n']:>6d} {o20['exc']:>+6.2f}% {o20['win']:>5.1f}% {o20['p05']:>+6.1f}%" if o20 else "  (too few)"
        print(f"{name:24s} {s20['n']:>6d} {s20['exc']:>+6.2f}% {s20['win']:>5.1f}% "
              f"{s20['p05']:>+6.1f}% {s20['big_loss']:>7.1f}% | {oo}")

    print("\n=== same, all horizons (full sample) ===")
    for name, fn in VARIANTS.items():
        tr = []
        for F in stocks:
            tr.extend(trade_rows(F, fn, buy_th=70.0, sell_th=35.0))
        longs = [r for r in tr if r[1] > 0]
        cells = []
        for h in HORIZONS:
            s = stats(longs, baseline, h)
            if s: cells.append(f"{h}d exc={s['exc']:+.2f}% win={s['win']:.1f}%")
        print(f"  {name:24s} " + " | ".join(cells))


if __name__ == "__main__":
    main()
