package com.stella.smartsosrelay

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.stella.smartsosrelay.services.OemBatteryHelper
import com.stella.smartsosrelay.services.SosForegroundService
import com.stella.smartsosrelay.services.VolumeGestureHelper
import com.stella.smartsosrelay.ui.BleMonitorScreen
import com.stella.smartsosrelay.ui.HomeScreen
import com.stella.smartsosrelay.ui.SensorMonitorScreen
import com.stella.smartsosrelay.ui.SetupScreen
import com.stella.smartsosrelay.ui.SosViewModel
import com.stella.smartsosrelay.ui.SosViewModelFactory

class MainActivity : ComponentActivity() {

    private var sosViewModel: SosViewModel? = null

    private val volumeGestureHelper = VolumeGestureHelper {
        sosViewModel?.triggerSos("VOLUME_GESTURE")
        runOnUiThread {
            Toast.makeText(this, "🆘 SOS TRIGGERED via Volume Gesture!", Toast.LENGTH_LONG).show()
        }
    }

    // Step 1: Request core permissions
    private val corePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        // After core permissions, request background location separately (required by Android 10+)
        requestBackgroundLocation()
    }

    // Step 2: Request background location (must be done AFTER foreground location is granted)
    private val backgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        handleBatteryOptimization()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestCorePermissions()
        enableEdgeToEdge()

        setContent {
            val darkColors = darkColorScheme(
                background = Color(0xFF0D0D0D),
                surface = Color(0xFF1A1A2E),
                primary = Color(0xFFD32F2F),
                onPrimary = Color.White,
                onBackground = Color.White,
                onSurface = Color.White
            )

            MaterialTheme(colorScheme = darkColors) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val application = application as SmartSosApplication
                    val vm: SosViewModel = viewModel(
                        factory = SosViewModelFactory(application.repository, application)
                    )
                    sosViewModel = vm

                    val navController = rememberNavController()
                    val user by vm.user.collectAsState()
                    val startDestination = if (user != null) "home" else "setup"

                    NavHost(navController = navController, startDestination = startDestination) {
                        composable("setup") {
                            SetupScreen(viewModel = vm, onSetupComplete = {
                                navController.navigate("home") {
                                    popUpTo("setup") { inclusive = true }
                                }
                            })
                        }
                        composable("home") {
                            HomeScreen(
                                viewModel = vm,
                                onSettingsClick = { navController.navigate("setup") },
                                onSensorMonitorClick = { navController.navigate("sensor_monitor") },
                                onBleMonitorClick = { navController.navigate("ble_monitor") }
                            )
                        }
                        composable("sensor_monitor") {
                            SensorMonitorScreen(
                                viewModel = vm,
                                onBackClick = { navController.popBackStack() }
                            )
                        }
                        composable("ble_monitor") {
                            BleMonitorScreen(
                                onBackClick = { navController.popBackStack() }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (volumeGestureHelper.onKeyPress(keyCode)) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * Step 1: Request all core runtime permissions in one batch.
     * Background location MUST be requested separately after foreground location is granted.
     */
    private fun requestCorePermissions() {
        val permissions = mutableListOf<String>()

        // SMS
        permissions.add(Manifest.permission.SEND_SMS)

        // Location (foreground)
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)

        // Bluetooth (API 31+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        // Notifications (API 33+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isNotEmpty()) {
            corePermissionLauncher.launch(notGranted.toTypedArray())
        } else {
            requestBackgroundLocation()
        }
    }

    /**
     * Step 2: Request ACCESS_BACKGROUND_LOCATION separately.
     * Android 10+ requires this to be requested AFTER foreground location is granted.
     */
    private fun requestBackgroundLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                != PackageManager.PERMISSION_GRANTED
            ) {
                backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                return
            }
        }
        handleBatteryOptimization()
    }

    /**
     * Step 3: Handle battery optimization exemption for OEM devices.
     * Then start the foreground service.
     */
    private fun handleBatteryOptimization() {
        // Request battery optimization exemption
        if (!OemBatteryHelper.isIgnoringBatteryOptimizations(this)) {
            OemBatteryHelper.requestBatteryOptimizationExemption(this)
        }

        // If aggressive OEM, try to open AutoStart settings
        if (OemBatteryHelper.isAggressiveOem()) {
            val brand = OemBatteryHelper.getManufacturerName()
            // Only show once — use SharedPreferences to track
            val prefs = getSharedPreferences("sos_prefs", MODE_PRIVATE)
            if (!prefs.getBoolean("autostart_shown", false)) {
                prefs.edit().putBoolean("autostart_shown", true).apply()
                val opened = OemBatteryHelper.openOemAutoStartSettings(this)
                if (opened) {
                    Toast.makeText(
                        this,
                        "⚠️ $brand detected! Please enable AutoStart for Smart SOS Relay to keep it running.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        startSosService()
    }

    private fun startSosService() {
        val intent = Intent(this, SosForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}