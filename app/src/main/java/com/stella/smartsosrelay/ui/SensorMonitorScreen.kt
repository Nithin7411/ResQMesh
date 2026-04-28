package com.stella.smartsosrelay.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import com.stella.smartsosrelay.services.FallDetectionHelper
import com.stella.smartsosrelay.services.FallDetectionPhase
import com.stella.smartsosrelay.services.SensorReading
import com.stella.smartsosrelay.services.SosForegroundService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SensorMonitorScreen(
    viewModel: SosViewModel,
    onBackClick: () -> Unit
) {
    val fallHelper = SosForegroundService.fallDetectionHelper

    val currentReading by fallHelper?.currentReading?.collectAsState()
        ?: remember { mutableStateOf(SensorReading()) }
    val triggerCount by fallHelper?.triggerCount?.collectAsState()
        ?: remember { mutableStateOf(0) }
    val triggerLog by fallHelper?.triggerLog?.collectAsState()
        ?: remember { mutableStateOf(emptyList<SensorReading>()) }
    val currentPhase by fallHelper?.currentPhaseFlow?.collectAsState()
        ?: remember { mutableStateOf(FallDetectionPhase.IDLE) }
    val allEvents by viewModel.allEvents.collectAsState()

    val reading = currentReading ?: SensorReading()
    val count = triggerCount ?: 0
    val log = triggerLog ?: emptyList()
    val phase = currentPhase ?: FallDetectionPhase.IDLE

    // Flash background red briefly when a trigger happens
    val bgColor by animateColorAsState(
        targetValue = if (count > 0 && log.isNotEmpty() &&
            System.currentTimeMillis() - log.last().timestamp < 1000
        ) Color(0xFF4A0000) else Color(0xFF0D0D0D),
        animationSpec = tween(500),
        label = "bg"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onBackClick) {
                    Text("← Back", color = Color(0xFF8AB4F8), fontSize = 16.sp)
                }
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    "SENSOR MONITOR",
                    color = Color(0xFF8AB4F8),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 3.sp
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ── Detection Phase Indicator ────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = when (phase) {
                        FallDetectionPhase.IDLE -> Color(0xFF1A1A2E)
                        FallDetectionPhase.FREEFALL_DETECTED -> Color(0xFF1A3A5C)
                        FallDetectionPhase.IMPACT_DETECTED -> Color(0xFF5C3A1A)
                        FallDetectionPhase.STILLNESS_CHECK -> Color(0xFF3A1A5C)
                        FallDetectionPhase.TRIGGERED -> Color(0xFF5C1A1A)
                    }
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "DETECTION PHASE",
                        color = Color.Gray,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    // Phase pipeline
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PhaseChip("IDLE", phase == FallDetectionPhase.IDLE, Color(0xFF66BB6A))
                        Text("→", color = Color.Gray, fontSize = 10.sp)
                        PhaseChip("FREEFALL", phase == FallDetectionPhase.FREEFALL_DETECTED, Color(0xFF42A5F5))
                        Text("→", color = Color.Gray, fontSize = 10.sp)
                        PhaseChip("IMPACT", phase == FallDetectionPhase.IMPACT_DETECTED || phase == FallDetectionPhase.STILLNESS_CHECK, Color(0xFFFFA726))
                        Text("→", color = Color.Gray, fontSize = 10.sp)
                        PhaseChip("FALL!", phase == FallDetectionPhase.TRIGGERED, Color(0xFFEF5350))
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Phase description
                    Text(
                        text = when (phase) {
                            FallDetectionPhase.IDLE -> "Monitoring for freefall pattern..."
                            FallDetectionPhase.FREEFALL_DETECTED -> "⚡ Freefall detected! Waiting for impact..."
                            FallDetectionPhase.IMPACT_DETECTED -> "💥 Waiting for high-G impact..."
                            FallDetectionPhase.STILLNESS_CHECK -> "🔍 Impact detected! Checking stillness..."
                            FallDetectionPhase.TRIGGERED -> "🚨 FALL CONFIRMED — SOS TRIGGERED!"
                        },
                        color = Color.White,
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Service status
            val serviceRunning = fallHelper != null
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (serviceRunning) Color(0xFF1B3A1B) else Color(0xFF3A1B1B)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (serviceRunning) Color(0xFF4CAF50) else Color.Red)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = if (serviceRunning) "Background Service ACTIVE" else "Service NOT RUNNING",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Live accelerometer readings
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "LIVE ACCELEROMETER",
                        color = Color(0xFF8AB4F8),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        AxisValue("X", reading.x, Color(0xFFEF5350))
                        AxisValue("Y", reading.y, Color(0xFF66BB6A))
                        AxisValue("Z", reading.z, Color(0xFF42A5F5))
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = Color(0xFF333355))
                    Spacer(modifier = Modifier.height(10.dp))

                    // Magnitude bar with zone indicators
                    Text("MAGNITUDE", color = Color.Gray, fontSize = 10.sp, letterSpacing = 1.sp)
                    Spacer(modifier = Modifier.height(4.dp))

                    // Zone labels
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Freefall", color = Color(0xFF42A5F5).copy(alpha = 0.5f), fontSize = 8.sp)
                        Text("Normal", color = Color(0xFF66BB6A).copy(alpha = 0.5f), fontSize = 8.sp)
                        Text("Impact", color = Color(0xFFEF5350).copy(alpha = 0.5f), fontSize = 8.sp)
                    }
                    Spacer(modifier = Modifier.height(2.dp))

                    // Multi-zone magnitude bar
                    val magnitudeColor = when {
                        reading.magnitude < FallDetectionHelper.FREEFALL_THRESHOLD -> Color(0xFF42A5F5) // Freefall zone
                        reading.magnitude > FallDetectionHelper.IMPACT_THRESHOLD -> Color(0xFFEF5350)   // Impact zone
                        reading.magnitude > 15f -> Color(0xFFFFA726)                                     // High
                        else -> Color(0xFF66BB6A)                                                         // Normal
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(10.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .background(Color(0xFF222244))
                        ) {
                            // Freefall zone marker
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(fraction = (FallDetectionHelper.FREEFALL_THRESHOLD / 40f))
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(Color(0xFF42A5F5).copy(alpha = 0.15f))
                            )
                            // Actual magnitude
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(fraction = (reading.magnitude / 40f).coerceIn(0f, 1f))
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(magnitudeColor)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = String.format("%.1f", reading.magnitude),
                            color = magnitudeColor,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Threshold info
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "Freefall: <${FallDetectionHelper.FREEFALL_THRESHOLD} m/s²",
                            color = Color(0xFF42A5F5).copy(alpha = 0.7f),
                            fontSize = 9.sp
                        )
                        Text(
                            "Impact: >${FallDetectionHelper.IMPACT_THRESHOLD} m/s²",
                            color = Color(0xFFEF5350).copy(alpha = 0.7f),
                            fontSize = 9.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Trigger counter
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF1A1A2E)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            "FALL TRIGGERS",
                            color = Color(0xFF8AB4F8),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp
                        )
                        Text(
                            "Freefall→Impact→Stillness confirmed",
                            color = Color.Gray,
                            fontSize = 10.sp
                        )
                    }
                    Text(
                        text = "$count",
                        color = if (count > 0) Color(0xFFEF5350) else Color(0xFF66BB6A),
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Event log
            Text(
                "EVENT LOG",
                color = Color(0xFF8AB4F8),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                modifier = Modifier.padding(start = 4.dp)
            )
            Spacer(modifier = Modifier.height(6.dp))

            val listState = rememberLazyListState()
            LaunchedEffect(allEvents.size) {
                if (allEvents.isNotEmpty()) {
                    listState.animateScrollToItem(0)
                }
            }

            if (allEvents.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        "No SOS events yet.\nFall pattern: freefall (drop) → impact (hit ground) → stillness",
                        color = Color.Gray,
                        modifier = Modifier.padding(16.dp),
                        textAlign = TextAlign.Center,
                        fontSize = 12.sp
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(allEvents.sortedByDescending { it.timestamp }) { event ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = when (event.status) {
                                    "PENDING" -> Color(0xFF3A2A1B)
                                    "SENT_SMS" -> Color(0xFF1B3A1B)
                                    "SENT_FIREBASE" -> Color(0xFF1B2A3A)
                                    else -> Color(0xFF1A1A2E)
                                }
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = event.eventId.takeLast(20),
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = formatTime(event.timestamp),
                                        color = Color.Gray,
                                        fontSize = 10.sp
                                    )
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    val statusColor = when (event.status) {
                                        "PENDING" -> Color(0xFFFFA726)
                                        "SENT_SMS" -> Color(0xFF66BB6A)
                                        "SENT_FIREBASE" -> Color(0xFF42A5F5)
                                        else -> Color.Gray
                                    }
                                    Text(
                                        text = event.status,
                                        color = statusColor,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = event.triggerReason,
                                        color = Color.Gray,
                                        fontSize = 9.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PhaseChip(label: String, active: Boolean, activeColor: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (active) activeColor.copy(alpha = 0.3f)
                else Color(0xFF222244)
            )
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            color = if (active) activeColor else Color.Gray,
            fontSize = 8.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            letterSpacing = 0.5.sp
        )
    }
}

@Composable
private fun AxisValue(label: String, value: Float, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = String.format("%+.2f", value),
            color = Color.White,
            fontSize = 16.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
        Text("m/s²", color = Color.Gray, fontSize = 9.sp)
    }
}

private fun formatTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
