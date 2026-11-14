#!/usr/bin/env python3
"""iter22 — 复核 iter21 里唯二"三段都为正 + 尾部不失控"的候选：把分数当**过滤器**，用量比当**池内排序**。

做法：先用上线分数取当日前列（前 20/30/40%）当合格池，池内按 5 日量比排序取 K 只，等权满仓，持 H 日。
随机基线跑 20 个种子取均值，避免"某一次抽签"的运气。
同时给出：与大盘（等权买入持有）的最大回撤对照、2×ATR 止损后的尾部、逐月。
换手率因子在 iter21 里样本内 t=+9、累计 +292%，但回撤 −52%、最差日 −45%，是杠杆式追热点，
本轮只作为对照记录，不作为候选上线。
"""
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import fast as X  # noqa: E402
import portsim as P  # noqa: E402
from model import CUTOFF, DATES  # noqa: E402

F = lambda k: P.FAC[k] if k in P.FAC else P.MAT[k]  # noqa: E731
ND, NS = P.ND, P.NS
S0 = P.MAT["_S"]["new"]
KEYMAT = {"量比": F("volr5"), "ADX": F("adx"), "换手": F("turnover"), "PDI-MDI": F("pdi") - F("mdi"),
          "量比×价涨": F("volr5") * np.sign(F("ret5")), "低波动": -F("atr_pct")}


def rows(fq, K, tie, H=20, seed=0, stop=None):
    VALID = X.valid_mat(S0, H)
    R = X.ret_mat(H)
    if stop is not None:
        R = stop_ret(H, stop, VALID)
    bench = np.nanmean(np.where(VALID, R, np.nan), axis=1)
    bench = np.where(np.isnan(bench), 0.0, bench)
    kf = max(1, int(round(fq * NS)))
    order_f = np.argsort(np.where(VALID, S0, -np.inf), axis=1)[:, ::-1]
    out = []
    for i in range(ND - 1 - H):
        pool = order_f[i, :kf]
        pool = pool[VALID[i, pool]]
        if len(pool) < 3:
            continue
        if tie is None:
            sel = np.random.default_rng(seed * 100003 + i).choice(pool, min(K, len(pool)), replace=False)
        else:
            kk = KEYMAT[tie][i, pool]
            sel = pool[np.argsort(-np.nan_to_num(kk, nan=-1e9))[:K]]
        rr = R[i, sel]
        rr = rr[np.isfinite(rr)]
        if rr.size == 0:
            continue
        out.append((DATES[i], float(np.mean(rr)), float(bench[i]), len(rr)))
    return out


_sc = {}


def stop_ret(H, mult, valid):
    """带 m×ATR 止损的单笔收益矩阵（日线近似：跳空穿越按开盘成交，否则按止损价）。"""
    key = (H, mult)
    if key in _sc:
        return _sc[key]
    C, O1, LOW = P.MAT["close"], P.MAT["open"], P.MAT["low"]
    A, M20 = F("atr"), F("ma20")
    e = np.roll(O1, -1, axis=0)                       # e[i] = 次日开盘
    x = np.roll(C, -(1 + H), axis=0)                  # x[i] = 到期收盘
    sp = C - mult * A
    sp = np.where((~np.isnan(M20)) & (M20 < C), np.maximum(sp, M20 * 0.995), sp)
    sp = np.clip(sp, C * 0.80, C * 0.98)
    pend = valid & (~np.isnan(sp)) & (~np.isnan(A)) & (sp > 0) & ~np.isnan(e) & (e > 0)
    res = np.full_like(C, np.nan)
    for s in range(1, H + 2):                          # 持有期内第 s 根日线（i+1 .. i+1+H）
        n = ND - s
        if n <= 0:
            break
        sub = slice(0, n)
        o_, l_ = O1[s:s + n], LOW[s:s + n]
        base, spv = e[sub], sp[sub]
        gap = pend[sub] & (o_ <= spv)
        hit = pend[sub] & ~gap & (l_ <= spv)
        res[sub][gap] = o_[gap] / base[gap] - 1.0 - X.COST
        res[sub][hit] = spv[hit] / base[hit] - 1.0 - X.COST
        pend[sub] &= ~(gap | hit)
        if not pend.any():
            break
    tail = pend & ~np.isnan(x) & ~np.isnan(e) & (e > 0)
    res[tail] = x[tail] / e[tail] - 1.0 - X.COST
    _sc[key] = res
    return res


def t3(out):
    ds = np.array([x[0] for x in out])
    r = np.array([x[1] for x in out])
    b = np.array([x[2] for x in out])
    e = r - b
    u = np.sort(np.unique(ds[ds < CUTOFF]))
    mid = u[len(u) // 2]
    def tt(m):
        ee = e[m]
        return ee.mean() / (ee.std(ddof=1) / np.sqrt(max(ee.size, 1))) if ee.size > 10 else np.nan
    seg = [tt(ds < mid), tt((ds >= mid) & (ds < CUTOFF)), tt(ds >= CUTOFF)]
    # 资金曲线（相位平均）
    idx = {d: i for i, d in enumerate(DATES)}
    cums, dds = [], []
    for p in range(8):
        jj = [j for j, d in enumerate(ds) if (idx[d] - p) % 20 == 0]
        if len(jj) < 3:
            continue
        eq = np.cumprod(1 + r[jj])
        cums.append(eq[-1] - 1)
        dds.append((eq / np.maximum.accumulate(eq) - 1).min())
    mo = {}
    for d, x, y, n in out:
        mo.setdefault(d[:7], []).append(x - y)
    mv = np.array([np.mean(mo[k]) for k in sorted(mo)])
    return dict(exc=float(e.mean()), t=[*seg], all_t=float(tt(np.ones(len(e), bool))),
                cum=float(np.mean(cums)), dd=float(np.mean(dds)),
                m_pos=float((mv > 0).mean()), m_worst=float(mv.min()), rmean=float(r.mean()),
                rmin=float(r.min()), n=float(np.mean([x[3] for x in out])))


def show(label, v, bh):
    print(f"  {label:<40} 日均{v['n']:5.1f}只 超额{v['exc']*100:+6.3f}% t={v['t'][0]:+5.2f}/{v['t'][1]:+5.2f}/"
          f"{v['t'][2]:+5.2f} 全{v['all_t']:+5.2f} | 累计{v['cum']*100:+7.1f}% 超大盘{(v['cum']-bh)*100:+6.1f}pp "
          f"回撤{v['dd']*100:5.1f}% | 月度正{v['m_pos']*100:3.0f}% 最差月{v['m_worst']*100:+5.2f}% 最差日{v['rmin']*100:+5.1f}%")


def main():
    bh = P.bench_hold(0, ND)
    ri, _ = P.universe_index(40)
    bh_dd = float((ri / np.maximum.accumulate(ri) - 1).min())
    print(f"\n大盘（288只等权买入持有）全程 {bh*100:+.1f}%  最大回撤 {bh_dd*100:.1f}%")

    print("\n==== 1. 池内随机（20 个种子平均）：先确立'不挑'的基线 ====")
    for fq in (0.20, 0.30, 0.40):
        for K in (10, 20):
            vs = [t3(rows(fq, K, None, seed=s)) for s in range(20)]
            agg = {k: float(np.mean([v[k] for v in vs])) for k in
                   ("exc", "cum", "dd", "m_pos", "m_worst", "rmin", "n")}
            agg["t"] = [float(np.mean([v["t"][j] for v in vs])) for j in range(3)]
            agg["all_t"] = float(np.mean([v["all_t"] for v in vs]))
            show(f"前{fq*100:2.0f}%池 · 随机取{K}", agg, bh)

    print("\n==== 2. 池内按 5 日量比排序（10 个种子无关，确定性）====")
    for fq in (0.20, 0.30, 0.40, 0.50):
        for K in (10, 15, 20, 30):
            show(f"前{fq*100:2.0f}%池 · 量比取{K}", t3(rows(fq, K, "量比")), bh)

    print("\n==== 3. 其它池内排序因子对照（前30%池, K=20）====")
    for tie in ("ADX", "PDI-MDI", "量比×价涨", "换手", "低波动"):
        show(f"前30%池 · {tie}取20", t3(rows(0.30, 20, tie)), bh)

    print("\n==== 4. 持有期与止损（前30%池 · 量比取20）====")
    for H in (10, 20, 30):
        show(f"H={H} 不止损", t3(rows(0.30, 20, "量比", H=H)), bh)
    for m in (1.5, 2.0, 3.0):
        show(f"H=20 止损{m}ATR", t3(rows(0.30, 20, "量比", stop=m)), bh)


if __name__ == "__main__":
    main()
