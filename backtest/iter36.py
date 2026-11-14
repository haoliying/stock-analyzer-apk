#!/usr/bin/env python3
"""iter36 — K=30 定稿规则体检表（App v4.3.0 要引用的就是这张表）。

把 iter31 的整套体检从 K=20 搬到 K=30，所有写进 Kotlin 注释/文案的数字都从这里取，
避免"文档说 20 只、代码跑 30 只"这种口径漂移。
"""
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import iter23 as H23  # noqa: E402
import iter25 as Q  # noqa: E402
import iter27 as W  # noqa: E402
import iter31 as B  # noqa: E402
from model import CUTOFF, DATES  # noqa: E402

ND, HP = B.ND, B.HP
FQ, K = B.FINAL_FQ, 30


def main():
    print(f"\n==== 1. 定稿：前{FQ*100:.0f}% 池 · 分层{K} · 不止损 ====")
    main = B.stat(f"前{FQ*100:.0f}% 分层{K}", B.rows(FQ, K, "strat"))
    B.stat(f"对照：分层20（v4.2.0）", B.rows(FQ, 20, "strat"))

    print(f"\n==== 2. 安慰剂（同规模 {K} 只）====")
    B.stat(f"池内分数最高的{K}只（排行榜）", B.rows(FQ, K, "top"))
    B.stat(f"随机{K}只（全市场，20 种子均值）", B.rows(1.00, K, "rand", seeds=tuple(range(20))))
    B.stat(f"随机{K}只（后{(1-FQ)*100:.0f}%，20 种子均值）",
           B.rows(FQ, K, "rand", seeds=tuple(range(20)), tail=True))

    print("\n==== 3. 配对检验（逐日超额差）====")
    def ex(rr):
        return {x[0]: x[1] - x[2] for x in rr}
    a30 = ex(B.rows(FQ, K, "strat"))
    for lab, rr in (("定稿30 − 定稿20", B.rows(FQ, 20, "strat")),
                    ("定稿30 − 现行(≥68满池)", Q.rows_rule(th=68.0, fq=None, K=None, H_=HP, min_pool=1)),
                    ("定稿30 − 池内最高分30", B.rows(FQ, K, "top"))):
        cur = ex(rr)
        common = [c for c in a30 if c in cur]
        d = np.array([a30[c] - cur[c] for c in common])
        print(f"  {lab:<22} 日均差 {d.mean()*100:+6.3f}%  t_HAC {H23.hac_t(d, HP):+5.2f}  "
              f"块自助p {H23.block_p(d, HP):.3f}  更好天数 {(d > 0).mean()*100:3.0f}%")

    print(f"\n==== 4. 止损的代价（定稿 {K} 只）====")
    for s in (2.0, 3.0):
        B.stat(f"定稿{K} · {s:.0f}×ATR 止损", B.rows(FQ, K, "strat", stop=s))

    print(f"\n==== 5. 档位构成（{K} 只槽位里各档占比）====")
    cnt = {"BUY": 0, "HOLD": 0, "SELL": 0, "STRONG_SELL": 0}
    tot, lo = 0, []
    for i in range(ND - 1 - HP):
        sel = B.basket(i, FQ, K, "strat")
        if sel.size == 0:
            continue
        sc = B.S[i, sel]
        tot += sel.size
        cnt["BUY"] += int((sc >= 68).sum())
        cnt["HOLD"] += int(((sc >= 43) & (sc < 68)).sum())
        cnt["SELL"] += int(((sc >= 31) & (sc < 43)).sum())
        cnt["STRONG_SELL"] += int((sc <= 30).sum())
        lo.append(float(sc.min()))
    for k_, v in cnt.items():
        print(f"  {k_:<12}{v / tot * 100:5.1f}%")
    print(f"  名单最低分：中位 {np.median(lo):.1f}  p10 {np.percentile(lo,10):.1f}")

    print("\n==== 6. 样本外 & 分月 ====")
    r = B.rows(FQ, K, "strat")
    e = np.array([x[1] - x[2] for x in r])
    ds = np.array([x[0] for x in r])
    o = ds >= CUTOFF
    print(f"  样本外（{CUTOFF} 起，{int(o.sum())} 天）日均超额 {e[o].mean()*100:+.3f}%  "
          f"t_HAC {H23.hac_t(e[o], HP):+.2f}")
    mo = {}
    for x in r:
        mo.setdefault(x[0][:7], []).append(x[1] - x[2])
    ks = sorted(mo)
    v = np.array([np.mean(mo[k_]) for k_ in ks])
    print(f"  分月 {len(v)} 个：正 {(v > 0).mean()*100:.0f}%  中位 {np.median(v)*100:+.2f}%  "
          f"最差 {v.min()*100:+.2f}%（{ks[int(v.argmin())]}）  最好 {v.max()*100:+.2f}%（{ks[int(v.argmax())]}）")

    print(f"\n==== 7. 真实用户分布（20 抽法 × 20 相位 = 400 条曲线，K={K}）====")
    vs, cs, dds, fl = [], [], [], []
    for sd in range(20):
        st = B.stat("", B.rows(FQ, K, "rand", seeds=(sd,)), quiet=True)
        vs.append(st["ca"] - st["cb"]); cs.append(st["ca"]); dds.append(st["cd"])
        fl.append(st["flat"])
    vs, cs, dds = np.concatenate(vs), np.concatenate(cs), np.concatenate(dds)
    fl = np.concatenate(fl)
    print(f"  超基准B：中位 {np.median(vs)*100:+.1f}pp  p10 {np.percentile(vs,10)*100:+.1f}pp  "
          f"跑赢概率 {(vs > 0).mean()*100:.0f}%")
    print(f"  累计：中位 {np.median(cs)*100:+.1f}%  p10 {np.percentile(cs,10)*100:+.1f}%  "
          f"最差 {cs.min()*100:+.1f}%")
    print(f"  最大回撤：中位 {np.median(dds)*100:.1f}%  最差 {dds.min()*100:.1f}%")
    print(f"  单期（20 交易日）：胜率 {(fl > 0).mean()*100:.0f}%  中位 {np.median(fl)*100:+.2f}%  "
          f"亏>10% {(fl < -0.10).mean()*100:.1f}%  最差 {fl.min()*100:+.1f}%")


if __name__ == "__main__":
    main()
