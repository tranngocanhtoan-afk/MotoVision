package com.motovision.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.motovision.app.data.model.PiLogItem
import com.motovision.app.data.remote.PiNetworkClient
import com.motovision.app.data.remote.UploadRequest
import com.motovision.app.ui.theme.*
import com.motovision.app.utils.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefManager = remember { PreferenceManager(context) }

    // State lưu trữ danh sách ổ gà lấy từ Pi
    var logList by remember { mutableStateOf<List<PiLogItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }

    // Hàm tải dữ liệu từ API Flask trên Raspberry Pi
    fun loadData() {
        scope.launch(Dispatchers.IO) {
            try {
                isLoading = true
                val api = PiNetworkClient.getService(context)
                val data = api.getPiLogs()
                withContext(Dispatchers.Main) {
                    // Reset trạng thái isSelected về false để đảm bảo UI sạch khi tải lại
                    logList = data.map { it.copy(isSelected = false) }
                    isLoading = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isLoading = false
                    Toast.makeText(context, "Lỗi kết nối Pi: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Tự động fetch dữ liệu khi vào màn hình
    LaunchedEffect(Unit) { loadData() }

    // Hàm gửi lệnh yêu cầu Pi Upload ảnh lên Google Cloud
    fun handleUpload() {
        val selectedItems = logList.filter { it.isSelected && !it.isSynced }

        if (selectedItems.isEmpty()) {
            Toast.makeText(context, "Chọn ít nhất 1 mục chưa đồng bộ!", Toast.LENGTH_SHORT).show()
            return
        }

        scope.launch(Dispatchers.IO) {
            val api = PiNetworkClient.getService(context)
            var successCount = 0
            var errorMessage = ""

            selectedItems.forEach { item ->
                try {
                    // Cấu trúc Request phải khớp hoàn toàn với API Python trên Pi
                    val req = UploadRequest(
                        filename = item.imageFilename,
                        id = item.id,
                        lat = item.lat,
                        lng = item.lng,
                        severity = item.severity,
                        timestamp = item.timestamp
                    )

                    val res = api.triggerUpload(req)
                    if (res.isSuccessful) {
                        successCount++
                        // Cập nhật State cục bộ để UI phản hồi ngay lập tức
                        logList = logList.map {
                            if (it.id == item.id) it.copy(isSynced = true, isSelected = false) else it
                        }
                    } else {
                        errorMessage = "Pi trả về lỗi: ${res.code()}"
                    }
                } catch (e: Exception) {
                    errorMessage = "Lỗi kết nối mạng: ${e.message}"
                }
            }

            withContext(Dispatchers.Main) {
                if (successCount > 0) {
                    Toast.makeText(context, "Đã gửi yêu cầu đồng bộ cho $successCount mục!", Toast.LENGTH_SHORT).show()
                } else if (errorMessage.isNotEmpty()) {
                    Toast.makeText(context, errorMessage, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Dữ Liệu Trên Pi", color = CyberNeonBlue, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Quay lại", tint = Color.White)
                    }
                },
                actions = {
                    // Nút kích hoạt Upload đồng loạt
                    IconButton(onClick = { handleUpload() }) {
                        Icon(Icons.Default.CloudUpload, contentDescription = "Đồng bộ", tint = CyberNeonBlue)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = CyberBlack)
            )
        },
        containerColor = CyberBlack
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = CyberNeonBlue)
                }
            } else if (logList.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Không tìm thấy ổ gà nào trên thiết bị", color = Color.Gray)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(vertical = 16.dp)
                ) {
                    // Sử dụng key = it.id để Compose nhận diện đúng từng Item, tránh lỗi "chọn tất cả"
                    items(logList, key = { it.id }) { item ->
                        HistoryItemRow(
                            item = item,
                            onCheckChange = { isChecked ->
                                // Update State chỉ cho item cụ thể
                                logList = logList.map {
                                    if (it.id == item.id) it.copy(isSelected = isChecked) else it
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun HistoryItemRow(item: PiLogItem, onCheckChange: (Boolean) -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CyberDarkGrey),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Hiển thị ảnh thực tế từ Flask server (Pi)
            AsyncImage(
                model = item.imageUrl,
                contentDescription = "Pothole Evidence",
                modifier = Modifier
                    .size(80.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black),
                contentScale = ContentScale.Crop
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.timestamp,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Hiển thị trạng thái đồng bộ
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val statusColor = if (item.isSynced) CyberGreen else Color.Gray
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(RoundedCornerShape(50))
                            .background(statusColor)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (item.isSynced) "Đã lưu Cloud" else "Chỉ lưu máy",
                        color = statusColor,
                        fontSize = 12.sp
                    )
                }
            }

            // Logic Checkbox: Chỉ cho chọn nếu chưa đồng bộ
            if (item.isSynced) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Hoàn thành",
                    tint = CyberGreen,
                    modifier = Modifier.size(28.dp)
                )
            } else {
                IconButton(onClick = { onCheckChange(!item.isSelected) }) {
                    Icon(
                        imageVector = if (item.isSelected) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                        contentDescription = "Chọn",
                        tint = if (item.isSelected) CyberNeonBlue else Color.Gray,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
    }
}