package com.wlt.adblocker.filter

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference

/**
 * Owns blocklist sources and the shared DomainTrie.
 *
 * Design (ported from Claude's BlocklistManager):
 *  - Trie rebuilt from scratch on any change, swapped atomically
 *  - Cache-first: rebuildFromCache() never touches network
 *  - refreshFromNetwork() downloads and rebuilds
 *  - Lookups never block on rebuilds
 */
data class BlocklistSource(
    val id: String, val name: String, val url: String,
    val isBuiltIn: Boolean = false, var enabled: Boolean = true,
    var ruleCount: Int = 0, var lastUpdatedEpochMs: Long = 0L, var lastError: String? = null,
)

data class RefreshResult(val succeeded: Int, val failed: Int)

class BlocklistManager(private val context: Context) {

    private val trieRef = AtomicReference(DomainTrie())
    val trie: DomainTrie get() = trieRef.get()

    private val cacheDir: File get() = File(context.filesDir, "blocklist_cache").apply { mkdirs() }
    private val sourcesFile: File get() = File(context.filesDir, "blocklist_sources.json")
    private var sources: MutableList<BlocklistSource> = mutableListOf()

    companion object {
        private const val TAG = "BlocklistManager"
        const val WLT_STARTER_ID = "wlt-starter"
        const val STEVENBLACK_ID = "stevenblack-hosts"
        const val OISD_BIG_ID = "oisd-big"

        fun defaultSources(): List<BlocklistSource> = listOf(
            BlocklistSource(WLT_STARTER_ID, "WLT starter (curated ad/game SDK domains)",
                "asset://starter_blocklist.txt", isBuiltIn = true, enabled = true),
            BlocklistSource(STEVENBLACK_ID, "StevenBlack hosts (unified)",
                "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts", enabled = true),
            BlocklistSource(OISD_BIG_ID, "oisd big",
                "https://big.oisd.nl", enabled = false),
        )
    }

    @Synchronized
    fun loadSources(): List<BlocklistSource> {
        if (sources.isNotEmpty()) return sources
        sources = if (sourcesFile.exists()) {
            try { readSourcesFile() } catch (e: Exception) { defaultSources().toMutableList() }
        } else { defaultSources().toMutableList() }
        return sources
    }

    private fun readSourcesFile(): MutableList<BlocklistSource> {
        val json = JSONArray(sourcesFile.readText())
        val list = mutableListOf<BlocklistSource>()
        for (i in 0 until json.length()) {
            val o = json.getJSONObject(i)
            list.add(BlocklistSource(
                id = o.getString("id"), name = o.getString("name"), url = o.getString("url"),
                isBuiltIn = o.optBoolean("isBuiltIn", false), enabled = o.optBoolean("enabled", true),
                ruleCount = o.optInt("ruleCount", 0), lastUpdatedEpochMs = o.optLong("lastUpdatedEpochMs", 0L),
                lastError = o.optString("lastError", "").takeIf { it.isNotBlank() },
            ))
        }
        return list
    }

    @Synchronized
    private fun persistSources() {
        val arr = JSONArray()
        for (s in sources) {
            val o = JSONObject()
            o.put("id", s.id); o.put("name", s.name); o.put("url", s.url)
            o.put("isBuiltIn", s.isBuiltIn); o.put("enabled", s.enabled)
            o.put("ruleCount", s.ruleCount); o.put("lastUpdatedEpochMs", s.lastUpdatedEpochMs)
            o.put("lastError", s.lastError ?: "")
            arr.put(o)
        }
        sourcesFile.writeText(arr.toString())
    }

    suspend fun rebuildFromCache() = withContext(Dispatchers.Default) {
        loadSources()
        val newTrie = DomainTrie()
        for (source in sources) {
            if (!source.enabled) continue
            val text = readCachedOrBundledText(source) ?: continue
            source.ruleCount = BlocklistParser.parseInto(text, newTrie)
        }
        trieRef.set(newTrie)
        persistSources()
        Log.i(TAG, "Rebuilt trie: ${newTrie.totalRuleCount} rules (${newTrie.blockRuleCount} block / ${newTrie.allowRuleCount} allow)")
    }

    private fun readCachedOrBundledText(source: BlocklistSource): String? {
        if (source.url.startsWith("asset://")) {
            val assetName = source.url.removePrefix("asset://")
            return try { context.assets.open(assetName).bufferedReader().use { it.readText() } }
            catch (e: Exception) { null }
        }
        val cacheFile = File(cacheDir, "${source.id}.txt")
        if (!cacheFile.exists()) return null
        return try { cacheFile.readText() } catch (e: Exception) { null }
    }

    suspend fun refreshFromNetwork(): RefreshResult = withContext(Dispatchers.IO) {
        loadSources()
        var succeeded = 0; var failed = 0
        for (source in sources) {
            if (!source.enabled || source.isBuiltIn) continue
            try {
                val downloaded = downloadText(source.url)
                File(cacheDir, "${source.id}.txt").writeText(downloaded)
                source.lastUpdatedEpochMs = System.currentTimeMillis()
                source.lastError = null
                succeeded++
            } catch (e: Exception) {
                source.lastError = e.message ?: e.javaClass.simpleName
                failed++
            }
        }
        persistSources()
        rebuildFromCache()
        RefreshResult(succeeded, failed)
    }

    private fun downloadText(urlStr: String): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000; conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "WLT-Adblocker/0.1 (Android)")
        return try {
            conn.connect()
            if (conn.responseCode !in 200..299) throw java.io.IOException("HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally { conn.disconnect() }
    }
}
