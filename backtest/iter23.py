#!/usr/bin/env python3
"""iter23 — 把"统计显著性"算对，再决定上不上线。

前面所有 t 值都建立在"每日一个 20 日持有"的样本上，相邻样本几乎完全重叠 ——
有效独立样本数只有 N/H ≈ 23，naive t 会虚高 sqrt(20)≈4.5 倍。
这一轮用三种口径复核：
  1) naive t（旧口径，仅供对照）
  2) Newey-West(HAC, Bartlett, L=H) 的 t
  3) 循环移动块自助法（block=H，2000 次）的 p 值
只有 HAC t 和块自助 p 都过关的方案才允许进入上线候选。
"""
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import fast as X  # noqa: E402
import iter22 as I  # noqa: E402
import portsim as P  # noqa: E402
from model import CUTOFF, DATES  # noqa: E402

ND = P.ND


def hac_t(x, L):
    x = np.asarray(x, float)
    n = x.size
    if n <= L + 2:
        return np.nan
    m = x.mean()
    d = x - m
    g0 = np.dot(d, d) / n
    s = g0
    for k in range(1, L + 1):
        w = 1.0 - k / (L + 1.0)
        gk = np.dot(d[k:], d[:-k]) / n
        s += 2.0 * w * gk
    se = np.sqrt(max(s, 1e-18) / n)
    return m / se


def block_p(x, L, B=2000, seed=7):
    """循环移动块自助：H0 = 日均超额 <= 0（把序列整体下移均值后重抽）"""
    x = np.asarray(x, float)
    n = x.size
    if n <= L:
        return np.nan
    rng = np.random.default_rng(seed)
    xb = x - x.mean()
    nb = n // L + 1
    cnt = 0
    for _ in range(B):
        starts = rng.integers(0, n, nb)
        s = np.concatenate([np.roll(xb, -st)[:L] for st in starts])[:n]
        if s.mean() >= x.mean():
            cnt += 1
    return cnt / B


def summarize(out, label, H=20, bh=0.389):
    ds = np.array([x[0] for x in out])
    r = np.array([x[1] for x in out])
    b = np.array([x[2] for x in out])
    e = r - b
    m = ds >= CUTOFF
    res = dict(label=label, mean=float(e.mean()), t_naive=float(hac_t(e, 0)),
               t_hac=float(hac_t(e, H)), p=float(block_p(e, H)),
               oos_mean=float(e[m].mean()), oos_hac=float(hac_t(e[m], H)),
               is_hac=float(hac_t(e[~m], H)))
    # 资金曲线
    idx = {d: i for i, d in enumerate(DATES)}
    cums, dds = [], []
    for p_ in range(8):
        jj = [j for j, d in enumerate(ds) if (idx[d] - p_) % H == 0]
        if len(jj) < 3:
            continue
        eq = np.cumprod(1 + r[jj])
        cums.append(eq[-1] - 1)
        dds.append((eq / np.maximum.accumulate(eq) - 1).min())
    res["cum"] = float(np.mean(cums))
    res["dd"] = float(np.mean(dds))
    print(f"  {label:<34} 日均超额 {res['mean']*100:+6.3f}%  t_naive {res['t_naive']:+5.2f}  "
          f"t_HAC {res['t_hac']:+5.2f}  块自助p {res['p']:.3f}  | 样本内 t_HAC {res['is_hac']:+5.2f} "
          f"样本外 均值{res['oos_mean']*100:+6.3f}% t_HAC {res['oos_hac']:+5.2f} | "
          f"累计 {res['cum']*100:+7.1f}% 超大盘 {(res['cum']-bh)*100:+6.1f}pp 回撤 {res['dd']*100:5.1f}%")
    return res


def main():
    bh = P.bench_hold(0, ND)
    print(f"\n大盘等权买入持有：累计 {bh*100:+.1f}%")
    print("\n==== 1. 现行上线做法（≥68分门槛，全部等权，可为空仓）====")
    P_rows = P.daily(dict(score="new", th=68.0, K=None, H=20))
    summarize([(d, x, y, n) for d, x, y, n in P_rows], "现行·68分全部等权")
    summarize(I.rows(0.30, 20, None), "前30%池·随机20（满仓）")
    summarize(I.rows(0.40, 20, None), "前40%池·随机20（满仓）")
    print("\n==== 2. 池内按量比 ====")
    for fq, K in ((0.30, 20), (0.40, 20), (0.40, 10), (0.50, 10), (0.50, 30)):
        summarize(I.rows(fq, K, "量比"), f"前{fq*100:.0f}%池·量比{K}")
    print("\n==== 3. 加上止损 ====")
    for fq, K, m in ((0.30, 20, 2.0), (0.40, 20, 2.0), (0.40, 20, 3.0), (0.50, 30, 2.0)):
        summarize(I.rows(fq, K, "量比", stop=m), f"前{fq*100:.0f}%池·量比{K}·止损{m}ATR")
    print("\n==== 4. 其它池内因子（含被否掉的，全部如实列出）====")
    for tie, K in (("PDI-MDI", 20), ("ADX", 20), ("换手", 20), ("低波动", 20), ("量比×价涨", 20)):
        summarize(I.rows(0.30, K, tie), f"前30%池·{tie}{K}")
    for tie in ("PDI-MDI", "换手"):
        summarize(I.rows(0.30, 20, tie, stop=2.0), f"前30%池·{tie}20·止损2ATR")
    print("\n==== 5. 门槛本身有没有用：分数前 X% 随机 vs 全市场随机 ====")
    summarize(I.rows(1.00, 20, None, seed=3), "全市场随机20（不看分数）")
    summarize(I.rows(0.20, 20, None), "前20%池·随机20")


if __name__ == "__main__":
    main()
