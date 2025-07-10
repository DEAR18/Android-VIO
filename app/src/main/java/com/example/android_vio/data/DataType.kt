package com.example.android_vio.data

data class ImuData(
    var timestamp: Long,
    var accX: Float,
    var accY: Float,
    var accZ: Float,
    var gyroX: Float,
    var gyroY: Float,
    var gyroZ: Float
)

data class MagnetometerData(
    var timestamp: Long,
    var x: Float,
    var y: Float,
    var z: Float,
)