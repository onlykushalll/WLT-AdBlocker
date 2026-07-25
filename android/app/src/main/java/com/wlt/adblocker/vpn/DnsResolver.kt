package com.wlt.adblocker.vpn

import android.content.Context
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/**
 * DNS-over-HTTPS (RFC 8484) + UDP fallback resolver.
 *
 * 4 upstream providers, listed in preference order. The default path is
 * DoH-first (Cloudflare 1.1.1.1) for privacy + integrity, with UDP
 * fallback when DoH is unreachable (captive portal, broken network, etc.).
 *
 * Why DoH-first: plain UDP/53 is observable by the network (your ISP,
 * coffee-shop WiFi, etc. can see every domain you query) and trivially
 * spoofable on hostile networks. DoH over HTTPS to 1.1.1.1 is opaque to
 * the local network and signed by Cloudflare's cert, blocking both
 * surveillance and tampering.
 *
 * Why fall back to UDP at all: some networks (captive portals, hotels,
 * restrictive corporate WiFi) block 1.1.1.1:443 entirely. In that case
 * we'd rather resolve via UDP than fail outright — the VPN would
 * otherwise be unable to reach ANYTHING, which is a worse user experience
 * than the privacy hit of plain UDP.
 *
 * All sockets created here MUST be protected via [VpnService.protect]
 * by the caller (we can't do it here without a VpnService reference —
 * the caller wraps each UDP query in a protected socket). For DoH,
 * [HttpsURLConnection] uses the system network stack which is
 * automatically protected by VpnService's per-process routing rules.
 */
class DnsResolver(private val context: Context) {

    companion object {
        private const val TAG = "DnsResolver"
        const val DEFAULT_TIMEOUT_MS = 5_000

        /** Upstream DNS provider definition. */
        data class Upstream(
            val name: String,
            val dohUrl: String,           // null/empty → DoH not supported, UDP only
            val udpAddress: String,       // dotted-quad IP
            val udpPort: Int = 53,
        )

        val UPSTREAMS: List<Upstream> = listOf(
            Upstream("cloudflare", "https://cloudflare-dns.com/dns-query", "1.1.1.1"),
            Upstream("google", "https://dns.google/dns-query", "8.8.8.8"),
            Upstream("quad9", "https://dns.quad9.net/dns-query", "9.9.9.9"),
            Upstream("adguard", "https://dns.adguard.com/dns-query", "94.140.14.14"),
        )

        fun upstreamByName(name: String): Upstream? = UPSTREAMS.firstOrNull { it.name == name }
    }

    /**
     * Resolves a raw DNS query packet, returning the raw response packet
     * (or null on failure).
     *
     * Tries the [primary] upstream first (DoH if configured), then falls
     * back to the remaining upstreams in order, alternating DoH and UDP
     * based on what each upstream supports.
     *
     * The caller is responsible for protecting any UDP socket via
     * [VpnService.protect] BEFORE calling this — we can't do it from
     * here without a VpnService reference. Pass a non-null [socketProtector]
     * callback to enable UDP protection.
     */
    fun resolve(
        query: ByteArray,
        primary: String = "cloudflare",
        socketProtector: ((java.net.DatagramSocket) -> Boolean)? = null,
    ): ByteArray? {
        // Try primary first, then fall back through the rest in order.
        val ordered = ArrayList<Upstream>(UPSTREAMS.size)
        upstreamByName(primary)?.let { ordered.add(it) }
        for (u in UPSTREAMS) if (u !in ordered) ordered.add(u)

        for (upstream in ordered) {
            // DoH first (if supported)
            if (upstream.dohUrl.isNotEmpty()) {
                val dohResponse = tryDoh(upstream.dohUrl, query)
                if (dohResponse != null) return dohResponse
            }
            // UDP fallback (or primary if no DoH)
            val udpResponse = tryUdp(upstream.udpAddress, upstream.udpPort, query, socketProtector)
            if (udpResponse != null) return udpResponse
        }
        Log.w(TAG, "All ${ordered.size} upstreams failed for query")
        return null
    }

    /** Sends [query] via DoH (HTTPS POST, application/dns-message per RFC 8484).
     *  Returns the response body or null on failure. */
    private fun tryDoh(dohUrl: String, query: ByteArray): ByteArray? {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(dohUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = DEFAULT_TIMEOUT_MS
                readTimeout = DEFAULT_TIMEOUT_MS
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/dns-message")
                setRequestProperty("Accept", "application/dns-message")
                setRequestProperty("User-Agent", "WLT-Adblocker/1.0")
                doOutput = true
                useCaches = false
            }
            connection.outputStream.use { it.write(query) }
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "DoH $dohUrl returned HTTP $code")
                return null
            }
            return connection.inputStream.use { it.readBytes() }
        } catch (e: Exception) {
            Log.w(TAG, "DoH query to $dohUrl failed: ${e.message}")
            return null
        } finally {
            connection?.disconnect()
        }
    }

    /** Sends [query] via plain UDP DNS to [ip]:[port]. Returns the response
     *  or null on failure. The caller may pass a [socketProtector] to invoke
     *  VpnService.protect() on the socket before connecting — without it,
     *  the socket would loop back through the VPN tun (infinite recursion). */
    private fun tryUdp(
        ip: String,
        port: Int,
        query: ByteArray,
        socketProtector: ((java.net.DatagramSocket) -> Boolean)?,
    ): ByteArray? {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            // CRITICAL: protect() the socket so it bypasses our own VPN tun.
            // Without this, the DNS query would loop back into our own
            // packet loop, creating an infinite recursion.
            if (socketProtector != null) {
                val protected = socketProtector.invoke(socket)
                if (!protected) {
                    Log.w(TAG, "VpnService.protect() returned false for UDP socket to $ip:$port")
                }
            }
            socket.soTimeout = DEFAULT_TIMEOUT_MS
            val addr = InetAddress.getByName(ip)
            val outPacket = DatagramPacket(query, query.size, addr, port)
            socket.send(outPacket)
            val inBuffer = ByteArray(4096) // DNS UDP messages are limited to 512 bytes by default; EDNS extends to 4096
            val inPacket = DatagramPacket(inBuffer, inBuffer.size)
            socket.receive(inPacket)
            return inBuffer.copyOfRange(0, inPacket.length)
        } catch (e: Exception) {
            Log.w(TAG, "UDP DNS query to $ip:$port failed: ${e.message}")
            return null
        } finally {
            // CRITICAL (Task 39 fix): always close the socket in finally to
            // prevent DatagramSocket leak. Sockets left open consume file
            // descriptors and can eventually exhaust the process's FD limit.
            socket?.close()
        }
    }

    /** Tests UDP latency to [upstream] by sending a query for "google.com"
     *  and measuring round-trip time. Returns latency in millis, or -1 on failure.
     *  Used by the DnsLatencyScreen UI. */
    fun measureUdpLatency(upstream: Upstream, socketProtector: ((java.net.DatagramSocket) -> Boolean)? = null): Long {
        val start = System.currentTimeMillis()
        // Build a minimal DNS query for google.com A record
        val query = buildMinimalQuery("google.com")
        val response = tryUdp(upstream.udpAddress, upstream.udpPort, query, socketProtector)
        return if (response != null && response.size >= 12) {
            System.currentTimeMillis() - start
        } else {
            -1L
        }
    }

    /** Builds a minimal DNS query packet for [domain] (type A, class IN).
     *  Used by [measureUdpLatency] for the latency test. */
    private fun buildMinimalQuery(domain: String): ByteArray {
        val labels = domain.split('.').filter { it.isNotEmpty() }
        val questionLen = 1 + labels.sumOf { it.length + 1 } + 1 + 4 // length bytes + labels + root + QTYPE + QCLASS
        val packet = ByteArray(12 + questionLen)
        // Transaction ID (random)
        val txId = (System.currentTimeMillis() and 0xFFFF).toInt()
        NetBytes.putUShort(packet, 0, txId)
        // Flags: RD=1 (recursion desired)
        NetBytes.putUShort(packet, 2, 0x0100)
        // QDCOUNT=1
        NetBytes.putUShort(packet, 4, 1)
        // ANCOUNT, NSCOUNT, ARCOUNT all 0
        // Question section
        var pos = 12
        for (label in labels) {
            packet[pos++] = label.length.toByte()
            for (c in label) packet[pos++] = c.code.toByte()
        }
        packet[pos++] = 0 // root label
        // QTYPE = A (1)
        NetBytes.putUShort(packet, pos, 1)
        // QCLASS = IN (1)
        NetBytes.putUShort(packet, pos + 2, 1)
        return packet
    }
}
