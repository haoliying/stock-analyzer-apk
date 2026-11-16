#!/usr/bin/env python3
"""metals_iter8 — 决策文档（README.md）里每一个"计数型"结论的对账脚本。

起因：README 第一版有三处凭印象写的数字（"13 个里只有 5 个 ≥68 档更好"、"四个年代每个资产全负"、
"同一资产的不同报价几乎完全相关"），前两处数错了、第三处以偏概全。凡是文档里写"X/Y 个"的句子，
都在这里重算一遍并落到 metals_iter8.out，不再靠记忆。

不引入任何新口径：分档、成本、持有期、择时协议、A 股对照全部直接复用 iter1/iter2 的函数。
"""
import glob
import os
import sys

import numpy as np
import pandas as pd

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
sys.path.insert(0, HERE)

from metals_iter1 import (BUY, COST, MIN_HIST, PRIMARY_H, SELL, STRONG_SELL,  # noqa: E402
                          build, load, score_at)
from metals_iter2 import (CONTROL_HI, CONTROL_LO, ERAS, REPRESENTATIVE,  # noqa: E402
                          ashare_rows, nonoverlap)

BK = [(0, 30, "0~30"), (30, 42, "30~42"), (42, 55, "42~55"), (55, 68, "55~68"), (68, 101, "68~78")]


def bucket(s):
    for lo, hi, lab in BK:
        if lo <= s < hi or (lab == "68~78" and s >= 68):
            return lab
    return None


def add(df):
    d = df.copy()
    d = d[np.isfinite(d[f"r{PRIMARY_H}"])].copy()      # 只要 20 日窗口完整的样本
    d["net"] = d[f"r{PRIMARY_H}"] - COST
    d["bk"] = d["score"].map(bucket)
    return d[d["bk"].notna()]


def per_name(d, col="net"):
    return d.pivot_table(index="name", columns="bk", values=col, aggfunc="mean")


def main():
    fs = [load(p) for p in sorted(glob.glob(os.path.join(HERE, "metals_data", "*.json")))]
    df = add(build(fs))
    aol = add(ashare_rows())
    labs = [b[2] for b in BK]

    print("=" * 104)
    print("metals_iter8  README 数字对账：所有 'X/Y 个标的' 的结论在这里重算")
    print(f"贵金属 {len(fs)} 报价 / {len(REPRESENTATIVE)} 独立资产；样本-日 {len(df):,}；"
          f"A股对照 {len(aol):,}")
    print("=" * 104)

    # ---------- [1] 分档排序：高到底比低好吗，乱在哪一段 ----------
    for tag, d in [("贵金属 13 报价（全历史）", df),
                   (f"贵金属 13 报价（同时窗 {CONTROL_LO}..{CONTROL_HI}）",
                    df[(df.date >= CONTROL_LO) & (df.date <= CONTROL_HI)]),
                   ("贵金属 4 独立资产（全历史）", df[df.name.isin(REPRESENTATIVE)]),
                   ("贵金属 4 独立资产（同时窗）",
                    df[(df.name.isin(REPRESENTATIVE)) & (df.date >= CONTROL_LO) & (df.date <= CONTROL_HI)]),
                   ("A股（同时窗）", aol)]:
        p = per_name(d)
        p = p.dropna(how="all")
        print(f"\n[1] {tag}：逐标的分档 20 日净收益%（扣 {COST*100:.2f}%）")
        if len(p) <= 16:                                 # A股 288 只不逐行打印
            print("    " + f"{'标的':<14}" + "".join(f"{l:>9}" for l in labs) + f"{'最高档':>9}")
            for nm, row in p.iterrows():
                print("    " + f"{nm:<14}" + "".join(f"{row[l]*100:>8.2f}%" if np.isfinite(row[l])
                                                     else f"{'n/a':>9}" for l in labs)
                      + f"{row.idxmax():>9}")
        hi = p["68~78"]
        print("    计数：68~78 高于 42~55 的标的 %d/%d；高于 55~68 %d/%d；高于 30~42 %d/%d；高于 0~30 %d/%d"
              % ((hi > p['42~55']).sum(), len(p), (hi > p['55~68']).sum(), len(p),
                 (hi > p['30~42']).sum(), len(p), (hi > p['0~30']).sum(), len(p)))
        print("    计数：68~78 不是最高档的标的 %d/%d"
              % (int((p.idxmax(axis=1) != "68~78").sum()), len(p)))
        print("    计数：低档绝对收益为正（net>0）的标的 —— 0~30 档 %d/%d，30~42 档 %d/%d"
              % (int((p['0~30'] > 0).sum()), len(p), int((p['30~42'] > 0).sum()), len(p)))
        print("    合并（仅对照，pooled 混了标的基线）：" + "  ".join(
            f"{l} {d[d.bk==l]['net'].mean()*100:+.2f}%(n={int((d.bk==l).sum())})" for l in labs))

    # ---------- [2] 年代 × 独立资产：择时超额的符号统计 ----------
    print("\n[2] >=68 才持有、20 日非重叠、每笔超额（标的 vs 自己一直持有）的年代符号统计")
    rows = []
    for era, lo, hi in ERAS:
        for F in fs:
            if F["name"] not in REPRESENTATIVE:
                continue
            st, ag = nonoverlap(F, lambda s: 1.0 if s >= BUY else 0.0, PRIMARY_H, COST, lo, hi)
            if len(st) < 15 or len(ag) < 15:
                continue
            rows.append((era, F["name"], len(st), (st - ag).mean() * 100))
    r = pd.DataFrame(rows, columns=["era", "name", "n", "excess"])
    print(f"  {'年代':<12}{'标的':<12}{'笔数':>6}{'每笔超额':>10}")
    for _, x in r.iterrows():
        print(f"  {x.era:<12}{x['name']:<12}{int(x.n):>6}{x.excess:>+9.2f}%")
    neg = int((r.excess < 0).sum())
    print(f"  → {len(r)} 个'年代×资产'组合里 {neg} 个为负、{len(r)-neg} 个为正；"
          f"为正的：{[(x.era, x['name'], round(x.excess,2)) for _, x in r.iterrows() if x.excess > 0]}")
    print(f"  → 说'四个年代每个独立资产全负'是错的，正确说法是 {neg}/{len(r)}。")

    # ---------- [3] 全历史逐标的择时符号（复核 0/13） ----------
    print("\n[3] >=68 才持有、全历史、逐标的每笔超额（复核 '0/13 为正'）")
    ex = []
    for F in fs:
        st, ag = nonoverlap(F, lambda s: 1.0 if s >= BUY else 0.0, PRIMARY_H, COST)
        if len(st) >= 15:
            ex.append((F["name"], len(st), 100 * np.mean(st != 0), st.mean() * 100,
                       ag.mean() * 100, (st - ag).mean() * 100))
    print(f"  {'标的':<14}{'笔数':>6}{'在场%':>7}{'策略':>8}{'持有':>8}{'超额':>9}")
    for nm, n, on, s, a, e in ex:
        print(f"  {nm:<14}{n:>6}{on:>6.0f}%{s:>+7.2f}%{a:>+7.2f}%{e:>+8.2f}%")
    print(f"  → 为正 {sum(1 for x in ex if x[5] > 0)}/{len(ex)}，中位 {np.median([x[5] for x in ex]):+.2f}%")

    # ---------- [4] AUC 计数（复核 '0.45~0.59 / 9 个在 0.5 附近或反向'） ----------
    print("\n[4] AUC：分数预测'未来 20 日内收盘回撤超过阈值'（0.5=无区分能力，<0.5=方向反）")
    p4 = pd.read_pickle(os.path.join(HERE, "metals_iter4.pkl"))
    for thr, col in ((-0.05, "mdd20"), (-0.10, "mdd20")):
        vals = {}
        for nm, g in p4.groupby("name"):
            g = g[np.isfinite(g["score"]) & np.isfinite(g[col])]
            if len(g) < 300:
                continue
            y = (g[col] <= thr).to_numpy()
            s = g["score"].to_numpy()
            if y.sum() < 30 or (~y).sum() < 30:
                continue
            rk = pd.Series(s).rank().to_numpy()
            auc = (rk[y].sum() - y.sum() * (y.sum() + 1) / 2) / (y.sum() * (~y).sum())
            vals[nm] = auc
        v = np.array(list(vals.values()))
        print(f"  阈值 {thr*100:.0f}%  事件数足够 {len(v)} 个标的  范围 {v.min():.3f}~{v.max():.3f}"
              f"  中位 {np.median(v):.3f}  <0.5 的 {int((v < 0.5).sum())}/{len(v)}"
              f"  在 0.45~0.55 的 {int(((v >= .45) & (v <= .55)).sum())}/{len(v)}")

    # ---------- [5] 阈值 / 减仓线 / 成本的符号计数 ----------
    print("\n[5] 符号计数复核（4 独立资产，标的等权每笔超额中位）")
    fs4 = [F for F in fs if F["name"] in REPRESENTATIVE]
    WINS = [("2007-2019", "2007-01-01", "2020-01-01"), ("2020-2026", "2020-01-01", "2027-01-01"),
            ("全历史", None, None)]
    for th in (55, 60, 65, 68, 70, 72, 76, 80):
        line = f"  加仓>={th}"
        for lab, lo, hi in WINS:
            e = []
            for F in fs4:
                st, ag = nonoverlap(F, lambda s, th=th: 1.0 if s >= th else 0.0,
                                    PRIMARY_H, COST, lo, hi)
                if len(st) >= 15:
                    e.append((st - ag).mean() * 100)
            line += f"   {lab}: 中位 {np.median(e):+.2f}% 为正 {sum(1 for x in e if x > 0)}/{len(e)}"
        print(line)
    for th in (25, 30, 35, 42, 50, 55):
        e = []
        for F in fs4:
            st, ag = nonoverlap(F, lambda s, th=th: 0.0 if s <= th else 1.0, PRIMARY_H, COST)
            if len(st) >= 15:
                e.append((st - ag).mean() * 100)
        print(f"  减仓<={th}（低于该值空仓） 全历史 中位 {np.median(e):+.2f}%  为正 {sum(1 for x in e if x>0)}/{len(e)}")
    for cst in (0.0002, 0.0005, 0.0012, 0.0030, 0.0060):
        e = []
        for F in fs:
            st, ag = nonoverlap(F, lambda s: 1.0 if s >= BUY else 0.0, PRIMARY_H, cst)
            if len(st) >= 15:
                e.append((st - ag).mean() * 100)
        print(f"  单边成本 {cst*100:.2f}%  13 报价中位 {np.median(e):+.2f}%  为正 {sum(1 for x in e if x>0)}/{len(e)}")

    # ---------- [6] 分数可达性：跨界频率 ----------
    print("\n[6] 68/42 线的'动作频率'（贵金属全历史，逐标的）")
    print(f"  {'标的':<14}{'在场%':>7}{'跨68/年':>9}{'跨42/年':>9}")
    for F in fs:
        sc = np.array([score_at(F, t)[0] for t in range(MIN_HIST, int(F["n"]))
                       if score_at(F, t)[1]], float)
        if len(sc) < 400:
            continue
        yrs = len(sc) / 244.0
        c68 = int(np.sum((sc[:-1] < BUY) & (sc[1:] >= BUY)) + np.sum((sc[:-1] >= BUY) & (sc[1:] < BUY)))
        c42 = int(np.sum((sc[:-1] <= SELL) & (sc[1:] > SELL)) + np.sum((sc[:-1] > SELL) & (sc[1:] <= SELL)))
        print(f"  {F['name']:<14}{100*np.mean(sc>=BUY):>6.0f}%{c68/yrs:>9.1f}{c42/yrs:>9.1f}")
    print(f"  参照：A股出厂阈值 SELL {SELL} / STRONG_SELL {STRONG_SELL} / BUY {BUY}，窗口 {PRIMARY_H} 日")


if __name__ == "__main__":
    main()
