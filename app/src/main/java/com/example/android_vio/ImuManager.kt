package com.example.android_vio

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.android_vio.data.DataRecorder
import com.example.android_vio.data.ImuData
import com.example.android_vio.data.MagnetometerData

/**
 * Manager class for handling IMU sensor operations including accelerometer, gyroscope, and magnetometer.
 * This class encapsulates all IMU-related functionality previously scattered in MainActivity.
 */
class ImuManager(
    private val context: Context,
    private val dataRecorder: DataRecorder,
    private val nativeReceiveImuData: ((Long, Float, Float, Float, Float, Float, Float) -> Unit)? = null
) : SensorEventListener {

    companion object {
        private const val TAG = "ImuManager"
    }

    // Sensor manager and sensors
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private var magnetometer: Sensor? = null

    // UI state for sensor data display
    var accelerometerData by mutableStateOf("Accelerometer: Null")
        private set
    var gyroscopeData by mutableStateOf("Gyroscope: Null")
        private set
    var magnetometerData by mutableStateOf("Magnetometer: Null")
        private set

    // Timing and FPS tracking
    var gyroTimestamp: Long by mutableStateOf(0)
        private set
    var accTimestamp: Long by mutableStateOf(0)
        private set
    var imuFps: Double by mutableStateOf(0.0)
        private set

    // IMU configuration
    private val imuSamplePeriod = 10_000 // 10ms
    private var imuDataBuffer = ImuData(0, 0f, 0f, 0f, 0f, 0f, 0f)
    private var magnetometerDataBuffer = MagnetometerData(0, 0f, 0f, 0f)

    // UI update handling
    private val imuUiHandler = Handler(Looper.getMainLooper())
    private var imuUiUpdateRunnable: Runnable? = null

    // Callback for processed IMU data (if needed for integration with external processors)
    interface ImuProcessingCallback {
        fun onProcessedImuData(
            timestamp: Long,
            procX: Float,
            procY: Float,
            procZ: Float
        )
    }

    private var processingCallback: ImuProcessingCallback? = null

    /**
     * Initialize the IMU manager with sensor services
     */
    fun initialize() {
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        initializeBodySensors()
    }

    /**
     * Initialize individual body sensors
     */
    private fun initializeBodySensors() {
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        if (accelerometer == null) accelerometerData = "Accelerometer: Not available on this device"
        if (gyroscope == null) gyroscopeData = "Gyroscope: Not available on this device"
        if (magnetometer == null) magnetometerData = "Magnetometer: Not available on this device"
    }

    /**
     * Register sensor listeners to start receiving sensor data
     */
    fun registerSensorListeners() {
        accelerometer?.let {
            sensorManager.registerListener(this, it, imuSamplePeriod)
        }
        gyroscope?.let {
            sensorManager.registerListener(this, it, imuSamplePeriod)
        }
        magnetometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    /**
     * Unregister sensor listeners to stop receiving sensor data
     */
    fun unregisterSensorListeners() {
        sensorManager.unregisterListener(this)
    }

    /**
     * Start periodic UI updates for sensor data display
     */
    fun startUiUpdates() {
        imuUiUpdateRunnable = object : Runnable {
            override fun run() {
                accelerometerData = "Acc: X=${"%.2f".format(imuDataBuffer.accX)}, Y=${
                    "%.2f".format(imuDataBuffer.accY)
                }, Z=${"%.2f".format(imuDataBuffer.accZ)}"
                gyroscopeData = "Gyro: X=${"%.2f".format(imuDataBuffer.gyroX)}, Y=${
                    "%.2f".format(imuDataBuffer.gyroY)
                }, Z=${"%.2f".format(imuDataBuffer.gyroZ)}"
                magnetometerData = "Mag: X=${"%.2f".format(magnetometerDataBuffer.x)}, Y=${
                    "%.2f".format(magnetometerDataBuffer.y)
                }, Z=${"%.2f".format(magnetometerDataBuffer.z)}"
                imuUiHandler.postDelayed(this, 1000)
            }
        }
        imuUiHandler.post(imuUiUpdateRunnable!!)
    }

    /**
     * Stop periodic UI updates
     */
    fun stopUiUpdates() {
        imuUiUpdateRunnable?.let { imuUiHandler.removeCallbacks(it) }
    }

    /**
     * Set callback for processed IMU data (for integration with native processors)
     */
    fun setProcessingCallback(callback: ImuProcessingCallback?) {
        processingCallback = callback
    }

    /**
     * Get the current IMU data buffer for external access
     */
    fun getCurrentImuData(): ImuData = imuDataBuffer.copy()

    /**
     * Get the current magnetometer data buffer for external access
     */
    fun getCurrentMagnetometerData(): MagnetometerData = magnetometerDataBuffer.copy()

    // SensorEventListener implementation
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not used for this implementation
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

                    // Call native JNI method if provided
                    nativeReceiveImuData?.invoke(
                        imuDataBuffer.timestamp,
                        imuDataBuffer.accX,
                        imuDataBuffer.accY,
                        imuDataBuffer.accZ,
                        imuDataBuffer.gyroX,
                        imuDataBuffer.gyroY,
                        imuDataBuffer.gyroZ
                    )

                    // Log data using DataRecorder
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

    /**
     * Handle processed IMU data from external processors
     */
    fun onProcessedImuData(
        timestamp: Long,
        procX: Float,
        procY: Float,
        procZ: Float
    ) {
        processingCallback?.onProcessedImuData(timestamp, procX, procY, procZ)
    }

    /**
     * Clean up resources when the manager is no longer needed
     */
    fun cleanup() {
        unregisterSensorListeners()
        stopUiUpdates()
    }
}
