#!/usr/bin/env python3
"""metals_iter9 — 给贵金属找一套**属于它自己的**建议口径（趋势 / 波动 / 止损 / 回调 / 带宽）

前情：iter1~iter8 证明出厂那套「同日横截面选股」分数在贵金属上不能用（择时每笔超额为正的
0/13 条，低档反而 11/13 绝对收益为正）。v4.8.0 因此只给了行情。本脚本回答另一半问题：
**换一种方式，贵金属到底能不能给出站得住脚的建议。**

口径与 A 股那套不同是刻意的：贵金属这一类只有 4 个独立资产、单资产、有长期看涨漂移，
所以主问题不是"哪只更好"，而是"要不要在场、仓位多大、什么价位减"。

════════════════════════════════════════════════════════════════════════
预注册的检验口径（跑之前写死；跑完不改判据，要改必须另开 iter 并说明理由）
════════════════════════════════════════════════════════════════════════
执行与成本
  · 决策只用 close(t) 及之前的数据；仓位从 t+1 生效（close-to-close 计量）
  · 调仓成本 = |Δ仓位| × 单边 0.12%；敏感性另跑 0.30%/side
样本与切分（防挑参数）
  · IS ≤ 2020-12-31，OOS ≥ 2021-01-01；判据看 OOS，参数不许在 OOS 重挑
  · 参数只取自下面这张预先声明的清单，不做事后搜索
  · 计数一律按 4 个独立资产（金/银/铂/钯 各一条代表序列），13 条报价只用于稳健性
判据（全满足才叫"可以上建议"）
  C1 风险调整：OOS 夏普 ≥ 一直持有 + 0.15，4 个资产里 ≥3 个成立
  C2 回撤：最大回撤优于一直持有（绝对值更小），OOS 与 IS 都要 4/4
  C3 收益代价：OOS 年化 ≥ 一直持有 − 1.5pp（不许靠砍掉全部上涨换好看的夏普）
  C4 稳定性：IS 与 OOS 两段方向一致（IS 也不得是反的）
  C5 成本：0.30%/side 下结论不翻
  C6 换手：每年调仓 ≤ 30 次（一天变三次的不叫建议）
  C7 显著性：日超额的移动块自助 95%CI；CI 含 0 就必须在文档里写"收益上没有证据"，
     只允许引用风险量（回撤/波动/夏普）的证据

方法清单（预先声明：7 族 21 个配置，全部 long-only、仓位 0..1、无杠杆）
  F0 一直持有（基准）
  F1 均线在场：close > MA_n 才持有，n ∈ {50, 100, 150, 200}
  F2 双均线：MA50 > MA200 才持有
  F3 时序动量：过去 k 日收益 > 0 才持有，k ∈ {63, 126, 252}
  F4 波动率目标（不判方向）：pos = min(1, σ_target / 21日年化波动)，σ_target ∈ {10%, 15%, 20%}
  F5 唐奇安突破：突破 m 日高进、跌破 e 日出，(m,e) ∈ {(20,10), (55,20), (100,40)}
  F6 ATR 跟踪止损：默认持有，收盘 < 20日最高收盘 − k×ATR 离场，收盘重回 MA20 上方再进，
     k ∈ {2, 3, 4}
  F7 趋势内回调：close > MA200 且 close ≤ (1−x)×20日最高收盘 才建仓，持有 20 日，
     x ∈ {0.05, 0.10}
  F8 带宽阶梯（A 股那两条例子在贵金属上的直接对应）：基础仓 50%；
     落在加仓带 [min(MA10,MA20), min(max(MA10,MA20), 1.01×现价)] → 100%；
     现价 ≥ MA20 + 2.5×ATR（减仓带上沿）→ 25%；其余 → 50%
"""
import glob
import json
import os
import sys

import numpy as np
import pandas as pd

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
sys.path.insert(0, HERE)
from indicators import atr_series                 # noqa: E402

DATA = os.path.join(HERE, "metals_data")
COST, COST_HI = 0.0012, 0.0030
SPLIT = "2021-01-01"
IS_HI = "2020-12-31"
DAYS = 252

ASSET_OF = {
    "金": ["sina_XAU", "sina_AU0", "sge_Au99.99", "sge_Au100g", "sge_Au99.95",
           "sge_AuT+D", "sge_mAuT+D"],
    "银": ["sina_XAG", "sina_AG0", "sge_AgT+D"],
    "铂": ["sina_XPT", "sge_Pt99.95"],
    "钯": ["sina_XPD"],
}
REP = ["sina_XAU", "sina_XAG", "sina_XPT", "sina_XPD"]


# ───────────────────────────── 数据与指标 ─────────────────────────────

def _sma(x, w):
    return pd.Series(x).rolling(w, min_periods=w).mean().to_numpy()


def _rollmax(x, w):
    return pd.Series(x).rolling(w, min_periods=w).max().to_numpy()


def _rollmin(x, w):
    return pd.Series(x).rolling(w, min_periods=w).min().to_numpy()


def _rollstd(x, w):
    return pd.Series(x).rolling(w, min_periods=w).std(ddof=0).to_numpy()


def load(path):
    d = json.load(open(path, encoding="utf-8"))
    r = pd.DataFrame(d["rows"])
    c, h, l = r["close"].to_numpy(float), r["high"].to_numpy(float), r["low"].to_numpy(float)
    F = {"name": d["name"], "date": r["date"].to_numpy(str),
         "open": r["open"].to_numpy(float), "close": c, "high": h, "low": l, "n": len(c)}
    for w in (10, 20, 50, 100, 150, 200):
        F[f"ma{w}"] = _sma(c, w)
    F["ret"] = np.concatenate([[np.nan], c[1:] / c[:-1] - 1.0])
    F["vol21"] = _rollstd(F["ret"], 21) * np.sqrt(DAYS)
    F["hh20"] = _rollmax(c, 20)
    F["atr"] = atr_series(h, l, c)
    return F


# ───────────────────────────── 仓位规则 ─────────────────────────────

def pos_rule(F, kind, p):
    """pos[t]：close(t) 时刻决定的目标仓位（0..1），从 t+1 开始生效。"""
    c, n = F["close"], F["n"]
    out = np.full(n, np.nan)

    if kind == "bh":
        out[:] = 1.0

    elif kind == "ma":
        m = F[f"ma{p}"]
        out[:] = np.where(np.isfinite(m), (c > m).astype(float), np.nan)

    elif kind == "dma":
        s, l = F[f"ma{p[0]}"], F[f"ma{p[1]}"]
        ok = np.isfinite(s) & np.isfinite(l)
        out[:] = np.where(ok, (s > l).astype(float), np.nan)

    elif kind == "tsm":
        k = p
        mom = np.full(n, np.nan)
        mom[k:] = c[k:] / c[:-k] - 1.0
        out[:] = np.where(np.isfinite(mom), (mom > 0).astype(float), np.nan)

    elif kind == "vt":
        v = F["vol21"]
        with np.errstate(divide="ignore", invalid="ignore"):
            w = np.where(np.isfinite(v) & (v > 0), p / v, np.nan)
        out[:] = np.clip(w, 0.0, 1.0)

    elif kind == "don":
        m, e = p
        hi = _rollmax(F["high"], m)
        lo = _rollmin(F["low"], e)
        st, arr = 0, np.full(n, np.nan)
        for t in range(1, n):
            if not (np.isfinite(hi[t]) and np.isfinite(lo[t]) and
                    np.isfinite(hi[t - 1]) and np.isfinite(lo[t - 1])):
                continue
            if st == 0 and c[t] > hi[t - 1]:
                st = 1
            elif st == 1 and c[t] < lo[t - 1]:
                st = 0
            arr[t] = st
        out[:] = arr

    elif kind == "trail":
        k = p
        st, arr = 1, np.full(n, np.nan)
        for t in range(1, n):
            hh, a, m20 = F["hh20"][t], F["atr"][t], F["ma20"][t]
            if not (np.isfinite(hh) and np.isfinite(a) and np.isfinite(m20)):
                continue
            if st == 1 and c[t] < hh - k * a:
                st = 0
            elif st == 0 and c[t] > m20:
                st = 1
            arr[t] = st
        out[:] = arr

    elif kind == "dip":
        x, hold = p, 20
        arr = np.full(n, np.nan)
        left = 0
        for t in range(1, n):
            m200, hh = F["ma200"][t], F["hh20"][t]
            if np.isfinite(m200) and np.isfinite(hh):
                if left > 0:
                    left -= 1
                elif c[t] > m200 and c[t] <= (1 - x) * hh:
                    left = hold
            arr[t] = 1.0 if left > 0 else 0.0
        out[:] = arr

    elif kind == "band":
        lo_b = F["ma10"]
        hi_b = F["ma20"]
        up = np.where(np.isfinite(lo_b) & np.isfinite(hi_b), np.maximum(lo_b, hi_b), np.nan)
        dn = np.where(np.isfinite(lo_b) & np.isfinite(hi_b), np.minimum(lo_b, hi_b), np.nan)
        trim = F["ma20"] + 2.5 * F["atr"]
        arr = np.full(n, np.nan)
        for t in range(1, n):
            if not (np.isfinite(dn[t]) and np.isfinite(up[t]) and np.isfinite(trim[t])):
                continue
            buy_hi = min(up[t], 1.01 * c[t])
            if c[t] >= trim[t]:
                arr[t] = 0.25
            elif dn[t] <= c[t] <= buy_hi:
                arr[t] = 1.0
            else:
                arr[t] = 0.5
        out[:] = arr
    return out


# ───────────────────────────── 计量 ─────────────────────────────

def strat_ret(F, pos, cost):
    """第 t 日的收益由**前一天收盘定下的仓位**赚取：
        r(t) = pos[t-1]*ret(t) − |pos[t-1] − pos[t-2]|*cost
       写成 pos[t]*ret(t) 就是拿 close(t) 的信息去赚 close(t-1)→close(t) 这一段，等于偷看一天，
       趋势类规则的当日收益和信号日高度同向，会被这个偏移放大到离谱（首跑就中了）。
       序列开始前视为空仓，所以基准也要付一次建仓成本，跟策略对称。"""
    n, ret = F["n"], F["ret"]
    w = np.full(n, np.nan)
    prev = 0.0
    for t in range(1, n):
        p = pos[t - 1]
        if not (np.isfinite(p) and np.isfinite(ret[t])):
            continue
        w[t] = p * ret[t] - abs(p - prev) * cost
        prev = p
    return w


def mask_of(dates, lo=None, hi=None):
    m = np.ones(len(dates), bool)
    if lo:
        m &= dates >= lo
    if hi:
        m &= dates <= hi
    return m


def stats(w):
    w = np.asarray(w, float)
    w = w[np.isfinite(w)]
    if len(w) < 60:
        return None
    eq = np.cumprod(1.0 + w)
    yrs = len(w) / DAYS
    sd = w.std(ddof=0)
    return {"n": len(w), "yrs": yrs, "cagr": eq[-1] ** (1 / yrs) - 1,
            "vol": sd * np.sqrt(DAYS), "sharpe": (w.mean() / sd * np.sqrt(DAYS)) if sd > 0 else np.nan,
            "maxdd": float((eq / np.maximum.accumulate(eq) - 1).min()),
            "cum": eq[-1] - 1}


def trades_per_year(pos, dates, cost_ok, lo=None, hi=None):
    m = mask_of(dates, lo, hi) & np.isfinite(pos)
    if m.sum() < 60:
        return np.nan
    step = np.diff(pos[m])
    yrs = m.sum() / DAYS
    return float((np.abs(step) > 1e-9).sum() / yrs)


def block_ci(x, reps=4000, blk=63, seed=11):
    x = np.asarray(x, float)
    x = x[np.isfinite(x)]
    if len(x) < blk * 4:
        return None
    rng = np.random.default_rng(seed)
    nb = int(np.ceil(len(x) / blk))
    means = np.empty(reps)
    for i in range(reps):
        idx = rng.integers(0, len(x) - blk, size=nb)
        s = np.concatenate([x[j:j + blk] for j in idx])[:len(x)]
        means[i] = s.mean()
    return float(x.mean()), float(np.percentile(means, 2.5)), float(np.percentile(means, 97.5))


# ───────────────────────────── 方法清单 ─────────────────────────────

METHODS = [("F0 一直持有", "bh", None)]
METHODS += [(f"F1 在场 close>MA{n}", "ma", n) for n in (50, 100, 150, 200)]
METHODS += [("F2 双均线 50/200", "dma", (50, 200))]
METHODS += [(f"F3 时序动量 {k}日", "tsm", k) for k in (63, 126, 252)]
METHODS += [(f"F4 波动率目标 {int(t*100)}%", "vt", t) for t in (0.10, 0.15, 0.20)]
METHODS += [(f"F5 唐奇安 {m}/{e}", "don", (m, e)) for m, e in ((20, 10), (55, 20), (100, 40))]
METHODS += [(f"F6 ATR 跟踪止损 {k}x", "trail", k) for k in (2, 3, 4)]
METHODS += [(f"F7 趋势内回调 -{int(x*100)}%/20日", "dip", x) for x in (0.05, 0.10)]
METHODS += [("F8 带宽阶梯 50/100/25", "band", None)]

CACHE = {}


def run(name, kind, p, cost=COST):
    """返回 (OOS/IS 的策略与基准统计、换手)。结果按 (name,kind,p,cost) 缓存。"""
    key = (name, kind, p, cost)
    if key in CACHE:
        return CACHE[key]
    F = CACHE["F_" + name] if "F_" + name in CACHE else load_series(name)
    d = F["date"]
    pos = pos_rule(F, kind, p)
    bpos = pos_rule(F, "bh", None)
    w, wb = strat_ret(F, pos, cost), strat_ret(F, bpos, cost)
    out = {
        "oos": stats(w[mask_of(d, SPLIT) & np.isfinite(w)]),
        "is": stats(w[mask_of(d, None, IS_HI) & np.isfinite(w)]),
        "oos_b": stats(wb[mask_of(d, SPLIT) & np.isfinite(wb)]),
        "is_b": stats(wb[mask_of(d, None, IS_HI) & np.isfinite(wb)]),
        "oos_tr": trades_per_year(pos, d, None, SPLIT, None),
        "is_tr": trades_per_year(pos, d, None, None, IS_HI),
        "excess_oos": (w - wb)[mask_of(d, SPLIT)],
        "full": stats(w[np.isfinite(w)]),
    }
    CACHE[key] = out
    return out


def load_series(name):
    for p in sorted(glob.glob(os.path.join(DATA, "*.json"))):
        F = load(p)
        if F["name"] == name:
            CACHE["F_" + name] = F
            return F
    raise KeyError(name)


def main():
    names = [json.load(open(p, encoding="utf-8"))["name"]
             for p in sorted(glob.glob(os.path.join(DATA, "*.json")))]
    asset_of = {nm: a for a, lst in ASSET_OF.items() for nm in lst}
    print("=" * 116)
    print("metals_iter9  贵金属建议口径：7 族 21 个配置｜IS ≤2020-12-31 / OOS ≥2021-01-01｜"
          "成本 0.12%/side｜long-only 0..1 仓位")
    print(f"  {len(names)} 条报价 = {len(ASSET_OF)} 个独立资产；代表序列 {REP}")
    print("=" * 116)

    # ---------- [1] 基准：一直持有是什么样 ----------
    print("\n[1] 基准（一直持有，扣建仓成本）：先让人知道对手是谁")
    print(f"  {'资产':4s} {'报价':13s} {'窗口':22s} {'年数':>5s} {'CAGR':>7s} {'波动':>7s} "
          f"{'夏普':>6s} {'最大回撤':>9s} {'累计':>8s}")
    for nm in REP + ["sina_AU0", "sina_AG0"]:
        F = load_series(nm)
        d, w = F["date"], strat_ret(F, pos_rule(F, "bh", None), COST)
        for lab, lo, hi in (("全样本", None, None), ("IS ", None, IS_HI), ("OOS", SPLIT, None)):
            st = stats(w[mask_of(d, lo, hi)])
            if not st or st["n"] < 250:
                continue
            sub = d[mask_of(d, lo, hi) & np.isfinite(w)]
            print(f"  {asset_of[nm]:4s} {nm:13s} {sub[0]+'~'+sub[-1]:22s} {st['yrs']:5.1f} "
                  f"{st['cagr']*100:6.1f}% {st['vol']*100:6.1f}% {st['sharpe']:6.2f} "
                  f"{st['maxdd']*100:8.1f}% {st['cum']*100:7.0f}%")
        print()

    # ---------- [2] 判据打分 ----------
    print("[2] 预注册判据（按 4 个代表资产计数；判定 = C1∧C2∧C3∧C4∧C6）")
    print(f"  {'方法':22s} {'C1夏普':>7s} {'C2回撤':>7s} {'C3收益':>7s} {'C4IS同向':>9s} "
          f"{'C6换手':>8s} {'OOS夏普Δ均值':>13s} {'OOS回撤Δ均值':>13s} {'判定':>5s}")
    for label, kind, p in METHODS:
        if kind == "bh":
            continue
        c1 = c2 = c3 = c4 = 0
        dsh, ddd, trs = [], [], []
        for nm in REP:
            r = run(nm, kind, p)
            b = run(nm, "bh", None)
            if not (r["oos"] and r["is"] and b["oos"] and b["is"]):
                continue
            s_o, s_i = r["oos"]["sharpe"] - b["oos"]["sharpe"], r["is"]["sharpe"] - b["is"]["sharpe"]
            d_o = r["oos"]["maxdd"] - b["oos"]["maxdd"]
            d_i = r["is"]["maxdd"] - b["is"]["maxdd"]
            c_g = r["oos"]["cagr"] - b["oos"]["cagr"]
            dsh.append(s_o); ddd.append(d_o); trs.append(r["oos_tr"])
            c1 += s_o >= 0.15
            c2 += (d_o > 0) and (d_i > 0)
            c3 += c_g >= -0.015
            c4 += (s_o > 0) == (s_i > 0)
        tr = float(np.mean(trs)) if trs else np.nan
        ok = c1 >= 3 and c2 == 4 and c3 >= 3 and c4 >= 3 and (np.isfinite(tr) and tr <= 30)
        print(f"  {label:22s} {c1:>5d}/4 {c2:>5d}/4 {c3:>5d}/4 {c4:>7d}/4 "
              f"{tr:>8.1f} {np.mean(dsh):>+13.2f} {np.mean(ddd)*100:>+12.1f}pp "
              f"{'过' if ok else '不过':>5s}")

    # ---------- [3] 明细：每个方法 OOS/IS 逐资产 ----------
    print("\n[3] 逐资产明细（OOS 段；括号内为一直持有；换手=每年调仓次数）")
    for label, kind, p in METHODS:
        if kind == "bh":
            continue
        print(f"\n  {label}")
        for nm in REP:
            r = run(nm, kind, p)
            b = run(nm, "bh", None)
            if not (r["oos"] and b["oos"]):
                print(f"    {nm:10s} 样本不足")
                continue
            print(f"    {nm:10s} 夏普 {r['oos']['sharpe']:.2f}({b['oos']['sharpe']:.2f})  "
                  f"CAGR {r['oos']['cagr']*100:6.1f}%({b['oos']['cagr']*100:6.1f}%)  "
                  f"回撤 {r['oos']['maxdd']*100:6.1f}%({b['oos']['maxdd']*100:6.1f}%)  "
                  f"IS 夏普 {r['is']['sharpe']:.2f}({b['is']['sharpe']:.2f})  "
                  f"换手 {r['oos_tr']:5.1f}")

    # ---------- [4] C7 收益上有没有证据 ----------
    print("\n[4] C7 日超额（对一直持有，同窗同成本，OOS，4 条代表序列合并）的移动块自助 95%CI")
    for label, kind, p in METHODS:
        if kind == "bh":
            continue
        x = np.concatenate([run(nm, kind, p)["excess_oos"] for nm in REP])
        ci = block_ci(x)
        if not ci:
            continue
        m, lo, hi = ci
        tag = "整段在零下" if hi < 0 else ("整段在零上" if lo > 0 else "含 0：收益上没证据")
        print(f"  {label:22s} 日均超额 {m*1e4:+7.1f}bp  CI [{lo*1e4:+7.1f}, {hi*1e4:+7.1f}]  {tag}")

    # ---------- [5] C5 成本翻倍 ----------
    print("\n[5] C5 成本敏感性：0.12% → 0.30%/side 的 OOS 夏普（4 资产均值）")
    print(f"  {'方法':22s} {'0.12%':>7s} {'0.30%':>7s} {'Δ':>7s}  {'0.30% 下回撤仍全优?':>22s}")
    for label, kind, p in METHODS:
        if kind == "bh":
            continue
        a, b2, ddok = [], [], 0
        for nm in REP:
            r1, r2 = run(nm, kind, p, COST), run(nm, kind, p, COST_HI)
            bb = run(nm, "bh", None, COST_HI)
            if r1["oos"] and r2["oos"] and bb["oos"]:
                a.append(r1["oos"]["sharpe"]); b2.append(r2["oos"]["sharpe"])
                ddok += (r2["oos"]["maxdd"] > bb["oos"]["maxdd"])
        print(f"  {label:22s} {np.mean(a):>7.2f} {np.mean(b2):>7.2f} "
              f"{np.mean(b2)-np.mean(a):>+7.2f}  {f'{ddok}/4':>18s}")

    # ---------- [6] 稳健性：同资产的另一市场报价 ----------
    print("\n[6] 稳健性：同一资产换个市场报价（境内上期所 / 上金所），OOS 夏普Δ（对各自基准）均值")
    alts = [nm for nm in names if nm not in REP]
    print(f"  {'方法':22s} " + " ".join(f"{nm.replace('sina_','').replace('sge_',''):>9s}" for nm in alts))
    for label, kind, p in METHODS:
        if kind == "bh":
            continue
        cells = []
        for nm in alts:
            F = load_series(nm)
            r = stats(strat_ret(F, pos_rule(F, kind, p), COST)[mask_of(F["date"], SPLIT)])
            b = stats(strat_ret(F, pos_rule(F, "bh", None), COST)[mask_of(F["date"], SPLIT)])
            cells.append(f"{(r['sharpe'] - b['sharpe']) if (r and b) else float('nan'):>9.2f}")
        print(f"  {label:22s} " + " ".join(cells))

    # ---------- [7] 回撤时长：建议到底改变了什么 ----------
    print("\n[7] OOS 段「水下时间」占比与最深回撤持续天数（这是风险量，不是收益）")
    print(f"  {'方法':22s} " + " ".join(f"{nm[-3:]:>16s}" for nm in REP))
    for label, kind, p in METHODS:
        cells = []
        for nm in REP:
            F = load_series(nm)
            w = strat_ret(F, pos_rule(F, kind, p), COST)[mask_of(F["date"], SPLIT)]
            st = stats(w)
            if not st:
                cells.append(f"{'—':>16s}")
                continue
            eq = np.cumprod(1 + w[np.isfinite(w)])
            uwm = eq < np.maximum.accumulate(eq)
            cur = uw = 0
            for f in uwm:
                uw = uw + 1 if f else 0
                cur = max(cur, uw)
            cells.append(f"{uwm.mean()*100:>6.0f}%/{cur:>4d}d")
        print(f"  {label:22s} " + " ".join(cells))


if __name__ == "__main__":
    main()
