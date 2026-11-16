#!/usr/bin/env python3
"""metals_iter3 — 把 iter2 的"择时全为负"钉死：同一套协议跑 A 股对照 + 消融。

iter2 的结果是难看的：score>=68 才持有，在 13 个贵金属报价上 13/13 跑输同一标的
一直持有，阈值从 55 扫到 80 没有一档为正。在看懂这个结论之前必须先排除三种可能：

  (A) 协议太苛刻 —— 如果同样的非重叠择时协议在 A 股上也全为负，那是协议的锅，
      不能说评分在贵金属上没用。所以这里用**同一份代码**跑 /data/backtest/cache
      的 288 只 A 股（2024-07..2026-11），并把贵金属截到同一时间窗，做同时段对照。
  (B) 不是评分的问题，是"任何趋势择时在持续上涨的资产上都吃亏" —— 那就再加一组
      消融：均线之上持有、20 日动量持有、低 ATR 持有、以及出厂分数的各档阈值，
      在贵金属上一律用同一协议比。
  (C) 分数也许不 predicting 收益，但能 controlling 风险 —— iter2 的[5] 显示分层仓位
      把 maxDD 从 -44.8% 压到 -25.6%（13 个里 12 个更浅），代价是 CAGR 从 8.9% 到 5.2%。
      这里把"只减不清仓"的温和版跑出来，看风险收益换得值不值。

评分/阈值/成本/130 根窗口 = 出厂口径，一字未改。凡是扫出来的参数都标"探索性，不作数"。
"""
import glob
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.dirname(HERE))
from metals_iter1 import BUY, COST, MIN_HIST, PRIMARY_H, SELL, STRONG_SELL, boot_p, hac_t, load, score_at  # noqa: E402
from metals_iter2 import CONTROL_HI, CONTROL_LO, REPRESENTATIVE, daily_strategy, nonoverlap, perf  # noqa: E402

DATA_A = "/data/backtest/cache"


def ashare_frames():
    """A 股缓存 -> 与贵金属 loader 同字段的 F 列表（指标缓存里已算好）。"""
    out = []
    for p in sorted(glob.glob(os.path.join(DATA_A, "*.npz"))):
        z = np.load(p, allow_pickle=True)
        out.append({"name": str(z["code"]), "n": int(z["n"]), "date": z["date"].astype(str),
                    "open": z["open"].astype(float), "close": z["close"].astype(float),
                    "ma20": z["ma20"], "ma60": z["ma60"], "ma10": z["ma10"],
                    "ma20_slope": z["ma20_slope"], "atr_pct": z["atr_pct"]})
    return out


def rule_table(F, t, extra):
    """各种"持有条件"，返回 name -> bool。"""
    s, ok = score_at(F, t)
    c = F["close"][t]
    ma20, ma60 = F["ma20"][t], F["ma60"][t]
    a = F["atr_pct"][t]
    r20 = c / F["close"][t - 20] - 1.0 if t >= 20 else np.nan
    out = {
        "score>=68(出厂)": ok and s >= BUY,
        "score>42(出厂)": ok and s > SELL,
        "score>=50": ok and s >= 50.0,
        "score>=55": ok and s >= 55.0,
        "score>=60": ok and s >= 60.0,
        "score>=76(分数上限)": ok and s >= 76.0,
        "close>ma20": ma20 == ma20 and c > ma20,
        "close>ma60": ma60 == ma60 and c > ma60,
        "ret20>0(20日动量)": r20 == r20 and r20 > 0,
        "ATR%<=2%(低波动档)": a == a and a <= 0.020,
    }
    for k, v in extra.items():
        out[k] = v(F, t, s, ok)
    return out


def timing_scan(fs, label, lo=None, hi=None, step=PRIMARY_H, cost=COST):
    """对每个标的、每条规则：每笔超额 = mean(w*g) - mean(g)，以及在场占比。"""
    res = {}
    for F in fs:
        n, c, o, d = int(F["n"]), F["close"], F["open"], F["date"]
        acc = {}
        for t in range(MIN_HIST, n - 1 - step, step):
            ds = str(d[t])
            if lo is not None and not (lo <= ds < hi):
                continue
            if o[t + 1] <= 0:
                continue
            g = c[t + 1 + step] / o[t + 1] - 1.0 - cost
            rl = rule_table(F, t, {})
            for k, on in rl.items():
                a = acc.setdefault(k, [[], []])
                a[0].append(g if on else 0.0)
                a[1].append(g)
        for k, (st, ag) in acc.items():
            if len(ag) < 15:
                continue
            st, ag = np.array(st), np.array(ag)
            res.setdefault(k, []).append({"name": F["name"], "n": len(ag),
                                          "ex": st.mean() - ag.mean(),
                                          "on": np.mean(st != 0),
                                          "t": hac_t(st - ag, 1)[0],
                                          "p": boot_p(st - ag, 1)})
    print(f"\n  [{label}] 每条规则在各标的上的每笔超额（标的等权；excess>0 的标的占比）")
    print(f"    {'规则':<20}{'标的数':>7}{'超额中位':>10}{'超额均值':>10}{'>0':>7}"
          f"{'在场占比中位':>12}{'标的内t的中位':>13}")
    for k, rows in sorted(res.items(), key=lambda kv: -np.median([r["ex"] for r in kv[1]])):
        e = np.array([r["ex"] for r in rows])
        onm = np.median([r["on"] for r in rows])
        tm = np.median([r["t"] for r in rows])
        print(f"    {k:<20}{len(rows):>7}{np.median(e)*100:>+9.2f}%{e.mean()*100:>+9.2f}%"
              f"{(e > 0).sum():>4}/{len(e)}{onm*100:>11.0f}%{tm:>13.2f}")
    return res


def main():
    fs = [load(p) for p in sorted(glob.glob(os.path.join(HERE, "metals_data", "*.json")))]
    fa = ashare_frames()
    print("=" * 104)
    print("metals_iter3  A股同协议对照 + 规则消融 + 风险版仓位")
    print(f"贵金属报价 {len(fs)} 个；A股 {len(fa)} 只；出厂口径成本 {COST*100:.2f}%/边，持有 {PRIMARY_H} 日")
    print("=" * 104)

    # ---------- (1) 同一协议、同一时间窗：A股 vs 贵金属 ----------
    win = (CONTROL_LO, CONTROL_HI)
    print(f"\n[1] 同时段对照（{win[0]}..{win[1]}，同一份代码、同一步长 20、同一成本）")
    fa_same = [F for F in fa if len(np.nonzero((np.asarray(F["date"], str) >= win[0]) &
                                               (np.asarray(F["date"], str) < win[1]))[0]) > 0]
    # A 股缓存本身就是这个窗口，直接全用；贵金属截同窗（从头开始保留历史以保指标）
    res_a = timing_scan(fa_same, "A股 288 只（缓存全窗口 2024-07..2026-11）")
    res_m = timing_scan(fs, f"贵金属（同期 {win[0]}..{win[1]}）", lo=win[0], hi=win[1])
    res_mf = timing_scan(fs, "贵金属（全历史 2007..2026）")

    print("\n  同一把尺子，两个市场：")
    print(f"    {'规则':<20}{'A股超额中位':>13}{'A股>0':>9}{'贵金属超额中位':>15}{'贵金属>0':>10}")
    for k in ["score>=68(出厂)", "score>42(出厂)", "close>ma20", "ret20>0(20日动量)"]:
        ea = np.array([r["ex"] for r in res_a.get(k, [])])
        em = np.array([r["ex"] for r in res_m.get(k, [])])
        print(f"    {k:<20}{np.median(ea)*100:>+12.2f}%{(ea > 0).sum():>4}/{len(ea):<4}"
              f"{np.median(em)*100:>+14.2f}%{(em > 0).sum():>4}/{len(em):<4}")

    # ---------- (2) 温和版仓位：只减仓、不清仓 ----------
    print("\n[2] 仓位变体（日频、含换手成本；'风险换收益'看 Sharpe 与 maxDD）")
    variants = {
        "一直持有（基准）": lambda s: 1.0,
        "出厂四档 1/0.5/0.2/0": lambda s: (1.0 if s >= BUY else 0.5 if s > SELL else 0.2 if s > STRONG_SELL else 0.0),
        "只减不清 1/0.7/0.4/0.2": lambda s: (1.0 if s >= BUY else 0.7 if s > SELL else 0.4 if s > STRONG_SELL else 0.2),
        "只在<=42减半 1/1/0.5/0.5": lambda s: (1.0 if s > SELL else 0.5),
        "只在<=30减半 1/1/1/0.5": lambda s: (1.0 if s > STRONG_SELL else 0.5),
    }
    print(f"  {'变体':<26}{'CAGR中位':>10}{'Sharpe中位':>11}{'波动中位':>10}{'maxDD中位':>11}"
          f"{'ΔCAGR vs持有':>14}{'ΔmaxDD vs持有':>14}{'Sharpe更好':>11}")
    for vname, wof in variants.items():
        rows = []
        for F in fs:
            sr, br, to = daily_strategy(F, wof, COST)
            if len(sr) < 120:
                continue
            ps, pb = perf(sr), perf(br)
            rows.append((ps, pb))
        if not rows:
            continue
        cs = np.array([r[0]["cagr"] for r in rows]); cb = np.array([r[1]["cagr"] for r in rows])
        ss = np.array([r[0]["sharpe"] for r in rows]); sb = np.array([r[1]["sharpe"] for r in rows])
        vs = np.array([r[0]["vol"] for r in rows]); ds = np.array([r[0]["maxdd"] for r in rows])
        db = np.array([r[1]["maxdd"] for r in rows])
        print(f"  {vname:<26}{np.median(cs)*100:>9.1f}%{np.median(ss):>11.2f}{np.median(vs)*100:>9.1f}%"
              f"{np.median(ds)*100:>10.1f}%{np.median(cs-cb)*100:>+13.1f}pp"
              f"{np.median(ds-db)*100:>+13.1f}pp{(ss > sb).sum():>6}/{len(rows)}")

    # 同样五个变体在 A 股上的对照（只取缓存里最长的 40 只，避免又慢又重复）
    fa40 = sorted(fa, key=lambda F: -int(F["n"]))[:40]
    print("\n  A 股对照（缓存里历史最长的 40 只，同一变体表）：")
    print(f"  {'变体':<26}{'CAGR中位':>10}{'Sharpe中位':>11}{'波动中位':>10}{'maxDD中位':>11}")
    for vname, wof in variants.items():
        rows = []
        for F in fa40:
            sr, br, to = daily_strategy(F, wof, COST)
            if len(sr) < 120:
                continue
            ps, pb = perf(sr), perf(br)
            rows.append((ps, pb))
        if not rows:
            continue
        print(f"  {vname:<26}{np.median([r[0]['cagr'] for r in rows])*100:>9.1f}%"
              f"{np.median([r[0]['sharpe'] for r in rows]):>11.2f}"
              f"{np.median([r[0]['vol'] for r in rows])*100:>9.1f}%"
              f"{np.median([r[0]['maxdd'] for r in rows])*100:>10.1f}%")

    # ---------- (3) 阈值/规则的块自助显著性（挑最"好看"的一条，正式检验） ----------
    print("\n[3] 正式检验：贵金属全历史、>=68 才持有，把 13 个报价的每笔超额并起来做自助"
          "（标的等权；注意同一资产多报价=重复计数，所以同时给只看 4 个独立资产的结果）")
    for lab, subset in [("13 个报价", fs), ("4 个独立资产", [F for F in fs if F["name"] in REPRESENTATIVE])]:
        es = []
        for F in subset:
            st, ag = nonoverlap(F, lambda s: 1.0 if s >= BUY else 0.0, PRIMARY_H, COST)
            if len(st) >= 30:
                es.append(st.mean() - ag.mean())
        e = np.array(es)
        rng = np.random.default_rng(11)
        draws = np.array([rng.choice(e, len(e), replace=True).mean() for _ in range(20000)])
        p = float((draws >= 0).mean())
        print(f"    {lab:<12} 标的 {len(e):>2}  超额均值 {e.mean()*100:+.2f}%  "
              f"自助 H0(超额>=0) 的 p ≈ {p:.4f}  95%CI [{np.percentile(draws,2.5)*100:+.2f}%, "
              f"{np.percentile(draws,97.5)*100:+.2f}%]")

    # ---------- (4) 为什么负：在场时买入价相对后期均价的位置 ----------
    print("\n[4] 机制检查：score>=68 时建仓，建在什么位置（相对其后 20 日）")
    for F in fs:
        n, c, o = int(F["n"]), F["close"], F["open"]
        pos, hits = [], 0
        for t in range(MIN_HIST, n - 1 - PRIMARY_H, PRIMARY_H):
            s, ok = score_at(F, t)
            if not ok or s < BUY or o[t + 1] <= 0:
                continue
            k = t + 1 + PRIMARY_H
            entry = o[t + 1]
            window = c[t + 1:k + 1]
            pos.append(entry / np.mean(window) - 1.0)
            hits += 1
        if hits < 30:
            continue
        p = np.array(pos)
        print(f"    {F['name']:<14} 建仓价高于其后20日均价 {100*(p > 0).mean():>5.1f}%  "
              f"高出中位 {np.median(p)*100:>+5.2f}%  (n={hits})")

    print("\n  说明：[1]/[2] 用的是同一段代码、同一成本、同一持有期；"
          "[4] 是对'为什么择时吃亏'的直接检查，不是收益预测。")


if __name__ == "__main__":
    main()
