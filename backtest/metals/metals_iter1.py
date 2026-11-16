#!/usr/bin/env python3
"""metals_iter1 — 贵金属能不能用 A 股那套评分出"加仓/减仓"建议？

口径与 A 股那轮完全对齐（`backtest/final_validate.py` 的 shipped config）：
  score = 50 + 趋势结构(±6/14/22, 由 ma20_slope 与 0.015 比较) + (c>ma10>ma20 ? +4 : -2)
          - max(0, atr_pct - 0.020) * 200, clip 0..100
  中性（既不是 c>ma20>ma60 也不是 c<ma20<ma60）再 -3
  历史窗口 130 根（MIN_HIST，与 App 的 BARS_FULL/BARS_MIN 同一族常量）
  事件：信号在 close(t)，open(t+1) 建仓，持有 H 个交易日，close(t+1+H) 平仓，扣单边成本

与 A 股那轮不同的是**基准**：贵金属这一类只有 7~13 个标的，而且彼此高度相关
（金/银/铂/钯 + 同一黄金的 4 种报价），"当日全样本前 50% 分位池"这种横截面统计量
在这个样本量上没有意义。所以这里的主检验不是横截面分位，而是：
  (1) 阈值可达性：68 / 42 这两条线在贵金属上到底能不能被走到；
  (2) 分档单调性：分数越高，未来 20 日收益是否越高（同一标的自身比较）；
  3) 择时对比：score>=68 才持有 vs score>=42 就持有 vs 一直持有（buy & hold）；
  4) 样本内/外分割（70% 日期处切），以及换手成本敏感性。

没有前视：所有指标只用 t 及之前；建仓在 t+1 开盘。
"""
import glob
import json
import os
import sys

import numpy as np
import pandas as pd

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))                 # backtest/indicators.py
from indicators import rolling_sma, atr_series            # noqa: E402

DATA = os.path.join(HERE, "metals_data")

# --- shipped config（与 StockAnalyzer.kt 一字对齐） ---
KNEE, PEN = 0.020, 200.0
SLOPE_STRONG = 0.015
BUY, SELL, STRONG_SELL = 68.0, 42.0, 30.0
MIN_HIST = 130
COST = 0.0012
HORIZONS = (5, 10, 20, 60)
PRIMARY_H = 20
COST_SENS = (0.0012, 0.0030)


def load(path):
    d = json.load(open(path, encoding="utf-8"))
    r = pd.DataFrame(d["rows"])
    c = r["close"].to_numpy(float)
    h = r["high"].to_numpy(float)
    l = r["low"].to_numpy(float)
    F = {
        "name": d["name"], "unit": d["unit"], "source": d["source"],
        "date": r["date"].to_numpy(str), "open": r["open"].to_numpy(float),
        "close": c, "high": h, "low": l, "n": len(c),
    }
    F["ma10"] = rolling_sma(c, 10)
    F["ma20"] = rolling_sma(c, 20)
    F["ma60"] = rolling_sma(c, 60)
    m20 = F["ma20"]
    slope = np.full(len(c), np.nan)
    with np.errstate(divide="ignore", invalid="ignore"):
        slope[10:] = m20[10:] / m20[:-10] - 1.0          # == precompute.pct_change(ma20, 10)
    slope[~np.isfinite(slope)] = np.nan
    F["ma20_slope"] = slope
    atr = atr_series(h, l, c)
    with np.errstate(divide="ignore", invalid="ignore"):
        F["atr_pct"] = np.where(c > 0, atr / c, np.nan)
    return F


def nan(x):
    return x is None or (isinstance(x, float) and np.isnan(x))


def score_at(F, t):
    """Shipped score, identical arithmetic to final_validate.new_fn / StockAnalyzer.kt."""
    c = F["close"][t]
    ma10, ma20, ma60, sl = F["ma10"][t], F["ma20"][t], F["ma60"][t], F["ma20_slope"][t]
    if nan(ma10) or nan(ma20) or nan(ma60) or nan(sl):
        return 0.0, False
    s = 50.0
    up = c > ma20 > ma60
    dn = c < ma20 < ma60
    if up:
        s += 22 if sl > SLOPE_STRONG else (14 if sl > 0.0 else 6)
    elif dn:
        s -= 22 if sl < -SLOPE_STRONG else (14 if sl < 0.0 else 6)
    else:
        s -= 3
    s += 4 if c > ma10 > ma20 else -2
    a = F["atr_pct"][t]
    if not nan(a):
        s -= max(0.0, a - KNEE) * PEN
    return float(np.clip(s, 0, 100)), True


def build(fs):
    """Rows: (name, date, score, {H: net forward return})."""
    rows = []
    for F in fs:
        n, c, o = int(F["n"]), F["close"], F["open"]
        for t in range(MIN_HIST, n - 1):
            s, ok = score_at(F, t)
            if not ok:
                continue
            eo = o[t + 1]
            if eo <= 0:
                continue
            a = F["atr_pct"][t]
            rec = {"name": F["name"], "date": str(F["date"][t]), "score": s, "idx": t,
                   "fname": F["name"], "atr_pct": np.nan if nan(a) else float(a)}
            bad = False
            for H in HORIZONS:
                k = t + 1 + H
                if k >= n:
                    bad = True
                    rec[f"r{H}"] = np.nan
                else:
                    rec[f"r{H}"] = c[k] / eo - 1.0
            rec["complete"] = not bad
            rows.append(rec)
    return pd.DataFrame(rows)


def hac_t(x, lag):
    """Newey-West (HAC) t-stat of the mean of x."""
    x = np.asarray(x, float)
    x = x[np.isfinite(x)]
    m = len(x)
    if m < 30:
        return float("nan"), m
    mu = x.mean()
    e = x - mu
    s = float(np.dot(e, e) / m)                      # gamma0
    for j in range(1, lag + 1):
        w = 1.0 - j / (lag + 1.0)
        s += 2.0 * w * float(np.dot(e[j:], e[:-j]) / m)
    se = np.sqrt(max(s, 1e-18) / m)
    return mu / se, m


def boot_p(x, lag, draws=4000, seed=7):
    """Moving-block bootstrap p for H0: mean <= 0 (one-sided), block = lag*2."""
    x = np.asarray(x, float)
    x = x[np.isfinite(x)]
    m = len(x)
    if m < 60:
        return float("nan")
    rng = np.random.default_rng(seed)
    bl = max(2, lag * 2)
    nb = int(np.ceil(m / bl))
    obs = x.mean()
    xc = x - obs
    cnt = 0
    for _ in range(draws):
        st = rng.integers(0, m - bl + 1, nb)
        samp = np.concatenate([xc[s:s + bl] for s in st])[:m]
        if samp.mean() >= obs:
            cnt += 1
    return (cnt + 1) / (draws + 1)


def tier_of(s):
    if s >= BUY:
        return "加仓线以上(>=68)"
    if s > SELL:
        return "持有(42~68)"
    if s > STRONG_SELL:
        return "减仓(30~42)"
    return "强烈减仓(<=30)"


def main():
    files = sorted(glob.glob(os.path.join(DATA, "*.json")))
    fs = [load(p) for p in files]
    df = build(fs)
    df = df[df["complete"]].copy()
    df["tier"] = df["score"].map(tier_of)

    print("=" * 100)
    print("metals_iter1  贵金属 × A股口径评分  首批结果")
    print(f"标的 {len(fs)} 个；可评分样本-日 {len(df):,}；"
          f"日期 {df['date'].min()} .. {df['date'].max()}")
    print("评分口径：KNEE 0.020 / PEN 200 / SLOPE_STRONG 0.015 / 阈值 68-42-30 / MIN_HIST 130 / 成本 0.12%")
    print("=" * 100)

    # ---------- (1) 阈值可达性 ----------
    print("\n[1] 阈值可达性：这两条线在贵金属上走得到吗")
    print(f"  {'标的':<14}{'n':>7}{'P10':>7}{'中位':>7}{'P90':>8}{'最高':>7}"
          f"{'>=68':>8}{'>=68条':>8}{'42~68':>8}{'<=42':>8}{'<=30':>8}{'ATR%中位':>10}")
    for nm, gdf in df.groupby("name"):
        sc = gdf["score"].to_numpy()
        ap = gdf["atr_pct"].to_numpy()
        print(f"  {nm:<14}{len(sc):>7}{np.percentile(sc,10):>7.1f}{np.median(sc):>7.1f}"
              f"{np.percentile(sc,90):>8.1f}{sc.max():>7.1f}"
              f"{100*(sc>=BUY).mean():>7.1f}%{(sc>=BUY).sum():>8}"
              f"{100*((sc>SELL)&(sc<BUY)).mean():>7.1f}%"
              f"{100*(sc<=SELL).mean():>7.1f}%{100*(sc<=STRONG_SELL).mean():>7.1f}%"
              f"{100*np.nanmedian(ap):>10.2f}")
    sc = df["score"].to_numpy()
    print(f"  {'全部合并':<13}{len(sc):>7}{np.percentile(sc,10):>7.1f}{np.median(sc):>7.1f}"
          f"{np.percentile(sc,90):>8.1f}{sc.max():>7.1f}"
          f"{100*(sc>=BUY).mean():>7.1f}%{(sc>=BUY).sum():>8}"
          f"{100*((sc>SELL)&(sc<BUY)).mean():>7.1f}%"
          f"{100*(sc<=SELL).mean():>7.1f}%{100*(sc<=STRONG_SELL).mean():>7.1f}%"
          f"{100*np.nanmedian(df['atr_pct'].to_numpy()):>10.2f}")

    # A 股对照组：直接取 App 的 parity fixture（11 只 A 股、含 8 只最长历史），
    # expected[i] = 第 i 根的 [score, advice]，与 App 逐字节一致；只取已满 130 根的。
    fx = os.path.join(os.path.dirname(os.path.dirname(HERE)), "app", "src", "test", "resources",
                      "engine_parity.json")
    if os.path.exists(fx):
        z = json.load(open(fx, encoding="utf-8"))
        ash = np.array([e[0] for s in z["stocks"] for i, e in enumerate(s["expected"])
                        if i >= MIN_HIST], dtype=float)
        print(f"\n  对照（A股 parity fixture，{len(ash):,} 个已评分 bar，非随机样本、仅 11 只）："
              f"中位 {np.median(ash):.1f}，>=68 占 {100*(ash>=BUY).mean():.1f}%，"
              f"42~68 占 {100*((ash>SELL)&(ash<BUY)).mean():.1f}%，"
              f"<=42 占 {100*(ash<=SELL).mean():.1f}%，<=30 占 {100*(ash<=STRONG_SELL).mean():.1f}%")

    # ---------- (2) 分档 vs 未来收益（同一标的自身比较） ----------
    print(f"\n[2] 分数分档 → 未来 {PRIMARY_H} 日净收益（扣成本后），按标的各自统计")
    df["net"] = df[f"r{PRIMARY_H}"] - COST
    hdr = f"  {'标的':<14}{'样本':>6}"
    bins = [(0, 30), (30, 42), (42, 55), (55, 68), (68, 78), (78, 101)]
    hdr += "".join(f"{f'{a}~{b}':>10}" for a, b in bins)
    print(hdr)
    for nm, gdf in df.groupby("name"):
        line = f"  {nm:<14}{len(gdf):>6}"
        for a, b in bins:
            sub = gdf[(gdf.score >= a) & (gdf.score < b)]["net"] * 100
            line += f"{sub.mean():>9.2f}%" if len(sub) >= 12 else f"{'n/a':>10}"
        print(line)

    print("\n  合并所有标的（同一标的内部比较才有意义，这里只是给个总览）：")
    for a, b in bins:
        sub = df[(df.score >= a) & (df.score < b)]["net"] * 100
        if len(sub) < 30:
            continue
        t, m = hac_t(sub, PRIMARY_H)
        print(f"    {a:>3}~{b:<4} n={m:>6}  日均收益 {sub.mean():+.3f}%  t_HAC {t:+.2f}")

    # 秩相关：分数与未来收益（每个标的内部，Spearman）
    print("\n  分数 vs 未来收益的 Spearman 秩相关（标的内部）：")
    for nm, gdf in df.groupby("name"):
        rho = gdf[["score", "net"]].corr(method="spearman").iloc[0, 1]
        t, m = hac_t(gdf["net"], PRIMARY_H)
        print(f"    {nm:<14} rho {rho:+.3f}  (n={m:,})")

    # ---------- (3) 择时：三种持仓规则 ----------
    print(f"\n[3] 择时对比（{PRIMARY_H} 日持有期口径下的等效年化，日频重叠样本用非重叠抽样）")
    print(f"  {'规则':<26}{'样本':>7}{'日均净收益':>12}{'t_HAC':>8}{'块自助p':>10}{'胜率':>8}")
    rules = [
        ("一直持有（buy&hold）", df),
        ("score>=68 才持有", df[df.score >= BUY]),
        ("score>42 就持有（含持有档）", df[df.score > SELL]),
        ("score<=42 才空仓/减仓", df[df.score <= SELL]),
        ("score<=30（强烈减仓档）", df[df.score <= STRONG_SELL]),
    ]
    for nm, sub in rules:
        if len(sub) < 30:
            print(f"  {nm:<26}{len(sub):>7}  样本太少")
            continue
        x = sub["net"] * 100
        t, m = hac_t(x, PRIMARY_H)
        p = boot_p(x, PRIMARY_H)
        print(f"  {nm:<26}{m:>7}{x.mean():>+11.3f}%{t:>8.2f}{p:>10.3f}"
              f"{100*(x>0).mean():>7.1f}%")

    # 相对基准：与"同一标的同期一直持有"的差
    print("\n  相对同一标的 buy&hold 的超额（按日对齐，标的等权）：")
    base = df.groupby(["name", "date"])[f"r{PRIMARY_H}"].mean().rename("bh")
    w = df.merge(base, on=["name", "date"])
    for nm, mask in [("score>=68", w.score >= BUY), ("score>42", w.score > SELL),
                     ("score<=42", w.score <= SELL)]:
        d = ((w[f"r{PRIMARY_H}"] - w["bh"]) * 100)[mask]
        if len(d) < 30:
            continue
        t, m = hac_t(d, PRIMARY_H)
        print(f"    {nm:<12} n={m:>6}  日均超额 {d.mean():+.3f}%  t_HAC {t:+.2f}  "
              f"块自助p {boot_p(d, PRIMARY_H):.3f}")

    # ---------- (4) 多周期 + 样本内外 ----------
    print(f"\n[4] 持有期敏感性（全部标的合并，日均净收益%，扣 0.12% 成本）")
    print(f"  {'档位':<18}" + "".join(f"{f'H={H}':>10}" for H in HORIZONS))
    for a, b, lab in [(BUY, 101, ">=68"), (SELL, BUY, "42~68"),
                      (STRONG_SELL, SELL, "30~42"), (0, STRONG_SELL, "<=30")]:
        line = f"  {lab:<18}"
        for H in HORIZONS:
            sub = (df[(df.score > a) & (df.score <= b)] if a > 0
                   else df[df.score <= b])[f"r{H}"] - COST
            line += f"{sub.mean()*100:>+9.2f}%" if len(sub) >= 30 else f"{'n/a':>10}"
        print(line)

    dates = sorted(df["date"].unique())
    cut = dates[int(len(dates) * 0.7)]
    print(f"\n[5] 样本内 / 样本外（切点 {cut}，与 A 股那轮同一 70% 规则）")
    for lab, sub in [("样本内", df[df.date < cut]), ("样本外", df[df.date >= cut])]:
        for nm, mask in [("score>=68", sub.score >= BUY), ("score<=42", sub.score <= SELL)]:
            x = (sub[mask]["r%d" % PRIMARY_H] - COST) * 100
            if len(x) < 30:
                print(f"    {lab} {nm:<12} n={len(x)}  样本太少")
                continue
            t, m = hac_t(x, PRIMARY_H)
            print(f"    {lab} {nm:<12} n={m:>6}  日均净收益 {x.mean():+.3f}%  t_HAC {t:+.2f}")

    print(f"\n[6] 成本敏感性（score>=68 档，日均净收益%）")
    for cst in COST_SENS:
        x = (df[df.score >= BUY][f"r{PRIMARY_H}"] - cst) * 100
        t, m = hac_t(x, PRIMARY_H)
        print(f"    成本 {cst*100:.2f}%  n={m}  {x.mean():+.3f}%  t_HAC {t:+.2f}")

    # ---------- (7) 每个标的各自的阈值可达性与最高档表现 ----------
    print(f"\n[7] 只看黄金类（XAU/AU0/Au99.99/Au(T+D)/mAu(T+D)）与只看白银类（XAG/AG0/Ag(T+D)）")
    groups = {
        "黄金类": ["sina_XAU", "sina_AU0", "sge_Au99.99", "sge_AuTD", "sge_mAuTD"],
        "白银类": ["sina_XAG", "sina_AG0", "sge_AgTD"],
        "铂钯类": ["sina_XPT", "sina_XPD", "sge_Pt99.95"],
    }
    for gname, members in groups.items():
        sub = df[df.name.isin(members)]
        if sub.empty:
            continue
        hi = sub[sub.score >= BUY]
        x = (hi[f"r{PRIMARY_H}"] - COST) * 100
        t, m = hac_t(x, PRIMARY_H)
        bh = (sub[f"r{PRIMARY_H}"] - COST) * 100
        tb, mb = hac_t(bh, PRIMARY_H)
        print(f"    {gname:<6} n={len(sub):>6}  buy&hold 日均 {bh.mean():+.3f}% (t {tb:+.2f})"
              f" | >=68 档 n={m:>5}（占 {100*m/len(sub):.1f}%）日均 {x.mean():+.3f}% (t {t:+.2f})")

    df.to_pickle(os.path.join(HERE, "metals_iter1.pkl"))
    print(f"\n样本已存 {os.path.join(HERE,'metals_iter1.pkl')}，后续 iter 直接读。")


if __name__ == "__main__":
    main()
