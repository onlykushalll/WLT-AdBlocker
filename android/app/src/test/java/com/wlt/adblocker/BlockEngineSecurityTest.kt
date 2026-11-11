package com.wlt.adblocker

import com.wlt.adblocker.filter.BlocklistParser
import com.wlt.adblocker.filter.DomainTrie
import com.wlt.adblocker.filter.Verdict
import com.wlt.adblocker.vpn.DnsPacketParser
import org.junit.Assert.*
import org.junit.Test

/**
 * Security, rule-cascade, and DNS response tests for the Kotlin fallback
 * domain trie, blocklist parser, and RFC 1035 packet builder.
 */
class BlockEngineSecurityTest {

    private val allowTrie = DomainTrie().apply {
        listOf(
            "google.com",
            "googlevideo.com",
            "chase.com",
            "paypal.com",
            "visa.com",
        ).forEach { insert(it, Verdict.ALLOW) }
    }

    private val blockTrie = DomainTrie().apply {
        listOf(
            "doubleclick.net",
            "googlesyndication.com",
            "googleadservices.com",
            "adservice.google.com",
            "unityads.unity3d.com",
            "applovin.com",
            "ironsrc.com",
            "chartboost.com",
            "vungle.com",
            "an.facebook.com",
            "adcolony.com",
            "mintegral.com",
            "fyber.com",
            "tapjoy.com",
            "inmobi.com",
            "dns.google",
            "cloudflare-dns.com",
            "dns.quad9.net",
            "doh.opendns.com",
            "dns.adguard.com",
        ).forEach { insert(it, Verdict.BLOCK) }
    }

    private fun shouldBlock(domain: String, customOverrides: Map<String, Verdict> = emptyMap()): Boolean {
        val norm = domain.trim().trimEnd('.').lowercase()
        if (norm.isEmpty() || norm == "localhost" || !norm.contains('.')) return false
        customOverrides[norm]?.let { return it == Verdict.BLOCK }
        if (allowTrie.lookup(norm) == Verdict.ALLOW) return false
        return blockTrie.lookup(norm) == Verdict.BLOCK
    }

    @Test
    fun testAdDomainsBlocked() {
        val adDomains = listOf(
            "pagead2.googlesyndication.com",
            "googleads.g.doubleclick.net",
            "ads.unityads.unity3d.com",
            "rt.applovin.com",
            "api.ironsrc.com",
            "live.chartboost.com",
            "api.vungle.com",
            "an.facebook.com",
            "ads.adcolony.com",
            "api.mintegral.com",
            "engine.fyber.com",
            "connect.tapjoy.com",
            "api.inmobi.com",
        )
        var blocked = 0
        for (d in adDomains) {
            if (shouldBlock(d)) blocked++
        }
        assertEquals("Not all ad domains blocked ($blocked/${adDomains.size})", adDomains.size, blocked)
    }

    @Test
    fun testLegitimateDomainsAllowed() {
        val legitDomains = listOf(
            "www.google.com",
            "mail.google.com",
            "drive.google.com",
            "r1.sn.googlevideo.com",
            "www.chase.com",
            "www.paypal.com",
            "www.visa.com",
            "github.com",
            "stackoverflow.com",
            "wikipedia.org",
            "android.com",
            "kotlinlang.org",
        )
        var falsePositives = 0
        for (d in legitDomains) {
            if (shouldBlock(d)) {
                falsePositives++
                println("FALSE POSITIVE: $d was blocked!")
            }
        }
        assertEquals("False positives detected ($falsePositives)", 0, falsePositives)
    }

    @Test
    fun testWildcardSubdomainsBlocked() {
        val wildcardTests = listOf(
            "x.doubleclick.net" to true,
            "a.b.c.doubleclick.net" to true,
            "sub.applovin.com" to true,
            "deep.nested.vungle.com" to true,
        )
        for ((domain, expected) in wildcardTests) {
            val result = shouldBlock(domain)
            assertEquals("Wildcard subdomain $domain: expected $expected got $result", expected, result)
        }
    }

    @Test
    fun testEdgeCases() {
        assertFalse("Empty domain should not block", shouldBlock(""))
        assertFalse("Single char should not block", shouldBlock("a"))
        assertFalse("Just dots should not block", shouldBlock("..."))
        assertFalse("No dot should not block", shouldBlock("localhost"))
        val longDomain = "a".repeat(250) + ".com"
        assertFalse("Very long domain should not crash", shouldBlock(longDomain))
        assertFalse("Unlisted domain should allow", shouldBlock("test.com"))
    }

    @Test
    fun testAllowlistPrecedence() {
        assertFalse("sub.google.com should be allowed", shouldBlock("sub.google.com"))
    }

    @Test
    fun testCustomRulesOverride() {
        assertTrue(
            "Custom block should override allowlist",
            shouldBlock("google.com", mapOf("google.com" to Verdict.BLOCK)),
        )
    }

    @Test
    fun testDoHBypassPrevention() {
        val dohBypassDomains = listOf(
            "dns.google",
            "cloudflare-dns.com",
            "dns.quad9.net",
            "doh.opendns.com",
            "dns.adguard.com",
        )
        val blocked = dohBypassDomains.count { shouldBlock(it) }
        assertEquals("All DoH bypass domains must be blocked", dohBypassDomains.size, blocked)
    }

    @Test
    fun testBlocklistParserFormats() {
        assertEquals(BlocklistParser.ParseResult.Block("ads.example.com"), BlocklistParser.parseLine("||ads.example.com^"))
        assertEquals(BlocklistParser.ParseResult.Allow("safe.example.com"), BlocklistParser.parseLine("@@||safe.example.com^"))
        assertEquals(BlocklistParser.ParseResult.Block("tracker.test"), BlocklistParser.parseLine("0.0.0.0 tracker.test"))
        assertEquals(BlocklistParser.ParseResult.Unsupported, BlocklistParser.parseLine("example.com##.ad-banner"))
    }

    @Test
    fun testBlockResponseTypes() {
        val query = buildQuery("blocked.com")
        assertEquals("blocked.com", DnsPacketParser.extractQueryName(query))
        val nxdomain = DnsPacketParser.buildNXDOMAIN(query)
        assertTrue("NXDOMAIN response should not be empty", nxdomain.isNotEmpty())
        assertTrue("NXDOMAIN response should fit in UDP (<512)", nxdomain.size < 512)
        val nullIp = DnsPacketParser.buildNullIP(query)
        assertTrue("NullIP response should not be empty", nullIp.isNotEmpty())
        val refused = DnsPacketParser.buildREFUSED(query)
        assertTrue("REFUSED response should not be empty", refused.isNotEmpty())
    }

    private fun buildQuery(domain: String): ByteArray {
        val buf = mutableListOf<Byte>()
        buf.addAll(listOf(0x00, 0x01).map { it.toByte() })
        buf.addAll(listOf(0x01, 0x00).map { it.toByte() })
        buf.addAll(listOf(0x00, 0x01).map { it.toByte() })
        buf.addAll(listOf(0x00, 0x00).map { it.toByte() })
        buf.addAll(listOf(0x00, 0x00).map { it.toByte() })
        buf.addAll(listOf(0x00, 0x00).map { it.toByte() })
        for (label in domain.split(".")) {
            buf.add(label.length.toByte())
            buf.addAll(label.map { it.code.toByte() })
        }
        buf.add(0)
        buf.addAll(listOf(0x00, 0x01).map { it.toByte() })
        buf.addAll(listOf(0x00, 0x01).map { it.toByte() })
        return buf.toByteArray()
    }
}
