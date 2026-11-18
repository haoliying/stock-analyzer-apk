#!/usr/bin/env python3
"""metals_iter11 — 境内溢价 z 到底算不算建议？（修正 iter10 §[4] 的滞后 + 停更价混淆）

为什么另开一个 iter（不是改判据，是补上我自己没做到的口径）：
  iter10 §[4] 写着"干净滞后"，实际只有半个。信号 z[t] 含外盘 stamp=t 的收盘，这条收盘要到
  **北京时间 t+1 日 05:00** 才到账；可它驱动的窗口是境内 close(t)→close(t+1)，起点是 t 日 15:00
  —— 窗口前 14 小时（含境内夜盘）发生在信号可知之前。于是"溢价太高就减半仓"跑出 OOS 夏普 2.07、
  年化 33%，好得离谱。iter9 已经因为"偏移一天"把夏普从 0.2 吹到 2.4，同类错第二次出现，按 bug 处理。

════════════════════════════════════════════════════════════════════════
预注册口径（跑之前写死）
════════════════════════════════════════════════════════════════════════
时间线（境内一天交易的真实顺序）
  t 日 15:00 境内收盘 → t 日 20:00–t+1 日 02:30 夜盘（属交易日 t+1）
  → t+1 日 05:00 外盘 stamp=t 的收盘到账 → t+1 日 09:00–15:00 境内日盘（收盘 = close(t+1)）
  → t+2 日 15:00 close(t+2)
  · 信号在 t+1 日 05:00 才可知，最早能动用的是 t+1 日 09:00 的日盘；本脚本更保守，
    只用 close(t+1)→close(t+2) 这段收益（**L=2**）。
  · L=1（=iter10 §[4] 的写法，pos[t] 驱动 close(t)→close(t+1)）作为**泄漏版**并列展示，
    两者的差就是"这条规则有多少钱是时间差造出来的"。判定一律看 L=2。
  · 外盘收盘按"境内日期 ≤ dd 的最近一根"取（外盘那天没开市时不会瞎补）。
主混淆：停更价（stale）
  · 定义：境内两根收盘的自然日间隔 ≥ 4 天（长假/春节/国庆），或
          境内当日 |涨跌幅| < 0.05% 而外盘对应区间 |涨跌| ≥ 0.5%（境内没动、外盘动了）
  · 这类交易日的 spread 一定会跳一大截，但那是"境内价还没反映外盘"的行情事实，不是预测力
  · 操作化处理：stale 信号**不建仓也不减仓**（沿用上一个非 stale 日的仓位；长假期间本来就动不了），
    基准始终是一直持有 100% —— 不把 stale 日从基准里挖掉，否则等于把追涨缺口算两遍
成本 / 仓位
  · 0.12%/side（敏感性 0.30%）；z > +0.5σ → 半仓，否则满仓；long-only，无杠杆
  · 参数只取自预先声明清单：k ∈ {60, 120}，阈值 0.5σ，标的 6 组（境内 vs 同日外盘）
计数与判据（6 条报价 = 3 个独立资产：金 / 银 / 铂）
  代表：XAU→AU0、XAG→AG0、XPT→Pt99.95；其余 3 条（Au99.99、Au100g、AgT+D）只作稳健性
  K1 L=2 剔 stale 的 OOS(≥2021) 夏普 ≥ 一直持有 + 0.15：≥2/3 资产
  K2 同期最大回撤优于一直持有：3/3
  K3 OOS 年化 ≥ 一直持有 − 1.5pp：≥2/3
  K4 IS(≤2020) 方向一致：≥2/3
  K5 0.30%/side 下 K1 仍 ≥2/3
  K6 换手 ≤ 30 次/年（均值）
  K7 日超额移动块自助 95%CI 不含 0；含 0 就只准引用风险量证据
  判定 = K1∧K2∧K3∧K4∧K5∧K6∧K7
另外必须回答（不过判据也要说清）：
  Q1 溢价回归那段里境内腿、外盘腿各贡献多少（决定"这个读数值多少钱"）
  Q2 金银比 z 在 L=2 下对金的相对表现还有没有预测力
  Q3 数据末日的读数是多少、App 只用行情页现有数据能否算出来
"""
import json
import os
from datetime import date

import numpy as np
import pandas as pd

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.join(HERE, "metals_data")
COST, COST_HI, DAYS = 0.0012, 0.0030, 252
SPLIT, IS_HI = "2021-01-01", "2020-12-31"
REP = [("sina_XAU", "sina_AU0", "金"), ("sina_XAG", "sina_AG0", "银"), ("sina_XPT", "sge_Pt99.95", "铂")]
ROBUST = [("sina_XAU", "sge_Au99.99", "金"), ("sina_XAU", "sge_Au100g", "金"), ("sina_XAG", "sge_AgT+D", "银")]
KS, THR = (60, 120), 0.5


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


def zscore(s, k):
    ma = pd.Series(s).rolling(k, min_periods=k).mean().to_numpy()
    sd = pd.Series(s).rolling(k, min_periods=k).std(ddof=0).to_numpy()
    with np.errstate(invalid="ignore", divide="ignore"):
        return (s - ma) / sd


def build(ovs_nm, dom_nm):
    """在境内自己的交易日轴上：境内收盘/收益、当日"最近可得"的外盘收盘、溢价 spread、stale 标记。"""
    dm, ov = series(dom_nm), series(ovs_nm)
    dd, dc = dm["date"], dm["close"]
    od, oc_ = ov["date"], ov["close"]
    idx = np.searchsorted(od, dd, side="right") - 1            # ≤ 境内日期的最后一根外盘
    ovs_last = np.where(idx >= 0, oc_[np.clip(idx, 0, None)], np.nan)
    ovs_last_prev = np.concatenate([[np.nan], ovs_last[:-1]])
    dom_ret = dc / np.concatenate([[np.nan], dc[:-1]]) - 1.0
    ovs_ret = ovs_last / ovs_last_prev - 1.0
    gap = np.full(len(dd), 99)
    gap[1:] = pd.to_datetime(pd.Series(dd)).diff().dt.days.to_numpy()[1:]
    spread = np.where(np.isfinite(ovs_last) & (dc > 0) & (ovs_last > 0), np.log(dc) - np.log(ovs_last), np.nan)
    stale = (gap >= 4) | ((np.abs(dom_ret) < 0.0005) & (np.abs(ovs_ret) >= 0.005))
    return {"date": dd, "close": dc, "dom_ret": dom_ret, "ovs_ret": ovs_ret, "spread": spread,
            "gap": gap, "stale": stale, "ovs_last": ovs_last}


def pos_rule(z, stale):
    """z>+0.5σ→半仓，否则满仓；stale 日不动（沿用上一仓位），非信号日满仓。"""
    n = len(z)
    raw = np.where(np.isfinite(z), np.where(z > THR, 0.5, 1.0), np.nan)
    out = np.empty(n)
    cur = 1.0
    for t in range(n):
        if np.isfinite(raw[t]) and not stale[t]:
            cur = raw[t]
        out[t] = cur
    return out


def strat(close, pos, lag, cost=COST):
    """w[t] = pos[t-lag]*ret(t) − |pos[t-lag]−pos[t-lag-1]|*cost；lag=2 才是干净可得性。"""
    ret = np.concatenate([[np.nan], close[1:] / close[:-1] - 1.0])
    n = len(close)
    w = np.full(n, np.nan)
    prev = 0.0
    for t in range(1, n):
        p = pos[t - lag] if t - lag >= 0 else np.nan
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
    eq, yrs = np.cumprod(1 + w), len(w) / DAYS
    sd = w.std(ddof=0)
    return {"n": len(w), "cagr": eq[-1] ** (1 / yrs) - 1,
            "sharpe": (w.mean() / sd * np.sqrt(DAYS)) if sd > 0 else np.nan,
            "maxdd": float((eq / np.maximum.accumulate(eq) - 1).min())}


def turns(pos, m):
    p = pos[m]
    p = p[np.isfinite(p)]
    if len(p) < 120:
        return np.nan
    return float((np.abs(np.diff(p)) > 1e-9).sum() / (len(p) / DAYS))


def block_ci(x, reps=4000, blk=63, seed=23):
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


def rows_for(o, dm, k):
    """返回该 (外盘, 境内, k) 的：pos、L=1/L=2 策略与基准的 OOS/IS 统计、换手、CI。"""
    F = build(o, dm)
    z = zscore(F["spread"], k)
    pos = pos_rule(z, F["stale"])
    dd = F["date"]
    one = np.ones(len(dd))
    m, si = dd >= SPLIT, dd <= IS_HI
    r = {"F": F, "z": z, "pos": pos, "k": k}
    for tag, lag in (("L1", 1), ("L2", 2)):
        w, wb = strat(F["close"], pos, lag), strat(F["close"], one, lag)
        r[tag] = {"oos": stat(w[m]), "oos_b": stat(wb[m]), "is": stat(w[si]), "is_b": stat(wb[si]),
                  "ex": (w - wb)[m],
                  "oos_hi": stat(strat(F["close"], pos, lag, COST_HI)[m]),
                  "oos_hi_b": stat(strat(F["close"], one, lag, COST_HI)[m])}
    r["tr"] = turns(pos, m)
    return r


def line(lab, r, tag):
    d = r[tag]
    if not (d["oos"] and d["oos_b"]):
        return
    ci = block_ci(d["ex"])
    txt = f"[{ci[1]*1e4:+6.1f},{ci[2]*1e4:+6.1f}]" if ci else "—"
    print(f"  {lab:26s} {tag:4s} 夏普 {d['oos']['sharpe']:>6.2f}(基准 {d['oos_b']['sharpe']:>5.2f})  "
          f"年化 {d['oos']['cagr']*100:>6.1f}%(基准 {d['oos_b']['cagr']*100:>5.1f}%)  "
          f"回撤 {d['oos']['maxdd']*100:>6.1f}%(基准 {d['oos_b']['maxdd']*100:>6.1f}%)  "
          f"换手 {r['tr']:>5.1f}  日超额CI {txt}")


def main():
    print("=" * 120)
    print("metals_iter11  境内溢价 z：泄漏版(L=1) vs 干净版(L=2)，stale 日不动仓｜0.12%/side｜z>+0.5σ→半仓")
    print("=" * 120)

    # ---------- [1] 混淆规模 ----------
    print("\n[1] 停更价（stale）有多常见：溢价跳一大截里有多少来自「境内没开市」")
    ALL = REP + ROBUST
    for o, dm, cls in ALL:
        F = build(o, dm)
        ok = np.isfinite(zscore(F["spread"], 120))
        print(f"  {cls}  {short(dm):9s} vs {short(o):5s} 有效 z {ok.sum():>5d} 天  "
              f"stale {(ok & F['stale']).sum():>4d} 天（{(ok & F['stale']).sum()/max(ok.sum(),1)*100:4.1f}%）  "
              f"其中长假 {(ok & (F['gap']>=4)).sum():>4d} 天  末日期 {F['date'][-1]}")

    # ---------- [2] 同一规则的三种口径 ----------
    print("\n[2] 同一条规则、同一个标的，只差在「什么时候才知道信号 / stale 动不动仓」（OOS ≥2021）")
    store = {}
    for o, dm, cls in ALL:
        for k in KS:
            r = rows_for(o, dm, k)
            store[(o, dm, k)] = r
            lab = f"{short(dm)}/{short(o)} k={k}"
            line(lab, r, "L1")
            line(lab, r, "L2")
            # 反面口径：stale 日也跟着动（就是 iter10 里那种"看起来很美"的做法）
            z = r["z"]
            pos_naive = np.where(np.isfinite(z), np.where(z > THR, 0.5, 1.0), np.nan)
            dd = r["F"]["date"]
            m = dd >= SPLIT
            w, wb = strat(r["F"]["close"], pos_naive, 2), strat(r["F"]["close"], np.ones(len(dd)), 2)
            d = {"oos": stat(w[m]), "oos_b": stat(wb[m]), "ex": (w - wb)[m]}
            print(f"  {'↑ 对照：stale 日也照信号动':26s} {'L2b':4s} 夏普 {d['oos']['sharpe']:>6.2f}(基准 {d['oos_b']['sharpe']:>5.2f})  "
                  f"年化 {d['oos']['cagr']*100:>6.1f}%(基准 {d['oos_b']['cagr']*100:>5.1f}%)  "
                  f"回撤 {d['oos']['maxdd']*100:>6.1f}%(基准 {d['oos_b']['maxdd']*100:>6.1f}%)")
        print()

    # ---------- [3] 判据 ----------
    print("[3] K1..K7（代表资产 3 个：金 AU0 / 银 AG0 / 铂 Pt99.95；L=2、stale 不动仓、基准=一直持有）")
    print(f"  {'k':>4s} {'K1夏普':>7s} {'K2回撤':>7s} {'K3年化':>7s} {'K4同向':>7s} {'K5成本':>7s} {'K6换手':>7s} {'K7CI':>7s} {'判定':>6s}")
    for k in KS:
        c = dict.fromkeys("1234567", 0)
        trs = []
        for o, dm, cls in REP:
            r = store[(o, dm, k)]
            d = r["L2"]
            if not (d["oos"] and d["oos_b"] and d["is"] and d["is_b"] and d["oos_hi"] and d["oos_hi_b"]):
                continue
            ci = block_ci(d["ex"])
            c["1"] += d["oos"]["sharpe"] >= d["oos_b"]["sharpe"] + 0.15
            c["2"] += d["oos"]["maxdd"] > d["oos_b"]["maxdd"]
            c["3"] += d["oos"]["cagr"] >= d["oos_b"]["cagr"] - 0.015
            ds_o, ds_i = d["oos"]["sharpe"] - d["oos_b"]["sharpe"], d["is"]["sharpe"] - d["is_b"]["sharpe"]
            c["4"] += (ds_o > 0) == (ds_i > 0)
            c["5"] += d["oos_hi"]["sharpe"] >= d["oos_hi_b"]["sharpe"] + 0.10
            c["7"] += bool(ci) and (ci[1] > 0 or ci[2] < 0)
            trs.append(r["tr"])
        n_ = len(trs)
        ok = (c["1"] >= 2 and c["2"] == 3 and c["3"] >= 2 and c["4"] >= 2 and c["5"] >= 2
              and np.mean(trs) <= 30 and c["7"] >= 2)
        print(f"  {k:>4d} {c['1']:>4d}/{n_} {c['2']:>4d}/{n_} {c['3']:>4d}/{n_} {c['4']:>4d}/{n_} "
              f"{c['5']:>4d}/{n_} {np.mean(trs):>7.1f} {c['7']:>4d}/{n_} {'过' if ok else '不过':>6s}")

    print("\n  IS(≤2020) 段同表（防「只在 2021 年后的金价牛市里好看」）")
    for k in KS:
        print(f"  k={k}")
        for o, dm, cls in REP:
            d = store[(o, dm, k)]["L2"]
            if not (d["is"] and d["is_b"]):
                continue
            print(f"    {short(dm)+' vs '+short(o):18s} 夏普 {d['is']['sharpe']:>6.2f}(基准 {d['is_b']['sharpe']:>5.2f})  "
                  f"年化 {d['is']['cagr']*100:>6.1f}%(基准 {d['is_b']['cagr']*100:>5.1f}%)  "
                  f"回撤 {d['is']['maxdd']*100:>6.1f}%(基准 {d['is_b']['maxdd']*100:>6.1f}%)")

    # ---------- [4] Q1 谁在动 ----------
    print("\n[4] Q1 溢价从 z>+1σ 回到 |z|<0.5σ 这一段里，境内腿 vs 外盘腿的贡献（中位数，%）")
    print(f"  {'标的':22s} {'k':>4s} {'事件':>5s} {'spread收敛':>11s} {'境内腿':>9s} {'外盘腿':>9s} {'境内腿占比':>11s} {'收敛天数':>9s}")
    for o, dm, cls in ALL:
        F = build(o, dm)
        for k in KS:
            z, sp = zscore(F["spread"], k), F["spread"]
            dc, ov = F["close"], F["ovs_last"]
            ev, i, n = [], 0, len(dc)
            while i < n - 30:
                if np.isfinite(z[i]) and z[i] > 1.0 and not F["stale"][i]:
                    j = i + 1
                    while j < n - 1 and np.isfinite(z[j]) and z[j] > THR:
                        j += 1
                    if j < n - 1 and np.isfinite(z[j]) and np.isfinite(ov[i]) and np.isfinite(ov[j]):
                        ev.append((sp[j] - sp[i], np.log(dc[j] / dc[i]), np.log(ov[j] / ov[i]), j - i))
                    i = j + 1
                else:
                    i += 1
            if len(ev) < 8:
                print(f"  {short(dm)+' vs '+short(o):22s} {k:>4d} {len(ev):>5d} 事件不足")
                continue
            e = np.array(ev, float)
            conv, dom, ovs, days = np.nanmedian(e[:, 0]), np.nanmedian(e[:, 1]), np.nanmedian(e[:, 2]), np.nanmedian(e[:, 3])
            sh = abs(dom) / (abs(dom) + abs(ovs)) if abs(dom) + abs(ovs) > 0 else np.nan
            print(f"  {short(dm)+' vs '+short(o):22s} {k:>4d} {len(ev):>5d} {conv*100:>+10.1f}% {dom*100:>+8.1f}% "
                  f"{ovs*100:>+8.1f}% {sh*100:>10.0f}% {days:>9.0f}")

    # ---------- [5] Q2 金银比 ----------
    print("\n[5] Q2 金银比 z → 金的相对表现（同币种、无 FX；银为对照腿）")
    sa = pd.Series(series("sina_XAU")["close"], index=series("sina_XAU")["date"])
    sb = pd.Series(series("sina_XAG")["close"], index=series("sina_XAG")["date"])
    j = pd.concat([sa, sb], axis=1, join="inner").dropna()
    ds = j.index.to_numpy(str)
    sp = (np.log(j.iloc[:, 0]) - np.log(j.iloc[:, 1])).to_numpy(float)
    for nm, col, other in (("金 XAU", 0, 1), ("银 XAG", 1, 0)):
        ca = j.iloc[:, col].to_numpy(float)
        for k in KS:
            z = zscore(sp if col == 0 else -sp, k)
            pos = np.where(np.isfinite(z), np.where(z > THR, 0.5, 1.0), np.nan)
            m = ds >= SPLIT
            for lag, tag in ((1, "L1"), (2, "L2")):
                w, wb = strat(ca, pos, lag), strat(ca, np.ones(len(ca)), lag)
                s_, b_ = stat(w[m]), stat(wb[m])
                if not (s_ and b_):
                    continue
                ci = block_ci((w - wb)[m])
                txt = f"[{ci[1]*1e4:+6.1f},{ci[2]*1e4:+6.1f}]" if ci else "—"
                print(f"  {nm+' 金银比 z('+str(k)+')':26s} {tag:4s} 夏普 {s_['sharpe']:>6.2f}(基准 {b_['sharpe']:>5.2f})  "
                      f"年化 {s_['cagr']*100:>6.1f}%(基准 {b_['cagr']*100:>5.1f}%)  "
                      f"回撤 {s_['maxdd']*100:>6.1f}%(基准 {b_['maxdd']*100:>6.1f}%)  换手 {turns(pos, m):>5.1f}  CI {txt}")

    # ---------- [6] Q3 当前读数 ----------
    print("\n[6] Q3 数据末日的读数（描述性，不是预测）")
    for o, dm, cls in ALL:
        F = build(o, dm)
        for k in KS:
            v = zscore(F["spread"], k)
            v = v[np.isfinite(v)]
            print(f"  {cls} 境内{short(dm):9s} vs {short(o):5s} z({k:>3d})={v[-1]:+.2f}σ 历史分位 "
                  f"{(v <= v[-1]).mean()*100:5.1f}% 末日 stale={'是' if F['stale'][-1] else '否'} 截至 {F['date'][-1]}")
    sa2 = pd.Series(series("sina_XAU")["close"], index=series("sina_XAU")["date"])
    sb2 = pd.Series(series("sina_XAG")["close"], index=series("sina_XAG")["date"])
    j2 = pd.concat([sa2, sb2], axis=1, join="inner").dropna()
    sp2 = (np.log(j2.iloc[:, 0]) - np.log(j2.iloc[:, 1])).to_numpy(float)
    for k in KS:
        v = zscore(sp2, k)
        v = v[np.isfinite(v)]
        print(f"  金银比 z({k:>3d})={v[-1]:+.2f}σ 历史分位 {(v <= v[-1]).mean()*100:5.1f}% 截至 {j2.index[-1]}")


if __name__ == "__main__":
    main()
