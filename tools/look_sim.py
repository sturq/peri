#!/usr/bin/env python3
"""numpy mirror of the AppleLook AGSL shader, plus the colour helpers the measuring tools share.

PARAMS must equal the constants in app/src/main/java/app/grapheneos/camera/AppleLook.kt.
Face lift and the HDR gain map are not simulated.

    python3 look_sim.py in.jpg out.jpg              # grade like the phone does
    python3 look_sim.py in.jpg --compare phone.jpg  # how far the phone's output is from the sim
"""
import sys

import numpy as np
from PIL import Image, ImageFilter

W = np.array([0.2126, 0.7152, 0.0722])

NAMES = ["k_tone", "m_tone", "s_curve", "gamma", "skin_hue", "green_hue"]
IDENTITY = np.array([0, 0.5, 0, 1, 0, 0], dtype=float)
# chosen with median_fit.py / pair_stats.py on 55 iPhone-vs-Pixel pairs, see README "How the look was measured"
PARAMS = np.array([-0.2, 0.5, 0.25, 0.93, 2.0, -2.0])

SKIN_H, SKIN_W = np.radians(70), np.radians(20)  # OKLab hue of skin/orange and bump width
GREEN_H, GREEN_W = np.radians(142), np.radians(25)  # foliage
SIGMA = 0.019  # local-average blur sigma as a fraction of the short side, as on the phone at 12 MP

M1 = np.array([[0.4122214708, 0.5363325363, 0.0514459929],
               [0.2119034982, 0.6806995451, 0.1073969566],
               [0.0883024619, 0.2817188376, 0.6299787005]])
M2 = np.array([[0.2104542553, 0.7936177850, -0.0040720468],
               [1.9779984951, -2.4285922050, 0.4505937099],
               [0.0259040371, 0.7827717662, -0.8086757660]])
M1i, M2i = np.linalg.inv(M1), np.linalg.inv(M2)


def to_lin(c):
    return np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)


def to_enc(c):
    c = np.clip(c, 0, 1)
    return np.where(c <= 0.0031308, c * 12.92, 1.055 * c ** (1 / 2.4) - 0.055)


def oklab(lin):
    return np.cbrt(np.maximum(lin, 0) @ M1.T) @ M2.T


def from_oklab(lab):
    return (lab @ M2i.T) ** 3 @ M1i.T


def bump(h, c, w):
    d = np.angle(np.exp(1j * (h - c)))  # wrapped hue distance
    return np.exp(-(d / w) ** 2)


def model(c, la, p):
    """c: sRGB-encoded HxWx3 in [0,1]; la: luma of the blurred (local average) image, HxWx1."""
    k, m, s, g, skin, green = p
    lin = to_lin(c)
    y = np.maximum(lin @ W, 1e-6)[..., None]
    ye = to_enc(y)
    # local tone: per-pixel gamma from the neighbourhood (Moroney non-linear masking), keeps 0 and 1
    t = ye ** (2.0 ** (k * (la - m)))
    # global S-curve and midtone gamma
    t = t + s * (t * t * (3 - 2 * t) - t)
    t = np.clip(t, 0, 1) ** g
    lin = lin * (to_lin(t) / y)
    # colour in OKLCh: skin and foliage hues nudged (degrees) toward yellow
    lab = oklab(lin)
    h = np.arctan2(lab[..., 2], lab[..., 1])
    C = np.hypot(lab[..., 1], lab[..., 2])
    h = h + np.radians(skin) * bump(h, SKIN_H, SKIN_W) + np.radians(green) * bump(h, GREEN_H, GREEN_W)
    return to_enc(from_oklab(np.stack([lab[..., 0], C * np.cos(h), C * np.sin(h)], -1)))


def local_avg(c):
    """Luma of the blurred image for a small float array (tools work on 384x288 copies)."""
    im = Image.fromarray((c * 255 + 0.5).astype(np.uint8))
    return np.asarray(im.filter(ImageFilter.GaussianBlur(SIGMA * min(im.size))), dtype=np.float32)[..., :3] @ W / 255.0


def blocks(x, n):
    """(H, W) -> (H/n, n, W/n, n)."""
    h, w = x.shape[0] // n * n, x.shape[1] // n * n
    return x[:h, :w].reshape(h // n, n, w // n, n)


def grade(img, p=PARAMS):
    img = img.convert("RGB")
    # AppleLook.render(): radius = min(w,h)/22 in [8,100]; RenderEffect turns radius into sigma
    r = min(max(min(img.size) / 22.0, 8.0), 100.0)
    la = np.asarray(img.filter(ImageFilter.GaussianBlur(0.57735 * r + 0.5)), dtype=np.float32) @ W / 255.0
    c = np.asarray(img, dtype=np.float32) / 255.0
    return Image.fromarray((model(c, la[..., None], p) * 255 + 0.5).astype(np.uint8))


if __name__ == "__main__":
    assert np.abs(model(np.full((2, 2, 3), 0.5), np.full((2, 2, 1), 0.5), IDENTITY) - 0.5).max() < 1e-6
    out = grade(Image.open(sys.argv[1]))
    if sys.argv[2] == "--compare":
        d = np.abs(np.asarray(out, dtype=np.float32) - np.asarray(Image.open(sys.argv[3]).convert("RGB"), dtype=np.float32))
        print(f"mean abs diff {d.mean():.2f}/255, p99 {np.percentile(d, 99):.1f}, max {d.max():.0f}")
    else:
        out.save(sys.argv[2], quality=95)
