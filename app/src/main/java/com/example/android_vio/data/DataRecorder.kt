package com.example.android_vio.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.Image
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.io.IOException
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService

// ImageSaver utility class for saving captured images (nested inside MainActivity or as a top-level class)
class ImageSaver(
    private val image: Image,
    private val outputDir: File,
    private val onImageSaved: (File) -> Unit
) : Runnable {
    override fun run() {
        if (image.planes == null || image.planes.isEmpty()) {
            Log.e(TAG, "image.planes is null or empty")
        }
        val buffer: ByteBuffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val photoFileName = "${image.timestamp}.jpg"
        val photoFile = File(outputDir, photoFileName)

        var output: FileOutputStream? = null
        try {
            output = FileOutputStream(photoFile).apply {
                write(bytes)
            }
            onImageSaved(photoFile) // Notify callback that image is saved

        } catch (e: IOException) {
            Log.e(TAG, "Error saving image: ${e.message}", e)
        } finally {
            image.close() // IMPORTANT: Close the Image to release resources
            output?.close()
        }
    }

    companion object {
        private const val TAG = "ImageSaver"
    }
}

/**
 * Handles the recording functionalities for sensor and image data.
 *
 * @param context Application context, used for file operations and displaying Toast messages.
 * @param fileExecutor Executor for all file writing operations to avoid blocking critical threads.
 * @param onRecordingStatusChanged Callback function invoked when the recording status changes.
 */
class DataRecorder(
    private val context: Context,
    private val fileExecutor: ExecutorService, // Renamed for clarity, handles all file I/O
    private val onRecordingStatusChanged: (Boolean) -> Unit,
) {
    // Internal state indicating whether recording is currently active
    var isRecordingInternal by mutableStateOf(false) // Changed to var and public for MainActivity to read

    // Permission state
    var hasStoragePermission: Boolean by mutableStateOf(false)
        private set

    // File writers for logging, access is managed via methods using the executor
    private var imuLogFileWriter: FileWriter? = null
    private var imageLogFileWriter: FileWriter? = null

    // Output directory for recorded data
    lateinit var outputDirectory: File

    init {
        checkStoragePermission()
    }

    /**
     * Check if storage permission is granted
     */
    fun checkStoragePermission(): Boolean {
        hasStoragePermission =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_MEDIA_IMAGES
                ) == PackageManager.PERMISSION_GRANTED &&
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.READ_MEDIA_VIDEO
                        ) == PackageManager.PERMISSION_GRANTED
            } else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) { // Android 9 (API 28) and below
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                // Android 10 (API 29) - 12 (API 32): Scoped Storage.
                // getExternalFilesDir() does not require WRITE_EXTERNAL_STORAGE permission.
                true
            }
        return hasStoragePermission
    }

    /**
     * Update permission status (should be called from permission result)
     */
    fun updatePermissionStatus(granted: Boolean) {
        hasStoragePermission = granted
        if (!granted && isRecordingInternal) {
            stopRecording()
        }
    }

    /**
     * Toggles the recording state (starts or stops recording).
     */
    fun toggleRecording() {
        if (isRecordingInternal) {
            stopRecording()
        } else {
            startRecording()
        }
    }

    /**
     * Starts data recording.
     */
    private fun startRecording() {
        if (!hasStoragePermission) {
            Log.w(
                TAG,
                "Storage permission not granted, cannot start recording."
            )
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(
                    context,
                    "Storage permission required for recording",
                    Toast.LENGTH_SHORT
                ).show()
            }
            return
        }

        try {
            // Create a session directory named with the current timestamp
            val sessionDirName =
                SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(
                    System.currentTimeMillis()
                )
            outputDirectory = File(
                getOutputDirectory(context),
                sessionDirName
            )
            Log.e(TAG, "outputdir ${outputDirectory}")

            // Ensure the directory exists
            if (!outputDirectory.exists()) {
                val created = outputDirectory.mkdirs()
                if (!created) {
                    throw IOException("Failed to create directory: ${outputDirectory.absolutePath}")
                }
            }

            // Initialize IMU data log file and writer
            val imuLogFile = File(outputDirectory, "imu_data.txt")
            imuLogFileWriter = FileWriter(imuLogFile)
            // Use fileExecutor to write the header to ensure thread safety, though less critical here
            fileExecutor.execute {
                try {
                    imuLogFileWriter?.append("timestamp_ns,accX,accY,accZ,gyroX,gyroY,gyroZ\n")
                } catch (e: IOException) {
                    Log.e(TAG, "Failed to write IMU header: ${e.message}", e)
                }
            }


            // Initialize image timestamp log file and writer
            val imageLogFile = File(outputDirectory, "image.txt")
            imageLogFileWriter = FileWriter(imageLogFile)
            fileExecutor.execute {
                try {
                    imageLogFileWriter?.append("timestamp_ns,image_filename\n")
                } catch (e: IOException) {
                    Log.e(TAG, "Failed to write image header: ${e.message}", e)
                }
            }


            // Update recording status and notify callback
            isRecordingInternal = true
            onRecordingStatusChanged(true)
//            showToast("Recording started in ${outputDirectory.absolutePath}")
            Log.d(TAG, "Recording started in ${outputDirectory.absolutePath}")

        } catch (e: IOException) {
            Log.e(TAG, "Failed to start recording: ${e.message}", e)
            showToast("Failed to start recording: ${e.message}")
            isRecordingInternal = false
            onRecordingStatusChanged(false)
            cleanupWriters() // Clean up partially created files
        }
    }

    /**
     * Stops data recording and closes all file writers.
     */
    fun stopRecording() {
        if (!isRecordingInternal) return

        isRecordingInternal = false
        onRecordingStatusChanged(false)

//        showToast("Recording stopped. Data saved.")
        Log.d(TAG, "Recording stopped. Flushing and closing files.")
        cleanupWriters()
    }

    /**
     * Safely flushes and closes file writers. Can be called on stop or on error.
     */
    private fun cleanupWriters() {
        // Use the executor to close files to ensure any pending writes are finished
        fileExecutor.execute {
            try {
                imuLogFileWriter?.flush()
                imuLogFileWriter?.close()
                imageLogFileWriter?.flush()
                imageLogFileWriter?.close()
            } catch (e: IOException) {
                Log.e(TAG, "Failed to close log files: ${e.message}", e)
            } finally {
                imuLogFileWriter = null
                imageLogFileWriter = null
            }
        }
    }


    /**
     * Logs IMU data to the log file using the file executor.
     * @param imuData An object containing IMU sensor data.
     */
    fun logImuData(imuData: ImuData) {
        if (isRecordingInternal) {
            fileExecutor.execute {
                try {
                    imuLogFileWriter?.append(
                        "${imuData.timestamp}," +
                                "${imuData.accX},${imuData.accY},${imuData.accZ}," +
                                "${imuData.gyroX},${imuData.gyroY},${imuData.gyroZ}\n"
                    )
                } catch (e: IOException) {
                    Log.e(
                        TAG,
                        "Failed to write IMU data to file: ${e.message}",
                        e
                    )
                }
            }
        }
    }

    /**
     * Logs image timestamp and filename to the log file using the file executor.
     * @param timestamp The image timestamp from `image.timestamp`.
     * @param filename The name of the saved image file.
     */
    fun logImageData(timestamp: Long, filename: String) {
        if (isRecordingInternal) {
            fileExecutor.execute {
                try {
                    imageLogFileWriter?.append("$timestamp,$filename\n")
                } catch (e: IOException) {
                    Log.e(
                        TAG,
                        "Failed to write image data to file: ${e.message}",
                        e
                    )
                }
            }
        }
    }

    private fun showToast(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val TAG = "DataRecorder"

        // Centralized function to get the output directory
        fun getOutputDirectory(context: Context): File {
            val mediaDir =
                context.getExternalFilesDir(null)?.apply { mkdirs() }
            return if (mediaDir != null && mediaDir.exists())
                mediaDir else context.filesDir
        }

    }
}