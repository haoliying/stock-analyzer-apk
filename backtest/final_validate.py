#!/usr/bin/env python3
"""FINAL VALIDATION — old app vs the config being shipped.

Shipped config: trend-structure score + volatility penalty (knee 2.0% ATR/day,
200 pts per point of excess), 加仓 line 68, 减仓 line 42.

Everything is measured as: signal on close(t) -> buy at open(t+1) -> hold 20
trading sessions, net of 0.12% cost, EXCESS vs the same-day universe mean,
date-equal-weighted, split into in-sample / out-of-sample (cutoff 2026-04-23).
"""
import numpy as np
from collections import defaultdict
from model import CUTOFF, BASELINE, _nan, g, stocks, MIN_HIST, COST, H
from strategy import v_old

KNEE, PEN, BUY, SELL = 0.020, 200.0, 68.0, 42.0


def new_fn(F, t):
    c = F["close"][t]
    ma10, ma20, ma60, sl = g(F, "ma10", t), g(F, "ma20", t), g(F, "ma60", t), g(F, "ma20_slope", t)
    if _nan(ma20) or _nan(ma60) or _nan(sl) or _nan(ma10):
        return 0.0, False
    s = 50.0
    up = c > ma20 > ma60
    dn = c < ma20 < ma60
    if up:
        s += 22 if sl > 0.015 else (14 if sl > 0.0 else 6)
    elif dn:
        s -= 22 if sl < -0.015 else (14 if sl < 0.0 else 6)
    else:
        s -= 3
    s += 4 if c > ma10 > ma20 else -2
    a = g(F, "atr_pct", t)
    if not _nan(a):
        s -= max(0.0, a - KNEE) * PEN
    return float(np.clip(s, 0, 100)), True


def old_fn(F, t):
    sc, ok = v_old(F, t)
    return float(sc), bool(ok)


def build(fn):
    rows = []
    for F in stocks:
        n = int(F["n"])
        c, o = F["close"], F["open"]
        lim = 0.097 if F["code"].startswith(("30", "68")) else 0.047
        for t in range(MIN_HIST, n - 1):
            s, ok = fn(F, t)
            if not ok:
                continue
            k = t + 1 + H
            eo = o[t + 1]
            if k >= n or eo <= 0:
                continue
            d = str(F["date"][t])
            b = BASELINE.get((d, H))
            if b is None:
                continue
            raw = (c[k] / eo - 1.0) - COST
            rows.append((s, d, raw, raw - b,
                         F["pct"][t] > 0 and eo >= c[t] * (1 + lim) * 0.995))
    return rows


def summarize(rows, buy_th, sell_th, name):
    tot = len(rows)
    nb = sum(1 for x in rows if x[0] >= buy_th)
    ns = sum(1 for x in rows if x[0] <= sell_th)
    bd = defaultdict(list)
    sd = defaultdict(list)
    for s, d, raw, exc, unb in rows:
        if s >= buy_th and not unb:
            bd[d].append((raw, exc))
        elif s <= sell_th:
            sd[d].append(exc)
    per = [np.mean([x[1] for x in v]) for v in bd.values() if len(v) >= 3]
    per_s = [np.mean(v) for v in sd.values() if len(v) >= 3]
    dates = sorted({x[1] for x in rows})
    h1 = set(dates[:len(dates) // 2])
    p1 = [np.mean([x[1] for x in v]) for d, v in bd.items() if len(v) >= 3 and d in h1]
    p2 = [np.mean([x[1] for x in v]) for d, v in bd.items() if len(v) >= 3 and d not in h1]
    oos = [x for d, v in bd.items() if d >= CUTOFF for x in v]
    mo = defaultdict(list)
    for d, v in bd.items():
        if len(v) >= 3:
            mo[d[:7]].append(np.mean([x[1] for x in v]))
    mp = np.array([np.mean(v) for v in mo.values()])
    print(f"\n### {name}   (加仓 score>={buy_th:.0f}, 减仓<={sell_th:.0f})")
    print(f"  发声频率: 加仓 {nb/tot*100:4.1f}% | 减仓 {ns/tot*100:4.1f}% | "
          f"合计 {(nb+ns)/tot*100:4.1f}% of stock-days")
    if len(per) >= 20:
        R = np.array([x[0] for x in oos]) if oos else np.array([np.nan])
        print(f"  加仓后20日 超额: 全程 {np.mean(per)*100:+5.2f}%  "
              f"前半 {np.mean(p1)*100:+5.2f}%  后半 {np.mean(p2)*100:+5.2f}%  "
              f"样本外 {np.mean([x[1] for x in oos])*100:+5.2f}%")
        print(f"  加仓实盘分布(样本外): 均值 {R.mean()*100:+5.2f}%  胜率 {(R>0).mean()*100:4.1f}%  "
              f"5%分位 {np.percentile(R,5)*100:+5.1f}%  25%分位 {np.percentile(R,25)*100:+5.1f}%  "
              f"亏>10%比例 {(R<-0.10).mean()*100:4.1f}%  最差 {R.min()*100:+5.1f}%")
        print(f"  月度稳定性: {len(mp)}个月  跑赢当月基准 {np.mean(mp>0)*100:4.0f}%  "
              f"最差月 {mp.min()*100:+5.2f}%  中位月 {np.median(mp)*100:+5.2f}%")
        nd = np.mean([len(v) for v in bd.values() if len(v) >= 3])
        print(f"  每日平均候选数: {nd:.1f} 只 (共{len(stocks)}只)")
    else:
        print("  加仓样本过少")
    if per_s:
        print(f"  减仓后20日 超额: {np.mean(per_s)*100:+5.2f}% (应为负=判断正确)")


def main():
    summarize(build(new_fn), BUY, SELL, "NEW 趋势结构+波动惩罚 (v4.1.0 上线)")
    summarize(build(old_fn), 60, 40, "OLD App 原算法 (v4.0.1)")

    print("\n=== 分档单调性 (date-equal excess, 20d) ===")
    rows = build(new_fn)
    for lo, hi, lab in ((0, 30, "强烈减仓 <=29"), (30, 42, "建议减仓 30-41"), (42, 55, "观望 42-54"),
                        (55, 68, "持有 55-67"), (68, 76, "建议加仓 68-75"), (76, 101, "强烈加仓 >=76")):
        bd = defaultdict(list)
        for s, d, raw, exc, unb in rows:
            if lo <= s < hi and not unb:
                bd[d].append(exc)
        per = [np.mean(v) for v in bd.values() if len(v) >= 3]
        n = sum(len(v) for v in bd.values())
        if len(per) < 15:
            print(f"  {lab:18s} n={n:<6d} (样本不足)")
            continue
        print(f"  {lab:18s} n={n:<6d} dates={len(per):<4d} excess={np.mean(per)*100:+5.2f}%")


if __name__ == "__main__":
    main()
