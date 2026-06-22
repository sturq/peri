# OrchardCam

A fork of [GrapheneOS Camera](https://github.com/GrapheneOS/Camera) that applies an
iPhone-style "look" to every photo as it is saved.

The camera UI, capture, and storage are GrapheneOS Camera's (MIT licensed). The only
addition is a grading stage: when a photo is saved, the developed JPEG is run through
`AppleLook` before it is written.

## The grade

From researching Apple's pipeline and Google's openly documented HDR+: the recognizable
iPhone look is mostly **local tone mapping** (shadows lifted, highlights held back so
every region is well-exposed) plus Apple's colour rendering (warm undertone, slightly
cool shadows, restrained saturation). `AppleLook` approximates this on the GPU:

- Local tone mapping via a large-radius blur as the local average, lifting shadows and
  compressing highlights per region (a single-scale take on HDR+'s exposure fusion).
- A global S-curve for contrast.
- Warm undertone, teal-leaning shadows, restrained saturation.

It runs on Android 13+ (AGSL `RuntimeShader`); on older versions the photo is saved
ungraded.

## What it is not

It does not reproduce Apple's pipeline. The iPhone's quality comes from raw-domain
multi-frame burst fusion (Smart HDR, Deep Fusion, Night mode) done on dedicated silicon
at capture time, which an app receiving a finished JPEG cannot reproduce. OrchardCam
renders the *look* on top of the image your phone's camera already produced.

## Tuning

The grade is one AGSL shader plus a few constants in
`app/src/main/java/app/grapheneos/camera/AppleLook.kt`. Adjust the shadow-lift,
highlight-compression, undertone and saturation values, or replace the shader with a
sampled 3D LUT.

## Build

CI (`.github/workflows/build.yml`) builds a debug APK on every push.
