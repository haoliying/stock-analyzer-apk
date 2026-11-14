#!/usr/bin/env python3
"""iter31 — v4.2.0 定稿规则的最终体检表（这一条就是 Kotlin 要实现的）。

规则（与 Kotlin selectDailyCandidates 一一对应，不留任何口径差）：
  1. 当日所有"K线足够、能算出分数"的股票按分数从高到低排序，记 n 只；
  2. 分散池 = 前 50%（take = ceil(0.50*n)），也就是"分数在当日中位数以上"，**不设绝对分数门槛**；
  3. 从池里按分位等距抽 20 只：pool[int(t*(q-1)/20)] for t in 0..19（去重）；
  4. 信号日收盘决定 → 次日开盘买入 → 等权持满 20 个交易日 → 收盘卖出，单边成本 0.12%；
  5. 默认不做机械止损（止损价照旧显示；止损的代价见第 4 节）。

安慰剂（第 2 节）：全市场随机 20 只 / 后 60% 随机 20 只 / 池内分数最高的 20 只。
分层与单池抽样是确定性的；只有 mode="rand" 用 seed，多 seed 时逐日取均值。
"""
import os
import sys
from math import ceil

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import iter23 as H23  # noqa: E402
import iter25 as Q  # noqa: E402
import iter27 as W  # noqa: E402
import portsim as P  # noqa: E402
from model import CUTOFF, DATES  # noqa: E402

ND, HP = P.ND, 20
S = Q.S0                       # 现行上线（v4.1.0）打分矩阵
VALID = W.VALID
R = W.R20
BM = W.BM
RANK = np.argsort(np.where(VALID, S, -np.inf), axis=1)[:, ::-1]

# 定稿参数：池 = 当日可评分股票的前 50%（= 分数在中位数以上），分层等距取 20 只
FINAL_FQ, FINAL_K = 0.50, 20


def basket(i, fq=FINAL_FQ, K=FINAL_K, mode="strat", seed=0, tail=False, fcount=None):
    """信号日 i 的篮子下标。tail=True 时从"分数后 (1-fq) 段"里随机抽（安慰剂）。
    fcount 不为 None 时池 = 固定只数（口径对照用），否则 = ceil(fq × 当日可评分只数)。"""
    ranked = RANK[i]
    ranked = ranked[VALID[i, ranked]]
    n = len(ranked)
    if n == 0:
        return np.array([], int)
    if tail:
        pool = ranked[int(round(fq * n)):]
        if len(pool) < K:
            return np.array([], int)
        return np.random.default_rng(seed * 100003 + i).choice(pool, K, replace=False)
    take = n if (fq is None or fq >= 1.0) else min(n, ceil(fq * n))
    if fcount is not None:
        take = min(n, fcount)
    pool = ranked[:take]
    q = len(pool)
    if K is None or q <= K:
        return pool
    if mode == "top":
        return pool[:K]
    if mode == "rand":
        return np.random.default_rng(seed * 100003 + i).choice(pool, K, replace=False)
    return pool[np.array(sorted({int(t * (q - 1) / K) for t in range(K)}))]


def rows(fq=FINAL_FQ, K=FINAL_K, mode="strat", seeds=(0,), stop=None, tail=False, fcount=None):
    """逐日 (信号日, 篮子 20 日收益, 基准B, 只数)；多 seed 时逐日取均值。"""
    rr = R if stop is None else Q.I.stop_ret(HP, stop, VALID)
    out = []
    for i in range(ND - 1 - HP):
        vals, nsel = [], 0
        for sd in seeds:
            sel = basket(i, fq, K, mode, sd, tail, fcount)
            if sel.size == 0:
                continue
            v = rr[i, sel]
            v = v[np.isfinite(v)]
            if v.size:
                vals.append(float(np.mean(v)))
                nsel = int(v.size)
        out.append((DATES[i], float(np.mean(vals)) if vals else 0.0, float(BM[i]), nsel))
    return out


def stat(label, r, quiet=False):
    e = np.array([x[1] - x[2] for x in r])
    ds = np.array([x[0] for x in r])
    o = ds >= CUTOFF
    ph = W.phases(r)
    ca = np.array([x["a"] for x in ph]); cb = np.array([x["b"] for x in ph])
    ba = np.array([W.bench_a(x["lo"], x["hi"]) for x in ph])
    cd = np.array([x["dd"] for x in ph])
    flat = np.array([float(v) for x in ph for v in x["s"]])
    if not quiet:
        print(f"  {label:<30} 日均{np.mean([x[3] for x in r]):6.1f}只 超额{e.mean()*100:+6.3f}% "
              f"t_HAC {H23.hac_t(e, HP):+5.2f} p {H23.block_p(e, HP):.3f} 样本外{e[o].mean()*100:+6.3f}% | "
              f"累计{ca.mean()*100:+6.1f}%±{ca.std()*100:4.1f}% 超B{(ca-cb).mean()*100:+6.1f}pp"
              f"({(ca > cb).sum()}/{len(ca)}相位) 超A{(ca-ba).mean()*100:+6.1f}pp"
              f"({(ca > ba).sum()}/{len(ca)}) 回撤{cd.mean()*100:5.1f}% "
              f"单期中位{np.median(flat)*100:+5.2f}% 亏>10%{(flat < -0.10).mean()*100:3.1f}% "
              f"最差单期{flat.min()*100:+5.1f}%")
    return dict(e=e, ds=ds, ca=ca, cb=cb, ba=ba, cd=cd, flat=flat)


def main():
    bh = P.bench_hold(0, ND)
    cb = np.array([x["b"] for x in W.phases([(DATES[i], BM[i], 0.0, 1) for i in range(ND - HP - 1)])])
    print(f"\n基准A 大盘·买入持有 {bh*100:+.1f}%   基准B 大盘·每20日再平衡 {cb.mean()*100:+.1f}%"
          f"±{cb.std()*100:.1f}% [{cb.min()*100:+.1f}%, {cb.max()*100:+.1f}%]")

    print("\n==== 1. 定稿规则 + 单参数敏感性 ====")
    main_rule = stat(f"定稿 前{FINAL_FQ*100:.0f}% 池 · 分层{FINAL_K} · 不止损",
                  rows(FINAL_FQ, FINAL_K, "strat"))
    print("  池宽（只改 fq）：")
    for fq in (0.30, 0.35, 0.40, 0.45, 0.50, 0.60):
        stat(f"    前{fq*100:.0f}% 池 · 分层{FINAL_K}", rows(fq, FINAL_K, "strat"))
    print("  只数（只改 K）：")
    for K in (10, 15, 20, 30, 50):
        stat(f"    前{FINAL_FQ*100:.0f}% 池 · 分层{K}", rows(FINAL_FQ, K, "strat"))

    print(f"\n==== 2. 安慰剂（同规模 {FINAL_K} 只）====")
    stat(f"池内分数最高的{FINAL_K}只（排行榜）", rows(FINAL_FQ, FINAL_K, "top"))
    stat(f"随机{FINAL_K}只（全市场，20种子均值）",
         rows(1.00, FINAL_K, "rand", seeds=tuple(range(20))))
    stat(f"随机{FINAL_K}只（后{(1-FINAL_FQ)*100:.0f}%，20种子均值）",
         rows(FINAL_FQ, FINAL_K, "rand", seeds=tuple(range(20)), tail=True))

    print("\n==== 3. 现行 v4.1.0 规则同表对照 ====")
    stat("现行 ≥68 全部等权", Q.rows_rule(th=68.0, fq=None, K=None, H_=HP, min_pool=1))
    stat("现行 ≥68 抽10（旧展示规模）", Q.rows_rule(th=68.0, fq=None, K=10, H_=HP, min_pool=1))
    base = {x[0]: x[1] - x[2] for x in Q.rows_rule(th=68.0, fq=None, K=None, H_=HP, min_pool=1)}
    print("  配对检验（候选 − 现行，逐日超额差；同一批信号日）：")
    for lab, rr in ((f"定稿 前{FINAL_FQ*100:.0f}% 分层{FINAL_K}", rows(FINAL_FQ, FINAL_K, "strat")),
                    ("前50% 分层30", rows(0.50, 30, "strat")),
                    ("前40% 分层20（上一版候选）", rows(0.40, 20, "strat")),
                    ("前50% 池满池等权", rows(0.50, None, "strat")),
                    ("定稿 + 2ATR 止损", rows(FINAL_FQ, FINAL_K, "strat", stop=2.0)),
                    ("定稿 + 3ATR 止损", rows(FINAL_FQ, FINAL_K, "strat", stop=3.0))):
        cur = {x[0]: x[1] - x[2] for x in rr}
        common = [c for c in base if c in cur]
        d = np.array([cur[c] - base[c] for c in common])
        print(f"    {lab:<18} 日均差 {d.mean()*100:+6.3f}%  t_HAC {H23.hac_t(d, HP):+5.2f}  "
              f"块自助p {H23.block_p(d, HP):.3f}  更好天数 {(d > 0).mean()*100:3.0f}%")

    print("\n==== 4. 止损的代价（定稿规则）====")
    stat("定稿 · 2×ATR 止损", rows(FINAL_FQ, FINAL_K, "strat", stop=2.0))
    stat("定稿 · 3×ATR 止损", rows(FINAL_FQ, FINAL_K, "strat", stop=3.0))

    print(f"\n==== 4b. 池宽口径对照：相对分位 vs 固定只数（同 K={FINAL_K}）====")
    nvalid = np.array([int(VALID[i].sum()) for i in range(ND - 1 - HP)])
    print(f"  当日可评分只数：中位 {np.median(nvalid):.0f}  p10 {np.percentile(nvalid,10):.0f}  "
          f"p90 {np.percentile(nvalid,90):.0f}")
    stat(f"    前{FINAL_FQ*100:.0f}%（相对，定稿）", rows(FINAL_FQ, FINAL_K, "strat"))
    for c in (115, 140, 150, 200):
        stat(f"    固定{c}只池", rows(FINAL_FQ, FINAL_K, "strat", fcount=c))
    stat("    前40%（相对，上一版候选）", rows(0.40, FINAL_K, "strat"))

    print("\n==== 5. 定稿的分年 / 分月 ====")
    e, ds = main_rule["e"], main_rule["ds"]
    for y in sorted({d[:4] for d in ds}):
        m = np.array([d.startswith(y) for d in ds])
        if m.sum() < 20:
            continue
        print(f"  {y}：{int(m.sum()):3d} 个信号日  日均超额 {e[m].mean()*100:+.3f}%  "
              f"t_HAC {H23.hac_t(e[m], HP):+.2f}  跑赢天数 {(e[m] > 0).mean()*100:3.0f}%  "
              f"单笔中位 {np.median(e[m])*100:+.2f}%")
    mo = {}
    for x in rows(FINAL_FQ, FINAL_K, "strat"):
        mo.setdefault(x[0][:7], []).append(x[1] - x[2])
    v = np.array([np.mean(mo[k]) for k in sorted(mo)]); ks = sorted(mo)
    print(f"  分月 {len(ks)} 个：正超额 {(v > 0).mean()*100:.0f}%  中位 {np.median(v)*100:+.2f}%  "
          f"最差 {v.min()*100:+.2f}%（{ks[int(np.argmin(v))]}）  最好 {v.max()*100:+.2f}%（{ks[int(np.argmax(v))]}）")

    print(f"\n==== 6. 真实用户的分布（{FINAL_K} 只 · 20 种抽法 × 20 个换仓相位 = 400 条曲线）====")
    vs, cs, dds, wa = [], [], [], []
    for sd in range(20):
        st = stat("", rows(FINAL_FQ, FINAL_K, "rand", seeds=(sd,)), quiet=True)
        vs.append(st["ca"] - st["cb"]); cs.append(st["ca"]); dds.append(st["cd"])
        wa.append(st["ca"] - st["ba"])
    vs = np.concatenate(vs); cs = np.concatenate(cs); dds = np.concatenate(dds); wa = np.concatenate(wa)
    print(f"  超同网格大盘：中位 {np.median(vs)*100:+.1f}pp  p10 {np.percentile(vs,10)*100:+.1f}pp  "
          f"p90 {np.percentile(vs,90)*100:+.1f}pp  跑赢概率 {(vs > 0).mean()*100:.0f}%")
    print(f"  超买入持有大盘：中位 {np.median(wa)*100:+.1f}pp  跑赢概率 {(wa > 0).mean()*100:.0f}%")
    print(f"  累计收益：中位 {np.median(cs)*100:+.1f}%  p10 {np.percentile(cs,10)*100:+.1f}%  "
          f"p90 {np.percentile(cs,90)*100:+.1f}%  亏钱概率 {(cs < 0).mean()*100:.0f}%")
    print(f"  最大回撤：中位 {np.median(dds)*100:.1f}%  p90（即最差 10%）{np.percentile(dds,10)*100:.1f}%  "
          f"最差 {dds.min()*100:.1f}%")
    print(f"  单期（20 个交易日）：胜率 {(main_rule['flat'] > 0).mean()*100:.0f}%  "
          f"中位 {np.median(main_rule['flat'])*100:+.2f}%  亏>10% {(main_rule['flat'] < -0.10).mean()*100:.1f}%  "
          f"最差 {main_rule['flat'].min()*100:+.1f}%")


if __name__ == "__main__":
    main()
