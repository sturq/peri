#!/usr/bin/env python3
"""Which iPhone-vs-Pixel differences hold across scenes? Per-pair deltas, then median and sign agreement.

Only traits that point the same way in most pairs belong in the grade; the rest is scene noise.

    python3 pair_stats.py pairs.npz pairs17.npz                        # raw differences
    python3 pair_stats.py pairs.npz pairs17.npz s_curve=0.25 gamma=0.93  # after grading the Pixel side
"""
import sys

import numpy as np

from look_sim import IDENTITY, NAMES, blocks, local_avg, model, oklab, to_lin

HUES = [("red", 0, 50), ("orange/skin", 50, 90), ("yellow", 90, 115), ("green", 115, 170),
        ("cyan", 170, 230), ("blue/sky", 230, 290), ("purple", 290, 360)]


def describe(c):
    lab = oklab(to_lin(c))
    L, a, b = lab[..., 0], lab[..., 1], lab[..., 2]
    C = np.hypot(a, b)
    h = np.degrees(np.arctan2(b, a)) % 360
    out = {f"L p{q}": np.percentile(L, q) for q in (5, 25, 50, 75, 95)}
    out["local contrast"] = blocks(L, 8).std((1, 3)).mean()
    out["global contrast"] = L.std()
    out["chroma mean"] = C.mean()
    neutral = C < 0.03
    out["neutral a"] = a[neutral].mean() if neutral.sum() > 100 else np.nan
    out["neutral b"] = b[neutral].mean() if neutral.sum() > 100 else np.nan
    shadows = L < np.percentile(L, 20)
    out["shadow a"] = a[shadows].mean()
    out["shadow b"] = b[shadows].mean()
    for name, h0, h1 in HUES:
        m = (h >= h0) & (h < h1) & (C > 0.04)
        out[f"C {name}"] = C[m].mean() if m.sum() > 200 else np.nan
        out[f"h {name}"] = h[m].mean() if m.sum() > 200 else np.nan
    return out


def grade(c, p):
    return model(c, local_avg(c)[..., None], p) if p is not None else c


def deltas(paths, p=None):
    """Per-pair iPhone-minus-Pixel descriptor deltas, Pixel side graded with params p."""
    rows, night = [], []
    for path in paths:
        d = np.load(path)
        for i in range(len(d["ncc"])):
            if d["ncc"][i] < 0.6:
                continue
            a, b = describe(grade(d[f"px{i}"], p)), describe(d[f"ip{i}"])
            rows.append({k: b[k] - a[k] if not k.startswith("C ") else b[k] / a[k] for k in a})
            night.append(d["hour"][i] >= 18)
    return rows, night


def main(paths):
    """pair_stats.py a.npz [b.npz] [name=value ...]: report, optionally after grading the Pixel side."""
    p = IDENTITY.copy()
    for kv in [x for x in paths if "=" in x]:
        k, v = kv.split("=")
        p[NAMES.index(k)] = float(v)
    graded = any("=" in x for x in paths)
    rows, night = deltas([x for x in paths if "=" not in x], p if graded else None)
    keys = rows[0].keys()
    print(f"{len(rows)} pairs ({sum(night)} night). iPhone minus Pixel{' graded ' + str(dict(zip(NAMES, p))) if graded else ''} (C = ratio)")
    print(f"{'trait':18s} {'median':>8s} {'IQR':>15s} {'agree':>6s} {'day med':>8s} {'night med':>9s}")
    for k in keys:
        v = np.array([r[k] for r in rows], dtype=float)
        nd, nn = v[~np.array(night)], v[np.array(night)]
        ref = 1.0 if k.startswith("C ") else 0.0
        ok = ~np.isnan(v)
        agree = np.mean(np.sign(v[ok] - ref) == np.sign(np.nanmedian(v) - ref))
        print(f"{k:18s} {np.nanmedian(v):8.4f} [{np.nanpercentile(v, 25):6.3f},{np.nanpercentile(v, 75):6.3f}] "
              f"{agree:6.0%} {np.nanmedian(nd):8.4f} {np.nanmedian(nn) if len(nn) else np.nan:9.4f}")


if __name__ == "__main__":
    main(sys.argv[1:])
