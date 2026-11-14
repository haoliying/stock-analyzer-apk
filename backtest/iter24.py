#!/usr/bin/env python3
"""iter24 — 对评分函数本身做**预先登记**的几处修改（不做贪心搜索）。

诊断依据：头 5% 是反指（超额 −1.06%，naive t −4.5），而前 30~40% 最好（+0.8%），
说明"斜率越陡加分越多 + 站上均线就加分"把分数最高的位置留给了最拥挤、最超买的票。
所以候选改动全部围绕"给追头降温"，每个都单独测，样本内/样本外都要看，
并和上线版本在同一口径（同池宽度、同 K、同基准）下比较。
"""
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import fast as X  # noqa: E402
import iter22 as I  # noqa: E402
import iter23 as H  # noqa: E402
import portsim as P  # noqa: E402
from model import DATES  # noqa: E402

F = lambda k: P.FAC[k] if k in P.FAC else P.MAT[k]  # noqa: E731
C, M10, M20, M60 = F("close"), F("ma10"), F("ma20"), F("ma60")
SL, ATRP, VOLR = F("ma20_slope"), F("atr_pct"), F("volr5")
RET5 = F("ret5")


def score(knee=0.020, pen=200.0, sl_hi=0.015, b_lo=6.0, b_mid=14.0, b_hi=22.0,
          updown=True, ma10_p=4.0, ma10_n=-2.0, ext_knee=0.05, ext_pen=0.0,
          steep_cut=0.0, volr_bonus=0.0, volr_need_up=False, slope_atr_norm=0.0):
    """上线模型 + 若干开关；默认参数必须与 final_validate.new_fn 完全一致。"""
    up = (C > M20) & (M20 > M60)
    dn = (C < M20) & (M20 < M60)
    hi_b = np.where(SL > sl_hi, b_hi, np.where(SL > 0, b_mid, b_lo))
    lo_b = np.where(SL < -sl_hi, b_hi, np.where(SL < 0, b_mid, b_lo))
    S = 50.0 + np.select([up, dn], [hi_b, -lo_b if updown else -b_lo], -3.0)
    if steep_cut:                       # 斜率过热降温：超过 2*sl_hi 后奖励递减
        S = S - steep_cut * np.clip((np.abs(SL) - 2 * sl_hi) / sl_hi, 0, 2) * np.sign(SL) * np.where(up | dn, 1, 0)
    S = S + np.where((C > M10) & (M10 > M20), ma10_p, ma10_n)
    a = np.nan_to_num(ATRP)
    S = S - np.maximum(0.0, a - knee) * pen
    if slope_atr_norm:                  # 斜率按波动归一（高波动的陡斜率不算强）
        S = S - slope_atr_norm * np.clip(np.abs(SL) / np.maximum(a, 0.005) - 0.5, 0, 2) * np.where(up | dn, 1, 0)
    if ext_pen:                         # 离 MA20 太远（追高）扣分
        S = S - ext_pen * np.clip((C / M20 - 1.0 - ext_knee) / 0.05, 0, 2)
    if volr_bonus:                      # 放量确认
        cond = (VOLR > 1.15) & (RET5 > 0) if volr_need_up else (VOLR > 1.15)
        S = S + volr_bonus * np.nan_to_num(cond.astype(float))
    valid = ~(np.isnan(C) | np.isnan(M10) | np.isnan(M20) | np.isnan(M60) | np.isnan(SL))
    return np.where(valid, np.clip(S, 0, 100), np.nan)


VARIANTS = [
    ("上线(基线)", dict()),
    ("A 追高降温 ext_pen=4", dict(ext_pen=4.0)),
    ("A 追高降温 ext_pen=8", dict(ext_pen=8.0)),
    ("A ext_knee=3% pen=6", dict(ext_knee=0.03, ext_pen=6.0)),
    ("B 斜率过热递减 cut=6", dict(steep_cut=6.0)),
    ("B 斜率过热递减 cut=12", dict(steep_cut=12.0)),
    ("C 波动斜率归一 10", dict(slope_atr_norm=10.0)),
    ("C 波动斜率归一 20", dict(slope_atr_norm=20.0)),
    ("D 放量确认 +4", dict(volr_bonus=4.0)),
    ("D 放量+价涨 +4", dict(volr_bonus=4.0, volr_need_up=True)),
    ("D 放量确认 +8", dict(volr_bonus=8.0)),
    ("E 波动阈值 2.5%", dict(knee=0.025)),
    ("E 波动阈值 3.0%", dict(knee=0.030)),
    ("F 均线结构不对称 dn=−6", dict(updown=False)),
    ("G 组合 A+D ext6+volr4", dict(ext_pen=6.0, volr_bonus=4.0)),
]


def rows_q(S, fq, K, tie=None, H_=20, seed=1):
    """与 iter22.rows 同口径，但用给定的分数矩阵"""
    valid = X.valid_mat(S, H_)
    R = X.ret_mat(H_)
    bench = np.nanmean(np.where(valid, R, np.nan), axis=1)
    bench = np.where(np.isnan(bench), 0.0, bench)
    kf = max(1, int(round(fq * P.NS)))
    order = np.argsort(np.where(valid, S, -np.inf), axis=1)[:, ::-1]
    out = []
    for i in range(P.ND - 1 - H_):
        pool = order[i, :kf]
        pool = pool[valid[i, pool]]
        if len(pool) < 3:
            continue
        if tie is None:
            sel = np.random.default_rng(seed * 100003 + i).choice(pool, min(K, len(pool)), replace=False)
        else:
            kk = I.KEYMAT[tie][i, pool]
            sel = pool[np.argsort(-np.nan_to_num(kk, nan=-1e9))[:K]]
        rr = R[i, sel]
        rr = rr[np.isfinite(rr)]
        if rr.size:
            out.append((DATES[i], float(np.mean(rr)), float(bench[i]), len(rr)))
    return out


def main():
    base = score()
    loop = P.MAT["_S"]["new"]
    m = ~np.isnan(loop) & ~np.isnan(base)
    print(f"\n[对齐检查] 与 final_validate.new_fn 逐 bar 比较：{m.sum()} 个有效 bar，"
          f"最大偏差 {np.abs(loop[m] - base[m]).max():.6f}")

    print("\n==== 1. 各变体在'前40%池·随机20'口径下的表现（HAC 统计）====")
    bh = P.bench_hold(0, P.ND)
    res = {}
    for lab, kw in VARIANTS:
        S = score(**kw) if kw else base
        r40 = H.summarize(rows_q(S, 0.40, 20), f"{lab:<28} 前40%随机20", bh=bh)
        r5 = H.summarize(rows_q(S, 0.05, 14), f"{lab:<28} 前5%(头部)", bh=bh)
        res[lab] = (r40["t_hac"], r40["p"], r40["oos_mean"], r5["mean"], r40["cum"], r40["dd"])
    print("\n==== 2. 汇总表（前40%池随机20）====")
    print(f"  {'变体':<28} {'t_HAC':>7} {'p':>6} {'OOS日均超额':>11} {'头部5%超额':>11} {'累计':>8} {'回撤':>7}")
    for lab, (t, p, oos, head, cum, dd) in res.items():
        print(f"  {lab:<28} {t:+7.2f} {p:6.3f} {oos*100:+10.3f}% {head*100:+10.2f}% {cum*100:+7.1f}% {dd*100:6.1f}%")
    print("\n==== 3. 最优两个变体再加'量比排序'与止损复核 ====")
    best = sorted(res.items(), key=lambda kv: -kv[1][0])[:3]
    for lab, _ in best:
        kw = dict(VARIANTS)[lab] if lab in dict(VARIANTS) else {}
        S = score(**kw) if kw else base
        H.summarize(rows_q(S, 0.40, 20, tie="量比"), f"{lab} 前40%量比20", bh=bh)
        H.summarize(rows_q(S, 0.30, 20, tie="量比"), f"{lab} 前30%量比20", bh=bh)


if __name__ == "__main__":
    main()
