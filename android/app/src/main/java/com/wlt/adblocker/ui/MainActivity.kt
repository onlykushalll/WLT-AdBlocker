package com.wlt.adblocker.ui

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.wlt.adblocker.data.PrefsRepository
import com.wlt.adblocker.data.WltDataStore
import com.wlt.adblocker.ui.theme.WltTheme
import com.wlt.adblocker.vpn.WltVpnService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpnService()
            lifecycleScope.launch {
                WltDataStore.setVpnEnabled(true)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check if auto-start was requested (from quick settings tile)
        val autoStart = intent?.getBooleanExtra("auto_start_vpn", false) ?: false

        setContent {
            WltTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    WltApp(
                        onToggleVpn = { enabled -> requestVpnToggle(enabled) }
                    )
                }
            }
        }

        if (autoStart) {
            // Requested from tile — trigger VPN start
            requestVpnToggle(true)
        }
    }

    private fun requestVpnToggle(enabled: Boolean) {
        if (enabled) {
            val intent = VpnService.prepare(this)
            if (intent != null) {
                vpnPermissionLauncher.launch(intent)
            } else {
                startVpnService()
                lifecycleScope.launch {
                    WltDataStore.setVpnEnabled(true)
                }
            }
        } else {
            stopVpnService()
            lifecycleScope.launch {
                WltDataStore.setVpnEnabled(false)
            }
        }
    }

    private fun startVpnService() {
        val intent = Intent(this, WltVpnService::class.java)
        startService(intent)
    }

    private fun stopVpnService() {
        val intent = Intent(this, WltVpnService::class.java)
        stopService(intent)
    }
}
