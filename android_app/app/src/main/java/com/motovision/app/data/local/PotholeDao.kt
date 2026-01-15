package com.motovision.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.motovision.app.data.model.CommunityPothole

@Dao
interface PotholeDao {
    // Hàm lấy danh sách hiển thị lên màn hình History
    @Query("SELECT * FROM potholes ORDER BY timestamp DESC")
    fun getAllPotholesList(): List<CommunityPothole>

    // Hàm lấy danh sách chưa đồng bộ (để SyncManager dùng)
    @Query("SELECT * FROM potholes WHERE isSynced = 0")
    fun getUnsyncedPotholes(): List<CommunityPothole>

    // Hàm lưu ổ gà mới (SocketManager dùng)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPothole(pothole: CommunityPothole)

    // Hàm đánh dấu đã upload xong
    @Query("UPDATE potholes SET isSynced = 1 WHERE id IN (:ids)")
    suspend fun markAsSynced(ids: List<String>)

    // Hàm xóa tất cả (Nút thùng rác)
    @Query("DELETE FROM potholes")
    suspend fun clearAll()

    // Hàm xóa các mục đã chọn
    @Query("DELETE FROM potholes WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)
}