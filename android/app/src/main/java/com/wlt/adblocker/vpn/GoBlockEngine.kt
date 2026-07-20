package com.wlt.adblocker.vpn

import android.content.Context
import android.util.Log
import com.wlt.adblocker.data.RuleStore
import mobile.Engine
import mobile.Mobile

/**
 * GoBlockEngine — wraps the Go core (wlt.aar via gomobile).
 * Primary block engine. Falls back to KotlinBlockEngine if Go fails.
 */
class GoBlockEngine {

    private val goEngine: Engine? = try {
        Mobile.newEngine()
    } catch (e: Exception) {
        Log.e(TAG, "Failed to create Go engine: ${e.message}", e)
        null
    }

    @Volatile var totalBlocked = 0L
    @Volatile var totalAllowed = 0L
    @Volatile var lastBlockReason: String = ""

    fun shouldBlock(domain: String): Boolean {
        // 1. Custom rules first
        val customDecision = RuleStore.checkCustomRule(domain)
        if (customDecision != null) {
            if (customDecision) { totalBlocked++; lastBlockReason = "user block rule" }
            else { totalAllowed++; lastBlockReason = "user allow rule" }
            return customDecision
        }

        // 2. Go engine
        if (goEngine != null) {
            val blocked = goEngine.shouldBlock(domain)
            if (blocked) { totalBlocked++; lastBlockReason = "Go engine block" }
            else { totalAllowed++; lastBlockReason = "allowed" }
            return blocked
        }

        // 3. Fallback
        totalAllowed++; lastBlockReason = "Go engine unavailable"
        return false
    }

    fun addBlockDomain(domain: String) { goEngine?.addBlockDomain(domain) }
    fun addAllowDomain(domain: String) { goEngine?.addAllowDomain(domain) }

    fun loadBundledLists(context: Context) {
        if (goEngine == null) return
        val lists = listOf("blocklists/wlt-game-ads.txt" to false, "blocklists/wlt-passthrough.txt" to true)
        for ((assetName, isAllow) in lists) {
            try {
                val outFile = context.getFileStreamPath(assetName.substringAfterLast("/"))
                context.assets.open(assetName).use { input ->
                    outFile.outputStream().use { output -> input.copyTo(output) }
                }
                if (isAllow) goEngine.addAllowDomain(outFile.absolutePath)
                else goEngine.addBlockDomain(outFile.absolutePath)
            } catch (e: Exception) { }
        }
    }

    fun blocklistSize(): Int = goEngine?.blocklistSize()?.toInt() ?: 0
    fun allowlistSize(): Int = goEngine?.allowlistSize()?.toInt() ?: 0
    fun statsJson(): String = goEngine?.statsJSON() ?: "{}"
    fun isAvailable(): Boolean = goEngine != null

    companion object { private const val TAG = "GoBlockEngine" }
}
