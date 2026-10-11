#!/usr/bin/env python3
"""Pick s_curve, gamma and k_tone so the MEDIAN iPhone-minus-graded-Pixel tone deltas vanish.

Least-squares fits on these pairs did not generalise (held-out loss got worse): iPhone and
Pixel meter each scene differently, so only effects that point the same way in most pairs are
real. The median ignores the scene noise. Two-fold check first, then a fit on all pairs; ship a
shrunk version of it (look_sim.PARAMS) and confirm with pair_stats.py.

    python3 median_fit.py pairs.npz pairs17.npz
"""
import sys

import numpy as np
from scipy.optimize import minimize

import pair_stats as ps
from look_sim import IDENTITY, NAMES

KEYS = ("global contrast", "local contrast", "L p5", "L p50", "L p95")
FREE = [NAMES.index(n) for n in ("s_curve", "gamma", "k_tone")]


def meds(x, items):
    p = IDENTITY.copy()
    p[FREE] = x
    return np.median([[ip[k] - a[k] for k in KEYS] for px, ip in items
                      for a in [ps.describe(ps.grade(px, p))]], 0)


def fit(items):
    return minimize(lambda x: float(np.sum(meds(x, items) ** 2)), IDENTITY[FREE], method="Nelder-Mead",
                    options={"maxfev": 200, "xatol": 1e-3, "fatol": 1e-9}).x


def main(paths):
    items = []
    for path in paths:
        d = np.load(path)
        items += [(d[f"px{i}"], ps.describe(d[f"ip{i}"])) for i in range(len(d["ncc"])) if d["ncc"][i] >= 0.6]
    fmt = lambda v: " ".join(f"{k}={x:+.4f}" for k, x in zip(KEYS, v))
    for a, b in ((items[0::2], items[1::2]), (items[1::2], items[0::2])):
        x = fit(a)
        print(f"s={x[0]:.3f} gamma={x[1]:.3f} k={x[2]:+.3f} held-out {fmt(meds(x, b))} (identity {fmt(meds(IDENTITY[FREE], b))})", flush=True)
    x = fit(items)
    print(f"ALL: s={x[0]:.4f} gamma={x[1]:.4f} k={x[2]:+.4f} medians {fmt(meds(x, items))}")


if __name__ == "__main__":
    main(sys.argv[1:])
