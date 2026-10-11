package app.grapheneos.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.Gainmap
import android.graphics.HardwareRenderer
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.hardware.HardwareBuffer
import android.media.ExifInterface
import android.media.FaceDetector
import android.media.ImageReader
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow

/**
 * iPhone look, applied to the JPEG the camera HAL developed (on Pixels that is already a
 * lightweight HDR+ merge).
 *
 * The constants are measured, not guessed: 55 pairs of iPhone 16 Pro / 17 Pro and Pixel 9 Pro /
 * 10 Pro shots of the same scenes (tools/pairs.py). Only differences that point the same way in
 * most pairs went in (tools/pair_stats.py, tools/median_fit.py): the iPhone has more global and
 * local contrast than HDR+, the same saturation, and skin and foliage lean a little toward yellow.
 * tools/look_sim.py mirrors this shader in numpy; keep both in sync by hand.
 *
 * 1. Local tone: per-pixel gamma from the neighbourhood average (Moroney's non-linear masking),
 *    on luminance only. K_TONE < 0 undoes some of HDR+'s flattening: bright regions get a bit
 *    brighter, dark ones a bit darker, black and white stay.
 * 2. Global S-curve with a midtone gamma that keeps the overall brightness.
 * 3. Colour in OKLCh: skin and foliage hues turn 2 degrees toward yellow.
 * 4. Backlit faces get extra exposure, like Apple's person segmentation (framework FaceDetector).
 * 5. An HDR gain map shaped like the iPhone's (Ultra HDR, API 34+): on HDR screens the photo
 *    gets brighter overall and most in the highlights.
 *
 * Not reproducible from one finished JPEG: multi-frame texture fusion, real scene highlights
 * for the gain map, hair/sky/teeth segmentation.
 *
 * ponytail: full-res GPU buffers. If large photos OOM, compute the blur on a downscaled copy
 * and sample it with a scaling local matrix.
 */
object AppleLook {

    private const val TAG = "AppleLook"

    // log2 HDR gain = GM_A + GM_B * luma + GM_C * smoothstep(GM_T, 1, luma), fitted to the median
    // gain per luma decile of 25 iPhone 16 Pro gain maps (tools/gainmap_stats.py): +0.5 stops in
    // the shadows rising to +1.3 at white. The iPhone files hold 1.0 to 2.8 stops of headroom.
    private const val GM_A = 0.49f
    private const val GM_B = 0.45f
    private const val GM_C = 0.42f
    private const val GM_T = 0.7f

    private const val MAX_FACES = 4

    private val SHADER = """
        uniform shader image;
        uniform shader blurred;
        uniform float4 faces[$MAX_FACES];  // centre x, y and radius x, y in pixels; radius 0 = unused
        uniform float4 faceEv;           // exposure lift per face, in stops

        const float3 W = float3(0.2126, 0.7152, 0.0722);

        // tools/look_sim.py PARAMS, same order
        const float K_TONE = -0.2;
        const float M_TONE = 0.5;
        const float S_CURVE = 0.25;
        const float GAMMA = 0.93;
        const float SKIN_HUE = 2.0;    // degrees, toward yellow
        const float GREEN_HUE = -2.0;  // degrees, toward yellow
        const float SKIN_H = 1.2217, SKIN_W = 0.3491;    // 70 and 20 degrees in OKLab hue
        const float GREEN_H = 2.4784, GREEN_W = 0.4363;  // 142 and 25 degrees

        float bump(float h, float c, float w) {
            float d = mod(h - c + 3.14159265, 6.28318531) - 3.14159265;  // wrapped hue distance
            return exp(-(d / w) * (d / w));
        }

        float enc(float y) { return fromLinearSrgb(half3(y)).r; }
        float dec(float y) { return toLinearSrgb(half3(y)).r; }

        float3 oklab(float3 c) {
            float3 lms = float3(
                dot(c, float3(0.4122214708, 0.5363325363, 0.0514459929)),
                dot(c, float3(0.2119034982, 0.6806995451, 0.1073969566)),
                dot(c, float3(0.0883024619, 0.2817188376, 0.6299787005)));
            lms = pow(max(lms, 0.0), float3(1.0 / 3.0));
            return float3(
                dot(lms, float3(0.2104542553, 0.7936177850, -0.0040720468)),
                dot(lms, float3(1.9779984951, -2.4285922050, 0.4505937099)),
                dot(lms, float3(0.0259040371, 0.7827717662, -0.8086757660)));
        }

        float3 linFromOklab(float3 lab) {
            float3 lms = float3(
                lab.x + 0.3963377774 * lab.y + 0.2158037573 * lab.z,
                lab.x - 0.1055613458 * lab.y - 0.0638541728 * lab.z,
                lab.x - 0.0894841775 * lab.y - 1.2914855480 * lab.z);
            lms = lms * lms * lms;
            return float3(
                dot(lms, float3(4.0767416621, -3.3077115913, 0.2309699292)),
                dot(lms, float3(-1.2684380046, 2.6097574011, -0.3413193965)),
                dot(lms, float3(-0.0041960863, -0.7034186147, 1.7076147010)));
        }

        half4 main(float2 p) {
            half4 src = image.eval(p);
            float la = dot(float3(blurred.eval(p).rgb), W);

            float3 lin = float3(toLinearSrgb(src.rgb));
            float y = max(dot(lin, W), 1e-6);
            float ye = enc(y);

            // local tone (keeps 0 and 1), then global S-curve and midtone gamma
            float t = pow(ye, exp2(K_TONE * (la - M_TONE)));
            t = t + S_CURVE * (t * t * (3.0 - 2.0 * t) - t);
            t = pow(clamp(t, 0.0, 1.0), GAMMA);

            // backlit faces
            float ev = 0.0;
            for (int i = 0; i < $MAX_FACES; i++) {
                if (faces[i].z > 0.0) {
                    float d = length((p - faces[i].xy) / faces[i].zw);
                    ev += faceEv[i] * (1.0 - smoothstep(0.6, 1.0, d));
                }
            }
            lin = lin * (dec(t) / y) * exp2(ev);

            // colour in OKLCh: skin and foliage hues nudged toward yellow
            float3 lab = oklab(lin);
            float h = atan(lab.z, lab.y);
            float c = length(lab.yz);
            h = h + radians(SKIN_HUE) * bump(h, SKIN_H, SKIN_W) + radians(GREEN_HUE) * bump(h, GREEN_H, GREEN_W);
            lin = linFromOklab(float3(lab.x, c * cos(h), c * sin(h)));

            return half4(fromLinearSrgb(half3(clamp(lin, 0.0, 1.0))), src.a);
        }
    """.trimIndent()

    /** Grade [jpeg] and return new JPEG bytes with EXIF preserved. Returns [jpeg] unchanged on any failure. */
    fun grade(context: Context, jpeg: ByteArray, quality: Int): ByteArray {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return jpeg
        return try {
            val t = LongArray(6)
            t[0] = SystemClock.elapsedRealtime()
            val src = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return jpeg
            val faces = findFaces(src, ExifInterface(ByteArrayInputStream(jpeg)))
            t[1] = SystemClock.elapsedRealtime()
            val graded = render(src, faces)
            src.recycle()
            t[2] = SystemClock.elapsedRealtime()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                graded.gainmap = gainmap(graded)
            }
            t[3] = SystemClock.elapsedRealtime()

            val tmp = File.createTempFile("grade", ".jpg", context.cacheDir)
            FileOutputStream(tmp).use { graded.compress(Bitmap.CompressFormat.JPEG, quality, it) }
            graded.recycle()
            t[4] = SystemClock.elapsedRealtime()

            copyExif(ByteArrayInputStream(jpeg), tmp)

            val out = tmp.readBytes()
            tmp.delete()
            t[5] = SystemClock.elapsedRealtime()
            Log.i(TAG, "ms: decode+faces ${t[1] - t[0]}, render ${t[2] - t[1]}, gainmap ${t[3] - t[2]}, " +
                    "encode ${t[4] - t[3]}, exif ${t[5] - t[4]}")
            out
        } catch (e: Throwable) {
            Log.w(TAG, "grade failed, keeping original", e)
            jpeg
        }
    }

    /** Grades a small grey frame once, so the first real photo doesn't pay for shader compilation (~6 s cold). */
    fun warmUp(context: Context) {
        val b = Bitmap.createBitmap(256, 192, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
        val jpeg = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        b.recycle()
        grade(context, jpeg, 90)
    }

    private class Face(val x: Float, val y: Float, val rx: Float, val ry: Float, val ev: Float)

    /**
     * Faces darker than the frame get lifted by up to 0.6 EV (half the deficit beyond 0.5 EV).
     * Detection runs upright on a 1/4 copy, since FaceDetector only finds upright frontal faces.
     */
    private fun findFaces(src: Bitmap, exif: ExifInterface): List<Face> {
        val scale = 4
        val sw = (src.width / scale) and 1.inv() // FaceDetector needs an even width
        val small = Bitmap.createScaledBitmap(src, sw, src.height / scale, true)
        // EXIF orientation as "flip horizontally, then rotate" (androidx ExifInterface's convention)
        val o = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val deg = when (o) { 6, 7 -> 90; 3, 4 -> 180; 8, 5 -> 270; else -> 0 }
        val m = Matrix().apply {
            if (o == 2 || o == 4 || o == 5 || o == 7) postScale(-1f, 1f)
            postRotate(deg.toFloat())
        }
        val bounds = RectF(0f, 0f, small.width.toFloat(), small.height.toFloat()).also { m.mapRect(it) }
        m.postTranslate(-bounds.left, -bounds.top)
        val upright = Bitmap.createBitmap(small, 0, 0, small.width, small.height, m, true)
            .let { b -> if (b.width % 2 == 0) b else Bitmap.createBitmap(b, 0, 0, b.width - 1, b.height) }
            .copy(Bitmap.Config.RGB_565, false)

        val found = arrayOfNulls<FaceDetector.Face>(MAX_FACES)
        val n = FaceDetector(upright.width, upright.height, MAX_FACES).findFaces(upright, found)
        upright.recycle()
        if (n == 0) {
            small.recycle()
            return emptyList()
        }

        fun luma(c: Int) = (0.2126f * (c shr 16 and 255) + 0.7152f * (c shr 8 and 255) + 0.0722f * (c and 255)) / 255f
        // frame median from a thumbnail: plain loops run interpreted in a fresh debug process
        val thumb = Bitmap.createScaledBitmap(small, 64, 48, true)
        val tp = IntArray(64 * 48).also { thumb.getPixels(it, 0, 64, 0, 0, 64, 48) }
        thumb.recycle()
        val median = FloatArray(tp.size) { luma(tp[it]) }.also { it.sort() }[tp.size / 2].pow(2.2f).coerceAtLeast(1e-4f)
        val px = IntArray(small.width * small.height)
        small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)

        val inv = Matrix().also { m.invert(it) }
        val sideways = deg % 180 != 0
        return found.take(n).filterNotNull().filter { it.confidence() >= 0.4f }.mapNotNull { f ->
            val e = f.eyesDistance()
            val mid = PointF().also { f.getMidPoint(it) }
            // face centre sits about half an eye distance below the eyes
            val pt = floatArrayOf(mid.x, mid.y + 0.5f * e).also { inv.mapPoints(it) }
            val rx = (if (sideways) 1.7f else 1.3f) * e
            val ry = (if (sideways) 1.3f else 1.7f) * e
            var sum = 0f
            var cnt = 0
            for (y in (pt[1] - ry / 2).toInt()..(pt[1] + ry / 2).toInt()) for (x in (pt[0] - rx / 2).toInt()..(pt[0] + rx / 2).toInt()) {
                if (x in 0 until small.width && y in 0 until small.height) {
                    sum += luma(px[y * small.width + x]).pow(2.2f); cnt++
                }
            }
            if (cnt == 0) return@mapNotNull null
            val deficit = (ln(median / (sum / cnt).coerceAtLeast(1e-4f)) / ln(2f))
            val ev = if (deficit > 0.5f) min(0.6f, 0.5f * deficit) else 0f
            Log.i(TAG, "face at ${pt[0] * scale},${pt[1] * scale} eyes=${e * scale} deficit=$deficit ev=$ev")
            if (ev > 0f) Face(pt[0] * scale, pt[1] * scale, rx * scale, ry * scale, ev) else null
        }.also { small.recycle() }
    }

    private fun render(src: Bitmap, faces: List<Face>): Bitmap {
        val w = src.width
        val h = src.height
        // local-average radius scales with image size (sigma ~1.9 % of the short side), capped for the blur effect
        val radius = (minOf(w, h) / 22f).coerceIn(8f, 100f)
        val blurred = renderNode(w, h) { node ->
            node.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
            node.beginRecording().drawBitmap(src, 0f, 0f, null)
        }

        val shader = RuntimeShader(SHADER)
        shader.setInputShader("image", BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
        shader.setInputShader("blurred", BitmapShader(blurred, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
        val f = FloatArray(4 * MAX_FACES)
        val ev = FloatArray(MAX_FACES)
        faces.take(MAX_FACES).forEachIndexed { i, face ->
            f[4 * i] = face.x; f[4 * i + 1] = face.y; f[4 * i + 2] = face.rx; f[4 * i + 3] = face.ry
            ev[i] = face.ev
        }
        shader.setFloatUniform("faces", f)
        shader.setFloatUniform("faceEv", ev)
        val paint = Paint().apply { this.shader = shader }

        val out = renderNode(w, h) { node ->
            node.beginRecording().drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        }
        blurred.recycle()
        return out
    }

    // gain map value 0..1 = (log2 gain - GM_A) / (GM_B + GM_C), from the graded SDR luma
    private val GAINMAP_SHADER = """
        uniform shader image;
        half4 main(float2 p) {
            float y = dot(float3(image.eval(p).rgb), float3(0.2126, 0.7152, 0.0722));
            float g = ($GM_B * y + $GM_C * smoothstep($GM_T, 1.0, y)) / ($GM_B + $GM_C);
            return half4(half3(g), 1.0);
        }
    """.trimIndent()

    /** Gain map at half size, rendered on the GPU. Grey RGB, so the JPEG's chroma planes stay flat. */
    private fun gainmap(graded: Bitmap): Gainmap {
        val w = graded.width / 2
        val h = graded.height / 2
        val shader = RuntimeShader(GAINMAP_SHADER)
        shader.setInputShader("image", BitmapShader(graded, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            filterMode = BitmapShader.FILTER_MODE_LINEAR
            setLocalMatrix(Matrix().apply { setScale(0.5f, 0.5f) })
        })
        val paint = Paint().apply { this.shader = shader }
        val map = renderNode(w, h) { node ->
            node.beginRecording().drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        }

        val rMin = 2f.pow(GM_A)
        val rMax = 2f.pow(GM_A + GM_B + GM_C)
        return Gainmap(map).apply {
            setRatioMin(rMin, rMin, rMin)
            setRatioMax(rMax, rMax, rMax)
            setGamma(1f, 1f, 1f)
            setEpsilonSdr(1e-5f, 1e-5f, 1e-5f)
            setEpsilonHdr(1e-5f, 1e-5f, 1e-5f)
            minDisplayRatioForHdrTransition = 1f
            displayRatioForFullHdr = rMax
        }
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
        // mutable, so a gain map can be attached
        val out = hw.copy(Bitmap.Config.ARGB_8888, true)

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
