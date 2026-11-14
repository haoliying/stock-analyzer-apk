#!/usr/bin/env python3
"""diag_phase — 核对同一规则在两套口径下的累计差异来源。

iter23 用 P.daily(portsim 逐笔 trade_rets) + 8 个相位，报"现行 ≥68 全部等权 累计 +46.7%"；
iter26 用 iter25.rows_rule(向量化 ret_mat) + 20 个相位，报同一条规则 +82.3%。
差 35 个百分点必须解释清楚：是收益矩阵口径不同，还是相位网格的运气不同。
"""
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import fast as X  # noqa: E402
import iter25 as Q  # noqa: E402
import portsim as P  # noqa: E402
from model import CUTOFF, DATES  # noqa: E402

IDX = {d: i for i, d in enumerate(DATES)}
ND = P.ND


def cum_of(rows, H=20, phases=None, since=None):
    ds = [x[0] for x in rows]
    r = np.array([x[1] for x in rows])
    out = []
    for p in range(phases or H):
        jj = [j for j, d in enumerate(ds) if (since is None or d >= since) and (IDX[d] - p) % H == 0]
        if len(jj) < 3:
            continue
        out.append(float(np.prod(1 + r[jj]) - 1))
    return np.array(out)


def main():
    a = [(d, x, y, n) for d, x, y, n in P.daily(dict(score="new", th=68.0, K=None, H=20))]
    b = Q.rows_rule(th=68.0, fq=None, K=None, H_=20)
    print(f"P.daily 行数 {len(a)}  rows_rule 行数 {len(b)}")
    da = {x[0]: x for x in a}
    db = {x[0]: x for x in b}
    common = sorted(set(da) & set(db))
    print(f"共同日期 {len(common)}")
    dif = np.array([da[d][1] - db[d][1] for d in common])
    ndif = np.array([da[d][3] - db[d][3] for d in common])
    print(f"日均收益差 {dif.mean()*100:+.4f}%  |绝对差|>{0.0005} 的天数 {(np.abs(dif) > 5e-4).sum()}  "
          f"最大 {np.abs(dif).max()*100:.3f}%   只数差 均{ndif.mean():+.2f} 最大{np.abs(ndif).max():.0f}")
    print(f"P.daily 日均只数 {np.mean([x[3] for x in a]):.2f}   rows_rule {np.mean([x[3] for x in b]):.2f}")
    print(f"P.daily 日均收益 {np.mean([x[1] for x in a])*100:+.4f}%   rows_rule {np.mean([x[1] for x in b])*100:+.4f}%")
    print(f"P.daily 日均基准 {np.mean([x[2] for x in a])*100:+.4f}%   rows_rule {np.mean([x[2] for x in b])*100:+.4f}%")

    for ph in (5, 8, 10, 20):
        for nm, rows in (("P.daily", a), ("rows_rule", b)):
            c = cum_of(rows, phases=ph)
            print(f"  相位数 {ph:2d}  {nm:<10} 累计均值 {c.mean()*100:+7.1f}%  "
                  f"标准差 {c.std()*100:5.1f}%  区间 [{c.min()*100:+6.1f}%, {c.max()*100:+6.1f}%]  n={len(c)}")

    bh = P.bench_hold(0, ND)
    print(f"\n大盘 bench_hold {bh*100:+.1f}%")
    r, _ = P.universe_index(40)
    print(f"universe_index 末端 {r[-1]*100-100:+.1f}%")
    # 基准的"同网格再平衡"累计：每日全市场 20 日收益，按每 20 天网格累乘
    R = X.ret_mat(20)
    valid = X.valid_mat(Q.S0, 20)
    bm = np.nanmean(np.where(valid, R, np.nan), axis=1)
    bm = np.where(np.isnan(bm), 0.0, bm)
    rows = [(DATES[i], float(bm[i]), 0.0, 1) for i in range(ND - 21)]
    c = cum_of(rows, phases=20)
    print(f"同网格再平衡基准B：累计均值 {c.mean()*100:+.1f}% 区间 [{c.min()*100:+.1f}%, {c.max()*100:+.1f}%]"
          f"   （买入持有大盘 {bh*100:+.1f}%）")


if __name__ == "__main__":
    main()
