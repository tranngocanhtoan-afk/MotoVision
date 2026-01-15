package com.motovision.app.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.Location
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.FileProvider
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContributeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Client để lấy vị trí GPS
    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }

    var capturedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var selectedSeverity by remember { mutableStateOf("Medium") }
    var isUploading by remember { mutableStateOf(false) }
    var tempImageUri by remember { mutableStateOf<Uri?>(null) }

    // Biến lưu vị trí tạm thời
    var currentLocation by remember { mutableStateOf<Location?>(null) }
    var locationStatus by remember { mutableStateOf("Đang lấy tọa độ...") }

    // Launcher chụp ảnh
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && tempImageUri != null) {
            val stream = context.contentResolver.openInputStream(tempImageUri!!)
            capturedBitmap = BitmapFactory.decodeStream(stream)
        }
    }

    // Launcher xin quyền: Cần cả Camera và Location
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
        val locationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false

        if (cameraGranted && locationGranted) {
            // Tạo file tạm để lưu ảnh
            val file = File.createTempFile("dataset_img_", ".jpg", context.cacheDir)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            tempImageUri = uri
            cameraLauncher.launch(uri)

            // Lấy tọa độ ngay khi có quyền
            scope.launch {
                currentLocation = getCurrentLocation(context, fusedLocationClient)
                locationStatus = if (currentLocation != null)
                    "GPS: ${currentLocation!!.latitude}, ${currentLocation!!.longitude}"
                else "Không lấy được GPS"
            }
        } else {
            Toast.makeText(context, "Cần quyền Camera & Vị trí để đóng góp dữ liệu!", Toast.LENGTH_LONG).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Đóng góp dữ liệu Train AI", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF121212))
            )
        },
        containerColor = Color(0xFF050505)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 1. KHUNG CHỤP ẢNH
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF1E1E1E))
                    .border(1.dp, Color.Gray, RoundedCornerShape(12.dp))
                    .clickable {
                        permissionLauncher.launch(arrayOf(
                            Manifest.permission.CAMERA,
                            Manifest.permission.ACCESS_FINE_LOCATION
                        ))
                    },
                contentAlignment = Alignment.Center
            ) {
                if (capturedBitmap != null) {
                    Image(
                        bitmap = capturedBitmap!!.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.CameraAlt, null, tint = Color.Gray, modifier = Modifier.size(48.dp))
                        Text("Chạm để chụp ảnh", color = Color.Gray)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("(Dữ liệu dùng để train Model)", color = Color.DarkGray, fontSize = 12.sp)
                    }
                }
            }

            // Hiển thị trạng thái GPS
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.GpsFixed, null, tint = Color.Gray, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(text = locationStatus, color = Color.Gray, fontSize = 12.sp)
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 2. LABELING (GÁN NHÃN)
            Text("Gán nhãn dữ liệu (Ground Truth):", color = Color.White, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                listOf("Low", "Medium", "High").forEach { level ->
                    FilterChip(
                        selected = selectedSeverity == level,
                        onClick = { selectedSeverity = level },
                        label = { Text(level) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF00FF9F), // Màu xanh CyberGreen
                            selectedLabelColor = Color.Black,
                            containerColor = Color(0xFF1E1E1E),
                            labelColor = Color.White
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // 3. NÚT GỬI (UPLOAD TO DATASET)
            Button(
                onClick = {
                    if (capturedBitmap == null) {
                        Toast.makeText(context, "Vui lòng chụp ảnh trước!", Toast.LENGTH_SHORT).show()
                    } else if (!isUploading) {
                        isUploading = true

                        // Lấy lại GPS lần cuối cho chắc chắn nếu chưa có
                        scope.launch {
                            if (currentLocation == null) {
                                currentLocation = getCurrentLocation(context, fusedLocationClient)
                            }

                            val lat = currentLocation?.latitude ?: 0.0
                            val lng = currentLocation?.longitude ?: 0.0

                            // Gọi hàm upload vào kho riêng
                            val success = uploadToDataset(capturedBitmap!!, selectedSeverity, lat, lng)

                            isUploading = false
                            if (success) {
                                Toast.makeText(context, "Đã gửi vào kho dữ liệu Dataset!", Toast.LENGTH_LONG).show()
                                capturedBitmap = null // Reset
                                onBack() // Quay lại Dashboard
                            } else {
                                Toast.makeText(context, "Lỗi upload! Kiểm tra mạng.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00FF9F)), // Màu xanh phân biệt với nút hệ thống
                shape = RoundedCornerShape(8.dp)
            ) {
                if (isUploading) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.Black)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Đang đóng góp...", color = Color.Black)
                } else {
                    Icon(Icons.Default.CloudUpload, null, tint = Color.Black)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("GỬI VÀO DATASET", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

// --- LOGIC BACKEND RIÊNG CHO TÍNH NĂNG ĐÓNG GÓP ---

/**
 * Hàm upload chuyên biệt:
 * 1. Lưu ảnh vào folder 'user_contributions' (Tách biệt folder hệ thống)
 * 2. Lưu metadata vào collection 'user_contributions' (Tách biệt database map)
 */
suspend fun uploadToDataset(bitmap: Bitmap, label: String, lat: Double, lng: Double): Boolean = withContext(Dispatchers.IO) {
    return@withContext try {
        val storage = FirebaseStorage.getInstance().reference
        val firestore = FirebaseFirestore.getInstance()

        // Tạo ID với prefix manual để dễ nhận diện
        val contributionId = "manual_${UUID.randomUUID()}"

        // 1. Chuẩn hóa ảnh cho AI (640x640, JPEG 60% chất lượng)
        val resized = Bitmap.createScaledBitmap(bitmap, 640, 640, true)
        val baos = ByteArrayOutputStream()
        resized.compress(Bitmap.CompressFormat.JPEG, 60, baos)
        val data = baos.toByteArray()

        // 2. Upload vào Folder riêng: user_contributions
        val fileRef = storage.child("user_contributions/$contributionId.jpg")
        fileRef.putBytes(data).await()

        val downloadUrl = fileRef.downloadUrl.await().toString()

        // 3. Lưu Metadata vào Collection riêng
        val datasetItem = hashMapOf(
            "id" to contributionId,
            "lat" to lat,
            "lng" to lng,
            "label" to label, // Nhãn do người dùng vote (Low/Medium/High)
            "severity_value" to when(label) { "High" -> 3; "Medium" -> 2; else -> 1 }, // Số hóa để tiện thống kê
            "image_url" to downloadUrl,
            "timestamp" to System.currentTimeMillis(),
            "device_model" to android.os.Build.MODEL, // Lưu tên máy để biết nguồn ảnh
            "is_reviewed" to false // Cờ để đánh dấu developer đã duyệt hay chưa
        )

        firestore.collection("user_contributions").document(contributionId).set(datasetItem).await()
        true
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
}

/**
 * Helper: Lấy vị trí GPS hiện tại (Yêu cầu quyền ACCESS_FINE_LOCATION)
 * Hàm này sử dụng FusedLocationProviderClient để lấy tọa độ chính xác nhất.
 */
@SuppressLint("MissingPermission")
suspend fun getCurrentLocation(context: Context, client: com.google.android.gms.location.FusedLocationProviderClient): Location? {
    // 1. Kiểm tra quyền lần cuối
    if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
        ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
        return null
    }

    return try {
        // CÁCH 1: Thử lấy vị trí lưu sẵn (Cache) - Nhanh, hoạt động tốt trong nhà
        val lastLocation = client.lastLocation.await()

        if (lastLocation != null) {
            android.util.Log.d("GPS", "Dùng LastLocation: ${lastLocation.latitude}")
            return lastLocation
        }

        // CÁCH 2: Nếu không có Cache, buộc phải quét vệ tinh (Tốn pin hơn, cần ra ngoài trời)
        android.util.Log.d("GPS", "LastLocation null, đang request GPS mới...")
        client.getCurrentLocation(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY, // Giảm xuống Balanced để dễ bắt được cả Wifi/4G (trong nhà cũng được)
            CancellationTokenSource().token
        ).await()
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}
