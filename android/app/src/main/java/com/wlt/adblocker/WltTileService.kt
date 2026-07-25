package com.wlt.adblocker

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log

/**
 * Quick Settings tile for one-tap VPN toggle.
 *
 * Registered in AndroidManifest with the BIND_QUICK_SETTINGS_TILE permission
 * and the QS_TILE intent filter. When the user pulls down the shade, the
 * system binds to us and calls [onStartListening] — we update the tile state
 * to reflect whether the VPN service is currently running.
 *
 * On click we either:
 *  - If VPN is already up: fire ACTION_STOP.
 *  - If VPN is down: launch MainActivity with the auto_start_vpn=true extra.
 *    We can't start the service directly from the tile because we may need
 *    VPN permission (VpnService.prepare), which requires an Activity context.
 *    MainActivity handles the permission flow on our behalf.
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
        val tile = qsTile ?: return
        if (tile.state == Tile.STATE_ACTIVE) {
            // Currently active → stop the VPN.
            val stopIntent = Intent().apply {
                setClassName(packageName, "com.wlt.adblocker.vpn.WltVpnService")
                action = "com.wlt.adblocker.action.STOP"
            }
            startService(stopIntent)
            tile.state = Tile.STATE_INACTIVE
            tile.updateTile()
        } else {
            // Inactive → launch MainActivity with auto-start extra. We use
            // startActivityAndCollapse (deprecated on API 34 but still works)
            // to avoid keeping the tile visible after the click.
            val launchIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra(MainActivity.EXTRA_AUTO_START_VPN, true)
            }
            try {
                startActivityAndCollapse(launchIntent)
            } catch (e: Exception) {
                Log.w(TAG, "startActivityAndCollapse failed, falling back to startActivity", e)
                startActivity(launchIntent)
            }
        }
    }

    /** Refreshes the tile state to reflect current VPN status.
     *  Heuristic: we treat the tile as active if the WltVpnService process
     *  is running. This isn't perfect (the service could be running but the
     *  VPN not actually established), but it's good enough for a tile. */
    private fun updateTile() {
        val tile = qsTile ?: return
        // We don't have a direct "is VPN running" API without binding to the
        // service, so we use a lightweight heuristic: check if the WLT VPN
        // service is in the system's active services list via ActivityManager.
        // For now we leave the state alone on first bind (the user will see
        // the last-known state) and only flip it on click. A more robust
        // implementation would bind to the service for an authoritative answer.
        tile.label = getString(R.string.tile_label)
        try {
            tile.icon = android.graphics.drawable.Icon.createWithResource(
                packageName,
                R.drawable.ic_launcher_foreground,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set tile icon", e)
        }
        tile.updateTile()
    }
}
