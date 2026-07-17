package com.wlt.adblocker.vpn

import android.util.Log
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * DNS upstream resolver — supports both plain UDP and encrypted DoH (DNS-over-HTTPS, RFC 8484).
 *
 * DoH is preferred for privacy: DNS queries are sent as HTTPS POST requests to
 * Cloudflare/Google/Quad9, invisible to the network operator.
 *
 * The VPN uses DoH by default. If DoH fails (e.g., network timeout), it falls
 * back to plain UDP to avoid blocking all DNS.
 *
 * Supported upstreams:
 *   - Cloudflare: doh = https://cloudflare-dns.com/dns-query, ip = 1.1.1.1
 *   - Google:     doh = https://dns.google/dns-query,       ip = 8.8.8.8
 *   - Quad9:      doh = https://dns.quad9.net/dns-query,     ip = 9.9.9.9
 */
class DnsResolver(
    private val provider: String = "cloudflare"
) {
    companion object {
        private const val TAG = "DnsResolver"
        private const val TIMEOUT_MS = 5000

        data class Upstream(val name: String, val dohUrl: String, val udpIp: String)
        val UPSTREAMS = mapOf(
            "cloudflare" to Upstream("Cloudflare", "https://cloudflare-dns.com/dns-query", "1.1.1.1"),
            "google" to Upstream("Google", "https://dns.google/dns-query", "8.8.8.8"),
            "quad9" to Upstream("Quad9", "https://dns.quad9.net/dns-query", "9.9.9.9"),
            "adguard" to Upstream("AdGuard", "https://dns.adguard-dns.com/dns-query", "94.140.14.14"),
        )
    }

    private val upstream = UPSTREAMS[provider] ?: UPSTREAMS["cloudflare"]!!

    /**
     * Resolve a DNS query via DoH (RFC 8484 binary format).
     * Sends the raw DNS wire-format packet as HTTP POST with application/dns-message content type.
     * Returns the raw DNS response bytes.
     */
    fun resolveDoh(query: ByteArray, vpnSocket: DatagramSocket? = null): ByteArray? {
        return try {
            val url = URL(upstream.dohUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/dns-message")
            conn.setRequestProperty("Accept", "application/dns-message")
            conn.doOutput = true
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS

            conn.outputStream.use { it.write(query) }

            if (conn.responseCode == 200) {
                val body = conn.inputStream.readBytes()
                conn.disconnect()
                if (body.isNotEmpty() && body.size >= 12) {
                    body
                } else {
                    Log.w(TAG, "DoH response too short: ${body.size}")
                    null
                }
            } else {
                Log.w(TAG, "DoH HTTP ${conn.responseCode}")
                conn.disconnect()
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "DoH failed: ${e.message}")
            null
        }
    }

    /**
     * Resolve via plain UDP (fallback when DoH fails).
     * Uses protect()ed socket to avoid VPN recursion.
     */
    fun resolveUdp(query: ByteArray, vpnSocket: DatagramSocket? = null): ByteArray? {
        return try {
            val socket = vpnSocket ?: DatagramSocket()
            socket.soTimeout = TIMEOUT_MS
            val upstreamAddr = InetAddress.getByName(upstream.udpIp)
            val reqPacket = DatagramPacket(query, query.size, upstreamAddr, 53)
            socket.send(reqPacket)
            val respBuf = ByteArray(4096)
            val respPacket = DatagramPacket(respBuf, respBuf.size)
            socket.receive(respPacket)
            val resp = respPacket.data.copyOf(respPacket.length)
            if (vpnSocket == null) socket.close()
            resp
        } catch (e: Exception) {
            Log.w(TAG, "UDP DNS failed: ${e.message}")
            null
        }
    }

    /**
     * Resolve with DoH-first, UDP-fallback strategy.
     * Returns the DNS response bytes, or null if both fail.
     */
    fun resolve(query: ByteArray, vpnSocket: DatagramSocket? = null): ByteArray? {
        // Try DoH first (encrypted, privacy-preserving)
        val dohResult = resolveDoh(query, vpnSocket)
        if (dohResult != null) return dohResult

        // Fallback to plain UDP
        Log.i(TAG, "Falling back to UDP DNS")
        return resolveUdp(query, vpnSocket)
    }

    fun providerName(): String = upstream.name
}
