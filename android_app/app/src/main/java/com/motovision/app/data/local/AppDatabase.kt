package com.motovision.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.motovision.app.data.model.CommunityPothole // Import đúng model mới

@Database(entities = [CommunityPothole::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun potholeDao(): PotholeDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "motovision_db"
                )
                    .fallbackToDestructiveMigration() // THÊM DÒNG NÀY: Để không bị lỗi khi đổi Entity
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}