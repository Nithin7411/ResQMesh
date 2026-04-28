package com.stella.smartsosrelay.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Automatically restarts the SOS foreground service after the phone reboots.
 * Critical for ensuring protection resumes without user interaction.
 *
 * Also initializes DeviceIdentityManager so BLE scanning can use the
 * stable device hash immediately on boot.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED ||
            intent?.action == "android.intent.action.QUICKBOOT_POWERON" ||
            intent?.action == "com.htc.intent.action.QUICKBOOT_POWERON"
        ) {
            // Initialize stable device identity before starting BLE service
            DeviceIdentityManager.init(context)

            val serviceIntent = Intent(context, SosForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}
