package com.stella.smartsosrelay.services

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.tasks.await

data class GpsCoordinates(val latitude: Double, val longitude: Double)

object LocationHelper {

    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocation(context: Context): GpsCoordinates {
        return try {
            val client = LocationServices.getFusedLocationProviderClient(context)

            // Try to get a fresh location first
            val location = client.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY,
                CancellationTokenSource().token
            ).await()

            if (location != null) {
                GpsCoordinates(location.latitude, location.longitude)
            } else {
                // Fallback to last known location
                val lastLocation = client.lastLocation.await()
                if (lastLocation != null) {
                    GpsCoordinates(lastLocation.latitude, lastLocation.longitude)
                } else {
                    Log.w("LocationHelper", "No location available")
                    GpsCoordinates(0.0, 0.0)
                }
            }
        } catch (e: Exception) {
            Log.e("LocationHelper", "Failed to get location", e)
            GpsCoordinates(0.0, 0.0)
        }
    }
}
