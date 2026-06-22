package com.sturq.orchardcam

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.hardware.HardwareBuffer
import android.media.ImageReader

/**
 * Approximation of the Apple camera "look": warm white balance, lifted shadows
 * with a slight teal tint, a gentle S-curve and a small saturation boost.
 *
 * This is a parametric grade applied to the already-developed capture. It is NOT
 * a full RAW development pipeline: to actually use the 12-bit headroom of the DNG
 * you would demosaic and tone-map the linear RAW first (NDK / libraw). The knobs
 * below are the tuning surface; adjust them to taste, or swap the shader for a
 * sampled 3D LUT exported from a real "shot on iPhone" profile.
 *
 * ponytail: per-pixel grade only, no local contrast. Add a guided-filter local
 * contrast pass if the flat-but-punchy HDR feel isn't strong enough.
 */
object AppleLook {

    private val SHADER = """
        uniform shader image;

        half3 satAdjust(half3 c, float s) {
            float l = dot(c, half3(0.2126, 0.7152, 0.0722));
            return mix(half3(l), c, s);
        }

        half4 main(float2 coord) {
            half4 src = image.eval(coord);
            half3 c = clamp(src.rgb, 0.0, 1.0);

            // lift shadows slightly
            c = pow(c, half3(0.92));

            // gentle S-curve contrast
            c = c * c * (3.0 - 2.0 * c) * 0.15 + c * 0.85;

            // warm white balance
            c.r = c.r * 1.04;
            c.b = c.b * 0.97;

            // teal tint weighted toward the shadows
            float luma = dot(c, half3(0.2126, 0.7152, 0.0722));
            half3 shadowTint = half3(-0.01, 0.005, 0.02);
            c = c + shadowTint * (1.0 - smoothstep(0.0, 0.5, luma));

            c = satAdjust(c, 1.12);

            return half4(clamp(c, 0.0, 1.0), src.a);
        }
    """.trimIndent()

    fun apply(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height

        val shader = RuntimeShader(SHADER)
        val effect = RenderEffect.createRuntimeShaderEffect(shader, "image")

        val reader = ImageReader.newInstance(
            w, h, PixelFormat.RGBA_8888, 1,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
        )

        val node = RenderNode("orchard").apply {
            setPosition(0, 0, w, h)
            setRenderEffect(effect)
        }
        node.beginRecording().drawBitmap(src, 0f, 0f, null)
        node.endRecording()

        val renderer = HardwareRenderer().apply {
            setSurface(reader.surface)
            setContentRoot(node)
        }
        renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()

        val image = reader.acquireNextImage()
        val buffer = image.hardwareBuffer!!
        val hardware = Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(ColorSpace.Named.SRGB))!!
        val out = hardware.copy(Bitmap.Config.ARGB_8888, false)

        buffer.close()
        image.close()
        renderer.destroy()
        node.discardDisplayList()
        reader.close()
        return out
    }
}
