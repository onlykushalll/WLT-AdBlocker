package com.wlt.adblocker.filter

/**
 * Parses multiple blocklist formats:
 *  - hosts: "0.0.0.0 ads.example.com" / "127.0.0.1 ads.example.com"
 *  - bare domain: "ads.example.com"
 *  - ABP block: "||ads.example.com^"
 *  - ABP allow: "@@||safe.example.com^"
 *  - comments: # or ! prefix, inline # trailing comments
 *
 * Ported from Claude's BlocklistParser — handles all real-world formats.
 */
object BlocklistParser {

    data class ParsedRule(val domain: String, val verdict: Verdict)

    private val ignoredHostsTargets = setOf("0.0.0.0", "127.0.0.1", "::1", "::")

    private fun looksLikeDomain(token: String): Boolean {
        if (token.isEmpty() || token.contains(' ')) return false
        if (!token.contains('.')) return false
        val allowed = "abcdefghijklmnopqrstuvwxyz0123456789-._"
        return token.lowercase().all { it in allowed }
    }

    fun parseLine(rawLine: String): ParsedRule? {
        var line = rawLine.trim()
        if (line.isEmpty()) return null
        if (line.startsWith("#") || line.startsWith("!")) return null

        // Strip inline comments (only when preceded by whitespace)
        val hashIdx = line.indexOf('#')
        if (hashIdx > 0 && (line[hashIdx - 1] == ' ' || line[hashIdx - 1] == '\t')) {
            line = line.substring(0, hashIdx).trim()
        }
        if (line.isEmpty()) return null

        // ABP format: ||domain^ or @@||domain^
        if (line.startsWith("||") || line.startsWith("@@||")) {
            val verdict = if (line.startsWith("@@")) Verdict.ALLOW else Verdict.BLOCK
            var body = if (verdict == Verdict.ALLOW) line.substring(4) else line.substring(2)
            for (sep in charArrayOf('^', '/', '$')) {
                val sepIdx = body.indexOf(sep)
                if (sepIdx >= 0) body = body.substring(0, sepIdx)
            }
            val domain = body.trim()
            return if (looksLikeDomain(domain)) ParsedRule(domain.lowercase(), verdict) else null
        }

        // Hosts format: "0.0.0.0 domain" or "127.0.0.1 domain alias1"
        val parts = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.size >= 2 && parts[0] in ignoredHostsTargets) {
            val domain = parts[1]
            return if (looksLikeDomain(domain)) ParsedRule(domain.lowercase(), Verdict.BLOCK) else null
        }

        // Bare domain
        if (parts.size == 1 && looksLikeDomain(parts[0])) {
            return ParsedRule(parts[0].lowercase(), Verdict.BLOCK)
        }

        return null
    }

    /** Streams a whole list into [trie], returns rule count. */
    fun parseInto(text: CharSequence, trie: DomainTrie): Int {
        var count = 0
        var lineStart = 0
        val len = text.length
        var i = 0
        while (i <= len) {
            if (i == len || text[i] == '\n') {
                val rawLine = text.subSequence(lineStart, i).toString()
                val rule = parseLine(rawLine)
                if (rule != null) {
                    trie.insert(rule.domain, rule.verdict)
                    count++
                }
                lineStart = i + 1
            }
            i++
        }
        return count
    }
}
