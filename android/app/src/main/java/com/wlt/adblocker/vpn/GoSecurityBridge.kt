package com.wlt.adblocker.vpn

import android.util.Log
import mobile.Mobile

/**
 * Bridge between Kotlin and the Go core (wlt.aar via gomobile).
 *
 * The Go functions exposed via gomobile:
 *   mobile.NewCA() -> String (PEM-encoded CA certificate)
 *   mobile.NewEngine() -> mobile.Engine
 *
 * SignCert is NOT yet in mobile.go — when added, wire it here.
 */
interface GoSecurityBridge {
    fun getOrCreateCaPem(): String
    fun signCertificateForDomain(domain: String): String
}

class GoSecurityBridgeImpl : GoSecurityBridge {
    companion object { private const val TAG = "GoSecurityBridge" }

    override fun getOrCreateCaPem(): String {
        return try {
            Mobile.newCA()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate CA via Go: ${e.message}", e)
            ""
        }
    }

    override fun signCertificateForDomain(domain: String): String {
        // TODO: Add SignCert to mobile.go, then call Mobile.signCert(domain)
        // For now, return empty — HttpsProxyService will refuse to start
        Log.w(TAG, "signCertificateForDomain not yet wired — needs mobile.go addition")
        return ""
    }
}
