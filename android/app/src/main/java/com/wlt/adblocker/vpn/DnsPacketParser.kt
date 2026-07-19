package com.wlt.adblocker.vpn

import java.nio.ByteBuffer

/**
 * RFC 1035 DNS message parser — minimal, fast, allocation-light.
 *
 * Used inside the VPN packet loop to extract the queried domain before
 * delegating to the block engine.
 *
 * Also extracts CNAME records from upstream responses for cloaking detection.
 */
object DnsPacketParser {

    private const val TYPE_CNAME: Short = 5

    /**
     * Extract the queried domain from a DNS query packet.
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
                off++
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
     * Extract CNAME targets from a DNS response packet.
     * Returns list of CNAME target domains (for cloaking detection).
     */
    fun extractCnameTargets(packet: ByteArray, length: Int): List<String> {
        if (length < 12) return emptyList()
        val anCount = ((packet[6].toInt() and 0xFF) shl 8) or (packet[7].toInt() and 0xFF)
        if (anCount == 0) return emptyList()

        // Skip question section
        var off = 12
        val qdCount = ((packet[4].toInt() and 0xFF) shl 8) or (packet[5].toInt() and 0xFF)
        for (i in 0 until qdCount) {
            // Skip QNAME
            while (off < length) {
                val b = packet[off].toInt() and 0xFF
                off++
                if (b == 0) break
                if (b and 0xC0 == 0xC0) { off++; break }
                off += b
            }
            off += 4 // QTYPE + QCLASS
        }

        val cnames = mutableListOf<String>()
        // Parse answer section
        for (i in 0 until anCount) {
            if (off >= length) break
            // Skip answer NAME (may use compression)
            off = skipName(packet, off, length)
            if (off + 10 > length) break

            val type = ((packet[off].toInt() and 0xFF) shl 8) or (packet[off + 1].toInt() and 0xFF)
            val rdlen = ((packet[off + 8].toInt() and 0xFF) shl 8) or (packet[off + 9].toInt() and 0xFF)
            off += 10 // TYPE(2) + CLASS(2) + TTL(4) + RDLENGTH(2)

            if (type == TYPE_CNAME.toInt() && off + rdlen <= length) {
                // RDATA is a domain name (may use compression)
                val cname = readName(packet, off, length)
                if (cname.isNotEmpty()) cnames.add(cname)
            }
            off += rdlen
        }
        return cnames
    }

    private fun skipName(packet: ByteArray, offset: Int, length: Int): Int {
        var off = offset
        while (off < length) {
            val b = packet[off].toInt() and 0xFF
            if (b == 0) { off++; break }
            if (b and 0xC0 == 0xC0) { off += 2; break }
            off += 1 + b
        }
        return off
    }

    private fun readName(packet: ByteArray, offset: Int, length: Int): String {
        val sb = StringBuilder(64)
        var off = offset
        var jumped = false
        var jumps = 0
        while (off < length && jumps < 128) {
            val b = packet[off].toInt() and 0xFF
            if (b == 0) break
            if (b and 0xC0 == 0xC0) {
                if (off + 1 >= length) break
                val ptr = ((b and 0x3F) shl 8) or (packet[off + 1].toInt() and 0xFF)
                off = ptr
                jumped = true
                jumps++
                continue
            }
            off++
            if (off + b > length) break
            if (sb.isNotEmpty()) sb.append('.')
            for (i in 0 until b) {
                val c = packet[off + i].toInt() and 0xFF
                sb.append(Character.toLowerCase(c.toChar()))
            }
            off += b
            if (jumped) jumps++
        }
        return sb.toString()
    }

    /**
     * Build an NXDOMAIN response for a query packet (block action).
     */
    fun buildNxDomain(query: ByteArray, queryLen: Int): ByteArray {
        if (queryLen < 12) return ByteArray(0)
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
        val response = ByteArray(off)
        System.arraycopy(query, 0, response, 0, off)
        var flags = ((query[2].toInt() and 0xFF) shl 8) or (query[3].toInt() and 0xFF)
        flags = flags or 0x8000 or 0x0080
        flags = flags and 0xFFF0.inv()
        flags = flags or 0x0003
        response[2] = ((flags shr 8) and 0xFF).toByte()
        response[3] = (flags and 0xFF).toByte()
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
        val response = ByteArray(off + 16)
        System.arraycopy(query, 0, response, 0, off)
        var flags = ((query[2].toInt() and 0xFF) shl 8) or (query[3].toInt() and 0xFF)
        flags = flags or 0x8000 or 0x0080
        flags = flags and 0xFFF0.inv()
        response[2] = ((flags shr 8) and 0xFF).toByte()
        response[3] = (flags and 0xFF).toByte()
        response[6] = 0; response[7] = 1
        response[8] = 0; response[9] = 0
        response[10] = 0; response[11] = 0
        response[off] = 0xC0.toByte(); response[off + 1] = 0x0C
        response[off + 2] = 0; response[off + 3] = 1
        response[off + 4] = 0; response[off + 5] = 1
        response[off + 6] = 0; response[off + 7] = 0; response[off + 8] = 0; response[off + 9] = 60
        response[off + 10] = 0; response[off + 11] = 4
        response[off + 12] = 0; response[off + 13] = 0; response[off + 14] = 0; response[off + 15] = 0
        return response
    }
}
