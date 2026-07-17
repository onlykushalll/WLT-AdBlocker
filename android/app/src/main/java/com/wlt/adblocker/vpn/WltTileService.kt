package com.wlt.adblocker.vpn

import android.content.Intent
import android.net.VpnService
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.wlt.adblocker.ui.MainActivity

/**
 * Quick Settings Tile — one-tap VPN toggle from the notification shade.
 *
 * Shows shield icon when active, dimmed when inactive.
 * Tapping requests VPN permission (if needed) and starts/stops the VPN service.
 *
 * Registered in AndroidManifest as a tile service.
 */
class WltTileService : TileService() {

    companion object {
        private const val TAG = "WltTileService"
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        Log.i(TAG, "Tile clicked")

        val vpnActive = isVpnActive()
        if (vpnActive) {
            // Stop VPN
            val intent = Intent(this, WltVpnService::class.java)
            stopService(intent)
        } else {
            // Request VPN permission (tile service can't request consent dialog directly,
            // so we launch the main activity which handles permission)
            val launchIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra("auto_start_vpn", true)
            }
            startActivityAndCollapse(launchIntent)
        }
        updateTile()
    }

    private fun isVpnActive(): Boolean {
        // Check if our VPN service is running by querying VpnService.prepare
        // If prepare returns null, VPN is either running or we have permission
        // For simplicity, we check if the user has enabled VPN in settings
        // A more robust check would use a shared preference or bound service
        return VpnService.prepare(this) == null &&
               com.wlt.adblocker.data.WltDataStore.vpnEnabled.let { false } // placeholder
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val active = isVpnActive()
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "WLT Adblocker"
        tile.contentDescription = if (active) "Ad blocking active" else "Ad blocking off"
        tile.updateTile()
    }
}
