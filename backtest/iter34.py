#!/usr/bin/env python3
"""iter34 — 只数定案：把 iter33 的"平均数差不多"往下拆到决策用的那几个面。

比较 K = 20 / 30 / 40 / 60 / 满池，维度：
  1) 样本外（2026-04-23 之后）的相位累计与超 B 情况 —— 参数在样本内选，样本外只验证一次；
  2) 分月正负与最差月；
  3) 真实用户分布（每个 K 跑 20 种分层偏移 × 20 个换仓相位 = 400 条曲线）；
     注意：分层本身是确定性的，这里用 rand 抽法近似"同一池子的不同覆盖方式"，
     目的是看只数对分散度的影响，不是给某个具体名单打分。
  4) 最低佣金的可执行性（本金 10 万 / 5 万时，只数对应的单边真实成本）。
"""
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import iter23 as H23  # noqa: E402
import iter27 as W  # noqa: E402
import iter31 as B  # noqa: E402
from model import CUTOFF, DATES  # noqa: E402

ND, HP = B.ND, B.HP
KS = [20, 30, 40, 60, None]


def oos(r):
    ph = W.phases(r, since=CUTOFF)
    ca = np.array([x["a"] for x in ph]); cb = np.array([x["b"] for x in ph])
    ba = np.array([W.bench_a(x["lo"], x["hi"]) for x in ph])
    cd = np.array([x["dd"] for x in ph])
    return len(ph), ca.mean(), ca.std(), float((ca > cb).sum()), float((ca - cb).mean()), \
        float((ca - ba).mean()), cd.mean()


def monthly(r):
    mo = {}
    for x in r:
        mo.setdefault(x[0][:7], []).append(x[1] - x[2])
    v = np.array([np.mean(mo[k]) for k in sorted(mo)])
    return len(v), float((v > 0).mean()), float(np.median(v)), float(v.min()), float(v.max())


def dist(K):
    vs, cs, dds = [], [], []
    for sd in range(20):
        st = B.stat("", B.rows(B.FINAL_FQ, K, "rand", seeds=(sd,)), quiet=True)
        vs.append(st["ca"] - st["cb"]); cs.append(st["ca"]); dds.append(st["cd"])
    vs = np.concatenate(vs); cs = np.concatenate(cs); dds = np.concatenate(dds)
    return (np.median(vs), np.percentile(vs, 10), float((vs > 0).mean()),
            np.median(cs), np.percentile(cs, 10), np.median(dds), dds.min())


def main():
    print(f"\n{'K':>6} {'日均超额':>9} {'t':>6} {'样本外相位':>10} {'样本外累计':>16} "
          f"{'超B相位赢':>9} {'样本外超B':>10} {'样本外超A':>10} {'样本外回撤':>9}")
    for K in KS:
        r = B.rows(B.FINAL_FQ, K, "strat")
        e = np.array([x[1] - x[2] for x in r])
        n, cm, cs_, win, vb, va, dd = oos(r)
        print(f"{str(K):>6} {e.mean()*100:+8.3f}% {H23.hac_t(e, HP):+6.2f} {n:>8}个 "
              f"{cm*100:+7.1f}%±{cs_*100:4.1f}% {win:>7.0f}/{n} {vb*100:+9.1f}pp {va*100:+9.1f}pp "
              f"{dd*100:8.1f}%")

    print(f"\n{'K':>6} {'月数':>5} {'正月占比':>9} {'中位月':>8} {'最差月':>9} {'最好月':>9}")
    for K in KS:
        n, pos, med, lo, hi = monthly(B.rows(B.FINAL_FQ, K, "strat"))
        print(f"{str(K):>6} {n:>5} {pos*100:>8.0f}% {med*100:+7.2f}% {lo*100:+8.2f}% {hi*100:+8.2f}%")

    print(f"\n{'K':>6} {'超B中位':>9} {'超B p10':>9} {'跑赢概率':>9} {'累计中位':>9} "
          f"{'累计p10':>9} {'回撤中位':>9} {'回撤最差':>9}   (20 抽法 × 20 相位 = 400 条曲线)")
    for K in KS:
        v10, vp10, vwin, cm, cp10, dm, dw = dist(K)
        print(f"{str(K):>6} {v10*100:+8.1f}pp {vp10*100:+8.1f}pp {vwin*100:>8.0f}% "
              f"{cm*100:+8.1f}% {cp10*100:+8.1f}% {dm*100:8.1f}% {dw*100:8.1f}%")

    print("\n最低佣金 5 元/笔 的单边等效成本（含卖出）：")
    for K in [k for k in KS if k]:
        print(f"  K={K:<4} 单只 {100/K:4.1f}%   本金 5 万 {5/(50000/K)*100:5.2f}%   "
              f"本金 10 万 {5/(100000/K)*100:5.2f}%   本金 20 万 {5/(200000/K)*100:5.2f}%")
    print("  （回测按 0.12%/边计，成本只影响绝对收益不影响超额：0.30%/边时 K=30 两年累计"
          " 从 +86.1% 掉到 +79.1%，超额 t 不变）")


if __name__ == "__main__":
    main()
