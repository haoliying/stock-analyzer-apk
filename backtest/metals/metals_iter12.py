#!/usr/bin/env python3
"""metals_iter12 — F9 波动率目标的可显示版（取整 + 不动区间），并给出当前读数

为什么要再跑一个 iter：iter9 里唯一在 OOS 和 IS 都把最大回撤做到 4/4 优于一直持有的是 F4 波动率目标
（C2 4/4，成本翻到 0.30% 仍 4/4），但它的**收益**证据是空的（C7 日超额 CI 含 0），
而且它每天重算一次，换手 191~246 次/年 —— App 界面不可能让用户按这个动手。
界面要显示的东西必须是"回测里真跑过的那个版本"，所以这里预声明一个可显示版并单独判一次。

════════════════════════════════════════════════════════════════════════
预注册口径（跑之前写死）
════════════════════════════════════════════════════════════════════════
F9 规则（唯一新增族；参数只取这张清单）
  · σ_t = 21 日收盘收益标准差 × √252（与 iter9 的 vol21 同一条公式）
  · raw_t = min(1, target / σ_t)，target ∈ {10%, 15%, 20%}
  · 取整到 5% 一档：step = round(raw_t / 0.05) × 0.05
  · 不动区间：|step − 当前仓位| < 0.10 时**不调**（省掉界面一天三变）
  · 起始仓位 = 1.0（第一个 σ 可用日按 step 校准）
  · 长期空仓不是选项：仓位下限 0.25（贵金属这一类的看涨漂移是它自己的，不给清仓建议）
计量与判据（沿用 iter9 的 C1..C7，同一套阈值，不重新定义）
  C1 OOS 夏普 ≥ 一直持有 + 0.15（≥3/4 资产）
  C2 OOS + IS 最大回撤都要优于一直持有（4/4）
  C3 OOS 年化 ≥ 一直持有 − 1.5pp
  C4 IS/OOS 方向一致
  C5 0.30%/side 结论不翻
  C6 换手 ≤ 30 次/年
  C7 日超额移动块自助 95%CI；含 0 就只准引用风险量证据
产品口径（判据之外的硬要求）
  · 若 C1/C3/C7 不过而 C2 过：这个数只能作为**风险刻度**显示，文案不得出现"该买/该卖/会涨/会跌"
  · 界面显示的必须是本脚本跑过的 target（三选一）与同一取整/不动区间规则
最后给出 13 条报价在 2026-11-16 的当前读数，作为 App 首屏的对照答案。
"""
import glob
import os

import numpy as np

import metals_iter9 as m9

HERE = os.path.dirname(os.path.abspath(__file__))
TARGETS = (0.10, 0.15, 0.20)
STEP, BAND, FLOOR = 0.05, 0.10, 0.25
REP = m9.REP
COST, COST_HI = m9.COST, m9.COST_HI
SPLIT, IS_HI = m9.SPLIT, m9.IS_HI


def pos_f9(F, target):
    """波动率目标 + 5% 取整 + 10% 不动区间 + 25% 下限。t 收盘决定，t+1 生效由 m9.strat_ret 负责。"""
    v = F["vol21"]
    n = F["n"]
    pos = np.full(n, np.nan)
    cur = 1.0
    for t in range(n):
        if not np.isfinite(v[t]) or v[t] <= 0:
            continue
        raw = min(1.0, target / v[t])
        step = round(raw / STEP) * STEP
        if abs(step - cur) >= BAND:
            cur = step
        pos[t] = max(cur, FLOOR)
    return pos


def run_one(F, target, cost=COST):
    pos = pos_f9(F, target)
    w = m9.strat_ret(F, pos, cost)
    wb = m9.strat_ret(F, m9.pos_rule(F, "bh", None), cost)
    d = F["date"]
    m, si = m9.mask_of(d, SPLIT), m9.mask_of(d, None, IS_HI)
    return {"oos": m9.stats(w[m]), "oos_b": m9.stats(wb[m]),
            "is": m9.stats(w[si]), "is_b": m9.stats(wb[si]),
            "oos_hi": m9.stats(m9.strat_ret(F, pos, COST_HI)[m]),
            "oos_hi_b": m9.stats(m9.strat_ret(F, m9.pos_rule(F, "bh", None), COST_HI)[m]),
            "ex": (w - wb)[m],
            "tr": m9.trades_per_year(pos, d, None, SPLIT, None),
            "pos": pos, "tr_all": m9.trades_per_year(pos, d, None, None, None)}


def main():
    print("=" * 118)
    print("metals_iter12  F9 波动率目标（5% 取整 + 10% 不动区间 + 25% 下限）｜干净滞后｜同 iter9 的 C1..C7")
    print("=" * 118)

    fs = {nm: m9.load(os.path.join(m9.DATA, nm + ".json")) for nm in REP}
    print("\n[1] 逐资产（OOS ≥2021 / IS ≤2020；括号内为一直持有）")
    for t in TARGETS:
        print(f"\n  target={int(t*100)}%")
        for nm in REP:
            r = run_one(fs[nm], t)
            if not (r["oos"] and r["is"]):
                continue
            o, b, i, ib = r["oos"], r["oos_b"], r["is"], r["is_b"]
            print(f"    {nm:10s} OOS 夏普 {o['sharpe']:>5.2f}({b['sharpe']:>5.2f})  年化 {o['cagr']*100:>6.1f}%({b['cagr']*100:>5.1f}%)  "
                  f"回撤 {o['maxdd']*100:>6.1f}%({b['maxdd']*100:>6.1f}%)  波动 {o['vol']*100:>5.1f}%({b['vol']*100:>5.1f}%)  "
                  f"IS 回撤 {i['maxdd']*100:>6.1f}%({ib['maxdd']*100:>6.1f}%)  换手 {r['tr']:>5.1f}")

    print("\n[2] C1..C7 打分（4 个独立资产）")
    print(f"  {'target':>7s} {'C1夏普':>7s} {'C2回撤':>7s} {'C3年化':>7s} {'C4同向':>7s} {'C5成本':>7s} "
          f"{'C6换手':>7s} {'C7CI':>16s}  {'判据整体':>9s}")
    for t in TARGETS:
        c1 = c2 = c3 = c4 = c5 = 0
        ci_ok = []
        trs, dd_d = [], []
        for nm in REP:
            r = run_one(fs[nm], t)
            if not (r["oos"] and r["is"]):
                continue
            c1 += r["oos"]["sharpe"] >= r["oos_b"]["sharpe"] + 0.15
            c2 += (r["oos"]["maxdd"] > r["oos_b"]["maxdd"]) and (r["is"]["maxdd"] > r["is_b"]["maxdd"])
            c3 += r["oos"]["cagr"] >= r["oos_b"]["cagr"] - 0.015
            do, di = r["oos"]["sharpe"] - r["oos_b"]["sharpe"], r["is"]["sharpe"] - r["is_b"]["sharpe"]
            c4 += (do > 0) == (di > 0)
            c5 += r["oos_hi"]["sharpe"] >= r["oos_hi_b"]["sharpe"] + 0.10
            trs.append(r["tr"])
            dd_d.append(r["oos"]["maxdd"] - r["oos_b"]["maxdd"])
            ci = m9.block_ci(r["ex"])
            ci_ok.append(ci)
        pooled = np.concatenate([r["ex"] for r in (run_one(fs[nm], t) for nm in REP) if r["ex"] is not None])
        ci = m9.block_ci(pooled)
        allpass = c1 >= 3 and c2 == 4 and c3 >= 3 and c4 >= 3 and c5 >= 3 and np.mean(trs) <= 30 and ci and not (ci[1] <= 0 <= ci[2])
        print(f"  {int(t*100):>6d}% {c1:>5d}/4 {c2:>5d}/4 {c3:>5d}/4 {c4:>7d}/4 {c5:>5d}/4 {np.mean(trs):>7.1f} "
              f"{f'{ci[0]*1e4:+.1f}bp [{ci[1]*1e4:+.1f},{ci[2]*1e4:+.1f}]':>16s}  {'过' if allpass else '不过（只 C2 过）' if c2==4 else '不过':>9s}")
        print(f"          回撤改善均值 {np.mean(dd_d)*100:+.1f}pp   各资产 CI: " +
              " ".join(f"{nm[-3:]}:[{c[1]*1e4:+.1f},{c[2]*1e4:+.1f}]" for nm, c in zip(REP, ci_ok) if c))

    print("\n[3] 全 13 条报价的当前读数（App 首屏要显示的数，2026-11-16）")
    print(f"  {'资产':4s} {'报价':14s} {'末日':12s} {'21日年化波动':>12s} {'建议仓位 10%':>13s} {'15%':>7s} {'20%':>7s}")
    asset_of = {nm: a for a, lst in m9.ASSET_OF.items() for nm in lst}
    for p in sorted(glob.glob(os.path.join(m9.DATA, "*.json"))):
        F = m9.load(p)
        nm = F["name"]
        v = F["vol21"]
        last = v[np.isfinite(v)][-1] if np.isfinite(v).any() else np.nan
        cells = []
        for t in TARGETS:
            pos = pos_f9(F, t)
            pv = pos[np.isfinite(pos)]
            cells.append(pv[-1] if len(pv) else np.nan)
        print(f"  {asset_of.get(nm,'?'):4s} {nm:14s} {F['date'][-1]:12s} {last*100:>11.1f}% "
              f"{cells[0]*100:>12.0f}% {cells[1]*100:>6.0f}% {cells[2]*100:>6.0f}%")

    print("\n[4] 这个读数一年动几次、每次动多少（界面能不能看）")
    for t in TARGETS:
        for nm in REP:
            F = fs[nm]
            pos = pos_f9(F, t)
            d = F["date"]
            m = d >= SPLIT
            p = pos[m]
            p = p[np.isfinite(p)]
            ch = np.diff(p)
            ch = ch[np.abs(ch) > 1e-9]
            yrs = len(p) / 252
            print(f"  target={int(t*100)}% {nm:10s} OOS 调仓 {len(ch):>3d} 次（{len(ch)/yrs:>4.1f} 次/年）"
                  f"  平均幅度 {np.mean(np.abs(ch))*100 if len(ch) else 0:>4.1f}pp  "
                  f"仓位区间 [{p.min()*100:.0f}%, {p.max()*100:.0f}%]  中位 {np.median(p)*100:.0f}%")


if __name__ == "__main__":
    main()
