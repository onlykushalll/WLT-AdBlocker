package com.wlt.adblocker.vpn

import java.nio.ByteBuffer

/**
 * RFC 1035 DNS message parser — minimal, fast, allocation-light.
 *
 * Used inside the VPN packet loop to extract the queried domain before
 * delegating to the Go core (via gomobile) for the block decision.
 *
 * Layout reference:
 *   Header (12 bytes): ID(2) Flags(2) QDCOUNT(2) ANCOUNT(2) NSCOUNT(2) ARCOUNT(2)
 *   Question: QNAME(variable) QTYPE(2) QCLASS(2)
 *   QNAME: [len][label]...[0]
 */
object DnsPacketParser {

    private const val TYPE_A: Short = 1
    private const val TYPE_AAAA: Short = 28
    private const val TYPE_CNAME: Short = 5

    /**
     * Extract the queried domain from a DNS query packet.
     * Returns lowercase domain or null if unparseable.
     */
    fun extractQueryDomain(packet: ByteArray, length: Int): String? {
        if (length < 12) return null
        val qdCount = ((packet[4].toInt() and 0xFF) shl 8) or (packet[5].toInt() and 0xFF)
        if (qdCount == 0) return null

        var off = 12
        val sb = StringBuilder(64)
        while (off < length) {
            val labelLen = packet[off].toInt() and 0xFF
            off++
            if (labelLen == 0) break
            if (labelLen and 0xC0 == 0xC0) {
                // Compression pointer — shouldn't appear in questions, but handle
                off++ // skip second byte of pointer
                break
            }
            if (off + labelLen > length) return null
            if (sb.isNotEmpty()) sb.append('.')
            for (i in 0 until labelLen) {
                val b = packet[off + i].toInt() and 0xFF
                sb.append(Character.toLowerCase(b.toChar()))
            }
            off += labelLen
        }
        return sb.toString().ifEmpty { null }
    }

    /**
     * Build an NXDOMAIN response for a query packet (block action).
     * Copies the header ID and question section, sets RCODE=3.
     */
    fun buildNxDomain(query: ByteArray, queryLen: Int): ByteArray {
        if (queryLen < 12) return ByteArray(0)
        // Echo header + questions, change flags to response with NXDOMAIN
        // Find end of question section
        var off = 12
        val qdCount = ((query[4].toInt() and 0xFF) shl 8) or (query[5].toInt() and 0xFF)
        for (i in 0 until qdCount) {
            // Skip QNAME
            while (off < queryLen) {
                val b = query[off].toInt() and 0xFF
                off++
                if (b == 0) break
                if (b and 0xC0 == 0xC0) { off++; break }
                off += b
            }
            off += 4 // QTYPE + QCLASS
        }
        val response = ByteArray(off)
        System.arraycopy(query, 0, response, 0, off)
        // Set QR=1, RCODE=3 (NXDOMAIN), RA=1
        var flags = ((query[2].toInt() and 0xFF) shl 8) or (query[3].toInt() and 0xFF)
        flags = flags or 0x8000 // QR = 1
        flags = flags or 0x0080 // RA = 1
        flags = flags and 0xFFF0.inv() // clear RCODE
        flags = flags or 0x0003 // RCODE = 3 (NXDOMAIN)
        response[2] = ((flags shr 8) and 0xFF).toByte()
        response[3] = (flags and 0xFF).toByte()
        // Clear answer/auth/additional counts
        response[6] = 0; response[7] = 0
        response[8] = 0; response[9] = 0
        response[10] = 0; response[11] = 0
        return response
    }

    /**
     * Build a 0.0.0.0 A response (AdAway-style sinkhole).
     */
    fun buildNullIp(query: ByteArray, queryLen: Int): ByteArray {
        if (queryLen < 12) return ByteArray(0)
        // Find question end
        var off = 12
        val qdCount = ((query[4].toInt() and 0xFF) shl 8) or (query[5].toInt() and 0xFF)
        for (i in 0 until qdCount) {
            while (off < queryLen) {
                val b = query[off].toInt() and 0xFF
                off++
                if (b == 0) break
                if (b and 0xC0 == 0xC0) { off++; break }
                off += b
            }
            off += 4
        }
        // Header(12) + question(off-12) + answer(name ptr=2, type=2, class=2, ttl=4, rdlen=2, rdata=4)
        val response = ByteArray(off + 16)
        System.arraycopy(query, 0, response, 0, off)
        var flags = ((query[2].toInt() and 0xFF) shl 8) or (query[3].toInt() and 0xFF)
        flags = flags or 0x8000 or 0x0080
        flags = flags and 0xFFF0.inv() // RCODE = 0 (NOERROR)
        response[2] = ((flags shr 8) and 0xFF).toByte()
        response[3] = (flags and 0xFF).toByte()
        // QDCOUNT already in response; set ANCOUNT = 1
        response[6] = 0; response[7] = 1
        response[8] = 0; response[9] = 0
        response[10] = 0; response[11] = 0
        // Answer: name pointer to question (0xC00C), type A, class IN, TTL 60, rdlen 4, 0.0.0.0
        response[off] = 0xC0.toByte(); response[off + 1] = 0x0C
        response[off + 2] = 0; response[off + 3] = 1 // type A
        response[off + 4] = 0; response[off + 5] = 1 // class IN
        response[off + 6] = 0; response[off + 7] = 0; response[off + 8] = 0; response[off + 9] = 60 // TTL
        response[off + 10] = 0; response[off + 11] = 4 // rdlen
        response[off + 12] = 0; response[off + 13] = 0; response[off + 14] = 0; response[off + 15] = 0
        return response
    }
}
