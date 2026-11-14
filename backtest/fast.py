#!/usr/bin/env python3
"""快速组合级评估：把"排序质量"和"资金曲线"分开算，全部向量化。

RET[H][i,j] = 第 i 个信号日、第 j 只票，t+1 开盘买、持 H 日后开盘/收盘卖的净收益。
选股票 = 每天从"可交易"里按分数取前 q 比例（固定宽度，避免阈值和分数刻度互相干扰）。
这样每个配置只要几毫秒，可以做正经的参数搜索；搜索目标只看样本内(<CUTOFF)，
样本外(>=CUTOFF)只用来验证，不参与挑参数。
"""
import sys

import numpy as np

sys.path.insert(0, "/data/backtest")
import portsim as P  # noqa: E402
from model import COST, CUTOFF, DATES  # noqa: E402

ND, NS = P.ND, P.NS
F = lambda k: P.FAC[k] if k in P.FAC else P.MAT[k]  # noqa: E731
C, O1 = P.MAT["close"], P.MAT["open"]
PCT, LIM = P.MAT["pct"], P.MAT["_lim"]
UNB = (PCT > 0) & (np.roll(O1, -1, axis=0) >= C * (1 + LIM) * 0.995)  # 次日开盘涨停买不到
UNB[-1] = True
_ret_cache = {}


def ret_mat(H):
    """行 = 信号日 i，列 = 股票；收益 = close[i+1+H]/open[i+1]-1-cost"""
    if H not in _ret_cache:
        e = np.roll(O1, -1, axis=0)
        x = np.roll(C, -(1 + H), axis=0)
        r = x / e - 1.0 - COST
        with np.errstate(all="ignore"):
            r[~np.isfinite(r)] = np.nan
        _ret_cache[H] = r
    return _ret_cache[H]


def valid_mat(S, H):
    r = ret_mat(H)
    return (~np.isnan(S)) & (~np.isnan(r)) & (~UNB) & (np.roll(O1, -1, axis=0) > 0) & (C > 0)


def daily_topq(S, q, H, bench=None, valid=None):
    """每日取分数前 q 比例，返回 (date, 篮子收益, 基准收益, 只数)"""
    R = ret_mat(H)
    if valid is None:
        valid = valid_mat(S, H)
    if bench is None:
        bench = np.nanmean(np.where(valid, R, np.nan), axis=1)  # 同日可交易全集等权 = 基准B
    k = max(1, int(round(q * NS)))
    rows = []
    order = np.argsort(np.where(valid, S, -np.inf), axis=1)[:, ::-1]
    for i in range(ND - 1 - H):
        b = bench[i]
        if np.isnan(b):
            continue
        sel = order[i, :k]
        sel = sel[valid[i, sel]]
        if len(sel) < 3:
            continue
        rows.append((DATES[i], float(np.mean(R[i, sel])), float(b), len(sel)))
    if not rows:
        return [], np.zeros((0, 3))
    return [r[0] for r in rows], np.array([[r[1], r[2], r[3]] for r in rows])


def score_stats(S, q, H, label, monthly=False):
    ds, d = daily_topq(S, q, H)
    if len(d) < 20:
        print(f"  {label:<44} 样本不足")
        return None
    e = d[:, 0] - d[:, 1]
    ism = np.array([x < CUTOFF for x in ds])
    out = {}
    for tag, m in (("全程", np.ones(len(e), bool)), ("样本内", ism), ("样本外", ~ism)):
        ee, rr = e[m], d[m, 0]
        t = ee.mean() / (ee.std(ddof=1) / np.sqrt(max(int(m.sum()), 1)))
        out[tag] = dict(n=int(m.sum()), mean=float(rr.mean()), exc=float(ee.mean()), t=float(t),
                        win=float((ee > 0).mean()), pos=float((rr > 0).mean()),
                        worst=float(rr.min()), nn=float(np.mean(d[m, 2])))
    print(f"  {label:<44} 日均{out['全程']['nn']:5.1f}只  超额 全程 {out['全程']['exc']*100:+6.3f}%(t={out['全程']['t']:+5.2f})  "
          f"样本内 {out['样本内']['exc']*100:+6.3f}%(t={out['样本内']['t']:+5.2f})  样本外 {out['样本外']['exc']*100:+6.3f}%(t={out['样本外']['t']:+5.2f})  "
          f"| 跑赢日 {out['全程']['win']*100:3.0f}% 篮子均值 {out['全程']['mean']*100:+5.2f}%")
    if monthly:
        mo = {}
        for x, y, z in zip(ds, d[:, 0], d[:, 1]):
            mo.setdefault(x[:7], []).append(y - z)
        v = np.array([np.mean(mo[k]) for k in sorted(mo)])
        print(f"     月度超额: {v.size} 月 均值 {v.mean()*100:+5.2f}%  正 {(v>0).mean()*100:3.0f}%  最差 {v.min()*100:+5.2f}%")
    return out


def compound(S, q, H, label, valid=None):
    """固定宽度、始终满仓的资金曲线（相位平均）vs 大盘等权每日持有"""
    R = ret_mat(H)
    if valid is None:
        valid = valid_mat(S, H) if valid is None else valid
    k = max(1, int(round(q * NS)))
    order = np.argsort(np.where(valid, S, -np.inf), axis=1)[:, ::-1]
    braw = np.nanmean(np.where(valid, R, np.nan), axis=1)
    cums, alphas, dds, beats, excs = [], [], [], [], []
    for p in range(min(H, 6)):
        rows = []
        for i in range(p, ND - 1 - H, H):
            sel = order[i, :k]
            sel = sel[valid[i, sel]]
            if len(sel) < 3:
                rows.append((0.0, 0.0))
                continue
            rows.append((float(np.mean(R[i, sel])), float(braw[i]) if not np.isnan(braw[i]) else 0.0))
        if not rows:
            continue
        r = np.array([x[0] for x in rows]); b = np.array([x[1] for x in rows])
        eq = np.cumprod(1 + r); beq = np.cumprod(1 + b)
        cums.append(eq[-1] - 1); alphas.append(eq[-1] / beq[-1] - 1)
        dds.append((eq / np.maximum.accumulate(eq) - 1).min())
        beats.append((r - b > 0).mean()); excs.append((r - b).mean())
    bh = P.bench_hold(0, ND)
    print(f"  {label:<44} 累计 {np.mean(cums)*100:+7.1f}%  年化 {(np.mean(cums))*100/1.87:+6.1f}%(粗)  "
          f"回撤 {np.mean(dds)*100:5.1f}%  同格再平衡基准 {np.mean(alphas)*100:+6.1f}%  "
          f"超大盘 {(np.mean(cums)-bh)*100:+6.1f}pp  跑赢期 {np.mean(beats)*100:3.0f}%")
    return dict(cum=float(np.mean(cums)), dd=float(np.mean(dds)), alpha=float(np.mean(alphas)),
                vs_bh=float(np.mean(cums) - bh), beat=float(np.mean(beats)), exc=float(np.mean(excs)))
