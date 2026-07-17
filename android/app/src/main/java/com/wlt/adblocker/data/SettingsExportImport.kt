package com.wlt.adblocker.data

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Export/Import settings as JSON.
 *
 * Exports: custom rules (block + allow), app firewall bypass list, and
 * preferences (layer toggles, block response, upstream DNS, theme).
 *
 * Format:
 * {
 *   "version": 1,
 *   "exported_at": 1234567890,
 *   "custom_rules": [{"domain": "ads.com", "block": true}, ...],
 *   "app_bypass": ["com.example.app", ...],
 *   "settings": {"dns": true, "sni": false, ...}
 * }
 */
object SettingsExportImport {

    private const val TAG = "SettingsExport"
    private const val VERSION = 1

    fun exportToJson(): String {
        val json = JSONObject()
        json.put("version", VERSION)
        json.put("exported_at", System.currentTimeMillis() / 1000)

        // Custom rules
        val rulesArray = JSONArray()
        for (rule in RuleStore.customRules.value) {
            val r = JSONObject()
            r.put("domain", rule.domain)
            r.put("block", rule.isBlock)
            rulesArray.put(r)
        }
        json.put("custom_rules", rulesArray)

        // App bypass
        val bypassArray = JSONArray()
        for (pkg in RuleStore.getBypassApps()) {
            bypassArray.put(pkg)
        }
        json.put("app_bypass", bypassArray)

        // Stats snapshot
        val stats = JSONObject()
        stats.put("total_queries", com.wlt.adblocker.vpn.BlockStats.totalQueries())
        stats.put("total_blocked", com.wlt.adblocker.vpn.BlockStats.totalBlocked())
        stats.put("total_allowed", com.wlt.adblocker.vpn.BlockStats.totalAllowed())
        json.put("stats_snapshot", stats)

        return json.toString(2)
    }

    fun importFromJson(jsonStr: String): ImportResult {
        val result = ImportResult()
        try {
            val json = JSONObject(jsonStr)
            val version = json.optInt("version", 0)
            if (version != VERSION) {
                Log.w(TAG, "Unknown version: $version")
            }

            // Custom rules
            val rulesArray = json.optJSONArray("custom_rules")
            if (rulesArray != null) {
                for (i in 0 until rulesArray.length()) {
                    val r = rulesArray.getJSONObject(i)
                    val domain = r.getString("domain")
                    val isBlock = r.getBoolean("block")
                    RuleStore.addRule(domain, isBlock)
                    result.rulesImported++
                }
            }

            // App bypass
            val bypassArray = json.optJSONArray("app_bypass")
            if (bypassArray != null) {
                for (i in 0 until bypassArray.length()) {
                    val pkg = bypassArray.getString(i)
                    RuleStore.setAppBypass(pkg, true)
                    result.appsBypassed++
                }
            }

            result.success = true
        } catch (e: Exception) {
            Log.e(TAG, "Import failed", e)
            result.success = false
            result.error = e.message ?: "Unknown error"
        }
        return result
    }

    fun exportToFile(context: Context, uri: Uri): Boolean {
        return try {
            val json = exportToJson()
            context.contentResolver.openOutputStream(uri)?.use { output ->
                output.write(json.toByteArray())
            }
            Log.i(TAG, "Exported settings to $uri")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Export to file failed", e)
            false
        }
    }

    fun importFromFile(context: Context, uri: Uri): ImportResult {
        return try {
            val json = context.contentResolver.openInputStream(uri)?.use { input ->
                BufferedReader(InputStreamReader(input)).readText()
            } ?: return ImportResult().apply { error = "Cannot read file" }
            importFromJson(json)
        } catch (e: Exception) {
            Log.e(TAG, "Import from file failed", e)
            ImportResult().apply { error = e.message ?: "Unknown error" }
        }
    }

    data class ImportResult(
        var success: Boolean = false,
        var rulesImported: Int = 0,
        var appsBypassed: Int = 0,
        var error: String? = null
    )
}
