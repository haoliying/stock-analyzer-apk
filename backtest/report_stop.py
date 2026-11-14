#!/usr/bin/env python3
"""按 App 给出的止损价（2xATR，且不高于现价98%）真实执行，会改变什么？

前面所有回测都没建模型止损（只看20日到期收益）。这里用日线 high/low 近似：
持有期内若当天 open <= 止损价 -> 按 open 成交（跳空穿越，滑点如实计入）；
若 low <= 止损价 -> 按止损价成交。没触发就持满 20 个交易日。
成本同样 0.12% 往返。
"""
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from final_validate import new_fn  # noqa: E402
from model import CUTOFF, COST, MIN_HIST, stocks, _nan, g  # noqa: E402

BUY, H = 68.0, 20


def stop_level(F, t):
    """镜像 Kotlin stopLoss(): price-2*ATR, 用 MA20*0.995 抬高, 夹在 [0.80, 0.98]*price"""
    c, a, m20 = F["close"][t], g(F, "atr", t), g(F, "ma20", t)
    if _nan(a) or a <= 0:
        return None
    s = c - 2 * a
    if not _nan(m20) and m20 < c:
        s = max(s, m20 * 0.995)
    return float(min(c * 0.98, max(c * 0.80, s)))


def run():
    rows = []
    for F in stocks:
        n = int(F["n"])
        o, c, lo = F["open"], F["close"], F["low"]
        lim = 0.097 if str(F["code"]).startswith(("30", "68")) else 0.047
        for t in range(MIN_HIST, n - 1):
            s, ok = new_fn(F, t)
            if not ok or s < BUY:
                continue
            e = o[t + 1]
            if e <= 0 or c[t] <= 0 or t + 1 + H >= n:
                continue
            if F["pct"][t] > 0 and e >= c[t] * (1 + lim) * 0.995:
                continue  # 开盘涨停买不到
            d = str(F["date"][t])
            hold = (c[t + 1 + H] / e - 1.0) - COST
            row = {"date": d, "hold": hold, "stop": np.nan, "days": H + 1, "hit": False}
            S = stop_level(F, t)
            if S is not None:
                for k in range(t + 1, t + 2 + H):  # 到期口径与 hold 一致（收盘 = t+1+H）
                    if o[k] <= S:
                        row.update(stop=o[k] / e - 1.0 - COST, days=k - t, hit=True)
                        break
                    if lo[k] <= S:
                        row.update(stop=S / e - 1.0 - COST, days=k - t, hit=True)
                        break
                if not row["hit"]:
                    row["stop"] = hold  # 持满到期，止损未触发
            rows.append(row)
    return rows


def show(a, label):
    a = a[~np.isnan(a)]
    if a.size == 0:
        print(f"    {label:<20} 无样本")
        return
    print(f"    {label:<20} n={a.size:<6d} 均值 {a.mean()*100:+5.2f}%  中位 {np.median(a)*100:+5.2f}%  "
          f"胜率 {(a>0).mean()*100:4.1f}%  p5 {np.percentile(a,5)*100:+5.1f}%  p25 {np.percentile(a,25)*100:+5.1f}%  "
          f"最差 {a.min()*100:+6.1f}%  亏>10% {(a<-0.10).mean()*100:4.1f}%  亏>20% {(a<-0.20).mean()*100:4.1f}%")


def basket(vals_by_day):
    return np.array([np.mean(x) for k, x in sorted(vals_by_day.items()) if x])


def by_day(rows, key, since=None):
    d = {}
    for r in rows:
        if since and r["date"] < since:
            continue
        v = r[key]
        if not np.isnan(v):
            d.setdefault(r["date"], []).append(v)
    return d


def main():
    rows = run()
    hit = np.array([r["hit"] for r in rows])
    print(f"\n样本 {len(rows)} 笔（新引擎 68 分以上、可买入）。止损触发比例 {hit.mean()*100:.1f}%")
    for tag, sel in (("全程", np.ones(len(rows), bool)),
                     ("样本外", np.array([r["date"] >= CUTOFF for r in rows]))):
        print(f"\n  —— {tag} ——")
        arr = lambda k: np.array([r[k] for r in rows])[sel]  # noqa: E731
        show(arr("hold"), "不止损(持满20日)")
        show(arr("stop"), "执行2xATR止损")
        h = hit[sel]
        st, hd = arr("stop"), arr("hold")
        dy = np.array([r["days"] for r in rows])[sel][h]
        print(f"    被止损的 {h.mean()*100:4.1f}%: 实际平均 {np.nanmean(st[h])*100:+5.2f}%、平均持仓 {dy.mean():4.1f} 天；"
              f"这批若不止损、持满20日平均 {np.nanmean(hd[h])*100:+5.2f}%"
              f" =>  止损平均多花 {np.nanmean(hd[h]-st[h])*100:+5.2f}%（买保险的钱），换来尾部封顶")
        print(f"    没止损的单子: 平均 {np.nanmean(hd[~h])*100:+5.2f}%  胜率 {(hd[~h]>0).mean()*100:4.1f}%")
        for key, lab in (("hold", "不止损篮子"), ("stop", "止损篮子")):
            bk = basket(by_day([r for r, s in zip(rows, sel) if s], key))
            if bk.size:
                print(f"    {lab:<12} 交易日 {bk.size:>4} 天  均值 {bk.mean()*100:+5.2f}%  中位 {np.median(bk)*100:+5.2f}%  "
                      f"赚钱天数 {(bk>0).mean()*100:4.1f}%  最差 {bk.min()*100:+5.1f}%  "
                      f"亏>5%天数 {(bk<-0.05).mean()*100:4.1f}%")


if __name__ == "__main__":
    main()
