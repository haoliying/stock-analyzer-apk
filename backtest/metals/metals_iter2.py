#!/usr/bin/env python3
"""metals_iter2 — 换基准：同一把尺子在 A 股和贵金属上各量一次，再做非重叠择时。

iter1 的结论里有两个必须修正的地方：
  1) "相对同一标的 buy&hold 的超额" 那段算错了：base 是按 (name,date) 分组的
     r20 均值，而每个标的每天只有一行，所以 bh == r20，超额恒等于 0。
     正确的基准是"同一标的、同一时段的无条件收益"（一直持有），这里重算。
  2) 用 A 股 parity fixture 当对照不公平：那 11 只是刻意挑的（8 只最长历史 +
     3 只最短），且多为高波动科创板，ATR 惩罚把它们整体压低了。
     这里改用 /data/backtest/cache 里的 288 只 A 股日线（2024-07..2026-11，
     与当初 A 股验证同一份缓存），并把贵金属截到同一时间窗，做同时段对照。

另外补三件 iter1 没做的事：
  - 非重叠抽样的择时（每 H 个交易日取一次点，避免 20 日窗口天天重叠把 t 值灌水）；
  - 分层仓位策略（>=68 满仓 / 42~68 半仓 / 30~42 两成 / <=30 空仓）对 CAGR、
    波动、Sharpe、最大回撤的实际影响，和一直持有对比；
  - 分年代（2007-2014 / 2015-2019 / 2020-2023 / 2024-2026）看结论是不是只是
    "黄金这几年刚好在涨"。

评分、阈值、130 根窗口、成本全部沿用出厂口径，一字未改。
"""
import glob
import os
import sys

import numpy as np
import pandas as pd

HERE = os.path.dirname(os.path.abspath(__file__))
BT = os.path.dirname(HERE)
sys.path.insert(0, HERE)
sys.path.insert(0, BT)
from metals_iter1 import (BUY, COST, MIN_HIST, PRIMARY_H, SELL, STRONG_SELL,   # noqa: E402
                          boot_p, build, hac_t, load, score_at)

DATA_A = "/data/backtest/cache"          # 288 只 A 股日线（当初 A 股验证的同一份缓存）
METAL_GROUP = {
    "黄金": ["sina_XAU", "sina_AU0", "sge_Au99.99", "sge_AuT+D", "sge_mAuT+D",
             "sge_Au99.95", "sge_Au100g"],
    "白银": ["sina_XAG", "sina_AG0", "sge_AgT+D"],
    "铂族": ["sina_XPT", "sina_XPD", "sge_Pt99.95"],
}
# 相互独立、可当作"有效标的数"的代表序列（其余是同一资产的另一种报价）
REPRESENTATIVE = ["sina_XAU", "sina_XAG", "sina_XPT", "sina_XPD"]
ERAS = [("2007-2014", "2007-01-01", "2015-01-01"),
        ("2015-2019", "2015-01-01", "2020-01-01"),
        ("2020-2023", "2020-01-01", "2024-01-01"),
        ("2024-2026", "2024-01-01", "2027-01-01")]
CONTROL_LO, CONTROL_HI = "2024-07-23", "2026-11-13"   # A 股缓存的时间窗


def group_of(name):
    for g, members in METAL_GROUP.items():
        if name in members:
            return g
    return "其他"


def ashare_rows():
    """同一把尺子跑 A 股缓存：字段名与贵金属 loader 对齐。"""
    rows = []
    for p in sorted(glob.glob(os.path.join(DATA_A, "*.npz"))):
        z = np.load(p, allow_pickle=True)
        F = {"name": str(z["code"]), "n": int(z["n"]), "date": z["date"].astype(str),
             "open": z["open"].astype(float), "close": z["close"].astype(float),
             "ma10": z["ma10"], "ma20": z["ma20"], "ma60": z["ma60"],
             "ma20_slope": z["ma20_slope"], "atr_pct": z["atr_pct"]}
        n, c, o = F["n"], F["close"], F["open"]
        for t in range(MIN_HIST, n - 1):
            s, ok = score_at(F, t)
            if not ok or o[t + 1] <= 0:
                continue
            k = t + 1 + PRIMARY_H
            if k >= n:
                continue
            rows.append({"name": F["name"], "date": str(F["date"][t]), "score": s,
                         "idx": t, "atr_pct": float(F["atr_pct"][t]),
                         f"r{PRIMARY_H}": c[k] / o[t + 1] - 1.0, "complete": True})
    return pd.DataFrame(rows)


def nonoverlap(F, weight_of, step, cost, lo=None, hi=None):
    """每 step 根取一次点：close(t) 决策 -> open(t+1) 建仓 -> close(t+1+step) 平仓。

    返回 (strat, allg)：
      strat[k] = weight * (净收益)，空仓时为 0；
      allg[k]  = 同一批点上一直持有的净收益（无条件基准，与 strat 一一对应）。
    每笔超额 = mean(strat) - mean(allg)。
    """
    n, c, o = int(F["n"]), F["close"], F["open"]
    strat, allg = [], []
    for t in range(MIN_HIST, n - 1 - step, step):
        if lo is not None and not (lo <= str(F["date"][t]) < hi):
            continue
        s, ok = score_at(F, t)
        if not ok or o[t + 1] <= 0:
            continue
        g = c[t + 1 + step] / o[t + 1] - 1.0 - cost
        allg.append(g)
        strat.append(weight_of(s) * g)
    return np.array(strat), np.array(allg)


def daily_strategy(F, weight_of, cost):
    """日频分层仓位：close(t) 定权重 w，w 作用于 close(t+1)->close(t+2) 的收益
    （中间空一天，代表你只能看到信号后的下一个开盘才动仓，无前视）。
    换手按 |Δw| 收单边成本。返回 (策略日收益, buy&hold 日收益, 年均换手)。"""
    n, c = int(F["n"]), F["close"]
    w = np.full(n, np.nan)
    for t in range(MIN_HIST, n):
        s, ok = score_at(F, t)
        w[t] = weight_of(s) if ok else np.nan
    sr, br, turns = [], [], 0.0
    prev = np.nan
    for t in range(MIN_HIST, n - 2):
        if not np.isfinite(w[t]):
            continue
        delta = 0.0 if not np.isfinite(prev) else abs(w[t] - prev)
        turns += delta
        prev = w[t]
        r = c[t + 2] / c[t + 1] - 1.0
        sr.append(w[t] * r - cost * delta)
        br.append(r)
    yrs = max(1e-9, (len(sr) or 1) / 244.0)
    return np.array(sr), np.array(br), turns / yrs


def perf(x, per_year=244):
    x = np.asarray(x, float)
    if len(x) < 12:
        return dict(n=len(x), cagr=np.nan, vol=np.nan, sharpe=np.nan, maxdd=np.nan)
    eq = np.cumprod(1.0 + x)
    yrs = len(x) / per_year
    cagr = eq[-1] ** (1.0 / yrs) - 1.0 if eq[-1] > 0 else -1.0
    vol = x.std(ddof=1) * np.sqrt(per_year)
    dd = (eq / np.maximum.accumulate(eq) - 1.0).min()
    return dict(n=len(x), cagr=cagr, vol=vol, sharpe=(x.mean() / x.std(ddof=1) * np.sqrt(per_year)
                                                     if x.std(ddof=1) > 0 else np.nan), maxdd=dd)


def main():
    files = sorted(glob.glob(os.path.join(HERE, "metals_data", "*.json")))
    fs = [load(p) for p in files]
    df = build(fs)
    df = df[np.isfinite(df[f"r{PRIMARY_H}"])].copy()     # 只要求 20 日窗口完整（iter1 因 60 日丢掉了最近 60 天）
    df["group"] = df["name"].map(group_of)
    df["net"] = df[f"r{PRIMARY_H}"] - COST
    aol = ashare_rows()
    aol["net"] = aol[f"r{PRIMARY_H}"] - COST
    if aol.empty:
        print(f"!! A 股缓存不可用：{DATA_A}")

    print("=" * 106)
    print("metals_iter2  贵金属 × 出厂评分：同时段对照 + 非重叠择时 + 分层仓位")
    print(f"贵金属 {len(fs)} 个报价 / {len(REPRESENTATIVE)} 个独立资产；"
          f"可评分样本-日 {len(df):,}；A股对照 {len(aol):,} 样本-日")
    print("=" * 106)

    # ---------- [1] 冗余度：13 个报价到底等于几个标的 ----------
    print("\n[1] 序列冗余度（日收益相关，代表序列之间）")
    rets = {}
    for F in fs:
        if F["name"] in REPRESENTATIVE or F["name"] in ("sina_AU0", "sina_AG0", "sge_AuT+D"):
            c = F["close"]
            r = pd.Series(c[1:] / c[:-1] - 1.0, index=F["date"][1:])
            rets[F["name"]] = r
    R = pd.DataFrame(rets).dropna(how="any")
    print(f"  共同交易日 {len(R):,} 天")
    print(R.corr().round(3).to_string())
    print("  → 读法（第一版这里写的是'同一资产的不同报价几乎完全相关'，不准确，已改）：")
    print("    境内两个源（SGE / 上期所）同日 0.972，确实是同一个价格的两个报价；")
    print("    但境外 XAU/XAG 与境内同日只有 0.27~0.34 —— 不是噪声，是**错一个交易日**：")
    print("    corr(XAU_t, AU0_t+1)=0.656（见 metals_iter7.out [B]）。境外按境外交易日打戳。")
    print("    '13 个报价 = 4 个独立资产'成立，依据是标的同一，不是同日相关。")
    print("    无论如何，横截面自由度只有 4，任何 pooled 横截面统计量都没意义，")
    print("    后面一律用'每个标的自己跟自己比'。")

    # ---------- [2] 同时段对照：分数分布 ----------
    print(f"\n[2] 分数分布对照（同一时间窗 {CONTROL_LO}..{CONTROL_HI}，同一把尺子）")
    m2 = df[(df.date >= CONTROL_LO) & (df.date <= CONTROL_HI)]
    m2r = m2[m2.name.isin(REPRESENTATIVE)]

    def dist(x, lab):
        x = np.asarray(x, float)
        if len(x) == 0:
            return
        print(f"  {lab:<26}{len(x):>7,}  P10 {np.percentile(x,10):>5.1f}  "
              f"中位 {np.median(x):>5.1f}  P90 {np.percentile(x,90):>5.1f}  最高 {x.max():>5.1f}"
              f"  >=68 {100*(x>=BUY).mean():>5.1f}%  42~68 {100*((x>SELL)&(x<BUY)).mean():>5.1f}%"
              f"  <=42 {100*(x<=SELL).mean():>5.1f}%  <=30 {100*(x<=STRONG_SELL).mean():>5.1f}%")

    print(f"  {'样本':<28}{'n':>8}{'':>6}{'':>6}{'':>6}{'':>6}{'':>7}{'':>8}{'':>8}{'':>8}{'':>8}")
    dist(aol["score"], "A股 288 只（同期）")
    dist(m2["score"], "贵金属 13 个报价（同期）")
    dist(m2r["score"], "贵金属 4 个独立资产（同期）")
    dist(df["score"], "贵金属 全历史 2007-2026")

    # ---------- [3] 分档 → 未来 20 日净收益，A股 vs 贵金属 同时段 ----------
    print(f"\n[3] 分数分档 → 未来 {PRIMARY_H} 日净收益%（扣 {COST*100:.2f}%），同时段对比")
    bins = [(0, 30), (30, 42), (42, 55), (55, 68), (68, 78), (78, 101)]
    hdr = f"  {'样本':<26}" + "".join(f"{f'{a}~{b}':>11}" for a, b in bins)
    print(hdr)
    for lab, src in [("A股 288 只", aol), ("贵金属(独立4)", m2r), ("贵金属(全13)", m2)]:
        line = f"  {lab:<26}"
        for a, b in bins:
            sub = src[(src.score >= a) & (src.score < b)]
            line += f"{sub['net'].mean()*100:>10.2f}%" if len(sub) >= 30 else f"{'n/a':>11}"
        print(line + f"   n={len(src):,}")

    # ---------- [4] 非重叠择时（关键：去掉 20 日窗口重叠） ----------
    print(f"\n[4] 非重叠择时（每 {PRIMARY_H} 根取一次点，close(t) 决策 / open(t+1) 建仓 / "
          f"close(t+1+{PRIMARY_H}) 平仓）")
    print(f"  {'标的':<14}{'笔数':>6}{'在场占比':>9}{'策略每笔均':>12}{'同期持有':>10}"
          f"{'每笔超额':>10}{'超额t':>8}{'胜率':>8}")
    rules = {
        ">=68 才持有": lambda s: 1.0 if s >= BUY else 0.0,
        ">42 就持有": lambda s: 1.0 if s > SELL else 0.0,
        "分层仓位": lambda s: (1.0 if s >= BUY else 0.5 if s > SELL else 0.2 if s > STRONG_SELL else 0.0),
    }
    for F in fs:
        nm = F["name"]
        st, ag = nonoverlap(F, rules[">=68 才持有"], PRIMARY_H, COST)
        if len(st) < 30:
            continue
        ex = st - ag
        on = np.mean(st != 0)
        t, _ = hac_t(ex, 1)                       # 非重叠抽样 => lag 1 足够
        p = boot_p(ex, 1)
        ps = "  p=%.3f" % p if p == p else ""
        print(f"  {nm:<14}{len(st):>6}{100*on:>8.0f}%{st.mean()*100:>11.2f}%{ag.mean()*100:>9.2f}%"
              f"{ex.mean()*100:>+9.2f}%{t:>8.2f}{100*(st>0).mean():>7.0f}%{ps}")
    print("  ↑ 每笔超额 = 该标的择时每笔收益 − 同一标的同一批点一直持有的每笔收益；"
          "t 为 Newey-West，p 为块自助（样本 <60 笔时不报 p）。")

    # 全样本合并（按独立资产，等权）
    print("\n  三种规则在各标的上的每笔超额，汇总（标的等权，非样本等权）：")
    for rname, rule in rules.items():
        exs, ons = [], []
        for F in fs:
            st, ag = nonoverlap(F, rule, PRIMARY_H, COST)
            if len(st) < 30:
                continue
            exs.append(st.mean() - ag.mean())
            ons.append(np.mean(st != 0))
        e = np.array(exs)
        print(f"    {rname:<14} 标的数 {len(e):>3}  超额中位 {np.median(e)*100:>+7.2f}%  "
              f"均值 {e.mean()*100:>+7.2f}%  为正 {(e>0).sum()}/{len(e)}  在场占比中位 {np.median(ons)*100:.0f}%")

    # ---------- [5] 日频分层仓位 vs 一直持有：风险指标 ----------
    print(f"\n[5] 日频分层仓位（>=68 满仓 / 42~68 半仓 / 30~42 两成 / <=30 空仓），成本计入换手")
    print(f"  {'标的':<14}{'CAGR策':>9}{'CAGR持':>9}{'波动策':>9}{'波动持':>9}"
          f"{'Sharpe策':>10}{'Sharpe持':>10}{'maxDD策':>10}{'maxDD持':>10}{'换手/年':>9}")
    rows5 = []
    for F in fs:
        sr, br, to = daily_strategy(F, rules["分层仓位"], COST)
        if len(sr) < 60:
            continue
        ps, pb = perf(sr), perf(br)
        rows5.append((F["name"], ps, pb, to))
        print(f"  {F['name']:<14}{ps['cagr']*100:>8.1f}%{pb['cagr']*100:>8.1f}%"
              f"{ps['vol']*100:>8.1f}%{pb['vol']*100:>8.1f}%{ps['sharpe']:>10.2f}{pb['sharpe']:>10.2f}"
              f"{ps['maxdd']*100:>9.1f}%{pb['maxdd']*100:>9.1f}%{to:>9.1f}")
    if rows5:
        print(f"  {'中位(13个报价)':<14}"
              f"{np.median([r[1]['cagr'] for r in rows5])*100:>8.1f}%"
              f"{np.median([r[2]['cagr'] for r in rows5])*100:>8.1f}%"
              f"{np.median([r[1]['vol'] for r in rows5])*100:>8.1f}%"
              f"{np.median([r[2]['vol'] for r in rows5])*100:>8.1f}%"
              f"{np.median([r[1]['sharpe'] for r in rows5]):>10.2f}"
              f"{np.median([r[2]['sharpe'] for r in rows5]):>10.2f}"
              f"{np.median([r[1]['maxdd'] for r in rows5])*100:>9.1f}%"
              f"{np.median([r[2]['maxdd'] for r in rows5])*100:>9.1f}%"
              f"{np.median([r[3] for r in rows5]):>9.1f}")
        print(f"  Sharpe 高于一直持有的标的数：{sum(1 for r in rows5 if r[1]['sharpe'] > r[2]['sharpe'])}"
              f"/{len(rows5)}；maxDD 更浅的：{sum(1 for r in rows5 if r[1]['maxdd'] > r[2]['maxdd'])}/{len(rows5)}")

    # ---------- [6] 分年代：结论是不是"黄金这几年刚好在涨" ----------
    print(f"\n[6] 分年代择时（>=68 才持有，20 日非重叠；超额 = 每笔策略 − 每笔无条件）")
    print(f"  {'年代':<12}{'标的':<12}{'笔数':>6}{'在场%':>7}{'策略每笔':>10}{'无条件':>9}"
          f"{'超额':>9}{'t':>7}")
    for era, lo, hi in ERAS:
        for F in fs:
            if F["name"] not in REPRESENTATIVE:
                continue
            st, ag = nonoverlap(F, rules[">=68 才持有"], PRIMARY_H, COST, lo, hi)
            if len(st) < 15 or len(ag) < 15:
                continue
            ex = st - ag
            t, _ = hac_t(ex, 1)
            print(f"  {era:<12}{F['name']:<12}{len(st):>6}{100*np.mean(st != 0):>6.0f}%"
                  f"{st.mean()*100:>9.2f}%{ag.mean()*100:>8.2f}%{ex.mean()*100:>+8.2f}%{t:>7.2f}")

    # ---------- [7] 阈值扫描：68/42 在贵金属上是不是"恰好合适" ----------
    print(f"\n[7] 阈值扫描（4 个独立资产、20 日非重叠、标的等权每笔超额中位%，单边成本 0.12%）")
    print("    样本内 = 2007-2019，样本外 = 2020-2026；出厂值用 * 标出。")
    print("    注意：扫描本身是在挑参数，样本外那一列才有参考价值，样本内那一列好看不能算数。")
    fs4 = [F for F in fs if F["name"] in REPRESENTATIVE]
    WINS = [("2007-2019", "2007-01-01", "2020-01-01"), ("2020-2026", "2020-01-01", "2027-01-01"),
            ("全历史", None, None)]
    print(f"  {'加仓线':>8}" + "".join(f"{lab:>14}" for lab, _, _ in WINS))
    for th in (55, 60, 65, 68, 70, 72, 76, 80):
        line = f"  {str(th) + ('*' if th == BUY else ''):>8}"
        for _, lo, hi in WINS:
            exs = []
            for F in fs4:
                st, ag = nonoverlap(F, lambda s, th=th: 1.0 if s >= th else 0.0,
                                    PRIMARY_H, COST, lo, hi)
                if len(st) >= 15:
                    exs.append(st.mean() - ag.mean())
            line += f"{np.median(exs)*100:>+12.2f}%" if exs else f"{'n/a':>14}"
        print(line)
    print(f"  {'减仓线':>8}（<=该值空仓，其余满仓）")
    for th in (25, 30, 35, 42, 50, 55):
        line = f"  {str(th) + ('*' if th == SELL else ''):>8}"
        for _, lo, hi in WINS:
            exs = []
            for F in fs4:
                st, ag = nonoverlap(F, lambda s, th=th: 0.0 if s <= th else 1.0,
                                   PRIMARY_H, COST, lo, hi)
                if len(st) >= 15:
                    exs.append(st.mean() - ag.mean())
            line += f"{np.median(exs)*100:>+12.2f}%" if exs else f"{'n/a':>14}"
        print(line)
    print(f"  {'持有满仓':>8}（基准，等价于不做任何择时）" + "".join(f"{0.0:>+13.2f}%" for _ in WINS))

    # ---------- [8] 减仓档有没有用 ----------
    print(f"\n[8] 减仓档有效性：score<=42 / <=30 之后的 20 日，是否真跑输同一标的无条件收益")
    print(f"  {'标的':<14}{'档':<8}{'n':>7}{'该档均':>9}{'无条件均':>10}{'差':>9}{'t':>7}")
    for nm, gdf in df.groupby("name"):
        base = gdf[f"r{PRIMARY_H}"].mean()
        for lab, sub in [("<=42", gdf[gdf.score <= SELL]), ("<=30", gdf[gdf.score <= STRONG_SELL])]:
            if len(sub) < 60:
                continue
            d = (sub[f"r{PRIMARY_H}"] - base) * 100
            t, m = hac_t(d, PRIMARY_H)
            print(f"  {nm:<14}{lab:<8}{m:>7}{sub[f'r{PRIMARY_H}'].mean()*100:>8.2f}%{base*100:>9.2f}%"
                  f"{d.mean():>+8.2f}%{t:>7.2f}")

    # ---------- [9] 成本敏感性（贵金属的真实成本区间） ----------
    print(f"\n[9] 成本敏感性：>=68 才持有、20 日非重叠、标的等权每笔超额中位%")
    for cst in (0.0002, 0.0005, COST, 0.0030, 0.0060):
        exs = []
        for F in fs:
            st, ag = nonoverlap(F, rules[">=68 才持有"], PRIMARY_H, cst)
            if len(st) >= 30:
                exs.append(st.mean() - ag.mean())
        e = np.array(exs)
        print(f"    单边 {cst*100:>4.2f}%  标的 {len(e):>2}  超额中位 {np.median(e)*100:>+7.2f}%  "
              f"为正 {(e > 0).sum()}/{len(e)}")

    df.to_pickle(os.path.join(HERE, "metals_iter2.pkl"))


if __name__ == "__main__":
    main()
