package com.motovision.app.data.remote

import android.content.Context
import com.motovision.app.utils.PreferenceManager
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object PiNetworkClient {
    private var retrofit: Retrofit? = null
    private var currentBaseUrl: String? = null

    fun getService(context: Context): PiApiService {
        val ip = PreferenceManager(context).getServerIp()
        val baseUrl = "http://$ip:8000/" // Cổng 8000 là Flask Server

        // Nếu IP đổi hoặc chưa có Retrofit thì tạo mới
        if (retrofit == null || currentBaseUrl != baseUrl) {
            currentBaseUrl = baseUrl
            retrofit = Retrofit.Builder()
                .baseUrl(baseUrl)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
        }
        return retrofit!!.create(PiApiService::class.java)
    }
}