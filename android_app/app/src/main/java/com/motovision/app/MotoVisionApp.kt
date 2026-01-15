package com.motovision.app

import android.app.Application
import android.util.Log
import android.widget.Toast
import com.motovision.app.data.local.AppDatabase
import com.motovision.app.data.remote.SocketManager

class MotoVisionApp : Application() {
    // Khởi tạo Database
    val database by lazy { AppDatabase.getDatabase(this) }

    override fun onCreate() {
        super.onCreate()
        Log.e("MotoSocket", ">>> APP ĐANG KHỞI ĐỘNG - DATABASE INIT <<<")


        // 2. HIỆN THÔNG BÁO LÊN MÀN HÌNH (MỚI)
        Toast.makeText(this, "APP ĐANG KHỞI TẠO DATABASE...", Toast.LENGTH_LONG).show()

    }
}