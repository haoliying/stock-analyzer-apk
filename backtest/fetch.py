#!/usr/bin/env python3
"""Fetch real A-share daily K-line data from East Money for backtesting."""
import json, os, sys, time, urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed

OUT = os.environ.get("BT_DATA", os.path.join(os.path.dirname(os.path.abspath(__file__)), "data"))
os.makedirs(OUT, exist_ok=True)
HDRS = {"User-Agent": "Mozilla/5.0", "Referer": "https://quote.eastmoney.com"}


def get(url, tries=3):
    for i in range(tries):
        try:
            req = urllib.request.Request(url, headers=HDRS)
            with urllib.request.urlopen(req, timeout=25) as r:
                return json.loads(r.read())
        except Exception as e:
            if i == tries - 1:
                return None
            time.sleep(1)


def universe(n=300):
    """Top-n A-shares by total market cap (f20), all boards."""
    codes, page = [], 1
    while len(codes) < n:
        u = ("https://push2.eastmoney.com/api/qt/clist/get?"
             f"pn={page}&pz=100&po=1&np=1&fltt=2&invt=2&fid=f20"
             "&fs=m:0+t:6,m:0+t:80,m:1+t:2,m:1+t:23,m:0+t:81+s:2048"
             "&fields=f12,f14,f20,f2")
        d = get(u)
        if not d or not d.get("data"):
            break
        rows = d["data"]["diff"]
        if not rows:
            break
        for r in rows:
            c, name, cap = r["f12"], r["f14"], r.get("f20") or 0
            if c.startswith(("68", "30", "60", "00")) and cap and cap > 2e10:
                codes.append((c, name))
        page += 1
        time.sleep(0.15)
        if page > 30:
            break
    return codes[:n]


def secid(code):
    return ("1." if code.startswith(("6", "9")) else "0.") + code


def kline(code, bars=560):
    u = ("https://push2his.eastmoney.com/api/qt/stock/kline/get?"
         f"secid={secid(code)}"
         "&fields1=f1,f2,f3,f4,f5,f6"
         "&fields2=f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61"
         "&klt=101&fqt=1&end=20500101&lmt=%d&rand=%d" % (bars, int(time.time() * 1000) % 99999))
    d = get(u)
    if not d or not d.get("data") or not d["data"].get("klines"):
        return None
    rows = []
    for line in d["data"]["klines"]:
        p = line.split(",")
        if len(p) < 11:
            continue
        rows.append({
            "date": p[0], "open": float(p[1]), "close": float(p[2]),
            "high": float(p[3]), "low": float(p[4]), "vol": float(p[5]),
            "amt": float(p[6]), "pct": float(p[8]), "turnover": float(p[10]),
        })
    return {"code": code, "name": d["data"].get("name", ""), "rows": rows}


def main():
    uni = universe(int(sys.argv[1]) if len(sys.argv) > 1 else 300)
    print(f"universe: {len(uni)} stocks", flush=True)
    got, failed = 0, 0
    with ThreadPoolExecutor(max_workers=8) as ex:
        futs = {ex.submit(kline, c): (c, n) for c, n in uni}
        for i, f in enumerate(as_completed(futs), 1):
            c, n = futs[f]
            try:
                r = f.result()
            except Exception:
                r = None
            if r and len(r["rows"]) >= 200:
                with open(f"{OUT}/{c}.json", "w") as fh:
                    json.dump(r, fh)
                got += 1
            else:
                failed += 1
            if i % 40 == 0:
                print(f"  {i}/{len(uni)} ok={got} fail={failed}", flush=True)
    print(f"DONE ok={got} fail={failed}", flush=True)


if __name__ == "__main__":
    main()
