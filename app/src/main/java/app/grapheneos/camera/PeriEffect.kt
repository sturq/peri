package app.grapheneos.camera

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import androidx.camera.core.CameraEffect
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import androidx.core.util.Consumer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executor

/**
 * Real-time iPhone-look grade for the live preview and video, via a CameraX
 * CameraEffect / SurfaceProcessor that renders the camera's external texture
 * through a GLSL grade.
 *
 * The grade matches the per-pixel part of [AppleLook] (S-curve + warm undertone +
 * teal shadows + saturation) so the preview looks like the saved photo. It omits
 * AppleLook's local tone mapping (that needs a per-frame blur, too heavy for a
 * real-time path here) so stills are very slightly more "HDR" than the preview.
 *
 * ponytail: per-pixel grade only on the live path. Add a downsampled multi-tap
 * blur for local tone mapping if the preview/photo mismatch is noticeable.
 */
object PeriEffect {
    private var effect: CameraEffect? = null

    /** PREVIEW + VIDEO only; stills stay on AppleLook in ImageSaver. */
    fun get(): CameraEffect {
        effect?.let { return it }
        return GradeEffect(GradeProcessor()).also { effect = it }
    }
}

private class GradeEffect(processor: GradeProcessor) : CameraEffect(
    CameraEffect.PREVIEW or CameraEffect.VIDEO_CAPTURE,
    processor.executor,
    processor,
    Consumer { t -> Log.w(TAG, "effect error", t) }
)

private const val TAG = "PeriEffect"

private val FRAGMENT_SHADER = """
    #extension GL_OES_EGL_image_external : require
    precision mediump float;
    varying vec2 vTexCoord;
    uniform samplerExternalOES sTexture;
    const vec3 W = vec3(0.2126, 0.7152, 0.0722);
    void main() {
        vec3 c = clamp(texture2D(sTexture, vTexCoord).rgb, 0.0, 1.0);
        c = c * c * (3.0 - 2.0 * c) * 0.18 + c * 0.82;
        c.r = c.r * 1.05;
        c.b = c.b * 0.96;
        float lp = dot(c, W);
        c = c + vec3(-0.012, 0.004, 0.022) * (1.0 - smoothstep(0.0, 0.45, lp));
        float l2 = dot(c, W);
        c = mix(vec3(l2), c, 1.12);
        gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
    }
""".trimIndent()

private val VERTEX_SHADER = """
    uniform mat4 uTexMatrix;
    attribute vec4 aPosition;
    attribute vec4 aTexCoord;
    varying vec2 vTexCoord;
    void main() {
        gl_Position = aPosition;
        vTexCoord = (uTexMatrix * aTexCoord).xy;
    }
""".trimIndent()

private class GradeProcessor : SurfaceProcessor {

    private val thread = HandlerThread("PeriGL").apply { start() }
    private val handler = Handler(thread.looper)
    val executor = Executor { handler.post(it) }

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var initialized = false

    private var program = 0
    private var aPosition = 0
    private var aTexCoord = 0
    private var uTexMatrix = 0
    private var texId = 0

    private var inputTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null
    private val outputs = HashMap<SurfaceOutput, EGLSurface>()

    private val vertexBuffer: FloatBuffer = floatBuffer(
        floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    )
    private val texBuffer: FloatBuffer = floatBuffer(
        floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
    )
    private val texMatrix = FloatArray(16)

    override fun onInputSurface(request: SurfaceRequest) {
        handler.post {
            ensureEgl()
            inputTexture?.release()
            inputSurface?.release()

            val st = SurfaceTexture(texId)
            st.setDefaultBufferSize(request.resolution.width, request.resolution.height)
            st.setOnFrameAvailableListener({ handler.post { drawFrame() } }, handler)
            val surface = Surface(st)
            inputTexture = st
            inputSurface = surface

            request.provideSurface(surface, executor) {
                st.setOnFrameAvailableListener(null)
                surface.release()
                st.release()
                if (inputSurface === surface) {
                    inputSurface = null
                    inputTexture = null
                }
            }
        }
    }

    override fun onOutputSurface(surfaceOutput: SurfaceOutput) {
        handler.post {
            ensureEgl()
            val surface = surfaceOutput.getSurface(executor) {
                outputs.remove(surfaceOutput)?.let { destroyWindow(it) }
                surfaceOutput.close()
            }
            val window = createWindow(surface)
            if (window != null) outputs[surfaceOutput] = window
        }
    }

    private fun drawFrame() {
        val st = inputTexture ?: return
        try {
            st.updateTexImage()
        } catch (e: Exception) {
            return
        }
        st.getTransformMatrix(texMatrix)

        for ((output, window) in outputs) {
            if (!EGL14.eglMakeCurrent(eglDisplay, window, window, eglContext)) continue
            val size = output.size
            val matrix = FloatArray(16)
            output.updateTransformMatrix(matrix, texMatrix)

            GLES20.glViewport(0, 0, size.width, size.height)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(program)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)

            GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, matrix, 0)

            GLES20.glEnableVertexAttribArray(aPosition)
            GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
            GLES20.glEnableVertexAttribArray(aTexCoord)
            GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, texBuffer)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            GLES20.glDisableVertexAttribArray(aPosition)
            GLES20.glDisableVertexAttribArray(aTexCoord)

            EGLExt.eglPresentationTimeANDROID(eglDisplay, window, st.timestamp)
            EGL14.eglSwapBuffers(eglDisplay, window)
        }
    }

    // --- EGL / GL setup ---

    private fun ensureEgl() {
        if (initialized) return
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1)

        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, num, 0)
        eglConfig = configs[0]

        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)

        // a 1x1 pbuffer so we can make the context current before any output exists
        val pbuffer = EGL14.eglCreatePbufferSurface(
            eglDisplay, eglConfig, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0
        )
        EGL14.eglMakeCurrent(eglDisplay, pbuffer, pbuffer, eglContext)

        program = buildProgram()
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        texId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        initialized = true
    }

    private fun createWindow(surface: Surface): EGLSurface? {
        return try {
            EGL14.eglCreateWindowSurface(
                eglDisplay, eglConfig, surface, intArrayOf(EGL14.EGL_NONE), 0
            )
        } catch (e: Exception) {
            Log.w(TAG, "createWindow failed", e)
            null
        }
    }

    private fun destroyWindow(window: EGLSurface) {
        EGL14.eglDestroySurface(eglDisplay, window)
    }

    private fun buildProgram(): Int {
        val vs = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vs)
        GLES20.glAttachShader(p, fs)
        GLES20.glLinkProgram(p)
        val status = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            throw RuntimeException("link failed: ${GLES20.glGetProgramInfoLog(p)}")
        }
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            throw RuntimeException("compile failed: ${GLES20.glGetShaderInfoLog(shader)}")
        }
        return shader
    }

    private fun floatBuffer(data: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply { put(data); position(0) }
}
