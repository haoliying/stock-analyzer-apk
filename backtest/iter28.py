#!/usr/bin/env python3
"""iter28 — 安慰剂检验：分数排序到底有没有信息？

iter27 的最优解（前50% 满池）20 个换仓相位全部跑赢"同网格再平衡的大盘"，块自助 p=0.050。
但"取前一半"这种操作，闭着眼睛取一半会不会也一样？必须做三件事：
  P1 单调性：分数前 10/20/…/100% 与 后 10/20/…/50% 的日均超额曲线；
  P2 安慰剂：随机取 50%/40%/20% 的种子平均（应≈0）；
  P3 可实现规模：只用 App 真能展示的 10~20 只时还剩多少。
"""
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import iter23 as H23  # noqa: E402
import iter25 as Q  # noqa: E402
import iter27 as W  # noqa: E402
import portsim as P  # noqa: E402
from model import CUTOFF, DATES  # noqa: E402

ND, NS = P.ND, P.NS
HP = 20
S = Q.S0
VALID = W.VALID
BM = W.BM
order = np.argsort(np.where(VALID, S, -np.inf), axis=1)[:, ::-1]


def slice_rows(lo_q, hi_q, seed=None, K=None):
    """取分数排名在 [lo_q, hi_q) 区间的那一段（lo=0 即最高分）。"""
    out = []
    for i in range(ND - 1 - HP):
        ranked = order[i]
        ranked = ranked[VALID[i, ranked]]
        n = len(ranked)
        a, b = int(round(lo_q * n)), int(round(hi_q * n))
        pp = ranked[a:b]
        if len(pp) < 3:
            out.append((DATES[i], 0.0, float(BM[i]), 0))
            continue
        if K and len(pp) > K:
            if seed is None:
                nn = len(pp)
                pp = pp[np.array(sorted({int(t * (nn - 1) / K) for t in range(K)}))]
            else:
                pp = np.random.default_rng(seed * 100003 + i).choice(pp, K, replace=False)
        rr = W.R20[i, pp]
        rr = rr[np.isfinite(rr)]
        out.append((DATES[i], float(np.mean(rr)) if rr.size else 0.0, float(BM[i]), len(rr)))
    return out


def line(rows, label):
    e = np.array([x[1] - x[2] for x in rows])
    n = np.array([x[3] for x in rows])
    ph = W.phases(rows)
    ca = np.array([x["a"] for x in ph]); cb = np.array([x["b"] for x in ph])
    ba = np.array([W.bench_a(x["lo"], x["hi"]) for x in ph])
    cd = np.array([x["dd"] for x in ph])
    m = np.array([x[0] >= CUTOFF for x in rows])
    print(f"  {label:<24} 日均{n.mean():6.1f}只 超额{e.mean()*100:+6.3f}% t_HAC {H23.hac_t(e, HP):+5.2f} "
          f"p {H23.block_p(e, HP):.3f} 样本外{e[m].mean()*100:+6.3f}% | "
          f"累计{ca.mean()*100:+6.1f}%±{ca.std()*100:4.1f}% 超B{(ca-cb).mean()*100:+6.1f}pp"
          f"(赢{(ca > cb).mean()*100:3.0f}%) 超A{(ca-ba).mean()*100:+6.1f}pp(赢{(ca > ba).mean()*100:3.0f}%) "
          f"回撤{cd.mean()*100:5.1f}%")
    return e


def main():
    bh = P.bench_hold(0, ND)
    cb_all = np.array([x["b"] for x in W.phases([(DATES[i], BM[i], 0.0, 1) for i in range(ND - HP - 1)])])
    print(f"\n基准A 大盘买入持有 {bh*100:+.1f}%  基准B 大盘每20日再平衡 {cb_all.mean()*100:+.1f}%"
          f"±{cb_all.std()*100:.1f}%")

    print("\n==== P1a 前 X%（高分端）满池：超额应随区间下移而衰减 ====")
    for q in (0.10, 0.20, 0.30, 0.40, 0.50, 0.60, 0.70, 0.80, 0.90, 1.00):
        line(slice_rows(0.0, q), f"前{q*100:3.0f}%")

    print("\n==== P1b 后 X%（低分端）满池：安慰剂的反面 ====")
    for q in (0.10, 0.20, 0.30, 0.40, 0.50):
        line(slice_rows(1.0 - q, 1.0), f"后{q*100:3.0f}%")

    print("\n==== P1c 中间段：证明这是排序信息不是'一半'本身 ====")
    line(slice_rows(0.50, 1.00), "中后段 50~100%")
    line(slice_rows(0.25, 0.75), "中间 25~75%")

    print("\n==== P2 安慰剂：随机取同样比例（20 个种子平均，不看分数）====")
    for q in (0.20, 0.40, 0.50):
        es, cs, bs, vb = [], [], [], []
        for sd in range(20):
            rows = []
            for i in range(ND - 1 - HP):
                ranked = order[i]
                ranked = ranked[VALID[i, ranked]]
                k = max(1, int(round(q * len(ranked))))
                pp = np.random.default_rng(sd * 100003 + i).choice(ranked, k, replace=False)
                rr = W.R20[i, pp]
                rr = rr[np.isfinite(rr)]
                rows.append((DATES[i], float(np.mean(rr)) if rr.size else 0.0, float(BM[i]), len(rr)))
            e = np.array([x[1] - x[2] for x in rows])
            ph = W.phases(rows)
            ca = np.array([x["a"] for x in ph]); cb = np.array([x["b"] for x in ph])
            es.append(e.mean()); cs.append(ca.mean()); vb.append((ca - cb).mean()); bs.append((ca > cb).mean())
        es = np.array(es); cs = np.array(cs); bs = np.array(bs); vb = np.array(vb)
        print(f"  随机{q*100:.0f}%（20种子） 日均超额 均{es.mean()*100:+6.3f}% ±{es.std()*100:4.3f}  "
              f"累计 均{cs.mean()*100:+6.1f}% ±{cs.std()*100:4.1f}%  "
              f"超B 均{vb.mean()*100:+6.1f}pp（{bs.mean()*100:3.0f}% 相位赢B）  "
              f"赢大盘A {(cs > bh).mean()*100:3.0f}%种子")

    print("\n==== P3 可实现规模：从池里只拿 10 / 20 只（20 种抽法的均值±标准差）====")
    for q, K in ((0.40, 10), (0.50, 10), (0.40, 20), (0.50, 20), (0.30, 20), (0.50, 30), (0.50, 50)):
        es, cs, ds_, bs, vb = [], [], [], [], []
        for sd in range(20):
            rows = slice_rows(0.0, q, seed=sd, K=K)
            e = np.array([x[1] - x[2] for x in rows])
            ph = W.phases(rows)
            ca = np.array([x["a"] for x in ph]); cb = np.array([x["b"] for x in ph])
            es.append(e.mean()); cs.append(ca.mean()); vb.append((ca - cb).mean())
            ds_.append(np.array([x["dd"] for x in ph]).mean()); bs.append((ca > cb).mean())
        print(f"  前{q*100:.0f}% 池 · 只拿{K:2d}只  超额{np.mean(es)*100:+6.3f}%±{np.std(es)*100:4.3f}  "
              f"累计{np.mean(cs)*100:+6.1f}%±{np.std(cs)*100:4.1f}%  "
              f"超B{np.mean(vb)*100:+6.1f}pp±{np.std(vb)*100:4.1f}  相位赢B {np.mean(bs)*100:3.0f}%  "
              f"回撤{np.mean(ds_)*100:5.1f}%±{np.std(ds_)*100:4.1f}")

    print("\n==== P4 最优池的分年、分月稳定性 ====")
    rows = slice_rows(0.0, 0.50)
    e = np.array([x[1] - x[2] for x in rows])
    ds = [x[0] for x in rows]
    for y in sorted({d[:4] for d in ds}):
        m = np.array([d.startswith(y) for d in ds])
        ph = W.phases([r for r, k in zip(rows, m) if k])
        ca = np.array([x["a"] for x in ph]); cb = np.array([x["b"] for x in ph])
        print(f"  {y} 天{m.sum():3d}  日均超额{e[m].mean()*100:+6.3f}% t_HAC {H23.hac_t(e[m], HP):+5.2f}  "
              f"跑赢天数{(e[m] > 0).mean()*100:3.0f}%  该年累计 策略{ca.mean()*100:+6.1f}% "
              f"基准B{cb.mean()*100:+6.1f}%  超B{(ca-cb).mean()*100:+6.1f}pp")
    mo = {}
    for x in rows:
        mo.setdefault(x[0][:7], []).append(x[1] - x[2])
    v = np.array([np.mean(mo[k]) for k in sorted(mo)])
    k = sorted(mo)
    print(f"  分月（{len(k)} 个月）正超额 {np.mean(v > 0)*100:3.0f}%  中位 {np.median(v)*100:+5.2f}%  "
          f"最差 {v.min()*100:+5.2f}%（{k[int(np.argmin(v))]}）  最好 {v.max()*100:+5.2f}%（{k[int(np.argmax(v))]}）")


if __name__ == "__main__":
    main()
