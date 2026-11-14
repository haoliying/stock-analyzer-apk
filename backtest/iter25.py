#!/usr/bin/env python3
"""iter25 — 候选上线规则的同台对比（同一基准、同一止损近似、同一统计口径）。

规则形态：池 = 当日可评分股票里"分数前 fq 名 **且** 分数 >= th"，
若池不足 min_pool 只则空仓；从池里分层抽 K 只等权，持 H 个交易日。
R0 = 现行上线（≥68 全部等权，可空仓），用来当被检验对象。
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
from model import CUTOFF, DATES  # noqa: E402

ND, NS = P.ND, P.NS
S0 = P.MAT["_S"]["new"]


def rows_rule(th=68.0, fq=None, K=20, H_=20, stop=None, min_pool=3, mode="strat",
              regime=None, half=False, S=None, off=0, seed=17):
    S = S0 if S is None else S
    valid = X.valid_mat(S, H_)
    R = X.ret_mat(H_) if stop is None else I.stop_ret(H_, stop, valid)
    bench = np.nanmean(np.where(valid, R if stop is None else X.ret_mat(H_), np.nan), axis=1)
    bench = np.where(np.isnan(bench), 0.0, bench)
    order = np.argsort(np.where(valid, S, -np.inf), axis=1)[:, ::-1]
    out = []
    for i in range(ND - 1 - H_):
        g = _regime_w(i, regime, half)
        if g <= 0.0:
            out.append((DATES[i], 0.0, float(bench[i]), 0))
            continue
        ranked = order[i]
        ranked = ranked[valid[i, ranked]]
        take = min(len(ranked), int(round(fq * NS))) if fq else len(ranked)
        pool = ranked[:take] if take else ranked
        pool = [j for j in pool if S[i, j] >= th]
        if len(pool) < min_pool:
            out.append((DATES[i], 0.0, float(bench[i]), 0))
            continue
        pp = np.array(pool)
        if K and len(pp) > K:
            if mode == "strat":
                nn = len(pp)
                # off=0 与 Kotlin selectDailyCandidates 的分层抽样完全一致
                pos = sorted({(int(t * (nn - 1) / K) + off) % nn for t in range(K)})
                sel = pp[np.array(pos)]
            elif mode == "rand":
                sel = np.random.default_rng(seed * 100003 + i).choice(pp, K, replace=False)
            elif mode == "top":
                sel = pp[:K]
            else:
                kk = I.KEYMAT[mode][i, pp]
                sel = pp[np.argsort(-np.nan_to_num(kk, nan=-1e9))[:K]]
        else:
            sel = pp
        rr = R[i, sel]
        rr = rr[np.isfinite(rr)]
        out.append((DATES[i], g * (float(np.mean(rr)) if rr.size else 0.0), float(bench[i]),
                    len(rr) if g >= 1.0 else int(round(len(rr) * g)) or 1))
    return out


def _regime_w(i, ma, half):
    """跌破大盘 40 日均线时的仓位系数：空仓=0，半仓=0.5，不择时=1。"""
    if not ma:
        return 1.0
    r, m = P.universe_index(ma)
    if np.isnan(m[i]) or np.isnan(r[i]):
        return 1.0
    return 1.0 if r[i] >= m[i] else (0.5 if half else 0.0)


IDX = {d: i for i, d in enumerate(DATES)}


def phase_cum(r, ds, H_, since=None):
    """按相位（每 H_ 天换仓的 8 种网格）分别累乘再取均值 —— 每日样本是重叠的，
    直接 prod 会把同一笔持仓重复计入 20 次，回撤和累计都会虚高。"""
    cums = []
    for p in range(H_):
        jj = [j for j, d in enumerate(ds)
              if (since is None or d >= since) and (IDX[d] - p) % H_ == 0]
        if len(jj) < 3:
            continue
        cums.append(float(np.prod(1 + r[jj]) - 1))
    return float(np.mean(cums)) if cums else float("nan")


def rep(label, out, bh, H_=20):
    v = H.summarize(out, f"{label:<30}", H=H_, bh=bh)
    expo = np.mean([1 if x[3] > 0 else 0 for x in out])
    ds = [x[0] for x in out]
    r = np.array([x[1] for x in out])
    print(f"     └ 持仓率 {expo*100:3.0f}%  日均只数 {np.mean([x[3] for x in out]):5.1f}  "
          f"样本外累计 {phase_cum(r, ds, H_, CUTOFF)*100:+6.1f}%  最差篮子 {r.min()*100:+5.1f}%")
    return v


def main():
    bh = P.bench_hold(0, ND)
    ri, _ = P.universe_index(40)
    bh_dd = float((ri / np.maximum.accumulate(ri) - 1).min())
    print(f"\n基准：288 只等权买入持有 累计 {bh*100:+.1f}%  最大回撤 {bh_dd*100:.1f}%")

    print("\n==== 现行上线（R0）====")
    rep("R0 现行 ≥68 全部等权",
        [(d, x, y, n) for d, x, y, n in P.daily(dict(score="new", th=68.0, K=None, H=20))], bh)

    print("\n==== 相对宽度池（候选上线）====")
    for th, fq, K in ((60.0, 0.40, 20), (60.0, 0.35, 20), (60.0, 0.30, 15), (60.0, 0.40, 30),
                      (68.0, 0.40, 20), (55.0, 0.40, 20), (60.0, 0.50, 20)):
        rep(f"≥{th:.0f} 且 前{fq*100:.0f}% · 分层取{K}", rows_rule(th, fq, K), bh)

    print("\n==== 绝对门槛到底有没有用（前40% · 分层20 · 只换 th）====")
    for th in (0.0, 43.0, 55.0, 60.0, 65.0, 68.0):
        rep(f"th={th:.0f} 且 前40% · 分层20", rows_rule(th, 0.40, 20), bh)

    print("\n==== 同上，加 2×ATR 止损 ====")
    for th, fq, K in ((60.0, 0.40, 20), (60.0, 0.35, 20), (60.0, 0.30, 15)):
        rep(f"≥{th:.0f}·前{fq*100:.0f}%·取{K}·止损2ATR", rows_rule(th, fq, K, stop=2.0), bh)

    print("\n==== 同上，加 3×ATR 止损 ====")
    for th, fq, K in ((60.0, 0.40, 20), (60.0, 0.35, 20)):
        rep(f"≥{th:.0f}·前{fq*100:.0f}%·取{K}·止损3ATR", rows_rule(th, fq, K, stop=3.0), bh)

    print("\n==== 择时降仓（跌破大盘 40 日均线→空仓/半仓）====")
    rep("前40%·20只·MA40空仓", rows_rule(60.0, 0.40, 20, regime=40, half=False), bh)
    rep("前40%·20只·MA40半仓", rows_rule(60.0, 0.40, 20, regime=40, half=True), bh)
    rep("前40%·20只·止损2+MA40半仓", rows_rule(60.0, 0.40, 20, stop=2.0, regime=40, half=True), bh)

    print("\n==== 排序方式对照（同一池）====")
    for mode in ("strat", "rand", "top", "量比", "低波动"):
        rep(f"前40%·20只·mode={mode}", rows_rule(60.0, 0.40, 20, mode=mode), bh)

    print("\n==== 只数对照（前40%·≥60·分层）====")
    for K in (10, 15, 20, 25, 40):
        rep(f"前40%·≥60·分层取{K}", rows_rule(60.0, 0.40, K), bh)

    print("\n==== 持有期对照（前40%·20只·≥60，含 2ATR 止损）====")
    for h in (10, 15, 20, 30):
        rep(f"H={h}", rows_rule(60.0, 0.40, 20, H_=h, stop=2.0), bh, H_=h)


if __name__ == "__main__":
    main()
