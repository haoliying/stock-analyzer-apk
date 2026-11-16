#!/usr/bin/env python3
"""metals_iter4 — 最后一关：这套分数在贵金属上能不能当"风险档"用。

iter2/iter3 已经把"择时"这条路堵死了（>=68 才持有，13/13 跑输同一标的一直持有；
阈值从 55 扫到 80 没有一档为正）。但出厂口径对用户的承诺不是 alpha，而是
「低分 = 风险高，可以减仓」。这是一句关于**下行**的话，必须单独检验，
不能拿"均值收益没差"就下结论。所以这里测三件事：

  [1] 下行预测：给定分数，其后 H 日内"最大不利波动"的分布
      —— P(MDD_H <= -5% / -10%) 随分数档怎么变；AUC（分数预测"未来 20 日回撤超 10%"）。
  [2] 只减不清的仓位变体（1.0 / 低分降到 0.5 / 0.25），对 CAGR、波动、maxDD、Calmar 的影响
      —— 这才是"减仓区间"在真实持仓里对应的动作。
  [3] 这套动作的代价：分数日内稳定性（AC1）、每年跨档次数、隐含换手与成本。

仍然一字不改：score = 50 ± 趋势结构 + 排列 − ATR 超额惩罚；BUY 68 / SELL 42 / STRONG_SELL 30；
MIN_HIST 130；成本单边 0.12%。无前视：MDD 用 t+1..t+H 的收盘价对 close(t) 计算。
"""
import glob
import os
import sys

import numpy as np
import pandas as pd

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.dirname(HERE))
from metals_iter1 import BUY, COST, MIN_HIST, SELL, STRONG_SELL, hac_t, load, score_at  # noqa: E402

HS = (5, 10, 20, 60)
TIERS = [(0, 30, "<=30 强烈减仓"), (30, 42, "30~42 减仓"),
         (42, 55, "42~55 持有"), (55, 68, "55~68 持有"), (68, 101, ">=68 加仓区间")]


def per_series(F):
    """每个交易日 t：score、其后 H 日的收益与最大不利波动（对 close(t)）。"""
    n, c = int(F["n"]), F["close"]
    recs = []
    for t in range(MIN_HIST, n):
        s, ok = score_at(F, t)
        if not ok:
            continue
        base = c[t]
        if base <= 0:
            continue
        r = {"name": F["name"], "date": str(F["date"][t]), "score": s, "idx": t}
        for H in HS:
            k = min(t + H, n - 1)
            if k <= t:
                r[f"r{H}"] = np.nan
                r[f"mdd{H}"] = np.nan
                r[f"full{H}"] = False
                continue
            seg = c[t + 1:k + 1]
            r[f"r{H}"] = c[k] / base - 1.0
            r[f"mdd{H}"] = seg.min() / base - 1.0
            r[f"full{H}"] = (t + H) < n
        recs.append(r)
    return pd.DataFrame(recs)


def auc(score, y):
    """AUC：用分数预测 y（1=发生大回撤）。 Mann-Whitney。"""
    y = np.asarray(y, bool)
    x = np.asarray(score, float)
    m = np.isfinite(x) & np.isfinite(y.astype(float))
    x, y = x[m], y[m]
    n1, n0 = int(y.sum()), int((~y).sum())
    if n1 < 20 or n0 < 20:
        return np.nan
    r = pd.Series(x).rank().to_numpy()
    return (r[y].sum() - n1 * (n1 + 1) / 2.0) / (n1 * n0)


def main():
    fs = [load(p) for p in sorted(glob.glob(os.path.join(HERE, "metals_data", "*.json")))]
    parts = [per_series(F) for F in fs]
    df = pd.concat(parts, ignore_index=True)
    print("=" * 104)
    print("metals_iter4  贵金属 × 出厂评分：下行预测 / 只减不清的仓位 / 换手代价")
    print(f"报价 {len(fs)} 个，可评分样本-日 {len(df):,}，日期 {df.date.min()}..{df.date.max()}")
    print("=" * 104)

    # ---------- [1] 下行预测 ----------
    for H in HS:
        d = df[df[f"full{H}"]]
        print(f"\n[1.{H}] 其后 {H} 日最大不利波动（对 close(t)，含当天之后所有收盘）")
        print(f"  {'分数档':<16}{'样本':>8}{'MDD均值':>9}{'P(<=-5%)':>10}{'P(<=-10%)':>11}"
              f"{'收益均值':>10}{'P(收益<0)':>11}")
        for lo, hi, lab in TIERS:
            sub = d[(d.score >= lo) & (d.score < hi)]
            if len(sub) < 200:
                continue
            print(f"  {lab:<16}{len(sub):>8,}{sub[f'mdd{H}'].mean()*100:>8.2f}%"
                  f"{100*(sub[f'mdd{H}'] <= -0.05).mean():>9.1f}%"
                  f"{100*(sub[f'mdd{H}'] <= -0.10).mean():>10.1f}%"
                  f"{sub[f'r{H}'].mean()*100:>9.2f}%{100*(sub[f'r{H}'] < 0).mean():>10.1f}%")
        for thr, lab in ((SELL, "<=42"), (STRONG_SELL, "<=30")):
            p_low = (d[f"mdd{H}"] <= -0.05)[d.score <= thr].mean()
            p_hi = (d[f"mdd{H}"] <= -0.05)[d.score > thr].mean()
            dd = (d[f"mdd{H}"] - d[f"mdd{H}"].mean())[d[f"score"] <= thr]
            t_, m_ = hac_t(dd, H)
            print(f"    低档({lab}) P(MDD<={-5}%)={100*p_low:.1f}% vs 其余 {100*p_hi:.1f}%"
                  f"  差 {100*(p_low-p_hi):+.1f}pp  低档 MDD 偏离均值 t_HAC {t_:+.2f} (n={m_:,})")

    print(f"\n[1.x] AUC：用分数预测\"未来 {HS[2]} 日收盘回撤超 10%\"（<0.5 = 分数越高越危险，方向反了）")
    for nm, gdf in df[df[f"full{HS[2]}"]].groupby("name"):
        a5 = auc(gdf["score"], gdf[f"mdd{HS[2]}"] <= -0.05)
        a10 = auc(gdf["score"], gdf[f"mdd{HS[2]}"] <= -0.10)
        print(f"    {nm:<14} AUC(-5%) {a5:.3f}   AUC(-10%) {a10:.3f}   n={len(gdf):,}")
    a5 = auc(df["score"], df[f"mdd{HS[2]}"] <= -0.05)
    a10 = auc(df["score"], df[f"mdd{HS[2]}"] <= -0.10)
    print(f"    {'全部合并':<14} AUC(-5%) {a5:.3f}   AUC(-10%) {a10:.3f}   n={len(df):,}")

    # ---------- [2] 只减不清的仓位变体 ----------
    print("\n[2] 仓位变体（日频，权重作用于 close(t+1)->close(t+2)，换手计成本）")
    variants = {
        "一直持有 1.0": lambda s: 1.0,
        "<=42 降到 0.5": lambda s: (0.5 if s <= SELL else 1.0),
        "<=42 0.5 / <=30 0.25": lambda s: (0.25 if s <= STRONG_SELL else 0.5 if s <= SELL else 1.0),
        "<=42 0.25": lambda s: (0.25 if s <= SELL else 1.0),
        ">=68 加到 1.25(不加杠杆=1.0)": lambda s: min(1.0, 1.0 if s >= BUY else (0.5 if s <= SELL else 1.0)),
    }
    rows = []
    for F in fs:
        n, c = int(F["n"]), F["close"]
        g = df[df.name == F["name"]].sort_values("idx")
        ii = g["idx"].to_numpy(int)
        sc = g["score"].to_numpy(float)
        if len(sc) < 300:
            continue
        for lab, wof in variants.items():
            w = np.array([wof(s) if np.isfinite(s) else np.nan for s in sc])
            sr, br, turn = [], [], 0.0
            prev = np.nan
            for j in range(len(w)):
                t = ii[j]
                if not np.isfinite(w[j]) or t + 2 >= n:
                    continue
                delta = 0.0 if not np.isfinite(prev) else abs(w[j] - prev)
                prev = w[j]
                turn += delta
                r = c[t + 2] / c[t + 1] - 1.0        # 信号日 t：t+1 开盘建仓，赚 t+1->t+2
                sr.append(w[j] * r - COST * delta)
                br.append(r)
            yrs = len(sr) / 244.0
            if yrs < 1.0:
                continue
            def stats(x):
                eq = np.cumprod(1 + np.array(x))
                cagr = eq[-1] ** (1 / yrs) - 1 if eq[-1] > 0 else -1.0
                vol = np.std(x, ddof=1) * np.sqrt(244)
                dd = (eq / np.maximum.accumulate(eq) - 1).min()
                return cagr, vol, dd
            cs, vs, ds = stats(sr)
            cb, vb, db = stats(br)
            rows.append(dict(name=F["name"], rule=lab, cagr=cs, vol=vs, dd=ds,
                             cagr_b=cb, vol_b=vb, dd_b=db, turn=turn / yrs))
    R = pd.DataFrame(rows)
    print(f"  {'规则':<28}{'CAGR中位':>10}{'Δvs持有':>9}{'波动中位':>10}{'Δvs持有':>9}"
          f"{'maxDD中位':>11}{'Δvs持有':>9}{'Calmar中位':>12}{'换手/年':>9}{'更好Calmar':>12}")
    for lab in variants:
        sub = R[R.rule == lab]
        if sub.empty:
            continue
        cal = (sub.cagr / -sub.dd).median()
        cal_b = (sub.cagr_b / -sub.dd_b).median()
        better = int(((sub.cagr / -sub.dd) > (sub.cagr_b / -sub.dd_b)).sum())
        print(f"  {lab:<28}{sub.cagr.median()*100:>9.1f}%{(sub.cagr-sub.cagr_b).median()*100:>+8.1f}pp"
              f"{sub.vol.median()*100:>9.1f}%{(sub.vol-sub.vol_b).median()*100:>+8.1f}pp"
              f"{sub.dd.median()*100:>10.1f}%{(sub.dd-sub.dd_b).median()*100:>+8.1f}pp"
              f"{cal:>12.2f}{sub.turn.median():>9.1f}{better:>7}/{len(sub)}")

    # ---------- [3] 分数本身的性质 ----------
    print("\n[3] 分数变化的快慢（跨档频率 = 你按建议操作时的换手）")
    for nm, gdf in df.groupby("name"):
        s = gdf.sort_values("idx")["score"].to_numpy()
        if len(s) < 200:
            continue
        ac1 = np.corrcoef(s[:-1], s[1:])[0, 1]
        d1 = np.abs(np.diff(s))
        cross = np.sum(((s[:-1] > SELL) != (s[1:] > SELL)))
        yrs = len(s) / 244.0
        print(f"    {nm:<14} AC1 {ac1:+.3f}  日变动中位 {np.median(d1):>4.1f}  "
              f"跨越68线 {int(np.sum(((s[:-1] >= BUY) != (s[1:] >= BUY))))/yrs:>5.1f}次/年  "
              f"跨越42线 {cross/yrs:>5.1f}次/年")

    print("\n[4] 分数与未来 20 日最大不利波动的秩相关（低分是否等于后来的大跌）")
    d20 = df.copy()
    for nm, gdf in d20.groupby("name"):
        rho = gdf[["score", "mdd20"]].corr(method="spearman").iloc[0, 1]
        print(f"    {nm:<14} Spearman(分数, 未来20日MDD) {rho:+.3f}")

    df.to_pickle(os.path.join(HERE, "metals_iter4.pkl"))


if __name__ == "__main__":
    main()
