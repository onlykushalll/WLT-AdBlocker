package com.wlt.adblocker.filter

/**
 * Domain-matching trie with longest-match-wins semantics.
 * A specific ALLOW overrides a blanket BLOCK (and vice versa).
 * Ported from Claude's DomainTrie.
 */
enum class Verdict { BLOCK, ALLOW }

class DomainTrie {

    private class Node {
        val children: HashMap<String, Node> = HashMap()
        var verdict: Verdict? = null
    }

    private val root = Node()

    @Volatile var blockRuleCount: Int = 0; private set
    @Volatile var allowRuleCount: Int = 0; private set
    val totalRuleCount: Int get() = blockRuleCount + allowRuleCount

    private fun normalize(domain: String): List<String> {
        var d = domain.trim().lowercase()
        if (d.endsWith(".")) d = d.substring(0, d.length - 1)
        if (d.isEmpty()) return emptyList()
        return d.split(".")
    }

    fun insert(domain: String, verdict: Verdict) {
        val labels = normalize(domain)
        if (labels.isEmpty()) return
        var node = root
        for (label in labels.asReversed()) {
            node = node.children.getOrPut(label) { Node() }
        }
        val hadVerdict = node.verdict
        node.verdict = verdict
        if (hadVerdict == null) {
            if (verdict == Verdict.BLOCK) blockRuleCount++ else allowRuleCount++
        }
    }

    fun lookup(domain: String): Verdict {
        val labels = normalize(domain)
        var node = root
        var lastVerdict = Verdict.ALLOW
        for (label in labels.asReversed()) {
            node = node.children[label] ?: break
            node.verdict?.let { lastVerdict = it }
        }
        return lastVerdict
    }

    fun clear() {
        root.children.clear()
        blockRuleCount = 0
        allowRuleCount = 0
    }
}
