package com.example.android_vio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.example.android_vio.ui.screens.PermissionRequestScreen
import com.example.android_vio.ui.screens.SensorFpsBar
import com.example.android_vio.ui.screens.SensorViewer
import com.example.android_vio.ui.theme.Android_vioTheme


class MainActivity : ComponentActivity(), SensorEventListener {
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private var magnetometer: Sensor? = null
    private var accelerometerData by mutableStateOf("Accelerometer: Null")
    private var gyroscopeData by mutableStateOf("Gyroscope: Null")
    private var magnetometerData by mutableStateOf("Magnetometer: Null")
    private var hasIMUPermission by mutableStateOf(false)
    private var hasCamPermission by mutableStateOf(false)
    private var gyroTimestamp: Long by mutableStateOf(0)
    private var accTimestamp: Long by mutableStateOf(0)
    private var camFrameTimestamp: Long by mutableStateOf(0)
    private var imuFps: Double by mutableStateOf(0.0)
    private var camFps: Double by mutableStateOf(0.0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        enableEdgeToEdge()

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
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

        setContent {
            Android_vioTheme {
                // The Scaffold is placed here to encompass the entire UI
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
                    val context = LocalContext.current

                    val imuPermissionLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission()
                    ) { isGranted: Boolean ->
                        hasIMUPermission = isGranted // Update local state
                        if (isGranted) {
                            initializeBodySensor() // Ensure sensors are initialized after grant
                        } else {
                            accelerometerData = "Accelerometer: Permission denied"
                            gyroscopeData = "Gyroscope: Permission denied"
                            magnetometerData = "Magnetometer: Permission denied"
                        }
                    }

                    val cameraPermissionLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission()
                    ) { isGranted ->
                        hasCamPermission = isGranted
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding) // Apply padding from Scaffold
                    ) {
                        if (!hasIMUPermission) {
                            PermissionRequestScreen(
                                "Body Sensor",
                                modifier = Modifier.weight(1f)
                            ) {
                                imuPermissionLauncher.launch(Manifest.permission.BODY_SENSORS)
                            }
                        }

                        if (!hasCamPermission) {
                            PermissionRequestScreen(
                                "Camera",
                                modifier = Modifier.weight(1f)
                            ) {
                                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                        }

                        if (hasIMUPermission && hasCamPermission) {
                            SensorViewer(
                                accelerometerData,
                                gyroscopeData,
                                magnetometerData
                            )

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
    }

    private fun initializeBodySensor() {
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        if (accelerometer == null) accelerometerData = "Accelerometer: Not available on this device"
        if (gyroscope == null) gyroscopeData = "Gyroscope: Not available on this device"
        if (magnetometer == null) magnetometerData = "Magnetometer: Not available on this device"
    }

    private fun registerSensorListeners() {
        if (!hasIMUPermission) {
            return
        }
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        magnetometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Handle accuracy changes if needed
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            when (it.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    accelerometerData =
                        "Acc: X=${"%.2f".format(it.values[0])}, Y=${"%.2f".format(it.values[1])}, Z=${
                            "%.2f".format(
                                it.values[2]
                            )
                        }"
                    val deltaT = it.timestamp - accTimestamp
                    if (accTimestamp != 0L && deltaT > 0) {
                        imuFps = 1.0 / (deltaT.toDouble() / 1_000_000_000)
                    }
                    accTimestamp = it.timestamp
                }

                Sensor.TYPE_GYROSCOPE -> {
                    gyroscopeData =
                        "Gyro: X=${"%.2f".format(it.values[0])}, Y=${"%.2f".format(it.values[1])}, Z=${
                            "%.2f".format(
                                it.values[2]
                            )
                        }"
                    gyroTimestamp = it.timestamp
                }

                Sensor.TYPE_MAGNETIC_FIELD -> {
                    magnetometerData =
                        "Mag: X=${"%.2f".format(it.values[0])}, Y=${"%.2f".format(it.values[1])}, Z=${
                            "%.2f".format(
                                it.values[2]
                            )
                        }"
                }
            }
        }
    }
}
