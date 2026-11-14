#!/usr/bin/env python3
"""组合级模拟器：把"选哪些、买多少、拿多久、要不要止损、要不要择时"当成配置来对比。

和逐笔回测的区别：这里算的是**真实资金曲线**——每 H 个交易日换一次仓，
等权（或按波动倒数加权）买入当日选出的股票，收益按 t+1 开盘价计入，扣往返成本。
关键改进：同一份数据用 H 个不同的起始相位各跑一遍再取平均（phase-averaging），
避免"23 期刚好踩到哪几天"的路径运气污染结论。
"""
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from final_validate import new_fn, old_fn  # noqa: E402
from model import COST, CUTOFF, DATES, MIN_HIST, stocks  # noqa: E402

ND, NS = len(DATES), len(stocks)
DI = {d: i for i, d in enumerate(DATES)}
DATE_IDX = {}          # (stock_j, global_i) -> per-stock t
MAT = {}


FAC_KEYS = ("ma5", "ma10", "ma20", "ma60", "ma20_slope", "ma60_slope", "atr", "atr_pct", "adx",
            "pdi", "mdi", "rsi_app", "dif", "dea", "hist", "hist_d", "kdj_app_k", "kdj_app_d",
            "volr5", "volr20", "boll_pos", "boll_w_pct", "pos20", "pos55", "ret5", "ret10",
            "ret20", "ret60", "brk20", "brk55", "hi20", "lo20", "obv_slope", "turnover", "vol_up")
FAC = {}


def build(fns):
    """fns: {name: fn(F,t)->(score,ok)}；一次性把所有矩阵填好。"""
    keys = ("open", "close", "high", "low", "pct", "atr", "atr_pct", "ma20", "vol") + FAC_KEYS
    for k in keys:
        MAT[k] = np.full((ND, NS), np.nan)
    S = {n: np.full((ND, NS), np.nan) for n in fns}
    ok = {n: np.zeros((ND, NS), bool) for n in fns}
    lim = np.array([0.097 if str(F["code"]).startswith(("30", "68")) else 0.047 for F in stocks])
    for j, F in enumerate(stocks):
        n = int(F["n"])
        for t in range(MIN_HIST, n):
            d = str(F["date"][t])
            i = DI.get(d)
            if i is None:
                continue
            DATE_IDX[(j, i)] = t
            for k in keys:
                MAT[k][i, j] = float(F[k][t])
        for k in FAC_KEYS:
            FAC.setdefault(k, np.full((ND, NS), np.nan))
            for t in range(MIN_HIST, n):
                i = DI.get(str(F["date"][t]))
                if i is not None:
                    FAC[k][i, j] = float(F[k][t])
        for name, fn in fns.items():
            for t in range(MIN_HIST, n):
                i = DI.get(str(F["date"][t]))
                if i is None:
                    continue
                s, o = fn(F, t)
                S[name][i, j] = s
                ok[name][i, j] = bool(o)
        MAT["_lim"] = lim
    MAT["_S"], MAT["_ok"] = S, ok
    return S, ok


FNS = {"new": new_fn, "old": old_fn}
build(FNS)


# ---------------------------------------------------------------- 选择
def pick(i, cfg):
    """返回 (列索引, 权重)。"""
    S = MAT["_S"][cfg["score"]][i]
    ok = MAT["_ok"][cfg["score"]][i]
    c, o1, pct = MAT["close"][i], MAT["open"][i + 1] if i + 1 < ND else np.full(NS, np.nan), MAT["pct"][i]
    tradable = ok & ~np.isnan(S) & ~np.isnan(c) & ~np.isnan(o1) & (o1 > 0) & (c > 0)
    unb = (pct > 0) & (o1 >= c * (1 + MAT["_lim"]) * 0.995)      # 开盘涨停买不到
    tradable &= ~unb
    if cfg.get("regime") and not regime_on(i, cfg["regime"]):
        return np.where(tradable)[0][:0], np.array([])
    th = cfg.get("th", 68.0)
    sel = tradable & (S >= th)
    idx = np.where(sel)[0]
    if len(idx) < cfg.get("min_n", 1):
        if cfg.get("fill"):        # 空仓时退到 HOLD 档，保持满仓
            sel2 = tradable & (S >= cfg["fill"])
            idx = np.where(sel2)[0]
            th = cfg["fill"]
        if len(idx) == 0:
            return idx, np.array([])
    order = idx[np.argsort(-S[idx])]
    k = cfg.get("K")
    if k and len(order) > k:
        mode = cfg.get("mode", "strat")
        if mode == "top":
            order = order[:k]
        elif mode == "strat":
            order = np.array([order[int(t * (len(order) - 1) / k)] for t in range(k)])
        elif mode == "rand":
            rs = np.random.default_rng(int(DATES[i].replace("-", "")) % 2 ** 31)
            order = rs.choice(order, k, replace=False)
    w = np.ones(len(order))
    if cfg.get("weight") == "ivola":
        a = MAT["atr_pct"][i, order]
        w = 1.0 / np.clip(a, 0.005, 0.08)
    w = w / w.sum()
    return order, w


_idx_cache = {}


def universe_index(window=40):
    """全市场等权净值（当日等权日收益累乘），只用 <= i 的信息。"""
    if window in _idx_cache:
        return _idx_cache[window]
    c = MAT["close"]
    ret = np.full((ND, NS), np.nan)
    ret[1:] = c[1:] / c[:-1] - 1.0
    with np.errstate(all="ignore"):
        rr = np.nanmean(ret, axis=1)
    rr = np.where(np.isnan(rr), 0.0, rr)
    r = np.cumprod(1 + rr)
    ma = np.full(ND, np.nan)
    for i in range(window - 1, ND):
        seg = r[i - window + 1:i + 1]
        if not np.isnan(seg).any():
            ma[i] = seg.mean()
    _idx_cache[window] = (r, ma)
    return r, ma


def regime_on(i, spec):
    r, ma = universe_index(spec["ma"])
    if i >= ND or np.isnan(ma[i]) or np.isnan(r[i]):
        return True
    return r[i] >= ma[i] if spec["type"] == "above" else True


# ---------------------------------------------------------------- 单笔收益
def trade_rets(i, js, H, stopm=None):
    """t=i 收盘信号 -> i+1 开盘买 -> 持有 H 日（可选止损）。返回净收益向量。"""
    if len(js) == 0:
        return np.array([])
    e = MAT["open"][i + 1, js]
    out_i = min(i + 1 + H, ND - 1)
    hold = MAT["close"][out_i, js] / e - 1.0 - COST
    if stopm is None:
        return hold
    c0, a, m20 = MAT["close"][i, js], MAT["atr"][i, js], MAT["ma20"][i, js]
    sp = c0 - stopm * a
    sp = np.where((~np.isnan(m20)) & (m20 < c0), np.maximum(sp, m20 * 0.995), sp)
    sp = np.clip(sp, c0 * 0.80, c0 * 0.98)
    live = (~np.isnan(sp) & (sp > 0) & ~np.isnan(a))
    res = np.where(live, np.nan, hold)
    done = ~live
    if done.all():
        return res
    for s in range(i + 1, min(i + 2 + H, ND)):
        oo, ll = MAT["open"][s, js], MAT["low"][s, js]
        gap = (~done) & (oo <= sp)
        hit = (~done) & (~gap) & (ll <= sp)
        res[gap] = oo[gap] / e[gap] - 1.0 - COST
        res[hit] = sp[hit] / e[hit] - 1.0 - COST
        done |= gap | hit
        if done.all():
            break
    res[~done] = hold[~done]
    return res


_BC = {}


def bench_ret(i, H):
    """当日均衡基准：全市场可交易的等权、同样持有 H 天。"""
    key = (i, H)
    if key not in _BC:
        js = np.where(~np.isnan(MAT["close"][i]) & ~np.isnan(MAT["open"][i + 1]) &
                      (MAT["open"][i + 1] > 0))[0]
        b = trade_rets(i, js, H, None)
        b = b[np.isfinite(b)] if b.size else b
        _BC[key] = (float(np.mean(b)) if b.size else np.nan, len(b))
    return _BC[key]


def daily(cfg):
    """每一个信号日算一次"当日篮子"，日期等权 —— 选参数用这个，比抽样换仓稳。"""
    H = cfg.get("H", 20)
    out = []
    for i in range(0, ND - 1 - H):
        js, w = pick(i, cfg)
        if len(js) == 0:
            continue
        r = trade_rets(i, js, H, cfg.get("stop"))
        m = np.isfinite(r)
        if not m.all():
            js, w, r = js[m], w[m] / w[m].sum(), r[m]
        if r.size == 0:
            continue
        b, nb = bench_ret(i, H)
        if np.isnan(b):
            continue
        out.append((DATES[i], float(np.dot(w, r)), b, len(js)))
    return out


def metrics(cfg, label, show_monthly=False):
    rows = daily(cfg)
    if len(rows) < 10:
        print(f"  {label:<40} 样本不足")
        return None
    r = np.array([x[1] for x in rows])
    b = np.array([x[2] for x in rows])
    e = r - b
    res = {}
    for tag, m in (("全程", np.ones(len(e), bool)),
                   ("样本内", np.array([x[0] < CUTOFF for x in rows])),
                   ("样本外", np.array([x[0] >= CUTOFF for x in rows]))):
        ee, rr = e[m], r[m]
        t = ee.mean() / (ee.std(ddof=1) / np.sqrt(max(ee.size, 1)))
        res[tag] = dict(n=int(m.sum()), mean=float(rr.mean()), exc=float(ee.mean()), t=float(t),
                        win=float((ee > 0).mean()), pos=float((rr > 0).mean()),
                        p10=float(np.percentile(rr, 10)), worst=float(rr.min()),
                        nn=float(np.mean(np.array([x[3] for x in rows])[m])))
        print(f"  {label:<34} {tag:<4} 日数 {res[tag]['n']:<4} 日均只数 {res[tag]['nn']:5.1f}  篮子均值 {res[tag]['mean']*100:+6.2f}%  "
              f"超额 {res[tag]['exc']*100:+6.3f}%  t={res[tag]['t']:+5.2f}  跑赢基准日 {res[tag]['win']*100:3.0f}%  "
              f"绝对赚日 {res[tag]['pos']*100:3.0f}%  p10 {res[tag]['p10']*100:+5.1f}%  最差日 {res[tag]['worst']*100:+5.1f}%")
    if show_monthly:
        mo = {}
        for d, x, y, n in rows:
            mo.setdefault(d[:7], []).append(x)
        v = np.array([np.mean(mo[k]) for k in sorted(mo)])
        print(f"     月度: {v.size} 月 均值 {v.mean()*100:+5.2f}% 正 {((v>0).mean()*100):3.0f}% 最差 {v.min()*100:+5.2f}%")
    return res


# ---------------------------------------------------------------- 资金曲线
def curve(cfg, dates):
    """给定换仓日期序列，返回 (期收益数组, 基准数组, 持仓只数数组)。"""
    H = cfg.get("H", 20)
    rows = []
    for i in dates:
        if i + 1 + H >= ND:
            continue
        js, w = pick(i, cfg)
        r = trade_rets(i, js, H, cfg.get("stop"))
        if r.size:
            m = np.isfinite(r)
            if not m.all():
                js, w, r = js[m], w[m] / w[m].sum(), r[m]
            if r.size == 0:
                w = np.array([])
        b, nb = bench_ret(i, H)
        b = np.array([b])
        rows.append((DATES[i],
                     float(np.dot(w, r)) if len(js) else 0.0,
                     (float(np.nanmean(b)) if b.size and np.isfinite(b).any() else 0.0),
                     len(js)))
    return rows


def equity(rows):
    r = np.array([x[1] for x in rows])
    eq = np.cumprod(1 + r)
    dd = (eq / np.maximum.accumulate(eq) - 1).min() if eq.size else 0.0
    return r, eq, dd


def bench_hold(lo, hi):
    """真正的"大盘"：全市场等权、每日买入持有（不再平衡）在同区间的涨幅。"""
    r, _ = universe_index(40)
    a = r[lo] if lo > 0 else 1.0
    out = r[hi - 1] / a - 1.0
    return float(out) if np.isfinite(out) else np.nan


def stats(cfg, label, since=None, until=None, phases=None):
    """跨相位平均，消除"哪几天换仓"的运气。"""
    lo = 0 if since is None else next(i for i, d in enumerate(DATES) if d >= since)
    hi = ND if until is None else next(i for i, d in enumerate(DATES) if d >= until)
    H = cfg.get("H", 20)
    ph = range(0, min(H, 5)) if phases is None else range(phases)
    all_r, all_b, cums, cagrs, dds, wps, beat, bcums, expo = [], [], [], [], [], [], [], [], []
    for p in ph:
        dates = list(range(lo + p, hi, H))
        rows = curve(cfg, dates)
        if not rows:
            continue
        r, eq, dd = equity(rows)
        b = np.array([x[2] for x in rows])
        beq = np.cumprod(1 + b)
        yrs = len(rows) * H / 244.0
        cums.append(eq[-1] - 1)
        bcums.append(beq[-1] - 1)
        cagrs.append((eq[-1]) ** (1 / max(yrs, .05)) - 1)
        dds.append(dd)
        wps.append(r.min())
        beat.append((r - b > 0).mean() if r.size else np.nan)
        expo.append(np.mean([x[3] > 0 for x in rows]))
        all_r.extend(r)
        all_b.extend(b)
    if not cums:
        print(f"  {label:<44} 无样本")
        return None
    r, b = np.array(all_r), np.array(all_b)
    bh = bench_hold(lo, hi)
    print(f"  {label:<42} 累计 {np.mean(cums)*100:+7.1f}%  年化 {np.mean(cagrs)*100:+6.1f}%  "
          f"回撤 {np.mean(dds)*100:5.1f}%  最差期 {min(wps)*100:+5.1f}%  持仓率 {np.mean(expo)*100:3.0f}%  "
          f"|| 基准 {np.mean(bcums)*100:+6.1f}%  期超额 {(r-b).mean()*100:+5.2f}%  跑赢期 {(r-b>0).mean()*100:3.0f}%  "
          f"(相位 {len(cums)})  [大盘每日持有 {bh*100:+6.1f}%]  超大盘 {(np.mean(cums)-bh)*100:+6.1f}pp")
    return dict(cum=float(np.mean(cums)), cagr=float(np.mean(cagrs)), dd=float(np.mean(dds)),
                beat=float(np.mean(beat)), exc=float((r - b).mean()), worst=float(min(wps)),
                bench=float(np.mean(bcums)), bh=bh,
                alpha=float(np.mean(cums) - np.mean(bcums)),
                vs_bh=float(np.mean(cums) - bh),
                periods=len(r))


def bench_cfg():
    return dict(score="new", th=-1e9, K=None, H=20, fill=None)


def show_tail(cfg, label, since=None):
    """逐笔尾部：所有信号日的单笔收益分布（相位无关，样本最大）"""
    H = cfg.get("H", 20)
    vals, ns = [], []
    for i in range(0, ND - 1 - H):
        if since and DATES[i] < since:
            continue
        js, _ = pick(i, cfg)
        r = trade_rets(i, js, H, cfg.get("stop"))
        if r.size:
            vals.extend(list(r))
            ns.append(len(js))
    a = np.array(vals)
    print(f"    {label:<40} 笔数 {a.size:<7d} 均值 {a.mean()*100:+5.2f}%  中位 {np.median(a)*100:+5.2f}%  "
          f"胜率 {(a>0).mean()*100:4.1f}%  p05 {np.percentile(a,5)*100:+5.1f}%  亏>10% {(a<-0.10).mean()*100:4.1f}%  "
          f"亏>20% {(a<-0.20).mean()*100:4.1f}%  最差 {a.min()*100:+6.1f}%  日均只数 {np.mean(ns):5.1f}")
