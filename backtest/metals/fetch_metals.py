#!/usr/bin/env python3
"""Fetch daily OHLC for precious metals from public endpoints (no API key).

Sources (both verified reachable without credentials on 2026-11-16):
  * Sina global futures daily K  -> 伦敦金 XAU / 伦敦银 XAG / 铂金 XPT / 钯金 XPD
      https://stock2.finance.sina.com.cn/futures/api/jsonp.php/var%20t=/
        GlobalFuturesService.getGlobalFuturesDailyKLine?symbol=XAU
  * Sina domestic (SHFE) daily K -> 沪金连续 AU0 / 沪银连续 AG0
      https://stock2.finance.sina.com.cn/futures/api/jsonp.php/var%20t=/
        InnerFuturesNewService.getDailyKLine?symbol=AU0
  * Shanghai Gold Exchange official site daily history -> Au99.99 / Au99.95 / Au100g /
        Au(T+D) / mAu(T+D) / Ag(T+D) / Pt99.95
      POST https://www.sge.com.cn/graph/Dailyhq   instid=Au99.99
      body answer: {"time":[["2016-12-19",open,close,low,high], ...]}

Output: metals_data/<name>.json  = {"name","source","unit","rows":[{date,open,high,low,
close,vol,pct,turnover}]}  — same row shape the A-share backtest uses, so the indicator
code can be reused unchanged.

Usage:  python3 fetch_metals.py             # all 13 series
        python3 fetch_metals.py XAU AG0     # argv is a symbol/kind filter

Two things about this data that change how the app must display it
(see README.md §5 / metals_iter7.out):
  * units differ: XAU/XAG/XPT/XPD = USD per oz; AU0 and all SGE gold = CNY per *gram*;
    AG0 / Ag(T+D) = CNY per *kilogram*.
  * dates differ in meaning: SGE folds the 20:00-02:30 night session into the next trading
    day, and the overseas Sina series are stamped on the overseas session, so a foreign move
    shows up in Chinese quotes about one session later (corr(XAU_t, AU0_t)=0.28 vs
    corr(XAU_t, AU0_t+1)=0.66).  Never difference "today's XAU" against "today's AU0".
"""
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request

BASE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(BASE, "metals_data")
os.makedirs(OUT, exist_ok=True)

HDRS = {
    "User-Agent": "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/120 Safari/537.36",
    "Referer": "https://finance.sina.com.cn",
}
SGE_HDRS = dict(HDRS)
SGE_HDRS.update({"Referer": "https://www.sge.com.cn/sjzx/mrhqsj",
                 "X-Requested-With": "XMLHttpRequest",
                 "Content-Type": "application/x-www-form-urlencoded"})


def get(url, headers=None, tries=4, wait=1.5):
    for i in range(tries):
        try:
            req = urllib.request.Request(url, headers=headers or HDRS)
            with urllib.request.urlopen(req, timeout=30) as r:
                return r.read().decode("utf-8", "replace")
        except Exception as e:                              # noqa: BLE001
            if i == tries - 1:
                print(f"  !! {url[:80]} -> {type(e).__name__}: {e}")
                return None
            time.sleep(wait * (i + 1))


def post(url, data, headers=None, tries=4, wait=1.5):
    body = urllib.parse.urlencode(data).encode()
    for i in range(tries):
        try:
            req = urllib.request.Request(url, data=body, headers=headers or SGE_HDRS)
            with urllib.request.urlopen(req, timeout=30) as r:
                return r.read().decode("utf-8", "replace")
        except Exception as e:                              # noqa: BLE001
            if i == tries - 1:
                print(f"  !! {url} {data} -> {type(e).__name__}: {e}")
                return None
            time.sleep(wait * (i + 1))


def jsonp_strip(txt):
    """JSONP 'var t=([...]);' or '/*..*/var t=([...])' -> list."""
    m = re.search(r"=\s*(\[.*\])\s*;?\s*\)?\s*$", txt, re.S)
    if not m:
        m = re.search(r"(\[.*\])", txt, re.S)
    return json.loads(m.group(1)) if m else None


def norm(rows, name, source, unit):
    """rows: list of dicts already keyed date/open/high/low/close/vol -> our row shape."""
    clean = []
    prev = None
    for r in rows:
        try:
            d = str(r["date"])[:10]
            o, h, l, c = (float(r["open"]), float(r["high"]), float(r["low"]), float(r["close"]))
            v = float(r.get("vol") or r.get("volume") or 0.0)
        except (KeyError, TypeError, ValueError):
            continue
        if not (c > 0 and o > 0 and h > 0 and l > 0):
            continue
        if h < l:                       # bad bar from the feed: keep but swap so ATR is sane
            h, l = l, h
        pct = 0.0 if prev in (None, 0) else (c / prev - 1.0) * 100.0
        clean.append({"date": d, "open": o, "high": h, "low": l, "close": c,
                      "vol": v, "pct": round(pct, 4), "turnover": round(c * v, 2)})
        prev = c
    clean.sort(key=lambda x: x["date"])
    ded, seen = [], set()
    for r in clean:
        if r["date"] in seen:
            continue
        seen.add(r["date"])
        ded.append(r)
    return {"name": name, "source": source, "unit": unit, "rows": ded}


def fetch_sina_global(symbol):
    url = ("https://stock2.finance.sina.com.cn/futures/api/jsonp.php/var%20t=/"
           "GlobalFuturesService.getGlobalFuturesDailyKLine?symbol=" + symbol)
    txt = get(url)
    if not txt:
        return None
    raw = jsonp_strip(txt)
    if not raw:
        return None
    return norm(raw, f"sina_{symbol}", "sina:GlobalFuturesService", "USD/oz")


def fetch_sina_domestic(symbol):
    url = ("https://stock2.finance.sina.com.cn/futures/api/jsonp.php/var%20t=/"
           "InnerFuturesNewService.getDailyKLine?symbol=" + symbol)
    txt = get(url)
    if not txt:
        return None
    raw = jsonp_strip(txt)
    if not raw:
        return None
    rows = [{"date": r.get("d"), "open": r.get("o"), "high": r.get("h"),
             "low": r.get("l"), "close": r.get("c"), "vol": r.get("v")} for r in raw]
    # 上期所报价单位：AU0/沪金 = 元/克，AG0/沪银 = 元/千克（别写错，UI 上要照此显示）
    unit = "CNY/g (SHFE)" if symbol.upper().startswith("AU") else "CNY/kg (SHFE)"
    return norm(rows, f"sina_{symbol}", "sina:InnerFuturesNewService", unit)


def fetch_sge(instid):
    txt = post("https://www.sge.com.cn/graph/Dailyhq", {"instid": instid})
    if not txt:
        return None
    try:
        arr = json.loads(txt)["time"]
    except Exception:                                      # noqa: BLE001
        print("  !! SGE parse fail:", txt[:120])
        return None
    # element order as published: [date, open, close, low, high]
    rows = [{"date": a[0], "open": a[1], "close": a[2], "low": a[3], "high": a[4],
             "vol": 0.0} for a in arr if isinstance(a, list) and len(a) >= 5]
    # 上金所：黄金类 = 元/克；Ag(T+D) = 元/千克
    unit = "CNY/kg (SGE)" if instid.upper().startswith("AG") else "CNY/g (SGE)"
    return norm(rows, f"sge_{instid.replace('(', '').replace(')', '')}",
                "sge:graph/Dailyhq", unit)


PLAN = [
    ("global", "XAU"), ("global", "XAG"), ("global", "XPT"), ("global", "XPD"),
    ("domestic", "AU0"), ("domestic", "AG0"),
    ("sge", "Au99.99"), ("sge", "Au99.95"), ("sge", "Au100g"),
    ("sge", "Au(T+D)"), ("sge", "mAu(T+D)"), ("sge", "Ag(T+D)"), ("sge", "Pt99.95"),
]


def main():
    only = set(sys.argv[1:])
    for kind, sym in PLAN:
        if only and sym not in only and kind not in only:
            continue
        fn = {"global": fetch_sina_global, "domestic": fetch_sina_domestic, "sge": fetch_sge}[kind]
        d = fn(sym)
        if not d or not d["rows"]:
            print(f"{kind:9s} {sym:10s} FAILED")
            continue
        path = os.path.join(OUT, d["name"] + ".json")
        with open(path, "w", encoding="utf-8") as f:
            json.dump(d, f, ensure_ascii=False)
        first, last = d["rows"][0]["date"], d["rows"][-1]["date"]
        print(f"{kind:9s} {sym:10s} {len(d['rows']):5d} bars  {first} .. {last}  "
              f"last close={d['rows'][-1]['close']:.3f}  ({d['unit']})")
        time.sleep(0.6)


if __name__ == "__main__":
    main()
