package com.wlt.adblocker.vpn

import android.net.VpnService
import android.util.Log
import com.wlt.adblocker.filter.DomainTrie
import com.wlt.adblocker.filter.Verdict
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * ================================ READ THIS FIRST ================================
 * This is the highest-risk file in this whole batch, and I want that stated
 * plainly rather than buried in a docstring nobody reads. Every other file
 * either fails to compile if wrong, or fails closed (allows a connection
 * it should have blocked). A bug in the RELAY path here -- the code that
 * runs for every ALLOWED connection, meaning almost all of them -- can
 * silently hang or reset real HTTPS connections on the device this runs
 * on. I have no way to test this against real traffic in this session; it
 * has never touched an actual TCP handshake with a real client.
 *
 * The TCP checksum + sequence-number arithmetic below WAS validated
 * standalone (scripts/validate_tcp.py, 10 checks, including the specific
 * "checksum changes if the pseudo-header's IP fields change" case that
 * catches a wrong-pseudo-header bug, and the ISN/ack running-total
 * arithmetic for a handshake + subsequent data). That gives me real
 * confidence in the byte-level mechanics. It does NOT give me confidence
 * in how this behaves under real-world conditions this code has never
 * seen: packet reordering, retransmission, slow/lossy real destinations,
 * concurrent connections at scale, or interaction with Android's own
 * network stack under memory/battery pressure.
 *
 * Recommended rollout, not optional advice: gate this behind its own
 * explicit setting, defaulted OFF, separate from the Phase 1 DNS-blocking
 * toggle. Test it yourself first against a small, deliberately-chosen set
 * of domains (not "everything") before considering wider use, and watch
 * for hung connections, not just crashes -- a hang is the failure mode
 * most likely here, and the easiest one to miss in casual testing.
 *
 * What this deliberately simplifies, and why each is a reasonable (not
 * free) simplification for a first pass:
 *  - No retransmission timers on our side. The link between the app and
 *    this code is a local virtual interface, not a real lossy network
 *    hop, so the loss this would protect against is much rarer here than
 *    for a normal TCP stack -- but "rarer" is not "never."
 *  - A fixed, generous advertised window rather than real flow control.
 *  - FIN handled as an immediate teardown rather than a proper
 *    three/four-way FIN exchange -- functionally fine for "connection
 *    ends," not spec-correct.
 *  - One background thread per relayed connection (server -> client
 *    direction), not a bounded pool. Fine at the scale of one phone's
 *    normal browsing; would need revisiting if this is ever handling
 *    hundreds of simultaneous connections.
 * ===================================================================================
 *
 * Architecture recap (see docs/ROADMAP.md and the earlier discussion this
 * session): once WltVpnService routes TCP:443 through the tun to make
 * this filter possible, EVERY such connection -- blocked or allowed --
 * must be handled here. There's no "peek at the SNI, then hand off to the
 * real network" option with VpnService; allowed connections are relayed
 * by this code via a real outbound socket, or they don't work at all.
 */
class ConnectionFilter(
    private val vpnService: VpnService,
    private val trieProvider: () -> DomainTrie,
    /** Caller supplies this so output writes stay synchronized with
     * whatever else (e.g. the DNS path) is writing to the same tun
     * output stream -- see WltVpnService's outputLock. */
    private val writePacket: (ByteArray) -> Unit,
    private val onSniDecision: (domain: String, blocked: Boolean) -> Unit,
) {
    companion object {
        private const val TAG = "ConnectionFilter"
        const val TCP_PROTOCOL = 6

        private const val FLAG_FIN = 0x01
        private const val FLAG_SYN = 0x02
        private const val FLAG_RST = 0x04
        private const val FLAG_PSH = 0x08
        private const val FLAG_ACK = 0x10

        private const val WINDOW_SIZE = 65535
        // One full TLS record's worth (16KB) plus its 5-byte header --
        // matches TlsClientHello's own MAX_TLS_RECORD_LENGTH assumption.
        private const val MAX_CLIENTHELLO_BUFFER = 16 * 1024 + 5

        private val secureRandom = SecureRandom()
    }

    private enum class State { SYN_RECEIVED, BUFFERING_CLIENT_HELLO, RELAYING, CLOSING }

    private class ConnKey(val clientAddr: ByteArray, val clientPort: Int, val dstAddr: ByteArray, val dstPort: Int) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ConnKey) return false
            return clientPort == other.clientPort && dstPort == other.dstPort &&
                clientAddr.contentEquals(other.clientAddr) && dstAddr.contentEquals(other.dstAddr)
        }
        override fun hashCode(): Int {
            var result = clientPort
            result = 31 * result + dstPort
            result = 31 * result + clientAddr.contentHashCode()
            result = 31 * result + dstAddr.contentHashCode()
            return result
        }
    }

    private class Connection(val key: ConnKey, val clientIsn: Long) {
        val lock = Any()
        val ourIsn: Long = secureRandom.nextLong() and 0xFFFFFFFFL
        var bytesSentToClient: Long = 0
        var bytesReceivedFromClient: Long = 0
        var state = State.SYN_RECEIVED
        val clientHelloBuffer = ByteArrayOutputStream()
        var realSocket: Socket? = null

        /** +1 accounts for our own SYN consuming one sequence number, per RFC 793. */
        fun ourSeq(): Long = (ourIsn + 1 + bytesSentToClient) and 0xFFFFFFFFL
        fun ourAck(): Long = (clientIsn + 1 + bytesReceivedFromClient) and 0xFFFFFFFFL
    }

    private val connections = ConcurrentHashMap<ConnKey, Connection>()

    private data class TcpHeader(
        val srcPort: Int, val dstPort: Int, val seq: Long, val ack: Long,
        val payloadOffset: Int,
        val flagSyn: Boolean, val flagAck: Boolean, val flagRst: Boolean, val flagFin: Boolean,
    )

    /**
     * Call for every IPv4/TCP packet WltVpnService's read loop sees
     * destined to port 443. Returns true if handled (tracked, relayed, or
     * used to tear down a connection); false if the caller should just
     * drop it silently (e.g. a stray packet for a connection already torn
     * down -- not an error, TCP retransmits and stragglers are normal).
     */
    fun handleTcpPacket(ipHeader: PacketIo.Ipv4Header, buf: ByteArray, tcpOffset: Int, totalLen: Int): Boolean {
        val tcp = parseTcpHeader(buf, tcpOffset, totalLen) ?: return false
        val key = ConnKey(ipHeader.srcAddr, tcp.srcPort, ipHeader.dstAddr, tcp.dstPort)

        if (tcp.flagSyn && !tcp.flagAck) {
            handleSyn(key, tcp)
            return true
        }

        val conn = connections[key] ?: return false

        if (tcp.flagRst) {
            teardown(conn, sendRst = false)
            return true
        }
        if (tcp.flagFin) {
            // Simplified teardown, not a spec-correct FIN exchange -- see
            // the file-level warning above.
            teardown(conn, sendRst = false)
            return true
        }

        val payloadLen = totalLen - tcp.payloadOffset
        if (payloadLen > 0) {
            handleClientData(conn, buf, tcp.payloadOffset, payloadLen)
        }
        return true
    }

    private fun handleSyn(key: ConnKey, tcp: TcpHeader) {
        val conn = Connection(key, clientIsn = tcp.seq)
        connections[key] = conn
        sendSegment(conn, flags = FLAG_SYN or FLAG_ACK, payload = ByteArray(0), consumesOneSeq = true)
    }

    private fun handleClientData(conn: Connection, buf: ByteArray, offset: Int, len: Int) {
        synchronized(conn.lock) { conn.bytesReceivedFromClient += len }

        when (conn.state) {
            State.SYN_RECEIVED, State.BUFFERING_CLIENT_HELLO -> {
                conn.state = State.BUFFERING_CLIENT_HELLO
                conn.clientHelloBuffer.write(buf, offset, len)

                if (conn.clientHelloBuffer.size() > MAX_CLIENTHELLO_BUFFER) {
                    // Not a normal ClientHello -- fail safe by allowing
                    // rather than holding the connection open forever.
                    Log.w(TAG, "ClientHello buffer exceeded expected size, allowing without SNI check")
                    startRelay(conn, conn.clientHelloBuffer.toByteArray())
                    return
                }

                val outcome = TlsClientHello.parseSni(conn.clientHelloBuffer.toByteArray())
                when (outcome.result) {
                    TlsClientHello.SniResult.INCOMPLETE -> {
                        sendSegment(conn, flags = FLAG_ACK, payload = ByteArray(0))
                    }
                    TlsClientHello.SniResult.FOUND -> {
                        val domain = outcome.hostname!!
                        val blocked = trieProvider().lookup(domain) == Verdict.BLOCK
                        onSniDecision(domain, blocked)
                        if (blocked) {
                            sendSegment(conn, flags = FLAG_RST, payload = ByteArray(0))
                            connections.remove(conn.key)
                        } else {
                            startRelay(conn, conn.clientHelloBuffer.toByteArray())
                        }
                    }
                    else -> {
                        // NO_SNI_EXTENSION, NOT_A_CLIENT_HELLO, MALFORMED:
                        // all fail-safe to ALLOW, per TlsClientHello's own
                        // documented contract.
                        startRelay(conn, conn.clientHelloBuffer.toByteArray())
                    }
                }
            }
            State.RELAYING -> {
                try {
                    conn.realSocket?.getOutputStream()?.write(buf, offset, len)
                    sendSegment(conn, flags = FLAG_ACK, payload = ByteArray(0))
                } catch (e: Exception) {
                    Log.w(TAG, "Relay write failed, tearing down", e)
                    teardown(conn, sendRst = true)
                }
            }
            State.CLOSING -> { /* ignore stragglers */ }
        }
    }

    private fun startRelay(conn: Connection, alreadyBufferedClientBytes: ByteArray) {
        synchronized(conn.lock) { conn.state = State.RELAYING }
        Thread({
            try {
                val socket = Socket()
                if (!vpnService.protect(socket)) {
                    Log.w(TAG, "protect() returned false for relay socket")
                }
                socket.connect(
                    InetSocketAddress(InetAddress.getByAddress(conn.key.dstAddr), conn.key.dstPort),
                    10_000,
                )
                conn.realSocket = socket
                socket.getOutputStream().write(alreadyBufferedClientBytes)

                val readBuf = ByteArray(16384)
                val input = socket.getInputStream()
                while (true) {
                    val n = input.read(readBuf)
                    if (n <= 0) break
                    sendSegment(conn, flags = FLAG_ACK or FLAG_PSH, payload = readBuf.copyOf(n))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Relay to real destination failed", e)
            } finally {
                teardown(conn, sendRst = false)
            }
        }, "wlt-relay-${conn.key.dstPort}-${conn.key.clientPort}").apply {
            isDaemon = true
            start()
        }
    }

    private fun sendSegment(conn: Connection, flags: Int, payload: ByteArray, consumesOneSeq: Boolean = false) {
        synchronized(conn.lock) {
            val segment = buildTcpSegment(
                srcIp = conn.key.dstAddr, dstIp = conn.key.clientAddr,
                srcPort = conn.key.dstPort, dstPort = conn.key.clientPort,
                seq = conn.ourSeq(), ack = conn.ourAck(),
                flags = flags, window = WINDOW_SIZE, payload = payload,
            )
            writePacket(wrapInIpv4(conn.key.dstAddr, conn.key.clientAddr, TCP_PROTOCOL, segment))
            if (payload.isNotEmpty()) conn.bytesSentToClient += payload.size
            if (consumesOneSeq) conn.bytesSentToClient += 1
        }
    }

    private fun teardown(conn: Connection, sendRst: Boolean) {
        synchronized(conn.lock) { conn.state = State.CLOSING }
        if (sendRst) sendSegment(conn, flags = FLAG_RST, payload = ByteArray(0))
        try {
            conn.realSocket?.close()
        } catch (e: Exception) {
            // already closing, nothing more to do
        }
        connections.remove(conn.key)
    }

    // --- TCP header parsing (RFC 793) ---

    private fun parseTcpHeader(buf: ByteArray, offset: Int, totalLen: Int): TcpHeader? {
        if (offset + 20 > totalLen) return null
        val srcPort = NetBytes.getUShort(buf, offset)
        val dstPort = NetBytes.getUShort(buf, offset + 2)
        val seq = NetBytes.getUInt(buf, offset + 4)
        val ack = NetBytes.getUInt(buf, offset + 8)
        val dataOffsetWords = (buf[offset + 12].asUnsignedInt() shr 4) and 0x0F
        val headerLen = dataOffsetWords * 4
        if (headerLen < 20 || offset + headerLen > totalLen) return null
        val flags = buf[offset + 13].asUnsignedInt()
        return TcpHeader(
            srcPort = srcPort, dstPort = dstPort, seq = seq, ack = ack,
            payloadOffset = offset + headerLen,
            flagSyn = (flags and FLAG_SYN) != 0,
            flagAck = (flags and FLAG_ACK) != 0,
            flagRst = (flags and FLAG_RST) != 0,
            flagFin = (flags and FLAG_FIN) != 0,
        )
    }

    /** Builds a 20-byte-header TCP segment (no options) with a correct
     * RFC 793 pseudo-header checksum. Validated standalone in
     * scripts/validate_tcp.py. */
    private fun buildTcpSegment(
        srcIp: ByteArray, dstIp: ByteArray, srcPort: Int, dstPort: Int,
        seq: Long, ack: Long, flags: Int, window: Int, payload: ByteArray,
    ): ByteArray {
        val header = ByteArray(20)
        NetBytes.putUShort(header, 0, srcPort)
        NetBytes.putUShort(header, 2, dstPort)
        NetBytes.putUInt(header, 4, seq)
        NetBytes.putUInt(header, 8, ack)
        header[12] = (5 shl 4).toByte() // data offset = 5 words (20 bytes), no options
        header[13] = flags.toByte()
        NetBytes.putUShort(header, 14, window)
        NetBytes.putUShort(header, 16, 0) // checksum placeholder
        NetBytes.putUShort(header, 18, 0) // urgent pointer
        val segment = header + payload

        val pseudoHeader = ByteArray(12)
        System.arraycopy(srcIp, 0, pseudoHeader, 0, 4)
        System.arraycopy(dstIp, 0, pseudoHeader, 4, 4)
        pseudoHeader[8] = 0
        pseudoHeader[9] = TCP_PROTOCOL.toByte()
        NetBytes.putUShort(pseudoHeader, 10, segment.size)

        val combined = pseudoHeader + segment
        val checksum = PacketIo.internetChecksum(combined, 0, combined.size)
        NetBytes.putUShort(segment, 16, checksum)
        return segment
    }

    /** Same IPv4-wrapping logic as PacketIo.buildUdpIpv4Packet, generalized
     * for an arbitrary protocol/payload instead of hardcoding UDP -- reuses
     * the same validated PacketIo.internetChecksum rather than
     * reimplementing it. */
    private fun wrapInIpv4(srcIp: ByteArray, dstIp: ByteArray, protocol: Int, payload: ByteArray): ByteArray {
        val totalLength = 20 + payload.size
        val out = ByteArray(totalLength)
        out[0] = 0x45
        out[1] = 0x00
        NetBytes.putUShort(out, 2, totalLength)
        NetBytes.putUShort(out, 4, 0)
        NetBytes.putUShort(out, 6, 0x4000)
        out[8] = 64
        out[9] = protocol.toByte()
        NetBytes.putUShort(out, 10, 0)
        System.arraycopy(srcIp, 0, out, 12, 4)
        System.arraycopy(dstIp, 0, out, 16, 4)
        NetBytes.putUShort(out, 10, PacketIo.internetChecksum(out, 0, 20))
        System.arraycopy(payload, 0, out, 20, payload.size)
        return out
    }
}
