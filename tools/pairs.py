#!/usr/bin/env python3
"""Pair Pixel/iPhone shots of the same scene by capture time, align them and cache small copies.

Input: a folder with the Notebookcheck London set (PXL_*.jpg from a Pixel 9 Pro, IMG_*.JPG from
an iPhone 16 Pro, shot seconds apart). Output: pairs.npz with aligned 384x288 float32 sRGB-encoded
arrays (iPhone converted from Display P3 to sRGB) plus the iPhone gain map at the same size.

    python3 pairs.py london/ pairs.npz                 # Notebookcheck London set, paired by time
    python3 pairs.py dl17/ pairs17.npz --by-number     # Droid-Life 17 Pro vs Pixel 10 Pro set
"""
import io
import re
import sys
from datetime import datetime
from pathlib import Path

import numpy as np
from PIL import Image, ImageCms, ImageFile, ImageOps

ImageFile.LOAD_TRUNCATED_IMAGES = True  # a few London files end a few bytes early

SRGB = ImageCms.createProfile("sRGB")
OUT = (384, 288)


def meta(p):
    im = Image.open(p)
    ex = im.getexif()
    sub = ex.get_ifd(0x8769)
    t = datetime.strptime(sub.get(36867) or ex.get(306), "%Y:%m:%d %H:%M:%S")
    return t, float(sub.get(41989) or 0)


def load_srgb(p):
    im = Image.open(p)
    icc = im.info.get("icc_profile")
    base = ImageOps.exif_transpose(im).convert("RGB")
    if icc:
        prof = ImageCms.ImageCmsProfile(io.BytesIO(icc))
        # the description is UTF-16 inside the profile, so ask lcms instead of searching the bytes
        if "sRGB" not in ImageCms.getProfileDescription(prof):
            base = ImageCms.profileToProfile(base, prof, SRGB, renderingIntent=ImageCms.Intent.RELATIVE_COLORIMETRIC)
    return base, im


def gainmap(im, size):
    """Second MPF image (the gain map), rotated like the base. None if absent."""
    if getattr(im, "n_frames", 1) < 2:
        return None
    im.seek(1)
    g = im.convert("L")
    im.seek(0)
    o = im.getexif().get(274, 1)
    g = {3: g.transpose(Image.ROTATE_180), 6: g.transpose(Image.ROTATE_270),
         8: g.transpose(Image.ROTATE_90)}.get(o, g)
    return g.resize(size, Image.BILINEAR)


def gray(im, w):
    h = round(im.height * w / im.width)
    return np.asarray(im.convert("L").resize((w, h), Image.BILINEAR), dtype=np.float32)


def align(px, ip):
    """Boxes (Pixel, iPhone) in full-res pixels covering the same scene area.

    Searches the Pixel scale, then the shift by phase correlation on 256-wide copies.
    """
    a = gray(ip, 256)
    best = None
    for s in np.linspace(0.8, 1.25, 19):
        b = gray(px, round(256 * s))
        h, w = min(a.shape[0], b.shape[0]), min(a.shape[1], b.shape[1])
        oa = np.array([(a.shape[0] - h) // 2, (a.shape[1] - w) // 2])
        ob = np.array([(b.shape[0] - h) // 2, (b.shape[1] - w) // 2])
        A = a[oa[0]:oa[0] + h, oa[1]:oa[1] + w]
        B = b[ob[0]:ob[0] + h, ob[1]:ob[1] + w]
        A = A - A.mean()
        B = B - B.mean()
        r = np.abs(np.fft.ifft2(np.fft.fft2(A) * np.conj(np.fft.fft2(B)))) / (np.sqrt((A * A).sum() * (B * B).sum()) + 1e-6)
        k = np.array(np.unravel_index(r.argmax(), r.shape))
        d = np.where(k < np.array([h, w]) // 2, k, k - np.array([h, w]))
        if best is None or r.max() > best[0]:
            best = (r.max(), s, ob - oa - d, a.shape, b.shape)
    score, s, t, ash, bsh = best
    # iPhone small coords u map to Pixel small coords u + t; keep the overlap
    lo = np.maximum(0, -t)
    hi = np.minimum(ash, np.array(bsh) - t)
    fi, fp = ip.width / ash[1], px.width / bsh[1]
    ib = (lo[1] * fi, lo[0] * fi, hi[1] * fi, hi[0] * fi)
    pb = ((lo[1] + t[1]) * fp, (lo[0] + t[0]) * fp, (hi[1] + t[1]) * fp, (hi[0] + t[0]) * fp)
    clip = lambda bx, im: (max(bx[0], 0), max(bx[1], 0), min(bx[2], im.width), min(bx[3], im.height))
    return score, s, clip(pb, px), clip(ib, ip)


def by_time(files):
    """Notebookcheck naming: IMG_* (iPhone) and PXL_* (Pixel), paired by capture time and focal length."""
    ips = [(p, *meta(p)) for p in files if p.name.startswith("IMG")]
    pxs = [(p, *meta(p)) for p in files if p.name.startswith("PXL")]
    for ipath, it, ifl in ips:
        ppath, pt, pfl = min(pxs, key=lambda x: abs((x[1] - it).total_seconds()))
        dt = (pt - it).total_seconds()
        if abs(dt) > 90 or not ifl or not pfl or max(ifl, pfl) / min(ifl, pfl) > 1.35:
            print(f"skip {ipath.name}: dt={dt:.0f}s focal {ifl}/{pfl}")
            continue
        yield ipath, ppath, it.hour, dt


def by_number(files):
    """Droid-Life naming: iPhone-17-Pro-Camera-Samples-N[-e...].jpg and Pixel-10-Pro-Camera-Samples-N.jpg."""
    num = lambda p: int(re.search(r"Samples-(\d+)", p.name).group(1))
    pxs = {num(p): p for p in files if p.name.startswith("Pixel")}
    for ipath in files:
        if ipath.name.startswith("iPhone") and num(ipath) in pxs:
            yield ipath, pxs[num(ipath)], 12, 0


def main(folder, out):
    files = sorted(Path(folder).glob("*.[jJ][pP][gG]"))
    rows = []
    for ipath, ppath, hour, dt in (by_number if "--by-number" in sys.argv else by_time)(files):
        ip, iraw = load_srgb(ipath)
        px, _ = load_srgb(ppath)
        if (ip.width > ip.height) != (px.width > px.height):
            print(f"skip {ipath.name}: orientation differs")
            continue
        score, s, pb, ib = align(px, ip)
        size = OUT if ip.width > ip.height else OUT[::-1]
        a = np.asarray(px.resize(size, Image.LANCZOS, box=pb), dtype=np.float32) / 255
        b = np.asarray(ip.resize(size, Image.LANCZOS, box=ib), dtype=np.float32) / 255
        ncc = np.corrcoef(a.mean(2).ravel(), b.mean(2).ravel())[0, 1]
        gm = gainmap(iraw, ip.size)
        g = np.asarray(gm.resize(size, Image.BILINEAR, box=ib), dtype=np.float32) / 255 if gm else None
        print(f"{ipath.name} <-> {ppath.name} dt={dt:.0f}s scale={s:.3f} peak={score:.3f} ncc={ncc:.3f} gm={'y' if gm else 'n'}")
        rows.append((ipath.name, ppath.name, hour, ncc, a, b, g))
    np.savez_compressed(out, names=np.array([[r[0], r[1]] for r in rows]),
                        hour=np.array([r[2] for r in rows]), ncc=np.array([r[3] for r in rows]),
                        **{f"px{i}": r[4] for i, r in enumerate(rows)},
                        **{f"ip{i}": r[5] for i, r in enumerate(rows)},
                        **{f"gm{i}": r[6] for i, r in enumerate(rows) if r[6] is not None})


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
