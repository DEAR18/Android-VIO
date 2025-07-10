package com.example.android_vio.ui.screens

import android.view.SurfaceView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp


@Composable
fun SensorViewer(
    accelerometerData: String,
    gyroscopeData: String,
    magnetometerData: String,
    previewView: SurfaceView,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
    ) {
        CameraPreview(
            previewView = previewView,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .aspectRatio(1f / 1f)
        )

        IMUDataScreen(
            accelerometerData = accelerometerData,
            gyroscopeData = gyroscopeData,
            magnetometerData = magnetometerData,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(10.dp)
        )
    }
}
