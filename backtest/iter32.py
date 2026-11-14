#!/usr/bin/env python3
"""iter32 — 定稿规则（前50%池·分层20）之上，还要不要留一个"最低分"？

动机是产品一致性，不是收益：名单里出现"强烈减仓（≤30）"的股票，用户会当成自相矛盾。
所以把绝对门槛直接加在分位池上测一遍：门槛剔掉的是池底那几只，同时也会改变分层取样的
位置（这会让结果抖动），因此必须看配对检验，而不是只看均值差。
"""
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import iter23 as H23  # noqa: E402
import iter31 as B  # noqa: E402
from model import DATES  # noqa: E402

ND, HP = B.ND, B.HP


def rows_f(th=0.0, fq=B.FINAL_FQ, K=B.FINAL_K):
    out = []
    for i in range(ND - 1 - HP):
        ranked = B.RANK[i]
        ranked = ranked[B.VALID[i, ranked]]
        n = len(ranked)
        pool = [j for j in ranked[:min(n, int(np.ceil(fq * n)))] if B.S[i, j] >= th]
        if not pool:
            out.append((DATES[i], 0.0, float(B.BM[i]), 0))
            continue
        pp = np.array(pool)
        q = len(pp)
        sel = pp if q <= K else pp[np.array(sorted({int(t * (q - 1) / K) for t in range(K)}))]
        v = B.R[i, sel]
        v = v[np.isfinite(v)]
        out.append((DATES[i], float(np.mean(v)) if v.size else 0.0, float(B.BM[i]), v.size))
    return out


def _sel(i, th):
    """门槛 th 下、分位池的分层取样结果（与 rows_f 同一条路径）。"""
    ranked = B.RANK[i]
    ranked = ranked[B.VALID[i, ranked]]
    n = len(ranked)
    pool = [j for j in ranked[:min(n, int(np.ceil(B.FINAL_FQ * n)))] if B.S[i, j] >= th]
    if not pool:
        return np.array([], int)
    pp = np.array(pool)
    q = len(pp)
    return pp if q <= B.FINAL_K else pp[np.array(sorted({int(t * (q - 1) / B.FINAL_K) for t in range(B.FINAL_K)}))]


def main():
    rr0 = B.rows()
    base = {x[0]: x[1] - x[2] for x in rr0}   # rr0 = 定稿（无门槛）
    print(f"  {'门槛':<12}{'日均只数':>8}{'名单最低分中位':>14}{'超额':>9}{'t_HAC':>7}{'p':>7}"
          f"{'超B':>8}{'相位赢':>9}{'回撤':>7}{'最差单期':>9}{'配对差':>8}{'配对t':>7}{'配对p':>7}")
    for th in (0.0, 15.0, 31.0, 43.0, 55.0, 68.0):
        r = rows_f(th)
        st = B.stat("", r, quiet=True)
        e = st["e"]
        lo = np.array([min((B.S[i, j] for j in _sel(i, th)), default=np.nan)
                       for i in range(ND - 1 - HP)])
        lo = lo[np.isfinite(lo)]
        cur = {x[0]: x[1] - x[2] for x in r}
        d = np.array([cur[c] - base[c] for c in cur if c in base])
        print(f"  ≥{th:<11.0f}{np.mean([x[3] for x in r]):8.1f}{np.median(lo):14.1f}"
              f"{e.mean()*100:+8.3f}%{H23.hac_t(e, HP):+7.2f}{H23.block_p(e, HP):7.3f}"
              f"{(st['ca'] - st['cb']).mean()*100:+7.1f}pp{int((st['ca'] > st['cb']).sum()):>5}/{len(st['ca'])}"
              f"{st['cd'].mean()*100:6.1f}%{st['flat'].min()*100:+8.1f}%"
              f"{d.mean()*100:+7.3f}%{H23.hac_t(d, HP):+7.2f}{H23.block_p(d, HP):7.3f}")

    # 名单里到底有多少只落在各档：按定稿规则跑一遍真实分布
    comp = {"BUY(≥68)": 0, "HOLD(43-67)": 0, "SELL(31-42)": 0, "STRONG_SELL(≤30)": 0}
    tot = 0
    for i in range(ND - 1 - HP):
        sel = B.basket(i)
        if sel.size == 0:
            continue
        for j in sel:
            s = B.S[i, j]
            tot += 1
            if s >= 68:
                comp["BUY(≥68)"] += 1
            elif s >= 43:
                comp["HOLD(43-67)"] += 1
            elif s > 30:
                comp["SELL(31-42)"] += 1
            else:
                comp["STRONG_SELL(≤30)"] += 1
    print(f"\n  定稿名单 {tot} 个持仓槽位的分数档分布：" +
          "  ".join(f"{k} {v/tot*100:.1f}%" for k, v in comp.items()))


if __name__ == "__main__":
    main()
