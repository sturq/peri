package com.sturq.orchardcam

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.sturq.orchardcam.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var manager: CameraManager
    private lateinit var cameraId: String
    private lateinit var characteristics: CameraCharacteristics

    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var jpegReader: ImageReader? = null
    private var rawReader: ImageReader? = null
    private var rawAvailable = false
    private var sensorOrientation = 90

    private lateinit var previewSize: Size
    private lateinit var jpegSize: Size

    private val thread = HandlerThread("camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executors.newSingleThreadExecutor()

    // pending pair for writing a DNG once both the RAW frame and its result arrive
    private val lock = Any()
    private var pendingResult: TotalCaptureResult? = null
    private var pendingRaw: Image? = null

    private val permission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) openWhenReady() else finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        manager = getSystemService(CAMERA_SERVICE) as CameraManager
        binding.captureButton.setOnClickListener { capture() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            openWhenReady()
        } else {
            permission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun openWhenReady() {
        if (binding.preview.isAvailable) {
            openCamera()
        } else {
            binding.preview.surfaceTextureListener =
                object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) =
                        openCamera()

                    override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
                    override fun onSurfaceTextureDestroyed(s: SurfaceTexture) = true
                    override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
                }
        }
    }

    private fun pickCamera(): String {
        for (id in manager.cameraIdList) {
            val c = manager.getCameraCharacteristics(id)
            if (c.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue
            val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
            if (caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)) return id
        }
        // fall back to the first back-facing camera, else the first camera
        return manager.cameraIdList.firstOrNull {
            manager.getCameraCharacteristics(it)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: manager.cameraIdList.first()
    }

    @Suppress("MissingPermission")
    private fun openCamera() {
        cameraId = pickCamera()
        characteristics = manager.getCameraCharacteristics(cameraId)
        sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        jpegSize = map.getOutputSizes(ImageFormat.JPEG).maxByOrNull { it.width.toLong() * it.height }!!
        val rawSizes = map.getOutputSizes(ImageFormat.RAW_SENSOR)
        rawAvailable = rawSizes != null && rawSizes.isNotEmpty()
        previewSize = map.getOutputSizes(SurfaceTexture::class.java)
            .filter { it.width <= 1920 }
            .maxByOrNull { it.width.toLong() * it.height } ?: Size(1280, 720)

        jpegReader = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2)
            .apply { setOnImageAvailableListener({ onJpeg(it) }, handler) }
        if (rawAvailable) {
            val raw = rawSizes!!.maxByOrNull { it.width.toLong() * it.height }!!
            rawReader = ImageReader.newInstance(raw.width, raw.height, ImageFormat.RAW_SENSOR, 2)
                .apply { setOnImageAvailableListener({ onRaw(it) }, handler) }
        }

        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                camera = device
                startSession()
            }

            override fun onDisconnected(device: CameraDevice) {
                device.close(); camera = null
            }

            override fun onError(device: CameraDevice, error: Int) {
                device.close(); camera = null
            }
        }, handler)
    }

    private fun startSession() {
        val texture = binding.preview.surfaceTexture!!
        texture.setDefaultBufferSize(previewSize.width, previewSize.height)
        val previewSurface = Surface(texture)

        val outputs = mutableListOf(
            OutputConfiguration(previewSurface),
            OutputConfiguration(jpegReader!!.surface)
        )
        rawReader?.let { outputs.add(OutputConfiguration(it.surface)) }

        val config = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR, outputs, executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    val request = camera!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                    request.addTarget(previewSurface)
                    request.set(
                        CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                    )
                    s.setRepeatingRequest(request.build(), null, handler)
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    runOnUiThread { toast("Camera session failed") }
                }
            }
        )
        camera!!.createCaptureSession(config)
    }

    private fun capture() {
        val cam = camera ?: return
        val sess = session ?: return
        val request = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        request.addTarget(jpegReader!!.surface)
        rawReader?.let { request.addTarget(it.surface) }
        request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        request.set(CaptureRequest.JPEG_QUALITY, 95.toByte())

        sess.capture(request.build(), object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult
            ) {
                if (!rawAvailable) return
                synchronized(lock) {
                    pendingResult = result
                    maybeWriteDng()
                }
            }
        }, handler)
    }

    private fun onRaw(reader: ImageReader) {
        val image = reader.acquireNextImage() ?: return
        synchronized(lock) {
            pendingRaw = image
            maybeWriteDng()
        }
    }

    // both halves present -> write the DNG, then reset
    private fun maybeWriteDng() {
        val result = pendingResult ?: return
        val image = pendingRaw ?: return
        pendingResult = null
        pendingRaw = null
        try {
            val name = stamp()
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "$name.dng")
                put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/OrchardCam")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                DngCreator(characteristics, result).use { dng ->
                    contentResolver.openOutputStream(uri)?.use { dng.writeImage(it, image) }
                }
            }
        } catch (e: Exception) {
            runOnUiThread { toast("RAW save failed: ${e.message}") }
        } finally {
            image.close()
        }
    }

    private fun onJpeg(reader: ImageReader) {
        val image = reader.acquireNextImage() ?: return
        try {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
            var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
            bmp = rotate(bmp, sensorOrientation)
            // ponytail: grading the developed JPEG, not the linear RAW. Swap to a
            // RAW-developed bitmap here once an NDK demosaic pipeline exists.
            val graded = AppleLook.apply(bmp)
            saveJpeg(graded)
            runOnUiThread { toast("Saved") }
        } catch (e: Exception) {
            runOnUiThread { toast("Save failed: ${e.message}") }
        } finally {
            image.close()
        }
    }

    private fun saveJpeg(bmp: Bitmap) {
        val name = stamp()
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/OrchardCam")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
        contentResolver.openOutputStream(uri)?.use {
            bmp.compress(Bitmap.CompressFormat.JPEG, 95, it)
        }
    }

    private fun rotate(bmp: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bmp
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    private fun stamp(): String =
        "ORCH_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        session?.close()
        camera?.close()
        jpegReader?.close()
        rawReader?.close()
        thread.quitSafely()
        super.onDestroy()
    }
}
