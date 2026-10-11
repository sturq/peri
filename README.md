<p align="center">
  <img src="docs/icon.png" width="112" alt="Peri">
</p>

<h1 align="center">Peri</h1>

<p align="center">An Android camera that gives saved photos the iPhone look.</p>

<p align="center">
  <img src="https://github.com/sturq/peri/actions/workflows/build.yml/badge.svg" alt="build status">
</p>

---

Peri is a fork of [GrapheneOS Camera](https://github.com/GrapheneOS/Camera) (MIT) that
grades every photo toward Apple's camera rendering as it is saved, wrapped in a periwinkle
palette. The camera UI, capture, and storage are GrapheneOS Camera's; Peri adds a grading
stage and a theme.

## The look

`AppleLook` grades each photo on the GPU (AGSL `RuntimeShader`) when it is written. Every step
comes from measurement, not from taste:

- **More contrast than HDR+.** A global S-curve plus a slight undoing of HDR+'s local
  flattening (bright regions a bit brighter, dark ones a bit darker). Overall brightness and
  saturation stay as they are.
- **Skin and foliage lean toward yellow** by 2 degrees of hue.
- **Backlit faces get lifted** by up to 0.6 EV, found with Android's built-in `FaceDetector`.
- **HDR gain map** (Android 14+). Saved photos are Ultra HDR JPEGs whose gain map has the same
  shape as the iPhone's: +0.5 stops in the shadows rising to +1.35 at white. On an HDR screen
  the photo glows the way iPhone photos do; elsewhere it shows the normal SDR image. Peri's
  own gallery views photos in HDR.

### How the look was measured

`tools/` holds the scripts. 55 pairs of the same scene, shot seconds apart: 38 from
Notebookcheck's London comparison (iPhone 16 Pro, Pixel 9 Pro, full-resolution originals) and
17 from Droid-Life's iPhone 17 Pro vs Pixel 10 Pro shootout. `pairs.py` aligns each pair and
converts the iPhone's Display P3 to sRGB. `pair_stats.py` compares them per trait and
counts in how many pairs the difference points the same way.

Only these held up in both sets: more global contrast (82 % and 88 % of pairs), more local
contrast, and the yellow lean of skin and foliage. Saturation, white balance, sky colour and
shadow tint went both ways. A least-squares fit of a richer model got worse on held-out
scenes, because the two phones expose each scene differently. So `median_fit.py` sets the
contrast where the median difference over all pairs disappears. With the shipped values the
remaining differences are a coin flip (51 to 60 % agreement).

The gain map shape is the median of 25 iPhone 16 Pro gain maps, read with `gainmap_stats.py`
from their ISO 21496-1 metadata. `look_sim.py` is a numpy copy of the shader; a debug
broadcast (`GradeTestReceiver`) runs the real shader on a file so the two can be compared.

The old grade (shadow lift, warm cast, teal shadows, +12 % saturation) moved Pixel photos
*away* from the iPhone on this data, mostly through a pink cast.

## Scope

Peri grades **saved photos**. The live preview shows the raw camera feed, not the look:
applying the grade to the preview and video needs a CameraX effect on the camera stream,
which the current CameraX 1.6 alpha plus the GrapheneOS preview pipeline does not let a
custom effect attach to. That is a known limitation.

It also does not reproduce Apple's capture pipeline. Smart HDR, Deep Fusion, and Night mode
run on dedicated silicon at capture time, fusing a raw multi-frame burst, which an app that
receives a finished JPEG cannot redo. Peri renders the *look* on top of the image the
phone's camera already produced.

## Tuning

The constants sit at the top of the shader in
[`AppleLook.kt`](app/src/main/java/app/grapheneos/camera/AppleLook.kt) and in
`tools/look_sim.py`; change both. `python3 tools/pair_stats.py pairs.npz s_curve=0.3` shows
what a change does against the measured pairs.

## Install

Download the latest APK from the [Releases page](https://github.com/sturq/peri/releases).
It is signed with a bundled debug key, so installing requires allowing unknown sources.
Android 13 (API 33) or newer is needed for the grade, Android 14 for the HDR gain map.

## Build

```
./gradlew assembleDebug
```

CI builds a debug APK on every push (artifacts under the Actions tab), and tagging `v*`
publishes a signed APK to the Releases page via `.github/workflows/release.yml`.

## Credits

Built on [GrapheneOS Camera](https://github.com/GrapheneOS/Camera). Computational
photography background draws on Google's HDR+ and Handheld Multi-Frame Super-Resolution
work.

## License

MIT, inherited from GrapheneOS Camera. See [LICENSE](LICENSE).
