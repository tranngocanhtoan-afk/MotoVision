package com.motovision.app.data.remote

import com.google.gson.annotations.SerializedName
import com.motovision.app.data.model.PiLogItem
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

interface PiApiService {
    // Lấy danh sách lịch sử
    @GET("/api/logs")
    suspend fun getPiLogs(): List<PiLogItem>

    // Gửi lệnh upload
    @POST("/api/upload")
    suspend fun triggerUpload(@Body request: UploadRequest): Response<ResponseBody>
}


data class UploadRequest(
    @SerializedName("imageFilename") val filename: String,
    @SerializedName("id") val id: String,
    @SerializedName("lat") val lat: Double,
    @SerializedName("lng") val lng: Double,
    @SerializedName("severity") val severity: String,
    @SerializedName("timestamp") val timestamp: String
)