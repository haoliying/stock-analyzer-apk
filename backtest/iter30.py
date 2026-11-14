#!/usr/bin/env python3
"""iter30 — 上线定稿：把"分散池"的边界分数线固定下来（要和 App 的档位语言兼容）。

iter28 的排序曲线显示分数前 30~50% 这一段最好，前 10~20% 反而差。但 App 现在的话术是
"≥68 才叫加仓线"，而前 40% 那条相对分数线中位数只有 46 分（有 80/418 天低于 43 持有线）。
所以定稿必须同时测：
  · 纯相对：前 X%（不看绝对分）
  · 相对 + 持有线：前 X% 且 ≥43   ← UI 语言自洽（入池的都不会是"该减仓"的）
  · 相对 + 60 分：前 X% 且 ≥60
两个规模：满池等权（理论上限）、分层抽 20 只（App 实际展示规模）。
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

ND, HP = P.ND, 20


def rows(th, fq, K, stop=None):
    return Q.rows_rule(th=th, fq=fq, K=K, H_=HP, stop=stop, min_pool=1,
                       mode=("strat" if K else "strat"))


def show(label, r):
    e = np.array([x[1] - x[2] for x in r])
    m = np.array([x[0] >= CUTOFF for x in r])
    ph = W.phases(r)
    ca = np.array([x["a"] for x in ph]); cb = np.array([x["b"] for x in ph])
    ba = np.array([W.bench_a(x["lo"], x["hi"]) for x in ph])
    cd = np.array([x["dd"] for x in ph])
    print(f"  {label:<26} 日均{np.mean([x[3] for x in r]):6.1f}只 超额{e.mean()*100:+6.3f}% "
          f"t_HAC {H23.hac_t(e, HP):+5.2f} p {H23.block_p(e, HP):.3f} 样本外{e[m].mean()*100:+6.3f}% | "
          f"累计{ca.mean()*100:+6.1f}%±{ca.std()*100:4.1f}% 超B{(ca-cb).mean()*100:+6.1f}pp"
          f"(赢{(ca > cb).mean()*100:3.0f}%相位) 超A{(ca-ba).mean()*100:+6.1f}pp"
          f"(赢{(ca > ba).mean()*100:3.0f}%) 回撤{cd.mean()*100:5.1f}% 最差单期{min(x['s'].min() for x in ph)*100:+5.1f}%")
    return dict(label=label, e=e, vsB=ca - cb, ca=ca)


def user_view(label, r, draws=20):
    """把 20 种抽法 × 20 个相位合起来看：普通用户拿到手的分布。"""
    vs, cs, dds = [], [], []
    for sd in range(draws):
        rr = [x for x in Q.rows_rule(th=0.0, fq=0.40, K=20, H_=HP, min_pool=1, mode="rand", seed=sd)]
        ph = W.phases(rr)
        ca = np.array([x["a"] for x in ph]); cb = np.array([x["b"] for x in ph])
        vs.append(ca - cb); cs.append(ca); dds.append(np.array([x["dd"] for x in ph]))
    vs = np.concatenate(vs); cs = np.concatenate(cs); dds = np.concatenate(dds)
    print(f"  {label}: 400 条曲线 超B 中位 {np.median(vs)*100:+5.1f}pp p10 {np.percentile(vs,10)*100:+5.1f}pp "
          f"赢 {(vs > 0).mean()*100:3.0f}%  累计中位 {np.median(cs)*100:+6.1f}%  回撤中位 {np.median(dds)*100:5.1f}%")


def main():
    bh = P.bench_hold(0, ND)
    cb = np.array([x["b"] for x in W.phases([(DATES[i], W.BM[i], 0.0, 1) for i in range(ND - HP - 1)])])
    print(f"\n大盘：买入持有 {bh*100:+.1f}% / 每20日再平衡 {cb.mean()*100:+.1f}%±{cb.std()*100:.1f}%")

    print("\n==== A. 满池等权（理论上限）====")
    for th, fq in ((0.0, 0.40), (43.0, 0.40), (60.0, 0.40), (0.0, 0.35), (43.0, 0.35),
                   (0.0, 0.30), (43.0, 0.30), (43.0, 0.50)):
        show(f"前{fq*100:.0f}%" + (f" 且≥{th:.0f}" if th else "") + " 满池", rows(th, fq, None))

    print("\n==== B. 分层抽 20 只（App 展示规模）====")
    for th, fq in ((0.0, 0.40), (43.0, 0.40), (60.0, 0.40), (43.0, 0.30), (43.0, 0.50)):
        show(f"前{fq*100:.0f}%" + (f" 且≥{th:.0f}" if th else "") + " 抽20", rows(th, fq, 20))

    print("\n==== C. 定稿候选 + 2×ATR / 3×ATR 止损的代价 ====")
    for th, fq, K in ((43.0, 0.40, None), (43.0, 0.40, 20)):
        lab = f"前{fq*100:.0f}% 且≥43 " + ("满池" if K is None else "抽20")
        show(lab + " 不止损", rows(th, fq, K))
        show(lab + " 2ATR", rows(th, fq, K, 2.0))
        show(lab + " 3ATR", rows(th, fq, K, 3.0))

    print("\n==== D. 现行规则同表对照 ====")
    show("现行 ≥68 满池", rows(68.0, None, None))
    show("现行 ≥68 抽10（旧展示）", rows(68.0, None, 10))

    print("\n==== E. 只买 20 只的普通用户分布（分层池 = 前40% 且 ≥43）====")
    user_view("前40% 随机20", rows(0.0, 0.40, 20))

    print("\n==== F. 入池分数线本身（前40% 且 ≥43 时每天实际有几只）====")
    r = rows(43.0, 0.40, None)
    n = np.array([x[3] for x in r])
    print(f"  满池只数 中位 {np.median(n):.0f}  p10 {np.percentile(n,10):.0f}  "
          f"p90 {np.percentile(n,90):.0f}  最少 {n.min()}  持仓率 {(n > 0).mean()*100:.0f}%")


if __name__ == "__main__":
    main()
