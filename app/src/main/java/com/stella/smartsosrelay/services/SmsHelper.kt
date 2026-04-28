package com.stella.smartsosrelay.services

import android.content.Context
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SmsHelper {
    fun sendSosSms(context: Context, phoneNumber: String, userName: String, latitude: Double, longitude: Double, triggerReason: String) {
        try {
            val time = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date())
            val mapsLink = "https://maps.google.com/maps?q=$latitude,$longitude"

            val message = buildString {
                append("EMERGENCY SOS ALERT!\n")
                append("From: $userName\n")
                append("Trigger: $triggerReason\n")
                append("Time: $time\n")
                append("GPS: $mapsLink\n")
                append("Please respond immediately!")
            }

            // Use the correct SmsManager API based on Android version
            val smsManager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }

            val parts = smsManager.divideMessage(message)
            smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null)
            Log.d("SmsHelper", "SOS Sent to $phoneNumber — Reason: $triggerReason")
        } catch (e: Exception) {
            Log.e("SmsHelper", "Failed to send SMS to $phoneNumber", e)
        }
    }
}
