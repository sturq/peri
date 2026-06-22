package app.grapheneos.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.media.ExifInterface
import android.os.Build
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Approximation of the iPhone "look", applied to the already-developed JPEG that
 * the camera HAL produced.
 *
 * What this does, and why (from the research):
 * - Local tone mapping. The iPhone HDR look comes from local tone mapping: shadows
 *   lifted, highlights held back so every region is well-exposed. Google's HDR+ does
 *   this with exposure fusion (Mertens). We approximate the same idea single-scale:
 *   a large-radius blur of the image is the local average, and each pixel is lifted
 *   where its neighbourhood is dark and compressed where it is bright.
 * - A global S-curve for contrast/pop.
 * - Apple-ish colour: warm undertone, slightly teal shadows, restrained saturation.
 *
 * What this does NOT do: Apple's results rest on raw-domain multi-frame burst fusion
 * that an app receiving a finished JPEG cannot reproduce. We render the look, not the
 * capture.
 *
 * ponytail: single-scale local tone mapping. If high-contrast edges show halos,
 * upgrade to a multi-scale (Laplacian-pyramid / true Mertens) blend.
 * ponytail: full-res GPU buffers. If large photos OOM, compute the blur on a
 * downscaled copy and sample it with a scaling local matrix.
 */
object AppleLook {

    private const val TAG = "AppleLook"

    private val SHADER = """
        uniform shader image;
        uniform shader blurred;

        const half3 W = half3(0.2126, 0.7152, 0.0722);
        float luma(half3 c) { return dot(c, W); }

        half4 main(float2 p) {
            half4 src = image.eval(p);
            half3 c = clamp(src.rgb, 0.0, 1.0);
            float la = clamp(luma(clamp(blurred.eval(p).rgb, 0.0, 1.0)), 0.0, 1.0);

            // local tone mapping: lift shadows / compress highlights by local average
            float lift = 1.0 + 0.35 * (1.0 - smoothstep(0.0, 0.5, la));
            float comp = 1.0 - 0.22 * smoothstep(0.5, 1.0, la);
            c = clamp(c * lift * comp, 0.0, 1.0);

            // global S-curve
            c = c * c * (3.0 - 2.0 * c) * 0.18 + c * 0.82;

            // warm undertone
            c.r = c.r * 1.05;
            c.b = c.b * 0.96;

            // teal weighted to shadows
            float lp = luma(c);
            c = c + half3(-0.012, 0.004, 0.022) * (1.0 - smoothstep(0.0, 0.45, lp));

            // restrained saturation
            float l2 = luma(c);
            c = mix(half3(l2), c, 1.12);

            return half4(clamp(c, 0.0, 1.0), src.a);
        }
    """.trimIndent()

    /** Grade [jpeg] and return new JPEG bytes with EXIF preserved. Returns [jpeg] unchanged on any failure. */
    fun grade(context: Context, jpeg: ByteArray, quality: Int): ByteArray {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return jpeg
        return try {
            val src = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return jpeg
            val graded = render(src.copy(Bitmap.Config.ARGB_8888, false))
            src.recycle()

            val tmp = File.createTempFile("grade", ".jpg", context.cacheDir)
            FileOutputStream(tmp).use { graded.compress(Bitmap.CompressFormat.JPEG, quality, it) }
            graded.recycle()

            copyExif(ByteArrayInputStream(jpeg), tmp)

            val out = tmp.readBytes()
            tmp.delete()
            out
        } catch (e: Throwable) {
            Log.w(TAG, "grade failed, keeping original", e)
            jpeg
        }
    }

    private fun render(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        // local-average radius scales with image size, capped for the blur effect
        val radius = (minOf(w, h) / 22f).coerceIn(8f, 100f)
        val blurred = renderNode(w, h) { node ->
            node.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
            node.beginRecording().drawBitmap(src, 0f, 0f, null)
        }

        val shader = RuntimeShader(SHADER)
        shader.setInputShader("image", BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
        shader.setInputShader("blurred", BitmapShader(blurred, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
        val paint = Paint().apply { this.shader = shader }

        val out = renderNode(w, h) { node ->
            node.beginRecording().drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        }
        blurred.recycle()
        return out
    }

    /** Render a RenderNode (configured by [setup], which must call beginRecording and draw) to a software Bitmap. */
    private fun renderNode(w: Int, h: Int, setup: (RenderNode) -> Unit): Bitmap {
        val reader = ImageReader.newInstance(
            w, h, PixelFormat.RGBA_8888, 1,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
        )
        val node = RenderNode("orchard").apply { setPosition(0, 0, w, h) }
        setup(node)
        node.endRecording()

        val renderer = HardwareRenderer().apply {
            setSurface(reader.surface)
            setContentRoot(node)
        }
        renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()

        val image = reader.acquireNextImage()
        val buffer = image.hardwareBuffer!!
        val hw = Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(ColorSpace.Named.SRGB))!!
        val out = hw.copy(Bitmap.Config.ARGB_8888, false)

        buffer.close()
        image.close()
        renderer.destroy()
        node.discardDisplayList()
        reader.close()
        return out
    }

    private val EXIF_TAGS = arrayOf(
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_SOFTWARE,
        ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FLASH, ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
    )

    private fun copyExif(srcStream: ByteArrayInputStream, dst: File) {
        val from = ExifInterface(srcStream)
        val to = ExifInterface(dst.absolutePath)
        for (tag in EXIF_TAGS) {
            from.getAttribute(tag)?.let { to.setAttribute(tag, it) }
        }
        to.saveAttributes()
    }
}
