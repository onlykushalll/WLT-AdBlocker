package com.wlt.adblocker.vpn

/**
 * Minimal IPv4 + UDP header parse/build. Ported from Claude's clean implementation.
 *
 * IPv4-only for Phase 1. Only parses UDP/DNS — never arbitrary TCP/IP.
 * This is the narrow DNS-only architecture (same as DNS66/PersonalDNSFilter).
 */
object PacketIo {

    const val PROTOCOL_UDP = 17

    data class Ipv4Header(
        val headerLength: Int,
        val totalLength: Int,
        val protocol: Int,
        val srcAddr: ByteArray,
        val dstAddr: ByteArray,
    )

    data class UdpHeader(
        val srcPort: Int,
        val dstPort: Int,
        val length: Int,
        val payloadOffset: Int,
    )

    fun parseIpv4Header(buf: ByteArray, len: Int): Ipv4Header? {
        if (len < 20) return null
        val versionAndIhl = buf[0].asUnsignedInt()
        if (versionAndIhl shr 4 != 4) return null
        val headerLength = (versionAndIhl and 0x0F) * 4
        if (headerLength < 20 || headerLength > len) return null
        val totalLength = NetBytes.getUShort(buf, 2)
        if (totalLength > len) return null
        val protocol = buf[9].asUnsignedInt()
        return Ipv4Header(
            headerLength = headerLength,
            totalLength = totalLength,
            protocol = protocol,
            srcAddr = buf.copyOfRange(12, 16),
            dstAddr = buf.copyOfRange(16, 20),
        )
    }

    fun parseUdpHeader(buf: ByteArray, offset: Int, bufLen: Int): UdpHeader? {
        if (offset + 8 > bufLen) return null
        val srcPort = NetBytes.getUShort(buf, offset)
        val dstPort = NetBytes.getUShort(buf, offset + 2)
        val length = NetBytes.getUShort(buf, offset + 4)
        if (length < 8 || offset + length > bufLen) return null
        return UdpHeader(srcPort, dstPort, length, offset + 8)
    }

    /** Standard RFC 1071 Internet checksum. */
    fun internetChecksum(buf: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            sum += NetBytes.getUShort(buf, i)
            i += 2
        }
        if (i < end) {
            sum += buf[i].asUnsignedInt().toLong() shl 8
        }
        while ((sum shr 16) != 0L) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return (sum.inv() and 0xFFFF).toInt()
    }

    /**
     * Builds a complete IPv4 + UDP packet carrying [payload].
     * UDP checksum left as 0 (valid per RFC 768 for UDP/IPv4).
     * IPv4 header checksum computed properly.
     */
    fun buildUdpIpv4Packet(
        srcAddr: ByteArray, srcPort: Int,
        dstAddr: ByteArray, dstPort: Int,
        payload: ByteArray,
    ): ByteArray {
        val udpLength = 8 + payload.size
        val totalLength = 20 + udpLength
        val out = ByteArray(totalLength)

        out[0] = 0x45 // version 4, IHL 5
        out[1] = 0x00
        NetBytes.putUShort(out, 2, totalLength)
        NetBytes.putUShort(out, 4, 0)
        NetBytes.putUShort(out, 6, 0x4000) // don't-fragment
        out[8] = 64 // TTL
        out[9] = PROTOCOL_UDP.toByte()
        NetBytes.putUShort(out, 10, 0) // checksum placeholder
        System.arraycopy(srcAddr, 0, out, 12, 4)
        System.arraycopy(dstAddr, 0, out, 16, 4)
        NetBytes.putUShort(out, 10, internetChecksum(out, 0, 20))

        val udpOffset = 20
        NetBytes.putUShort(out, udpOffset, srcPort)
        NetBytes.putUShort(out, udpOffset + 2, dstPort)
        NetBytes.putUShort(out, udpOffset + 4, udpLength)
        NetBytes.putUShort(out, udpOffset + 6, 0) // no UDP checksum
        System.arraycopy(payload, 0, out, udpOffset + 8, payload.size)

        return out
    }
}

/** Byte extension for unsigned interpretation (Kotlin bytes are signed). */
fun Byte.asUnsignedInt(): Int = this.toInt() and 0xFF
