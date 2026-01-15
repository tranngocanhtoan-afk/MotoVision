package com.motovision.app.utils

import android.content.Context

object Config {

    // Hàm này sẽ được gọi mỗi khi cần kết nối
    fun getWsUrl(context: Context): String {
        val ip = PreferenceManager(context).getServerIp()
        return "ws://$ip:8765"
    }

    fun getStreamUrl(context: Context): String {
        val ip = PreferenceManager(context).getServerIp()
        return "http://$ip:8000/stream.mjpg"
    }

    const val CLOUD_API_URL = "https://script.google.com/macros/s/AKfycbzUwklck20636jlLPAu4dnReirvQgR4bvHl1GkQZnZbijJ3zNs7WLkbeHYvFuKwK88/exec"

}
