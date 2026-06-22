# OrchardCam

A minimal Android camera that captures RAW (DNG) and saves an "Apple-look" graded
JPEG alongside it. The grade approximates the recognizable iPhone rendering: warm
white balance, lifted shadows with a slight teal tint, a gentle S-curve and a
small saturation boost.

## What it does

- Camera2 capture on a RAW-capable back camera.
- Saves the unprocessed sensor data as `.dng` (via `DngCreator`) to
  `Pictures/OrchardCam`. Full headroom, openable in Lightroom or any RAW editor.
- Decodes the captured JPEG, applies the look (AGSL `RuntimeShader`) and saves
  that too.

## What it does not do

It does not replicate Apple's actual pipeline. Apple fuses multiple bracketed
exposures and runs per-region machine learning at capture time on the ISP and
Neural Engine; none of that is available to a third-party Android app.

It also does not yet develop the RAW. The grade is applied to the already
developed JPEG, not to the linear 12-bit sensor data, so it does not use the
DNG's extra headroom. Grading the RAW directly needs an on-device demosaic and
tone-map step (NDK / libraw). That is the next step, marked in
`MainActivity.kt` and `AppleLook.kt`.

## Tuning the look

The grade lives in `AppleLook.kt` as a single AGSL shader. Adjust the white
balance, shadow tint, contrast and saturation constants, or replace the shader
with a sampled 3D LUT exported from a real camera profile.

## Build

CI (`.github/workflows/build.yml`) builds a debug APK on every push and uploads
it as an artifact. To build locally with the Android SDK installed:

```
gradle assembleDebug
```

Requires Android 13 (API 33) or newer, for the AGSL `RuntimeShader` grade.

## Status

Early. The code compiles in CI but capture has not been verified on a physical
device yet. RAW plus JPEG plus preview is a stream combination that depends on
the device's camera support level.
