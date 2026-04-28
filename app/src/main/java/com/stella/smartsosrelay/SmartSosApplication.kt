package com.stella.smartsosrelay

import android.app.Application
import com.stella.smartsosrelay.data.SosDatabase
import com.stella.smartsosrelay.data.SosRepository
import com.stella.smartsosrelay.services.DeviceIdentityManager

class SmartSosApplication : Application() {
    val database by lazy { SosDatabase.getDatabase(this) }
    val repository by lazy { SosRepository(database.sosDao()) }

    override fun onCreate() {
        super.onCreate()
        // Initialize stable device identity before any BLE/Firebase operations
        DeviceIdentityManager.init(this)
    }
}
