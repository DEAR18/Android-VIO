package com.example.android_vio.ui.screens

import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun CameraPreview(
    previewView: SurfaceView,
    modifier: Modifier = Modifier
) { // Type changed to SurfaceView
    AndroidView(
        factory = {
            previewView
        },
        modifier = modifier
    )
}