#!/usr/bin/env python3
"""Export a parity fixture: real bars + the validated Python engine's expected scores.

The Kotlin engine (app/src/main/java/com/stock/analyzer/analysis/) is supposed to be
a faithful port of `new_fn` in final_validate.py. This file freezes that contract as
data so a JVM unit test can check it bar by bar on real prices.
"""
import glob
import json
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from model import _nan, g  # noqa: E402
from final_validate import new_fn  # noqa: E402  (runs its own report on import; harmless)

OUT = os.environ.get(
    "BT_FIXTURE",
    os.path.join(os.path.dirname(HERE), "StockAnalyzer", "app", "src", "test", "resources", "engine_parity.json"),
)
N_STOCKS = 8


def main():
    files = sorted(glob.glob(os.path.join(HERE, "cache", "*.npz")))
    # 取历史最长的几只，外加几只短的，把数据不足分支也覆盖到
    infos = []
    for p in files:
        z = np.load(p, allow_pickle=True)
        infos.append((int(z["n"]), p, str(z["code"])))
    infos.sort(reverse=True)
    picks = [p for _, p, _ in infos[:N_STOCKS]] + [p for _, p, _ in infos[-3:]]

    out = []
    for p in picks:
        z = np.load(p, allow_pickle=True)
        n = int(z["n"])
        F = {k: z[k] for k in z.files}
        for k in ("close", "open", "high", "low", "vol", "ma20", "ma60", "ma10",
                  "ma20_slope", "atr_pct"):
            F[k] = F[k].astype(float)
        bars = [
            {
                "d": str(F["date"][t]),
                "o": float(F["open"][t]),
                "c": float(F["close"][t]),
                "h": float(F["high"][t]),
                "l": float(F["low"][t]),
                "v": int(F["vol"][t]),
            }
            for t in range(n)
        ]
        exp = []
        for t in range(n):
            s, ok = new_fn(F, t)
            exp.append([float(s), 1 if ok else 0])
        out.append({"code": str(F["code"]), "bars": bars, "expected": exp})
        print(f"  {str(F['code'])} n={n}")

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w") as f:
        json.dump({"stocks": out}, f, ensure_ascii=False, separators=(",", ":"))
    print(f"fixture -> {OUT} ({os.path.getsize(OUT)/1e6:.2f} MB, {len(out)} stocks)")


if __name__ == "__main__":
    main()
