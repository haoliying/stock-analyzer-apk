#!/usr/bin/env python3
"""metals_iter6 — 最后一块拼图：贵金属的"风险档"证据强度，和 A 股同口径对照。

iter4/iter5 出现的分裂事实：
  · 择时（68 以上买、42 以下卖）在贵金属上没有任何超额（13/13 为负，CI [-0.56%,-0.29%]）；
  · 但"低分 → 未来更容易吃到深回撤"在贵金属上是成立的：
      标的内部 P(MDD20<=-10%) 低档更高 11/13，符号检验 p=0.022；
      同日跨标的的低档减高档 = +10.4pp，t_HAC +4.33，块自助 p=0.0002；
      只看黄金、再按 ATR 分层，低档 P(MDD20<=-5%) 15.6% vs 高档 7.5%。

这块拼图决定了"贵金属这一类到底给不给建议、给哪一半建议"，所以必须：
  [1] 把同一个风险统计量在 A 股 288 只上算一遍 —— 贵金属有没有 A 股那么强？
  [2] 贵金属按独立资产（金/银/铂/钯）聚合，避免同一资产的多报价重复计数；
  [3] "68 以上"在命中率口径下有没有优势（App 的措辞是"其后 20 日跑赢中位数"）；
  [4] 白银、铂钯也做 ATR 分层，看 iter5 的黄金结果是不是普遍规律。

口径不变（score / 68 / 42 / 30 / 130 根 / MDD 用 t+1..t+H 收盘对 close(t)）。
"""
import glob
import os
import statistics
import sys

import numpy as np
import pandas as pd

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.dirname(HERE))
from metals_iter1 import BUY, COST, MIN_HIST, PRIMARY_H, SELL, STRONG_SELL, boot_p, hac_t, load  # noqa: E402
from metals_iter5 import series, ashare_series  # noqa: E402

ASSET = {"sina_XAU": "黄金(伦敦)", "sina_AU0": "黄金(沪)", "sge_Au99.99": "黄金(上金所)",
         "sge_AuT+D": "黄金T+D", "sge_mAuT+D": "黄金T+D迷你", "sge_Au99.95": "金99.95",
         "sge_Au100g": "金100克", "sina_XAG": "白银(纽约)", "sina_AG0": "白银(沪)",
         "sge_AgT+D": "白银T+D", "sina_XPT": "铂(纽约)", "sge_Pt99.95": "铂(上金所)",
         "sina_XPD": "钯(纽约)"}
KIND = {"sina_XAU": "金", "sina_AU0": "金", "sge_Au99.99": "金", "sge_AuT+D": "金",
        "sge_mAuT+D": "金", "sge_Au99.95": "金", "sge_Au100g": "金",
        "sina_XAG": "银", "sina_AG0": "银", "sge_AgT+D": "银",
        "sina_XPT": "铂", "sge_Pt99.95": "铂", "sina_XPD": "钯"}
INDEP = ["sina_XAU", "sina_XAG", "sina_XPT", "sina_XPD"]


def tier_spread(df, H, thr, ev, min_n=120):
    """每标的：低档 vs 高档的事件概率差（pp）。返回 dict name -> diff。"""
    out = {}
    d = df[np.isfinite(df[f"mdd{H}"])]
    for nm, g in d.groupby("name"):
        lo, hi = g[g.score <= thr], g[g.score > thr]
        if len(lo) < min_n or len(hi) < min_n:
            continue
        out[nm] = 100 * ((lo[f"mdd{H}"] <= ev).mean() - (hi[f"mdd{H}"] <= ev).mean())
    return out


def report(spreads, lab):
    v = np.array(list(spreads.values()), float)
    if len(v) == 0:
        print(f"    {lab:<30} 无可比标的")
        return
    k = int((v > 0).sum())
    print(f"    {lab:<30} 标的 {len(v):>3}  差值中位 {np.median(v):>+6.1f}pp  "
          f"均值 {v.mean():>+6.1f}pp  低档更危险 {k}/{len(v)}")


def day_cluster(df, H, thr, ev, min_n=120):
    """把'低档 vs 高档 事件概率差'按日聚合：同一天取所有标的的档位，再对日做 HAC + 块自助。

    先在标的内部去均值（减去该标的全期事件基率），剩下的才是真正的'档内 vs 档外'信息，
    然后同一天取均值，避免高波动标的长期占住低档造成构成错觉。
    """
    d = df[np.isfinite(df[f"mdd{H}"])].copy()
    d["ev"] = (d[f"mdd{H}"] <= ev).astype(float)
    d["base"] = d.groupby("name")["ev"].transform("mean")
    d["dev"] = d["ev"] - d["base"]
    low = d[d.score <= thr].groupby("date")["dev"].mean()
    high = d[d.score > thr].groupby("date")["dev"].mean()
    j = pd.concat([low.rename("lo"), high.rename("hi")], axis=1).dropna()
    dif = (j["lo"] - j["hi"]).to_numpy() * 100
    t_, m_ = hac_t(dif, H)
    return dict(n_days=m_, mean=dif.mean(), t=t_, p=boot_p(dif, H),
                median=np.median(dif), n_instr=d["name"].nunique())


def main():
    fs = [load(p) for p in sorted(glob.glob(os.path.join(HERE, "metals_data", "*.json")))]
    mdf = pd.concat([series(F) for F in fs], ignore_index=True)
    adf = ashare_series()
    adf["name"] = adf["name"].astype(str)
    print("=" * 104)
    print("metals_iter6  贵金属 vs A股：同一个'风险档'统计量的强度对照")
    print(f"贵金属报价 {len(fs)} 个 / 独立资产 {len(INDEP)} 个；A股 {adf.name.nunique()} 只")
    print("=" * 104)

    # ---------- [1] 同一统计量，两个市场 ----------
    print("\n[1] P(MDD_H <= 阈值)：低档(score<=42) 减 高档(>42)，逐标的差值 + 日聚类检验")
    for H, ev in [(10, -0.05), (20, -0.05), (20, -0.10), (60, -0.10)]:
        lab = f"H={H:>2} P(MDD<={ev*100:.0f}%)"
        report(tier_spread(mdf, H, SELL, ev), f"贵金属(13报价) {lab}")
        report({k: v for k, v in tier_spread(mdf, H, SELL, ev).items() if k in INDEP},
               f"  仅独立资产 {lab}")
        dc = day_cluster(mdf, H, SELL, ev)
        print(f"      贵金属日聚类：{dc['n_days']:,} 天  均值 {dc['mean']:+.2f}pp  中位 {dc['median']:+.2f}pp"
              f"  t_HAC {dc['t']:+.2f}  块自助 p {dc['p']:.4f}")
        report(tier_spread(adf, H, SELL, ev, min_n=60), f"A股(288只)   {lab}")
        dca = day_cluster(adf[np.isfinite(adf[f"mdd{H}"])], H, SELL, ev)
        print(f"      A股日聚类：{dca['n_days']:,} 天  均值 {dca['mean']:+.2f}pp  中位 {dca['median']:+.2f}pp"
              f"  t_HAC {dca['t']:+.2f}  块自助 p {dca['p']:.4f}")

    print("\n[1.b] 同样看'强烈减仓档'(<=30) 对 >42")
    for H, ev in [(20, -0.10), (60, -0.10)]:
        report(tier_spread(mdf, H, STRONG_SELL, ev), f"贵金属 <=30 vs >42 (H={H})")
        report(tier_spread(adf, H, STRONG_SELL, ev, min_n=60), f"A股     <=30 vs >42 (H={H})")

    # ---------- [2] 按资产聚合 ----------
    print("\n[2] 贵金属按资产聚合（同一资产的不同报价合并成一个证据）")
    for H, ev in [(20, -0.05), (20, -0.10), (60, -0.10)]:
        sp = tier_spread(mdf, H, SELL, ev)
        by = {}
        for nm, v in sp.items():
            by.setdefault(KIND.get(nm, "?"), []).append(v)
        line = f"    H={H:>2} P(MDD<={ev*100:.0f}%):  "
        for k in ("金", "银", "铂", "钯"):
            if k in by:
                line += f"{k} {statistics.mean(by[k]):+.1f}pp({len(by[k])})  "
        print(line)

    # ---------- [3] "68 以上"在命中率口径下 ----------
    print("\n[3] 命中率口径：档位内'其后 20 日收益 > 该标的当日全档中位数'的比例"
          "（App 的胜率措辞就是这个，基准 50%）")
    d = mdf[np.isfinite(mdf["r20"])].copy()
    d["med"] = d.groupby("date")["r20"].transform("median")          # 当日全报价中位数（横截面）
    d["medself"] = d.groupby("name")["r20"].transform("median")       # 该标的全期中位数（时序）
    for lo, hi, lab in [(0, 30, "<=30"), (30, 42, "30~42"), (42, 55, "42~55"),
                        (55, 68, "55~68"), (68, 76, "68~76"), (70, 76, "70~76(池化前50%近似)")]:
        sub = d[(d.score >= lo) & (d.score < hi)]
        if len(sub) < 500:
            continue
        hit = 100 * (sub.r20 > sub["med"]).mean()
        per = [100 * (g.r20 > g["med"]).mean() for _, g in sub.groupby("name") if len(g) >= 120]
        hs = 100 * (sub.r20 > sub["medself"]).mean()
        pers = [100 * (g.r20 > g["medself"]).mean() for _, g in sub.groupby("name") if len(g) >= 120]
        print(f"    {lab:<20} n={len(sub):>6,}  横截面基准命中率 {hit:>5.1f}%"
              f"（标的中位 {statistics.median(per):>5.1f}%, >50%的 {sum(1 for x in per if x>50)}/{len(per)}）"
              f"  时序基准 {hs:>5.1f}%（标的中位 {statistics.median(pers):>5.1f}%）")
    a = adf[np.isfinite(adf["r20"])].copy()
    a["med"] = a.groupby("date")["r20"].transform("median")
    a["medself"] = a.groupby("name")["r20"].transform("median")
    print("    A股对照（横截面中位数 = 当日 288 只，这是 App 里那句话的口径）：")
    for lo, hi, lab in [(0, 30, "<=30"), (42, 55, "42~55"), (68, 76, "68~76")]:
        sub = a[(a.score >= lo) & (a.score < hi)]
        if len(sub) < 500:
            continue
        per = [100 * (g.r20 > g["med"]).mean() for _, g in sub.groupby("name") if len(g) >= 60]
        pers = [100 * (g.r20 > g["medself"]).mean() for _, g in sub.groupby("name") if len(g) >= 60]
        print(f"    {lab:<20} n={len(sub):>6,}  横截面基准命中率 {100*(sub.r20 > sub['med']).mean():>5.1f}%"
              f"（标的中位 {statistics.median(per):>5.1f}%, >50%的 {sum(1 for x in per if x>50)}/{len(per)}）"
              f"  时序基准 {100*(sub.r20 > sub['medself']).mean():>5.1f}%"
              f"（标的中位 {statistics.median(pers):>5.1f}%）")

    # ---------- [4] 波动分层（金/银/铂钯各自） ----------
    print("\n[4] 同一资产 + 同一波动层内：低档 vs 高档 的 P(MDD20<=-5%) 差"
          "（排除'低分只是高分标的的波动大'这一混淆）")
    d20 = mdf[np.isfinite(mdf["mdd20"])].copy()
    bands = [(0, 0.012, "ATR<1.2%"), (0.012, 0.02, "1.2~2.0%"), (0.02, 0.03, "2.0~3.0%"),
             (0.03, 9, "ATR>3.0%")]
    for kind in ("金", "银", "铂", "钯"):
        line = f"    {kind}类  "
        for lo, hi, blab in bands:
            s = d20[(d20.name.map(KIND) == kind) & (d20.atr >= lo) & (d20.atr < hi)]
            l, h = s[s.score <= SELL], s[s.score > SELL]
            if len(l) < 120 or len(h) < 120:
                line += f"{blab}: n/a  "
                continue
            p1, p2 = 100 * (l.mdd20 <= -0.05).mean(), 100 * (h.mdd20 <= -0.05).mean()
            line += f"{blab}: {p1:.0f}%vs{p2:.0f}%({p1-p2:+.0f}pp)  "
        print(line)
    a20 = adf[np.isfinite(adf["mdd20"])].copy()
    line = "    A股  "
    for lo, hi, blab in [(0, 0.02, "ATR<2%"), (0.02, 0.04, "2~4%"), (0.04, 9, "ATR>4%")]:
        s = a20[(a20.atr >= lo) & (a20.atr < hi)]
        l, h = s[s.score <= SELL], s[s.score > SELL]
        if len(l) < 200 or len(h) < 200:
            continue
        p1, p2 = 100 * (l.mdd20 <= -0.05).mean(), 100 * (h.mdd20 <= -0.05).mean()
        line += f"{blab}: {p1:.0f}%vs{p2:.0f}%({p1-p2:+.0f}pp)  "
    print(line)

    # ---------- [5] 分数分布对照（可达性，最终版） ----------
    print("\n[5] 分数分布（全样本）：贵金属 vs A股，同一把尺子")
    for lab, x in [("贵金属(独立4)", mdf[mdf.name.isin(INDEP)]["score"]),
                   ("贵金属(13报价)", mdf["score"]),
                   ("A股(288只)", adf["score"])]:
        v = x.to_numpy(float)
        print(f"    {lab:<16} P10 {np.percentile(v,10):>5.1f}  中位 {np.median(v):>5.1f}  "
              f"P90 {np.percentile(v,90):>5.1f}  最高 {v.max():>5.1f}  "
              f">=68 {100*(v>=BUY).mean():>5.1f}%  <=42 {100*(v<=SELL).mean():>5.1f}%  "
              f"<=30 {100*(v<=STRONG_SELL).mean():>5.1f}%")


if __name__ == "__main__":
    main()
