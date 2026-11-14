#!/usr/bin/env python3
"""Iteration 18 — LAST: how to build the 每日推荐 list from the eligible book.

Facts established: the score>=68 book is worth +0.69% date-equal excess, but the
TOP 10 BY SCORE is worth -1.5%. So ranking a short list by score concentrates the
crowded, extended names. Test selection rules inside the eligible book, using the
config being shipped (knee 2.0%, ATR penalty 200, capped 30, BUY 68).

Reference points: (a) the whole eligible book, (b) 10 names picked at random from
the book — a short list must beat that to earn its place.
"""
import numpy as np
from collections import defaultdict
from model import CUTOFF, _nan, g, stocks, MIN_HIST, COST, H, BASELINE

KNEE, PEN, CAP, BUY = 0.020, 200.0, 30.0, 68.0


def ship(F, t):
    c = F["close"][t]
    ma10, ma20, ma60, sl = g(F, "ma10", t), g(F, "ma20", t), g(F, "ma60", t), g(F, "ma20_slope", t)
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
        s -= min(max(0.0, a - KNEE) * PEN, CAP)
    return float(np.clip(s, 0, 100)), True


rng = np.random.default_rng(7)
day = defaultdict(list)
for F in stocks:
    n = int(F["n"])
    c, o = F["close"], F["open"]
    lim = 0.097 if F["code"].startswith(("30", "68")) else 0.047
    for t in range(MIN_HIST, n - 1):
        s, ok = ship(F, t)
        if not ok or s < BUY:
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
        ma10, ma20 = float(g(F, "ma10", t)), float(g(F, "ma20", t))
        px = float(c[t])
        code = str(F["code"])
        seg = ("kb" if code.startswith("68") else "cy" if code.startswith("30")
               else "sh" if code.startswith("6") else "sz")
        day[d].append(dict(s=s, seg=seg, ex=(c[k] / eo - 1.0) - COST - b,
                           raw=(c[k] / eo - 1.0) - COST,
                           a=float(g(F, "atr_pct", t)),
                           r20=float(g(F, "ret20", t)),
                           sl=float(g(F, "ma20_slope", t)),
                           bias10=px / ma10 - 1.0 if ma10 > 0 else 9.9,
                           bias20=px / ma20 - 1.0 if ma20 > 0 else 9.9,
                           volr=float(g(F, "volr20", t)),
                           pos=float(g(F, "pos55", t))))

def roundrobin(v, cap):
    """score 优先, 但每个板块最多 cap 只 -> 分散风格, 避免同一类拥挤股霸榜"""
    by = {}
    for x in sorted(v, key=lambda y: -y["s"]):
        g = by.setdefault(x["seg"], [])
        if len(g) < cap:
            g.append(x)
    out, i = [], 0
    while True:
        added = False
        for seg in sorted(by):
            if i < len(by[seg]):
                out.append(by[seg][i]); added = True
        if not added:
            break
        i += 1
    return out


def strat(v, k=10):
    """按 score 分位等距取 k 只: 覆盖整个候选池而不是只取头部"""
    v = sorted(v, key=lambda x: -x["s"])
    if len(v) <= k:
        return v
    return [v[int(round(i * (len(v) - 1) / (k - 1)))] for i in range(k)]


RULES = {
    "0 全部合格(参考)": (lambda v: v, None),
    "1 score 高->低": (lambda v: sorted(v, key=lambda x: -x["s"]), 10),
    "2 离MA10最近(回踩)": (lambda v: sorted(v, key=lambda x: abs(x["bias10"])), 10),
    "3 离MA20最近": (lambda v: sorted(v, key=lambda x: abs(x["bias20"])), 10),
    "4 ATR靠近3.3%": (lambda v: sorted(v, key=lambda x: abs(x["a"] - 0.033)), 10),
    "5 ATR靠近2.5%": (lambda v: sorted(v, key=lambda x: abs(x["a"] - 0.025)), 10),
    "6 回踩+score": (lambda v: sorted(v, key=lambda x: abs(x["bias10"]) - 0.01 * x["s"] / 10), 10),
    "7 score&回踩双条件": (lambda v: sorted(v, key=lambda x: (abs(x["bias10"]) > 0.06, -x["s"])), 10),
    "8 随机10只(基准)": (lambda v: list(rng.permutation(v)[:10]), 10),
    "9 随机10只(基准2)": (lambda v: list(rng.permutation(v)[:10]), 10),
    "10 ATR2.5-4%+score": (lambda v: sorted([x for x in v if 0.025 <= x["a"] <= 0.040],
                                           key=lambda x: -x["s"]), 10),
    "11 斜率高&回踩浅": (lambda v: sorted(v, key=lambda x: (-x["sl"], abs(x["bias10"]))), 10),
    "12 68-74zone top10": (lambda v: sorted([x for x in v if x["s"] < 75], key=lambda x: -x["s"]), 10),
    "13 68-74zone top8": (lambda v: sorted([x for x in v if x["s"] < 75], key=lambda x: -x["s"]), 8),
    "14 68-72zone top10": (lambda v: sorted([x for x in v if x["s"] < 72], key=lambda x: -x["s"]), 10),
    "15 68-74 +随机": (lambda v: list(rng.permutation([x for x in v if x["s"] < 75])), 10),
    "16 分层(按score分位取10)": (lambda v: [sorted(v, key=lambda x: -x["s"])[int(i * max(1, len(v) - 1) / 10)]
                                          for i in range(10)] if len(v) >= 10 else v, 10),
    "17 低ATR的10只(防御)": (lambda v: sorted(v, key=lambda x: x["a"]), 10),
    "18 高ATR的10只(进攻)": (lambda v: sorted(v, key=lambda x: -x["a"]), 10),
    "19 板块均衡(每组<=3)": (lambda v: roundrobin(v, 3), 10),
    "20 板块均衡(每组<=2)": (lambda v: roundrobin(v, 2), 10),
    "21 板块均衡(每组<=4)": (lambda v: roundrobin(v, 4), 10),
    "22 板块均衡+分层混合": (lambda v: strat(roundrobin(v, 3)), 10),
}

dates = sorted(day)
h1 = set(dates[:len(dates) // 2])
print(f"{'rule':20s} {'dates':>5s} {'n/d':>5s} {'d_exc':>7s} {'h1':>7s} {'h2':>7s} "
      f"{'oos_d':>6s} {'oos_exc':>7s} {'posM%':>5s} {'worstMo':>7s} {'p05':>7s} {'>10L':>5s}")
for lab, (fn, topn) in RULES.items():
    per, e1, e2, ooper, raws = [], [], [], [], []
    mo = defaultdict(list)
    cnt, ond = [], []
    for d, v in day.items():
        picks = fn(v)
        if topn:
            picks = picks[:topn]
        if len(picks) < 3:
            continue
        cnt.append(len(picks))
        m = float(np.mean([x["ex"] for x in picks]))
        per.append(m)
        (e1 if d in h1 else e2).append(m)
        raws.extend([x["raw"] for x in picks])
        mo[d[:7]].append(m)
        if d >= CUTOFF:
            ooper.append(m)
            ond.append(len(picks))
    p = np.array(per)
    mp = np.array([np.mean(v) for v in mo.values()])
    R = np.array(raws)
    print(f"{lab:20s} {len(p):>5d} {np.mean(cnt):>5.1f} {p.mean()*100:>+6.2f}% "
          f"{np.mean(e1)*100:>+6.2f}% {np.mean(e2)*100:>+6.2f}% {len(ooper):>6d} "
          f"{np.mean(ooper)*100:>+6.2f}% {(mp>0).mean()*100:>4.0f}% {mp.min()*100:>+6.2f}% "
          f"{np.percentile(R,5)*100:>+6.1f}% {(R<-0.10).mean()*100:>4.0f}%")
print(f"\neligible stock-days={sum(len(v) for v in day.values())}, "
      f"dates with >=3 picks={len([1 for v in day.values() if len(v)>=3])}, "
      f"平均每日合格数={np.mean([len(v) for v in day.values() if len(v)>=3]):.1f}")
