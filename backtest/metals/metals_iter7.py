#!/usr/bin/env python3
"""metals_iter7 — 数据质量审计：所有结论都站在这些数据上，先把它查清楚。

起因：iter2 的相关矩阵里出现一个讲不通的数字 —— sina_XAU(伦敦金现货) 与
sina_AU0(沪金主力) 的日收益相关只有 0.33，而 sge_AuT+D 与 sina_AU0 是 0.972。
同一个资产不该差这么多。要么是我的日期对齐有问题，要么是这两个序列的"交易日"定义不同。
这直接影响"13 个报价 = 4 个独立资产"这个说法，也影响 iter1 全历史样本的日期跨度
（明明只有 5184 根 K 线，为什么日期能到 2026-11-16）。

查四件事：
  [A] 每个序列的日期跨度、根数、日历密度（根数 / 跨度天数）、重复日期、日期是否单调；
  [B] 全球序列(XAU/XAG/XPT/XPD) 与 国内序列(AU0/AG0/AuT+D) 的相关，按"同日 / 滞后1日 /
      滞后2日 / 滞后3日"分别算 —— 如果滞后后相关跳高，就是日期错位；
  [C] SGE 的 Date 字段有没有真实时间：用当天 Au 收盘对次日，看隔夜缺口落在哪一天；
  [D] 结论：哪些序列可用作日内/隔日对齐，哪些只能用作"自身的历史"。

不改任何评分口径；这是纯数据检查。
"""
import glob
import json
import os
from datetime import datetime

import numpy as np
import pandas as pd

HERE = os.path.dirname(os.path.abspath(__file__))
sys_path = os.path.dirname(HERE)
import sys
sys.path.insert(0, sys_path)
sys.path.insert(0, HERE)
from metals_iter1 import load  # noqa: E402

def main():
    fs = [load(p) for p in sorted(glob.glob(os.path.join(HERE, "metals_data", "*.json")))]
    print("=" * 100)
    print("metals_iter7  贵金属数据质量审计")
    print("=" * 100)

    # [A] 日历一致性
    print("\n[A] 每个序列：跨度 / 根数 / 日历密度 / 重复日期 / 是否单调")
    print(f"  {'序列':<14}{'根数':>6}{'起':>12}{'止':>12}{'跨度年':>8}{'密度':>8}"
          f"{'重复':>6}{'单调':>6}{'周末日期':>9}{'1月1日':>8}")
    for F in fs:
        d = [str(x) for x in F["date"]]
        days = (datetime.strptime(d[-1], "%Y-%m-%d") - datetime.strptime(d[0], "%Y-%m-%d")).days
        dens = len(d) / (days / 365.0)
        dupes = len(d) - len(set(d))
        mono = all(b > a for a, b in zip(d, d[1:]))
        wd = sum(1 for x in d if datetime.strptime(x, "%Y-%m-%d").weekday() >= 5)
        jan1 = sum(1 for x in d if x.endswith("-01-01"))
        print(f"  {F['name']:<14}{len(d):>6}{d[0]:>12}{d[-1]:>12}{days/365.0:>8.1f}"
              f"{dens:>8.0f}{dupes:>6}{'是' if mono else '否':>6}{wd:>9}{jan1:>8}")
    print("  判据：期货/现货日线满打满算 250/365≈0.685 根每天 → 密度应在 245~255；"
          "密度≈365 说明日历被逐日填充（包含非交易日）。")

    # [B] 跨市场相关，按滞后
    print("\n[B] 同一资产跨市场日收益相关（按错位天数）——查日期对齐")
    ser = {}
    for F in fs:
        c = F["close"]
        s = pd.Series(np.log(c[1:] / c[:-1]),
                      index=[str(x) for x in F["date"][1:]])
        # sina_AU0 有 1 个重复日期，reindex 会直接报错 —— 保留首次出现
        ser[F["name"]] = s[~s.index.duplicated(keep="first")]
    pairs = [("sina_XAU", "sina_AU0"), ("sina_XAU", "sge_AuT+D"), ("sina_XAG", "sina_AG0"),
             ("sina_XAG", "sge_AgT+D"), ("sina_XAU", "sina_XAG"), ("sina_AU0", "sina_AG0"),
             ("sina_XPT", "sina_XPD"), ("sge_AuT+D", "sina_AU0")]
    lags = (-2, -1, 0, 1, 2)
    print("  口径：k>0 = b 比 a 晚 k 个交易日才反应（b 滞后）；k<0 = b 提前 k 个交易日。")
    print("  第一遍写成只测 k>0 并把表头标成'国内滞后'，结果测的其实是 corr(a_t, b_{t-1})，")
    print("  方向正好相反 —— 这就是这张表第一次跑出来'滞后相关≈0'、和手工核对 0.65 对不上的原因。")
    print(f"  {'配对':<26}" + "".join(f"{('b滞后%+d' % k):>10}" for k in lags)
          + f"{'校验:b提前1':>12}{'共同日':>9}")
    for a, b in pairs:
        sa, sb = ser[a], ser[b]
        # 并集日历上的位移相关（不受节假日错日影响）
        idx = sorted(set(sa.index) | set(sb.index))
        A = sa.reindex(idx).to_numpy(dtype=float)
        B = sb.reindex(idx).to_numpy(dtype=float)
        row = []
        for k in lags:
            if k == 0:
                x, y = A, B
            elif k > 0:                       # b 延后 k：a_t 对 b_{t+k}
                x, y = A[:-k], B[k:]
            else:                             # b 提前 |k|
                s = -k
                x, y = A[s:], B[:-s]
            m = ~(np.isnan(x) | np.isnan(y))
            row.append(np.corrcoef(x[m], y[m])[0, 1] if m.sum() > 100 else np.nan)
        # 严格"次日"（日历 +1 天）作为对照
        s2 = sb.copy()
        s2.index = [(pd.Timestamp(t) + pd.Timedelta(days=1)).strftime("%Y-%m-%d") for t in s2.index]
        j = pd.concat([sa.rename("a"), s2.rename("b")], axis=1).dropna()
        cal = j["a"].corr(j["b"]) if len(j) > 100 else np.nan
        n = len(pd.concat([sa.rename("a"), sb.rename("b")], axis=1).dropna())
        print(f"  {a+' vs '+b:<26}"
              + "".join(f"{v:>10.3f}" if v == v else f"{'n/a':>10}" for v in row)
              + f"{cal:>12.3f}{n:>9,}")
    print("  结论：境内两个源（SGE / 上期所）对同一天，同日相关 0.97、无滞后；")
    print("        境外收盘 vs 境内，同日只有 0.28~0.38，境内**下一个交易日**才 0.60~0.66。")
    print("        → '13 个报价 = 4 个独立资产'的依据是标的同一（同一种金属），不是同日相关；")
    print("          App 里 XAU 与 AU0 并排时时间戳不同源，不能当成同一时刻的价格。")

    # [C] SGE 时间戳真伪
    print("\n[C] SGE Date 字段有没有真时间：看 23:00 夜盘归属日")
    p = os.path.join(HERE, "metals_data", "sge_AuT+D.json")
    if os.path.exists(p):
        d = json.load(open(p, encoding="utf-8"))
        rows = d["rows"]
        # 找一次已知的金价急跌日，看它落在哪个 date
        chg = [(rows[i]["date"], rows[i]["close"] / rows[i - 1]["close"] - 1.0)
               for i in range(1, len(rows))]
        chg.sort(key=lambda x: x[1])
        print(f"  跌幅最大的 5 天（按 SGE Date）：{[(a, round(b*100,2)) for a, b in chg[:5]]}")
        print(f"  涨幅最大的 5 个交易日：{[(a, round(b*100,2)) for a,b in chg[-5:]]}")
        ds = [r["date"] for r in rows]
        gaps = [(datetime.strptime(b, "%Y-%m-%d") - datetime.strptime(a, "%Y-%m-%d")).days
                for a, b in zip(ds, ds[1:])]
        print(f"  相邻 Date 间隔分布：==1天 {sum(1 for g in gaps if g==1)}, ==2天 {sum(1 for g in gaps if g==2)}, "
              f"==3天 {sum(1 for g in gaps if g==3)}, >=7天 {sum(1 for g in gaps if g>=7)}")
        print("  判据：若夜盘(20:00-02:30)被正确并入次日，周五之后应出现 3 天间隔而不是 7 天，"
              "且 1 月 1 日不应出现行情。")

    # [D] 全球序列与国内的日期错位统计
    print("\n[D] 全球序列 vs 国内序列：同一天有没有行情")
    x = set(str(d) for d in ser["sina_XAU"].index)
    a = set(str(d) for d in ser["sina_AU0"].index)
    print(f"  sina_XAU 交易日 {len(x):,}；sina_AU0 交易日 {len(a):,}；交集 {len(x & a):,}")
    print(f"  只在 XAU 有：{len(x - a):,}；只在 AU0 有：{len(a - x):,}")
    only_au = sorted(a - x)[-8:]
    print(f"  最近 8 个'只有国内有行情'的日期：{only_au}")
    only_x = sorted(x - a)[-8:]
    print(f"  最近 8 个'只有国外有行情'的日期：{only_x}")

    # [E] 事件窗：把 [B] 的滞后用肉眼可对上的具体一天钉死
    print("\n[E] 已知行情的日期对齐（净收益 %，按各自序列自己的 date）")
    by = {F["name"]: {str(r["date"]): r for r in json.load(
        open(os.path.join(HERE, "metals_data", f"{F['name']}.json"), encoding="utf-8"))["rows"]}
        for F in fs}

    def pct_at(nm, day):
        """day 可能是 't' 也可能是 't+1'（相对参照日）——直接查该序列该日的涨跌幅"""
        r = by[nm].get(day)
        return None if r is None else round(r["pct"], 2)

    EVENTS = [("2013-04-15 伦敦金单日暴跌(Comex 4/15)", ["sina_XAU", "sina_AU0", "sge_Au99.99"]),
              ("2020-08-07 白银见顶前后", ["sina_XAG", "sina_AG0"]),
              ("2026-01-30 贵金属急跌", ["sina_XAU", "sina_XAG", "sina_AU0", "sge_Au99.99"])]
    for label, names in EVENTS:
        d0 = label.split()[0]
        days = [(pd.Timestamp(d0) + pd.Timedelta(days=k)).strftime("%Y-%m-%d") for k in (-1, 0, 1, 2)]
        print(f"  {label}")
        for nm in names:
            print(f"    {nm:<14}" + "  ".join(f"{d}:{pct_at(nm, d) if pct_at(nm, d) is not None else '-':>7}"
                                              for d in days))
    print("  判据：若境外跌幅落在 4/15、境内跌幅同一天也有一半、第二天再补一半，")
    print("        说明两个源的 date 不是同一时刻；App 里绝不能把 XAU 的'今日'和 AU0 的'今日'")
    print("        当成同一时刻的价格做差或求溢价。")

    # [F] 传导分解：境外大波动日，境内当天反应多少、第二天补多少
    print("\n[F] 大波动传导（境外 |日收益|>3% 的日子，看境内简单收益 %）")
    rser = {}
    for F in fs:
        c = F["close"]
        s = pd.Series((c[1:] / c[:-1] - 1.0) * 100.0, index=[str(x) for x in F["date"][1:]])
        rser[F["name"]] = s[~s.index.duplicated(keep="first")]

    def propagate(src, dst):
        a, b = rser[src], rser[dst]
        cal = sorted(set(a.index) | set(b.index))
        A = pd.Series(a.reindex(cal).to_numpy(float), index=cal)
        B = pd.Series(b.reindex(cal).to_numpy(float), index=cal)
        ev = A[A.abs() > 3.0].index
        pos = {d: i for i, d in enumerate(cal)}
        same, nxt, aft, pre, evv = [], [], [], [], []
        for d in ev:
            i = pos[d]
            for lst, off in ((pre, -1), (same, 0), (nxt, 1), (aft, 2)):
                if i + off < 0 or i + off >= len(cal):
                    continue
                v = B.iloc[i + off]
                if np.isfinite(v):
                    lst.append(v)
            if i < len(cal) and np.isfinite(B.iloc[i]):
                evv.append(A.iloc[i])
        sgn = (np.sign(np.array(same)) == np.sign(np.array(evv))).mean() if same else np.nan
        return (len(ev), np.mean(pre) if pre else np.nan, np.mean(same) if same else np.nan,
                np.mean(nxt) if nxt else np.nan, np.mean(aft) if aft else np.nan, sgn,
                np.mean(np.abs(same)) / max(1e-9, np.mean(np.abs(A[ev]))) if same else np.nan,
                (np.mean(np.abs(same)) + np.mean(np.abs(nxt))) / max(1e-9, np.mean(np.abs(A[ev])))
                if same and nxt else np.nan)

    print(f"  {'配对':<26}{'事件数':>7}{'前1日':>8}{'同日':>8}{'后1日':>8}{'后2日':>8}"
          f"{'同日同号':>9}{'同日吸收':>10}{'两日累计':>10}")
    for src, dst in [("sina_XAU", "sina_AU0"), ("sina_XAU", "sge_Au99.99"),
                     ("sina_XAG", "sina_AG0"), ("sina_XAG", "sge_AgT+D"),
                     ("sina_AU0", "sina_XAU"), ("sina_AG0", "sina_XAG")]:
        n, p0, s0, s1, s2, sg, ab0, ab2 = propagate(src, dst)
        print(f"  {src+' -> '+dst:<26}{n:>7}{p0:>+8.2f}{s0:>+8.2f}{s1:>+8.2f}{s2:>+8.2f}"
              f"{sg*100:>8.0f}%{ab0:>9.0%}{ab2:>9.0%}")
    print("  读法：'同日吸收'=境内同日 |涨跌幅| / 境外事件日 |涨跌幅|；'两日累计'同理含次日。")
    print("        同日同号比例 + 两日累计 > 100% 就说明境内既跟当天、又补第二天（不是干净的错位一天）。")


if __name__ == "__main__":
    main()
