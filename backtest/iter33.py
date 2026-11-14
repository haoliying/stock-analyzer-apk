#!/usr/bin/env python3
"""iter33 — 名单只数 K 该取多少：收益、稳健性、尾部、以及"能不能真的执行"。

定稿池不变（当日可评分股票前 50%，无绝对门槛），只改 K：
  · 收益与显著性：日均超额、t_HAC、块自助 p
  · 稳健性：20 个换仓相位的累计均值±标准差、跑赢基准B 的相位数、平均最大回撤
  · 尾部：最差单期、亏超 10% 的期次占比
  · 可执行性：单只权重 = 100/K%，以及"单笔最低佣金 5 元"在不同本金下折算成的单边成本
    —— 只数越多，小资金越会被最低佣金吃掉，所以 K 不是越大越好，要看成本敏感度那一栏。
"""
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import fast as X  # noqa: E402
import iter23 as H23  # noqa: E402
import iter31 as B  # noqa: E402
from model import DATES  # noqa: E402

ND, HP = B.ND, B.HP
KS = [5, 10, 15, 20, 25, 30, 40, 50, 60, 80, 100, 120, None]


def line(tag, r):
    st = B.stat("", r, quiet=True)
    e = st["e"]
    ca, cb, cd = st["ca"], st["cb"], st["cd"]
    n = np.array([x[3] for x in r])
    return dict(tag=tag, ex=float(e.mean()), t=H23.hac_t(e, HP), p=H23.block_p(e, HP),
                cum=float(ca.mean()), sd=float(ca.std()), winB=int((ca > cb).sum()),
                ph=len(ca), dd=float(cd.mean()), worst=float(st["flat"].min()),
                tail10=float((st["flat"] < -0.10).mean()), nmean=float(n.mean()),
                nmin=int(n.min()))


def show(d, cost):
    k = "满池" if d["tag"] == "None" else d["tag"]
    print(f"  {cost:5.2f}%  {str(k):>6}  日均{d['nmean']:6.1f}只(最少{d['nmin']:3d})  "
          f"超额{d['ex']*100:+6.3f}% t {d['t']:+5.2f} p {d['p']:5.3f} | "
          f"累计{d['cum']*100:+6.1f}%±{d['sd']*100:4.1f}% 超B赢{d['winB']:2d}/{d['ph']}  "
          f"回撤{d['dd']*100:5.1f}% 最差单期{d['worst']*100:+6.1f}% 亏>10%{d['tail10']*100:4.1f}%")


def main():
    print("\n==== 1. 成本 0.12%/边（上线口径）：只数扫描 ====")
    base = {}
    for K in KS:
        d = line(str(K), B.rows(B.FINAL_FQ, K, "strat"))
        base[str(K)] = d
        show(d, 0.12)

    print("\n==== 2. 相对 20 只的配对检验（逐日超额差，K − 20）====")
    ref = {x[0]: x[1] - x[2] for x in B.rows(B.FINAL_FQ, 20, "strat")}
    for K in KS:
        if K is None or K <= 20:
            continue
        cur = {x[0]: x[1] - x[2] for x in B.rows(B.FINAL_FQ, K, "strat")}
        common = [c for c in cur if c in ref]
        d = np.array([cur[c] - ref[c] for c in common])
        print(f"  K={K:<4} 日均差 {d.mean()*100:+6.3f}%  t_HAC {H23.hac_t(d, HP):+5.2f}  "
              f"块自助p {H23.block_p(d, HP):5.3f}  更好天数 {(d > 0).mean()*100:3.0f}%")

    print("\n==== 3. 单只最低佣金 5 元折算成的单边成本（只数越多，小资金越吃亏）====")
    print(f"  {'K':>5} {'单只权重':>9} " + "".join(f"{'本金'+str(v//10000)+'万':>12}" for v in
                                                  (50000, 100000, 200000, 500000)))
    for K in [x for x in KS if x]:
        w = 1.0 / K
        row = "".join(f"{(5.0 / (cap * w)) * 100:11.2f}%" for cap in (50000, 100000, 200000, 500000))
        print(f"  {K:>5} {w*100:>8.1f}%{row}")

    print("\n==== 4. 成本敏感度：把单边成本抬到 0.20% / 0.30% 后各只数还剩多少 ====")
    keep = [10, 20, 30, 50, 80, None]
    for cost in (0.0020, 0.0030):
        X.COST = cost
        X._ret_cache.clear()
        B.R = X.ret_mat(HP)
        _bm = np.nanmean(np.where(B.VALID, B.R, np.nan), axis=1)
        B.BM = np.where(np.isnan(_bm), 0.0, _bm)
        for K in keep:
            show(line(str(K), B.rows(B.FINAL_FQ, K, "strat")), cost * 100)
    X.COST = 0.0012
    X._ret_cache.clear()

    print("\n==== 5. 每天池子里到底有几只能填满（池宽 50%，可评分中位 281 只）====")
    q = np.array([min(len(B.RANK[i][B.VALID[i, B.RANK[i]]]),
                      int(np.ceil(0.5 * B.VALID[i].sum()))) for i in range(ND - 1 - HP)])
    print(f"  池只数：中位 {np.median(q):.0f}  p10 {np.percentile(q,10):.0f}  最少 {q.min()}")
    for K in (20, 30, 40, 50, 60, 80):
        print(f"  K={K:<4} 池子够满的天数 {(q >= K).mean()*100:5.1f}%   日均实际只数 "
              f"{np.minimum(q, K).mean():.1f}")


if __name__ == "__main__":
    main()
