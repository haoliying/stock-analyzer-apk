#!/usr/bin/env python3
"""metals_iter10 — 跨市场信息：境内外盘 lead-lag 与相对偏离（贵金属的"另一种方式"）

iter9 的结论是"借来的口径（均线/动量/突破/止损/回调/带宽）在贵金属上一个都没过判据"。
本脚本回答剩下的另一半：**贵金属独有的、A 股那套根本用不上的信息**——同一件商品在两个市场
（纽约/伦敦收盘 vs 上海收盘）以两种货币报价，两者之间的领先关系与价差偏离。
iter2 量到 corr(XAU_t, AU0_{t+1}) = 0.66，我当时把它讲成了"境内跟随外盘、有领先关系"。
**这句话本身要先被检验**：它很可能只是"同一个夜晚的行情被两条收盘价各记了一次"。

════════════════════════════════════════════════════════════════════════
预注册口径（跑之前写死，跑完不改；要改必须另开 iter 并说明理由）
════════════════════════════════════════════════════════════════════════
时间可得性（本脚本的全部要点都在这）
  · 外盘日线 stamp=e_k 那根的收盘，实际到账约北京时间 e_k 次日 05:00
    → known_at(e_k) := e_k + 1 个自然日
  · 境内 A_i = close(d_{i-1}) → close(d_i) 的收盘对收盘收益；境内收盘约 15:00
  · 三种配对：
      E1 重叠： dd[i-1] < known_at ≤ dd[i]     信号掉在收益窗口中间（机械重叠，不可交易）
      E2 干净： 第一个满足 dd[i-1] ≥ known_at 的 i  到账之后第一个完整境内窗口
      E3 对照： E2 再往后一格（连它都显著就是判据在骗人）
  · iter9 的教训：偏移一天就能把夏普吹上天，所以 E1/E2/E3 必须分开报

T1 lead-lag（能不能预测）
  配对：XAU→AU0、XAU→Au99.99、XAU→Au100g、XAG→AG0、XAG→AgT+D、XPT→Pt99.95（钯无境内对应）
  统计量：Spearman IC；外盘涨/跌两组各自的境内次窗均值（bp）+ 移动块自助 95%CI
  L1：E2 的 IC ≥ 0.05 且 CI 不含 0，且 4 个年代里 ≥3 个同号；
      若 E1 显著而 E2 不显著 → 判为"重叠假象"，不许当作预测写进任何文档
  L2：按 E2 信号做 0/1 择时，干净滞后 + 0.12%/side 后 OOS 年化 ≥ 一直持有 −1.5pp，且换手 ≤ 30/年

T2 相对偏离（只问"贵不贵"，不问方向）
  spread_t = log(A) − log(B)，同日 inner join；FX 漂移由长均线吸收，所以 z 度量的是
  "偏离自己的常态"，不是"绝对贵贱"（这点必须写进界面文案）
  z_k,t = (spread_t − MA_k(spread)_t) / std_k，k ∈ {20, 60, 120}
  未来偏离 fd_h = spread_{t+h} − spread_t，h ∈ {5, 20}
  标的：境内外溢价（6 组）+ 金银比（同币种，无 FX 问题，k ∈ {60,120}）
  R1：|IC| ≥ 0.06 且回归均值的日均幅度 CI 不含 0，且 ≥3/4 年代同号
  R2：把 z 变仓位（z > +0.5σ → 该标的只留 50%，否则满仓；干净滞后、扣成本）
      OOS 夏普 ≥ 一直持有 + 0.15 且回撤不劣化，且日超额 CI 不含 0
  R3：这个读数必须只用 App 行情页已有的数据算得出来（日 K 收盘 + 同日外盘收盘），
      否则不许上界面

跑完只报告，不挑参数。若全不过，产品结论仍是"贵金属不给建议"，
并把"corr=0.66 其实是窗口重叠"这条一起写进文档——因为我上一版讲错了。
"""
import json
import os
from datetime import date, timedelta

import numpy as np
import pandas as pd
from scipy.stats import spearmanr

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.join(HERE, "metals_data")
COST, DAYS = 0.0012, 252
SPLIT, IS_HI = "2021-01-01", "2020-12-31"
ERAS = [("2008-2014", "2008-01-01", "2014-12-31"),
        ("2015-2019", "2015-01-01", "2019-12-31"),
        ("2020-2023", "2020-01-01", "2023-12-31"),
        ("2024-2026", "2024-01-01", "2026-12-31")]
PAIRS = [("sina_XAU", "sina_AU0"), ("sina_XAU", "sge_Au99.99"), ("sina_XAU", "sge_Au100g"),
         ("sina_XAG", "sina_AG0"), ("sina_XAG", "sge_AgT+D"), ("sina_XPT", "sge_Pt99.95")]
RATIOS = [("金银比", "sina_XAU", "sina_XAG", "sina_XAU"),
          ("银金比", "sina_XAG", "sina_XAU", "sina_XAG")]


def short(nm):
    return nm.replace("sina_", "").replace("sge_", "")


def _d(s):
    return date(int(s[0:4]), int(s[5:7]), int(s[8:10]))


CACHE = {}


def series(nm):
    if nm not in CACHE:
        d = json.load(open(os.path.join(DATA, nm + ".json"), encoding="utf-8"))
        r = pd.DataFrame(d["rows"])
        CACHE[nm] = {"date": r["date"].to_numpy(str), "close": r["close"].to_numpy(float)}
    return CACHE[nm]


def block_ci(x, reps=4000, blk=63, seed=7):
    x = np.asarray(x, float)
    x = x[np.isfinite(x)]
    if len(x) < blk * 4:
        return None
    rng = np.random.default_rng(seed)
    nb = int(np.ceil(len(x) / blk))
    means = np.empty(reps)
    for i in range(reps):
        idx = rng.integers(0, len(x) - blk, size=nb)
        means[i] = np.concatenate([x[j:j + blk] for j in idx])[:len(x)].mean()
    return float(x.mean()), float(np.percentile(means, 2.5)), float(np.percentile(means, 97.5))


# ─────────────────────── T1：lead-lag 的三种配对 ───────────────────────

def lead_lag(ovs_nm, dom_nm):
    o, dm = series(ovs_nm), series(dom_nm)
    od, oc, dd, dc = o["date"], o["close"], dm["date"], dm["close"]
    dret = np.concatenate([[np.nan], dc[1:] / dc[:-1] - 1.0])
    oret = np.concatenate([[np.nan], oc[1:] / oc[:-1] - 1.0])
    out = {k: [] for k in ("E1", "E2", "E3")}
    for k in range(1, len(od)):
        if not np.isfinite(oret[k]):
            continue
        known = (_d(od[k]) + timedelta(days=1)).isoformat()
        st = np.searchsorted(dd, known)          # 第一个 ≥ known 的境内交易日
        if st < 1 or st + 2 >= len(dd):
            continue
        for tag, j in (("E1", st), ("E2", st + 1), ("E3", st + 2)):
            if np.isfinite(dret[j]):
                out[tag].append((od[k], known, dd[j - 1], dd[j], oret[k], dret[j]))
    return out


def stats_on(rows, lo=None, hi=None):
    a = np.array([[s, y] for _, _, _, dt, s, y in rows if (lo is None or dt >= lo) and (hi is None or dt <= hi)])
    if len(a) < 120:
        return None
    up, dn = a[a[:, 0] > 0, 1], a[a[:, 0] < 0, 1]
    return {"n": len(a), "ic": float(spearmanr(a[:, 0], a[:, 1]).statistic),
            "up": up, "dn": dn, "gap": float(up.mean() - dn.mean()) if len(up) > 30 and len(dn) > 30 else np.nan}


# ─────────────────────── T2：偏离度与仓位 ───────────────────────

def spread(a_nm, b_nm):
    sa = pd.Series(series(a_nm)["close"], index=series(a_nm)["date"])
    sb = pd.Series(series(b_nm)["close"], index=series(b_nm)["date"])
    j = pd.concat([sa, sb], axis=1, join="inner").dropna()
    return j.index.to_numpy(str), (np.log(j.iloc[:, 0]) - np.log(j.iloc[:, 1])).to_numpy(float)


def zscore(s, k):
    ma = pd.Series(s).rolling(k, min_periods=k).mean().to_numpy()
    sd = pd.Series(s).rolling(k, min_periods=k).std(ddof=0).to_numpy()
    with np.errstate(invalid="ignore", divide="ignore"):
        return (s - ma) / sd


def fwd_dev(s, h):
    out = np.full(len(s), np.nan)
    out[:-h] = s[h:] - s[:-h]
    return out


def strat(dates, close, pos, cost=COST):
    """第 t 日收益 = pos[t-1] × ret(t) − |pos[t-1]−pos[t-2]|×cost（iter9 修完偏移后的写法）。"""
    ret = np.concatenate([[np.nan], close[1:] / close[:-1] - 1.0])
    w = np.full(len(close), np.nan)
    prev = 0.0
    for t in range(1, len(close)):
        p = pos[t - 1]
        if not (np.isfinite(p) and np.isfinite(ret[t])):
            continue
        w[t] = p * ret[t] - abs(p - prev) * cost
        prev = p
    return w


def stat(w):
    w = np.asarray(w, float)
    w = w[np.isfinite(w)]
    if len(w) < 120:
        return None
    eq = np.cumprod(1 + w)
    yrs, sd = len(w) / DAYS, w.std(ddof=0)
    return {"n": len(w), "yrs": yrs, "cagr": eq[-1] ** (1 / yrs) - 1,
            "sharpe": (w.mean() / sd * np.sqrt(DAYS)) if sd > 0 else np.nan,
            "maxdd": float((eq / np.maximum.accumulate(eq) - 1).min())}


def turns(dates, pos):
    p = pos[np.isfinite(pos)]
    if len(p) < 120:
        return np.nan
    return float((np.abs(np.diff(p)) > 1e-9).sum() / (len(p) / DAYS))


def asset_on(dates, nm):
    """按给定日期序列取出该标的的收盘（日期不全是交易日时按最近可得处理）。"""
    s = pd.Series(series(nm)["close"], index=series(nm)["date"])
    return s.reindex(dates).to_numpy(float)


def main():
    print("=" * 118)
    print("metals_iter10  跨市场信息：lead-lag 的可得性分解 + 相对偏离")
    print("  E1=窗口重叠（不可交易）  E2=信号到账后第一个完整境内窗口  E3=再后一天（对照）")
    print("=" * 118)

    # ---------- [1] T1 ----------
    print("\n[1] T1 外盘收盘 → 境内下一窗：Spearman IC（括号内 n）")
    print(f"  {'外盘→境内':20s} {'E1 重叠':>22s} {'E2 干净':>22s} {'E3 对照':>22s}")
    rows_all, agg = {}, {v: [] for v in ("E1", "E2", "E3")}
    for o, dm in PAIRS:
        rr = lead_lag(o, dm)
        rows_all[(o, dm)] = rr
        cells = []
        for v in ("E1", "E2", "E3"):
            c = stats_on(rr[v])
            cells.append(f"{c['ic']:+.3f} (n={c['n']})".rjust(22) if c else "样本不足".rjust(22))
            for x in rr[v]:
                agg[v].append((x[4], x[5], x[3]))
        print(f"  {short(o)+'→'+short(dm):20s} " + " ".join(cells))

    print("\n  合并 6 组配对后的条件均值（外盘涨 vs 跌，境内次窗；bp，移动块自助 95%CI）")
    print(f"  {'配对':10s} {'n':>6s} {'外盘涨→境内':>24s} {'外盘跌→境内':>24s} {'涨−跌':>10s} {'IC':>8s}")
    for v in ("E1", "E2", "E3"):
        c = stats_on([(None, None, None, d, s, y) for s, y, d in agg[v]])
        if not c:
            continue
        cu, cd = block_ci(c["up"]), block_ci(c["dn"])
        print(f"  {v:10s} {c['n']:>6d}  [{cu[1]*1e4:+6.1f},{cu[2]*1e4:+6.1f}] mean {cu[0]*1e4:>+7.1f}"
              f"  [{cd[1]*1e4:+6.1f},{cd[2]*1e4:+6.1f}] mean {cd[0]*1e4:>+7.1f}"
              f" {c['gap']*1e4:>+9.1f}bp {c['ic']:>+8.3f}")

    print("\n  L1 年代稳定性（各配对 E2 的 IC 分年代；≥0.05 的年代数）")
    for (o, dm), rr in rows_all.items():
        parts, sig = [], 0
        for lab, lo, hi in ERAS:
            c = stats_on(rr["E2"], lo, hi)
            parts.append(f"{lab[:4]}:{c['ic']:+.2f}" if c else f"{lab[:4]}:—")
            sig += 1 if c and c["ic"] >= 0.05 else 0
        print(f"  {short(o)+'→'+short(dm):20s} " + "  ".join(parts) + f"   {sig}/4")

    # ---------- [2] L2 ----------
    print("\n[2] L2 把 E2 信号当择时（外盘涨→持 100%，跌→空仓），干净滞后 + 0.12%/side，OOS ≥2021")
    print(f"  {'外盘→境内':20s} {'夏普':>7s} {'基准':>7s} {'年化':>8s} {'基准':>8s} {'回撤':>8s} {'基准':>8s} {'换手':>7s} {'日超额95%CI':>22s}")
    for (o, dm), rr in rows_all.items():
        dd = series(dm)["date"]
        dc = series(dm)["close"]
        pos = np.full(len(dc), np.nan)
        sig = {d: s for _, _, _, d, s, _ in rr["E2"]}
        for i, dt in enumerate(dd):
            if dt in sig:
                pos[i] = 1.0 if sig[dt] > 0 else 0.0
        m = dd >= SPLIT
        w, wb = strat(dd, dc, pos)[m], strat(dd, dc, np.ones(len(dc)))[m]
        s, b = stat(w), stat(wb)
        if not (s and b):
            continue
        ci = block_ci(w - wb)
        txt = f"[{ci[1]*1e4:+6.1f},{ci[2]*1e4:+6.1f}]" if ci else "—"
        print(f"  {short(o)+'→'+short(dm):20s} {s['sharpe']:>7.2f} {b['sharpe']:>7.2f} "
              f"{s['cagr']*100:>7.1f}% {b['cagr']*100:>7.1f}% {s['maxdd']*100:>7.1f}% {b['maxdd']*100:>7.1f}% "
              f"{turns(dd[m], pos[m]):>7.1f} {txt:>22s}")

    # ---------- [3] T2 偏离 → 未来偏离 ----------
    print("\n[3] T2 z = (spread − MA_k)/std_k；看未来 h 日 spread 的变化（是否回归自己的常态）")
    print(f"  {'标的':34s} {'k':>4s} {'h':>3s} {'IC(z, Δspread)':>15s} {'Δspread均值':>13s} {'回归方向幅度CI':>24s} {'年代同号':>9s}")
    for lab, a, b, _ in RATIOS + [(f"境内溢价 {short(dm)} vs {short(o)}", dm, o, dm) for o, dm in PAIRS]:
        ds, sp = spread(a, b)
        for k in ((60, 120) if lab in ("金银比", "银金比") else (20, 60, 120)):
            for h in (5, 20):
                z, fd = zscore(sp, k), fwd_dev(sp, h)
                ok = np.isfinite(z) & np.isfinite(fd)
                if ok.sum() < 400:
                    continue
                zz, ff, dt = z[ok], fd[ok], ds[ok]
                ic = float(spearmanr(zz, ff).statistic)
                ci = block_ci(-ff * np.sign(ic))          # 取回归均值的正方向
                sig = tot = 0
                for el, lo, hi in ERAS:
                    m = (dt >= lo) & (dt <= hi)
                    if m.sum() >= 250:
                        tot += 1
                        sig += 1 if float(spearmanr(zz[m], ff[m]).statistic) * np.sign(ic) > 0 else 0
                print(f"  {lab:34s} {k:>4d} {h:>3d} {ic:>+15.3f} {(-ff*np.sign(ic)).mean()*1e4:>+12.1f}bp "
                      f"[{ci[1]*1e4:+7.1f},{ci[2]*1e4:+7.1f}] {f'{sig}/{tot}':>9s}")

    # ---------- [4] R2 偏离变仓位 ----------
    print("\n[4] R2 规则：z > +0.5σ（相对自己的常态太贵）→ 该标的只留 50%，否则满仓。干净滞后、OOS ≥2021")
    print(f"  {'规则':34s} {'k':>4s} {'夏普':>7s} {'基准':>7s} {'年化':>8s} {'基准':>8s} {'回撤':>8s} {'基准':>8s} {'换手':>7s} {'日超额95%CI':>22s}")
    cands = [("金银比→金", "sina_XAU", "sina_XAG", "sina_XAU", 120),
             ("金银比→金", "sina_XAU", "sina_XAG", "sina_XAU", 60),
             ("银金比→银", "sina_XAG", "sina_XAU", "sina_XAG", 120)]
    cands += [(f"境内溢价→{short(dm)}", dm, o, dm, 60) for o, dm in PAIRS]
    cands += [(f"境内溢价→{short(dm)}", dm, o, dm, 120) for o, dm in PAIRS]
    for lab, a, b, asset, k in cands:
        ds, sp = spread(a, b)
        z = zscore(sp, k)
        pos = np.where(np.isfinite(z), np.where(z > 0.5, 0.5, 1.0), np.nan)
        cl = asset_on(ds, asset)
        ok = np.isfinite(cl)
        ds, cl, pos = ds[ok], cl[ok], pos[ok]
        m = ds >= SPLIT
        w, wb = strat(ds, cl, pos)[m], strat(ds, cl, np.ones(len(cl)))[m]
        s, bm = stat(w), stat(wb)
        if not (s and bm):
            continue
        ci = block_ci(w - wb)
        txt = f"[{ci[1]*1e4:+6.1f},{ci[2]*1e4:+6.1f}]" if ci else "—"
        print(f"  {lab:34s} {k:>4d} {s['sharpe']:>7.2f} {bm['sharpe']:>7.2f} "
              f"{s['cagr']*100:>7.1f}% {bm['cagr']*100:>7.1f}% {s['maxdd']*100:>7.1f}% {bm['maxdd']*100:>7.1f}% "
              f"{turns(ds[m], pos[m]):>7.1f} {txt:>22s}")

    # ---------- [5] R3 读数可行性 ----------
    print("\n[5] R3 「现在贵不贵」这个读数本身（描述性，不是预测）：当前 z 与历史分位")
    for lab, a, b, _ in RATIOS:
        ds, sp = spread(a, b)
        for k in (60, 120, 250):
            zz = zscore(sp, k)
            zz = zz[np.isfinite(zz)]
            cur = zz[-1]
            print(f"  {lab:12s} z({k:>3d}) 当前 {cur:+.2f}σ  全历史分位 {(zz <= cur).mean()*100:5.1f}%  "
                  f"p90 {np.percentile(zz, 90):+.2f}σ p10 {np.percentile(zz, 10):+.2f}σ  截至 {ds[-1]}")
    for o, dm in PAIRS:
        ds, sp = spread(dm, o)
        zz = zscore(sp, 120)
        zz = zz[np.isfinite(zz)]
        cur = zz[-1]
        print(f"  境内{short(dm):<8s} vs {short(o):<5s} z(120) 当前 {cur:+.2f}σ 分位 {(zz <= cur).mean()*100:5.1f}%  "
              f"p90 {np.percentile(zz, 90):+.2f}σ p10 {np.percentile(zz, 10):+.2f}σ  截至 {ds[-1]}")


if __name__ == "__main__":
    main()
