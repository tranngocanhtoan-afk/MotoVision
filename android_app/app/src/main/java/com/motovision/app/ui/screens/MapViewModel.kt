package com.motovision.app.ui.screens

import android.util.Log
import androidx.lifecycle.ViewModel
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.motovision.app.data.model.CommunityPothole
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class MapViewModel : ViewModel() {

    // 1. State chứa danh sách ổ gà (Dùng lại Flow cũ để DashboardScreen không bị lỗi)
    private val _potholes = MutableStateFlow<List<CommunityPothole>>(emptyList())
    val potholes = _potholes.asStateFlow()

    // 2. Khởi tạo Firestore
    private val firestore = FirebaseFirestore.getInstance()

    // Biến để quản lý việc đóng/mở lắng nghe dữ liệu
    private var potholeListener: ListenerRegistration? = null

    /**
     * Sửa lại hàm loadDataFromCloud:
     * Thay vì gọi HTTP GET một lần, ta dùng SnapshotListener để dữ liệu tự cập nhật realtime.
     */
    fun loadDataFromCloud() {
        // Hủy lắng nghe cũ nếu có để tránh rò rỉ bộ nhớ
        potholeListener?.remove()

        Log.d("MapViewModel", "Bắt đầu lắng nghe dữ liệu từ Firebase Firestore...")

        // 3. Truy vấn đến collection "potholes"
        potholeListener = firestore.collection("potholes")
            .orderBy("timestamp", Query.Direction.DESCENDING) // Sắp xếp mới nhất lên đầu
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    Log.e("MapViewModel", "Lỗi lắng nghe Firestore: ${e.message}")
                    return@addSnapshotListener
                }

                if (snapshot != null) {
                    // Chuyển đổi dữ liệu từ Firestore sang danh sách Model CommunityPothole
                    val list = snapshot.documents.mapNotNull { doc ->
                        try {
                            // Firestore tự động ánh xạ các field vào object nếu tên biến trùng khớp
                            doc.toObject(CommunityPothole::class.java)
                        } catch (exception: Exception) {
                            Log.e("MapViewModel", "Lỗi parse dữ liệu: ${exception.message}")
                            null
                        }
                    }

                    // 4. Cập nhật UI (DashboardScreen sẽ tự động vẽ lại Marker)
                    _potholes.value = list
                    Log.d("MapViewModel", "Đã cập nhật realtime ${list.size} ổ gà từ Firebase")
                }
            }
    }

    /**
     * Giải phóng tài nguyên khi ViewModel bị hủy
     */
    override fun onCleared() {
        super.onCleared()
        potholeListener?.remove()
    }
}