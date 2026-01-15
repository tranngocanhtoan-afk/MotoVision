package com.motovision.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.speech.tts.TextToSpeech
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import com.google.maps.android.compose.clustering.Clustering
import androidx.compose.material3.*
import com.motovision.app.ui.model.PotholeClusterItem
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.app.ActivityCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.maps.android.compose.*
import com.motovision.app.R
import com.motovision.app.data.model.CommunityPothole
import com.motovision.app.data.remote.SocketManager
import com.motovision.app.ui.theme.CyberBlack
import com.motovision.app.ui.theme.CyberGreen
import com.motovision.app.ui.theme.CyberNeonBlue
import com.motovision.app.ui.theme.CyberNeonRed
import com.motovision.app.ui.theme.CyberNeonYellow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun DashboardScreen(
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    // [THÊM MỚI] Tham số để mở màn hình Đóng góp
    onOpenContribute: () -> Unit,
    streamUrl: String,
    mapViewModel: MapViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // --- 1. DỮ LIỆU TỪ SOCKET (LIVE - THỜI GIAN THỰC) ---
    val isConnected = SocketManager.isConnected.collectAsState().value
    val telemetry = SocketManager.telemetryState.collectAsState().value
    val livePotholes = SocketManager.potholes.collectAsState().value

    // --- 2. DỮ LIỆU TỪ CLOUD (CỘNG ĐỒNG - GOOGLE SHEET) ---
    val cloudPotholes by mapViewModel.potholes.collectAsState()

    // --- 3. STATE UI (TRẠNG THÁI GIAO DIỆN) ---
    var showSettings by remember { mutableStateOf(false) } // Dialog cài đặt
    var isFlashing by remember { mutableStateOf(false) } // Hiệu ứng chớp đỏ
    var isSyncing by remember { mutableStateOf(false) } // Trạng thái đang đồng bộ
    var menuExpanded by remember { mutableStateOf(false) } // Menu 3 gạch

    // Biến lưu ổ gà đang được chọn để xem ảnh chi tiết
    var selectedPothole by remember { mutableStateOf<CommunityPothole?>(null) }
    var selectedClusterList by remember { mutableStateOf<List<CommunityPothole>?>(null) }

    // Database dùng cho chức năng Sync
    val database = (context.applicationContext as com.motovision.app.MotoVisionApp).database

    // --- 4. STATE LOCATION & MAP ---
    var isMapLoaded by remember { mutableStateOf(false) } // Để chỉ zoom lần đầu tiên mở app
    var isFollowingUser by remember { mutableStateOf(true) } // Chế độ "Bám theo người dùng"

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(10.7769, 106.6953), 15f)
    }

    // Tự động tắt chế độ "bám theo" khi người dùng chạm tay kéo bản đồ
    LaunchedEffect(cameraPositionState.isMoving) {
        if (cameraPositionState.isMoving && cameraPositionState.cameraMoveStartedReason == CameraMoveStartedReason.GESTURE) {
            isFollowingUser = false
        }
    }

    // Kiểm tra quyền vị trí
    var hasLocationPermission by remember {
        mutableStateOf(
            ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasLocationPermission = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }

    // Text To Speech (Giọng nói chị Google)
    val tts = remember {
        TextToSpeech(context) {}.apply { language = Locale.forLanguageTag("vi-VN") }
    }

    // --- 5. LIFECYCLE EFFECTS (XỬ LÝ KHI MÀN HÌNH KHỞI CHẠY) ---

    // Kết nối Socket và Tải Map khi mở màn hình
    LaunchedEffect(Unit) {
        SocketManager.connect()
        mapViewModel.loadDataFromCloud()
    }

    // Cảnh báo giọng nói & Chớp đỏ khi phát hiện ổ gà mới (LIVE)
    LaunchedEffect(livePotholes.size) {
        if (livePotholes.isNotEmpty()) {
            isFlashing = true
            val lastPothole = livePotholes.last()
            tts.speak("Cảnh báo ổ gà mức ${lastPothole.severity}", TextToSpeech.QUEUE_FLUSH, null, null)
            delay(500)
            isFlashing = false
        }
    }

    // Xử lý GPS và Camera Map đi theo người dùng
    LaunchedEffect(hasLocationPermission) {
        if (hasLocationPermission) {
            val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
            val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000).build()
            val locationCallback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    result.lastLocation?.let { location ->
                        SocketManager.updatePhoneLocation(location.latitude, location.longitude)
                        if (isFollowingUser) {
                            cameraPositionState.move(
                                CameraUpdateFactory.newLatLngZoom(
                                    LatLng(location.latitude, location.longitude),
                                    17f
                                )
                            )
                        }
                    }
                }
            }
            try {
                fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, android.os.Looper.getMainLooper())
            } catch (e: SecurityException) { e.printStackTrace() }
        } else {
            permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    // --- 6. GIAO DIỆN CHÍNH (LAYOUT) ---
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(CyberBlack) // Nền đen
                .safeDrawingPadding()
        ) {

            // ==========================================
            // PHẦN 1: CAMERA STREAM (Nửa trên)
            // ==========================================
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.4f)
                    .border(2.dp, CyberNeonBlue, CutCornerShape(bottomEnd = 20.dp, bottomStart = 20.dp))
                    .background(Color.Black)
            ) {
                // WebView hiển thị Stream từ Raspberry Pi
                key(streamUrl) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                settings.apply {
                                    loadWithOverviewMode = true
                                    useWideViewPort = true
                                    builtInZoomControls = false
                                    displayZoomControls = false
                                }
                                setBackgroundColor(0)
                                loadUrl(streamUrl)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // HUD hiển thị FPS và Nhiệt độ CPU
                Row(modifier = Modifier.align(Alignment.TopEnd).padding(top = 60.dp, end = 16.dp)) {
                    Text("FPS: ${telemetry.fps}", color = CyberNeonBlue, fontSize = 12.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("CPU: ${telemetry.cpuTemp}°C", color = CyberNeonYellow, fontSize = 12.sp)
                }

                // MENU 3 GẠCH (Góc trên trái)
                Box(modifier = Modifier.align(Alignment.TopStart).padding(top = 50.dp, start = 8.dp)) {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.Menu, "Menu", tint = Color.White, modifier = Modifier.size(32.dp))
                    }

                    // Nội dung Menu sổ xuống
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                        modifier = Modifier.background(Color(0xFF1E1E1E))
                    ) {
                        // Mục 1: Đồng bộ Cloud
                        DropdownMenuItem(
                            text = { Text("Đồng bộ Cloud", color = Color.White) },
                            leadingIcon = {
                                if (isSyncing) CircularProgressIndicator(modifier = Modifier.size(20.dp), color = CyberNeonBlue)
                                else Icon(Icons.Default.Share, null, tint = CyberNeonBlue)
                            },
                            onClick = {
                                menuExpanded = false
                                if (!isSyncing) {
                                    isSyncing = true
                                    Toast.makeText(context, "Đang đồng bộ...", Toast.LENGTH_SHORT).show()
                                    scope.launch(Dispatchers.IO) {
                                        val success = com.motovision.app.data.remote.SyncManager.syncData(database)
                                        withContext(Dispatchers.Main) {
                                            isSyncing = false
                                            Toast.makeText(context, if (success) "Đồng bộ XONG!" else "Lỗi đồng bộ!", Toast.LENGTH_SHORT).show()
                                            mapViewModel.loadDataFromCloud()
                                        }
                                    }
                                }
                            }
                        )

                        // [THÊM MỚI] Mục 2: Đóng góp dữ liệu (Nằm ngay trên Lịch sử)
                        DropdownMenuItem(
                            text = { Text("Đóng góp dữ liệu", color = Color.White) },
                            // Icon Camera màu Đỏ Neon
                            leadingIcon = { Icon(Icons.Default.CameraAlt, null, tint = CyberNeonRed) },
                            onClick = { menuExpanded = false; onOpenContribute() } // Chuyển màn hình
                        )

                        // Mục 3: Lịch sử
                        DropdownMenuItem(
                            text = { Text("Lịch sử phát hiện", color = Color.White) },
                            leadingIcon = { Icon(Icons.Default.DateRange, null, tint = Color.Yellow) },
                            onClick = { menuExpanded = false; onOpenHistory() }
                        )

                        HorizontalDivider(thickness = 0.5.dp, color = Color.Gray)

                        // Mục 4: Cài đặt độ nhạy
                        DropdownMenuItem(
                            text = { Text("Cài đặt độ nhạy", color = Color.White) },
                            leadingIcon = { Icon(Icons.Default.Settings, null, tint = Color.Gray) },
                            onClick = { menuExpanded = false; showSettings = true }
                        )

                        // Mục 5: Đổi IP
                        DropdownMenuItem(
                            text = { Text("Đổi IP Raspberry Pi", color = Color.White) },
                            leadingIcon = { Icon(Icons.Default.Settings, null, tint = CyberNeonBlue) },
                            onClick = { menuExpanded = false; onOpenSettings() }
                        )
                    }
                }
            }

            // ==========================================
            // PHẦN 2: THANH THÔNG SỐ TỐC ĐỘ (Giữa)
            // ==========================================
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.1f)
                    .background(Color(0xFF121212)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.padding(start = 16.dp)) {
                    Text("SPEED", color = Color.Gray, fontSize = 10.sp)
                    Text("${telemetry.speed}", color = CyberNeonBlue, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                }
                Box(
                    modifier = Modifier
                        .padding(end = 16.dp)
                        .background(if (isConnected) CyberGreen.copy(alpha = 0.2f) else Color.Red.copy(alpha = 0.2f))
                        .border(1.dp, if (isConnected) CyberGreen else Color.Red)
                        .padding(8.dp)
                ) {
                    Text(if (isConnected) "CONNECTED" else "DISCONNECTED", color = if (isConnected) CyberGreen else Color.Red, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            // ==========================================
            // PHẦN 3: BẢN ĐỒ GOOGLE MAP (Nửa dưới)
            // ==========================================
            val clusterItems = remember(cloudPotholes) {
                cloudPotholes.map { PotholeClusterItem(it) }
            }
            Box(modifier = Modifier.fillMaxWidth().weight(0.5f)) {
                GoogleMap(
                    modifier = Modifier.fillMaxSize(),
                    cameraPositionState = cameraPositionState,
                    properties = MapProperties(
                        mapStyleOptions = MapStyleOptions.loadRawResourceStyle(context, R.raw.map_style),
                        isMyLocationEnabled = hasLocationPermission
                    ),
                    uiSettings = MapUiSettings(
                        zoomControlsEnabled = false,
                        myLocationButtonEnabled = false,
                        compassEnabled = true
                    )
                ) {
                    Clustering(
                        items = clusterItems,
                        clusterItemContent = null,

                        // --- SỬA Ở ĐÂY ---
                        // Thêm ": PotholeClusterItem" để trình biên dịch hiểu rõ kiểu dữ liệu
                        onClusterItemClick = { item: PotholeClusterItem ->
                            selectedPothole = item.potholeData
                            true // Đã xử lý sự kiện
                        },
                        // -----------------

                        onClusterClick = { cluster ->
                            val currentZoom = cameraPositionState.position.zoom
                            scope.launch {
                                cameraPositionState.animate(
                                    CameraUpdateFactory.newLatLngZoom(
                                        cluster.position,
                                        currentZoom + 2f
                                    )
                                )
                            }
                            isFollowingUser = false

                            val items = cluster.items.map { it.potholeData }
                            selectedClusterList = items // Kích hoạt Dialog danh sách

                            true
                        },

                        clusterContent = { cluster ->
                            Surface(
                                shape = androidx.compose.foundation.shape.CircleShape,
                                color = Color.Blue,
                                contentColor = Color.White,
                                modifier = Modifier.size(40.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = cluster.size.toString(),
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    )
                }

                // CỤM NÚT ĐIỀU KHIỂN BẢN ĐỒ (Góc dưới phải)
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 80.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    SmallFloatingActionButton(
                        onClick = {
                            mapViewModel.loadDataFromCloud()
                            Toast.makeText(context, "Đang tải lại bản đồ...", Toast.LENGTH_SHORT).show()
                        },
                        containerColor = CyberBlack,
                        contentColor = CyberNeonBlue
                    ) {
                        Icon(Icons.Default.Refresh, "Reload")
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    FloatingActionButton(
                        onClick = { isFollowingUser = true },
                        containerColor = if (isFollowingUser) CyberNeonBlue else Color.Gray,
                        contentColor = Color.Black
                    ) {
                        Icon(Icons.Default.LocationOn, "My Location")
                    }
                }
            }
        }

        // ==========================================
        // CÁC LỚP PHỦ (DIALOG & OVERLAY)
        // ==========================================
        if (isFlashing) {
            Box(modifier = Modifier.fillMaxSize().border(8.dp, Color.Red.copy(alpha = 0.8f)))
        }

        if (showSettings) {
            SettingsDialog(
                currentThreshold = telemetry.threshold,
                onDismiss = { showSettings = false },
                onThresholdChange = { newValue -> SocketManager.sendThreshold(newValue) }
            )
        }

        if (selectedPothole != null) {
            PotholeDetailDialog(pothole = selectedPothole!!) {
                selectedPothole = null
            }
        }

        if (selectedClusterList != null) {
            ClusterListDialog(
                potholes = selectedClusterList!!,
                onDismiss = { selectedClusterList = null }
            )
        }
    }
}

// --- COMPOSABLE: DIALOG CÀI ĐẶT ---
@Composable
fun SettingsDialog(
    currentThreshold: Float,
    onDismiss: () -> Unit,
    onThresholdChange: (Float) -> Unit
) {
    var localSliderValue by remember { mutableStateOf(currentThreshold) }
    LaunchedEffect(currentThreshold) { localSliderValue = currentThreshold }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF121212),
        title = { Text("CÀI ĐẶT ĐỘ NHẠY", color = CyberNeonBlue) },
        text = {
            Column {
                Text("Ngưỡng phát hiện: ${(localSliderValue * 100).toInt()}%", color = Color.White)
                Slider(
                    value = localSliderValue,
                    onValueChange = { localSliderValue = it },
                    onValueChangeFinished = { onThresholdChange(localSliderValue) },
                    valueRange = 0.1f..0.9f,
                    colors = SliderDefaults.colors(thumbColor = CyberNeonBlue, activeTrackColor = CyberNeonBlue)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("ĐÓNG", color = CyberNeonBlue) }
        }
    )
}

// ---  COMPOSABLE: DIALOG CHI TIẾT Ổ GÀ ---
@Composable
fun PotholeDetailDialog(pothole: CommunityPothole, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            colors = CardDefaults.cardColors(containerColor = CyberBlack),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp)
                .border(1.dp, CyberNeonBlue, RoundedCornerShape(16.dp))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("BẰNG CHỨNG Ổ GÀ", color = CyberNeonBlue, fontWeight = FontWeight.Bold, fontSize = 18.sp)

                val imageList = remember(pothole.images) {
                    pothole.images.split(",\n", ",")
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Đã tìm thấy ${imageList.size} ảnh (Vuốt để xem)",
                    color = Color.Gray,
                    fontSize = 12.sp
                )

                Spacer(modifier = Modifier.height(8.dp))

                if (imageList.isNotEmpty()) {
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(imageList) { imageUrl ->
                            AsyncImage(
                                model = imageUrl,
                                contentDescription = "Evidence",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .width(280.dp)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.DarkGray)
                                    .border(1.dp, Color.Gray, RoundedCornerShape(8.dp))
                            )
                        }
                    }
                } else {
                    Box(modifier = Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                        Text("Không có ảnh", color = Color.Gray)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("ID:", color = Color.Gray)
                    Text(
                        if(pothole.id.length > 15) pothole.id.take(15)+"..." else pothole.id,
                        color = Color.White
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Mức độ:", color = Color.White)
                    Text(
                        if(pothole.severity >= 3) "NGUY HIỂM" else "Trung bình",
                        color = if(pothole.severity >= 3) CyberNeonRed else Color.Yellow,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = CyberNeonBlue),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Đóng", color = CyberBlack, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}


@Composable
fun ClusterListDialog(
    potholes: List<CommunityPothole>,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            colors = CardDefaults.cardColors(containerColor = CyberBlack),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f) // Chiếm 80% chiều cao màn hình
                .border(1.dp, CyberNeonBlue, RoundedCornerShape(16.dp))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Tiêu đề
                Text(
                    "CỤM ${potholes.size} Ổ GÀ",
                    color = CyberNeonBlue,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Danh sách các điểm trong khu vực này",
                    color = Color.Gray,
                    fontSize = 12.sp
                )

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 12.dp),
                    color = Color.DarkGray
                )

                // Danh sách cuộn (LazyColumn)
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(potholes) { item ->
                        ClusterItemRow(item)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Nút đóng
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E1E1E)),
                    modifier = Modifier.fillMaxWidth().border(1.dp, Color.Gray, RoundedCornerShape(24.dp))
                ) {
                    Text("Đóng", color = Color.White)
                }
            }
        }
    }
}

@Composable
fun ClusterItemRow(pothole: CommunityPothole) {
    // Lấy ảnh đầu tiên để hiển thị thumbnail
    val firstImage = pothole.images.split(",")[0].trim()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF121212), RoundedCornerShape(8.dp))
            .border(1.dp, Color.DarkGray, RoundedCornerShape(8.dp))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Ảnh thumbnail
        AsyncImage(
            model = firstImage,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(60.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.Gray)
        )

        Spacer(modifier = Modifier.width(12.dp))

        // Thông tin
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "ID: ...${pothole.id.takeLast(6)}",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Badge mức độ
                Surface(
                    color = if (pothole.severity >= 3) CyberNeonRed.copy(alpha=0.2f) else CyberNeonYellow.copy(alpha=0.2f),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.border(1.dp, if (pothole.severity >= 3) CyberNeonRed else CyberNeonYellow, RoundedCornerShape(4.dp))
                ) {
                    Text(
                        text = if (pothole.severity >= 3) " NGUY HIỂM " else " TRUNG BÌNH ",
                        color = if (pothole.severity >= 3) CyberNeonRed else CyberNeonYellow,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(2.dp)
                    )
                }
            }
        }
    }
}