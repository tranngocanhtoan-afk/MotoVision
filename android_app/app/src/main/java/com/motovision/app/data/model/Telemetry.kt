package com.motovision.app.data.model

data class Telemetry(
    val speed: Float = 0f,
    val cpuTemp: Float = 0f,
    val fps: Int = 0,
    val storageAlert: Boolean = false,
    val threshold: Float = 0.65f
)