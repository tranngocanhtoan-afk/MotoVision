package com.motovision.app.ui.model

import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.clustering.ClusterItem
import com.motovision.app.data.model.CommunityPothole

// Class này bọc CommunityPothole lại để dùng cho thư viện Gom Nhóm (Clustering)
class PotholeClusterItem(
    val potholeData: CommunityPothole
) : ClusterItem {

    override fun getPosition(): LatLng {
        return LatLng(potholeData.lat, potholeData.lng)
    }

    override fun getTitle(): String {
        return "Mức độ: ${potholeData.severity}"
    }

    override fun getSnippet(): String {
        return "Chạm để xem chi tiết"
    }

    override fun getZIndex(): Float? = 0f
}