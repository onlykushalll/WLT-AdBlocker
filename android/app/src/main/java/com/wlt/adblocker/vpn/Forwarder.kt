package com.wlt.adblocker.vpn

import android.net.VpnService
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Passthrough forwarder for non-DNS traffic when the VPN is in full-capture mode
 * (addRoute 0.0.0.0/0). The VPN MUST capture all traffic to become the default
 * network (so the system sends DNS to our TUN), but that means ALL traffic
 * enters the TUN. This forwarder relays non-DNS UDP and TCP back to the real
 * network via protected sockets — without it, the VPN swallows all traffic and
 * breaks the internet.
 *
 * UDP: per-session (srcPort,dstIp,dstPort) DatagramSocket. A background thread
 *      reads responses and writes them back to the TUN. Sessions expire after
 *      [UDP_SESSION_TIMEOUT_MS] of inactivity.
 *
 * TCP: per-connection (clientAddr,clientPort,dstAddr,dstPort) Socket. On SYN
 *      we send SYN-ACK and connect a protected Socket to the destination. Data
 *      from the client is relayed to the Socket; data from the Socket is wrapped
 *      in TCP segments and written to the TUN. Seq/ack tracking follows RFC 793.
 *      This is a simplified stack (no retransmission timers, fixed window) —
 *      adequate for a local TUN where packet loss is near-zero.
 *
 * The TCP segment/packet construction reuses the validated checksum logic from
 * [PacketIo] (RFC 1071 internet checksum) and the wrapping pattern from
 * [ConnectionFilter].
 */
class Forwarder(
    private val vpnService: VpnService,
    /** Caller supplies this so output writes stay synchronized with the DNS
     *  path and anything else writing to the same tun output stream. */
    private val writePacket: (ByteArray) -> Unit,
) {
    companion object {
        private const val TAG = "Forwarder"

        private const val FLAG_FIN = 0x01
        private const val FLAG_SYN = 0x02
        private const val FLAG_RST = 0x04
        private const val FLAG_PSH = 0x08
        private const val FLAG_ACK = 0x10
        private const val WINDOW_SIZE = 65535
        private const val UDP_SESSION_TIMEOUT_MS = 30_000L
        private val secureRandom = SecureRandom()
    }

    // ============================================================
    // UDP forwarding
    // ============================================================

    private data class UdpSessionKey(val srcPort: Int, val dstIp: ByteArray, val dstPort: Int) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is UdpSessionKey) return false
            return srcPort == other.srcPort && dstPort == other.dstPort && dstIp.contentEquals(other.dstIp)
        }
        override fun hashCode(): Int = 31 * (31 * srcPort + dstPort) + dstIp.contentHashCode()
    }

    private inner class UdpSession(
        val key: UdpSessionKey,
        val clientIp: ByteArray,
        val socket: DatagramSocket,
    ) {
        @Volatile var lastActivity: Long = System.currentTimeMillis()
        private val responseThread: Thread

        init {
            responseThread = Thread({
                val buf = ByteArray(4096)
                while (!socket.isClosed) {
                    try {
                        val pkt = DatagramPacket(buf, buf.size)
                        socket.receive(pkt)
                        lastActivity = System.currentTimeMillis()
                        // Build the response IP+UDP packet: src = dstIp:dstPort, dst = clientIp:srcPort
                        val response = PacketIo.buildUdpIpv4Packet(
                            srcIp = key.dstIp,
                            dstIp = clientIp,
                            srcPort = key.dstPort,
                            dstPort = key.srcPort,
                            payload = pkt.data.copyOfRange(0, pkt.length),
                        )
                        writePacket(response)
                    } catch (e: Exception) {
                        if (!socket.isClosed) Log.w(TAG, "UDP response read error", e)
                        break
                    }
                }
                udpSessions.remove(key)
            }, "wlt-udp-${key.srcPort}-${key.dstPort}").apply {
                isDaemon = true
                start()
            }
        }

        fun send(payload: ByteArray) {
            lastActivity = System.currentTimeMillis()
            try {
                socket.send(DatagramPacket(payload, payload.size,
                    InetAddress.getByAddress(key.dstIp), key.dstPort))
            } catch (e: Exception) {
                Log.w(TAG, "UDP forward send failed", e)
            }
        }
    }

    private val udpSessions = ConcurrentHashMap<UdpSessionKey, UdpSession>()

    /** Forwards a non-DNS UDP packet. [buf] is the full IP packet; [ipHeader]
     *  and [udpOffset] locate the UDP header within it. */
    fun forwardUdp(ipHeader: PacketIo.Ipv4Header, buf: ByteArray, udpOffset: Int, totalLen: Int) {
        val udp = PacketIo.parseUdp(buf, udpOffset, totalLen) ?: return
        val payloadOffset = udpOffset + PacketIo.UDP_HEADER_LEN
        val payloadLen = totalLen - payloadOffset
        if (payloadLen <= 0) return
        val payload = buf.copyOfRange(payloadOffset, payloadOffset + payloadLen)

        val key = UdpSessionKey(udp.srcPort, ipHeader.dstAddr, udp.dstPort)
        val session = udpSessions[key] ?: run {
            val socket = DatagramSocket()
            // CRITICAL: protect() before using. If it fails, drop the packet —
            // without protection, the outbound UDP would loop through the TUN.
            if (!vpnService.protect(socket)) {
                Log.w(TAG, "protect() failed for UDP to ${ipHeader.dstAddr}:${udp.dstPort} — dropping")
                socket.close()
                return
            }
            socket.soTimeout = 0 // block forever (response thread)
            UdpSession(key, ipHeader.srcAddr, socket).also { udpSessions[key] = it }
        }
        session.send(payload)
    }

    // ============================================================
    // TCP forwarding (adapted from ConnectionFilter — simplified, no SNI)
    // ============================================================

    private data class TcpConnKey(
        val clientAddr: ByteArray, val clientPort: Int,
        val dstAddr: ByteArray, val dstPort: Int,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is TcpConnKey) return false
            return clientPort == other.clientPort && dstPort == other.dstPort &&
                clientAddr.contentEquals(other.clientAddr) && dstAddr.contentEquals(other.dstAddr)
        }
        override fun hashCode(): Int {
            var r = clientPort; r = 31 * r + dstPort
            r = 31 * r + clientAddr.contentHashCode(); r = 31 * r + dstAddr.contentHashCode()
            return r
        }
    }

    private class TcpConnection(val key: TcpConnKey, val clientIsn: Long) {
        val lock = Any()
        /** Serializes all writes to [realSocket]'s output stream. Prevents the
         *  connect thread's buffered-data flush from interleaving with
         *  [handleClientData]'s direct writes — interleaving would cause
         *  out-of-order delivery to the real server (buffered ClientHello
         *  arriving AFTER subsequent data), breaking TLS handshakes. */
        val socketWriteLock = Any()
        val ourIsn: Long = secureRandom.nextLong() and 0xFFFFFFFFL
        var bytesSentToClient: Long = 0
        var bytesReceivedFromClient: Long = 0
        @Volatile var closed = false
        /** @Volatile so the packet-loop thread sees the connect thread's write
         *  without needing a synchronized block on the fast path. */
        @Volatile var realSocket: Socket? = null
        /** Buffer for client data received before the real socket is connected.
         *  Flushed in handleSyn() after connect() succeeds. */
        val pendingClientData: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        fun ourSeq(): Long = (ourIsn + 1 + bytesSentToClient) and 0xFFFFFFFFL
        fun ourAck(): Long = (clientIsn + 1 + bytesReceivedFromClient) and 0xFFFFFFFFL
    }

    private val tcpConnections = ConcurrentHashMap<TcpConnKey, TcpConnection>()

    private data class TcpHeaderFull(
        val srcPort: Int, val dstPort: Int, val seq: Long, val ack: Long,
        val payloadOffset: Int, val payloadLen: Int,
        val flagSyn: Boolean, val flagAck: Boolean, val flagRst: Boolean, val flagFin: Boolean,
    )

    /** Handles a TCP packet from the TUN. Returns true if handled. */
    fun forwardTcp(ipHeader: PacketIo.Ipv4Header, buf: ByteArray, tcpOffset: Int, totalLen: Int): Boolean {
        val tcp = parseTcpHeader(buf, tcpOffset, totalLen) ?: return false
        val key = TcpConnKey(ipHeader.srcAddr, tcp.srcPort, ipHeader.dstAddr, tcp.dstPort)

        if (tcp.flagSyn && !tcp.flagAck) {
            handleSyn(key, tcp)
            return true
        }
        val conn = tcpConnections[key] ?: return false
        if (tcp.flagRst || tcp.flagFin) {
            teardown(conn, sendRst = tcp.flagRst)
            return true
        }
        if (tcp.payloadLen > 0) {
            handleClientData(conn, buf, tcp.payloadOffset, tcp.payloadLen)
        }
        return true
    }

    private fun handleSyn(key: TcpConnKey, tcp: TcpHeaderFull) {
        val conn = TcpConnection(key, clientIsn = tcp.seq)
        tcpConnections[key] = conn
        // Send SYN-ACK immediately
        sendSegment(conn, FLAG_SYN or FLAG_ACK, ByteArray(0), consumesOneSeq = true)
        // Connect to the real destination in a background thread and start relaying
        Thread({
            try {
                val socket = Socket()
                // CRITICAL: protect() the socket BEFORE connect() so it bypasses
                // our own TUN. If protect() fails, DROP the connection — without
                // protection, the outbound connect() would loop through the TUN,
                // creating infinite recursion and crashing the VPN.
                if (!vpnService.protect(socket)) {
                    Log.w(TAG, "protect() failed for TCP to ${key.dstAddr}:${key.dstPort} — dropping")
                    socket.close()
                    teardown(conn, sendRst = true)
                    return@Thread
                }
                socket.connect(InetSocketAddress(InetAddress.getByAddress(key.dstAddr), key.dstPort), 10_000)
                // CRITICAL FIX (Task 60 + B-KOTLIN-VPN-DEBUG): flush any client
                // data that was buffered while the socket was connecting.
                //
                // WITHOUT the socketWriteLock: the connect thread writes the
                // buffered data while [handleClientData] (on the packet-loop
                // thread) may simultaneously write NEW data directly to the
                // same socket.getOutputStream(). The two writes interleave,
                // so the server receives NEW data BEFORE the buffered data
                // (e.g., client cert before ClientHello) — TLS aborts.
                //
                // WITH the socketWriteLock: the connect thread holds the lock
                // during the flush. [handleClientData] acquires the same lock
                // for direct writes, so it blocks until the flush is done.
                // Order on the wire: buffered-first, then new. Correct.
                //
                // realSocket is set INSIDE conn.lock (and INSIDE socketWriteLock)
                // so that [handleClientData]'s volatile read of realSocket and
                // its subsequent acquisition of socketWriteLock form a correct
                // happens-before chain: if the packet loop sees realSocket !=
                // null, the connect thread is already holding (or has released)
                // socketWriteLock, guaranteeing the flush completed first.
                synchronized(conn.socketWriteLock) {
                    val buffered: ByteArray = synchronized(conn.lock) {
                        val data = conn.pendingClientData.toByteArray()
                        conn.pendingClientData.reset()
                        conn.realSocket = socket
                        data
                    }
                    if (buffered.isNotEmpty()) {
                        try {
                            socket.getOutputStream().write(buffered)
                            Log.d(TAG, "Flushed ${buffered.size} bytes of buffered client data to ${key.dstAddr}:${key.dstPort}")
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to flush buffered client data to ${key.dstAddr}:${key.dstPort}", e)
                        }
                    }
                }
                val readBuf = ByteArray(16384)
                val input = socket.getInputStream()
                while (!conn.closed) {
                    val n = input.read(readBuf)
                    if (n <= 0) break
                    sendSegment(conn, FLAG_ACK or FLAG_PSH, readBuf.copyOf(n))
                }
            } catch (e: Exception) {
                if (!conn.closed) Log.w(TAG, "TCP relay to ${key.dstAddr}:${key.dstPort} failed", e)
            } finally {
                teardown(conn, sendRst = false)
            }
        }, "wlt-tcp-${key.dstPort}-${key.clientPort}").apply {
            isDaemon = true
            start()
        }
    }

    private fun handleClientData(conn: TcpConnection, buf: ByteArray, offset: Int, len: Int) {
        // Update the running receive counter first, so ourAck() always reflects
        // all data we've taken responsibility for (whether forwarded directly
        // or buffered for the connect thread to flush). The ACK number must
        // advance by [len] regardless of which path we take below — otherwise
        // the relay loop's PSH segments would carry a stale ACK and the client
        // would needlessly retransmit.
        synchronized(conn.lock) { conn.bytesReceivedFromClient += len }

        // @Volatile read — fast path, no lock needed for the visibility check.
        val socket = conn.realSocket
        if (socket != null && socket.isConnected && !socket.isClosed) {
            // Socket is connected — write directly. Serialized via
            // [conn.socketWriteLock] to prevent interleaving with the connect
            // thread's buffered-data flush (see handleSyn for the full rationale).
            try {
                synchronized(conn.socketWriteLock) {
                    // Re-check isClosed inside the lock — the connection may
                    // have been torn down between the volatile read and here.
                    if (!socket.isClosed) {
                        socket.getOutputStream().write(buf, offset, len)
                    } else {
                        // Socket closed under us — buffer as fallback so the
                        // data isn't lost (will be drained if the connect
                        // thread is still alive; otherwise harmlessly stranded
                        // since the connection is being torn down).
                        synchronized(conn.lock) {
                            conn.pendingClientData.write(buf, offset, len)
                        }
                        return
                    }
                }
                sendSegment(conn, FLAG_ACK, ByteArray(0))
            } catch (e: Exception) {
                Log.w(TAG, "handleClientData: direct write failed, buffering", e)
                synchronized(conn.lock) {
                    conn.pendingClientData.write(buf, offset, len)
                }
                // Don't ACK — the client will retransmit if needed.
                // bytesReceivedFromClient was already incremented above, so
                // future ACKs will acknowledge this data once it's flushed.
            }
        } else {
            // Socket not connected yet (SYN in flight). Buffer the data so it
            // can be flushed once the connection is established.
            // CRITICAL FIX (Task 60): previously we ACKed the data without
            // forwarding it, causing silent data loss. Now we buffer it.
            synchronized(conn.lock) {
                conn.pendingClientData.write(buf, offset, len)
            }
            // Don't ACK yet — the client will retransmit if needed, and
            // we'll forward the buffered data once connected. (The ACK number
            // already accounts for this data via the increment above, so when
            // a subsequent packet triggers an ACK, it will cover this data too.)
        }
    }

    private fun sendSegment(conn: TcpConnection, flags: Int, payload: ByteArray, consumesOneSeq: Boolean = false) {
        synchronized(conn.lock) {
            if (conn.closed) return
            val segment = buildTcpSegment(
                srcIp = conn.key.dstAddr, dstIp = conn.key.clientAddr,
                srcPort = conn.key.dstPort, dstPort = conn.key.clientPort,
                seq = conn.ourSeq(), ack = conn.ourAck(),
                flags = flags, window = WINDOW_SIZE, payload = payload,
            )
            writePacket(wrapInIpv4(conn.key.dstAddr, conn.key.clientAddr, PacketIo.IP_PROTO_TCP, segment))
            if (payload.isNotEmpty()) conn.bytesSentToClient += payload.size
            if (consumesOneSeq) conn.bytesSentToClient += 1
        }
    }

    private fun teardown(conn: TcpConnection, sendRst: Boolean) {
        synchronized(conn.lock) { conn.closed = true }
        if (sendRst) {
            try { sendSegment(conn, FLAG_RST, ByteArray(0)) } catch (_: Exception) {}
        }
        try { conn.realSocket?.close() } catch (_: Exception) {}
        // Only remove if this is still the current connection for this key.
        // If a new SYN replaced this connection (the client reused the same
        // 4-tuple after we tore it down), we must NOT remove the new one —
        // doing so would orphan the new connection (its relay thread would
        // eventually teardown and remove nothing, but the connection would
        // be unreachable for new data). ConcurrentHashMap.remove(key, value)
        // uses equals() — TcpConnection is a plain class so equals is
        // reference equality, which is exactly what we want here.
        tcpConnections.remove(conn.key, conn)
    }

    private fun parseTcpHeader(buf: ByteArray, offset: Int, totalLen: Int): TcpHeaderFull? {
        if (offset + 20 > totalLen) return null
        val srcPort = NetBytes.getUShort(buf, offset)
        val dstPort = NetBytes.getUShort(buf, offset + 2)
        val seq = NetBytes.getUInt(buf, offset + 4)
        val ack = NetBytes.getUInt(buf, offset + 8)
        val dataOffsetWords = (buf[offset + 12].asUnsignedInt() shr 4) and 0x0F
        val headerLen = dataOffsetWords * 4
        if (headerLen < 20 || offset + headerLen > totalLen) return null
        val flags = buf[offset + 13].asUnsignedInt()
        val payloadOffset = offset + headerLen
        val payloadLen = totalLen - payloadOffset
        return TcpHeaderFull(
            srcPort = srcPort, dstPort = dstPort, seq = seq, ack = ack,
            payloadOffset = payloadOffset, payloadLen = if (payloadLen > 0) payloadLen else 0,
            flagSyn = (flags and FLAG_SYN) != 0,
            flagAck = (flags and FLAG_ACK) != 0,
            flagRst = (flags and FLAG_RST) != 0,
            flagFin = (flags and FLAG_FIN) != 0,
        )
    }

    private fun buildTcpSegment(
        srcIp: ByteArray, dstIp: ByteArray, srcPort: Int, dstPort: Int,
        seq: Long, ack: Long, flags: Int, window: Int, payload: ByteArray,
    ): ByteArray {
        val header = ByteArray(20)
        NetBytes.putUShort(header, 0, srcPort)
        NetBytes.putUShort(header, 2, dstPort)
        NetBytes.putUInt(header, 4, seq)
        NetBytes.putUInt(header, 8, ack)
        header[12] = (5 shl 4).toByte()
        header[13] = flags.toByte()
        NetBytes.putUShort(header, 14, window)
        NetBytes.putUShort(header, 16, 0)
        NetBytes.putUShort(header, 18, 0)
        val segment = header + payload
        val pseudo = ByteArray(12)
        System.arraycopy(srcIp, 0, pseudo, 0, 4)
        System.arraycopy(dstIp, 0, pseudo, 4, 4)
        pseudo[8] = 0
        pseudo[9] = PacketIo.IP_PROTO_TCP.toByte()
        NetBytes.putUShort(pseudo, 10, segment.size)
        val combined = pseudo + segment
        val checksum = PacketIo.internetChecksum(combined, 0, combined.size)
        NetBytes.putUShort(segment, 16, checksum)
        return segment
    }

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

    /** Cleans up expired UDP sessions. Call periodically from the stats job. */
    fun cleanupExpiredSessions() {
        val now = System.currentTimeMillis()
        val expired = udpSessions.values.filter { now - it.lastActivity > UDP_SESSION_TIMEOUT_MS }
        for (s in expired) {
            try { s.socket.close() } catch (_: Exception) {}
            udpSessions.remove(s.key)
        }
        if (expired.isNotEmpty()) {
            Log.d(TAG, "Cleaned up ${expired.size} expired UDP sessions (${udpSessions.size} active)")
        }
    }

    /** Returns the number of active TCP connections. For the DiagnosticScreen. */
    fun getActiveTcpCount(): Int = tcpConnections.size

    /** Returns the number of active UDP sessions. For the DiagnosticScreen. */
    fun getActiveUdpCount(): Int = udpSessions.size
}
