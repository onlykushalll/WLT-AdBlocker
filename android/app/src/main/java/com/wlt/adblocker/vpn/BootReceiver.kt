package com.wlt.adblocker.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i("WltBoot", "Boot completed — auto-start disabled by default (require user opt-in)")
            // We don't auto-start VPN on boot for privacy reasons — user must tap to enable.
            // Phase 2: add a setting "Start on boot" that gates this.
        }
    }
}
