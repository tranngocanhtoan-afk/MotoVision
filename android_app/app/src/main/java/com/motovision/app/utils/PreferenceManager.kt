package com.motovision.app.utils

import android.content.Context
import android.content.SharedPreferences

class PreferenceManager(context: Context) {
    private val sharedPreferences: SharedPreferences =
        context.getSharedPreferences("MotoVisionPrefs", Context.MODE_PRIVATE)

    // Lưu IP mới vào máy
    fun setServerIp(ip: String) {
        sharedPreferences.edit().putString("server_ip", ip).apply()
    }

    // Đọc IP đã lưu, nếu chưa có thì lấy mặc định là 192.168.1.1
    fun getServerIp(): String {
        return sharedPreferences.getString("server_ip", "192.168.1.1") ?: "192.168.1.1"
    }
}