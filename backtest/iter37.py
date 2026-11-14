#!/usr/bin/env python3
"""iter37 — 把 iter32 的"要不要留最低分门槛"在 K=30 上重做一遍。

结论要写进 Kotlin 注释，就必须和上线只数同口径（不能在 20 只上测完直接搬到 30 只）。
"""
import os
import sys
from math import ceil

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import iter23 as H23  # noqa: E402
import iter31 as B  # noqa: E402
from model import DATES  # noqa: E402

ND, HP = B.ND, B.HP
K = 30


def sel_of(i, th):
    ranked = B.RANK[i]
    ranked = ranked[B.VALID[i, ranked]]
    n = len(ranked)
    pool = [j for j in ranked[:min(n, int(ceil(B.FINAL_FQ * n)))] if B.S[i, j] >= th]
    if not pool:
        return np.array([], int)
    pp = np.array(pool)
    q = len(pp)
    return pp if q <= K else pp[np.array(sorted({int(t * (q - 1) / K) for t in range(K)}))]


def rows_f(th):
    out = []
    for i in range(ND - 1 - HP):
        s = sel_of(i, th)
        if s.size == 0:
            out.append((DATES[i], 0.0, float(B.BM[i]), 0))
            continue
        v = B.R[i, s]
        v = v[np.isfinite(v)]
        out.append((DATES[i], float(np.mean(v)) if v.size else 0.0, float(B.BM[i]), v.size))
    return out


def main():
    base = {x[0]: x[1] - x[2] for x in B.rows(B.FINAL_FQ, K, "strat")}
    print(f"  {'门槛':<10}{'日均只数':>8}{'最低分中位':>10}{'超额':>9}{'t':>6}{'p':>7}"
          f"{'超B':>8}{'相位赢':>8}{'回撤':>7}{'最差单期':>9}{'配对差':>9}{'配对t':>7}{'配对p':>7}")
    for th in (0.0, 15.0, 31.0, 43.0, 55.0, 68.0):
        r = rows_f(th)
        st = B.stat("", r, quiet=True)
        lo = np.array([min((B.S[i, j] for j in sel_of(i, th)), default=np.nan)
                       for i in range(ND - 1 - HP)])
        lo = lo[np.isfinite(lo)]
        cur = {x[0]: x[1] - x[2] for x in r}
        d = np.array([cur[c] - base[c] for c in cur if c in base])
        print(f"  ≥{th:<9.0f}{np.mean([x[3] for x in r]):8.1f}{np.median(lo):10.1f}"
              f"{st['e'].mean()*100:+8.3f}%{H23.hac_t(st['e'], HP):+6.2f}{H23.block_p(st['e'], HP):7.3f}"
              f"{(st['ca'] - st['cb']).mean()*100:+7.1f}pp{int((st['ca'] > st['cb']).sum()):>4}/{len(st['ca'])}"
              f"{st['cd'].mean()*100:6.1f}%{st['flat'].min()*100:+8.1f}%"
              f"{d.mean()*100:+8.3f}%{H23.hac_t(d, HP):+7.2f}{H23.block_p(d, HP):7.3f}")


if __name__ == "__main__":
    main()
