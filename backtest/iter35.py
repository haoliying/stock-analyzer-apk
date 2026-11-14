#!/usr/bin/env python3
"""iter35 — 只数的下界已经够了，这里量上界：A 股"1 手 100 股 + 5 元最低佣金"到底允许几只。

前面几轮说明 K 从 20 到满池，日均超额没有区别（配对 |t|≤0.61），而分散度越好评测越稳。
所以决定 K 的其实不是收益，是**这个名单能不能真的下单**：
  1) 最小申报单位：主板/创业板 100 股起，科创板(688) 200 股起 —— 名单里价格最高的那几只
     决定了"要凑齐 K 只等权，本金至少要多少"；
  2) 5 元最低佣金：单只金额越小，等效费率越高（K=40、5 万本金 → 0.40%/边）；
  3) 下不动的那几只怎么办：把买不起的槽位等权摊给买得起的，看实际有效只数（HHI）掉到多少；
  4) 每次换仓的工作量：K 只 = 每 20 个交易日 K 笔买 + K 笔卖，以及名单的重叠率。
"""
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import iter27 as W  # noqa: E402
import iter31 as B  # noqa: E402
import portsim as P  # noqa: E402
from model import DATES  # noqa: E402

ND, HP = B.ND, B.HP
CLOSE = P.MAT["close"]
CODES = np.array([str(F["code"]) for F in P.stocks])
# 最小申报股数：科创板 200 股起（按 1 股递增，这里用 200 作下限），其余 100 股
LOT = np.where(np.char.startswith(CODES, "68"), 200, 100)
KS = [15, 20, 30, 40, 60, None]
CAPS = [30000, 50000, 100000, 200000]


def picks(i, K):
    return B.basket(i, B.FINAL_FQ, K, "strat")


def main():
    nvalid = np.array([int(B.VALID[i].sum()) for i in range(ND - 1 - HP)])
    print(f"\n可评分股票数：中位 {np.median(nvalid):.0f} 只，"
          f"池（前50%）中位 {np.median(nvalid)/2:.0f} 只，最少 {nvalid.min()} 只")

    print("\n==== 1. 凑齐整张名单需要的本金（等权、每只至少 1 手）====")
    print(f"{'K':>6} {'名单最贵中位':>12} {'最贵p90':>10} {'所需本金中位':>13} {'所需本金p90':>13} "
          f"{'5万买得起':>10} {'10万买得起':>10}")
    for K in KS:
        need, hi = [], []
        for i in range(0, ND - 1 - HP, 3):      # 每 3 天抽 1 天，够稳也快
            sel = picks(i, K)
            if sel.size == 0:
                continue
            sel = sel[np.isfinite(CLOSE[i, sel])]
            if sel.size == 0:
                continue
            p = CLOSE[i, sel]
            k = sel.size
            need.append(k * np.max(p * LOT[sel]))
            hi.append(np.max(p))
        need, hi = np.array(need), np.array(hi)
        # 每只槽位金额 = 本金 / k
        ok5 = float((need <= 50000).mean())
        ok10 = float((need <= 100000).mean())
        print(f"{str(K):>6} {np.median(hi):>10.1f}元 {np.percentile(hi,90):>9.1f}元 "
              f"{np.median(need)/10000:>11.1f}万 {np.percentile(need,90)/10000:>11.1f}万 "
              f"{ok5*100:>9.0f}% {ok10*100:>9.0f}%")

    print("\n==== 2. 本金不够时的真实有效只数（买不起的槽位摊给其余）====")
    print(f"{'本金':>8} " + " ".join(f"{'K='+str(k):>14}" for k in KS if k))
    for cap in CAPS:
        line = f"{cap/10000:>6.0f}万 "
        for K in KS:
            if K is None:
                line += f"{'—':>15}"
                continue
            eff, buy = [], []
            for i in range(0, ND - 1 - HP, 3):
                sel = picks(i, K)
                if sel.size == 0:
                    continue
                p, l = CLOSE[i, sel], LOT[sel]
                good = np.isfinite(p) & (cap / sel.size >= p * l)
                buy.append(float(good.mean()))
                w = good.sum()
                if w:
                    # 摊平后仍受整手约束：有效只数用 HHI 倒数近似（未再解整数规划，偏乐观）
                    eff.append(float(w))
            line += f" 买得起{np.mean(buy)*100:3.0f}% 有效{np.mean(eff):3.0f}"
        print(line)

    print("\n==== 3. 5 元最低佣金的等效单边费率 ====")
    print(f"{'K':>6}" + "".join(f"{'本金 ' + str(c // 10000) + '万':>12}" for c in CAPS))
    for K in KS:
        if K is None:
            continue
        print(f"{K:>6}" + "".join(f"{5 / (c / K) * 100:>11.2f}%" for c in CAPS))
    print("  （回测口径 0.12%/边；超出部分 × 每 20 日 2 边 × 23 期 ≈ 两年绝对收益的额外损耗）")
    for K in [20, 30, 40, 60]:
        for c in (30000, 50000, 100000):
            extra = 2 * max(0.0, 5 / (c / K) - 0.0012)
            print(f"    K={K:<3} 本金 {c/10000:.0f}万 → 两年额外 {extra*23*100:5.1f}pp", end="")
        print()

    print("\n==== 4. 换仓工作量（20 个交易日一次，重叠部分不用动）====")
    print(f"{'K':>6} {'每期留存量':>10} {'每次下单笔数':>12} {'两年累计笔数':>12} {'两年累计佣金(5元/笔)':>18}")
    for K in KS:
        ov, sz = [], []
        prev = None
        for i in range(0, ND - 1 - HP, HP):
            sel = set(int(x) for x in picks(i, K))
            if not sel:
                continue
            sz.append(len(sel))
            if prev is not None:
                ov.append(len(sel & prev) / max(1, len(prev)))
            prev = sel
        k = int(np.mean(sz)) if sz else 0
        o = float(np.mean(ov)) if ov else 0.0
        per = 2 * k * (1 - o)
        print(f"{str(K):>6} {o*100:>9.0f}% {per:>12.0f} {per*len(ov):>12.0f} "
              f"{per*len(ov)*5:>16.0f}元")


if __name__ == "__main__":
    main()
