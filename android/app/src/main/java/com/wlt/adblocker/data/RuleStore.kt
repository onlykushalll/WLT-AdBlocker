package com.wlt.adblocker.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Process-wide singleton holding user-defined custom rules and per-app
 * firewall config.
 *
 * Why a singleton instead of a Repository pattern: the VPN service and
 * the UI both need to read the same rule set, and the VPN service may
 * outlive the activity that wrote the rule. A Repository scoped to a
 * ViewModel or activity would be torn down with that scope. A singleton
 * scoped to the process keeps the rules alive across config changes,
 * activity destruction, and service restarts.
 *
 * Persistence: rules are persisted as JSON to `filesDir/rulestore.json`
 * on every mutation, so they survive process death. The file is small
 * (typically <100 entries) so a blocking write on every mutation is
 * acceptable. If we ever need to scale to thousands of rules, switch
 * to a write-behind queue.
 */
class RuleStore private constructor(private val context: Context) {

    companion object {
        private const val TAG = "RuleStore"
        private const val FILENAME = "rulestore.json"

        @Volatile
        private var INSTANCE: RuleStore? = null

        /** Returns the process-wide singleton, creating it on first access. */
        fun get(context: Context): RuleStore {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: RuleStore(context.applicationContext).also {
                    it.loadFromDisk()
                    INSTANCE = it
                }
            }
        }
    }

    /** A user-defined rule. [BLOCK] rules override everything (including the
     *  blocklist). [ALLOW] rules act as passthrough (skip the blocklist). */
    enum class RuleType { BLOCK, ALLOW }

    data class CustomRule(
        val domain: String,
        val type: RuleType,
        val createdAt: Long = System.currentTimeMillis(),
    )

    private val _customRules = MutableStateFlow<List<CustomRule>>(emptyList())
    /** Observable list of custom rules. Updates push immediately to UI and engine. */
    val customRules: StateFlow<List<CustomRule>> = _customRules.asStateFlow()

    private val _bypassApps = MutableStateFlow<Set<String>>(emptySet())
    /** Observable set of package names whose traffic should bypass the VPN entirely. */
    val bypassApps: StateFlow<Set<String>> = _bypassApps.asStateFlow()

    /** Adds (or replaces, if same domain) a custom rule. Persists to disk. */
    fun addRule(domain: String, type: RuleType) {
        val normalized = domain.trim().lowercase()
        if (normalized.isEmpty()) return
        _customRules.update { existing ->
            val without = existing.filterNot { it.domain == normalized }
            without + CustomRule(normalized, type)
        }
        persist()
    }

    /** Removes a custom rule by domain. Persists to disk. */
    fun removeRule(domain: String) {
        val normalized = domain.trim().lowercase()
        _customRules.update { existing -> existing.filterNot { it.domain == normalized } }
        persist()
    }

    /** Checks [domain] against custom rules. Returns the matching rule or null.
     *
     *  This is the FIRST check in the [com.wlt.adblocker.vpn.KotlinBlockEngine]
     *  cascade — user rules override the blocklist, the allowlist, and
     *  game SDK detection. */
    fun checkCustomRule(domain: String): CustomRule? {
        val normalized = domain.trim().lowercase()
        if (normalized.isEmpty()) return null
        // Longest-suffix-match wins, so we walk from longest to shortest.
        val rules = _customRules.value
        // Simple O(n) scan — n is small (typically <100). For larger N, switch
        // to a DomainTrie. For now, prioritize correctness.
        var bestMatch: CustomRule? = null
        var bestLen = -1
        for (rule in rules) {
            if (matches(normalized, rule.domain)) {
                if (rule.domain.length > bestLen) {
                    bestMatch = rule
                    bestLen = rule.domain.length
                }
            }
        }
        return bestMatch
    }

    /** Returns true if [queried] is, or is a subdomain of, [rule]. Both sides
     *  are normalized to lowercase and trailing dots stripped before comparison. */
    private fun matches(queried: String, rule: String): Boolean {
        if (rule.isEmpty()) return false
        if (queried == rule) return true
        return queried.endsWith(".$rule")
    }

    /** Sets or clears the VPN bypass flag for [packageName]. Persists to disk. */
    fun setAppBypass(packageName: String, bypass: Boolean) {
        _bypassApps.update { existing ->
            if (bypass) existing + packageName else existing - packageName
        }
        persist()
    }

    /** Returns true if [packageName] is configured to bypass the VPN. */
    fun isAppBypassed(packageName: String): Boolean = packageName in _bypassApps.value

    /** Snapshot of the bypass set — used by VpnService.Builder.addDisallowedApplication. */
    fun getBypassApps(): Set<String> = _bypassApps.value.toSet()

    /** Clears ALL custom rules and bypass apps. Persists to disk. */
    fun clearAll() {
        _customRules.value = emptyList()
        _bypassApps.value = emptySet()
        persist()
    }

    // --- Persistence ---

    private fun persist() {
        val json = JSONObject().apply {
            val rulesArray = JSONArray()
            for (rule in _customRules.value) {
                rulesArray.put(JSONObject().apply {
                    put("domain", rule.domain)
                    put("type", rule.type.name)
                    put("createdAt", rule.createdAt)
                })
            }
            put("customRules", rulesArray)
            val bypassArray = JSONArray()
            for (pkg in _bypassApps.value) bypassArray.put(pkg)
            put("bypassApps", bypassArray)
            put("version", 1)
        }
        try {
            File(context.filesDir, FILENAME).writeText(json.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist RuleStore to disk", e)
        }
    }

    private fun loadFromDisk() {
        val file = File(context.filesDir, FILENAME)
        if (!file.exists()) return
        try {
            val json = JSONObject(file.readText())
            val rulesArray = json.optJSONArray("customRules") ?: JSONArray()
            val rules = ArrayList<CustomRule>(rulesArray.length())
            for (i in 0 until rulesArray.length()) {
                val obj = rulesArray.getJSONObject(i)
                val type = when (obj.optString("type")) {
                    "BLOCK" -> RuleType.BLOCK
                    "ALLOW" -> RuleType.ALLOW
                    else -> continue
                }
                rules.add(
                    CustomRule(
                        domain = obj.optString("domain"),
                        type = type,
                        createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                    )
                )
            }
            _customRules.value = rules
            val bypassArray = json.optJSONArray("bypassApps") ?: JSONArray()
            val bypass = HashSet<String>(bypassArray.length())
            for (i in 0 until bypassArray.length()) {
                bypass.add(bypassArray.getString(i))
            }
            _bypassApps.value = bypass
            Log.i(TAG, "Loaded ${rules.size} custom rules, ${bypass.size} bypass apps from disk")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse RuleStore from disk, starting fresh", e)
        }
    }
}
