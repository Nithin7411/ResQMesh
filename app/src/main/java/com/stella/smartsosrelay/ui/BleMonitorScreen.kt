package com.stella.smartsosrelay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stella.smartsosrelay.services.BleLogEntry
import com.stella.smartsosrelay.services.DeviceIdentityManager
import com.stella.smartsosrelay.services.SosForegroundService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


@Composable
fun BleMonitorScreen(onBackClick: () -> Unit) {

    val bleMgr = SosForegroundService.bleManager

    val isAdvertising by bleMgr?.isAdvertising?.collectAsState() ?: remember { mutableStateOf(false) }
    val isScanning by bleMgr?.isScanning?.collectAsState() ?: remember { mutableStateOf(false) }
    val advertisingLog by bleMgr?.advertisingLog?.collectAsState() ?: remember { mutableStateOf(emptyList<BleLogEntry>()) }
    val scannedDevices by bleMgr?.scannedDevices?.collectAsState() ?: remember { mutableStateOf(emptyList<BleLogEntry>()) }

    // Use stable device identity
    val deviceUuid = DeviceIdentityManager.deviceUuid
    val deviceHex = DeviceIdentityManager.deviceHex

    // 🔥 Debug (you can remove later)
    LaunchedEffect(scannedDevices) {
        println("UI DEBUG → scannedDevices size = ${scannedDevices.size}")
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D0D0D))
    ) {

        // 🔥 SINGLE SCROLLABLE LIST (IMPORTANT FIX)
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp)
        ) {

            // ── HEADER ─────────────────────────────
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onBackClick) {
                        Text("← Back", color = Color(0xFF8AB4F8))
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        "BLE MONITOR",
                        color = Color(0xFF42A5F5),
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
            }

            // ── DEVICE IDENTITY ──────────────────────
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("THIS DEVICE", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    "Hash: $deviceHex",
                                    color = Color(0xFF42A5F5),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "UUID: ${deviceUuid.take(8)}…",
                                    color = Color.Gray,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp
                                )
                            }
                            // Identity stability indicator
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF1B5E20).copy(alpha = 0.5f))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    "🔒 Stable ID",
                                    color = Color(0xFF66BB6A),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
            }

            // ── STATUS ────────────────────────────
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    StatusCard("ADVERTISING", isAdvertising, advertisingLog.size, Color.Red, Modifier.weight(1f))
                    StatusCard("SCANNING", isScanning, scannedDevices.size, Color.Green, Modifier.weight(1f))
                }

                Spacer(modifier = Modifier.height(16.dp))
            }

            // ── OUTGOING ──────────────────────────
            item {
                Text("📡 OUTGOING", color = Color.Red)
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (advertisingLog.isEmpty()) {
                item { EmptyCard("No broadcasts") }
            } else {
                items(advertisingLog.sortedByDescending { it.timestamp }) {
                    BleLogCard(it, true)
                }
            }

            // ── INCOMING ──────────────────────────
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text("📻 INCOMING", color = Color.Green)
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (scannedDevices.isEmpty()) {
                item { EmptyCard("No scans yet") }
            } else {
                items(scannedDevices.sortedByDescending { it.timestamp }) {
                    BleLogCard(it, false)
                }
            }
        }
    }
}

@Composable
private fun StatusCard(
    label: String,
    active: Boolean,
    count: Int,
    activeColor: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = if (active) activeColor.copy(alpha = 0.15f) else Color(0xFF1A1A2E)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (active) activeColor else Color.Gray)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text("$count packets", color = Color.Gray, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun BleLogCard(entry: BleLogEntry, outgoing: Boolean) {
    val borderColor = if (outgoing) Color(0xFFEF5350) else Color(0xFF66BB6A)
    val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E)),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(modifier = Modifier.padding(12.dp)) {
            // Color indicator
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(64.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(borderColor)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                // Row 1: Device ID + Timestamp
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "ID: ${entry.deviceId.takeLast(8)}",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = sdf.format(Date(entry.timestamp)),
                        color = Color.Gray,
                        fontSize = 11.sp
                    )
                }

                Spacer(modifier = Modifier.height(3.dp))

                // Row 2: Event ID + Hop count
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    if (entry.eventIdHex.isNotBlank() && entry.eventIdHex != "00000000") {
                        Text(
                            text = "📋 EVT: ${entry.eventIdHex}",
                            color = Color(0xFFCE93D8),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    // Hop count badge
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(
                                    when (entry.hopCount) {
                                        0 -> Color(0xFF1B5E20).copy(alpha = 0.6f)
                                        1 -> Color(0xFF827717).copy(alpha = 0.6f)
                                        else -> Color(0xFFB71C1C).copy(alpha = 0.6f)
                                    }
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = if (entry.hopCount == 0) "DIRECT" else "HOP ${entry.hopCount}",
                                color = Color.White,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(3.dp))

                // Row 3: Location + Trigger
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        text = "📍 ${String.format("%.4f", entry.latitude)}, ${String.format("%.4f", entry.longitude)}",
                        color = Color(0xFF8AB4F8),
                        fontSize = 11.sp
                    )
                    val reasonLabel = when (entry.triggerReason) {
                        "M", "MANUAL" -> "🖐 Manual"
                        "S", "SENSOR" -> "📱 Sensor"
                        "V", "VOLUME_GESTURE" -> "🔊 Volume"
                        else -> "❓ ${entry.triggerReason}"
                    }
                    Text(text = reasonLabel, color = Color(0xFFFFA726), fontSize = 11.sp)
                }

                // Row 4: RSSI (incoming only)
                if (!outgoing && entry.rssi != 0) {
                    val rssiColor = when {
                        entry.rssi > -60 -> Color(0xFF66BB6A) // Strong
                        entry.rssi > -75 -> Color(0xFFFFA726) // Medium
                        else -> Color(0xFFEF5350)             // Weak
                    }
                    Text(
                        text = "Signal: ${entry.rssi} dBm",
                        color = rssiColor,
                        fontSize = 10.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyCard(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(
            text = text,
            color = Color.Gray,
            modifier = Modifier.padding(20.dp),
            textAlign = TextAlign.Center,
            fontSize = 13.sp
        )
    }
}
