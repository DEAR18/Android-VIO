package com.example.android_vio

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.example.android_vio.data.DataRecorder
import com.example.android_vio.data.ImageSaver
import com.example.android_vio.data.ImuData
import com.example.android_vio.data.MagnetometerData
import com.example.android_vio.ui.screens.PermissionRequestScreen
import com.example.android_vio.ui.screens.SensorFpsBar
import com.example.android_vio.ui.screens.SensorViewer
import com.example.android_vio.ui.theme.Android_vioTheme
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import android.os.Looper


interface ImuProcessingCallback {
    fun onProcessedImuData(
        timestamp: Long,
        procX: Float,
        procY: Float,
        procZ: Float
    )
}

class MainActivity : ComponentActivity(), SensorEventListener,
    ImuProcessingCallback {
    companion object {
        init {
            System.loadLibrary("android-vio-native")
        }

        private const val TAG = "MainActivity"
    }

    private external fun nativeInitProcessor()
    private external fun nativeDestroyProcessor()
    private external fun nativeStartProcessing()
    private external fun nativeStopProcessing()
    private external fun nativeReceiveImuData(
        timestamp: Long,
        accX: Float, accY: Float, accZ: Float,
        gyroX: Float, gyroY: Float, gyroZ: Float
    )

    private external fun nativeSetOutputCallback(callback: ImuProcessingCallback)

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private var magnetometer: Sensor? = null
    private var accelerometerData by mutableStateOf("Accelerometer: Null")
    private var gyroscopeData by mutableStateOf("Gyroscope: Null")
    private var magnetometerData by mutableStateOf("Magnetometer: Null")

    private var hasIMUPermission by mutableStateOf(false)
    private var hasCamPermission by mutableStateOf(false)
    private var hasStoragePermission by mutableStateOf(false)

    private var gyroTimestamp: Long by mutableStateOf(0)
    private var accTimestamp: Long by mutableStateOf(0)
    private var camTimestamp: Long by mutableStateOf(0)
    private var imuFps: Double by mutableStateOf(0.0)
    private var camFps: Double by mutableStateOf(0.0)

    private var imuSamplePeriod = 10_000 // 10ms
    private var imuDataBuffer = ImuData(0, 0f, 0f, 0f, 0f, 0f, 0f)
    private var magnetometerDataBuffer = MagnetometerData(0, 0f, 0f, 0f)

    private val imuUiHandler = Handler(Looper.getMainLooper())
    private var imuUiUpdateRunnable: Runnable? = null

    private var isRecording by mutableStateOf(false)
    private lateinit var dataRecorder: DataRecorder

    // --- Camera2 and Threading Variables ---
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

    private var exposureTimeRange: Range<Long>? = null
    private var isoRange: Range<Int>? = null
    private var captureIso by mutableStateOf(120)
    private var captureExposureTime by mutableStateOf(1_000_000_000L / 50)
    private var captureFPS by mutableStateOf(1_000_000_000L / 10)

    // Executor for dedicated, non-blocking file I/O operations
    private val fileIoExecutor: ExecutorService =
        Executors.newSingleThreadExecutor()

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
                camFps =
                    1.0 / (deltaT.toDouble() / 1_000_000_000)
            }
            camTimestamp = image.timestamp

            // Post image saving to the camera background thread
//            if (dataRecorder.isRecordingInternal) {
//                backgroundHandler.post(
//                    ImageSaver(
//                        image,
//                        dataRecorder.outputDirectory
//                    ) { savedFile ->
////                        runOnUiThread {} // Update UI on the main thread
//                        // Log image data using DataRecorder's thread-safe method
//                        dataRecorder.logImageData(
//                            camTimestamp,
//                            savedFile.name
//                        )
//                        image.close()
//                    }
//                )
//            } else {
//                image.close()
//            }
            image.close()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        enableEdgeToEdge()

        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        startBackgroundThread()

        sensorManager =
            getSystemService(SENSOR_SERVICE) as SensorManager
        hasIMUPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.BODY_SENSORS
        ) == PackageManager.PERMISSION_GRANTED
        if (hasIMUPermission) {
            initializeBodySensor()
        }
        hasCamPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        hasStoragePermission = checkStoragePermission()

        nativeInitProcessor()
        nativeSetOutputCallback(this)

        // Setup Lifecycle Observer for Native Processor and resources cleanup
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                super.onStart(owner)
                nativeStartProcessing()
                Log.d(TAG, "Native processing started.")
            }

            override fun onStop(owner: LifecycleOwner) {
                super.onStop(owner)
                nativeStopProcessing()
                Log.d(TAG, "Native processing stopped.")
            }

            override fun onDestroy(owner: LifecycleOwner) {
                super.onDestroy(owner)
                nativeDestroyProcessor()
                closeCamera()
                stopBackgroundThread()
                dataRecorder.stopRecording()
                fileIoExecutor.shutdown() // Shut down the file I/O executor
                Log.d(TAG, "Cleaned up all resources.")
            }
        })

        setContent {
            Android_vioTheme {
                val context = LocalContext.current
                val surfaceView = remember { SurfaceView(context) }

                dataRecorder = remember {
                    DataRecorder(
                        context = context,
                        fileExecutor = fileIoExecutor, // Pass the dedicated executor
                        onRecordingStatusChanged = { recording ->
                            isRecording = recording
                        }
                    )
                }

                val imuPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { isGranted: Boolean ->
                    hasIMUPermission = isGranted
                    if (isGranted) {
                        initializeBodySensor()
                        registerSensorListeners() // Register after permission granted
                    } else {
                        accelerometerData = "Accelerometer: Permission denied"
                        gyroscopeData = "Gyroscope: Permission denied"
                        magnetometerData = "Magnetometer: Permission denied"
                    }
                }

                val cameraPermissionLauncher =
                    rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission()
                    ) { isGranted ->
                        hasCamPermission = isGranted
                    }

                val storagePermissionLauncher =
                    rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestMultiplePermissions() // Use RequestMultiplePermissions for granular ones
                    ) { permissionsGranted: Map<String, Boolean> ->
                        val allGranted =
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                permissionsGranted[Manifest.permission.READ_MEDIA_IMAGES] == true &&
                                        permissionsGranted[Manifest.permission.READ_MEDIA_VIDEO] == true
                            } else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                                permissionsGranted[Manifest.permission.WRITE_EXTERNAL_STORAGE] == true
                            } else {
                                true // For Android 10-12, app-specific external storage doesn't need explicit runtime permission
                            }
                        hasStoragePermission = allGranted
                        if (allGranted) {
                            Toast.makeText(
                                context,
                                "Storage permissions granted!",
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            Toast.makeText(
                                context,
                                "Storage permissions denied!",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }


                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        SensorFpsBar(
                            imuFps = imuFps,
                            cameraFps = camFps,
                            modifier = Modifier.systemBarsPadding()
                        )
                    }
                ) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        // Permission Request UI for IMU
                        if (!hasIMUPermission) {
                            PermissionRequestScreen(
                                "Body Sensor",
                                modifier = Modifier.weight(1f)
                            ) {
                                imuPermissionLauncher.launch(Manifest.permission.BODY_SENSORS)
                            }
                        }
                        // Permission Request UI for Camera
                        if (!hasCamPermission) {
                            PermissionRequestScreen(
                                "Camera",
                                modifier = Modifier.weight(1f)
                            ) {
                                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                        }
                        // Request Storage permission if not granted
                        if (!hasStoragePermission) {
                            PermissionRequestScreen(
                                "Storage",
                                modifier = Modifier.weight(1f)
                            ) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    storagePermissionLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.READ_MEDIA_IMAGES,
                                            Manifest.permission.READ_MEDIA_VIDEO
                                        )
                                    )
                                } else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                                    storagePermissionLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.WRITE_EXTERNAL_STORAGE
                                        )
                                    )
                                } else {
                                    // For Android 10-12, getExternalFilesDir() doesn't need permission,
                                    // so if we reach here, it implies storage is available for our use case.
                                    Toast.makeText(
                                        context,
                                        "Storage permission not explicitly required for app-specific files on this Android version.",
                                        Toast.LENGTH_LONG
                                    ).show()
                                    hasStoragePermission =
                                        true // Assume granted for app-specific storage
                                }
                            }
                        }

                        if (hasIMUPermission && hasCamPermission && hasStoragePermission) {
                            // Setup SurfaceHolder callback to open camera when UI is ready
                            DisposableEffect(surfaceView) {
                                val holderCallback =
                                    object : SurfaceHolder.Callback {
                                        override fun surfaceCreated(holder: SurfaceHolder) {
                                            previewSurfaceHolder = holder
                                            setupCameraOutputs(
                                                holder.surfaceFrame.width(),
                                                holder.surfaceFrame.height()
                                            )
//                                            surfaceView.layoutParams.width = 720
//                                            surfaceView.layoutParams.height = 1080
//                                            surfaceView.requestLayout()
                                            openCamera()
                                        }

                                        override fun surfaceChanged(
                                            holder: SurfaceHolder,
                                            format: Int,
                                            width: Int,
                                            height: Int
                                        ) {
                                            Log.d(
                                                TAG,
                                                "Surface changed with size: ${width}x${height}"
                                            )
                                            if (width > 0 && height > 0) {
                                                setupCameraOutputs(
                                                    width,
                                                    height
                                                )
                                                openCamera()
                                            }
                                        }

                                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                                            previewSurfaceHolder = null
                                            closeCamera()
                                        }
                                    }
                                surfaceView.holder.addCallback(holderCallback)
                                onDispose {
                                    surfaceView.holder.removeCallback(
                                        holderCallback
                                    )
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(innerPadding)
                            ) {
                                SensorViewer(
                                    accelerometerData = accelerometerData,
                                    gyroscopeData = gyroscopeData,
                                    magnetometerData = magnetometerData,
                                    previewView = surfaceView,
                                    modifier = Modifier.fillMaxSize()
                                )

                                // Record Button, aligned to the bottom center.
                                Button(
                                    onClick = {
                                        dataRecorder.toggleRecording(
                                        )
                                    },
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .padding(
                                            start = 10.dp,
                                            bottom = 10.dp
                                        )
                                ) {
                                    Text(if (isRecording) "Stop Recording" else "Start Recording")
                                }
                            }

                            DisposableEffect(Unit) {
                                registerSensorListeners()
                                onDispose {
                                    sensorManager.unregisterListener(this@MainActivity)
                                }
                            }
                        }
                    }
                }
            }
        }
        startImuUiUpdate()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopImuUiUpdate()
    }

    // --- Camera2 API Implementation ---

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").apply { start() }
        backgroundHandler = Handler(backgroundThread.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread.quitSafely()
        try {
            backgroundThread.join()
        } catch (e: InterruptedException) {
            Log.e(TAG, "Interrupted while stopping background thread", e)
        }
    }

    // Helper to find optimal preview/image sizes
    private fun setupCameraOutputs(viewWidth: Int, viewHeight: Int) {
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

                // Find optimal Preview size based on actual SurfaceView dimensions
//                previewSize = chooseOptimalSize(
//                    map.getOutputSizes(SurfaceHolder::class.java),
//                    viewWidth,
//                    viewHeight
//                )
                previewSize = imageSize?.let {
                    chooseOptimalSize(
                        map.getOutputSizes(SurfaceHolder::class.java),
                        it.width,
                        it.height
                    )
                }
//               previewSurfaceHolder?.surface.
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
            // Some devices might not have CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
            Log.e(
                TAG,
                "Device doesn't support Camera2 API or configuration map is null.",
                e
            )
        }
    }

    // Helper function to choose an optimal size from a list of available sizes
    private fun chooseOptimalSize(
        choices: Array<Size>,
        width: Int,
        height: Int
    ): Size {
        // Collect the supported resolutions that are at least as big as the preview Surface
        val bigEnough = ArrayList<Size>()
        // Collect the supported resolutions that are smaller than the preview Surface
        val notBigEnough = ArrayList<Size>()

        // Prioritize sizes that match the aspect ratio and are close to the target
        val targetRatio = width.toFloat() / height
        for (option in choices) {
            Log.d(TAG, "image size option ${option}")
            if (option.width == 0 || option.height == 0) continue // Skip invalid sizes

            val aspectRatio = option.width.toFloat() / option.height
            if (aspectRatio == targetRatio) {
                if (option.width >= width && option.height >= height) {
                    bigEnough.add(option)
                } else {
                    notBigEnough.add(option)
                }
            }
        }

        // Pick the smallest of those big enough, or if there are no big enough, pick the largest of those not big enough.
        // Fallback to choosing the closest if aspect ratio doesn't strictly match.
        return when {
            bigEnough.size > 0 -> bigEnough.minByOrNull { it.width * it.height }!!
            notBigEnough.size > 0 -> notBigEnough.maxByOrNull { it.width * it.height }!!
            else -> {
                // No exact aspect ratio match, pick the closest size to target
                Log.w(
                    TAG,
                    "Couldn't find any suitable size with matching aspect ratio. Selecting closest."
                )
                choices.minByOrNull { Math.abs(it.width - width) + Math.abs(it.height - height) }
                    ?: choices[0]
            }
        }
    }


    private fun openCamera() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
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

    private fun closeCamera() {
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

    private fun createCameraPreviewSession() {
        try {
            if (cameraDevice == null || previewSurfaceHolder?.surface == null || imageReader == null) {
                Log.e(
                    TAG,
                    "Cannot create preview session: cameraDevice, surface or imageReader is null."
                )
                return
            }

            // This is the output surface for the preview.
            val previewSurface = previewSurfaceHolder!!.surface

            // Create a repeating request builder for a continuous stream of preview and image data
            previewCaptureRequestBuilder =
                cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                    .apply {
                        addTarget(previewSurface)
                        addTarget(imageReader!!.surface) // CRITICAL: Add ImageReader surface to the repeating request
                        applyCaptureSettings(this)
                    }

            // Create the session
            cameraDevice!!.createCaptureSession(
                listOf(previewSurface, imageReader!!.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            // Start the repeating request to get a continuous stream
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

    // Function to apply manual exposure settings to a CaptureRequest.Builder
    private fun applyCaptureSettings(builder: CaptureRequest.Builder) {
        builder.set(
            CaptureRequest.CONTROL_AE_MODE,
            CameraMetadata.CONTROL_AE_MODE_OFF
        )

        builder.set(CaptureRequest.SENSOR_SENSITIVITY, captureIso)
        builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, captureExposureTime)
        builder.set(CaptureRequest.SENSOR_FRAME_DURATION, captureFPS)
    }

    private fun checkStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_MEDIA_IMAGES
            ) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.READ_MEDIA_VIDEO
                    ) == PackageManager.PERMISSION_GRANTED
        } else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) { // Android 9 (API 28) and below
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            // Android 10 (API 29) - 12 (API 32): Scoped Storage.
            // getExternalFilesDir() does not require WRITE_EXTERNAL_STORAGE permission.
            // If the app needs to write to public directories, MediaStore API is used,
            // which often doesn't require WRITE_EXTERNAL_STORAGE for writing *own* media.
            // For this app's use case (app-specific external storage), we can assume true.
            true
        }
    }

    private fun initializeBodySensor() {
        accelerometer =
            sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        magnetometer =
            sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        if (accelerometer == null) accelerometerData =
            "Accelerometer: Not available on this device"
        if (gyroscope == null) gyroscopeData =
            "Gyroscope: Not available on this device"
        if (magnetometer == null) magnetometerData =
            "Magnetometer: Not available on this device"
    }

    private fun registerSensorListeners() {
        if (!hasIMUPermission) {
            return
        }
        accelerometer?.let {
            sensorManager.registerListener(
                this,
                it,
                imuSamplePeriod
            )
        }
        gyroscope?.let {
            sensorManager.registerListener(
                this,
                it,
                imuSamplePeriod
            )
        }
        magnetometer?.let {
            sensorManager.registerListener(
                this,
                it,
                SensorManager.SENSOR_DELAY_GAME
            )
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not used for this example
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            when (it.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    val deltaT = it.timestamp - accTimestamp
                    if (accTimestamp != 0L && deltaT > 0) {
                        imuFps = 1.0 / (deltaT.toDouble() / 1_000_000_000)
                    }
                    accTimestamp = it.timestamp

                    imuDataBuffer.timestamp = accTimestamp
                    imuDataBuffer.accX = it.values[0]
                    imuDataBuffer.accY = it.values[1]
                    imuDataBuffer.accZ = it.values[2]

                    nativeReceiveImuData(
                        imuDataBuffer.timestamp,
                        imuDataBuffer.accX,
                        imuDataBuffer.accY,
                        imuDataBuffer.accZ,
                        imuDataBuffer.gyroX,
                        imuDataBuffer.gyroY,
                        imuDataBuffer.gyroZ
                    )

                    dataRecorder.logImuData(imuDataBuffer)
                }

                Sensor.TYPE_GYROSCOPE -> {
                    gyroTimestamp = it.timestamp
                    imuDataBuffer.gyroX = it.values[0]
                    imuDataBuffer.gyroY = it.values[1]
                    imuDataBuffer.gyroZ = it.values[2]
                }

                Sensor.TYPE_MAGNETIC_FIELD -> {
                    magnetometerDataBuffer.timestamp = it.timestamp
                    magnetometerDataBuffer.x = it.values[0]
                    magnetometerDataBuffer.y = it.values[1]
                    magnetometerDataBuffer.z = it.values[2]
                }
            }
        }
    }

    override fun onProcessedImuData(
        timestamp: Long,
        procX: Float,
        procY: Float,
        procZ: Float
    ) {
//        accelerometerData =
//            "Acc: X=${"%.2f".format(procX)}, Y=${"%.2f".format(procY)}, Z=${
//                "%.2f".format(
//                    procZ
//                )
//            }"
    }

    private fun startImuUiUpdate() {
        imuUiUpdateRunnable = object : Runnable {
            override fun run() {
                accelerometerData =
                    "Acc: X=${"%.2f".format(imuDataBuffer.accX)}, Y=${
                        "%.2f".format(imuDataBuffer.accY)
                    }, Z=${"%.2f".format(imuDataBuffer.accZ)}"
                gyroscopeData =
                    "Gyro: X=${"%.2f".format(imuDataBuffer.gyroX)}, Y=${
                        "%.2f".format(imuDataBuffer.gyroY)
                    }, Z=${"%.2f".format(imuDataBuffer.gyroZ)}"
                magnetometerData =
                    "Mag: X=${"%.2f".format(magnetometerDataBuffer.x)}, Y=${
                        "%.2f".format(magnetometerDataBuffer.y)
                    }, Z=${"%.2f".format(magnetometerDataBuffer.z)}"
                imuUiHandler.postDelayed(this, 1000)
            }
        }
        imuUiHandler.post(imuUiUpdateRunnable!!)
    }

    private fun stopImuUiUpdate() {
        imuUiUpdateRunnable?.let { imuUiHandler.removeCallbacks(it) }
    }
}
