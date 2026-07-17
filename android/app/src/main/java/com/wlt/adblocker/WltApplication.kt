package com.wlt.adblocker

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.wlt.adblocker.data.WltDataStore
import com.wlt.adblocker.util.NotificationHelper

class WltApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        WltDataStore.init(this)
        NotificationHelper.createChannels(this)
    }

    companion object {
        lateinit var instance: WltApplication
            private set
    }
}
