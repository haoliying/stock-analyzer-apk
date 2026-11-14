#!/usr/bin/env python3
"""iter27 — 把"跑赢大盘"这句话算清楚：同相位、同网格的公平对照。

diag_phase 暴露两个问题：
  1) 累计收益对"哪几天换仓"极其敏感：同一条规则 20 个相位从 +26% 到 +139%。
     所以任何单条累计数字都必须写成"相位均值 ± 标准差"，并报有多少相位赢基准。
  2) 之前拿"20 日换仓的组合"去比"每日买入持有的大盘"是不公平的。
     288 只等权、每 20 天再平衡 = +62.6%，而每日买入持有 = +38.9%，
     这 23.7 个百分点是**再平衡本身**带来的，不是算法带来的。
本轮每条候选规则同时对照两个基准：
  基准A 大盘·每日买入持有（+38.9%，全相位相同）
  基准B 大盘·同相位每 20 天再平衡等权（与策略同网格，这才是"跑赢大盘"的正确含义）
"""
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import fast as X  # noqa: E402
import iter23 as H23  # noqa: E402
import iter25 as Q  # noqa: E402
import portsim as P  # noqa: E402
from model import CUTOFF, DATES  # noqa: E402

ND, NS = P.ND, P.NS
IDX = {d: i for i, d in enumerate(DATES)}
HP = 20
S0 = Q.S0
R20 = X.ret_mat(HP)
VALID = X.valid_mat(S0, HP)
_BM = np.nanmean(np.where(VALID, R20, np.nan), axis=1)
BM = np.where(np.isnan(_BM), 0.0, _BM)          # 基准B：每 20 日再平衡的全市场等权
IDXAILY, _MA40 = P.universe_index(40)           # 基准A：每日买入持有的全市场等权净值


def rule(th=68.0, fq=None, K=None, stop=None, mode="strat"):
    """rows_rule 的统一入口，min_pool=1：池里有 1 只就买（和 App 实际行为一致）。"""
    return Q.rows_rule(th=th, fq=fq, K=K, H_=HP, stop=stop, min_pool=1, mode=mode)


def phases(rows, since=None):
    """把一个"每日篮子"序列变成若干条**非重叠**的资金曲线（每 20 天换仓）。"""
    ds = [x[0] for x in rows]
    r = np.array([x[1] for x in rows])
    out = []
    for p in range(HP):
        jj = [j for j, d in enumerate(ds) if (since is None or d >= since) and (IDX[d] - p) % HP == 0]
        if len(jj) < 4:
            continue
        a = IDX[ds[jj[0]]]
        z = IDX[ds[jj[-1]]] + HP
        seq = np.cumprod(1 + r[jj])
        out.append(dict(a=float(seq[-1] - 1),
                        dd=float((seq / np.maximum.accumulate(seq) - 1).min()),
                        b=float(np.prod(1 + BM[jj]) - 1),
                        n=len(jj), s=r[jj], lo=a, hi=min(z, ND - 1)))
    return out


def bench_a(lo, hi):
    v = IDXAILY[max(lo, 1)]
    return float(IDXAILY[min(hi, ND - 1)] / v - 1.0)


def report(label, rows, quiet=False):
    e = np.array([x[1] - x[2] for x in rows])
    ds = [x[0] for x in rows]
    t = H23.hac_t(e, HP)
    p = H23.block_p(e, HP)
    ph = phases(rows)
    ca = np.array([x["a"] for x in ph])
    cb = np.array([x["b"] for x in ph])
    cd = np.array([x["dd"] for x in ph])
    ba = np.array([bench_a(x["lo"], x["hi"]) for x in ph])
    if not quiet:
        print(f"  {label:<30} 日均只数{np.mean([x[3] for x in rows]):6.1f}  "
              f"日超额 {e.mean()*100:+6.3f}% t_HAC {t:+5.2f} p {p:.3f} | "
              f"累计 {ca.mean()*100:+6.1f}%±{ca.std()*100:4.1f}% "
              f"[{ca.min()*100:+5.1f},{ca.max()*100:+5.1f}]  "
              f"超B {(ca-cb).mean()*100:+6.1f}pp(赢{(ca > cb).mean()*100:3.0f}%相位)  "
              f"超A {(ca-ba).mean()*100:+6.1f}pp(赢{(ca > ba).mean()*100:3.0f}%)  "
              f"回撤 {cd.mean()*100:5.1f}%")
    return dict(label=label, e=e, ds=ds, t=t, p=p, cum=ca.mean(), cum_sd=ca.std(),
                cum_min=ca.min(), cum_max=ca.max(), vsB=float((ca - cb).mean()),
                beatB=float((ca > cb).mean()), vsA=float((ca - ba).mean()),
                beatA=float((ca > ba).mean()), dd=cd.mean(), dd_min=cd.min())


def main():
    bh = P.bench_hold(0, ND)
    cb_all = np.array([x["b"] for x in phases([(DATES[i], BM[i], 0.0, 1) for i in range(ND - HP - 1)])])
    print(f"\n基准A 大盘·每日买入持有 {bh*100:+.1f}%   "
          f"基准B 大盘·每20日再平衡 相位均值 {cb_all.mean()*100:+.1f}%±{cb_all.std()*100:.1f}% "
          f"[{cb_all.min()*100:+.1f}%, {cb_all.max()*100:+.1f}%]  → A→B 再平衡增益 "
          f"{(cb_all.mean()-bh)*100:+.1f}pp")

    print("\n==== 0. 基准自检：全市场等权（应≈0 超额 vs B）====")
    report("全市场满池（= 基准B）",
           [(DATES[i], float(BM[i]), float(BM[i]), int((VALID[i]).sum())) for i in range(ND - HP - 1)])

    print("\n==== 1. 现行上线 vs 候选（满池等权，min_pool=1）====")
    res = []
    res.append(report("现行 ≥68 全部等权", rule(th=68.0)))
    res.append(report("≥60 全部等权", rule(th=60.0)))
    for fq in (0.20, 0.30, 0.40, 0.50, 0.70):
        res.append(report(f"前{fq*100:.0f}% 满池等权", rule(th=0.0, fq=fq)))
    res.append(report("前40% 且 ≥60", rule(th=60.0, fq=0.40)))

    print("\n==== 2. 只买 20 只（App 展示规模）====")
    for fq in (0.30, 0.40, 0.50):
        res.append(report(f"前{fq*100:.0f}% 分层取20", rule(th=0.0, fq=fq, K=20)))
    res.append(report("≥68 分层取20", rule(th=68.0, K=20)))

    print("\n==== 3. 止损（尾部对照）====")
    for m in (2.0, 3.0):
        res.append(report(f"前40% 满池 + {m:g}×ATR止损", rule(th=0.0, fq=0.40, stop=m)))
        res.append(report(f"≥68 满池 + {m:g}×ATR止损", rule(th=68.0, stop=m)))

    print("\n==== 4. 样本外（只用 2026-04-23 之后的相位段）====")
    for lab, rows in (("现行 ≥68", rule(th=68.0)), ("前40% 满池", rule(th=0.0, fq=0.40)),
                      ("前50% 满池", rule(th=0.0, fq=0.50)), ("前30% 满池", rule(th=0.0, fq=0.30)),
                      ("前40% 满池+2ATR", rule(th=0.0, fq=0.40, stop=2.0))):
        ph = phases(rows, since=CUTOFF)
        if not ph:
            continue
        ca = np.array([x["a"] for x in ph]); cb = np.array([x["b"] for x in ph])
        ba = np.array([bench_a(x["lo"], x["hi"]) for x in ph])
        cd = np.array([x["dd"] for x in ph])
        print(f"  {lab:<22} 相位 {len(ph):2d} 个（每个约 {ph[0]['n']} 期）  累计 {ca.mean()*100:+6.1f}%±"
              f"{ca.std()*100:4.1f}%  超B {(ca-cb).mean()*100:+6.1f}pp(赢{(ca > cb).mean()*100:3.0f}%)  "
              f"超A {(ca-ba).mean()*100:+6.1f}pp(赢{(ca > ba).mean()*100:3.0f}%)  回撤 {cd.mean()*100:5.1f}%")

    print("\n==== 5. 配对检验（逐日超额差，候选 − 现行）====")
    base = rule(th=68.0)
    bd = {x[0]: x[1] - x[2] for x in base}
    for lab, rows in (("前30% 满池", rule(th=0.0, fq=0.30)), ("前40% 满池", rule(th=0.0, fq=0.40)),
                      ("前50% 满池", rule(th=0.0, fq=0.50)), ("前40% 分层20", rule(th=0.0, fq=0.40, K=20)),
                      ("前40%+2ATR", rule(th=0.0, fq=0.40, stop=2.0)), ("≥60 满池", rule(th=60.0))):
        common = [x[0] for x in rows if x[0] in bd]
        m = {x[0]: x[1] - x[2] for x in rows}
        d = np.array([m[c] - bd[c] for c in common])
        print(f"  {lab:<20} 日均差 {d.mean()*100:+6.3f}%  t_HAC {H23.hac_t(d, HP):+5.2f}  "
              f"块自助p {H23.block_p(d, HP):.3f}  更好天数 {(d > 0).mean()*100:3.0f}%")

    print("\n==== 6. 择时与空仓对照（前40% 满池）====")
    for lab, rows in (("不择时（基准）", rule(th=0.0, fq=0.40)),
                      ("MA40 跌破空仓", Q.rows_rule(th=0.0, fq=0.40, K=None, H_=HP, min_pool=1,
                                                   regime=40, half=False)),
                      ("MA40 跌破半仓", Q.rows_rule(th=0.0, fq=0.40, K=None, H_=HP, min_pool=1,
                                                  regime=40, half=True))):
        report(lab, rows)


if __name__ == "__main__":
    main()
