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

The recognizable iPhone rendering is mostly local tone mapping plus Apple's colour science.
That comes from researching Apple's pipeline alongside Google's openly documented HDR+,
where the strongest published detail lives. `AppleLook` approximates it on the GPU
(AGSL `RuntimeShader`) when a photo is written:

- **Local tone mapping.** A large-radius blur of the frame is used as a per-region average,
  lifting shadows where the neighbourhood is dark and holding back highlights where it is
  bright. This is a single-scale take on HDR+'s exposure fusion, and it is the main reason
  iPhone photos read as "HDR".
- **Global S-curve** for contrast.
- **Colour.** Warm undertone, slightly teal shadows, restrained saturation.

The grade is injected at one point in `capturer/ImageSaver.kt`: the developed JPEG is
decoded, graded, re-encoded, and its EXIF is preserved. It runs on Android 13+ (for the
AGSL shader); older versions save the photo ungraded.

## Theme

The sturq periwinkle palette (base `#2A3042`, primary `#B9C5EE`) is mapped onto the
Material 3 colour tokens and the camera's accent colours, with a periwinkle aperture
launcher icon.

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

The grade is one AGSL shader plus a handful of constants in
[`AppleLook.kt`](app/src/main/java/app/grapheneos/camera/AppleLook.kt). Adjust the
shadow-lift, highlight-compression, undertone, and saturation values, or replace the shader
with a sampled 3D LUT.

## Build

```
./gradlew assembleDebug
```

CI builds a debug APK on every push; grab it from the latest run's artifacts under the
Actions tab. Android 13 (API 33) or newer is recommended so the grade is applied.

## Credits

Built on [GrapheneOS Camera](https://github.com/GrapheneOS/Camera). Computational
photography background draws on Google's HDR+ and Handheld Multi-Frame Super-Resolution
work.

## License

MIT, inherited from GrapheneOS Camera. See [LICENSE](LICENSE).
