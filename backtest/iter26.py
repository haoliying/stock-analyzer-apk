#!/usr/bin/env python3
"""iter26 — 候选规则 vs 现行规则的配对检验 + 去抽签运气。

iter25 的坑：分层抽 K 只、随机抽 K 只，都带"今天抽到哪几只"的运气，
累计收益在 +40% ~ +70% 之间跳，分不清是规则好还是手气好。
本轮只认两种口径：
  A. 确定性满池：池内**全部**等权（K=None），没有任何抽签成分；
  B. 抽签稳健性：同一规则跑 20 个分层偏移 + 20 个随机种子，看均值±标准差。
显著性用配对检验：同一批交易日的"候选超额 − 现行超额"序列，做 HAC t 与块自助 p。
只有 (1) 相对现行配对显著，或 (2) 尾部/回撤明显更好，才允许上线。
"""
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import iter23 as H  # noqa: E402
import iter25 as Q  # noqa: E402
import portsim as P  # noqa: E402
from model import CUTOFF, DATES  # noqa: E402

ND, NS = P.ND, P.NS
IDX = {d: i for i, d in enumerate(DATES)}


def series(out, H_=20):
    """(dates, r, b) 以及相位平均的累计/回撤（全程 + 样本外）。"""
    ds = np.array([x[0] for x in out])
    r = np.array([x[1] for x in out])
    b = np.array([x[2] for x in out])
    e = r - b
    n = np.array([x[3] for x in out], float)
    res = dict(ds=ds, r=r, b=b, e=e, n=n)
    for tag, m in (("all", np.ones(len(ds), bool)), ("oos", ds >= CUTOFF)):
        cums, dds = [], []
        for p in range(H_):
            jj = [j for j, d in enumerate(ds) if m[j] and (IDX[d] - p) % H_ == 0]
            if len(jj) < 3:
                continue
            eq = np.cumprod(1 + r[jj])
            cums.append(eq[-1] - 1)
            dds.append((eq / np.maximum.accumulate(eq) - 1).min())
        res["cum" + tag] = float(np.mean(cums)) if cums else np.nan
        res["dd" + tag] = float(np.mean(dds)) if dds else np.nan
        res["beat" + tag] = float((e[m] > 0).mean()) if m.any() else np.nan
    res["worst"] = float(r.min())
    res["expo"] = float((n > 0).mean())
    return res


def one_line(s, label, bh):
    e = s["e"]
    print(f"  {label:<34} 日均{s['n'].mean():5.1f}只 持仓{s['expo']*100:3.0f}%  "
          f"超额{e.mean()*100:+6.3f}% t_HAC {H.hac_t(e, 20):+5.2f} p {H.block_p(e, 20):.3f}  "
          f"样本外{e[s['ds'] >= CUTOFF].mean()*100:+6.3f}% | "
          f"累计{s['cumall']*100:+7.1f}%(超大盘{(s['cumall']-bh)*100:+6.1f}pp) "
          f"样本外累计{s['cumoos']*100:+7.1f}% 回撤{s['ddall']*100:5.1f}% "
          f"跑赢期{s['beatall']*100:3.0f}% 最差单期{s['worst']*100:+5.1f}%")


def paired(a, b_, label, H_=20):
    """候选 a 相对现行 b 的**同日**超额差：配对 HAC t + 块自助 p。"""
    da = {d: (x - y) for d, x, y in zip(a["ds"], a["r"], a["b"])}
    db = {d: (x - y) for d, x, y in zip(b_["ds"], b_["r"], b_["b"])}
    common = [d for d in b_["ds"] if d in da]
    dd = np.array([da[d] - db[d] for d in common])
    m = np.array([d >= CUTOFF for d in common])
    print(f"  {label:<34} 共同交易日 {len(dd):4d}  日均差 {dd.mean()*100:+6.3f}%  "
          f"t_HAC {H.hac_t(dd, H_):+5.2f}  块自助p {H.block_p(dd, H_):.3f}  "
          f"样本外差 {dd[m].mean()*100:+6.3f}%  候选更好天数 {(dd > 0).mean()*100:3.0f}%")
    return dd


def main():
    bh = P.bench_hold(0, ND)
    print(f"\n大盘（288只等权买入持有）累计 {bh*100:+.1f}%")

    # ---- 1. 确定性满池：池内全部等权，无抽签 ----------------------------------
    print("\n==== 1. 确定性满池（池内全部等权，零抽签成分）====")
    det = {}
    for th, fq in ((68.0, None), (60.0, None), (55.0, None), (43.0, None), (0.0, 0.40),
                   (0.0, 0.30), (0.0, 0.50), (0.0, 0.70), (60.0, 0.40), (60.0, 0.30), (60.0, 0.50),
                   (0.0, 0.20), (0.0, 0.10)):
        lab = f"≥{th:.0f}" + (f" 且 前{fq*100:.0f}%" if fq else "（绝对门槛）")
        det[lab] = series(Q.rows_rule(th=th, fq=fq, K=None, H_=20))
        one_line(det[lab], lab + " 满池等权", bh)

    base = det["≥68（绝对门槛）"]

    print("\n==== 2. 配对检验：候选 vs 现行 ≥68（同一批交易日，逐日篮子超额差）====")
    for lab in det:
        if lab == "≥68（绝对门槛）":
            continue
        paired(det[lab], base, lab + "  −  现行≥68")

    # ---- 3. 抽签稳健性：分层偏移 × 随机种子 ----------------------------------
    print("\n==== 3. 抽签稳健性（同规则 40 种抽法：均值 ± 标准差 [最差, 最好]）====")
    for th, fq, K, stop in ((60.0, 0.40, 20, None), (60.0, 0.40, 20, 2.0), (60.0, 0.40, 30, None),
                            (68.0, 0.40, 20, None), (0.0, 0.40, 20, None), (0.0, 0.30, 20, None),
                            (60.0, 0.30, 15, 2.0)):
        tag = f"≥{th:.0f} 前{fq*100:.0f}% 取{K}" + (f" 止损{stop}ATR" if stop else "")
        cs, ds_, es, ts = [], [], [], []
        for off in range(20):
            s = series(Q.rows_rule(th=th, fq=fq, K=K, H_=20, stop=stop, off=off))
            cs.append(s["cumall"]); es.append(s["e"].mean()); ts.append(H.hac_t(s["e"], 20))
        for sd in range(20):
            s = series(Q.rows_rule(th=th, fq=fq, K=K, H_=20, stop=stop, mode="rand", seed=sd))
            cs.append(s["cumall"]); ds_.append(s["ddall"]); es.append(s["e"].mean())
        cs = np.array(cs); ds_ = np.array(ds_); es = np.array(es); ts = np.array(ts)
        print(f"  {tag:<26} 累计 均{cs.mean()*100:+6.1f}% ±{cs.std()*100:4.1f}% "
              f"[{cs.min()*100:+6.1f}%, {cs.max()*100:+6.1f}%]  超大盘 {(cs.mean()-bh)*100:+6.1f}pp  "
              f"40种抽法赢大盘 {(cs > bh).mean()*100:3.0f}%  |  回撤 均{ds_.mean()*100:5.1f}% ±"
              f"{ds_.std()*100:4.1f}%  日均超额 {es.mean()*100:+5.3f}%  t_HAC 中位 {np.median(ts):+4.2f}")

    # ---- 4. 现行规则加"满池等权 + 分层展示 10 只"的实际差别 --------------------
    print("\n==== 4. 展示 10 只 vs 满池 30~50 只：如果用户只买展示的那 10 只 ====")
    for th, fq, K in ((60.0, 0.40, 10), (68.0, None, 10), (68.0, None, 20)):
        tag = f"≥{th:.0f}" + (f" 前{fq*100:.0f}%" if fq else "") + f" · 只买{K}只"
        cs, es = [], []
        for off in range(20):
            s = series(Q.rows_rule(th=th, fq=fq, K=K, H_=20, off=off))
            cs.append(s["cumall"]); es.append(s["e"].mean())
        for sd in range(20):
            s = series(Q.rows_rule(th=th, fq=fq, K=K, H_=20, mode="rand", seed=sd))
            cs.append(s["cumall"]); es.append(s["e"].mean())
        print(f"  {tag:<24} 累计 均{np.mean(cs)*100:+6.1f}% ±{np.std(cs)*100:4.1f}%  "
              f"日均超额 {np.mean(es)*100:+5.3f}%  赢大盘 {np.mean(np.array(cs) > bh)*100:3.0f}%")

    # ---- 5. 分年度、分月：赢家是不是只赢在某一段 --------------------------------
    print("\n==== 5. 分年度日均超额（%）/ 该年跑赢大盘的天数占比 ====")
    def yearly(s, label):
        yrs = sorted({d[:4] for d in s["ds"]})
        cells = []
        for y in yrs:
            m = np.array([d.startswith(y) for d in s["ds"]])
            if m.sum() < 10:
                continue
            cells.append(f"{y} {s['e'][m].mean()*100:+5.2f}/{(s['e'][m] > 0).mean()*100:3.0f}%")
        print(f"  {label:<30} " + "  ".join(cells))

    yearly(base, "现行 ≥68 全部等权")
    yearly(det["≥60 且 前40%"], "候选 ≥60 且 前40% 满池等权")
    yearly(det["≥0 且 前40%"], "候选 前40% 满池等权（无门槛）")
    yearly(series(Q.rows_rule(th=60.0, fq=0.40, K=20, H_=20)), "候选 ≥60 前40% 分层取20")

    print("\n==== 6. 分月：正超额月份数（共 %d 个月）====" % len({d[:7] for d in base["ds"]}))
    def monthly(s, label):
        mo = {}
        for d, x, y in zip(s["ds"], s["r"], s["b"]):
            mo.setdefault(d[:7], []).append(x - y)
        v = np.array([np.mean(mo[k]) for k in sorted(mo)])
        k = sorted(mo)
        print(f"  {label:<30} 正月 {(v > 0).mean()*100:3.0f}%  中位 {np.median(v)*100:+5.2f}%  "
              f"最差月 {v.min()*100:+5.2f}%（{k[int(v.argmin())]}）  最好月 {v.max()*100:+5.2f}%")
    for lab, s in (("现行 ≥68 全部等权", base),
                   ("候选 ≥60 且 前40% 满池", det["≥60 且 前40%"]),
                   ("候选 前40% 满池（无门槛）", det["≥0 且 前40%"]),
                   ("候选 ≥60 前40% 分层20", series(Q.rows_rule(th=60.0, fq=0.40, K=20, H_=20)))):
        monthly(s, lab)


if __name__ == "__main__":
    main()
