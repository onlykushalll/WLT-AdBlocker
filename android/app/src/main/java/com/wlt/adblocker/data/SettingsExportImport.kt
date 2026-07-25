package com.wlt.adblocker.data

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream

/**
 * JSON export/import of user settings.
 *
 * Exports:
 *  - Custom rules (block + allow) from [RuleStore]
 *  - App bypass list from [RuleStore]
 *  - Aggregate stats snapshot (blocked/allowed totals) from [BlockStats]
 *
 * Imports:
 *  - Parses the JSON, calls [RuleStore.addRule] / [RuleStore.setAppBypass]
 *    for each entry. Import is additive — existing rules are NOT cleared
 *    first. Call [RuleStore.clearAll] explicitly if a "replace" semantic
 *    is desired.
 *
 * Format versioned for forward compatibility: the JSON has a `"version"`
 * field at the top. Future versions can add fields without breaking
 * older parsers (we use `optString`/`optLong` everywhere).
 *
 * I/O via ContentResolver: caller passes a `content://` Uri from a
 * system file picker (SAF). We don't ask for storage permissions.
 */
class SettingsExportImport(private val context: Context) {

    companion object {
        private const val TAG = "SettingsExportImport"
        private const val VERSION = 1
    }

    /** Builds the export JSON as a string. Does not perform any I/O. */
    fun buildExportJson(stats: ExportStats? = null): String {
        val ruleStore = RuleStore.get(context)
        val rules = ruleStore.customRules.value
        val bypass = ruleStore.bypassApps.value
        val json = JSONObject()
        json.put("version", VERSION)
        json.put("exportedAt", System.currentTimeMillis())
        json.put("appPackage", context.packageName)

        val rulesArray = JSONArray()
        for (rule in rules) {
            rulesArray.put(JSONObject().apply {
                put("domain", rule.domain)
                put("type", rule.type.name)
                put("createdAt", rule.createdAt)
            })
        }
        json.put("customRules", rulesArray)

        val bypassArray = JSONArray()
        for (pkg in bypass) bypassArray.put(pkg)
        json.put("bypassApps", bypassArray)

        if (stats != null) {
            json.put("stats", JSONObject().apply {
                put("totalBlocked", stats.totalBlocked)
                put("totalAllowed", stats.totalAllowed)
                put("topBlocked", JSONArray().apply {
                    stats.topBlocked.forEach { (domain, count) ->
                        put(JSONObject().apply {
                            put("domain", domain)
                            put("count", count)
                        })
                    }
                })
            })
        }
        return json.toString(2)
    }

    /** Writes the export JSON to [uri] via ContentResolver. Returns true on success. */
    fun exportToUri(uri: Uri, stats: ExportStats? = null): Boolean {
        return try {
            val json = buildExportJson(stats)
            val resolver = context.contentResolver
            val out: OutputStream? = resolver.openOutputStream(uri, "w")
            if (out == null) {
                Log.e(TAG, "ContentResolver returned null stream for $uri")
                return false
            }
            out.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            Log.i(TAG, "Exported settings to $uri (${json.length} chars)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export settings to $uri", e)
            false
        }
    }

    /** Reads JSON from [uri] and applies it to [RuleStore]. Returns the
     *  number of rules + bypass apps applied, or -1 on error. */
    fun importFromUri(uri: Uri): Int {
        return try {
            val resolver = context.contentResolver
            val text = resolver.openInputStream(uri)?.use { input ->
                input.bufferedReader(Charsets.UTF_8).readText()
            } ?: run {
                Log.e(TAG, "ContentResolver returned null stream for $uri")
                return -1
            }
            applyImport(text)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import settings from $uri", e)
            -1
        }
    }

    /** Applies the JSON text directly to [RuleStore]. Public for testing.
     *
     *  SECURITY: All imported domains and package names are validated to
     *  prevent injection attacks (C3 fix from security audit):
     *  - Domains must have ≥2 labels (e.g., "example.com", not "com")
     *  - Domains must match a safe charset (letters, digits, dots, hyphens)
     *  - Package names must look like Android package names
     *  - The adblocker's own package is never added to bypassApps
     *  - Total import size is capped to prevent DoS */
    fun applyImport(jsonText: String): Int {
        val json = JSONObject(jsonText)
        val ruleStore = RuleStore.get(context)
        var applied = 0
        val ownPackage = context.packageName

        val rulesArray = json.optJSONArray("customRules")
        if (rulesArray != null) {
            // Cap at 1000 rules to prevent DoS
            val count = minOf(rulesArray.length(), 1000)
            for (i in 0 until count) {
                val obj = rulesArray.getJSONObject(i)
                val domain = obj.optString("domain").trim().lowercase()
                val typeStr = obj.optString("type")
                val type = when (typeStr) {
                    "BLOCK" -> RuleStore.RuleType.BLOCK
                    "ALLOW" -> RuleStore.RuleType.ALLOW
                    else -> continue
                }
                // SECURITY: validate domain before adding
                if (isValidDomain(domain) && domain != ownPackage) {
                    ruleStore.addRule(domain, type)
                    applied++
                } else {
                    Log.w(TAG, "Import rejected invalid domain: '$domain'")
                }
            }
        }

        val bypassArray = json.optJSONArray("bypassApps")
        if (bypassArray != null) {
            val count = minOf(bypassArray.length(), 500)
            for (i in 0 until count) {
                val pkg = bypassArray.getString(i).trim()
                // SECURITY: never allow the adblocker itself to be bypassed
                if (pkg.isNotEmpty() && isValidPackageName(pkg) && pkg != ownPackage) {
                    ruleStore.setAppBypass(pkg, true)
                    applied++
                } else {
                    Log.w(TAG, "Import rejected invalid/sensitive package: '$pkg'")
                }
            }
        }
        Log.i(TAG, "Imported $applied rules + bypass apps")
        return applied
    }

    /** Validates that [domain] is safe to import:
     *  - ≥2 labels (prevents "com" wildcard allow)
     *  - Only letters, digits, dots, hyphens
     *  - Each label ≥1 char
     *  - Max 253 chars total (DNS limit) */
    private fun isValidDomain(domain: String): Boolean {
        if (domain.isEmpty() || domain.length > 253) return false
        if (!domain.matches(Regex("^[a-z0-9.-]+$"))) return false
        val labels = domain.split('.')
        if (labels.size < 2) return false // Must have at least SLD.TLD
        for (label in labels) {
            if (label.isEmpty() || label.length > 63) return false
            if (label.startsWith("-") || label.endsWith("-")) return false
        }
        return true
    }

    /** Validates that [pkg] looks like an Android package name:
     *  - Only letters, digits, dots, underscores
     *  - ≥2 segments (e.g., com.example, not just "com")
     *  - Max 255 chars */
    private fun isValidPackageName(pkg: String): Boolean {
        if (pkg.isEmpty() || pkg.length > 255) return false
        if (!pkg.matches(Regex("^[a-zA-Z0-9._]+$"))) return false
        val parts = pkg.split('.')
        if (parts.size < 2) return false
        for (part in parts) {
            if (part.isEmpty()) return false
        }
        return true
    }

    /** Aggregate stats included in exports for user reference. */
    data class ExportStats(
        val totalBlocked: Long,
        val totalAllowed: Long,
        val topBlocked: List<Pair<String, Int>>,
    )
}
