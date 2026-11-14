#!/usr/bin/env python3
"""diag_engine — 找出 fast.ret_mat（向量化）与 portsim.trade_rets（逐笔）口径不一致的那几天。"""
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import fast as X  # noqa: E402
import iter25 as Q  # noqa: E402
import portsim as P  # noqa: E402
from model import DATES  # noqa: E402

H = 20
S = Q.S0
valid = X.valid_mat(S, H)
R = X.ret_mat(H)
a = {x[0]: x for x in P.daily(dict(score="new", th=68.0, K=None, H=H))}
b = {x[0]: x for x in Q.rows_rule(th=68.0, fq=None, K=None, H_=H)}


def pick_indices(i):
    """复现 rows_rule 当天选出的股票下标（≥68、全部等权）。"""
    order = np.argsort(np.where(valid[i], S[i], -np.inf))[::-1]
    js = [j for j in order if valid[i, j] and S[i, j] >= 68.0]
    return np.array(js, int)


bad = [d for d in a if abs(a[d][1] - b[d][1]) > 5e-4]
print(f"分歧日 {len(bad)} 天：{bad}")
for d in bad:
    i = DATES.index(d)
    js = pick_indices(i)
    ra = P.trade_rets(i, js, H)
    rb = R[i, js]
    n_a = int(np.isfinite(ra).sum())
    n_b = int(np.isfinite(rb).sum())
    print(f"\n  {d}  i={i}  选出 {len(js)} 只  P.daily={a[d][1]*100:.3f}%({a[d][3]}只)  "
          f"ret_mat={b[d][1]*100:.3f}%({b[d][3]}只)")
    dif = np.nan_to_num(ra - rb)
    for k in np.argsort(-np.abs(dif))[:5]:
        j = js[k]
        print(f"     名称下标 j={j} 只数差 {n_a - n_b}  trade_rets={ra[k]:+.4f}  ret_mat={rb[k]:+.4f}  "
              f"score={S[i, j]:.1f}  UNB={bool(X.UNB[i, j])}  次日开盘={P.MAT['open'][i+1, j]}  "
              f"收盘={P.MAT['close'][i, j]}  到期收盘={P.MAT['close'][min(i+1+H, P.ND-1), j]}")
