package com.wlt.adblocker.vpn

/**
 * Parses the TLS ClientHello to extract the SNI hostname.
 * Used by ConnectionFilter (Phase 2) for SNI-based blocking.
 *
 * Streaming parser — handles ClientHellos split across TCP segments.
 */
object TlsClientHello {

    enum class SniResult { FOUND, INCOMPLETE, NO_SNI_EXTENSION, NOT_A_CLIENT_HELLO, MALFORMED }

    data class ParseOutcome(val result: SniResult, val hostname: String?)

    fun parseSni(data: ByteArray): ParseOutcome {
        if (data.size < 5) return ParseOutcome(SniResult.INCOMPLETE, null)
        if (data[0].asUnsignedInt() != 0x16) return ParseOutcome(SniResult.NOT_A_CLIENT_HELLO, null)

        val recordLen = (data[3].asUnsignedInt() shl 8) or data[4].asUnsignedInt()
        if (data.size < 5 + recordLen) return ParseOutcome(SniResult.INCOMPLETE, null)

        if (data.size < 9) return ParseOutcome(SniResult.INCOMPLETE, null)
        if (data[5].asUnsignedInt() != 0x01) return ParseOutcome(SniResult.NOT_A_CLIENT_HELLO, null)

        var off = 9 + 2 + 32 // record header(5) + handshake header(4) + version(2) + random(32)

        if (off + 1 > data.size) return ParseOutcome(SniResult.MALFORMED, null)
        val sidLen = data[off].asUnsignedInt(); off += 1 + sidLen

        if (off + 2 > data.size) return ParseOutcome(SniResult.MALFORMED, null)
        val csLen = (data[off].asUnsignedInt() shl 8) or data[off + 1].asUnsignedInt(); off += 2 + csLen

        if (off + 1 > data.size) return ParseOutcome(SniResult.MALFORMED, null)
        val cmLen = data[off].asUnsignedInt(); off += 1 + cmLen

        if (off + 2 > data.size) return ParseOutcome(SniResult.NO_SNI_EXTENSION, null)
        val extTotalLen = (data[off].asUnsignedInt() shl 8) or data[off + 1].asUnsignedInt(); off += 2
        val extEnd = off + extTotalLen
        if (extEnd > data.size) return ParseOutcome(SniResult.INCOMPLETE, null)

        while (off + 4 <= extEnd) {
            val extType = (data[off].asUnsignedInt() shl 8) or data[off + 1].asUnsignedInt()
            val extDataLen = (data[off + 2].asUnsignedInt() shl 8) or data[off + 3].asUnsignedInt()
            off += 4
            if (off + extDataLen > extEnd) return ParseOutcome(SniResult.MALFORMED, null)
            if (extType == 0x0000) return parseSniExtension(data, off, extDataLen)
            off += extDataLen
        }
        return ParseOutcome(SniResult.NO_SNI_EXTENSION, null)
    }

    private fun parseSniExtension(data: ByteArray, offset: Int, len: Int): ParseOutcome {
        if (len < 2) return ParseOutcome(SniResult.MALFORMED, null)
        val listLen = (data[offset].asUnsignedInt() shl 8) or data[offset + 1].asUnsignedInt()
        var pos = offset + 2
        val end = offset + 2 + listLen
        if (end > offset + len) return ParseOutcome(SniResult.MALFORMED, null)
        while (pos + 3 <= end) {
            val nameType = data[pos].asUnsignedInt()
            val nameLen = (data[pos + 1].asUnsignedInt() shl 8) or data[pos + 2].asUnsignedInt()
            pos += 3
            if (pos + nameLen > end) return ParseOutcome(SniResult.MALFORMED, null)
            if (nameType == 0) {
                return ParseOutcome(SniResult.FOUND, String(data, pos, nameLen, Charsets.US_ASCII).lowercase())
            }
            pos += nameLen
        }
        return ParseOutcome(SniResult.NO_SNI_EXTENSION, null)
    }
}
