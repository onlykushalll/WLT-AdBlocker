package com.wlt.adblocker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Stub BootReceiver.
 *
 * PRIVACY DEFAULT: auto-start on boot is DISABLED. The receiver is registered
 * in the AndroidManifest with `android:enabled="false"` — the system will
 * never deliver BOOT_COMPLETED to us unless the user explicitly opts in via
 * Settings (which would call `PackageManager.setComponentEnabledSetting` to
 * flip the receiver to ENABLED).
 *
 * The receiver is intentionally tiny: even when enabled, all it does is fire
 * a start intent at the VPN service. The service itself decides whether to
 * actually establish the TUN based on its own persisted state.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) {
            Log.w(TAG, "Ignoring unexpected action: ${intent.action}")
            return
        }
        Log.i(TAG, "Boot completed — but auto-start is disabled by default; no-op")
        // Intentionally does NOT start the VPN service. If a future version
        // adds a "start on boot" toggle in Settings, this is where the
        // opt-in check would go:
        //
        //   if (PrefsRepository(context).shouldStartOnBoot()) {
        //       WltVpnService.startVPN(context)
        //   }
        //
        // For now we leave it as a no-op so the manifest entry serves as a
        // placeholder and the receiver is harmless even if a user manually
        // enables it via adb.
    }
}
