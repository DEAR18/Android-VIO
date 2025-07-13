package com.example.android_vio

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresPermission
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
import androidx.core.view.WindowCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.example.android_vio.data.DataRecorder
import com.example.android_vio.sensors.CameraController
import com.example.android_vio.sensors.ImuManager
import com.example.android_vio.ui.screens.PermissionRequestScreen
import com.example.android_vio.ui.screens.SensorFpsBar
import com.example.android_vio.ui.screens.SensorViewer
import com.example.android_vio.ui.theme.Android_vioTheme
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


class MainActivity : ComponentActivity() {
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

    private external fun nativeSetOutputCallback(callback: ImuManager.ImuProcessingCallback)

    // IMU Manager
    private lateinit var imuManager: ImuManager

    // Camera Controller
    private lateinit var cameraController: CameraController

    private var isRecording by mutableStateOf(false)
    private lateinit var dataRecorder: DataRecorder

    // Executor for dedicated, non-blocking file I/O operations
    private val fileIoExecutor: ExecutorService =
        Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        enableEdgeToEdge()

        nativeInitProcessor()

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
                cameraController.cleanup()
                dataRecorder.stopRecording()
                imuManager.cleanup()
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

                // Initialize IMU Manager
                imuManager = remember {
                    ImuManager(
                        context = context,
                        dataRecorder = dataRecorder,
                        nativeReceiveImuData = ::nativeReceiveImuData
                    ).apply {
                        initialize()
                        // Set native callback
                        nativeSetOutputCallback(object :
                            ImuManager.ImuProcessingCallback {
                            override fun onProcessedImuData(
                                timestamp: Long,
                                procX: Float,
                                procY: Float,
                                procZ: Float
                            ) {
                                onProcessedImuData(
                                    timestamp,
                                    procX,
                                    procY,
                                    procZ
                                )
                            }
                        })
                    }
                }

                // Initialize Camera Controller
                cameraController = remember {
                    CameraController(
                        context = context,
                        dataRecorder = dataRecorder
                    ).apply {
                        initialize()
                    }
                }

                val imuPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { isGranted: Boolean ->
                    imuManager.updatePermissionStatus(isGranted)
                    if (isGranted) {
                        imuManager.registerSensorListeners()
                    }
                }

                val cameraPermissionLauncher =
                    rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission()
                    ) { isGranted ->
                        cameraController.updatePermissionStatus(isGranted)
                    }

                val storagePermissionLauncher =
                    rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestMultiplePermissions()
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
                        dataRecorder.updatePermissionStatus(allGranted)
                    }


                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        SensorFpsBar(
                            imuFps = imuManager.imuFps,
                            cameraFps = cameraController.camFps,
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
                        if (!imuManager.hasPermission) {
                            PermissionRequestScreen(
                                "Body Sensor",
                                modifier = Modifier.weight(1f)
                            ) {
                                imuPermissionLauncher.launch(Manifest.permission.BODY_SENSORS)
                            }
                        }
                        // Permission Request UI for Camera
                        if (!cameraController.hasPermission) {
                            PermissionRequestScreen(
                                "Camera",
                                modifier = Modifier.weight(1f)
                            ) {
                                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                        }
                        // Request Storage permission if not granted
                        if (!dataRecorder.hasStoragePermission) {
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
                                    dataRecorder.updatePermissionStatus(true)
                                }
                            }
                        }

                        if (imuManager.hasPermission && cameraController.hasPermission && dataRecorder.hasStoragePermission) {
                            // Setup SurfaceHolder callback to open camera when UI is ready
                            DisposableEffect(surfaceView) {
                                val holderCallback =
                                    object : SurfaceHolder.Callback {
                                        @RequiresPermission(Manifest.permission.CAMERA)
                                        override fun surfaceCreated(holder: SurfaceHolder) {
                                            cameraController.setPreviewSurfaceHolder(
                                                holder
                                            )
                                            cameraController.setupCameraOutputs(
                                                holder.surfaceFrame.width(),
                                                holder.surfaceFrame.height()
                                            )
                                            cameraController.openCamera()
                                        }

                                        @RequiresPermission(Manifest.permission.CAMERA)
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
                                                cameraController.setupCameraOutputs(
                                                    width,
                                                    height
                                                )
                                                cameraController.openCamera()
                                            }
                                        }

                                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                                            cameraController.setPreviewSurfaceHolder(
                                                null
                                            )
                                            cameraController.closeCamera()
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
                                    accelerometerData = imuManager.accelerometerData,
                                    gyroscopeData = imuManager.gyroscopeData,
                                    magnetometerData = imuManager.magnetometerData,
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
                                if (imuManager.hasPermission) {
                                    imuManager.registerSensorListeners()
                                    imuManager.startUiUpdates()
                                }
                                onDispose {
                                    imuManager.unregisterSensorListeners()
                                    imuManager.stopUiUpdates()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    private fun onProcessedImuData(
        timestamp: Long,
        procX: Float,
        procY: Float,
        procZ: Float
    ) {
        // Handle processed IMU data from native processor
        imuManager.onProcessedImuData(timestamp, procX, procY, procZ)
    }
}
