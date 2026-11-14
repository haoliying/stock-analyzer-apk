#!/usr/bin/env python3
"""回答两个问题：成功率多少、能赚多少百分比。

引擎 = 上线版本（final_validate.new_fn，加仓线 68），旧引擎 = strategy.v_old（v4.0.1，
加仓线 60），都从 final_validate 直接导入，避免二次移植产生偏差。
协议与回测一致：t 日收盘出信号 -> t+1 开盘买入（开盘涨停剔除）-> 持有 h 个交易日
-> 扣 0.12% 往返成本。
"成功率"两个口径：绝对赚钱的比例（净值>0），跑赢同日全市场平均的比例（超额>0）。
"""
import os
import sys
from collections import defaultdict

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from final_validate import new_fn, old_fn  # noqa: E402  上线引擎 / 旧引擎的同一份实现
from model import BASELINE, CUTOFF, COST, DATES, MIN_HIST, stocks  # noqa: E402

BUY, OLD_BUY = 68.0, 60.0
HS = (5, 10, 20)


def collect(fn):
    """rows: (score, date, {h: (net, excess)}, unbuyable)"""
    rows = []
    for F in stocks:
        n = int(F["n"])
        c, o = F["close"], F["open"]
        lim = 0.097 if str(F["code"]).startswith(("30", "68")) else 0.047
        for t in range(MIN_HIST, n - 1):
            s, ok = fn(F, t)
            if not ok:
                continue
            eo = o[t + 1]
            if eo <= 0 or c[t] <= 0:
                continue
            d = str(F["date"][t])
            res = {}
            for h in HS:
                k = t + 1 + h
                b = BASELINE.get((d, h))
                if k >= n or b is None:
                    res[h] = (np.nan, np.nan)
                    continue
                raw = (c[k] / eo - 1.0) - COST
                res[h] = (raw, raw - b)
            unb = F["pct"][t] > 0 and eo >= c[t] * (1 + lim) * 0.995
            rows.append((float(s), d, res, unb))
    return rows


def v(vals):
    return np.array([x for x in vals if not np.isnan(x)])


def line(vals, label):
    a = v(vals)
    if a.size == 0:
        print(f"    {label:<22} 无样本")
        return a
    print(f"    {label:<22} n={a.size:<6d} 均值 {a.mean()*100:+5.2f}%  中位 {np.median(a)*100:+5.2f}%  "
          f"胜率 {(a>0).mean()*100:4.1f}%  p25 {np.percentile(a,25)*100:+5.1f}%  "
          f"p75 {np.percentile(a,75)*100:+5.1f}%  最差 {a.min()*100:+6.1f}%  最好 {a.max()*100:+6.1f}%")
    return a


def describe(rows, buy_th, name):
    sel = [r for r in rows if r[0] >= buy_th and not r[3]]
    print(f"\n================ {name}：达到加仓线(score>={buy_th:.0f})、可买入，共 {len(sel)} 笔信号 ================")
    for h in HS:
        print(f"  持有 {h:>2} 个交易日:")
        a = line([r[2][h][0] for r in sel], "实际收益(扣费)")
        e = v([r[2][h][1] for r in sel])
        if e.size:
            print(f"      跑赢同日全市场平均 {(e>0).mean()*100:4.1f}%   超额均值 {e.mean()*100:+5.2f}%   "
                  f"超额中位 {np.median(e)*100:+5.2f}%   亏>10% {(a<-0.10).mean()*100:4.1f}%   "
                  f"亏>20% {(a<-0.20).mean()*100:4.1f}%   赚>10% {(a>0.10).mean()*100:4.1f}%")
    oos = [r for r in sel if r[1] >= CUTOFF]
    print(f"  —— 样本外（信号日 >= {CUTOFF}，{len(oos)} 笔）——")
    for h in HS:
        a = line([r[2][h][0] for r in oos], f"{h:>2}日 实际收益")
        e = v([r[2][h][1] for r in oos])
        if e.size:
            print(f"      {h}日 跑赢基准 {(e>0).mean()*100:4.1f}%  超额均值 {e.mean()*100:+5.2f}%  "
                  f"亏>20% {(a<-0.20).mean()*100:4.1f}%")
    return sel


def baskets(rows, mode, picks=10, min_n=3):
    """一个信号日 = 买一篮子（等权），返回 (date, net20, excess20, size)"""
    by = defaultdict(list)
    for r in rows:
        by[r[1]].append(r)
    out = []
    for d, lst in sorted(by.items()):
        lst = [x for x in lst if not x[3]]
        if len(lst) < min_n:
            continue
        if mode == "all":
            chosen = lst
        else:
            lst = sorted(lst, key=lambda x: -x[0])
            if mode == "strat" and len(lst) > picks:
                chosen = [lst[int(i * (len(lst) - 1) / picks)] for i in range(picks)]
            elif mode == "top":
                chosen = lst[:picks]
            else:
                chosen = lst
        raw = v([x[2][20][0] for x in chosen])
        exc = v([x[2][20][1] for x in chosen])
        if raw.size == 0:
            continue
        out.append((d, float(raw.mean()), float(exc.mean()) if exc.size else np.nan, len(chosen)))
    return out


def compound(bk, name, since=None):
    """非重叠滚动：每 20 个交易日换一次仓，无信号期空仓记 0。since 起算只看样本外。"""
    m = {b[0]: b for b in bk}
    grid = [d for d in DATES if since is None or d >= since]
    path, bpath, cycles, bcycles = 1.0, 1.0, [], []
    for i in range(0, len(grid), 20):
        d = grid[i]
        b = m.get(d)
        r = b[1] if b is not None else 0.0
        path *= (1 + r)
        cycles.append((d, r, path, b is not None))
        br = BASELINE.get((d, 20))
        if br is not None:
            bpath *= (1 + br)
            bcycles.append((d, br, bpath))
    if not cycles:
        print(f"\n  [{name}] 无可滚动样本")
        return
    arr = np.array([c[1] for c in cycles])
    eq = np.array([c[2] for c in cycles])
    filled = sum(1 for c in cycles if c[3])
    dd = (eq / np.maximum.accumulate(eq) - 1).min()
    days = len(cycles) * 20
    yrs = max(days / 244.0, 1e-9)
    tot = eq[-1] - 1
    farr = np.array([c[1] for c in cycles if c[3]])
    print(f"\n  [{name}] 每20个交易日换仓，共 {len(cycles)} 期（其中 {filled} 期有信号持仓，其余空仓）"
          f"≈ {days} 个交易日（约 {yrs:.1f} 年）")
    print(f"    累计 {tot*100:+7.1f}%   年化 {(1+tot)**(1/yrs)-1:+6.1%}   单期均值 {arr.mean()*100:+5.2f}%   "
          f"持仓期均值 {farr.mean()*100:+5.2f}%   持仓期赚钱占比 {(farr>0).mean()*100:3.0f}%   "
          f"单期中位 {np.median(arr)*100:+5.2f}%   最差单期 {arr.min()*100:+5.1f}%   最大回撤 {dd*100:5.1f}%")
    brr = np.array([c[1] for c in bcycles])
    bqq = np.array([c[2] for c in bcycles])
    if bqq.size:
        print(f"    对照·全市场平均不择时同期: 累计 {(bqq[-1]-1)*100:+7.1f}%   年化 {(bqq[-1]-1)/yrs:+6.1%}(单利)   "
              f"赚钱期占比 {(brr>0).mean()*100:3.0f}%   最大回撤 {(bqq/np.maximum.accumulate(bqq)-1).min()*100:5.1f}%")


def main():
    rows_new = collect(new_fn)
    rows_old = collect(old_fn)

    sel_new = describe(rows_new, BUY, "v4.1.0 新引擎")
    describe(rows_old, OLD_BUY, "v4.0.1 旧引擎（对照）")

    print("\n================ 一篮子（当天把所有信号等权买一批）20日口径 ================")
    for mode, lab in (("all", "新·全部合格等权"), ("strat", "新·分位等距取10(上线)"), ("top", "新·评分最高取10(否掉)")):
        bk = baskets([r for r in rows_new if r[0] >= BUY], mode)
        raw = np.array([b[1] for b in bk])
        exc = v([b[2] for b in bk])
        print(f"  {lab:<22} 交易日 {len(bk):>4} 天  均值 {raw.mean()*100:+5.2f}%  中位 {np.median(raw)*100:+5.2f}%  "
              f"赚钱天数 {(raw>0).mean()*100:4.1f}%  p10 {np.percentile(raw,10)*100:+5.1f}%  "
              f"最差 {raw.min()*100:+5.1f}%  最好 {raw.max()*100:+5.1f}%  | 跑赢基准 {(exc>0).mean()*100:4.1f}%")
        compound(bk, lab)
    print("\n  —— 只看样本外（信号日 >= %s，相当于'上线后才用'）——" % CUTOFF)
    for mode, lab in (("all", "新·全部合格等权"), ("strat", "新·分位等距取10(上线)")):
        compound(baskets([r for r in rows_new if r[0] >= BUY], mode), f"样本外 {lab}", since=CUTOFF)
    oos_bk = [b for b in baskets([r for r in rows_new if r[0] >= BUY], "strat") if b[0] >= CUTOFF]
    raw = np.array([b[1] for b in oos_bk])
    exc = v([b[2] for b in oos_bk])
    print(f"    样本外 {len(oos_bk)} 个信号日  一篮子20日均值 {raw.mean()*100:+5.2f}%  中位 {np.median(raw)*100:+5.2f}%  "
          f"赚钱天数 {(raw>0).mean()*100:4.1f}%  最差 {raw.min()*100:+5.1f}%  最好 {raw.max()*100:+5.1f}%  "
          f"| 跑赢基准 {(exc>0).mean()*100:4.1f}%")
    bk_old = baskets([r for r in rows_old if r[0] >= OLD_BUY], "all")
    if bk_old:
        raw = np.array([b[1] for b in bk_old])
        exc = v([b[2] for b in bk_old])
        print(f"  {'旧·全部合格等权':<22} 交易日 {len(bk_old):>4} 天  均值 {raw.mean()*100:+5.2f}%  "
              f"中位 {np.median(raw)*100:+5.2f}%  赚钱天数 {(raw>0).mean()*100:4.1f}%  最差 {raw.min()*100:+5.1f}%  "
              f"| 跑赢基准 {(exc>0).mean()*100:4.1f}%")
        compound(bk_old, "旧·全部合格等权")

    print("\n================ 按自然月（上线的'分位等距取10'，20日持有）================")
    mo = defaultdict(list)
    for d, raw, exc, n in baskets([r for r in rows_new if r[0] >= BUY], "strat"):
        mo[d[:7]].append(raw)
    vals = [(k, float(np.mean(x))) for k, x in sorted(mo.items())]
    arr = np.array([x for _, x in vals])
    print(f"  {len(arr)} 个月  均值 {arr.mean()*100:+5.2f}%  中位 {np.median(arr)*100:+5.2f}%  "
          f"正收益月 {(arr>0).mean()*100:3.0f}%  最差月 {arr.min()*100:+5.2f}%  最好月 {arr.max()*100:+5.2f}%")
    print("  逐月: " + "  ".join(f"{k[2:]}:{x*100:+.1f}%" for k, x in vals))
    for tag, rws, th, lab in (("新", rows_new, BUY, "全部合格等权"), ("旧", rows_old, OLD_BUY, "分位等距取10")):
        mo2 = defaultdict(list)
        for d, raw, exc, n in baskets([r for r in rws if r[0] >= th], lab == "分位等距取10" and "strat" or "all"):
            mo2[d[:7]].append(raw)
        a2 = np.array([np.mean(x) for _, x in sorted(mo2.items())])
        print(f"  对照 {tag}·{lab} {len(a2)} 个月: 均值 {a2.mean()*100:+5.2f}%  中位 {np.median(a2)*100:+5.2f}%  "
              f"正收益月 {(a2>0).mean()*100:3.0f}%  最差月 {a2.min()*100:+5.2f}%  最好月 {a2.max()*100:+5.2f}%")

    print("\n================ 单笔收益分布（新引擎 20日，看'一次能赚/亏多少'）================")
    a = v([r[2][20][0] for r in sel_new])
    qs = [1, 5, 10, 25, 50, 75, 90, 95, 99]
    print("  分位: " + "  ".join(f"p{p}:{np.percentile(a,p)*100:+.1f}%" for p in qs))
    for lo, hi in ((-9, -0.20), (-0.20, -0.10), (-0.10, 0), (0, 0.10), (0.10, 0.20), (0.20, 0.50), (0.50, 9)):
        m = (a >= lo) & (a < hi)
        print(f"    {lo*100 if lo > -9 else '<-20':>6}% ~ {hi*100 if hi < 9 else '>50':<6}%  占比 {m.mean()*100:5.1f}%")


if __name__ == "__main__":
    main()
