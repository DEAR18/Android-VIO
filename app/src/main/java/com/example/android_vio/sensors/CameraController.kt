package com.example.android_vio.sensors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.SurfaceHolder
import androidx.annotation.RequiresPermission
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.example.android_vio.data.DataRecorder
import com.example.android_vio.data.ImageSaver
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Controller class for managing Camera2 API operations including preview, capture, and image processing.
 * This class encapsulates all camera-related functionality previously scattered in MainActivity.
 */
class CameraController(
    private val context: Context,
    private val dataRecorder: DataRecorder
) {
    companion object {
        private const val TAG = "CameraController"
    }

    // Camera state tracking
    var camTimestamp: Long by mutableStateOf(0)
        private set
    var camFps: Double by mutableStateOf(0.0)
        private set

    // Permission state
    var hasPermission: Boolean by mutableStateOf(false)
        private set

    // Camera2 and Threading Variables
    private lateinit var cameraManager: CameraManager
    private var cameraId: String? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var previewSize: Size? = null
    private var imageSize: Size? = Size(720, 480)

    private lateinit var backgroundHandler: Handler
    private lateinit var backgroundThread: HandlerThread
    private val cameraOpenCloseLock = Semaphore(1)

    private var previewCaptureRequestBuilder: CaptureRequest.Builder? = null
    private var previewSurfaceHolder: SurfaceHolder? = null

    // Camera settings
    private var exposureTimeRange: Range<Long>? = null
    private var isoRange: Range<Int>? = null
    var captureIso by mutableStateOf(120)
        private set
    var captureExposureTime by mutableStateOf(1_000_000_000L / 50)
        private set
    var captureFPS by mutableStateOf(1_000_000_000L / 10)
        private set

    // Camera Callbacks
    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraOpenCloseLock.release()
            cameraDevice = camera
            Log.d(TAG, "CameraDevice opened: ${camera.id}")
            createCameraPreviewSession()
        }

        override fun onDisconnected(camera: CameraDevice) {
            cameraOpenCloseLock.release()
            camera.close()
            cameraDevice = null
            Log.w(TAG, "CameraDevice disconnected: ${camera.id}")
        }

        override fun onError(camera: CameraDevice, error: Int) {
            cameraOpenCloseLock.release()
            camera.close()
            cameraDevice = null
            Log.e(TAG, "CameraDevice Error: $error, Camera ID: ${camera.id}")
        }
    }

    private val onImageAvailableListener =
        ImageReader.OnImageAvailableListener { reader ->
            val image =
                reader.acquireNextImage() ?: return@OnImageAvailableListener
            val deltaT = image.timestamp - camTimestamp
            if (camTimestamp != 0L && deltaT > 0) {
                camFps = 1.0 / (deltaT.toDouble() / 1_000_000_000)
            }
            camTimestamp = image.timestamp

            // Save image if recording is active
            if (dataRecorder.isRecordingInternal) {
                backgroundHandler.post(
                    ImageSaver(
                        image,
                        dataRecorder.outputDirectory
                    ) { savedFile ->
                        dataRecorder.logImageData(camTimestamp, savedFile.name)
                        image.close()
                    }
                )
            } else {
                image.close()
            }
        }

    /**
     * Initialize the camera controller
     */
    fun initialize() {
        cameraManager =
            context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        checkPermission()
        startBackgroundThread()
    }

    /**
     * Check if camera permission is granted
     */
    fun checkPermission(): Boolean {
        hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        return hasPermission
    }

    /**
     * Update permission status (should be called from permission result)
     */
    fun updatePermissionStatus(granted: Boolean) {
        hasPermission = granted
    }

    /**
     * Setup camera outputs based on view dimensions
     */
    fun setupCameraOutputs(viewWidth: Int, viewHeight: Int) {
        try {
            for (id in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(id)

                val cameraFacing =
                    characteristics.get(CameraCharacteristics.LENS_FACING)
                if (cameraFacing == null || cameraFacing != CameraCharacteristics.LENS_FACING_BACK) {
                    continue // Skip non-back cameras
                }
                cameraId = id

                // Get stream configuration map
                val map =
                    characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                        ?: continue

                // Find optimal JPEG (Image Capture) size
                imageSize = imageSize?.let {
                    chooseOptimalSize(
                        map.getOutputSizes(ImageFormat.JPEG),
                        it.width,
                        it.height
                    )
                }
                Log.d(TAG, "Selected Image Capture Size: $imageSize")

                // Find optimal Preview size based on image size
                previewSize = imageSize?.let {
                    chooseOptimalSize(
                        map.getOutputSizes(SurfaceHolder::class.java),
                        it.width,
                        it.height
                    )
                }
                Log.d(TAG, "Selected Preview Size: $previewSize")

                // Initialize ImageReader for still captures
                imageReader = ImageReader.newInstance(
                    imageSize!!.width, imageSize!!.height,
                    ImageFormat.JPEG, /*maxImages*/ 2
                ).apply {
                    setOnImageAvailableListener(
                        onImageAvailableListener,
                        backgroundHandler
                    )
                }

                // Query exposure ranges
                exposureTimeRange =
                    characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                isoRange =
                    characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                Log.d(TAG, "Exposure Time Range (ns): $exposureTimeRange")
                Log.d(TAG, "ISO Range: $isoRange")

                break
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to setup camera outputs: ${e.message}", e)
        } catch (e: NullPointerException) {
            Log.e(
                TAG,
                "Device doesn't support Camera2 API or configuration map is null.",
                e
            )
        }
    }

    /**
     * Set the surface holder for camera preview
     */
    fun setPreviewSurfaceHolder(holder: SurfaceHolder?) {
        previewSurfaceHolder = holder
    }

    /**
     * Open the camera
     */
    @RequiresPermission(Manifest.permission.CAMERA)
    fun openCamera() {
        if (!hasPermission) {
            Log.w(TAG, "Camera permission not granted, cannot open camera.")
            return
        }
        if (cameraId == null) {
            Log.e(TAG, "No suitable camera ID found.")
            return
        }
        try {
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("Time out waiting to lock camera opening.")
            }
            cameraManager.openCamera(
                cameraId!!,
                cameraStateCallback,
                backgroundHandler
            )
            Log.d(TAG, "Attempting to open camera: $cameraId")
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to open camera: ${e.message}", e)
        } catch (e: InterruptedException) {
            throw RuntimeException(
                "Interrupted while trying to acquire camera open lock.",
                e
            )
        }
    }

    /**
     * Close the camera and release resources
     */
    fun closeCamera() {
        try {
            cameraOpenCloseLock.acquire()
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
            Log.d(TAG, "Camera resources closed.")
        } catch (e: InterruptedException) {
            throw RuntimeException(
                "Interrupted while trying to acquire camera lock for closing.",
                e
            )
        } finally {
            cameraOpenCloseLock.release()
        }
    }

    /**
     * Create camera preview session
     */
    private fun createCameraPreviewSession() {
        try {
            if (cameraDevice == null || previewSurfaceHolder?.surface == null || imageReader == null) {
                Log.e(
                    TAG,
                    "Cannot create preview session: cameraDevice, surface or imageReader is null."
                )
                return
            }

            val previewSurface = previewSurfaceHolder!!.surface

            // Create a repeating request builder for continuous stream
            previewCaptureRequestBuilder =
                cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                    .apply {
                        addTarget(previewSurface)
                        addTarget(imageReader!!.surface)
                        applyCaptureSettings(this)
                    }

            // Create the session
            cameraDevice!!.createCaptureSession(
                listOf(previewSurface, imageReader!!.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            captureSession?.setRepeatingRequest(
                                previewCaptureRequestBuilder!!.build(),
                                null,
                                backgroundHandler
                            )
                            Log.d(
                                TAG,
                                "Camera preview and image stream session configured and started."
                            )
                        } catch (e: CameraAccessException) {
                            Log.e(TAG, "Failed to start repeating request", e)
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(
                            TAG,
                            "Failed to configure camera capture session."
                        )
                    }
                },
                backgroundHandler
            )
            Log.d(
                TAG,
                "Creating camera capture session with preview and imageReader surfaces."
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to create camera preview session", e)
        }
    }

    /**
     * Apply manual capture settings to a CaptureRequest.Builder
     */
    private fun applyCaptureSettings(builder: CaptureRequest.Builder) {
        builder.set(
            CaptureRequest.CONTROL_AE_MODE,
            CameraMetadata.CONTROL_AE_MODE_OFF
        )
        builder.set(CaptureRequest.SENSOR_SENSITIVITY, captureIso)
        builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, captureExposureTime)
        builder.set(CaptureRequest.SENSOR_FRAME_DURATION, captureFPS)
    }

    /**
     * Choose optimal size from available choices
     */
    private fun chooseOptimalSize(
        choices: Array<Size>,
        width: Int,
        height: Int
    ): Size {
        val bigEnough = ArrayList<Size>()
        val notBigEnough = ArrayList<Size>()

        val targetRatio = width.toFloat() / height
        for (option in choices) {
            Log.d(TAG, "image size option $option")
            if (option.width == 0 || option.height == 0) continue

            val aspectRatio = option.width.toFloat() / option.height
            if (aspectRatio == targetRatio) {
                if (option.width >= width && option.height >= height) {
                    bigEnough.add(option)
                } else {
                    notBigEnough.add(option)
                }
            }
        }

        return when {
            bigEnough.size > 0 -> bigEnough.minByOrNull { it.width * it.height }!!
            notBigEnough.size > 0 -> notBigEnough.maxByOrNull { it.width * it.height }!!
            else -> {
                Log.w(
                    TAG,
                    "Couldn't find any suitable size with matching aspect ratio. Selecting closest."
                )
                choices.minByOrNull {
                    kotlin.math.abs(it.width - width) + kotlin.math.abs(
                        it.height - height
                    )
                }
                    ?: choices[0]
            }
        }
    }

    /**
     * Start background thread for camera operations
     */
    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").apply { start() }
        backgroundHandler = Handler(backgroundThread.looper)
    }

    /**
     * Stop background thread
     */
    private fun stopBackgroundThread() {
        backgroundThread.quitSafely()
        try {
            backgroundThread.join()
        } catch (e: InterruptedException) {
            Log.e(TAG, "Interrupted while stopping background thread", e)
        }
    }

    /**
     * Clean up resources
     */
    fun cleanup() {
        closeCamera()
        stopBackgroundThread()
    }

    /**
     * Update camera settings
     */
    fun updateCaptureSettings(iso: Int, exposureTime: Long, fps: Long) {
        captureIso = iso
        captureExposureTime = exposureTime
        captureFPS = fps

        // Apply new settings to current session if active
        previewCaptureRequestBuilder?.let { builder ->
            applyCaptureSettings(builder)
            try {
                captureSession?.setRepeatingRequest(
                    builder.build(),
                    null,
                    backgroundHandler
                )
            } catch (e: CameraAccessException) {
                Log.e(TAG, "Failed to update capture settings", e)
            }
        }
    }
}
