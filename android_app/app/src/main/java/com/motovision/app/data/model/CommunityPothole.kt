package com.motovision.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "potholes")
data class CommunityPothole(
    @PrimaryKey val id: String = "", // Firebase cần giá trị mặc định
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val severity: Int = 1,
    val images: String = "",
    val timestamp: Long = 0L,
    var isSynced: Boolean = false
) {
    // Constructor trống cho Firebase
    constructor() : this("", 0.0, 0.0, 1, "", 0L, false)
}