package com.stella.smartsosrelay.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stella.smartsosrelay.services.DeviceIdentityManager
import com.stella.smartsosrelay.services.SosForegroundService
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    viewModel: SosViewModel,
    onSettingsClick: () -> Unit,
    onSensorMonitorClick: () -> Unit,
    onBleMonitorClick: () -> Unit
) {
    var isCountdownActive by remember { mutableStateOf(false) }
    var countdownSeconds by remember { mutableStateOf(5) }
    var isDebugExpanded by remember { mutableStateOf(false) }

    // Collect live BLE/Sensor status
    val bleMgr = SosForegroundService.bleManager
    val fallHelper = SosForegroundService.fallDetectionHelper
    val isScanning by bleMgr?.isScanning?.collectAsState() ?: remember { mutableStateOf(false) }
    val isAdvertising by bleMgr?.isAdvertising?.collectAsState() ?: remember { mutableStateOf(false) }

    // Firestore userId
    val firestoreUserId by viewModel.firestoreUserId.collectAsState()

    LaunchedEffect(isCountdownActive) {
        if (isCountdownActive) {
            while (countdownSeconds > 0) {
                delay(1000)
                countdownSeconds--
            }
            if (countdownSeconds == 0) {
                viewModel.triggerSos("MANUAL")
                isCountdownActive = false
                countdownSeconds = 5
            }
        }
    }

    // Pulse animation for the SOS button
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF0D0D0D), Color(0xFF1A0000), Color(0xFF0D0D0D))
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top bar — Settings only (clean look)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onSettingsClick) {
                    Text("⚙ Settings", color = Color(0xFF8AB4F8), fontSize = 13.sp)
                }

                // Show SOS ID badge if available
                if (firestoreUserId.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF1A1A2E))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            "ID: $firestoreUserId",
                            color = Color(0xFF8AB4F8),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Title
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Smart SOS Relay",
                    color = Color.White,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Background protection active",
                    color = Color(0xFF66BB6A),
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Vol: ↓↓↑↑↑↓ = secret SOS trigger",
                    color = Color(0xFFFFA726).copy(alpha = 0.7f),
                    fontSize = 11.sp
                )
            }

            // Massive SOS Button with pulse
            Button(
                onClick = {
                    if (!isCountdownActive) {
                        isCountdownActive = true
                        countdownSeconds = 5
                    }
                },
                modifier = Modifier
                    .size(250.dp)
                    .scale(pulseScale)
                    .clip(CircleShape),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFD32F2F)
                ),
                elevation = ButtonDefaults.buttonElevation(
                    defaultElevation = 12.dp,
                    pressedElevation = 4.dp
                )
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "SOS",
                        color = Color.White,
                        fontSize = 64.sp,
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        text = "TAP FOR HELP",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 12.sp,
                        letterSpacing = 2.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Status bar
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .padding(20.dp)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    StatusIcon("SMS", Color(0xFF66BB6A))
                    StatusIcon("BLE", if (isScanning || isAdvertising) Color(0xFF42A5F5) else Color(0xFF555555))
                    StatusIcon("Sensor", if (fallHelper != null) Color(0xFFFFA726) else Color(0xFF555555))
                    StatusIcon("Cloud", Color(0xFF8AB4F8))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ── Debug Panel — Collapsible ─────────────────────────────────
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    // Header — clickable to expand/collapse
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isDebugExpanded = !isDebugExpanded }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("🔧", fontSize = 14.sp)
                            Text(
                                "Debug & Monitoring",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            // Live status dots
                            if (isScanning) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF42A5F5))
                                )
                            }
                            if (fallHelper != null) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFFFFA726))
                                )
                            }
                            Text(
                                if (isDebugExpanded) "▲" else "▼",
                                color = Color.Gray,
                                fontSize = 12.sp
                            )
                        }
                    }

                    // Expandable content
                    AnimatedVisibility(
                        visible = isDebugExpanded,
                        enter = expandVertically(),
                        exit = shrinkVertically()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Device identity info
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF222244))
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text(
                                        "DEVICE HASH",
                                        color = Color.Gray,
                                        fontSize = 9.sp,
                                        letterSpacing = 1.sp
                                    )
                                    Text(
                                        DeviceIdentityManager.deviceHex,
                                        color = Color(0xFF42A5F5),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        "UUID",
                                        color = Color.Gray,
                                        fontSize = 9.sp,
                                        letterSpacing = 1.sp
                                    )
                                    Text(
                                        "${DeviceIdentityManager.deviceUuid.take(8)}…",
                                        color = Color.Gray,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp
                                    )
                                }
                            }

                            // Quick status row
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // BLE Status chip
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            if (isScanning) Color(0xFF0D47A1).copy(alpha = 0.4f)
                                            else Color(0xFF222244)
                                        )
                                        .padding(8.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .clip(CircleShape)
                                                .background(if (isScanning) Color(0xFF42A5F5) else Color.Gray)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            if (isScanning) "BLE Active" else "BLE Off",
                                            color = Color.White,
                                            fontSize = 10.sp
                                        )
                                    }
                                }

                                // Sensor Status chip
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            if (fallHelper != null) Color(0xFFE65100).copy(alpha = 0.3f)
                                            else Color(0xFF222244)
                                        )
                                        .padding(8.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .clip(CircleShape)
                                                .background(if (fallHelper != null) Color(0xFFFFA726) else Color.Gray)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            if (fallHelper != null) "Sensor On" else "Sensor Off",
                                            color = Color.White,
                                            fontSize = 10.sp
                                        )
                                    }
                                }
                            }

                            // Navigation buttons
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = onSensorMonitorClick,
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = Color(0xFF81C784)
                                    )
                                ) {
                                    Text("📡 Sensors", fontSize = 12.sp)
                                }
                                OutlinedButton(
                                    onClick = onBleMonitorClick,
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = Color(0xFF42A5F5)
                                    )
                                ) {
                                    Text("📻 BLE", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Countdown overlay
        if (isCountdownActive) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.9f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "⚠️", fontSize = 48.sp)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Sending SOS in",
                        color = Color.White,
                        fontSize = 20.sp
                    )
                    Text(
                        text = "$countdownSeconds",
                        color = Color(0xFFEF5350),
                        fontSize = 72.sp,
                        fontWeight = FontWeight.Black
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = {
                            isCountdownActive = false
                            countdownSeconds = 5
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF424242)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.padding(horizontal = 32.dp)
                    ) {
                        Text(
                            "CANCEL",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun StatusIcon(label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 11.sp
        )
    }
}
