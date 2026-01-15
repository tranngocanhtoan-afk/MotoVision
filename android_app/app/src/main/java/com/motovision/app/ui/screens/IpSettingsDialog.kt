package com.motovision.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

@Composable
fun IpSettingsDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    currentIp: String
) {
    var ipInput by remember { mutableStateOf(currentIp) }
    var isScanning by remember { mutableStateOf(false) }
    var scanMessage by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cấu hình IP Raspberry Pi") },
        text = {
            Column {
                OutlinedTextField(
                    value = ipInput,
                    onValueChange = { ipInput = it },
                    label = { Text("Địa chỉ IP") },
                    placeholder = { Text("Ví dụ: 192.168.1.15") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                if (scanMessage.isNotEmpty()) {
                    Text(text = scanMessage, color = if(scanMessage.startsWith("Tìm thấy")) Color.Green else Color.Red)
                }
            }
        },
        confirmButton = {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                // NÚT QUÉT TỰ ĐỘNG (AUTO SCAN)
                TextButton(
                    onClick = {
                        if (!isScanning) {
                            isScanning = true
                            scanMessage = "Đang quét mạng..."
                            scope.launch(Dispatchers.IO) {
                                val foundIp = scanForPi()
                                withContext(Dispatchers.Main) {
                                    isScanning = false
                                    if (foundIp != null) {
                                        ipInput = foundIp
                                        scanMessage = "Tìm thấy Pi: $foundIp"
                                    } else {
                                        scanMessage = "Không tìm thấy thiết bị nào."
                                    }
                                }
                            }
                        }
                    },
                    enabled = !isScanning
                ) {
                    if (isScanning) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Đang quét...")
                    } else {
                        Text("Tự động tìm Pi")
                    }
                }

                // NÚT KẾT NỐI
                Button(onClick = { onConfirm(ipInput) }) {
                    Text("Lưu & Kết nối")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Hủy")
            }
        }
    )
}

// Hàm gửi UDP Broadcast để tìm Pi
fun scanForPi(): String? {
    var socket: DatagramSocket? = null
    try {
        socket = DatagramSocket()
        socket.broadcast = true
        socket.soTimeout = 2000 // Chờ tối đa 2 giây

        val sendData = "MOTO_DISCOVER".toByteArray()

        // Gửi gói tin quảng bá (Broadcast) đến toàn mạng LAN cổng 9999
        val sendPacket = DatagramPacket(
            sendData, sendData.size,
            InetAddress.getByName("255.255.255.255"), 9999
        )
        socket.send(sendPacket)

        // Chờ phản hồi
        val recvBuf = ByteArray(1024)
        val receivePacket = DatagramPacket(recvBuf, recvBuf.size)
        socket.receive(receivePacket)

        val message = String(receivePacket.data, 0, receivePacket.length).trim()
        if (message == "MOTO_HERE") {
            // Lấy IP của người gửi phản hồi
            return receivePacket.address.hostAddress
        }
    } catch (e: Exception) {
        e.printStackTrace()
    } finally {
        socket?.close()
    }
    return null
}