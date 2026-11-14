#!/usr/bin/env python3
"""iter20 — 让"只买评分最靠前 10~20 只"也能跑赢大盘。

诊断（fast.py）：上线分数在**门槛**上有信息量（≥68 的那一篮子 20 日超额 +0.71%，t=3.0），
但在**头部排序**上是负的：每天只买分数前 5%（14 只）超额 −1.06%（t=−4.5），前 10%（29 只）−0.49%。
也就是说分数越高反而越不该买 —— 头部全是"已经涨了一段 + 均线多头 + 斜率最陡"的拥挤票。
本脚本在保持结构门槛的前提下，搜索能改善头部排序的因子组合。

纪律：
  - 只用样本内(<CUTOFF)选参数，并且要求 IS 前半段与后半段都为正；
  - 样本外(>=CUTOFF)只在最后验证一次，不参与挑选；
  - 因子必须有限、有经济含义（不追高、不买波动失控的、量价确认）；
  - 目标 = 前 5% 与前 10% 两个宽度上的 t 值之和（因为 App 的列表就是这两档）。
"""
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import fast as X  # noqa: E402
import portsim as P  # noqa: E402
from model import CUTOFF  # noqa: E402

F = lambda k: P.FAC[k] if k in P.FAC else P.MAT[k]  # noqa: E731
C, M10, M20, M60 = F("close"), F("ma10"), F("ma20"), F("ma60")
SL20, SL60 = F("ma20_slope"), F("ma60_slope")
ATRP, ADX, RSI = F("atr_pct"), F("adx"), F("rsi_app")
VOLR5, OBVS = F("volr5"), F("obv_slope")
RET20, RET60 = F("ret20"), F("ret60")
POS20, BPOS = F("pos20"), F("boll_pos")
PDIMDI = F("pdi") - F("mdi")
TURNOV = F("turnover")


def bnd(x, scale):
    """把因子压到 [-1,1]，缺失当 0（中性），不做任何未来变换。"""
    with np.errstate(all="ignore"):
        return np.nan_to_num(np.clip(x / scale, -1.0, 1.0))


FEAT = {
    "ext20": bnd(C / M20 - 1.0 - 0.02, 0.06),        # 离 MA20 多远（>0 = 追高）
    "ext60": bnd(C / M60 - 1.0 - 0.05, 0.15),        # 离 MA60 多远
    "mom60": bnd(RET60, 0.20),                        # 60 日动量
    "mom20": bnd(RET20, 0.15),                        # 20 日动量（可能反转）
    "pos20": bnd(POS20 - 0.5, 0.5),                   # 在 20 日区间的相对位置
    "boll": bnd(BPOS - 0.5, 0.5),
    "adx": bnd(ADX - 25.0, 15.0),
    "dim": bnd(PDIMDI, 15.0),
    "vol5": bnd(VOLR5 - 1.2, 0.8),                    # 5 日量比（放大=确认）
    "obv": bnd(OBVS, 0.02),                           # OBV 斜率（资金流向）
    "sl60": bnd(SL60, 0.02),                          # 60 日均线斜率
    "sl20": bnd(SL20, 0.02),                          # 20 日均线斜率（已用在结构里）
    "vol": bnd(ATRP - 0.020, 0.020),                  # 波动惩罚的连续版
    "rsi": bnd(RSI - 55.0, 25.0),                     # 超买
    "turn": bnd(np.log(np.where(TURNOV > 0, TURNOV, np.nan)) - 0.3, 0.8),
}

BASE = P.MAT["_S"]["new"].copy()
GRID = [-12.0, -8.0, -5.0, -3.0, 0.0, 3.0, 5.0, 8.0, 12.0]
QS = (0.05, 0.10)


def tsplit(S, q, H=20):
    """返回 (IS前半 t, IS后半 t, 样本外 t)"""
    ds, d = X.daily_topq(S, q, H)
    if len(d) < 40:
        return None
    e = d[:, 0] - d[:, 1]
    dm = np.array(ds)
    ismask = dm < CUTOFF
    isd = np.sort(np.unique(dm[ismask]))
    mid = isd[len(isd) // 2]
    out = []
    for m in (ismask & (dm < mid), ismask & (dm >= mid), ~ismask):
        ee = e[m]
        out.append(ee.mean() / (ee.std(ddof=1) / np.sqrt(max(ee.size, 1))) if ee.size > 10 else np.nan)
    return out


def obj(S, H=20):
    """选参数目标：样本内两半、两个宽度的 t 值都要正，取最小值（max-min 稳健）。"""
    vals = []
    for q in QS:
        ts = tsplit(S, q, H)
        if ts is None:
            return -99
        vals += ts[:2]
    if any(np.isnan(v) for v in vals):
        return -99
    return float(min(vals))


def report(S, label, H=20):
    line = []
    for q in QS:
        ts = tsplit(S, q, H)
        ds, d = X.daily_topq(S, q, H)
        e = d[:, 0] - d[:, 1]
        m = np.array(ds) < CUTOFF
        line.append(f"q{q:.2f}: IS前{ts[0]:+5.2f} IS后{ts[1]:+5.2f} OOS{ts[2]:+5.2f} "
                    f"超{e.mean()*100:+5.2f}%")
    print(f"  {label:<40} " + "  |  ".join(line))


def main():
    print("\n==== 0. 上线分数（基线）====")
    report(BASE, "shipped")
    cur = BASE.copy()
    w = {}
    print("\n==== 1. 贪心搜索（只看样本内两半的 t，最小值最大）====")
    for rnd in range(3):
        best = None
        for name, fct in FEAT.items():
            for g in GRID:
                if g == w.get(name, 0.0):
                    continue
                trial = cur + g * fct
                sc = obj(trial)
                if best is None or sc > best[0]:
                    best = (sc, name, g)
        if best is None or best[0] <= obj(cur):
            print(f"  第 {rnd+1} 轮无改善（最优 {best[0]:+.2f} vs 当前 {obj(cur):+.2f}），停止")
            break
        sc, name, g = best
        w[name] = g
        cur = cur + g * FEAT[name]
        print(f"  第 {rnd+1} 轮加入 {name}={g:+.0f} → 目标 t(min) {sc:+.2f}")
        report(cur, f"  +{name}")
    print("\n==== 2. 最终权重 ====", {k: v for k, v in w.items() if v})
    np.save(os.path.join(HERE, "search_iter20.npy"), cur)
    with open(os.path.join(HERE, "search_iter20.txt"), "w") as fh:
        fh.write(repr(w) + "\n")
    print("\n==== 3. 组合级对照 ====")
    for q in (0.03, 0.05, 0.075, 0.10, 0.15):
        X.score_stats(cur, q, 20, f"搜索后 q={q}")
    print("\n==== 4. 资金曲线（始终满仓，相位平均）====")
    X.compound(BASE, 0.05, 20, "上线分数 top5%(≈14只)")
    X.compound(cur, 0.05, 20, "搜索后 top5%(≈14只)")
    X.compound(cur, 0.10, 20, "搜索后 top10%(≈29只)")
    print(f"\n大盘等权买入持有全程 = {P.bench_hold(0, P.ND)*100:+.1f}%")


if __name__ == "__main__":
    main()
