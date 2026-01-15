package com.motovision.app.data.remote

import android.util.Log
import com.google.firebase.functions.ktx.functions
import com.google.firebase.ktx.Firebase
import com.motovision.app.data.local.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

object SyncManager {
    private const val TAG = "MotoSync"

    // 1. Khởi tạo Cloud Functions (Thay thế cho Firestore trực tiếp)
    // Dùng để gọi hàm xử lý logic gom nhóm trên Server
    private val functions = Firebase.functions

    /**
     * Đồng bộ dữ liệu chưa sync từ SQLite cục bộ lên Server qua Cloud Function
     */
    suspend fun syncData(database: AppDatabase): Boolean = withContext(Dispatchers.IO) {
        try {
            // 2. Lấy danh sách ổ gà chưa đồng bộ từ DAO (Giữ nguyên logic cũ)
            val unsyncedList = database.potholeDao().getUnsyncedPotholes()

            if (unsyncedList.isEmpty()) {
                Log.d(TAG, "Không có dữ liệu mới để đồng bộ.")
                return@withContext true
            }

            Log.d(TAG, "Đang chuẩn bị gửi ${unsyncedList.size} mục lên Cloud Function...")

            // Danh sách chứa các ID đã gửi thành công để đánh dấu sau này
            val successfulIds = mutableListOf<String>()

            // 3. Duyệt qua từng mục và gửi lên Cloud
            // Dùng vòng lặp thay vì gửi batch để xử lý lỗi từng cái riêng biệt
            unsyncedList.forEach { item ->
                try {
                    // Tạo gói dữ liệu (Payload) đúng chuẩn mà Cloud Function yêu cầu
                    // (Phải khớp với request.data trong file index.js)
                    val data = hashMapOf(
                        "id" to item.id,
                        "lat" to item.lat,
                        "lng" to item.lng,
                        "severity" to item.severity,
                        // Cloud Function JS dùng key 'imageUrl', trong khi Local dùng 'images'
                        "imageUrl" to item.images
                    )

                    // GỌI CLOUD FUNCTION: "uploadPothole"
                    // .await() giúp code chạy tuần tự, đợi Server trả lời rồi mới đi tiếp
                    val result = functions
                        .getHttpsCallable("uploadPothole")
                        .call(data)
                        .await()

                    // --- ĐÃ SỬA LỖI TẠI ĐÂY ---
                    // Sử dụng phương thức getter .getData() thay vì truy cập trực tiếp property .data
                    val response = result.getData() as? Map<String, Any>
                    val status = response?.get("status") ?: "unknown"

                    Log.d(TAG, "Upload Item ${item.id} -> Server: $status")

                    // Nếu code chạy đến đây mà không bị Exception thì coi như thành công
                    successfulIds.add(item.id)

                } catch (e: Exception) {
                    // Nếu 1 item bị lỗi (ví dụ mạng chập chờn), chỉ log lỗi và BỎ QUA item đó
                    // Các item khác vẫn tiếp tục được xử lý
                    Log.e(TAG, "Lỗi khi gửi item ${item.id}: ${e.message}")
                }
            }

            // 4. Cập nhật trạng thái "Đã Sync" vào Database nội bộ (SQLite)
            // Chỉ cập nhật những cái đã nằm trong danh sách successfulIds
            if (successfulIds.isNotEmpty()) {
                database.potholeDao().markAsSynced(successfulIds)
                Log.i(TAG, "Đã đồng bộ hoàn tất ${successfulIds.size}/${unsyncedList.size} mục.")
            }

            // Trả về true nếu có ít nhất 1 cái thành công
            return@withContext successfulIds.isNotEmpty()

        } catch (e: Exception) {
            // Lỗi tổng quát (ví dụ lỗi DB)
            Log.e(TAG, "Lỗi nghiêm trọng trong SyncManager: ${e.message}")
            return@withContext false
        }
    }
}