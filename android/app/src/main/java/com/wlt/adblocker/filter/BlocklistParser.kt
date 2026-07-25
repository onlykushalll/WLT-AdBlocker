package com.wlt.adblocker.filter

import android.util.Log

/**
 * Parses blocklist files in the four formats WLT supports:
 *
 * 1. **Hosts format** — `0.0.0.0 ads.example.com` or `127.0.0.1 ads.example.com`
 *    (used by AdAway, Pi-hole, etc.). The IP is ignored; the domain is the rule.
 * 2. **ABP filter syntax** — `||ads.example.com^` for block rules,
 *    `@@||ads.example.com^` for allow rules. The trailing `^` separator
 *    and any `$modifier` (third-party, important, etc.) are stripped —
 *    at the VPN layer we don't honor modifiers, only the domain.
 * 3. **Bare domain** — one domain per line, nothing else.
 * 4. **Comments** — lines starting with `#`, `!`, or containing `#`
 *    inline (treated as comment-to-end-of-line in bare/ABP contexts).
 *
 * Cosmetic (`##.selector`) and scriptlet (`##+js(...)`) rules are
 * intentionally REJECTED here — they have no meaning at the DNS layer
 * and would silently pollute the trie with garbage. The caller is
 * responsible for routing those to the HTTPS/cosmetic engine instead.
 *
 * The parser is a single function that streams line-by-line; it's
 * allocation-light (no regex unless a line actually needs it).
 */
object BlocklistParser {

    private const val TAG = "BlocklistParser"

    /** Result of parsing a single line. */
    sealed class ParseResult {
        /** A block rule for [domain]. */
        data class Block(val domain: String) : ParseResult()
        /** An allow rule (exception) for [domain]. */
        data class Allow(val domain: String) : ParseResult()
        /** The line was a comment, blank, or otherwise ignorable. */
        data object Ignore : ParseResult()
        /** The line was a cosmetic/scriptlet rule or unsupported syntax. */
        data object Unsupported : ParseResult()
    }

    /** Parses one line of a blocklist file into a [ParseResult]. */
    fun parseLine(rawLine: String): ParseResult {
        // Strip inline comments (# ...) for bare/hosts formats — but be careful
        // not to mangle ABP rules that legitimately contain `#` (cosmetic) —
        // those we WANT to reject anyway, so detecting them first is fine.
        val line = rawLine.trim()
        if (line.isEmpty()) return ParseResult.Ignore
        if (line.startsWith("#") || line.startsWith("!")) return ParseResult.Ignore

        // Cosmetic / scriptlet rules — reject explicitly so callers can log.
        if (line.contains("##") || line.contains("#@#") || line.contains("##+js")) {
            return ParseResult.Unsupported
        }

        // ABP exception: @@||example.com^
        if (line.startsWith("@@||")) {
            val domain = extractAbpDomain(line.substring(4))
            return if (domain != null) ParseResult.Allow(domain) else ParseResult.Ignore
        }
        // ABP block: ||example.com^
        if (line.startsWith("||")) {
            val domain = extractAbpDomain(line.substring(2))
            return if (domain != null) ParseResult.Block(domain) else ParseResult.Ignore
        }
        // ABP bare-domain exception (rare but valid): @@example.com
        if (line.startsWith("@@")) {
            val domain = stripModifiers(line.substring(2))
            return if (isValidDomain(domain)) ParseResult.Allow(domain) else ParseResult.Ignore
        }

        // Hosts format: 0.0.0.0 ads.example.com  [optional comment]
        // Match if the first whitespace-separated token is an IP literal.
        val firstSpace = line.indexOfFirst { it.isWhitespace() }
        if (firstSpace > 0) {
            val first = line.substring(0, firstSpace)
            if (isIpLiteral(first)) {
                val rest = line.substring(firstSpace).trim()
                // Take the first whitespace-separated token of the rest as the domain
                val domain = rest.split(Regex("\\s+"))[0]
                val cleaned = stripModifiers(domain)
                return if (isValidDomain(cleaned)) ParseResult.Block(cleaned) else ParseResult.Ignore
            }
        }

        // Bare domain (with optional $modifiers)
        val cleaned = stripModifiers(line)
        return if (isValidDomain(cleaned)) ParseResult.Block(cleaned) else ParseResult.Ignore
    }

    /** Parses a complete blocklist file's contents and returns the (block, allow)
     *  domain lists. Unsupported lines are logged at WARN level. */
    fun parse(contents: String): Pair<List<String>, List<String>> {
        val block = ArrayList<String>()
        val allow = ArrayList<String>()
        var unsupportedCount = 0
        for (line in contents.lineSequence()) {
            when (val r = parseLine(line)) {
                is ParseResult.Block -> block.add(r.domain)
                is ParseResult.Allow -> allow.add(r.domain)
                ParseResult.Ignore -> { /* skip */ }
                ParseResult.Unsupported -> unsupportedCount++
            }
        }
        if (unsupportedCount > 0) {
            Log.i(TAG, "Skipped $unsupportedCount cosmetic/scriptlet rules (not applicable at DNS layer)")
        }
        return block to allow
    }

    /** Extracts the domain from an ABP `||domain^...` style rule,
     *  stopping at `^`, `/`, `$`, or whitespace. */
    private fun extractAbpDomain(afterSeparator: String): String? {
        val end = afterSeparator.indexOfFirst { it == '^' || it == '/' || it == '$' || it.isWhitespace() }
        val candidate = if (end == -1) afterSeparator else afterSeparator.substring(0, end)
        val cleaned = stripModifiers(candidate)
        return if (isValidDomain(cleaned)) cleaned else null
    }

    /** Strips `$modifier` suffixes from a domain token. */
    private fun stripModifiers(token: String): String {
        val dollar = token.indexOf('$')
        val cleaned = if (dollar >= 0) token.substring(0, dollar) else token
        // Also strip a trailing `^` (ABP separator)
        return cleaned.trimEnd('^').trim()
    }

    /** Returns true if [s] looks like a domain: at least one dot, no spaces,
     *  no leading/trailing punctuation, and ASCII-only (we don't support IDN). */
    private fun isValidDomain(s: String): Boolean {
        if (s.isEmpty() || s.length > 253) return false
        if (s.startsWith(".") || s.endsWith(".")) return false
        if (s.contains(' ') || s.contains('\t')) return false
        if (!s.contains('.')) return false
        // Reject anything with characters that wouldn't survive a DNS query
        for (c in s) {
            if (!(c.isLetterOrDigit() || c == '.' || c == '-' || c == '_' || c == '*')) return false
        }
        return true
    }

    private fun isIpLiteral(s: String): Boolean {
        // 0.0.0.0, 127.0.0.1, ::, ::1, etc. — quick check, not exhaustive.
        if (s.isEmpty()) return false
        if (s.startsWith("0.0.0.0") || s.startsWith("127.0.0.1")) return true
        if (s == "::" || s == "::1" || s == "0::0") return true
        // Generic IPv4 dotted-quad check
        val parts = s.split('.')
        if (parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 }) return true
        return false
    }
}
