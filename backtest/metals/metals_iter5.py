#!/usr/bin/env python3
"""metals_iter5 — 把 iter4 的"风险档"结论拆开验：会不会只是标的构成造成的错觉。

iter4 [1]  pooled 结果说：score<=42 的日子，其后 20 日出 -5% 以上回撤的概率 29.7%，
其余 21.1%。听起来"减仓区间"在贵金属上是有用的。但这是**跨标的混合**：低分档天然
被 XPT/XPD 这类高波动、长期负收益的标的占住（它们 ATR 3%+，分数被罚 20~36 分），
所以"低分档回撤多"可能只是在说"铂钯这堆样本本身风险大"，不是同一个标的内部的预测。

A 股那一轮我已经上过一次这个当（池化出现假信号）。所以这里：
  [A] 每个标的自己跟自己比：P(MDD<=-5%/-10%) 在 <=42 / >42 两档的差，逐标的列，
      再做符号检验（13 个标的里几个为正）；同时给"同日跨标的"的版本做对照。
  [B] 日期聚类：同一天所有标的算一个观测，再对"日"做 t 与块自助（消除重叠+共同冲击）。
  [C] 分数成分分解：趋势项 / 排列项 / ATR 惩罚各自贡献多少 —— 如果 ATR 惩罚主导，
      那这套分数在贵金属上实际是波动计，不是趋势计，UI 上就不该叫"加仓区间"。
  [D] 用同一成分分解看 A 股（288 只缓存）作对照。

口径不变：出厂 score / 68 / 42 / 30 / 130 根 / 0.12%。
"""
import glob
import os
import sys

import numpy as np
import pandas as pd

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.dirname(HERE))
from metals_iter1 import KNEE, MIN_HIST, PEN, SELL, SLOPE_STRONG, boot_p, hac_t, load, nan  # noqa: E402

HS = (5, 10, 20, 60)
DATA_A = "/data/backtest/cache"


def parts_at(F, t):
    """把出厂分数拆成四项：50 基线 + 趋势 + 排列 + ATR 惩罚（与 score_at 严格同式）。"""
    c = F["close"][t]
    ma10, ma20, ma60, sl = F["ma10"][t], F["ma20"][t], F["ma60"][t], F["ma20_slope"][t]
    if nan(ma10) or nan(ma20) or nan(ma60) or nan(sl):
        return None
    up, dn = c > ma20 > ma60, c < ma20 < ma60
    if up:
        tr = 22 if sl > SLOPE_STRONG else (14 if sl > 0.0 else 6)
    elif dn:
        tr = -22 if sl < -SLOPE_STRONG else (-14 if sl < 0.0 else -6)
    else:
        tr = -3
    ar = 4 if c > ma10 > ma20 else -2
    a = F["atr_pct"][t]
    pen = 0.0 if nan(a) else -max(0.0, a - KNEE) * PEN
    return dict(base=50.0, trend=tr, arr=ar, pen=pen, atr=a)


def series(F, step=1):
    """每个交易日：score 分项 + 其后 H 日 MDD / 收益。"""
    n, c = int(F["n"]), F["close"]
    rows = []
    for t in range(MIN_HIST, n - 1, step):
        p = parts_at(F, t)
        if p is None:
            continue
        rec = {"name": F["name"], "date": str(F["date"][t]), "idx": t}
        rec.update(p)
        rec["score"] = float(np.clip(p["base"] + p["trend"] + p["arr"] + p["pen"], 0, 100))
        for H in HS:
            k = t + H
            if k >= n:
                rec[f"mdd{H}"] = np.nan
                continue
            rec[f"mdd{H}"] = c[t + 1:k + 1].min() / c[t] - 1.0
            rec[f"r{H}"] = c[k] / c[t] - 1.0
        rows.append(rec)
    return pd.DataFrame(rows)


def ashare_series(limit=None):
    out = []
    for p in sorted(glob.glob(os.path.join(DATA_A, "*.npz"))):
        z = np.load(p, allow_pickle=True)
        F = {"name": str(z["code"]), "n": int(z["n"]), "date": z["date"].astype(str),
             "close": z["close"].astype(float), "ma10": z["ma10"], "ma20": z["ma20"],
             "ma60": z["ma60"], "ma20_slope": z["ma20_slope"], "atr_pct": z["atr_pct"]}
        out.append(series(F))
    return pd.concat([x for x in out if len(x)], ignore_index=True)


def sign_summary(vals, lab):
    v = np.array(vals, float)
    v = v[np.isfinite(v)]
    if len(v) == 0:
        print(f"    {lab:<22} 无可比样本")
        return
    pos = int((v > 0).sum())
    print(f"    {lab:<22} 标的 {len(v):>3}  差值中位 {np.median(v):>+6.1f}pp  "
          f"均值 {v.mean():>+6.1f}pp  低档更危险的 {(v > 0).sum()}/{len(v)}  "
          f"符号检验p(双侧) {binom_p(pos, len(v)):.3f}")


def binom_p(k, n):
    """双侧符号检验（正态近似，n<25 时用精确二项）。"""
    from math import comb
    if n == 0:
        return float("nan")
    if n <= 30:
        lo = sum(comb(n, i) for i in range(0, k + 1)) / 2 ** n
        hi = sum(comb(n, i) for i in range(k, n + 1)) / 2 ** n
        return min(1.0, 2.0 * min(lo, hi))
    z = (abs(k - n / 2) - 0.5) / np.sqrt(n / 4.0)
    return float(2 * (1 - 0.5 * (1 + np.math.erf(z / np.sqrt(2)))))


def main():
    fs = [load(p) for p in sorted(glob.glob(os.path.join(HERE, "metals_data", "*.json")))]
    df = pd.concat([series(F) for F in fs], ignore_index=True)
    df["group"] = np.where(df.name.str.contains("XAU|AU"), "黄金",
                           np.where(df.name.str.contains("XAG|AG"), "白银", "铂钯"))

    print('metals_iter5  贵金属"减仓区间"到底是不是同一标的内部的预测 + 分数成分分解')
    print(f"报价 {len(fs)} 个，样本-日 {len(df):,}，{df.date.min()}..{df.date.max()}")
    print("=" * 104)

    # ---------- [A] 标的内部 ----------
    for H in HS:
        d = df[np.isfinite(df[f"mdd{H}"])]
        print(f"\n[A.{H}] 标的内部：其后 {H} 日 P(收盘口径 MDD <= -5% / -10%)，低档 vs 高档")
        print(f"  {'标的':<14}{'n低':>6}{'n高':>6}{'低<=5%':>8}{'高<=5%':>8}{'差':>8}"
              f"{'低<=10%':>9}{'高<=10%':>9}{'差':>8}")
        d5, d10 = [], []
        for nm, g in d.groupby("name"):
            lo, hi = g[g.score <= SELL], g[g.score > SELL]
            if len(lo) < 120 or len(hi) < 120:
                continue
            p5l, p5h = 100 * (lo[f"mdd{H}"] <= -0.05).mean(), 100 * (hi[f"mdd{H}"] <= -0.05).mean()
            p10l, p10h = 100 * (lo[f"mdd{H}"] <= -0.10).mean(), 100 * (hi[f"mdd{H}"] <= -0.10).mean()
            d5.append(p5l - p5h); d10.append(p10l - p10h)
            print(f"  {nm:<14}{len(lo):>6}{len(hi):>6}{p5l:>7.1f}%{p5h:>7.1f}%{p5l-p5h:>+7.1f}"
                  f"{p10l:>8.1f}%{p10h:>8.1f}%{p10l-p10h:>+7.1f}")
        sign_summary(d5, f"-5% 事件（H={H}）")
        sign_summary(d10, f"-10% 事件（H={H}）")

    # 同一天跨标的（对照：这才是 iter4 pooled 的近似）
    print("\n[A.c] 同一天跨标的（不区分标的本身差异，对照用）")
    for H in (20,):
        d = df[np.isfinite(df[f"mdd{H}"])]
        piv = d.pivot_table(index="date", columns="name", values=f"mdd{H}")
        scv = d.pivot_table(index="date", columns="name", values="score")
        lo_m = (scv <= SELL).to_numpy()
        hi_m = (scv > SELL).to_numpy()
        ev = (piv <= -0.05).to_numpy()
        ok = np.isfinite(piv.to_numpy())
        day_lo = np.where(ok & lo_m, ev, np.nan)
        day_hi = np.where(ok & hi_m, ev, np.nan)
        dd = (np.nanmean(day_lo, axis=1) - np.nanmean(day_hi, axis=1)) * 100
        dd = dd[np.isfinite(dd)]
        t_, m_ = hac_t(dd, H)
        # 注意：boot_p 内部已经做了平移（xc = x - obs），这里必须传原始序列；
        # 传"已中心化"的序列会让观测均值≈0、p≈0.5 —— 这是本脚本第一版踩到的坑。
        print(f"    H={H}: 有效交易日 {m_:,}  低档减高档 = {np.nanmean(dd):+.1f}pp  t_HAC {t_:+.2f}"
              f"  块自助 p {boot_p(dd, H):.4f}")

    # ---------- [B] 日期聚类：未来 20 日收益 ----------
    print("\n[B] 日期聚类：score<=42 的日子，其后 20 日收益是否更低（标的内部先去均值，再按日均值）")
    d = df[np.isfinite(df["r20"])]
    d = d.assign(dev=d.groupby("name")["r20"].transform(lambda x: x - x.mean()))
    byday = d.groupby("date").apply(
        lambda g: pd.Series({"n": len(g), "低档": (g.score <= SELL).mean(),
                             "dev_低": g.dev[g.score <= SELL].mean() * 100,
                             "dev_高": g.dev[g.score > SELL].mean() * 100})).reset_index()
    both = byday[(byday["dev_低"] == byday["dev_低"]) & (byday["dev_高"] == byday["dev_高"])]
    dif = (both["dev_低"] - both["dev_高"]).to_numpy()
    t_, m_ = hac_t(dif, 20)
    print(f"    同时有两档的日子 {m_:,}  差值均值 {dif.mean():+.3f}pp/20日  t_HAC {t_:+.2f}"
          f"  块自助 p {boot_p(dif, 20):.4f}  （负 = 低档其后更差 = 减仓建议方向正确；"
          f"p 是 H0:均值<=0 的单侧检验，正值方向时应当接近 1）")

    # ---------- [C] 成分分解 ----------
    print("\n[C] 分数成分：贵金属 vs A股（同一天同一标的的均值/标准差）")

    def decomp(x, lab):
        tot = x["score"].to_numpy(float).std()
        for col, nm in (("trend", "趋势项"), ("arr", "排列项"), ("pen", "ATR惩罚")):
            v = x[col].to_numpy(float)
            share = (v.std() / tot) if tot > 0 else float("nan")
            print(f"    {lab:<10}{nm:<8}均值 {v.mean():>7.2f}  标准差 {v.std():>6.2f}"
                  f"  标准差/总分标准差 {share:>5.2f}")
        print(f"    {lab:<10}{'合成分':<8}均值 {x['score'].mean():>7.2f}  标准差 {tot:>6.2f}")

    decomp(df[df.name.isin(["sina_XAU", "sina_AU0", "sge_Au99.99"])], "黄金类")
    decomp(df[df.name.isin(["sina_XAG", "sina_AG0", "sge_AgT+D"])], "白银类")
    decomp(df[df.name.isin(["sina_XPT", "sina_XPD", "sge_Pt99.95"])], "铂钯类")
    a = ashare_series()
    decomp(a, "A股288只")
    print(f"    A股 288 只可评分样本-日 {len(a):,}，窗口 {a.date.min()}..{a.date.max()}")

    print("\n    ATR% 分布（惩罚 = max(0, ATR%-2.0%)*200 分）")
    for lab, sub in [("黄金类", df[df.group == "黄金"]), ("白银类", df[df.group == "白银"]),
                     ("铂钯类", df[df.group == "铂钯"]), ("A股288只", a)]:
        v = sub["atr"].to_numpy(float)
        v = v[np.isfinite(v)]
        print(f"      {lab:<10} 中位 {np.median(v)*100:>5.2f}%  P75 {np.percentile(v,75)*100:>5.2f}%  "
              f"P95 {np.percentile(v,95)*100:>5.2f}%  超过2.0%的日子 {100*(v>0.02).mean():>5.1f}%  "
              f"被罚>10分的日子 {100*((v-0.02)*200>10).mean():>5.1f}%")

    # ---------- [D] 控制波动后的分数信息 ----------
    print("\n[D] 只看黄金类（同一资产、报价合并），按 ATR 分层后再看分数档 → 20 日 MDD")
    g = df[(df.group == "黄金") & np.isfinite(df["mdd20"])]
    for lo, hi, lab in [(0, 0.012, "ATR<1.2%"), (0.012, 0.02, "1.2~2.0%"), (0.02, 0.03, "2.0~3.0%"),
                        (0.03, 9, "ATR>3.0%")]:
        s = g[(g.atr >= lo) & (g.atr < hi)]
        if len(s) < 500:
            continue
        l, h = s[s.score <= SELL], s[s.score > SELL]
        if len(l) < 120 or len(h) < 120:
            continue
        print(f"    {lab:<10} n={len(s):>6,}  P(MDD<=-5%) 低档 {100*(l.mdd20<=-0.05).mean():>5.1f}%  "
              f"高档 {100*(h.mdd20<=-0.05).mean():>5.1f}%  差 {(100*(l.mdd20<=-0.05).mean()-100*(h.mdd20<=-0.05).mean()):>+6.1f}pp"
              f"   20日收益 低档 {l.r20.mean()*100:+.2f}%  高档 {h.r20.mean()*100:+.2f}%")

    df.to_pickle(os.path.join(HERE, "metals_iter5.pkl"))


if __name__ == "__main__":
    main()
