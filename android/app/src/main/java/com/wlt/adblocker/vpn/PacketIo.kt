package com.wlt.adblocker.vpn

import android.util.Log
import java.net.InetAddress

/**
 * IPv4 + UDP packet parser/builder for the VPN tun interface.
 *
 * All packet handling in WLT operates on IPv4 only, because Android's
 * VpnService.Builder.addRoute() with "0.0.0.0/0" plus our explicit
 * IPv6 bypass (`addDsuRoute`/allow-families) keeps us on the v4 path
 * for DNS interception. If v6 ever gets routed here, the [parse] call
 * will return null and the loop will drop the packet rather than
 * misinterpreting it.
 *
 * Endianness: all multi-byte fields are big-endian (network byte order)
 * per RFC 791 (IPv4) and RFC 768 (UDP). Reading/writing goes through
 * [NetBytes] to keep that assumption in one place.
 *
 * The checksum helpers here implement RFC 1071 (the "Internet checksum"
 * used by both IPv4 headers and UDP-over-IPv4 with the pseudo-header)
 * and are reused by [ConnectionFilter]'s TCP path.
 */
object PacketIo {

    private const val TAG = "PacketIo"

    const val IP_PROTO_ICMP = 1
    const val IP_PROTO_TCP = 6
    const val IP_PROTO_UDP = 17

    /** Minimum IPv4 header length in bytes (no options). */
    const val IPV4_HEADER_MIN_LEN = 20
    /** UDP header length in bytes. */
    const val UDP_HEADER_LEN = 8

    /** Parsed IPv4 header. Source/destination returned as raw 4-byte arrays
     *  (NOT copies — callers MUST NOT mutate the underlying buffer through them). */
    data class Ipv4Header(
        val version: Int,
        val ihl: Int,                // header length in 4-byte words
        val headerLen: Int,          // = ihl * 4
        val tos: Int,
        val totalLength: Int,
        val identification: Int,
        val flagsFragment: Int,
        val ttl: Int,
        val protocol: Int,
        val checksum: Int,
        val srcAddr: ByteArray,
        val dstAddr: ByteArray,
    )

    /** Parsed UDP header. */
    data class UdpHeader(
        val srcPort: Int,
        val dstPort: Int,
        val length: Int,
        val checksum: Int,
    )

    /** Tries to parse an IPv4 packet. Returns null if the buffer is not IPv4,
     *  is truncated, or has an inconsistent IHL/total-length combination. */
    fun parseIpv4(buf: ByteArray, length: Int): Pair<Ipv4Header, Int>? {
        if (length < IPV4_HEADER_MIN_LEN) {
            Log.w(TAG, "IPv4 packet too short: $length bytes")
            return null
        }
        val versionIhl = buf[0].asUnsignedInt()
        val version = (versionIhl shr 4) and 0x0F
        if (version != 4) {
            // Not IPv4 — caller should drop or handle IPv6 separately.
            return null
        }
        val ihl = versionIhl and 0x0F
        val headerLen = ihl * 4
        if (headerLen < IPV4_HEADER_MIN_LEN || headerLen > length) {
            Log.w(TAG, "IPv4 IHL out of bounds: ihl=$ihl, len=$length")
            return null
        }
        val totalLength = NetBytes.getUShort(buf, 2)
        if (totalLength < headerLen || totalLength > length) {
            // Tolerate trailing padding (some drivers deliver more bytes than totalLength)
            // but never trust a totalLength smaller than the header.
            if (totalLength < headerLen) return null
        }
        val protocol = buf[9].asUnsignedInt()
        val srcAddr = buf.copyOfRange(12, 16)
        val dstAddr = buf.copyOfRange(16, 20)
        val header = Ipv4Header(
            version = version,
            ihl = ihl,
            headerLen = headerLen,
            tos = buf[1].asUnsignedInt(),
            totalLength = totalLength,
            identification = NetBytes.getUShort(buf, 4),
            flagsFragment = NetBytes.getUShort(buf, 6),
            ttl = buf[8].asUnsignedInt(),
            protocol = protocol,
            checksum = NetBytes.getUShort(buf, 10),
            srcAddr = srcAddr,
            dstAddr = dstAddr,
        )
        return header to headerLen
    }

    /** TCP header (minimal — just enough for port extraction). */
    data class TcpHeader(
        val srcPort: Int,
        val dstPort: Int,
    )

    /** Parses a TCP header at [offset]. Returns null if not enough bytes.
     *  Only extracts source and destination ports (first 4 bytes). */
    fun parseTcp(buf: ByteArray, offset: Int, length: Int): TcpHeader? {
        if (offset + 4 > length) return null // Need at least srcPort + dstPort
        return TcpHeader(
            srcPort = NetBytes.getUShort(buf, offset),
            dstPort = NetBytes.getUShort(buf, offset + 2),
        )
    }

    /** Parses a UDP header at [offset]. Returns null if not enough bytes. */
    fun parseUdp(buf: ByteArray, offset: Int, length: Int): UdpHeader? {
        if (offset + UDP_HEADER_LEN > length) return null
        return UdpHeader(
            srcPort = NetBytes.getUShort(buf, offset),
            dstPort = NetBytes.getUShort(buf, offset + 2),
            length = NetBytes.getUShort(buf, offset + 4),
            checksum = NetBytes.getUShort(buf, offset + 6),
        )
    }

    /** Builds a complete IPv4/UDP packet with the given payload.
     *  Source/destination addresses are 4-byte arrays. Computes both the IPv4
     *  header checksum and the UDP checksum (with pseudo-header) per RFC 1071. */
    fun buildUdpIpv4Packet(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray,
    ): ByteArray {
        require(srcIp.size == 4 && dstIp.size == 4) { "IPv4 addresses must be 4 bytes" }
        val udpLen = UDP_HEADER_LEN + payload.size
        val totalLen = IPV4_HEADER_MIN_LEN + udpLen

        val packet = ByteArray(totalLen)
        // --- IPv4 header ---
        packet[0] = 0x45.toByte()                                 // version=4, IHL=5
        packet[1] = 0x00                                          // TOS
        NetBytes.putUShort(packet, 2, totalLen)
        NetBytes.putUShort(packet, 4, 0)                          // identification
        NetBytes.putUShort(packet, 6, 0x4000)                     // flags=DF, fragment offset=0
        packet[8] = 64                                            // TTL
        packet[9] = IP_PROTO_UDP.toByte()
        NetBytes.putUShort(packet, 10, 0)                         // checksum placeholder
        System.arraycopy(srcIp, 0, packet, 12, 4)
        System.arraycopy(dstIp, 0, packet, 16, 4)
        // IPv4 header checksum over the 20-byte header only
        NetBytes.putUShort(packet, 10, internetChecksum(packet, 0, IPV4_HEADER_MIN_LEN))

        // --- UDP header ---
        val udpOffset = IPV4_HEADER_MIN_LEN
        NetBytes.putUShort(packet, udpOffset, srcPort)
        NetBytes.putUShort(packet, udpOffset + 2, dstPort)
        NetBytes.putUShort(packet, udpOffset + 4, udpLen)
        NetBytes.putUShort(packet, udpOffset + 6, 0)              // checksum placeholder
        System.arraycopy(payload, 0, packet, udpOffset + UDP_HEADER_LEN, payload.size)

        // UDP checksum includes pseudo-header (src IP, dst IP, protocol, UDP length)
        val pseudo = ByteArray(12)
        System.arraycopy(srcIp, 0, pseudo, 0, 4)
        System.arraycopy(dstIp, 0, pseudo, 4, 4)
        pseudo[8] = 0
        pseudo[9] = IP_PROTO_UDP.toByte()
        NetBytes.putUShort(pseudo, 10, udpLen)
        val combined = pseudo + packet.copyOfRange(udpOffset, udpOffset + udpLen)
        val udpChecksum = internetChecksum(combined, 0, combined.size)
        NetBytes.putUShort(packet, udpOffset + 6, if (udpChecksum == 0) 0xFFFF else udpChecksum)
        return packet
    }

    /** Computes the RFC 1071 Internet checksum over [buf] range [offset, offset+length].
     *  Returns the one's-complement sum as an unsigned 16-bit value. Used for
     *  IPv4 header checksums and UDP/TCP pseudo-header checksums. */
    fun internetChecksum(buf: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            sum += NetBytes.getUShort(buf, i).toLong()
            i += 2
        }
        // Trailing odd byte: pad with zero high byte (RFC 1071)
        if (i < end) {
            sum += (buf[i].asUnsignedInt() shl 8).toLong()
        }
        // Fold 32-bit sum into 16 bits
        while ((sum ushr 16) != 0L) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        val result = (sum.toInt() and 0xFFFF).inv() and 0xFFFF
        return result
    }

    /** Convenience: returns true if [buf] is an IPv4 packet carrying UDP
     *  with destination port [port]. Used by the VPN loop to quickly
     *  identify DNS queries without allocating a full [Ipv4Header]. */
    fun isIpv4UdpToPort(buf: ByteArray, length: Int, port: Int): Boolean {
        val parsed = parseIpv4(buf, length) ?: return false
        val (header, headerLen) = parsed
        if (header.protocol != IP_PROTO_UDP) return false
        val udp = parseUdp(buf, headerLen, length) ?: return false
        return udp.dstPort == port
    }

    /** Returns the InetAddress view of a 4-byte IPv4 address. */
    fun inetFromBytes(bytes: ByteArray): InetAddress =
        InetAddress.getByAddress(bytes)
}
