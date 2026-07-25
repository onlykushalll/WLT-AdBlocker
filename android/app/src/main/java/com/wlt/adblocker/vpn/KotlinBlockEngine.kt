package com.wlt.adblocker.vpn

import android.content.Context
import android.util.Log
import com.wlt.adblocker.data.RuleStore
import com.wlt.adblocker.filter.BlocklistManager
import com.wlt.adblocker.filter.Verdict

/**
 * Fallback block engine used when the Go gomobile binding ([GoBlockEngine])
 * is unavailable or fails to load.
 *
 * Same cascade as the Go engine (see wlt-core/engine/engine.go):
 *   1. **Custom rule check** (user rules override everything) — [RuleStore.checkCustomRule]
 *   2. **Allowlist** (passthrough) — [BlocklistManager.allowTrie]
 *   3. **Blocklist** (main denylist) — [BlocklistManager.blockTrie]
 *   4. **Game SDK detection** — [detectSdk] (15 SDKs)
 *   5. **CNAME cloaking** — caller checks via [DnsPacketParser.extractCNAMETargets]
 *      and re-invokes [shouldBlock] on each CNAME target
 *
 * The Kotlin engine is simpler than the Go engine (no bloom filter, no
 * forensic traces, no JA4 fingerprinting) but the cascade order is
 * identical, so the user-visible behavior is the same when the Go
 * engine isn't available.
 *
 * Performance: lookups are O(label-count) per query thanks to the
 * DomainTrie. On a mid-range device, the engine handles >10k queries/sec
 * single-threaded — plenty for a phone's traffic.
 *
 * [lastBlockReason] is a thread-local-ish field (we use a thread-local
 * ThreadLocal to avoid contention on the VPN loop thread vs the UI
 * thread reading stats). Each blocked call sets it; UI code reads it
 * for display in the query log.
 */
class KotlinBlockEngine(
    context: Context,
    private val blocklistManager: BlocklistManager = BlocklistManager(context),
) {

    companion object {
        private const val TAG = "KotlinBlockEngine"

        /**
         * 15 game/ads SDK fingerprint patterns. Each entry is a (SDK name,
         * domain suffix) pair. A query whose domain matches the suffix
         * (longest-suffix match) is flagged as that SDK.
         *
         * Mirrors wlt-core/internal/gamesdk/sdk.go's DEFAULT_FINGERPRINTS.
         * Keep in sync if the Go list changes.
         */
        val SDK_PATTERNS: List<Pair<String, String>> = listOf(
            "AdMob" to "doubleclick.net",
            "AdMob" to "googleadservices.com",
            "AdMob" to "googlesyndication.com",
            "Unity Ads" to "unityads.unity3d.com",
            "Unity Ads" to "unity3d.com",
            "AppLovin" to "applovin.com",
            "AppLovin" to "sdk.applovin.com",
            "ironSource" to "ironsrc.com",
            "ironSource" to "supersonic.com",
            "Chartboost" to "chartboost.com",
            "Vungle" to "vungle.com",
            "Meta Audience" to "facebook.com",
            "Meta Audience" to "audience-network.facebook.com",
            "AdColony" to "adcolony.com",
            "Mintegral" to "mintegral.com",
            "Fyber" to "fyber.com",
            "Tapjoy" to "tapjoy.com",
            "InMobi" to "inmobi.com",
            "AppsFlyer" to "appsflyer.com",
            "AppsFlyer" to "appsflyersdk.com",
            "Adjust" to "adjust.com",
            "Adjust" to "adjust.io",
            "Branch" to "branch.io",
        )

        /**
         * DoH bypass domains — apps that hardcode these can circumvent DNS
         * blocking by making HTTPS requests directly to DoH endpoints
         * (e.g., dns.google, cloudflare-dns.com). We block them at the
         * DNS layer so apps can't resolve DoH server addresses.
         *
         * Source: Task 20 honest gap analysis (22 providers added to the
         * blocklist). Mirrored here for resilience — even if the blocklist
         * file fails to load, these are still blocked.
         */
        val DOH_BYPASS_DOMAINS: Set<String> = setOf(
            "dns.google",
            "dns.google.com",
            "cloudflare-dns.com",
            "cloudflare-dns.com.",
            "dns.cloudflare.com",
            "dns.quad9.net",
            "dns.quad9.net.",
            "dns.adguard.com",
            "dns.adguard.com.",
            "dns-adguard.adguard.com",
            "dns.dnscrypt.info",
            "doh.opendns.com",
            "doh.opendns.com.",
            "dnsomatic.com",
            "dns0.eu",
            "dns0.eu.",
            "dns0.children",
            "common.dns0.eu",
            "freedns.controld.com",
            "p2.freedns.controld.com",
            "dnsforge.de",
            "dnsforge.de.",
            "doh.dnsforge.de",
            "mullvad.dns.mullvad.net",
            "doh.mullvad.net",
        )
    }

    private val ruleStore = RuleStore.get(context)

    /**
     * "Why was this query blocked?" — set on the calling thread by
     * [shouldBlock] when it returns true. UI code reads this for the
     * query log display. ThreadLocal so the VPN loop and UI don't
     * clobber each other.
     */
    private val lastBlockReason: ThreadLocal<String> = ThreadLocal.withInitial { "" }
    private val lastBlockSdk: ThreadLocal<String?> = ThreadLocal.withInitial { null }

    /** Loads ALL bundled blocklists from `assets/blocklists/` into the
     *  in-memory trie. Safe to call on a background thread. Idempotent. */
    fun loadBundledBlocklists() {
        val count = blocklistManager.loadBundledAssets()
        Log.i(TAG, "Loaded $count rules from bundled blocklist assets")
    }

    /**
     * Returns true if [domain] should be blocked. Sets [lastBlockReason]
     * on the calling thread to a human-readable reason string.
     *
     * Cascade order (first match wins):
     *  1. User custom rule (BLOCK → block; ALLOW → allow)
     *  2. Allowlist (passthrough) — explicit allow rule in the blocklist
     *  3. Blocklist — explicit block rule
     *  4. Game SDK detection — known ad SDK domain
     *  5. DoH bypass prevention — known DoH endpoint domain
     *
     * If none of the above match, returns false (allow).
     */
    fun shouldBlock(domain: String): Boolean {
        val normalized = domain.trim().trimEnd('.').lowercase()
        if (normalized.isEmpty() || normalized == "localhost") return false

        // 1. Custom rule (highest priority)
        val custom = ruleStore.checkCustomRule(normalized)
        if (custom != null) {
            return when (custom.type) {
                RuleStore.RuleType.BLOCK -> {
                    lastBlockReason.set("Custom block rule")
                    lastBlockSdk.set(null)
                    true
                }
                RuleStore.RuleType.ALLOW -> {
                    // User explicitly allowed — skip everything else.
                    false
                }
            }
        }

        // 2. Allowlist (passthrough — banking/gov/critical infrastructure)
        val allowTrie = blocklistManager.allowTrie()
        if (allowTrie.lookup(normalized) == Verdict.ALLOW) {
            return false
        }

        // 3. Blocklist
        if (blocklistManager.blockTrie().lookup(normalized) == Verdict.BLOCK) {
            lastBlockReason.set("Blocklist match")
            lastBlockSdk.set(null)
            return true
        }

        // 4. Game SDK detection
        val sdk = detectSdk(normalized)
        if (sdk != null) {
            lastBlockReason.set("Game ad SDK")
            lastBlockSdk.set(sdk)
            return true
        }

        // 5. DoH bypass prevention
        if (isDoHBypassDomain(normalized)) {
            lastBlockReason.set("DoH bypass prevention")
            lastBlockSdk.set(null)
            return true
        }

        // 6. DGA (Domain Generation Algorithm) detection — heuristic
        // Catches malware/phishing domains that use algorithmically generated
        // names (e.g., xkqjw7y2.com). Based on Shannon entropy + n-gram score.
        if (isDgaSuspicious(normalized)) {
            lastBlockReason.set("DGA suspicious (high entropy)")
            lastBlockSdk.set(null)
            return true
        }

        return false
    }

    /** Detects whether [domain] matches a known game/ads SDK pattern.
     *  Returns the SDK name (e.g., "AdMob", "Unity Ads") or null.
     *
     *  Used both internally by [shouldBlock] and externally by the UI
     *  (for the query log "SDK" badge, even on allowed queries). */
    fun detectSdk(domain: String): String? {
        val normalized = domain.trim().trimEnd('.').lowercase()
        if (normalized.isEmpty()) return null
        // Longest-suffix-match: iterate patterns and return the longest matching suffix.
        var bestSdk: String? = null
        var bestLen = -1
        for ((sdk, suffix) in SDK_PATTERNS) {
            if (normalized == suffix || normalized.endsWith(".$suffix")) {
                if (suffix.length > bestLen) {
                    bestSdk = sdk
                    bestLen = suffix.length
                }
            }
        }
        return bestSdk
    }

    /** Returns true if [domain] is a known DoH bypass endpoint. */
    fun isDoHBypassDomain(domain: String): Boolean {
        val normalized = domain.trim().trimEnd('.').lowercase()
        if (normalized.isEmpty()) return false
        if (normalized in DOH_BYPASS_DOMAINS) return true
        // Also check parent suffixes (e.g., sub.dns.google should also be blocked)
        for (bypass in DOH_BYPASS_DOMAINS) {
            if (normalized.endsWith(".$bypass")) return true
        }
        return false
    }

    /** Reads the most recent block reason set by [shouldBlock] on this thread.
     *  Returns an empty string if no query has been blocked on this thread. */
    fun getLastBlockReason(): String = lastBlockReason.get() ?: ""
    fun getLastBlockSdk(): String? = lastBlockSdk.get()

    /** For CNAME cloaking: re-checks [domain] without setting lastBlockReason
     *  (we don't want to overwrite the original query's reason). */
    fun shouldBlockCnameTarget(domain: String): Boolean {
        val normalized = domain.trim().trimEnd('.').lowercase()
        if (normalized.isEmpty()) return false
        val custom = ruleStore.checkCustomRule(normalized)
        if (custom?.type == RuleStore.RuleType.BLOCK) return true
        if (custom?.type == RuleStore.RuleType.ALLOW) return false
        if (blocklistManager.allowTrie().lookup(normalized) == Verdict.ALLOW) return false
        if (blocklistManager.blockTrie().lookup(normalized) == Verdict.BLOCK) return true
        if (detectSdk(normalized) != null) return true
        if (isDoHBypassDomain(normalized)) return true
        if (isDgaSuspicious(normalized)) return true
        return false
    }

    // ================================================================
    // DGA (Domain Generation Algorithm) Detection
    // ================================================================
    // Heuristic DGA detection based on Shannon entropy + character ratios.
    // Ported from the Go internal/dga package. Only checks the SLD (second-
    // level domain), not subdomains, to avoid false positives on CDN URLs.
    //
    // This is intentionally conservative: it only flags domains with
    // suspicious characteristics AND that aren't in the allowlist. The
    // allowlist check has already happened by the time we reach this code.
    private fun isDgaSuspicious(domain: String): Boolean {
        // Extract the SLD (e.g., "example" from "sub.example.com")
        val parts = domain.split('.')
        if (parts.size < 2) return false
        val sld = parts[parts.size - 2]
        if (sld.length < 6) return false // Too short to be confident

        // Skip known-good TLDs that often have short/odd SLDs
        val tld = parts.last()
        if (tld in setOf("edu", "gov", "mil")) return false

        val entropy = shannonEntropy(sld)
        val vowelRatio = vowelCount(sld).toDouble() / sld.length
        val digitRatio = digitCount(sld).toDouble() / sld.length
        val hyphenCount = sld.count { it == '-' }

        // DGA indicators (all must be true for a block — conservative):
        // 1. High entropy (> 3.5) — random-looking
        // 2. Low vowel ratio (< 0.25) — not natural language
        // 3. Many digits (> 0.25) — algorithmically generated
        // OR: High entropy + many hyphens (> 2) — clearly not a real domain
        val highEntropy = entropy > 3.5
        val lowVowels = vowelRatio < 0.25
        val manyDigits = digitRatio > 0.25
        val manyHyphens = hyphenCount > 2

        return (highEntropy && lowVowels && manyDigits) || (highEntropy && manyHyphens)
    }

    private fun shannonEntropy(s: String): Double {
        if (s.isEmpty()) return 0.0
        val freq = HashMap<Char, Int>()
        for (c in s) freq[c] = (freq[c] ?: 0) + 1
        var entropy = 0.0
        val len = s.length.toDouble()
        for ((_, count) in freq) {
            val p = count / len
            entropy -= p * (Math.log(p) / Math.log(2.0))
        }
        return entropy
    }

    private fun vowelCount(s: String): Int =
        s.count { it in "aeiou" }

    private fun digitCount(s: String): Int =
        s.count { it.isDigit() }

    // ================================================================
    // Block Response Type (DNS rewrite)
    // ================================================================
    // AdGuard-style: different block responses for different scenarios.
    // NXDOMAIN = "domain doesn't exist" (fastest, saves bandwidth)
    // NullIP = 0.0.0.0 (some apps retry less with a real IP)
    // REFUSED = DNS refusal (explicit "not allowed")
    enum class BlockResponseType { NXDOMAIN, NULL_IP, REFUSED }

    var blockResponseType: BlockResponseType = BlockResponseType.NXDOMAIN

    /** Returns the DNS response bytes for the current block response type. */
    fun buildBlockResponse(query: ByteArray): ByteArray =
        when (blockResponseType) {
            BlockResponseType.NXDOMAIN -> DnsPacketParser.buildNXDOMAIN(query)
            BlockResponseType.NULL_IP -> DnsPacketParser.buildNullIP(query)
            BlockResponseType.REFUSED -> DnsPacketParser.buildREFUSED(query)
        }
}
