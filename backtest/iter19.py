#!/usr/bin/env python3
"""iter19 — 目标：在"真实资金曲线"上跑赢大盘，同时不把尾部搞坏。

基准两个，都要看：
  基准B = 全市场等权、同样每 H 天换仓（剔除选股之外的差异，纯看 alpha）
  大盘A = 全市场等权、每日买入持有（用户说的"大盘"，也是散户真能买到的东西）
指标：累计、年化、最大回撤、最差单期、持仓率、超大盘 pp、期超额、跑赢期比例。
全程 / 样本内(<CUTOFF) / 样本外(>=CUTOFF) 三段分别看，选参数只看样本内+全程，样本外只验证一次。
"""
import sys

import numpy as np

sys.path.insert(0, "/data/backtest")
import portsim as P  # noqa: E402

BASE = dict(score="new", th=68.0, K=10, mode="strat", H=20)


def run(cfg, label):
    out = {}
    for tag, kw in (("full", {}), ("is", {"until": P.CUTOFF}), ("oos", {"since": P.CUTOFF})):
        out[tag] = P.stats(cfg, f"{label} [{tag}]", **kw)
    return out


def table(res):
    return " | ".join(f"{k}: 累{v['cum']*100:+6.1f} 超盘{v['vs_bh']*100:+6.1f}pp 回撤{v['dd']*100:5.1f} "
                      f"超额{v['exc']*100:+5.2f}" for k, v in res.items())


def main():
    cfgs = [
        ("上线 68·分位取10", dict(BASE)),
        ("68·全部合格等权", dict(BASE, K=None)),
        ("68·取20分位", dict(BASE, K=20)),
        ("68·取15分位", dict(BASE, K=15)),
        ("68·取30分位", dict(BASE, K=30)),
        ("68·取10随机", dict(BASE, mode="rand")),
        ("64·全部合格", dict(BASE, th=64.0, K=None)),
        ("60·全部合格", dict(BASE, th=60.0, K=None)),
        ("72·全部合格", dict(BASE, th=72.0, K=None)),
        ("68·全部+空仓补HOLD(55)", dict(BASE, K=None, fill=55.0)),
        ("68·全部·按波动倒数加权", dict(BASE, K=None, weight="ivola")),
        ("68·取20·按波动倒数加权", dict(BASE, K=20, weight="ivola")),
        ("68·全部·持10天", dict(BASE, K=None, H=10)),
        ("68·全部·持30天", dict(BASE, K=None, H=30)),
        ("68·全部·持40天", dict(BASE, K=None, H=40)),
        ("68·全部·MA40择时", dict(BASE, K=None, regime={"type": "above", "ma": 40})),
        ("68·全部·MA20择时", dict(BASE, K=None, regime={"type": "above", "ma": 20})),
        ("68·全部·MA60择时", dict(BASE, K=None, regime={"type": "above", "ma": 60})),
        ("68·全部·止损1.5ATR", dict(BASE, K=None, stop=1.5)),
        ("68·全部·止损3ATR", dict(BASE, K=None, stop=3.0)),
        ("68·取20·止损2ATR", dict(BASE, K=20, stop=2.0)),
        ("旧引擎60·全部", dict(BASE, score="old", th=60.0, K=None)),
    ]
    res = {}
    print("\n==== 组合级（相位平均，H=配置持有期）====")
    for lab, cfg in cfgs:
        r = {}
        for tag, kw in (("full", {}), ("is", {"until": P.CUTOFF}), ("oos", {"since": P.CUTOFF})):
            r[tag] = P.stats(cfg, f"{lab} [{tag}]", **kw)
        res[lab] = r
    print("\n==== 排序：全程超大盘 pp ====")
    for lab, r in sorted(res.items(), key=lambda kv: -kv[1]["full"]["vs_bh"]):
        print(f"  {lab:<26} 超大盘 全程{r['full']['vs_bh']*100:+7.1f}pp  样本内{r['is']['vs_bh']*100:+7.1f}pp  "
              f"样本外{r['oos']['vs_bh']*100:+7.1f}pp | 累计 全程{r['full']['cum']*100:+7.1f} 样本外{r['oos']['cum']*100:+7.1f} | "
              f"回撤 全程{r['full']['dd']*100:5.1f} 样本外{r['oos']['dd']*100:5.1f} | 跑赢基准B 全程{r['full']['beat']*100:3.0f}% "
              f"样本外{r['oos']['beat']*100:3.0f}%")


if __name__ == "__main__":
    main()
