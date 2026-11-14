#!/usr/bin/env python3
"""Indicator library: faithful ports of the app's current math + improved versions."""
import numpy as np
import pandas as pd


def ema(x, n):
    """Standard EMA seeded with the first value (matches Kotlin emaSeries)."""
    return pd.Series(x, dtype="float64").ewm(span=n, adjust=False).mean().to_numpy()


def sma_last(x, n):
    return np.mean(x[-n:]) if len(x) >= n else None


def rolling_sma(x, n):
    return pd.Series(x, dtype="float64").rolling(n).mean().to_numpy()


def rsi_simple(close, n=14):
    """Port of app calculateRSI: plain mean of the last n gains/losses (no smoothing)."""
    out = np.full(len(close), np.nan)
    if len(close) <= n:
        return out
    ch = np.diff(close)
    for i in range(n, len(close)):
        w = ch[i - n:i]
        g = w[w > 0].sum()
        l = -w[w < 0].sum()
        out[i] = 100.0 if l == 0 else 100.0 - 100.0 / (1.0 + (g / n) / (l / n))
    return out


def rsi_wilder(close, n=14):
    ch = np.diff(close, prepend=close[0])
    ch[0] = 0.0
    up = np.where(ch > 0, ch, 0.0)
    dn = np.where(ch < 0, -ch, 0.0)
    if len(close) <= n:
        return np.full(len(close), np.nan)
    ag, al = up[1:n + 1].mean(), dn[1:n + 1].mean()
    out = np.full(len(close), np.nan)
    out[n] = 100.0 if al == 0 else 100.0 - 100.0 / (1.0 + ag / al)
    for i in range(n + 1, len(close)):
        ag = (ag * (n - 1) + up[i]) / n
        al = (al * (n - 1) + dn[i]) / n
        out[i] = 100.0 if al == 0 else 100.0 - 100.0 / (1.0 + ag / al)
    return out


def macd_series(close, fast=12, slow=26, sig=9):
    dif = ema(close, fast) - ema(close, slow)
    dea = ema(dif, sig)
    hist = (dif - dea) * 2
    return dif, dea, hist


def kdj_app(high, low, close, n=9):
    """Port of app calculateKDJ: single-step RSV smoothed once against a 50 seed."""
    out_k = np.full(len(close), np.nan)
    out_d = np.full(len(close), np.nan)
    out_j = np.full(len(close), np.nan)
    for i in range(n - 1, len(close)):
        hh, ll = high[i - n + 1:i + 1].max(), low[i - n + 1:i + 1].min()
        rsv = 50.0 if hh == ll else (close[i] - ll) / (hh - ll) * 100
        k = rsv / 3 + 50 * 2 / 3
        d = k / 3 + 50 * 2 / 3
        out_k[i], out_d[i], out_j[i] = k, d, 3 * k - 2 * d
    return out_k, out_d, out_j


def kdj_iter(high, low, close, n=9):
    """Proper iterative KDJ: K=(2K'+RSV)/3, D=(2D'+K)/3."""
    m = len(close)
    K = np.full(m, np.nan)
    D = np.full(m, np.nan)
    J = np.full(m, np.nan)
    k, d = 50.0, 50.0
    for i in range(m):
        if i < n - 1:
            continue
        hh, ll = high[i - n + 1:i + 1].max(), low[i - n + 1:i + 1].min()
        rsv = 50.0 if hh == ll else (close[i] - ll) / (hh - ll) * 100
        k = (2 * k + rsv) / 3
        d = (2 * d + k) / 3
        K[i], D[i], J[i] = k, d, 3 * k - 2 * d
    return K, D, J


def atr_series(high, low, close, n=14):
    m = len(close)
    tr = np.empty(m)
    tr[0] = high[0] - low[0]
    for i in range(1, m):
        tr[i] = max(high[i] - low[i], abs(high[i] - close[i - 1]), abs(low[i] - close[i - 1]))
    return pd.Series(tr, dtype="float64").ewm(alpha=1 / n, adjust=False).mean().to_numpy()


def adx_series(high, low, close, n=14):
    """Wilder ADX + directional components."""
    m = len(close)
    up = np.diff(high, prepend=high[0])
    dn = -np.diff(low, prepend=low[0])
    plus_dm = np.where((up > dn) & (up > 0), up, 0.0)
    minus_dm = np.where((dn > up) & (dn > 0), dn, 0.0)
    tr = np.empty(m)
    tr[0] = high[0] - low[0]
    for i in range(1, m):
        tr[i] = max(high[i] - low[i], abs(high[i] - close[i - 1]), abs(low[i] - close[i - 1]))
    al = pd.Series(tr).ewm(alpha=1 / n, adjust=False).mean().to_numpy()
    ap = pd.Series(plus_dm).ewm(alpha=1 / n, adjust=False).mean().to_numpy()
    am = pd.Series(minus_dm).ewm(alpha=1 / n, adjust=False).mean().to_numpy()
    with np.errstate(divide="ignore", invalid="ignore"):
        pdi = np.where(al > 0, 100 * ap / al, 0.0)
        mdi = np.where(al > 0, 100 * am / al, 0.0)
        ds = pdi + mdi
        dx = np.where(ds > 0, 100 * np.abs(pdi - mdi) / ds, 0.0)
    adx = pd.Series(dx).ewm(alpha=1 / n, adjust=False).mean().to_numpy()
    return adx, pdi, mdi


def boll(close, n=20, k=2.0):
    mid = rolling_sma(close, n)
    sd = pd.Series(close, dtype="float64").rolling(n).std(ddof=0).to_numpy()
    up, lo = mid + k * sd, mid - k * sd
    with np.errstate(divide="ignore", invalid="ignore"):
        width = np.where(mid > 0, (up - lo) / mid, np.nan)
        pos = np.where(up > lo, (close - lo) / (up - lo), np.nan)
    return mid, up, lo, width, pos


def vol_ratio(vol, n=5):
    """Today's volume vs the trailing n-day average (excluding today)."""
    base = pd.Series(vol, dtype="float64").shift(1).rolling(n).mean().to_numpy()
    with np.errstate(divide="ignore", invalid="ignore"):
        return np.where(base > 0, np.asarray(vol, dtype="float64") / base, np.nan)


def obv_series(close, vol):
    dir_ = np.sign(np.diff(close, prepend=close[0]))
    return np.cumsum(dir_ * np.asarray(vol, dtype="float64"))
