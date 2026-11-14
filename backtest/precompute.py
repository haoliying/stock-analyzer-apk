#!/usr/bin/env python3
"""Precompute every indicator per stock once, cache to .npz for fast strategy sweeps."""
import glob, json, os
import numpy as np
import pandas as pd
from indicators import (ema, rolling_sma, rsi_simple, rsi_wilder, macd_series,
                        kdj_app, kdj_iter, atr_series, adx_series, boll,
                        vol_ratio, obv_series)

_BASE = os.environ.get("BT_HOME", os.path.dirname(os.path.abspath(__file__)))
DATA, CACHE = f"{_BASE}/data", f"{_BASE}/cache"
os.makedirs(CACHE, exist_ok=True)


def cross_up(a, b):
    return (a > b) & (np.roll(a, 1) <= np.roll(b, 1))


def cross_dn(a, b):
    return (a < b) & (np.roll(a, 1) >= np.roll(b, 1))


def rolling_pct_rank(x, n):
    return pd.Series(x, dtype="float64").rolling(n).apply(
        lambda w: (w[-1] >= w).mean() if not np.isnan(w[-1]) else np.nan, raw=True).to_numpy()


def pct_change(x, k):
    """out[i] = x[i]/x[i-k] - 1 for i>=k, nan before. No look-ahead."""
    out = np.full(len(x), np.nan)
    with np.errstate(divide="ignore", invalid="ignore"):
        out[k:] = x[k:] / x[:-k] - 1.0
    out[~np.isfinite(out)] = np.nan
    return out


def build(code):
    d = json.load(open(f"{DATA}/{code}.json"))
    r = pd.DataFrame(d["rows"])
    c, h, l, v = (r["close"].to_numpy(float), r["high"].to_numpy(float),
                  r["low"].to_numpy(float), r["vol"].to_numpy(float))
    m = len(c)
    F = {"n": m}
    F["close"], F["high"], F["low"], F["vol"], F["pct"] = c, h, l, v, r["pct"].to_numpy(float)
    F["open"] = r["open"].to_numpy(float)
    F["turnover"] = r["turnover"].to_numpy(float)
    for n_ in (5, 10, 20, 60):
        F[f"ma{n_}"] = rolling_sma(c, n_)
    F["ema12"], F["ema26"] = ema(c, 12), ema(c, 26)
    F["rsi_app"] = rsi_simple(c, 14)
    F["rsi"] = rsi_wilder(c, 14)
    dif, dea, hist = macd_series(c)
    F["dif"], F["dea"], F["hist"] = dif, dea, hist
    F["hist_d"] = np.diff(hist, prepend=hist[0])
    F["dif_x_up"] = cross_up(dif, dea)
    F["dif_x_dn"] = cross_dn(dif, dea)
    ka, da, ja = kdj_app(h, l, c)
    F["kdj_app_k"], F["kdj_app_d"], F["kdj_app_j"] = ka, da, ja
    k, dd, j = kdj_iter(h, l, c)
    F["kdj_k"], F["kdj_d"], F["kdj_j"] = k, dd, j
    F["kdj_x_up"] = cross_up(k, dd)
    F["kdj_x_dn"] = cross_dn(k, dd)
    F["atr"] = atr_series(h, l, c)
    F["atr_pct"] = np.where(c > 0, F["atr"] / c, np.nan)
    adx, pdi, mdi = adx_series(h, l, c)
    F["adx"], F["pdi"], F["mdi"] = adx, pdi, mdi
    mid, bu, bl, bw, bp = boll(c)
    F["boll_mid"], F["boll_up"], F["boll_lo"], F["boll_w"], F["boll_pos"] = mid, bu, bl, bw, bp
    F["boll_w_pct"] = rolling_pct_rank(bw, 120)
    F["volr5"] = vol_ratio(v, 5)
    F["volr20"] = vol_ratio(v, 20)
    F["obv"] = obv_series(c, v)
    ov = F["obv"]
    obvs = np.full(m, np.nan)
    obvs[10:] = ov[10:] - ov[:-10]
    F["obv_slope"] = obvs
    for n_ in (20, 55):
        s = pd.Series(c)
        hi = s.rolling(n_).max().shift(1).to_numpy()
        lo = s.rolling(n_).min().shift(1).to_numpy()
        F[f"hi{n_}"], F[f"lo{n_}"] = hi, lo
        F[f"brk{n_}"] = c >= hi
        F[f"brkdn{n_}"] = c <= lo
        rng = hi - lo
        F[f"pos{n_}"] = np.where(rng > 0, (c - lo) / rng, np.nan)
    for n_ in (5, 10, 20, 60):
        F[f"ret{n_}"] = pct_change(c, n_)
    F["ma20_slope"] = pct_change(F["ma20"], 10)
    F["ma60_slope"] = pct_change(F["ma60"], 10)
    F["vol_up"] = (np.diff(c, prepend=c[0]) > 0)
    F["date"] = r["date"].to_numpy()
    F["code"] = code
    F["name"] = d["name"]
    return F


def main():
    codes = sorted(os.path.basename(p)[:-5] for p in glob.glob(f"{DATA}/*.json"))
    for i, c in enumerate(codes, 1):
        out = f"{CACHE}/{c}.npz"
        if not os.path.exists(out):
            try:
                np.savez_compressed(out, **build(c))
            except Exception as e:
                print(f"  {c}: {type(e).__name__} {e}")
        if i % 50 == 0:
            print(f"precomputed {i}/{len(codes)}", flush=True)
    print(f"cache ready: {len(glob.glob(f'{CACHE}/*.npz'))} stocks", flush=True)


if __name__ == "__main__":
    main()
