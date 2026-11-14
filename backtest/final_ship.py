#!/usr/bin/env python3
"""FINAL SHIPPED MODEL — this file is the spec that gets ported to Kotlin.

score = 50
      + trend structure (close vs MA20 vs MA60, graded by MA20 10-day slope)
      + MA10 alignment
      - volatility penalty, capped:  min(24, max(0, ATR%/14d - 2.0%) * 300)
advice: 买入>=66 持有43..65 卖出<=42 强烈卖出<=30  (强烈买入 tier decided below)

Validation protocol: signal at close(t), entry at open(t+1), 20 trading sessions,
net of 0.12% cost, excess vs same-day universe mean, date-equal weighted,
out-of-sample = dates >= 2026-04-23.
"""
import numpy as np
from collections import defaultdict
from model import CUTOFF, _nan, g, stocks, MIN_HIST, COST, H, BASELINE
from strategy import v_old

ATR_KNEE, ATR_PEN, ATR_CAP, ATR_GATE = 0.020, 300.0, 24.0, 0.045
BUY, STRONG_BUY, SELL, STRONG_SELL = 66.0, 75.0, 42.0, 30.0


def ship(F, t):
    c = F["close"][t]
    ma10, ma20, ma60 = g(F, "ma10", t), g(F, "ma20", t), g(F, "ma60", t)
    sl = g(F, "ma20_slope", t)
    if _nan(ma20) or _nan(ma60) or _nan(ma10) or _nan(sl):
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
        s -= min(max(0.0, a - ATR_KNEE) * ATR_PEN, ATR_CAP)
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
    bd, sd = defaultdict(list), defaultdict(list)
    for s, d, raw, exc, unb in rows:
        if s >= buy_th and not unb:
            bd[d].append((raw, exc))
        elif s <= sell_th:
            sd[d].append(exc)
    dates = sorted({x[1] for x in rows})
    h1 = set(dates[:len(dates) // 2])
    per, e1, e2 = [], [], []
    for d, v in bd.items():
        if len(v) < 3:
            continue
        m = np.mean([x[1] for x in v])
        per.append(m)
        (e1 if d in h1 else e2).append(m)
    oo = [x for d, v in bd.items() if d >= CUTOFF for x in v]
    R = np.array([x[0] for x in oo])
    OE = np.array([x[1] for x in oo])
    mo = defaultdict(list)
    for d, v in bd.items():
        if len(v) >= 3:
            mo[d[:7]].append(np.mean([x[1] for x in v]))
    mp = np.array([np.mean(v) for v in mo.values()])
    sells = [np.mean(v) for v in sd.values() if len(v) >= 3]
    print(f"\n### {name}  (加仓>={buy_th:.0f} 减仓<={sell_th:.0f})")
    print(f"  发声率: 加仓 {nb/tot*100:4.1f}% / 减仓 {ns/tot*100:4.1f}% / 合计 {(nb+ns)/tot*100:4.1f}%"
          f"   每日候选 {np.mean([len(v) for v in bd.values() if len(v)>=3]):.1f} 只")
    print(f"  加仓超额(日期等权): 全程 {np.mean(per)*100:+.2f}%  前半 {np.mean(e1)*100:+.2f}%  "
          f"后半 {np.mean(e2)*100:+.2f}%  样本外 {OE.mean()*100:+.2f}%")
    print(f"  样本外单笔20日: 均值 {R.mean()*100:+.2f}% 胜率 {(R>0).mean()*100:.1f}% "
          f"p05 {np.percentile(R,5)*100:+.1f}% p25 {np.percentile(R,25)*100:+.1f}% "
          f"亏>10% {(R<-0.10).mean()*100:.1f}% 亏>20% {(R<-0.20).mean()*100:.1f}% 最差 {R.min()*100:+.1f}%")
    print(f"  月度: {len(mp)}月 跑赢基准 {(mp>0).mean()*100:.0f}% 中位 {np.median(mp)*100:+.2f}% "
          f"最差 {mp.min()*100:+.2f}%")
    if sells:
        print(f"  减仓超额 {np.mean(sells)*100:+.2f}% (负=判断正确)")


new_rows = build(ship)
old_rows = build(old_fn)
summarize(new_rows, BUY, SELL, "NEW 上线模型")
summarize(old_rows, 60, 40, "OLD v4.0.1 模型")

print("\n=== NEW 分档 (date-equal excess) ===")
for lo, hi, lab in ((0, 30, "强烈卖出 <=29"), (30, 43, "卖出 30-42"), (43, 58, "持有 43-57"),
                    (58, 66, "观望 58-65"), (66, 75, "买入 66-74"), (75, 101, "强烈买入 >=75")):
    bd = defaultdict(list)
    for s, d, raw, exc, unb in new_rows:
        if lo <= s < hi and not unb:
            bd[d].append(exc)
    per = [np.mean(v) for v in bd.values() if len(v) >= 3]
    n = sum(len(v) for v in bd.values())
    if len(per) < 15:
        print(f"  {lab:16s} n={n:<6d} 样本不足")
        continue
    print(f"  {lab:16s} n={n:<6d} dates={len(per):<4d} excess={np.mean(per)*100:+5.2f}%")

print("\n=== 是否保留'强烈买入'档? 看 >=70 区间内部按 ATR 细分 ===")
atr_at = {}
for F in stocks:
    n = int(F["n"])
    c, o = F["close"], F["open"]
    lim = 0.097 if F["code"].startswith(("30", "68")) else 0.047
    for t in range(MIN_HIST, n - 1):
        s, ok = ship(F, t)
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
        if s >= 70 and not (F["pct"][t] > 0 and eo >= c[t] * (1 + lim) * 0.995):
            atr_at.setdefault(d, []).append((s, raw - b, float(g(F, "atr_pct", t))))
for lo, hi, lab in ((0.0, 0.020, "ATR<=2.0%"), (0.020, 0.030, "2.0-3.0%"),
                    (0.030, 0.045, "3.0-4.5%"), (0.045, 9.9, ">4.5%")):
    per = [np.mean([e for s, e, a in v if lo <= a < hi]) for d, v in atr_at.items()
           if len([1 for s, e, a in v if lo <= a < hi]) >= 3]
    n = sum(len([1 for s, e, a in v if lo <= a < hi]) for v in atr_at.values())
    if len(per) >= 20:
        print(f"  {lab:10s} n={n:<6d} dates={len(per):<4d} excess={np.mean(per)*100:+5.2f}%")
    else:
        print(f"  {lab:10s} n={n:<6d} 样本不足")

print("\n=== 每日推荐榜单测试: 按 score 排序 vs 按 '不追高' rank_score 排序 ===")
# rank_score demotes the over-extended top zone (measured: >=76 loses, 68-74 wins)
def rank_of(s, atr):
    return s - max(0.0, s - 75.0) * 1.5


day = defaultdict(list)
for F in stocks:
    n = int(F["n"])
    c, o = F["close"], F["open"]
    lim = 0.097 if F["code"].startswith(("30", "68")) else 0.047
    for t in range(MIN_HIST, n - 1):
        s, ok = ship(F, t)
        if not ok:
            continue
        k = t + 1 + H
        eo = o[t + 1]
        if k >= n or eo <= 0:
            continue
        if F["pct"][t] > 0 and eo >= c[t] * (1 + lim) * 0.995:
            continue
        d = str(F["date"][t])
        b = BASELINE.get((d, H))
        if b is None:
            continue
        day[d].append((s, rank_of(s, 0), (c[k] / eo - 1.0) - COST - b, float(g(F, "atr_pct", t))))

for TOPN in (10, 20):
    for key, lab in ((0, "按 score 排序"), (1, "按 rank_score 排序(不追高)")):
        per = []
        for d, v in day.items():
            v = sorted(v, key=lambda x: -x[key])[:TOPN]
            if len(v) >= 3:
                per.append(np.mean([x[2] for x in v]))
        p = np.array(per)
        oos = [np.mean([x[2] for x in sorted(v, key=lambda x: -x[key])[:TOPN]])
               for d, v in day.items() if d >= CUTOFF and len(v) >= 3]
        print(f"  top{TOPN:<3d} {lab:24s} dates={len(p):4d} d_exc={p.mean()*100:+5.2f}% "
              f"oos={np.mean(oos)*100:+5.2f}% pos%={(p>0).mean()*100:4.0f}% worst={p.min()*100:+5.1f}%")
