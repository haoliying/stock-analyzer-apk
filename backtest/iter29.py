#!/usr/bin/env python3
"""iter29 — 上线前最后一道检查：分季度稳定性、最差持仓期、真实用户能拿到的分布。

iter27/28 定的规则是"当日可评分股票里取分数前 40%，分层展示 20 只，等权满仓，持 20 个交易日"。
上线前必须回答三个问题：
  Q1 分段：这个超额是只在某一段行情里有，还是每个季度都在？
  Q2 尾部：历史上最差的几个 20 日持仓期，用户实际会看到什么数字？
  Q3 真实分布：一个只买 20 只的普通用户，20 个换仓相位 × 20 种抽法，
     他的累计收益分布（中位数、10 分位、90 分位、跑赢大盘的概率）是什么样？
"""
import os
import sys

from collections import Counter

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import fast as X  # noqa: E402
import iter23 as H23  # noqa: E402
import iter25 as Q  # noqa: E402
import iter27 as W  # noqa: E402
import portsim as P  # noqa: E402
from model import CUTOFF, DATES  # noqa: E402

ND, HP = P.ND, 20
BM = W.BM


def pool_rows(fq=0.40, K=20, stop=None, off=0, seed=None):
    return Q.rows_rule(th=0.0, fq=fq, K=K, H_=HP, stop=stop, min_pool=1,
                       mode=("strat" if seed is None else "rand"), off=off, seed=seed)


def quarterly(rows, label):
    e = np.array([x[1] - x[2] for x in rows])
    q = [x[0][:4] + "-Q" + str((int(x[0][5:7]) - 1) // 3 + 1) for x in rows]
    c = Counter(q)
    cells = []
    for k in sorted(c):
        m = np.array([x == k for x in q])
        cells.append(f"{k} {e[m].mean()*100:+4.1f}/{(e[m] > 0).mean()*100:3.0f}%")
    pos = sum(1 for k in sorted(c) if e[np.array([x == k for x in q])].mean() > 0)
    print(f"  {label}")
    print("    " + "  ".join(cells))
    print(f"    季度数 {len(c)}，正超额 {pos} 个（{pos/len(c)*100:.0f}%）")


def period_dist(rows, label):
    flat = np.array([float(v) for x in W.phases(rows) for v in x["s"]])
    qs = np.percentile(flat, [1, 5, 10, 25, 50, 75, 90])
    print(f"  {label}")
    print(f"    单期（20个交易日）收益 n={flat.size}  p1 {qs[0]*100:+5.1f}%  p5 {qs[1]*100:+5.1f}%  "
          f"p10 {qs[2]*100:+5.1f}%  p25 {qs[3]*100:+5.1f}%  中位 {qs[4]*100:+5.1f}%  "
          f"p75 {qs[5]*100:+5.1f}%  p90 {qs[6]*100:+5.1f}%  最差 {flat.min()*100:+5.1f}%  "
          f"亏>10% {(flat < -0.10).mean()*100:3.1f}%  亏>15% {(flat < -0.15).mean()*100:3.1f}%  "
          f"赚>10% {(flat > 0.10).mean()*100:3.1f}%")


def user_distribution(fq=0.40, K=20, stop=None):
    cs, vsb, dds, beata, beatb = [], [], [], [], []
    for sd in range(20):
        rows = pool_rows(fq, K, stop=stop, seed=sd)
        ph = W.phases(rows)
        ca = np.array([x["a"] for x in ph])
        cb = np.array([x["b"] for x in ph])
        ba = np.array([W.bench_a(x["lo"], x["hi"]) for x in ph])
        cs.append(ca); vsb.append(ca - cb); dds.append(np.array([x["dd"] for x in ph]))
        beata.append(ca > ba); beatb.append(ca > cb)
    ca = np.concatenate(cs); vb = np.concatenate(vsb)
    dd = np.concatenate(dds); ba_ = np.concatenate(beata); bb_ = np.concatenate(beatb)
    print(f"  前{fq*100:.0f}% 池 拿{K}只 {'+' + str(stop) + '×ATR止损' if stop else '不止损'}："
          f"{20*20} 条资金曲线（20 抽法 × 20 相位）")
    print(f"    累计 中位 {np.median(ca)*100:+6.1f}%  p10 {np.percentile(ca, 10)*100:+6.1f}%  "
          f"p90 {np.percentile(ca, 90)*100:+6.1f}%  最差 {ca.min()*100:+6.1f}%  最好 {ca.max()*100:+6.1f}%")
    print(f"    超同网格大盘 中位 {np.median(vb)*100:+6.1f}pp  p10 {np.percentile(vb, 10)*100:+6.1f}pp  "
          f"赢的概率 {(vb > 0).mean()*100:3.0f}%   超买入持有大盘 赢的概率 {(ba_).mean()*100:3.0f}%")
    print(f"    最大回撤 中位 {np.median(dd)*100:5.1f}%  p90 最差 {np.percentile(dd, 10)*100:5.1f}%  "
          f"最差 {dd.min()*100:5.1f}%")


def main():
    bh = P.bench_hold(0, ND)
    cb = np.array([x["b"] for x in W.phases([(DATES[i], BM[i], 0.0, 1) for i in range(ND - HP - 1)])])
    print(f"\n基准A 大盘买入持有 {bh*100:+.1f}%   基准B 大盘每20日再平衡 {cb.mean()*100:+.1f}%±{cb.std()*100:.1f}%")

    print("\n==== Q1 分季度日均超额 / 该季跑赢大盘天数占比 ====")
    quarterly(pool_rows(0.40, 20), "候选：前40% 池 · 分层 20 只 · 持 20 日 · 不止损")
    quarterly(pool_rows(0.40, 20, stop=3.0), "候选：同上 + 3×ATR 止损")
    quarterly(Q.rows_rule(th=68.0, fq=None, K=None, H_=HP, min_pool=1), "现行：≥68 全部等权")

    print("\n==== Q2 单期（20 个交易日）收益分布：用户真实会看到的数字 ====")
    period_dist(pool_rows(0.40, 20), "候选 前40%·20只")
    period_dist(pool_rows(0.40, 20, stop=2.0), "候选 前40%·20只·2ATR止损")
    period_dist(pool_rows(0.40, 20, stop=3.0), "候选 前40%·20只·3ATR止损")
    period_dist(Q.rows_rule(th=68.0, fq=None, K=None, H_=HP, min_pool=1), "现行 ≥68 全部等权")
    period_dist(Q.rows_rule(th=68.0, fq=None, K=None, H_=HP, min_pool=1, stop=2.0), "现行 ≥68 + 2ATR止损")

    print("\n==== Q3 只买 20 只的普通用户：400 条曲线的分布 ====")
    user_distribution(0.40, 20)
    user_distribution(0.40, 20, stop=3.0)
    user_distribution(0.40, 20, stop=2.0)
    user_distribution(0.50, 20)

    print("\n==== Q4 换手成本敏感性（每 20 日换仓，已含单边 0.12%）====")
    for cost in (0.0012, 0.0020, 0.0030):
        X.COST = cost
        X._ret_cache.clear()
        rows = pool_rows(0.40, 20)
        e = np.array([x[1] - x[2] for x in rows])
        ph = W.phases(rows)
        ca = np.array([x["a"] for x in ph]); cbb = np.array([x["b"] for x in ph])
        print(f"  单边成本 {cost*100:.2f}%（双边 {2*cost*100:.2f}%） 日均超额 {e.mean()*100:+6.3f}%  "
              f"t_HAC {H23.hac_t(e, HP):+5.2f}  累计 {ca.mean()*100:+6.1f}%  超B {(ca-cbb).mean()*100:+6.1f}pp")
    X.COST = 0.0012
    X._ret_cache.clear()


if __name__ == "__main__":
    main()
