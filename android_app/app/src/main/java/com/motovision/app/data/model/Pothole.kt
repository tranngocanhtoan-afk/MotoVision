package com.motovision.app.data.model

data class Pothole(
    val id: String,
    val lat: Double,
    val lng: Double,
    val severity: Int, // 1: Nhẹ, 2: Vừa, 3: Nguy hiểm
    val confidence: Float,
    val imageFile: String
)