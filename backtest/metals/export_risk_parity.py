#!/usr/bin/env python3
"""export_risk_parity — 生成 app/src/test/resources/metals_risk_parity.json

为什么要有这个夹具：界面上「风险参考」那张卡的每一个数都是 `MetalsRisk.kt` 里的
纯算术（21 日年化波动、波动目标折仓位、比值的 z 与历史分位）。这些公式在 Python 里
跑过（iter9/iter10/iter11/iter12），Kotlin 里又实现了一遍 —— **两份实现只要有一条
口径不一样，界面上显示的就是回测里没有验证过的那个东西**。所以这里把同一批日线
（跟上一次回测完全相同的那 6 条序列）喂给一份逐行照着 Kotlin 写的 Python，
把期望值连输入一起写进夹具，单测再拿 Kotlin 跑一遍对数字。

刻意写成朴素循环、不用 pandas：pandas 的 rolling 用的是带补偿的滑动和，
浮点末位跟 Kotlin 的累加顺序不同；夹具要把关的是**口径**，不是最后一位噪声，
所以两边都用最笨的写法，差异只可能来自规则本身。

同时这里核对一次 iter12 §[3] 打印过的那张表（13 条报价的当前读数）：
表里的数对不上，就说明这份镜像跟回测跑的不是同一个东西。
"""
import json
import math
import os

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.join(HERE, "metals_data")
OUT = os.path.normpath(os.path.join(
    HERE, "..", "..", "app", "src", "test", "resources", "metals_risk_parity.json"))

# 与 MetalsRisk.kt 同一组常量，改一边必须改另一边
VOL_WINDOW, TRADING_DAYS = 21, 252.0
TARGETS = (0.10, 0.15, 0.20)
STEP, BAND, FLOOR = 0.05, 0.10, 0.25
Z_WINDOW, MIN_BARS = 120, 130

# 界面上的 6 个品种 → metals_data 里那条序列（同一个新浪接口，同一批日线）
CODES = {
    "hf_XAU": "sina_XAU",
    "hf_XAG": "sina_XAG",
    "hf_XPT": "sina_XPT",
    "hf_XPD": "sina_XPD",
    "nf_AU0": "sina_AU0",
    "nf_AG0": "sina_AG0",
}

# iter12 §[3] 打印的当前读数（末日 2026-11-16）：波动 / 10% / 15% / 20% 折出来的仓位
ITER12_TABLE = {
    "sina_XAU": (26.0, 35, 60, 75),
    "sina_XAG": (43.3, 25, 30, 45),
    "sina_XPT": (36.7, 30, 45, 50),
    "sina_XPD": (41.0, 30, 35, 50),
    "sina_AU0": (22.5, 50, 75, 100),
    "sina_AG0": (43.3, 30, 45, 45),
}


def rows(name):
    d = json.load(open(os.path.join(DATA, name + ".json"), encoding="utf-8"))
    r = d["rows"]
    return [x["date"] for x in r], [float(x["close"]) for x in r]


# ─────────────────────── 与 MetalsRisk.kt 逐行对应的实现 ───────────────────────

TAIL = 400        # 每个品种留最后 400 根的仓位轨迹，让单测能逐日对而不只是对最后一个点


def vol_annualized(closes):
    """21 日收盘收益的总体标准差（ddof=0）× √252；不足 VOL_WINDOW+1 根不给数。"""
    if len(closes) < VOL_WINDOW + 1:
        return None
    ret = [None] + [closes[i] / closes[i - 1] - 1.0 for i in range(1, len(closes))]
    w = ret[len(ret) - VOL_WINDOW:]
    mean = sum(w) / VOL_WINDOW
    sq = sum((x - mean) ** 2 for x in w)
    sd = math.sqrt(sq / VOL_WINDOW)
    return None if sd <= 0.0 else sd * math.sqrt(TRADING_DAYS)


def weight_path(closes, target, ties=None):
    """波动目标 + 5% 取整 + 10% 不动区间 + 25% 下限，整段回放（有状态，不能只看最后一天）。"""
    if len(closes) < MIN_BARS:
        return None
    ret = [None] + [closes[i] / closes[i - 1] - 1.0 for i in range(1, len(closes))]
    out = [float("nan")] * len(closes)
    cur = 1.0
    seen = False
    for i in range(VOL_WINDOW, len(closes)):
        w = ret[i - VOL_WINDOW + 1:i + 1]
        mean = sum(w) / VOL_WINDOW
        sq = sum((x - mean) ** 2 for x in w)
        v = math.sqrt(sq / VOL_WINDOW) * math.sqrt(TRADING_DAYS)
        if not math.isfinite(v) or v <= 0.0:
            continue
        seen = True
        raw = min(1.0, target / v)
        x = raw / STEP
        if ties is not None and x - math.floor(x) == 0.5:   # .5 平局：half-even 与四舍五入在这里分叉
            ties.append(x)
        step = _rint(x) * STEP                  # half-even，与 Python 内置 round() 同规则
        if abs(step - cur) >= BAND:
            cur = step
        out[i] = max(cur, FLOOR)
    return out if seen else None


def _rint(x):
    """Java Math.rint / Python round()：.5 取偶。"""
    f = math.floor(x)
    d = x - f
    if d > 0.5:
        return float(f + 1)
    if d < 0.5:
        return float(f)
    return float(f) if f % 2 == 0 else float(f + 1)


def gauge(closes, target):
    ties = []
    path = weight_path(closes, target, ties)
    if path is None:
        return None
    vol = vol_annualized(closes)
    if vol is None:
        return None
    valid = changes = 0
    last, last_idx = float("nan"), -1
    for i, p in enumerate(path):
        if not math.isfinite(p):
            continue
        if math.isfinite(last) and abs(p - last) > 1e-9:
            changes += 1
        last, valid, last_idx = p, valid + 1, i
    if last_idx < 0 or valid < 2:
        return None
    tail = path[len(path) - TAIL:]
    assert all(math.isfinite(p) for p in tail), "这一段尾部还有不可用位，夹具没法逐日对"
    return {"vol": vol, "weight": path[last_idx], "target": target, "bars": valid,
            "changes": changes, "ties": len(ties),
            "changes_per_year": changes / (valid / TRADING_DAYS), "tail": tail}


def pair_lookup(dom_dates, dom_c, ovs_dates, ovs_c):
    """境内外溢价：以境内日期为轴，外盘取「≤ 该日期」的最后一根。返回 (日期, 比值)。"""
    out, j = [], -1
    for i, d in enumerate(dom_dates):
        while j + 1 < len(ovs_dates) and ovs_dates[j + 1] <= d:
            j += 1
        if j < 0:
            continue
        a, b = dom_c[i], ovs_c[j]
        if a > 0 and b > 0:
            out.append((d, a / b))
    return out


def pair_inner_join(num_dates, num_c, den_dates, den_c):
    den = dict(zip(den_dates, den_c))
    out = []
    for d, a in zip(num_dates, num_c):
        b = den.get(d)
        if b is not None and a > 0 and b > 0:
            out.append((d, a / b))
    return out


def z_series(s, window):
    """返回 (z 列表, 最后一个可用 z 落在第几根)。分位要跟整段比，时间要按最后那根标。"""
    out, last_idx = [], -1
    for i in range(window - 1, len(s)):
        w = s[i - window + 1:i + 1]
        ma = sum(w) / window
        sq = sum((x - ma) ** 2 for x in w)
        sd = math.sqrt(sq / window)
        if sd <= 0.0:
            continue
        z = (s[i] - ma) / sd
        if math.isfinite(z):
            out.append(z)
            last_idx = i
    return out, last_idx


def deviation(pairs, window=Z_WINDOW):
    if len(pairs) < window + MIN_BARS:
        return None
    zs, last_idx = z_series([math.log(v) for _, v in pairs], window)
    if last_idx < 0:
        return None
    cur = zs[-1]
    return {"z": cur, "percentile": sum(1 for x in zs if x <= cur) * 100.0 / len(zs),
            "as_of": pairs[last_idx][0], "n_z": len(zs), "n_pairs": len(pairs)}


# ─────────────────────────────── 组装 ───────────────────────────────

def main():
    series, expect, bad = {}, {}, []
    for code, name in CODES.items():
        dates, closes = rows(name)
        series[code] = {"source": name, "dates": dates, "closes": closes}
        g = {t: gauge(closes, t) for t in TARGETS}
        expect[code] = {
            "vol": vol_annualized(closes),
            "bars_total": len(closes),
            "last_date": dates[-1],
            "gauge": {f"{t:.2f}": g[t] for t in TARGETS},
        }
        # 自核：跟 iter12 §[3] 打印的那张表对上（表里是 1 位小数 / 整数百分比）
        v, w10, w15, w20 = ITER12_TABLE[name]
        got = (round(g[0.10]["weight"] * 100), round(g[0.15]["weight"] * 100),
               round(g[0.20]["weight"] * 100), round(g[0.10]["vol"] * 100, 1))
        want = (w10, w15, w20, v)
        if got != want:
            bad.append(f"  {name}: 这份镜像算出 仓位 {got[:3]} 波动 {got[3]}%，"
                       f"iter12 表里是 {want[:3]} 波动 {want[3]}%")

    dev = {}
    s = {k: (v["dates"], v["closes"]) for k, v in series.items()}
    dev["ratio_XAU_XAG"] = deviation(pair_inner_join(*s["hf_XAU"], *s["hf_XAG"]))
    dev["ratio_XPT_XPD"] = deviation(pair_inner_join(*s["hf_XPT"], *s["hf_XPD"]))
    dev["premium_AU0_XAU"] = deviation(pair_lookup(*s["nf_AU0"], *s["hf_XAU"]))
    dev["premium_AG0_XAG"] = deviation(pair_lookup(*s["nf_AG0"], *s["hf_XAG"]))
    dev = {k: v for k, v in dev.items() if v}

    print("=" * 100)
    print("export_risk_parity — 与 MetalsRisk.kt 同口径的参考实现（夹具生成的同时自核 iter12 §[3]）")
    print("=" * 100)
    if bad:
        print("\n!! 与 iter12 §[3] 的表对不上，说明这份镜像不是回测跑过的那个东西：")
        print("\n".join(bad))
    else:
        print("\n[1] 6 条序列全部与 iter12 §[3] 打印的读数一致：")
    print(f"\n  {'code':7s} {'末日':11s} {'21日波动':>9s} "
          f"{'仓10%':>6s} {'仓15%':>6s} {'仓20%':>6s} {'可用根':>6s} {'次/年':>6s} {'平局':>5s}")
    ties_total = 0
    for code in CODES:
        e = expect[code]
        g = e["gauge"]
        ties_total += g["0.15"]["ties"]
        print(f"  {code:7s} {e['last_date']:11s} {e['vol']*100:>8.1f}% "
              f"{g['0.10']['weight']*100:>5.0f}% {g['0.15']['weight']*100:>5.0f}% "
              f"{g['0.20']['weight']*100:>5.0f}% {g['0.15']['bars']:>6d} "
              f"{g['0.15']['changes_per_year']:>6.1f} {g['0.15']['ties']:>5d}")
    print(f"  （「平局」= target/σ 正好落在 5% 档位中间、half-even 与四舍五入会给不同答案的次数。"
          f"6 条序列合计 {ties_total} 次：这条取整规则在这批数据上一个数都没改动，"
          f"写死它只是不让两份实现分叉）")
    print("\n[2] 偏离读数（z(120) 与全历史分位；对 iter10 §[5] / iter11 §[6]）")
    for k, v in dev.items():
        print(f"  {k:16s} z={v['z']:+.2f}σ 分位 {v['percentile']:5.1f}% "
              f"（{v['n_z']} 个 z，截至 {v['as_of']}）")

    payload = {
        "comment": "由 backtest/metals/export_risk_parity.py 生成，勿手改。"
                   "输入是 metals_data 里与上次回测同一批新浪日线；"
                   "期望值用一份逐行照着 MetalsRisk.kt 写的 Python 算出，"
                   "单测（MetalsRiskTest）拿同一批输入跑 Kotlin 再对一次。",
        "generated_from": "backtest/metals/export_risk_parity.py",
        "params": {"VOL_WINDOW": VOL_WINDOW, "TRADING_DAYS": TRADING_DAYS,
                   "TARGETS": list(TARGETS), "STEP": STEP, "BAND": BAND, "FLOOR": FLOOR,
                   "Z_WINDOW": Z_WINDOW, "MIN_BARS": MIN_BARS},
        "iter12_table": ITER12_TABLE,
        "series": series,
        "expect": expect,
        "deviations": dev,
    }
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as fh:
        json.dump(payload, fh, ensure_ascii=False, separators=(",", ":"))
    print(f"\n[3] 已写出 {os.path.relpath(OUT, os.path.join(HERE, '..', '..'))}"
          f"（{os.path.getsize(OUT) / 1024:.0f} KB）")
    if bad:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
