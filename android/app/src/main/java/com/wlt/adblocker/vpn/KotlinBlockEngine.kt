package com.wlt.adblocker.vpn

import android.content.Context
import com.wlt.adblocker.data.RuleStore
import java.util.concurrent.ConcurrentHashMap

/**
 * Kotlin fallback block engine — used when the Go gomobile .aar is not yet wired in.
 * Implements a hash-set based domain blocklist with suffix matching + custom rules.
 */
class KotlinBlockEngine {

    private val exactBlock = ConcurrentHashMap.newKeySet<String>()
    private val suffixBlock = ConcurrentHashMap.newKeySet<String>()
    private val allowlist = ConcurrentHashMap.newKeySet<String>()

    @Volatile var totalBlocked = 0L
    @Volatile var totalAllowed = 0L
    @Volatile var lastBlockReason: String = ""

    private val sdkDomains = mapOf(
        "admob" to listOf("doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com", "admob.google.com"),
        "unity" to listOf("unityads.unity3d.com", "cloud.unity3d.com", "cdn.unity.com"),
        "applovin" to listOf("applovin.com", "applovin-thirdparty.com"),
        "ironsource" to listOf("ironsrc.com"),
        "chartboost" to listOf("chartboost.com"),
        "vungle" to listOf("vungle.com"),
        "meta" to listOf("an.facebook.com", "ads.facebook.com"),
        "adcolony" to listOf("adcolony.com"),
        "mintegral" to listOf("mintegral.com"),
        "fyber" to listOf("fyber.com"),
        "tapjoy" to listOf("tapjoy.com"),
        "inmobi" to listOf("inmobi.com"),
        "appsflyer" to listOf("appsflyer.com"),
        "adjust" to listOf("adjust.com"),
        "branch" to listOf("branch.io"),
    )

    fun shouldBlock(domain: String): Boolean {
        val d = domain.lowercase().trim('.')
        if (d.isEmpty()) return false

        // 1. Custom rules (user-defined) — highest priority
        val customDecision = RuleStore.checkCustomRule(d)
        if (customDecision != null) {
            if (customDecision) {
                totalBlocked++
                lastBlockReason = "user block rule"
            } else {
                totalAllowed++
                lastBlockReason = "user allow rule (passthrough)"
            }
            return customDecision
        }

        // 2. Allowlist (bundled passthrough)
        if (allowlist.contains(d)) {
            totalAllowed++
            lastBlockReason = "allowlist (passthrough)"
            return false
        }
        val labels = d.split('.')
        for (i in 0 until labels.size - 1) {
            val suffix = labels.subList(i, labels.size).joinToString(".")
            if (allowlist.contains(suffix)) {
                totalAllowed++
                lastBlockReason = "allowlist suffix: $suffix"
                return false
            }
        }

        // 3. Blocklist exact match
        if (exactBlock.contains(d)) {
            totalBlocked++
            lastBlockReason = "blocklist match"
            return true
        }
        // 4. Blocklist suffix match
        for (i in 0 until labels.size - 1) {
            val suffix = labels.subList(i, labels.size).joinToString(".")
            if (suffixBlock.contains(suffix)) {
                totalBlocked++
                lastBlockReason = "blocklist suffix: $suffix"
                return true
            }
        }

        // 5. Game SDK detection
        val sdk = detectSdk(d)
        if (sdk != null) {
            totalBlocked++
            lastBlockReason = "game SDK: $sdk"
            return true
        }

        totalAllowed++
        lastBlockReason = "allowed"
        return false
    }

    fun detectSdk(domain: String): String? {
        val d = domain.lowercase().trim('.')
        for ((sdk, patterns) in sdkDomains) {
            for (pattern in patterns) {
                if (d == pattern || d.endsWith(".$pattern")) return sdk
            }
        }
        return null
    }

    fun addBlock(domain: String) {
        val d = domain.lowercase().trim('.').removePrefix("*.")
        if (d.isEmpty()) return
        suffixBlock.add(d)
        exactBlock.add(d)
    }

    fun addAllow(domain: String) {
        val d = domain.lowercase().trim('.').removePrefix("*.")
        if (d.isNotEmpty()) allowlist.add(d)
    }

    fun loadBundledLists(context: Context) {
        val blockLists = listOf("blocklists/wlt-game-ads.txt")
        val allowLists = listOf("blocklists/wlt-passthrough.txt")
        for (name in blockLists) {
            try {
                context.assets.open(name).bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val t = line.trim()
                        if (t.isNotEmpty() && !t.startsWith("#") && !t.startsWith("!")) addBlock(t)
                    }
                }
            } catch (e: Exception) { }
        }
        for (name in allowLists) {
            try {
                context.assets.open(name).bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val t = line.trim()
                        if (t.isNotEmpty() && !t.startsWith("#") && !t.startsWith("!")) addAllow(t)
                    }
                }
            } catch (e: Exception) { }
        }
    }

    fun blocklistSize(): Int = exactBlock.size
    fun allowlistSize(): Int = allowlist.size
}
