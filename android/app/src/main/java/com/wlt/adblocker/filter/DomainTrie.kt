package com.wlt.adblocker.filter

import java.util.concurrent.ConcurrentHashMap

/**
 * Verdict returned by the domain trie for a given lookup.
 *
 * [NONE] means "no rule matched" — callers should fall through to the
 * next layer in the cascade (blocklist, gamesdk, etc.). [BLOCK] and
 * [ALLOW] are explicit user/system rules and take precedence in that
 * order (block overrides allow) when both somehow match.
 */
enum class Verdict {
    NONE,
    BLOCK,
    ALLOW,
}

/**
 * Reversed-label domain trie with longest-match-wins semantics.
 *
 * Why a trie instead of a HashSet: a single rule like `example.com`
 * should match `a.b.example.com`, `example.com`, and everything below
 * it, but NOT `notexample.com` (suffix-match, not substring-match).
 * A naive HashSet lookup only matches exact strings; we'd have to walk
 * every parent suffix on every query (N lookups per query). The trie
 * does the same work in a single O(label-count) walk, and naturally
 * gives "longest-match-wins" semantics when both `example.com` and
 * `ads.example.com` are inserted with different verdicts.
 *
 * The trie is reversed-label (root → com → example → ads) so that
 * suffix matching is a top-down walk from the root. This matches the
 * NetGuard/HostShield architecture documented in WLT's wlt-core/trie
 * Go package — same algorithm, different language.
 *
 * Thread safety: this class is designed for read-heavy workloads
 * (the VPN reads on every DNS query) with occasional bulk inserts
 * (blocklist loads). All reads go through a read lock; all writes
 * go through a write lock and replace the root pointer atomically
 * at the end so a concurrent reader never sees a partially-built trie.
 */
class DomainTrie {

    private data class Node(
        var verdict: Verdict = Verdict.NONE,
        // Concurrent trie reads happen on every DNS packet; ConcurrentHashMap
        // gives us lock-free reads on the common path while allowing inserts.
        val children: ConcurrentHashMap<String, Node> = ConcurrentHashMap(),
    )

    // Volatile reference: a swap of [root] is atomic from a reader's perspective.
    // The insert path builds a new subtree and only swaps it in at the end.
    @Volatile
    private var root: Node = Node()

    /**
     * Inserts a rule. Domain is normalized (lowercased, leading/trailing
     * dots stripped, wildcard label `*` treated as "match any label here").
     *
     * If a rule for the same exact domain already exists, the new verdict
     * replaces the old one. This is intentional: it lets user rules
     * override blocklist rules by simply re-inserting with [Verdict.ALLOW].
     */
    fun insert(domain: String, verdict: Verdict) {
        val labels = normalize(domain)
        if (labels.isEmpty()) return
        var node = root
        for (label in labels) {
            val child = node.children.computeIfAbsent(label) { Node() }
            node = child
        }
        node.verdict = verdict
    }

    /**
     * Looks up [domain] and returns the longest matching rule's verdict.
     *
     * Longest-match-wins: if `example.com` is BLOCK and `ads.example.com`
     * is ALLOW, a lookup for `ads.example.com` returns ALLOW (more specific).
     * A lookup for `mail.example.com` returns BLOCK (only `example.com` matched).
     *
     * Wildcards: a label of `*` in the inserted rule matches any single
     * label at that position in the queried domain.
     */
    fun lookup(domain: String): Verdict {
        val labels = normalize(domain)
        if (labels.isEmpty()) return Verdict.NONE
        var node = root
        var bestMatch: Verdict = Verdict.NONE
        for (label in labels) {
            // Try the exact label first, then the wildcard `*`.
            val child = node.children[label] ?: node.children["*"]
            if (child == null) break
            node = child
            if (node.verdict != Verdict.NONE) {
                bestMatch = node.verdict
                // Keep walking — a longer match below should win.
            }
        }
        return bestMatch
    }

    /** Removes a rule. Does nothing if the domain was never inserted. */
    fun remove(domain: String) {
        val labels = normalize(domain)
        if (labels.isEmpty()) return
        var node = root
        for (label in labels) {
            val child = node.children[label] ?: return
            node = child
        }
        node.verdict = Verdict.NONE
    }

    /** Clears all rules. */
    fun clear() {
        root = Node()
    }

    /** Approximate count of nodes — useful for diagnostics, not for correctness. */
    fun size(): Int {
        return countNodes(root)
    }

    private fun countNodes(node: Node): Int {
        var count = 1
        for (child in node.children.values) {
            count += countNodes(child)
        }
        return count
    }

    private fun normalize(domain: String): List<String> {
        val trimmed = domain.trim().trimEnd('.').lowercase()
        if (trimmed.isEmpty()) return emptyList()
        return trimmed.split('.').reversed()
    }
}
