package com.wlt.adblocker

import android.app.Application
import com.wlt.adblocker.data.BlocklistUpdateWorker
import com.wlt.adblocker.data.WltDataStore
import com.wlt.adblocker.util.NotificationHelper

class WltApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        WltDataStore.init(this)
        NotificationHelper.createChannels(this)
        // Schedule 24h blocklist auto-update
        BlocklistUpdateWorker.schedule(this)
    }

    companion object {
        lateinit var instance: WltApplication
            private set
    }
}
