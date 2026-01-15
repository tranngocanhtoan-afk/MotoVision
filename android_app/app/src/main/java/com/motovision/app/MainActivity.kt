package com.motovision.app

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
// Import các màn hình UI
import com.motovision.app.ui.screens.DashboardScreen
import com.motovision.app.ui.screens.HistoryScreen
import com.motovision.app.ui.screens.ContributeScreen
import com.motovision.app.ui.theme.MotoVisionTheme
import com.motovision.app.utils.PreferenceManager
import com.motovision.app.ui.IpSettingsDialog
import com.motovision.app.utils.Config

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. KHỞI TẠO CƠ SỞ DỮ LIỆU & SOCKET
        val database = (application as MotoVisionApp).database
        // Khởi động Socket để lắng nghe tín hiệu từ Raspberry Pi
        com.motovision.app.data.remote.SocketManager.init(this, database)

        // 2. CẤU HÌNH GIAO DIỆN HỆ THỐNG
        enableEdgeToEdge() // Cho phép app tràn viền
        // Ẩn thanh trạng thái (Status bar)
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.statusBars())

        setContent {
            // --- KHU VỰC 3: QUẢN LÝ TRẠNG THÁI (STATE) ---
            val context = LocalContext.current

            // Quản lý lưu trữ cài đặt (IP Address)
            val prefManager = remember { PreferenceManager(context) }

            // Biến điều hướng: "dashboard", "history", "contribute"
            var currentScreen by remember { mutableStateOf("dashboard") }

            // Biến trạng thái hiển thị popup IP
            var showIpDialog by remember { mutableStateOf(false) }

            // URL Camera stream
            var currentStreamUrl by remember {
                mutableStateOf(Config.getStreamUrl(context))
            }

            MotoVisionTheme {
                // --- KHU VỰC 4: ĐIỀU HƯỚNG MÀN HÌNH ---
                when (currentScreen) {

                    // TRƯỜNG HỢP 1: MÀN HÌNH CHÍNH (DASHBOARD)
                    "dashboard" -> {
                        DashboardScreen(
                            onOpenHistory = { currentScreen = "history" },
                            // [QUAN TRỌNG] Thêm dòng này để sửa lỗi:
                            onOpenContribute = { currentScreen = "contribute" },
                            onOpenSettings = { showIpDialog = true },
                            streamUrl = currentStreamUrl
                        )
                    }

                    // TRƯỜNG HỢP 2: MÀN HÌNH LỊCH SỬ
                    "history" -> {
                        HistoryScreen(
                            onBack = { currentScreen = "dashboard" }
                            // Lưu ý: Đã xóa onNavigateToContribute ở đây vì nút đó đã chuyển sang Dashboard
                        )
                    }

                    // TRƯỜNG HỢP 3: MÀN HÌNH ĐÓNG GÓP DỮ LIỆU
                    "contribute" -> {
                        ContributeScreen(
                            // Sửa lại: Bấm Back thì về Dashboard (Menu chính)
                            onBack = { currentScreen = "dashboard" }
                        )
                    }
                }

                // --- KHU VỰC 5: POPUP CÀI ĐẶT IP ---
                if (showIpDialog) {
                    IpSettingsDialog(
                        currentIp = prefManager.getServerIp(),
                        onDismiss = { showIpDialog = false },
                        onConfirm = { newIp ->
                            prefManager.setServerIp(newIp)
                            com.motovision.app.data.remote.SocketManager.restartConnection()
                            currentStreamUrl = "http://$newIp:8000/stream.mjpg"
                            showIpDialog = false
                            Toast.makeText(context, "Đang kết nối tới $newIp...", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        }
    }
}