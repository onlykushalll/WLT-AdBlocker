package com.wlt.adblocker.vpn

import android.content.Context
import android.util.Log
import com.wlt.adblocker.data.RuleStore
import mobile.Engine
import mobile.DnsResult

/**
 * GoBlockEngine — wraps the Go core (wlt.aar via gomobile) for use by the VPN service.
 *
 * This replaces KotlinBlockEngine as the primary block engine, giving us:
 *   - Reversed-label domain trie (O(m) lookup, memory-efficient)
 *   - Counting bloom filter (suffix-aware, 0.08% FP rate)
 *   - Game SDK fingerprinting (12 SDKs with graceful ad response)
 *   - Ad Forensics (per-layer decision tracking)
 *   - CNAME cloaking detection
 *   - DoH bypass prevention
 *   - Smart Cascade (DNS → SNI → HTTPS → Scriptlet)
 *
 * The KotlinBlockEngine is kept as a fallback if the Go engine fails to load.
 */
class GoBlockEngine {

    private val goEngine: Engine? = try {
        Engine()
    } catch (e: Exception) {
        Log.e(TAG, "Failed to create Go engine: ${e.message}", e)
        null
    }

    @Volatile var totalBlocked = 0L
    @Volatile var totalAllowed = 0L
    @Volatile var lastBlockReason: String = ""

    /**
     * Check if a domain should be blocked using the Go trie+bloom+gamesdk engine.
     * Falls back to checking custom rules (RuleStore) first for user rules.
     */
    fun shouldBlock(domain: String): Boolean {
        // 1. Custom rules (user-defined) — checked in Kotlin for speed
        val customDecision = RuleStore.checkCustomRule(domain)
        if (customDecision != null) {
            if (customDecision) { totalBlocked++; lastBlockReason = "user block rule" }
            else { totalAllowed++; lastBlockReason = "user allow rule" }
            return customDecision
        }

        // 2. Go engine — trie + bloom + gamesdk + DoH bypass
        if (goEngine != null) {
            // Build a minimal DNS query packet for the domain
            val query = buildDnsQuery(domain)
            val result = goEngine.checkDNS(query, null)
            if (result != null) {
                if (result.block) {
                    totalBlocked++
                    lastBlockReason = result.reason ?: "Go engine block"
                } else {
                    totalAllowed++
                    lastBlockReason = "allowed"
                }
                return result.block
            }
        }

        // 3. Fallback — allow if Go engine unavailable
        totalAllowed++
        lastBlockReason = "Go engine unavailable, allowed"
        return false
    }

    /**
     * Check a raw DNS query packet against the Go engine.
     * Returns a DnsResult with block decision + response packet.
     */
    fun checkDnsPacket(query: ByteArray, response: ByteArray? = null): DnsResult? {
        if (goEngine == null) return null
        return goEngine.checkDNS(query, response)
    }

    /**
     * Check SNI (TLS ClientHello) against the Go engine.
     */
    fun checkSni(payload: ByteArray): Boolean {
        if (goEngine == null) return false
        val result = goEngine.checkSNI(payload)
        return result?.block ?: false
    }

    /**
     * Add a domain to the Go engine's blocklist at runtime.
     */
    fun addBlockDomain(domain: String) {
        goEngine?.addBlockDomain(domain)
    }

    /**
     * Add a domain to the Go engine's allowlist at runtime.
     */
    fun addAllowDomain(domain: String) {
        goEngine?.addAllowDomain(domain)
    }

    /**
     * Load a blocklist file into the Go engine.
     * format: 0=auto, 1=hosts, 2=adblock, 3=domains
     */
    fun loadBlocklistFile(path: String, format: Int): Int {
        if (goEngine == null) return 0
        return goEngine.loadBlocklistFile(path, format.toLong()).toInt()
    }

    /**
     * Load an allowlist file into the Go engine.
     */
    fun loadAllowlistFile(path: String, format: Int): Int {
        if (goEngine == null) return 0
        return goEngine.loadAllowlistFile(path, format.toLong()).toInt()
    }

    /**
     * Load bundled blocklists from assets into the Go engine.
     * Copies asset files to internal storage first, then loads them.
     */
    fun loadBundledLists(context: Context) {
        if (goEngine == null) return

        // Copy blocklist assets to internal storage so Go can read them
        val lists = listOf(
            "blocklists/wlt-game-ads.txt" to "wlt-game-ads.txt",
            "blocklists/wlt-passthrough.txt" to "wlt-passthrough.txt"
        )
        for ((assetName, fileName) in lists) {
            try {
                val outFile = context.getFileStreamPath(fileName)
                context.assets.open(assetName).use { input ->
                    outFile.outputStream().use { output -> input.copyTo(output) }
                }
                val format = 3 // domains format
                if (fileName.contains("passthrough") || fileName.contains("allow")) {
                    val count = loadAllowlistFile(outFile.absolutePath, format)
                    Log.i(TAG, "Loaded allowlist $fileName: $count domains")
                } else {
                    val count = loadBlocklistFile(outFile.absolutePath, format)
                    Log.i(TAG, "Loaded blocklist $fileName: $count domains")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load $assetName: ${e.message}")
            }
        }

        // Also load any remote blocklists (downloaded by BlocklistUpdateWorker)
        val remoteFiles = context.filesDir.listFiles()?.filter { it.name.endsWith(".txt") && it.name != "wlt-game-ads.txt" && it.name != "wlt-passthrough.txt" }
        remoteFiles?.forEach { file ->
            try {
                val count = loadBlocklistFile(file.absolutePath, 3)
                Log.i(TAG, "Loaded remote blocklist ${file.name}: $count domains")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load remote ${file.name}: ${e.message}")
            }
        }
    }

    fun blocklistSize(): Int = goEngine?.blocklistSize()?.toInt() ?: 0
    fun allowlistSize(): Int = goEngine?.allowlistSize()?.toInt() ?: 0
    fun statsJson(): String = goEngine?.statsJSON() ?: "{}"

    fun isAvailable(): Boolean = goEngine != null

    companion object {
        private const val TAG = "GoBlockEngine"

        /**
         * Build a minimal DNS query packet for a domain.
         * Used to call the Go engine's CheckDNS method.
         */
        private fun buildDnsQuery(domain: String): ByteArray {
            val buf = mutableListOf<Byte>()
            // Header: ID=1, flags=0x0100 (RD=1), QDCOUNT=1
            buf.addAll(listOf(0x00, 0x01).map { it.toByte() })
            buf.addAll(listOf(0x01, 0x00).map { it.toByte() })
            buf.addAll(listOf(0x00, 0x01).map { it.toByte() })
            buf.addAll(listOf(0x00, 0x00).map { it.toByte() })
            buf.addAll(listOf(0x00, 0x00).map { it.toByte() })
            buf.addAll(listOf(0x00, 0x00).map { it.toByte() })
            // QNAME
            for (label in domain.split(".")) {
                if (label.isNotEmpty()) {
                    buf.add(label.length.toByte())
                    buf.addAll(label.map { it.code.toByte() })
                }
            }
            buf.add(0)
            // QTYPE=A (1), QCLASS=IN (1)
            buf.addAll(listOf(0x00, 0x01).map { it.toByte() })
            buf.addAll(listOf(0x00, 0x01).map { it.toByte() })
            return buf.toByteArray()
        }
    }
}
