package com.motovision.app.data.remote

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.motovision.app.data.local.AppDatabase
import com.motovision.app.data.model.CommunityPothole
import com.motovision.app.data.model.Telemetry
import com.motovision.app.utils.Config
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.*
import org.json.JSONObject

object SocketManager {
    private const val TAG = "MotoSocket"
    private val client = OkHttpClient()

    // SỬA QUAN TRỌNG: Phải là 'var' để có thể gán lại giá trị
    private var webSocket: WebSocket? = null

    private var appContext: Context? = null
    private var appDatabase: AppDatabase? = null
    private val firestore = FirebaseFirestore.getInstance()

    private val _isConnected = MutableStateFlow(false)
    val isConnected = _isConnected.asStateFlow()

    private val _telemetryState = MutableStateFlow(Telemetry())
    val telemetryState = _telemetryState.asStateFlow()

    private val _potholes = MutableStateFlow<List<CommunityPothole>>(emptyList())
    val potholes = _potholes.asStateFlow()

    private var currentPhoneLat: Double = 0.0
    private var currentPhoneLng: Double = 0.0

    fun init(context: Context, db: AppDatabase) {
        this.appContext = context.applicationContext
        this.appDatabase = db
    }

    fun updatePhoneLocation(lat: Double, lng: Double) {
        currentPhoneLat = lat
        currentPhoneLng = lng
    }

    fun connect() {
        if (webSocket != null) return
        val context = appContext ?: return

        observeCloudData()

        val url = Config.getWsUrl(context)
        val request = Request.Builder().url(url).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _isConnected.value = true
                Log.d(TAG, "Đã kết nối Socket tới: $url")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                _isConnected.value = false
                Log.d(TAG, "Socket đang đóng: $reason")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _isConnected.value = false
                // Giải quyết lỗi 'val' cannot be reassigned:
                // Gán trực tiếp vào biến của Object
                this@SocketManager.webSocket = null
                Log.d(TAG, "Socket đã đóng hẳn.")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _isConnected.value = false
                Log.e(TAG, "Lỗi kết nối Socket: ${t.message}")
                // Giải quyết lỗi 'val' cannot be reassigned:
                this@SocketManager.webSocket = null
            }
        })
    }

    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type")

            when (type) {
                "telemetry" -> {
                    val data = json.getJSONObject("data")
                    _telemetryState.value = Telemetry(
                        speed = data.optDouble("speed", 0.0).toFloat(),
                        cpuTemp = data.optDouble("cpu_temp", 0.0).toFloat(),
                        fps = data.optInt("fps", 0),
                        storageAlert = data.optBoolean("storage_alert", false),
                        threshold = data.optDouble("current_threshold", 0.65).toFloat()
                    )
                }

                "pothole_detected" -> {
                    val data = json.getJSONObject("data")

                    // Lấy thông tin
                    val id = data.optString("id")
                    val lat = data.optDouble("lat", 0.0)
                    val lng = data.optDouble("lng", 0.0)
                    val severity = data.optInt("severity", 1)
                    val imageFilename = data.optString("image_filename", "")

                    // Tạo object
                    val pothole = CommunityPothole(
                        id = id,
                        lat = lat,
                        lng = lng,
                        severity = severity,
                        images = imageFilename, // Chỉ lưu tên file, chưa có link
                        timestamp = System.currentTimeMillis(),
                        isSynced = false
                    )

                    // 1. CHỈ LƯU VÀO DATABASE CỤC BỘ (Để hiện trong mục Lịch sử)
                    CoroutineScope(Dispatchers.IO).launch {
                        appDatabase?.potholeDao()?.insertPothole(pothole)
                    }

                    // 2. TUYỆT ĐỐI KHÔNG add vào _potholes.value
                    // XÓA ĐOẠN CODE CẬP NHẬT LIST Ở ĐÂY ĐI
                    // Map sẽ không hiện gì cả cho đến khi bạn bấm Upload
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi xử lý JSON: ${e.message}")
        }
    }

    private fun observeCloudData() {
        firestore.collection("potholes")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    Log.e(TAG, "Firestore Listen Error: ${e.message}")
                    return@addSnapshotListener
                }

                if (snapshot != null) {
                    val cloudList = snapshot.documents.mapNotNull { doc ->
                        doc.toObject(CommunityPothole::class.java)
                    }
                    val liveUnsynced = _potholes.value.filter { it.id.startsWith("live_") || !it.isSynced }
                    _potholes.value = (cloudList + liveUnsynced).distinctBy { it.id }
                }
            }
    }

    fun sendThreshold(value: Float) {
        webSocket?.let {
            val json = JSONObject()
            json.put("command", "set_threshold")
            json.put("value", value)
            it.send(json.toString())
        }
    }

    fun disconnect() {
        webSocket?.close(1000, "App closed")
        webSocket = null
        _isConnected.value = false
    }

    fun restartConnection() {
        disconnect()
        CoroutineScope(Dispatchers.IO).launch {
            kotlinx.coroutines.delay(500)
            connect()
        }
    }
}