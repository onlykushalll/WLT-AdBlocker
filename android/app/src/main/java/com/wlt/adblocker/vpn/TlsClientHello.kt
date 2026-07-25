package com.wlt.adblocker.vpn

import android.util.Log

/**
 * Streaming TLS ClientHello SNI extractor.
 *
 * Why "streaming": the VPN tun delivers TCP segments, which may split a
 * single TLS ClientHello across multiple packets (the typical ClientHello
 * is 200-500 bytes, well under the 1460-byte MSS, but with TLS 1.3 +
 * ECH + second-chance grease + session tickets it can grow). The parser
 * accepts incremental bytes via [parseSni] and returns one of three
 * results:
 *
 *  - [SniResult.INCOMPLETE] — need more bytes, call again with the
 *    accumulated buffer
 *  - [SniResult.FOUND] — SNI extracted successfully, hostname is non-null
 *  - [SniResult.NOT_A_CLIENT_HELLO] — first byte isn't 0x16 (Handshake)
 *    or first handshake byte isn't 0x01 (ClientHello). Callers should
 *    fail-safe to ALLOW (let the connection through) since this might
 *    be a TLS-record-segmented or post-ClientHello record.
 *  - [SniResult.NO_SNI_EXTENSION] — valid ClientHello, but no SNI
 *    extension present. Callers should fail-safe to ALLOW.
 *  - [SniResult.MALFORMED] — ClientHello present but structurally
 *    invalid. Fail-safe to ALLOW.
 *
 * Reference: RFC 5246 (TLS 1.2), RFC 8446 (TLS 1.3), RFC 6066 (SNI ext).
 *
 * NOT a full TLS parser: we read only enough bytes to extract SNI. Any
 * extension we don't recognize is skipped via its declared length. We
 * never validate the cryptographic content — just structural fields.
 */
object TlsClientHello {

    private const val TAG = "TlsClientHello"

    /** Maximum ClientHello size we'll attempt to parse. 16KB TLS record +
     *  5-byte header = 16389 bytes. Anything larger is malformed. */
    const val MAX_TLS_RECORD_LENGTH = 16 * 1024 + 5

    enum class SniResult {
        INCOMPLETE,
        FOUND,
        NOT_A_CLIENT_HELLO,
        NO_SNI_EXTENSION,
        MALFORMED,
    }

    /** Result of a [parseSni] call. */
    data class SniOutcome(
        val result: SniResult,
        val hostname: String?,
    )

    /**
     * Parses [buffer] (a possibly-accumulated sequence of bytes from one
     * or more TCP segments) and returns an [SniOutcome].
     *
     * Pure function: no internal state. Callers are responsible for
     * accumulating bytes from multiple segments and re-calling this
     * method with the full accumulated buffer.
     *
     * Returns:
     *  - [SniOutcome] with result INCOMPLETE and null hostname if more
     *    bytes are needed
     *  - [SniOutcome] with result FOUND and the lowercased hostname if
     *    the SNI extension was successfully extracted
     *  - [SniOutcome] with result NOT_A_CLIENT_HELLO/NO_SNI_EXTENSION/MALFORMED
     *    and null hostname in those cases — callers should fail-safe to ALLOW
     */
    fun parseSni(buffer: ByteArray): SniOutcome {
        if (buffer.size < 5) return SniOutcome(SniResult.INCOMPLETE, null)

        // --- TLS record header (5 bytes) ---
        // byte 0: ContentType — must be 0x16 (Handshake)
        if (buffer[0].asUnsignedInt() != 0x16) {
            return SniOutcome(SniResult.NOT_A_CLIENT_HELLO, null)
        }
        // bytes 1-2: TLS version (typically 0x0301 for compatibility)
        // bytes 3-4: record length
        val recordLen = NetBytes.getUShort(buffer, 3)
        // Total bytes we expect: 5-byte header + record body
        if (5 + recordLen > buffer.size) {
            // Don't have the whole record yet
            if (buffer.size < MAX_TLS_RECORD_LENGTH) {
                return SniOutcome(SniResult.INCOMPLETE, null)
            }
            return SniOutcome(SniResult.MALFORMED, null)
        }

        // --- Handshake header (4 bytes) ---
        // byte 5: HandshakeType — must be 0x01 (ClientHello)
        var pos = 5
        if (buffer[pos].asUnsignedInt() != 0x01) {
            return SniOutcome(SniResult.NOT_A_CLIENT_HELLO, null)
        }
        // bytes 6-8: Handshake length (24-bit big-endian)
        val handshakeLen =
            ((buffer[pos + 1].asUnsignedInt() shl 16) or
                (buffer[pos + 2].asUnsignedInt() shl 8) or
                buffer[pos + 3].asUnsignedInt())
        pos += 4

        // Check we have enough bytes for the declared handshake body
        if (pos + handshakeLen > buffer.size) {
            return SniOutcome(SniResult.INCOMPLETE, null)
        }

        // --- ClientHello body ---
        // bytes: legacy_version (2) + random (32) + session_id (1 + len) +
        //        cipher_suites (2 + len) + compression_methods (1 + len) +
        //        extensions (2 + len)
        try {
            // legacy_version (skip 2)
            pos += 2
            // random (skip 32)
            pos += 32
            // session_id
            if (pos >= buffer.size) return SniOutcome(SniResult.MALFORMED, null)
            val sessionIdLen = buffer[pos].asUnsignedInt()
            pos += 1 + sessionIdLen
            // cipher_suites
            if (pos + 2 > buffer.size) return SniOutcome(SniResult.MALFORMED, null)
            val cipherSuitesLen = NetBytes.getUShort(buffer, pos)
            pos += 2 + cipherSuitesLen
            // compression_methods
            if (pos >= buffer.size) return SniOutcome(SniResult.MALFORMED, null)
            val compMethodsLen = buffer[pos].asUnsignedInt()
            pos += 1 + compMethodsLen
            // extensions
            if (pos + 2 > buffer.size) {
                // No extensions present — ClientHello without SNI. Legal but
                // rare for modern clients.
                return SniOutcome(SniResult.NO_SNI_EXTENSION, null)
            }
            val extensionsTotalLen = NetBytes.getUShort(buffer, pos)
            pos += 2
            val extensionsEnd = pos + extensionsTotalLen
            if (extensionsEnd > buffer.size) {
                return SniOutcome(SniResult.MALFORMED, null)
            }

            // Walk extensions looking for SNI (type 0x0000)
            while (pos + 4 <= extensionsEnd) {
                val extType = NetBytes.getUShort(buffer, pos)
                val extLen = NetBytes.getUShort(buffer, pos + 2)
                pos += 4
                if (pos + extLen > extensionsEnd) {
                    return SniOutcome(SniResult.MALFORMED, null)
                }
                if (extType == 0x0000) {
                    // SNI extension found. Parse it.
                    val hostname = parseSniExtension(buffer, pos, extLen)
                    return if (hostname != null) {
                        SniOutcome(SniResult.FOUND, hostname.lowercase())
                    } else {
                        SniOutcome(SniResult.NO_SNI_EXTENSION, null)
                    }
                }
                pos += extLen
            }
            // Walked all extensions, no SNI found.
            return SniOutcome(SniResult.NO_SNI_EXTENSION, null)
        } catch (e: Exception) {
            Log.w(TAG, "ClientHello parse error", e)
            return SniOutcome(SniResult.MALFORMED, null)
        }
    }

    /** Parses the SNI extension body (server_name_list). Returns the first
     *  host_name entry (type 0x00), or null on malformed input. */
    private fun parseSniExtension(buf: ByteArray, offset: Int, length: Int): String? {
        if (length < 2) return null
        val listLen = NetBytes.getUShort(buf, offset)
        if (2 + listLen > length) return null
        var pos = offset + 2
        val end = offset + 2 + listLen
        while (pos + 5 <= end) {
            val nameType = buf[pos].asUnsignedInt()
            val nameLen = NetBytes.getUShort(buf, pos + 1)
            pos += 3 + nameLen
            if (pos > end) return null
            if (nameType == 0x00) {
                // host_name — ASCII string
                val nameStart = pos - nameLen
                return String(buf, nameStart, nameLen, Charsets.US_ASCII)
            }
        }
        return null
    }
}
