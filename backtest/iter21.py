#!/usr/bin/env python3
"""iter21 — 不换评分函数，只换"用它的姿势"。

iter20 的教训写在前面：对 15 个因子做贪心搜索能把样本内 t 推到 +11，样本外立刻变成 −4，
回撤 −42%。那是过拟合，不上线。所以这一轮只测**预先登记**的姿势类假设，每个都单独看：

前提：分数 = 上线模型（final_validate.new_fn），不动。
  1) 门槛宽度：先取分数前 20% / 30% / 50% 当"合格池"（前 5% 已证明是反指）；
  2) 池内怎么挑到 K=10/20 只：随机(=分层抽样的等价) / 低波动 / 离MA20最近 / 动量最接近池内中位 /
     20日位置最低 / 换手最大 / ADX最强；
  3) 始终满仓、每 H 天全换，看 20 日持有。
判定：样本内前半、样本内后半、样本外 三段的 t 值都 > 0 才算候选；回撤不能比"全部合格等权"更差。
"""
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import fast as X  # noqa: E402
import portsim as P  # noqa: E402
from model import COST, CUTOFF, DATES  # noqa: E402

F = lambda k: P.FAC[k] if k in P.FAC else P.MAT[k]  # noqa: E731
C, M20 = F("close"), F("ma20")
ND, NS = P.ND, P.NS
S = P.MAT["_S"]["new"]
VALID = X.valid_mat(S, 20)
R = X.ret_mat(20)
BENCH = np.nanmean(np.where(VALID, R, np.nan), axis=1)
BENCH[np.isnan(BENCH)] = 0.0

KEYS = {
    "低波动": -F("atr_pct"),
    "离MA20近": -np.abs(C / M20 - 1.0),
    "动量居中": -np.abs(F("ret60") - np.nanmedian(F("ret60"), axis=1, keepdims=True)),
    "20日位低": -F("pos20"),
    "换手大": F("turnover"),
    "ADX强": F("adx"),
    "量比大": F("volr5"),
    "RSI低": -F("rsi_app"),
}


def rows(filt_q, K, key, H=20, valid=None, R_=None, bench=None, S_=None):
    valid = VALID if valid is None else valid
    R_ = R if R_ is None else R_
    bench = BENCH if bench is None else bench
    S_ = S if S_ is None else S_
    kf = max(1, int(round(filt_q * NS)))
    order_f = np.argsort(np.where(valid, S_, -np.inf), axis=1)[:, ::-1]
    out = []
    for i in range(ND - 1 - H):
        pool = order_f[i, :kf]
        pool = pool[valid[i, pool]]
        if len(pool) < K:
            if len(pool) < 3:
                continue
            sel = pool
        elif key is None:
            rs = np.random.default_rng(10000 + i)
            sel = rs.choice(pool, K, replace=False)
        else:
            kk = KEYS[key][i, pool]
            sel = pool[np.argsort(-np.nan_to_num(kk, nan=-1e9))[:K]]
        rr = R_[i, sel]
        rr = rr[np.isfinite(rr)]
        if rr.size == 0:
            continue
        out.append((DATES[i], float(np.mean(rr)), float(bench[i]), len(rr)))
    return out


def evaluate(out, label, quiet=False):
    if len(out) < 40:
        print(f"  {label:<44} 样本不足({len(out)})")
        return None
    ds = np.array([x[0] for x in out])
    r = np.array([x[1] for x in out])
    b = np.array([x[2] for x in out])
    n = np.array([x[3] for x in out])
    e = r - b
    dm = np.sort(np.unique(ds[ds < CUTOFF]))
    mid = dm[len(dm) // 2]
    tvals = []
    for m in ((ds < mid), ((ds >= mid) & (ds < CUTOFF)), (ds >= CUTOFF)):
        ee = e[m]
        tvals.append(ee.mean() / (ee.std(ddof=1) / np.sqrt(max(ee.size, 1))))
    tt = e.mean() / (e.std(ddof=1) / np.sqrt(e.size))
    ok = all(x > 0 for x in tvals)
    print(f"  {label:<44} 日均{n.mean():5.1f}只 超额 全程{e.mean()*100:+6.3f}%(t{tt:+5.2f}) "
          f"IS前{tvals[0]:+5.2f} IS后{tvals[1]:+5.2f} OOS{tvals[2]:+5.2f}  "
          f"跑赢日{(e>0).mean()*100:3.0f}% 篮子{r.mean()*100:+5.2f}% 最差日{r.min()*100:+5.1f}% "
          f"{'  ✔三段都为正' if ok else ''}")
    return dict(label=label, exc=float(e.mean()), t=float(tt), ts=tvals, ok=ok,
                mean=float(r.mean()), worst=float(r.min()), n=float(n.mean()),
                win=float((e > 0).mean()), ds=ds, r=r, b=b)


def equity(res, label, H=20):
    """按各自的换仓网格算资金曲线（相位平均）+ 回撤，对照大盘等权买入持有。"""
    ds, r, b = res["ds"], res["r"], res["b"]
    idx = {d: i for i, d in enumerate(DATES)}
    cums, dds = [], []
    dsl = list(ds)
    for p in range(6):
        jj = [j for j, d in enumerate(dsl) if (idx[d] - p) % H == 0]
        if not jj:
            continue
        rr = r[jj]
        eq = np.cumprod(1 + rr)
        cums.append(eq[-1] - 1)
        dds.append((eq / np.maximum.accumulate(eq) - 1).min())
    bh = P.bench_hold(0, ND)
    print(f"     └ 资金曲线 累计 {np.mean(cums)*100:+7.1f}%  回撤 {np.mean(dds)*100:5.1f}%  "
          f"超大盘 {(np.mean(cums)-bh)*100:+6.1f}pp")


def main():
    print("\n==== A. 门槛宽度 × 池内随机（=现行分层抽样的广义版本，H=20，始终满仓）====")
    res = {}
    for fq in (0.05, 0.10, 0.20, 0.30, 0.40, 0.50, 0.70):
        for K in (10, 20, None):
            if K and K > fq * NS:
                continue
            lab = f"前{fq*100:2.0f}%池 · {'全部' if K is None else f'随机取{K}'}"
            res[lab] = evaluate(rows(fq, K or 10 ** 6, None), lab)
    print("\n==== B. 前30%池，池内不同挑法（K=10 / K=20）====")
    for key in list(KEYS) + [None]:
        for K in (10, 20):
            lab = f"前30%池 · {key or '随机'}取{K}"
            res[lab] = evaluate(rows(0.30, K, key), lab)
    print("\n==== C. 候选方案的资金曲线 ====")
    for lab, r in res.items():
        if r and r["ok"]:
            equity(r, lab)
    print("\n==== D. 参照：现行上线做法（≥68分等权，可为空仓）====")
    P.metrics(dict(score="new", th=68.0, K=None, H=20), "现行 68分门槛 全部等权")
    X.compound(S, 0.30, 20, "前30%满仓·随机10只(近似)")


if __name__ == "__main__":
    main()
