package com.wlt.adblocker.vpn

import android.content.Context
import android.util.Log
import com.wlt.adblocker.data.RuleStore
import java.util.concurrent.ConcurrentHashMap

/**
 * Kotlin block engine — fallback when Go engine unavailable.
 * Now loads ALL blocklists: game-ads, passthrough, cname-cloak, youtube,
 * spotify, social-ads, crypto-mining, smart-tv-ads, trackers.
 */
class KotlinBlockEngine {

    private val exactBlock = ConcurrentHashMap.newKeySet<String>()
    private val suffixBlock = ConcurrentHashMap.newKeySet<String>()
    private val allowlist = ConcurrentHashMap.newKeySet<String>()
    private val cnameCloakTargets = ConcurrentHashMap.newKeySet<String>()

    @Volatile var totalBlocked = 0L
    @Volatile var totalAllowed = 0L
    @Volatile var lastBlockReason: String = ""

    private val sdkDomains = mapOf(
        "admob" to listOf("doubleclick.net","googlesyndication.com","googleadservices.com","adservice.google.com","admob.google.com"),
        "unity" to listOf("unityads.unity3d.com","cloud.unity3d.com","cdn.unity.com"),
        "applovin" to listOf("applovin.com","applovin-thirdparty.com"),
        "ironsource" to listOf("ironsrc.com"),
        "chartboost" to listOf("chartboost.com"),
        "vungle" to listOf("vungle.com"),
        "meta" to listOf("an.facebook.com","ads.facebook.com"),
        "adcolony" to listOf("adcolony.com"),
        "mintegral" to listOf("mintegral.com"),
        "fyber" to listOf("fyber.com"),
        "tapjoy" to listOf("tapjoy.com"),
        "inmobi" to listOf("inmobi.com"),
        "appsflyer" to listOf("appsflyer.com"),
        "adjust" to listOf("adjust.com"),
        "branch" to listOf("branch.io"),
    )

    private val dohBypassDomains = setOf(
        "dns.google","cloudflare-dns.com","dns.quad9.net","doh.opendns.com","dns.adguard.com","doh.pub","dns.alidns.com","doh.xfinity.com","doh.dns.sb"
    )

    fun shouldBlock(domain: String): Boolean {
        val d = domain.lowercase().trim('.')
        if (d.isEmpty()) return false

        val customDecision = RuleStore.checkCustomRule(d)
        if (customDecision != null) {
            if (customDecision) { totalBlocked++; lastBlockReason = "user block" }
            else { totalAllowed++; lastBlockReason = "user allow" }
            return customDecision
        }

        if (allowlist.contains(d)) { totalAllowed++; lastBlockReason = "allowlist"; return false }
        val labels = d.split('.')
        for (i in 0 until labels.size - 1) {
            val suffix = labels.subList(i, labels.size).joinToString(".")
            if (allowlist.contains(suffix)) { totalAllowed++; lastBlockReason = "allowlist suffix"; return false }
        }

        if (dohBypassDomains.any { d == it || d.endsWith(".$it") }) {
            totalBlocked++; lastBlockReason = "DoH bypass prevention"; return true
        }

        if (exactBlock.contains(d)) { totalBlocked++; lastBlockReason = "blocklist match"; return true }
        for (i in 0 until labels.size - 1) {
            val suffix = labels.subList(i, labels.size).joinToString(".")
            if (suffixBlock.contains(suffix)) { totalBlocked++; lastBlockReason = "blocklist suffix: $suffix"; return true }
        }

        val sdk = detectSdk(d)
        if (sdk != null) { totalBlocked++; lastBlockReason = "game SDK: $sdk"; return true }

        totalAllowed++; lastBlockReason = "allowed"
        return false
    }

    fun checkCnameChain(cnameTargets: List<String>): Boolean {
        for (target in cnameTargets) {
            val t = target.lowercase().trim('.')
            if (cnameCloakTargets.contains(t)) { lastBlockReason = "CNAME cloak: $t"; return true }
            if (exactBlock.contains(t) || suffixBlock.contains(t)) { lastBlockReason = "CNAME blocked: $t"; return true }
            val sdk = detectSdk(t)
            if (sdk != null) { lastBlockReason = "CNAME SDK: $sdk"; return true }
        }
        return false
    }

    fun detectSdk(domain: String): String? {
        val d = domain.lowercase().trim('.')
        for ((sdk, patterns) in sdkDomains) {
            for (pattern in patterns) { if (d == pattern || d.endsWith(".$pattern")) return sdk }
        }
        return null
    }

    fun addBlock(domain: String) {
        val d = domain.lowercase().trim('.').removePrefix("*.")
        if (d.isEmpty()) return
        suffixBlock.add(d); exactBlock.add(d)
    }

    fun addAllow(domain: String) {
        val d = domain.lowercase().trim('.').removePrefix("*.")
        if (d.isNotEmpty()) allowlist.add(d)
    }

    fun addCnameCloak(domain: String) {
        val d = domain.lowercase().trim('.')
        if (d.isNotEmpty()) cnameCloakTargets.add(d)
    }

    fun loadBundledLists(context: Context) {
        // Load ALL blocklists
        val blockLists = listOf(
            "blocklists/wlt-game-ads.txt",
            "blocklists/wlt-youtube-ads.txt",
            "blocklists/wlt-spotify-ads.txt",
            "blocklists/wlt-social-ads.txt",
            "blocklists/wlt-crypto-mining.txt",
            "blocklists/wlt-smart-tv-ads.txt",
            "blocklists/wlt-trackers.txt"
        )
        val allowLists = listOf("blocklists/wlt-passthrough.txt")
        val cnameLists = listOf("blocklists/wlt-cname-cloak.txt")

        for (name in blockLists) {
            try {
                context.assets.open(name).bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val t = line.trim()
                        if (t.isNotEmpty() && !t.startsWith("#") && !t.startsWith("!") && !t.startsWith("*.")) addBlock(t)
                    }
                }
            } catch (e: Exception) { Log.w("KotlinBlockEngine", "Missing: $name") }
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
        for (name in cnameLists) {
            try {
                context.assets.open(name).bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val t = line.trim()
                        if (t.isNotEmpty() && !t.startsWith("#") && !t.startsWith("!")) addCnameCloak(t)
                    }
                }
            } catch (e: Exception) { }
        }
        loadRemoteBlocklists(context)
    }

    fun loadRemoteBlocklists(context: Context): Int {
        var total = 0
        val files = context.filesDir.listFiles()?.filter { it.name.endsWith(".txt") } ?: return 0
        for (file in files) {
            try {
                file.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val t = line.trim()
                        if (t.isNotEmpty() && !t.startsWith("#") && !t.startsWith("!")) { addBlock(t); total++ }
                    }
                }
            } catch (e: Exception) { }
        }
        return total
    }

    fun blocklistSize(): Int = exactBlock.size
    fun allowlistSize(): Int = allowlist.size
}
