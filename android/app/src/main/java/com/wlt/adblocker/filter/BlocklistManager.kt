package com.wlt.adblocker.filter

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Manages the in-memory blocklist trie, with cache-first loading and
 * atomic swap semantics so a background blocklist refresh can never
 * leave the trie half-built while a DNS query is being answered.
 *
 * Loading order on startup:
 *  1. Cached compiled list in `filesDir/blocklists/compiled.bin` (if fresh)
 *  2. Fresh download from sources (handled by [BlocklistUpdateWorker])
 *  3. Bundled assets (`blocklists/wlt-*.txt`) — always present, never absent
 *
 * The atomic reference holds a single [LoadedTrie] which combines the
 * block trie, the allow trie, and a count for diagnostics. Reads (from
 * the VPN query loop) just dereference the AtomicReference — lock-free.
 * Writes build a new trie fully, then swap.
 */
class BlocklistManager(private val context: Context) {

    private companion object {
        const val TAG = "BlocklistManager"
        const val CACHE_DIR = "blocklists"
        const val CACHE_FILE = "compiled.bin"
    }

    /** Snapshot of the currently-loaded blocklist state. */
    data class LoadedTrie(
        val block: DomainTrie,
        val allow: DomainTrie,
        val blockCount: Int,
        val allowCount: Int,
        val source: String, // "assets" | "cache" | "network"
        val loadedAt: Long,
    )

    private val current = AtomicReference<LoadedTrie>(
        LoadedTrie(DomainTrie(), DomainTrie(), 0, 0, "unloaded", 0L)
    )

    /** Returns the current loaded trie snapshot. Always non-null. */
    fun current(): LoadedTrie = current.get()

    /** Convenience for callers that only care about the block trie. */
    fun blockTrie(): DomainTrie = current.get().block

    /** Convenience for callers that only care about the allow trie. */
    fun allowTrie(): DomainTrie = current.get().allow

    /** Loads all bundled blocklist assets from `assets/blocklists/`. Safe to
     *  call on a background thread at startup. Returns the count of rules
     *  loaded. Idempotent — re-running replaces the trie. */
    fun loadBundledAssets(): Int {
        val block = DomainTrie()
        val allow = DomainTrie()
        var blockCount = 0
        var allowCount = 0
        val assetFiles = try {
            context.assets.list("blocklists") ?: emptyArray()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list blocklists assets directory", e)
            emptyArray()
        }
        for (filename in assetFiles.sorted()) {
            if (!filename.endsWith(".txt")) continue
            try {
                context.assets.open("blocklists/$filename").use { input ->
                    val text = input.bufferedReader(Charsets.UTF_8).readText()
                    val (blockDomains, allowDomains) = BlocklistParser.parse(text)
                    for (d in blockDomains) {
                        block.insert(d, Verdict.BLOCK)
                        blockCount++
                    }
                    for (d in allowDomains) {
                        allow.insert(d, Verdict.ALLOW)
                        allowCount++
                    }
                }
                Log.i(TAG, "Loaded $filename: $blockCount block, $allowCount allow rules so far")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load blocklist $filename", e)
            }
        }
        current.set(
            LoadedTrie(
                block = block,
                allow = allow,
                blockCount = blockCount,
                allowCount = allowCount,
                source = "assets",
                loadedAt = System.currentTimeMillis(),
            )
        )
        Log.i(TAG, "Bundled assets loaded: $blockCount block, $allowCount allow rules from ${assetFiles.size} files")
        return blockCount + allowCount
    }

    /** Replaces the in-memory trie with one loaded from [file]. The file is
     *  expected to be a plain-text blocklist in any format [BlocklistParser]
     *  understands. Called by [BlocklistUpdateWorker] after a fresh download. */
    fun loadFromFile(file: File, source: String = "network"): Int {
        if (!file.exists() || !file.canRead()) {
            Log.w(TAG, "Blocklist file missing or unreadable: ${file.absolutePath}")
            return 0
        }
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrElse {
            Log.e(TAG, "Failed to read blocklist file ${file.name}", it)
            return 0
        }
        val (blockDomains, allowDomains) = BlocklistParser.parse(text)
        val block = DomainTrie()
        val allow = DomainTrie()
        for (d in blockDomains) block.insert(d, Verdict.BLOCK)
        for (d in allowDomains) allow.insert(d, Verdict.ALLOW)
        current.set(
            LoadedTrie(
                block = block,
                allow = allow,
                blockCount = blockDomains.size,
                allowCount = allowDomains.size,
                source = source,
                loadedAt = System.currentTimeMillis(),
            )
        )
        Log.i(TAG, "Loaded ${file.name}: ${blockDomains.size} block, ${allowDomains.size} allow rules")
        return blockDomains.size + allowDomains.size
    }

    /** Adds a single user rule to BOTH the current trie (so it takes effect
     *  immediately) and any future reloads (via RuleStore, not handled here).
     *  Used by [com.wlt.adblocker.data.RuleStore] when a custom rule is added.
     *
     *  Safe under concurrency: [DomainTrie] is internally thread-safe for
     *  inserts (uses ConcurrentHashMap internally), so we don't need to swap
     *  the AtomicReference — we mutate the existing trie in place. */
    fun addUserRule(domain: String, verdict: Verdict) {
        val snap = current.get()
        when (verdict) {
            Verdict.BLOCK -> snap.block.insert(domain, Verdict.BLOCK)
            Verdict.ALLOW -> snap.allow.insert(domain, Verdict.ALLOW)
            Verdict.NONE -> { /* no-op */ }
        }
    }

    /** Removes a single user rule from the current trie. */
    fun removeUserRule(domain: String, verdict: Verdict) {
        val snap = current.get()
        when (verdict) {
            Verdict.BLOCK -> snap.block.remove(domain)
            Verdict.ALLOW -> snap.allow.remove(domain)
            Verdict.NONE -> { /* no-op */ }
        }
    }

    /** For diagnostics / settings screen display. */
    fun stats(): String {
        val snap = current.get()
        return "block=${snap.blockCount}, allow=${snap.allowCount}, source=${snap.source}, " +
            "loadedAt=${snap.loadedAt} (${(System.currentTimeMillis() - snap.loadedAt) / 1000}s ago)"
    }
}
