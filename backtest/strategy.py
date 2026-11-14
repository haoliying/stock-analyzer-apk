#!/usr/bin/env python3
"""Strategy definitions + honest walk-forward evaluation.

Protocol
--------
Signal computed on CLOSE of day t using only data <= t.
Entry is the OPEN of t+1 (you cannot act on a close you just saw).
Trades whose next open is at the limit-up price are dropped: unbuyable.
Forward returns measured 5/10/20 trading days after entry, net of cost.
EXCESS is measured against the mean outcome of every stock in the universe
that had the SAME signal date and horizon, so a rising market cannot
masquerade as stock-picking skill.
"""
import glob
import os

import numpy as np

CACHE = os.environ.get("BT_CACHE", os.path.join(os.path.dirname(os.path.abspath(__file__)), "cache"))
HORIZONS = (5, 10, 20)
MIN_HIST = 130
COST = 0.0012
BUY_TH, SELL_TH = 70.0, 35.0


def load_all():
    out = []
    for p in sorted(glob.glob(f"{CACHE}/*.npz")):
        z = np.load(p, allow_pickle=True)
        F = {k: z[k] for k in z.files}
        for k in ("close", "open", "pct", "vol"):
            F[k] = F[k].astype(float)
        F["code"] = str(F["code"])
        out.append(F)
    return out


# ------------------------------------------------------------------ helpers
def _nan(x):
    return x is None or (isinstance(x, float) and np.isnan(x))


def g(F, key, t):
    return F[key][t]


# ---------------------------------------------------------------- strategies
def v_old(F, t):
    """Faithful port of the app's current StockAnalyzer.analyze()."""
    c = F["close"][t]
    ma5, ma10, ma20 = g(F, "ma5", t), g(F, "ma10", t), g(F, "ma20", t)
    rsi = g(F, "rsi_app", t)
    dif, dea, hist = g(F, "dif", t), g(F, "dea", t), g(F, "hist", t)
    k, d, j = g(F, "kdj_app_k", t), g(F, "kdj_app_d", t), g(F, "kdj_app_j", t)
    if _nan(ma5) or _nan(ma10) or _nan(hist):
        return 0.0, False
    s = 50.0
    s += 10 if ma5 > ma10 else -10
    if not _nan(ma20):
        s += 5 if ma5 > ma20 else -5
    s += 5 if c > ma5 else -5
    if not _nan(rsi):
        if rsi > 80: s -= 15
        elif rsi > 70: s -= 10
        elif rsi < 20: s += 15
        elif rsi < 30: s += 10
    if dif > dea: s += 15 if hist > 0 else 8
    else: s -= 15 if hist < 0 else 8
    if not _nan(k):
        if k > d and j > k: s += 8
        elif k < d and j < k: s -= 8
        if j > 100: s -= 5
        if j < 0: s += 5
    if c >= g(F, "hi20", t): s += 10
    if c <= g(F, "lo20", t): s -= 10
    return float(np.clip(s, 0, 100)), True


def score_regime(F, t, w_trend=18, w_adx=8, w_mom=8, w_vol=8, use_rsi=True,
                 use_vol=True, w_break=6, punish_oversold=True):
    """Regime aware: follow trend, confirm with volume, never fade strength."""
    c = F["close"][t]
    ma20, ma60, sl = g(F, "ma20", t), g(F, "ma60", t), g(F, "ma20_slope", t)
    if _nan(ma20) or _nan(ma60) or _nan(sl):
        return 0.0, False
    s = 50.0
    up = c > ma20 > ma60
    dn = c < ma20 < ma60

    if up and sl > 0.01: s += w_trend
    elif up: s += w_trend * 0.55
    elif dn and sl < -0.01: s -= w_trend
    elif dn: s -= w_trend * 0.55
    else: s -= 2

    adx, pdi, mdi = g(F, "adx", t), g(F, "pdi", t), g(F, "mdi", t)
    if not _nan(adx):
        if adx > 25: s += w_adx if pdi > mdi else -w_adx
        elif adx < 18: s -= 3

    dif, dea, hd = g(F, "dif", t), g(F, "dea", t), g(F, "hist_d", t)
    if not _nan(hd):
        if dif > dea and hd > 0: s += w_mom
        elif dif < dea and hd < 0: s -= w_mom

    if use_vol:
        vr, upday = g(F, "volr5", t), bool(g(F, "vol_up", t))
        if not _nan(vr):
            if upday and vr > 1.3: s += w_vol
            elif upday and vr < 0.8: s -= 5
            elif (not upday) and vr > 1.5: s -= w_vol

    if g(F, "brk55", t) and g(F, "volr20", t) > 1.2 and not _nan(g(F, "volr20", t)):
        s += w_break
    if g(F, "brkdn20", t): s -= 10

    bp = g(F, "boll_pos", t)
    if not _nan(bp) and bp > 1.0 and not up:
        s -= 4

    if use_rsi:
        rsi = g(F, "rsi", t)
        if not _nan(rsi):
            if up:
                if 50 <= rsi <= 72: s += 4
                elif rsi > 82: s -= 4
            elif punish_oversold and rsi < 35:
                s -= 4                      # weak AND still falling
    return float(np.clip(s, 0, 100)), True


def S_trend_only(F, t):
    return score_regime(F, t, use_rsi=False, use_vol=False)

def S_no_vol(F, t):
    return score_regime(F, t, use_vol=False)

def S_full(F, t):
    return score_regime(F, t)

def S_full_let_oversold(F, t):
    return score_regime(F, t, punish_oversold=False)

def S_strong_th(F, t):
    return score_regime(F, t, w_trend=22, w_vol=10, w_adx=10, w_mom=10)


STRATEGIES = {
    "old_app (current)": v_old,
    "trend only": S_trend_only,
    "trend+rsi, no vol": S_no_vol,
    "trend+rsi+vol (new)": S_full,
    "new, tolerate oversold": S_full_let_oversold,
    "new, stronger weights": S_strong_th,
}


# ---------------------------------------------------------------- evaluation
def trade_rows(F, fn, buy_th=BUY_TH, sell_th=SELL_TH):
    """(signal_date, side, score, {h: net return}) for one stock."""
    n = int(F["n"])
    c, o = F["close"], F["open"]
    limit = 0.097 if F["code"].startswith(("30", "68")) else 0.047
    rows = []
    for t in range(MIN_HIST, n - 1):
        sc, ok = fn(F, t)
        if not ok:
            continue
        side = 1 if sc >= buy_th else (-1 if sc <= sell_th else 0)
        if side == 0:
            continue
        eo = o[t + 1]
        if eo <= 0:
            continue
        if side > 0 and F["pct"][t] > 0 and eo >= c[t] * (1 + limit) * 0.995:
            continue                                    # opened limit-up, can't buy
        ret = {}
        for h in HORIZONS:
            k = t + 1 + h
            ret[h] = (c[k] / eo - 1.0) - COST if k < n else np.nan
        rows.append((str(F["date"][t]), side, sc, ret))
    return rows


def build_baseline(stocks):
    """Mean forward return of EVERY stock for each (signal_date, horizon)."""
    acc = {}
    for F in stocks:
        n = int(F["n"])
        c, o = F["close"], F["open"]
        for t in range(MIN_HIST, n - 1):
            eo = o[t + 1]
            if eo <= 0:
                continue
            dt = str(F["date"][t])
            for h in HORIZONS:
                k = t + 1 + h
                if k < n:
                    acc.setdefault((dt, h), []).append(c[k] / eo - 1.0)
    return {kk: float(np.mean(v)) for kk, v in acc.items()}


def report(name, trades, baseline, cutoff):
    print(f"\n--- {name} ---")
    for side, lab in ((1, "BUY/加仓"), (-1, "SELL/减仓")):
        sel = [r for r in trades if r[1] == side]
        if not sel:
            print(f"  {lab:12s} no signals")
            continue
        parts = []
        for h in HORIZONS:
            vals = np.array([r[3][h] for r in sel if not np.isnan(r[3][h])], dtype=float)
            exc = np.array([r[3][h] - baseline.get((r[0], h), np.nan)
                            for r in sel if not np.isnan(r[3][h])
                            and (r[0], h) in baseline], dtype=float)
            if vals.size == 0:
                continue
            win = (vals > 0).mean() * 100 if side > 0 else (vals < 0).mean() * 100
            parts.append(f"{h}d n={vals.size:<5d} avg={vals.mean()*100:+6.2f}% "
                         f"exc={exc.mean()*100:+6.2f}% {'win' if side>0 else 'corr'}={win:4.1f}%")
        print(f"  {lab:12s} " + (" | ".join(parts)))
        # out of sample
        oos = [r for r in sel if r[0] >= cutoff]
        if len(oos) >= 15:
            p2 = []
            for h in HORIZONS:
                vals = np.array([r[3][h] for r in oos if not np.isnan(r[3][h])], dtype=float)
                exc = np.array([r[3][h] - baseline.get((r[0], h), np.nan)
                                for r in oos if not np.isnan(r[3][h])
                                and (r[0], h) in baseline], dtype=float)
                if vals.size:
                    p2.append(f"{h}d n={vals.size} avg={vals.mean()*100:+6.2f}% exc={exc.mean()*100:+6.2f}%")
            print(f"  {'  [OOS]':12s} " + " | ".join(p2))


def main():
    stocks = load_all()
    dates = sorted({str(d) for F in stocks for d in F["date"][MIN_HIST:]})
    cutoff = dates[int(len(dates) * 0.7)]
    print(f"universe: {len(stocks)} stocks | {dates[0]} .. {dates[-1]} | OOS from {cutoff}")
    print(f"signal thresholds: BUY>={BUY_TH:.0f}  SELL<={SELL_TH:.0f}\n")

    baseline = build_baseline(stocks)
    b10 = np.array([v for (d, h), v in baseline.items() if h == 10])
    print(f"universe baseline 10d: avg={b10.mean()*100:+.2f}% "
          f"win={(b10 > 0).mean()*100:.1f}%  (all stock-days)\n")

    for name, fn in STRATEGIES.items():
        tr = []
        for F in stocks:
            tr.extend(trade_rows(F, fn))
        report(name, tr, baseline, cutoff)


def distribution():
    """How loud is each model? Fraction of stock-days calling buy / sell / hold."""
    stocks = load_all()
    print("signal frequency over all evaluated stock-days:")
    for name, fn in STRATEGIES.items():
        cnt = {1: 0, 0: 0, -1: 0}
        tot = 0
        for F in stocks:
            n = int(F["n"])
            for t in range(MIN_HIST, n - 1):
                sc, ok = fn(F, t)
                if not ok:
                    continue
                tot += 1
                cnt[1 if sc >= BUY_TH else (-1 if sc <= SELL_TH else 0)] += 1
        if tot:
            print(f"  {name:24s} n={tot:<7d} BUY={cnt[1]/tot*100:5.1f}%  "
                  f"HOLD={cnt[0]/tot*100:5.1f}%  SELL={cnt[-1]/tot*100:5.1f}%")


if __name__ == "__main__":
    distribution()
    print()
    main()
