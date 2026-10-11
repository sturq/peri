#!/usr/bin/env python3
"""Measure the HDR gain maps inside iPhone JPEGs (ISO 21496-1 metadata in the MPF second image).

Prints per file the metadata and the mean log2 gain per SDR luma bin, so a synthetic gain map
can copy the shape.

    python3 gainmap_stats.py london/IMG_*.JPG
"""
import io
import struct
import sys

import numpy as np
from PIL import Image, ImageFile, ImageFilter

ImageFile.LOAD_TRUNCATED_IMAGES = True
ISO = b"urn:iso:std:iso:ts:21496:-1\0"


def segments(jpg):
    i = 2
    while i + 4 <= len(jpg) and jpg[i] == 0xFF:
        mk = jpg[i + 1]
        if mk == 0xDA:
            return
        n = struct.unpack(">H", jpg[i + 2:i + 4])[0]
        yield mk, jpg[i + 4:i + 2 + n]
        i += 2 + n


def iso_meta(gm_jpg):
    for mk, data in segments(gm_jpg):
        if mk == 0xE2 and data.startswith(ISO) and len(data) > len(ISO) + 8:
            b = data[len(ISO):]
            minv, wv, flags = struct.unpack(">HHB", b[:5])
            o = 5
            assert not flags & 0x80, "multichannel not handled"
            if flags & 0x08:  # common denominator
                den, bh, ah = struct.unpack(">III", b[o:o + 12]); o += 12
                gmin, gmax, gam, ob, oa = struct.unpack(">iiIii", b[o:o + 20])
                return dict(base_headroom=bh / den, alt_headroom=ah / den, gmin=gmin / den, gmax=gmax / den,
                            gamma=gam / den, off_base=ob / den, off_alt=oa / den)
            bh, bhd, ah, ahd = struct.unpack(">IIII", b[o:o + 16]); o += 16
            v = struct.unpack(">iIiIIIiIiI", b[o:o + 40])
            return dict(base_headroom=bh / bhd, alt_headroom=ah / ahd, gmin=v[0] / v[1], gmax=v[2] / v[3],
                        gamma=v[4] / v[5], off_base=v[6] / v[7], off_alt=v[8] / v[9])
    return None


def main(paths):
    W = np.array([0.2126, 0.7152, 0.0722])
    bins = np.linspace(0, 1, 11)
    rows = []
    for p in paths:
        im = Image.open(p)
        offs = getattr(im, "_MpoImageFile__mpoffsets", None)
        if not offs or len(offs) < 2:
            continue
        raw = open(p, "rb").read()
        meta = iso_meta(raw[offs[1]:])
        if not meta:
            continue
        v = np.asarray(Image.open(io.BytesIO(raw[offs[1]:])).convert("L"), dtype=np.float32) / 255
        base = Image.open(p).convert("RGB").resize(v.shape[::-1], Image.BOX)
        ye = np.asarray(base, dtype=np.float32) @ W / 255  # P3-encoded luma, close enough for shape
        la = np.asarray(base.filter(ImageFilter.GaussianBlur(0.019 * min(base.size))), dtype=np.float32) @ W / 255
        g = meta["gmin"] + (meta["gmax"] - meta["gmin"]) * v ** (1 / meta["gamma"])
        prof = [g[(ye >= a) & (ye < b)].mean() if ((ye >= a) & (ye < b)).sum() > 50 else np.nan
                for a, b in zip(bins[:-1], bins[1:])]
        rows.append(prof)
        corr = np.corrcoef(g.ravel(), np.stack([ye.ravel(), la.ravel()]))[0, 1:]
        print(f"{p.split('/')[-1]:16s} {v.shape[1]}x{v.shape[0]} gmin={meta['gmin']:+.2f} gmax={meta['gmax']:.2f} "
              f"gamma={meta['gamma']:.2f} alt={meta['alt_headroom']:.2f} mean={g.mean():+.2f} "
              f"p99={np.percentile(g, 99):.2f} corr(ye,la)={corr[0]:.2f},{corr[1]:.2f}")
        print("   log2 gain by luma decile: " + " ".join(f"{x:+.2f}" for x in prof))
    r = np.nanmedian(np.array(rows), 0)
    print("MEDIAN log2 gain by luma decile: " + " ".join(f"{x:+.2f}" for x in r))


if __name__ == "__main__":
    main(sys.argv[1:])
