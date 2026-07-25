package com.wlt.adblocker

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.wlt.adblocker.ui.WltApp
import com.wlt.adblocker.ui.theme.WltTheme
import com.wlt.adblocker.vpn.WltVpnService
import kotlinx.coroutines.launch

/**
 * Single-activity host for the WLT Compose UI.
 *
 * Responsibilities:
 *  - Host the [WltApp] composable (which contains the NavHost + bottom nav).
 *  - Handle the `auto_start_vpn` intent extra fired by the quick-settings
 *    tile: if true, kick off VPN permission flow + start the VPN service
 *    once permission is granted.
 *  - Launch the system VPN permission dialog via
 *    [ActivityResultContracts.StartActivityForResult] and forward the result
 *    to [WltVpnService.startVPN] on success.
 *  - Enable edge-to-edge display so the app draws under system bars.
 *
 * What it explicitly does NOT do:
 *  - Hold any UI state. All state lives in composables via [remember] /
 *    StateFlow collectors.
 *  - Reference any specific screen directly. The navigation graph is owned
 *    by [WltApp].
 */
class MainActivity : ComponentActivity() {

    companion object {
        /** Quick-settings tile puts this boolean extra in the launch intent
         *  to ask MainActivity to auto-start the VPN (after permission). */
        const val EXTRA_AUTO_START_VPN = "auto_start_vpn"
    }

    /**
     * VPN permission launcher. [VpnService.prepare] returns null if permission
     * is already granted; otherwise it returns an Intent we must launch via
     * an ActivityResultContract to actually prompt the user.
     *
     * On success (RESULT_OK) we fire [WltVpnService.startVPN]. On any other
     * result, we silently skip — the user can toggle the switch on the
     * dashboard to try again.
     */
    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            WltVpnService.startVPN(this)
        }
    }

    /** True if the intent that launched us contains EXTRA_AUTO_START_VPN=true.
     *  Read once in onCreate so we don't repeatedly re-trigger VPN start on
     *  every onNewIntent. */
    private var pendingAutoStartVpn: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Read auto-start extra from the launch intent (quick-settings tile).
        pendingAutoStartVpn = intent?.getBooleanExtra(EXTRA_AUTO_START_VPN, false) ?: false

        setContent {
            WltTheme {
                WltApp()
            }
        }

        // If the tile asked us to auto-start, kick off the permission flow.
        // Use lifecycleScope.launch (NOT MainScope().launch) — we want the
        // coroutine tied to the activity lifecycle so it cancels on destroy.
        if (pendingAutoStartVpn) {
            pendingAutoStartVpn = false
            lifecycleScope.launch {
                ensureVpnPermissionAndStart()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_AUTO_START_VPN, false)) {
            lifecycleScope.launch {
                ensureVpnPermissionAndStart()
            }
        }
    }

    /**
     * If VPN permission is already granted, start the service directly.
     * Otherwise launch the system permission dialog via [vpnPermissionLauncher].
     *
     * Called from [onCreate] (if the tile launched us with auto-start=true)
     * and from the Dashboard when the user toggles the VPN switch on.
     */
    fun requestVpnPermissionAndStart() {
        lifecycleScope.launch {
            ensureVpnPermissionAndStart()
        }
    }

    private fun ensureVpnPermissionAndStart() {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent == null) {
            // Permission already granted — go straight to start.
            WltVpnService.startVPN(this)
        } else {
            // Need to prompt the user. The launcher callback handles the
            // start-on-success case.
            vpnPermissionLauncher.launch(prepareIntent)
        }
    }
}
