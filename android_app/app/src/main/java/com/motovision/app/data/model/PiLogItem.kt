package com.motovision.app.data.model

import com.google.gson.annotations.SerializedName

data class PiLogItem(
    @SerializedName("id") val id: String = "",

    @SerializedName("timestamp")
    val timestamp: String = "",

    @SerializedName("lat")
    val lat: Double = 0.0, // Python gửi số -> Kotlin nhận Double (ĐÚNG)

    @SerializedName("lng")
    val lng: Double = 0.0, // Python gửi số -> Kotlin nhận Double (ĐÚNG)

    // --- SỬA DÒNG NÀY ---
    // Python gửi về số (ví dụ: 30.5), không phải chuỗi "30.5"
    // Nên phải đổi từ String sang Double
    @SerializedName("speed")
    val speed: Double = 0.0,
    // --------------------

    @SerializedName("severity")
    val severity: String = "Low",

    @SerializedName("imageFilename")
    val imageFilename: String = "",

    @SerializedName("imageUrl")
    val imageUrl: String = "",

    @SerializedName("isSynced")
    val isSynced: Boolean = false,

    var isSelected: Boolean = false
)