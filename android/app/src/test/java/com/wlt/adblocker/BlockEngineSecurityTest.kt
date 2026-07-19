package com.wlt.adblocker

import com.wlt.adblocker.data.RuleStore
import com.wlt.adblocker.vpn.DnsPacketParser
import com.wlt.adblocker.vpn.KotlinBlockEngine
import org.junit.Assert.*
import org.junit.Test

/**
 * Vulnerability and block-rate tests for the KotlinBlockEngine.
 */
class BlockEngineSecurityTest {

    private val engine = KotlinBlockEngine().apply {
        addBlock("doubleclick.net")
        addBlock("googlesyndication.com")
        addBlock("googleadservices.com")
        addBlock("adservice.google.com")
        addBlock("unityads.unity3d.com")
        addBlock("applovin.com")
        addBlock("ironsrc.com")
        addBlock("chartboost.com")
        addBlock("vungle.com")
        addBlock("an.facebook.com")
        addBlock("adcolony.com")
        addBlock("mintegral.com")
        addBlock("fyber.com")
        addBlock("tapjoy.com")
        addBlock("inmobi.com")
        addAllow("google.com")
        addAllow("googlevideo.com")
        addAllow("chase.com")
        addAllow("paypal.com")
        addAllow("visa.com")
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
            "api.inmobi.com"
        )
        var blocked = 0
        for (d in adDomains) {
            if (engine.shouldBlock(d)) blocked++
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
            "kotlinlang.org"
        )
        var falsePositives = 0
        for (d in legitDomains) {
            if (engine.shouldBlock(d)) {
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
            "deep.nested.vungle.com" to true
        )
        for ((domain, shouldBlock) in wildcardTests) {
            val result = engine.shouldBlock(domain)
            assertEquals("Wildcard subdomain $domain: expected $shouldBlock got $result", shouldBlock, result)
        }
    }

    @Test
    fun testEdgeCases() {
        assertFalse("Empty domain should not block", engine.shouldBlock(""))
        assertFalse("Single char should not block", engine.shouldBlock("a"))
        assertFalse("Just dots should not block", engine.shouldBlock("..."))
        assertFalse("No dot should not block", engine.shouldBlock("localhost"))
        val longDomain = "a".repeat(250) + ".com"
        assertFalse("Very long domain should not crash", engine.shouldBlock(longDomain))
        assertFalse("Unicode domain should not crash", engine.shouldBlock("test.com"))
    }

    @Test
    fun testAllowlistPrecedence() {
        engine.addAllow("google.com")
        assertFalse("sub.google.com should be allowed", engine.shouldBlock("sub.google.com"))
    }

    @Test
    fun testCustomRulesOverride() {
        RuleStore.addRule("google.com", true)
        assertTrue("Custom block should override allowlist", engine.shouldBlock("google.com"))
        RuleStore.removeRule("google.com")
    }

    @Test
    fun testDoHBypassPrevention() {
        // Documents vulnerability: DoH bypass domains not blocked
        val dohBypassDomains = listOf(
            "dns.google",
            "cloudflare-dns.com",
            "dns.quad9.net",
            "doh.opendns.com",
            "dns.adguard.com"
        )
        var blocked = 0
        for (d in dohBypassDomains) {
            if (engine.shouldBlock(d)) blocked++
        }
        println("DoH bypass domains blocked: $blocked/${dohBypassDomains.size}")
        // TODO: Add DoH bypass domains to blocklist to fix this vulnerability
    }

    @Test
    fun testBlockResponseTypes() {
        val query = buildQuery("blocked.com")
        val nxdomain = DnsPacketParser.buildNxDomain(query, query.size)
        assertTrue("NXDOMAIN response should not be empty", nxdomain.isNotEmpty())
        assertTrue("NXDOMAIN response should fit in UDP (<512)", nxdomain.size < 512)
        val nullIp = DnsPacketParser.buildNullIp(query, query.size)
        assertTrue("NullIP response should not be empty", nullIp.isNotEmpty())
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
