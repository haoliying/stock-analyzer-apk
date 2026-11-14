#!/usr/bin/env python3
"""Iteration 3: tune the ATR risk filter, sweep selectivity, and test whether an
ATR stop-loss improves the outcome a real user would feel.

Signals are computed ONCE per model and reused across every exit rule, so the
comparisons are strictly apples-to-apples.
"""
import numpy as np
from strategy import load_all, build_baseline, MIN_HIST, COST, _nan, g, v_old
from iter2 import V_core, V_full, V_full_atr, V_full_brk

H = 20
LIMIT = lambda code: 0.097 if code.startswith(("30", "68")) else 0.047
stocks = load_all()
DATES = sorted({str(d) for F in stocks for d in F["date"][MIN_HIST:]})
CUTOFF = DATES[int(len(DATES) * 0.7)]
MID = DATES[len(DATES) // 2]
BASELINE = build_baseline(stocks)


def atr_score(mult, base=V_core, knee=0.020):
    def fn(F, t):
        s, ok = base(F, t)
        if not ok:
            return 0.0, False
        a = g(F, "atr_pct", t)
        if mult and not _nan(a):
            s -= max(0.0, (a - knee)) * mult
        return float(np.clip(s, 0, 100)), True
    return fn


def gen_signals(fn, buy=70.0, sell=35.0):
    """(stock_index, t, date, score, side) for every signal, plus fixed-horizon returns."""
    out = []
    for si, F in enumerate(stocks):
        n = int(F["n"])
        c, o = F["close"], F["open"]
        lim = LIMIT(F["code"])
        for t in range(MIN_HIST, n - 1):
            sc, ok = fn(F, t)
            if not ok:
                continue
            side = 1 if sc >= buy else (-1 if sc <= sell else 0)
            if side == 0:
                continue
            eo = o[t + 1]
            if eo <= 0:
                continue
            if side > 0 and F["pct"][t] > 0 and eo >= c[t] * (1 + lim) * 0.995:
                continue
            ret = {}
            for h in (5, 10, 20):
                k = t + 1 + h
                ret[h] = (c[k] / eo - 1.0) - COST if k < n else np.nan
            out.append((si, t, str(F["date"][t]), sc, side, ret))
    return out


def summ(sigs, h=H, oos_only=False, first_only=False):
    sel = [s for s in sigs if s[4] > 0 and not np.isnan(s[5][h])
           and (s[2] >= CUTOFF if oos_only else (s[2] < MID if first_only else True))]
    if len(sel) < 40:
        return None
    v = np.array([s[5][h] for s in sel])
    e = np.array([s[5][h] - BASELINE.get((s[2], h), np.nan) for s in sel
                  if (s[2], h) in BASELINE])
    return dict(n=len(sel), avg=v.mean() * 100, exc=e.mean() * 100 if e.size else np.nan,
                win=(v > 0).mean() * 100, p05=np.percentile(v, 5) * 100,
                p25=np.percentile(v, 25) * 100, bl=(v < -0.10).mean() * 100)


def row(label, s):
    if not s:
        print(f"  {label:30s} (too few)")
        return
    print(f"  {label:30s} n={s['n']:<6d} exc={s['exc']:+5.2f}% avg={s['avg']:+5.2f}% "
          f"win={s['win']:4.1f}% p05={s['p05']:+5.1f}% p25={s['p25']:+5.1f}% >10%loss={s['bl']:4.1f}%")


print("=== 1. ATR (volatility) penalty sweep — core trend model, BUY>=70 ===")
print(f"  {'config':30s} {'n':>6s} {'exc':>7s} {'avg':>7s} {'win':>6s} {'p05':>6s} {'>10%':>6s} | "
      f"{'oosN':>5s} {'oosExc':>7s} {'oosWin':>6s} {'oosP05':>7s} {'oos>10%':>7s}")
cache = {}
for mult in (0, 100, 150, 225, 300, 450, 600):
    sigs = gen_signals(atr_score(mult))
    cache[mult] = sigs
    f_, o_ = summ(sigs), summ(sigs, oos_only=True)
    if f_ and o_:
        print(f"  ATRpen={mult:<6d}{'':16s} {f_['n']:>6d} {f_['exc']:>+6.2f}% {f_['avg']:>+6.2f}% "
              f"{f_['win']:>5.1f}% {f_['p05']:>+5.1f}% {f_['bl']:>5.1f}% | "
              f"{o_['n']:>5d} {o_['exc']:>+6.2f}% {o_['win']:>5.1f}% {o_['p05']:>+6.1f}% {o_['bl']:>6.1f}%")

print("\n=== 2. knee test: where should the ATR penalty start? (pen=300) ===")
for knee in (0.015, 0.020, 0.025, 0.030):
    sigs = gen_signals(atr_score(300, knee=knee))
    f_, o_ = summ(sigs), summ(sigs, oos_only=True)
    if f_ and o_:
        print(f"  knee={knee:.3f}  full exc={f_['exc']:+5.2f}% win={f_['win']:4.1f}% p05={f_['p05']:+5.1f}% "
              f">10%={f_['bl']:4.1f}% | oos exc={o_['exc']:+5.2f}% win={o_['win']:4.1f}% "
              f"p05={o_['p05']:+5.1f}% >10%={o_['bl']:4.1f}%")

print("\n=== 3. selectivity sweep on the risk-filtered model ===")
print(f"  {'model/thresh':30s} {'n':>6s} {'exc':>7s} {'win':>6s} {'p05':>6s} | "
      f"{'oosN':>5s} {'oosExc':>7s} {'oosWin':>6s} {'oosP05':>7s}")
for nm, fn in (("core+ATR300", atr_score(300)), ("full+ATRscores", V_full_atr),
               ("full+ATR+brkVeto", V_full_brk)):
    for bt in (70, 75, 80, 85):
        sigs = gen_signals(fn, buy=float(bt))
        f_, o_ = summ(sigs), summ(sigs, oos_only=True)
        if f_ and o_:
            print(f"  {nm + f' BUY>={bt}':30s} {f_['n']:>6d} {f_['exc']:>+6.2f}% {f_['win']:>5.1f}% "
                  f"{f_['p05']:>+5.1f}% | {o_['n']:>5d} {o_['exc']:>+6.2f}% {o_['win']:>5.1f}% "
                  f"{o_['p05']:>+6.1f}%")

print("\n=== 4. stop-loss / trend-exit simulation on identical entries ===")


def sim_exit(F, t, atr_mult=None, trail=None, break_ma=None, max_days=40):
    """Return net holding-period return with an exit rule applied."""
    n = int(F["n"])
    eo = F["open"][t + 1]
    a = g(F, "atr_pct", t)
    if eo <= 0 or t + 2 >= n:
        return None
    stop = None
    if atr_mult and not _nan(a):
        stop = eo * (1 - atr_mult * a)
    best_exit = None
    end = min(t + 1 + max_days, n - 1)
    for k in range(t + 1, end + 1):
        op, lo, hi, cl = F["open"][k], F["low"][k], F["high"][k], F["close"][k]
        if stop is not None:
            if trail and not _nan(a):
                stop = max(stop, hi * (1 - atr_mult * a))
            if lo <= stop:
                return (min(op, stop) / eo) - 1.0 - COST
        if break_ma is not None:
            m = g(F, "ma20", k)
            if not _nan(m) and cl < m * (1 - break_ma):
                return cl / eo - 1.0 - COST
        if k == min(t + 1 + H, n - 1):
            best_exit = cl / eo - 1.0 - COST
    return best_exit if best_exit is not None else F["close"][end] / eo - 1.0 - COST


def eval_exit(sigs, **kw):
    v = []
    for s in sigs:
        if s[2] >= str(2024):          # need room for the exit window
            F = stocks[s[0]]
            if int(F["n"]) - s[1] < 45:
                continue
            r = sim_exit(F, s[1], **kw)
            if r is not None:
                v.append((s[2], r))
    return v


def esum(label, arr, cutoff=None):
    if not arr:
        print(f"  {label:34s} (none)")
        return
    a = np.array([x[1] for x in arr])
    d = [x[0] for x in arr]
    o = a[[i for i, x in enumerate(d) if x >= CUTOFF]] if cutoff else a
    tail = f" | oos avg={o.mean()*100:+5.2f}% win={(o>0).mean()*100:4.1f}% p05={np.percentile(o,5)*100:+5.1f}% maxloss={o.min()*100:+6.1f}%" if o.size > 40 else ""
    print(f"  {label:34s} n={a.size:<6d} avg={a.mean()*100:+5.2f}% win={(a>0).mean()*100:4.1f}% "
          f"p05={np.percentile(a,5)*100:+5.1f}% worst={a.min()*100:+6.1f}%{tail}")


ref = gen_signals(atr_score(300))
print("  (entries = core trend + ATR300, BUY>=70; exit rule varies)")
esum("hold 20d, no stop", eval_exit(ref), cutoff=True)
for m in (1.5, 2.0, 2.5, 3.0):
    esum(f"stop {m}xATR", eval_exit(ref, atr_mult=m), cutoff=True)
    esum(f"trail stop {m}xATR", eval_exit(ref, atr_mult=m, trail=True), cutoff=True)
for b in (0.02, 0.04):
    esum(f"exit if close<MA20*(1-{b:.2f})", eval_exit(ref, break_ma=b), cutoff=True)
esum("stop 2.5x + MA20 break", eval_exit(ref, atr_mult=2.5, break_ma=0.03), cutoff=True)
esum("trail 2.5x + MA20 break", eval_exit(ref, atr_mult=2.5, trail=True, break_ma=0.03), cutoff=True)

print("\n=== 5. split-half stability, chosen model vs old app ===")
print("  chosen: core trend + ATR300, BUY>=70, 20d hold")
for lab, kw in (("first half", dict(first_only=True)), ("second half/OOS", dict(oos_only=True))):
    row(lab, summ(ref, **kw))
old = gen_signals(v_old)
print("  old_app:")
for lab, kw in (("first half", dict(first_only=True)), ("second half/OOS", dict(oos_only=True))):
    row(lab, summ(old, **kw))
row("old_app full", summ(old))
row("chosen full", summ(ref))
