package com.wlt.adblocker.vpn

import android.util.Log

/**
 * Minimal RFC 1035 DNS message parser and response builder.
 *
 * Scope is deliberately narrow: WLT only needs to (a) extract the QNAME
 * from the question section of a query, (b) extract CNAME targets from
 * the answer section of upstream responses (for cloaking detection), and
 * (c) build three kinds of synthetic responses (NXDOMAIN, 0.0.0.0 null IP,
 * REFUSED) that echo the query's transaction ID and question so the
 * client can match them up.
 *
 * This is NOT a full DNS library. Anything beyond that (TXT, SRV, NAPTR,
 * DNSSEC, EDNS options) is treated as "skip" — we either pass through to
 * the upstream response or fail closed.
 *
 * Compression pointers (RFC 1035 §4.1.4, the 0xC00C pattern) are handled
 * in [readName] to avoid infinite loops when reading upstream responses.
 */
object DnsPacketParser {

    private const val TAG = "DnsPacketParser"

    // DNS opcodes
    const val OPCODE_QUERY = 0

    // DNS response codes (RFC 1035 §4.1.1, RFC 2136 §3.1.1)
    const val RCODE_NOERROR = 0
    const val RCODE_FORMERR = 1
    const val RCODE_SERVFAIL = 2
    const val RCODE_NXDOMAIN = 3
    const val RCODE_NOTIMP = 4
    const val RCODE_REFUSED = 5

    // DNS RR types we care about
    private const val TYPE_A = 1
    private const val TYPE_CNAME = 5
    private const val TYPE_AAAA = 28

    private const val CLASS_IN = 1

    // QR bit (query/response) is bit 15 of flags
    private const val FLAG_QR = 0x8000
    // AA (authoritative answer) bit 10
    private const val FLAG_AA = 0x0400
    // RD (recursion desired) bit 8
    private const val FLAG_RD = 0x0100
    // RA (recursion available) bit 7
    private const val FLAG_RA = 0x0080

    /** Reads a domain name starting at [offset], following compression pointers.
     *  Returns the decoded dotted name (without trailing dot) and the offset
     *  of the first byte AFTER the name when called in the question section
     *  (where callers need to advance past it). Returns null on malformed input. */
    private fun readName(packet: ByteArray, offset: Int): Pair<String, Int>? {
        val labels = ArrayList<String>()
        var pos = offset
        var afterPtr = -1
        var jumps = 0
        while (true) {
            if (pos >= packet.size) return null
            val len = packet[pos].asUnsignedInt()
            if (len == 0) {
                pos++
                if (afterPtr == -1) afterPtr = pos
                break
            }
            // Compression pointer: top two bits set (0xC0)
            if ((len and 0xC0) == 0xC0) {
                if (pos + 1 >= packet.size) return null
                val ptr = ((len and 0x3F) shl 8) or packet[pos + 1].asUnsignedInt()
                if (afterPtr == -1) afterPtr = pos + 2
                if (ptr >= packet.size) return null
                // Anti-loop guard
                if (++jumps > 128) return null
                pos = ptr
                continue
            }
            // Regular label
            if (pos + 1 + len > packet.size) return null
            if (len > 63) return null // label too long
            val label = String(packet, pos + 1, len, Charsets.US_ASCII)
            labels.add(label)
            pos += 1 + len
        }
        val name = if (labels.isEmpty()) "." else labels.joinToString(".")
        return name to afterPtr
    }

    /** Extracts the QNAME (lowercased) from the question section of a DNS query packet.
     *  Returns null if the packet is too short or malformed. */
    fun extractQueryName(packet: ByteArray): String? {
        if (packet.size < 12) return null
        val qdCount = NetBytes.getUShort(packet, 4)
        if (qdCount == 0) return null
        val (name, _) = readName(packet, 12) ?: return null
        return name.lowercase()
    }

    /** Extracts the QNAME and question TYPE from a query packet. Returns null on malformed input. */
    fun extractQuery(packet: ByteArray): Pair<String, Int>? {
        if (packet.size < 12) return null
        val qdCount = NetBytes.getUShort(packet, 4)
        if (qdCount == 0) return null
        val (name, afterName) = readName(packet, 12) ?: return null
        if (afterName + 4 > packet.size) return null
        val qType = NetBytes.getUShort(packet, afterName)
        return name.lowercase() to qType
    }

    /** Extracts CNAME targets from the answer section of an upstream DNS response.
     *  Used to detect CNAME cloaking (where a benign-looking domain points
     *  to a tracker domain in the answer section). Returns an empty list if
     *  there are no CNAME records or the packet is malformed. */
    fun extractCNAMETargets(packet: ByteArray): List<String> {
        if (packet.size < 12) return emptyList()
        val anCount = NetBytes.getUShort(packet, 6)
        if (anCount == 0) return emptyList()
        // Skip the question section
        val qdCount = NetBytes.getUShort(packet, 4)
        var pos = 12
        repeat(qdCount) {
            val r = readName(packet, pos) ?: return emptyList()
            pos = r.second + 4 // skip QTYPE + QCLASS
            if (pos > packet.size) return emptyList()
        }
        val targets = ArrayList<String>()
        repeat(anCount) {
            val nameResult = readName(packet, pos) ?: return targets
            pos = nameResult.second
            if (pos + 10 > packet.size) return targets
            val rrType = NetBytes.getUShort(packet, pos)
            val rdLen = NetBytes.getUShort(packet, pos + 8)
            pos += 10
            if (pos + rdLen > packet.size) return targets
            if (rrType == TYPE_CNAME) {
                val cname = readName(packet, pos)
                if (cname != null) {
                    targets.add(cname.first.lowercase())
                }
            }
            pos += rdLen
        }
        return targets
    }

    /** Returns true if the packet looks like a DNS query (QR bit clear, opcode 0,
     *  at least one question). Used by the VPN loop to filter out garbage. */
    fun isQuery(packet: ByteArray): Boolean {
        if (packet.size < 12) return false
        val flags = NetBytes.getUShort(packet, 2)
        val qr = (flags and FLAG_QR) != 0
        if (qr) return false
        val opcode = (flags shr 11) and 0x0F
        if (opcode != OPCODE_QUERY) return false
        val qdCount = NetBytes.getUShort(packet, 4)
        return qdCount >= 1
    }

    /** Returns the transaction ID (first 2 bytes) of a DNS packet. */
    fun transactionId(packet: ByteArray): Int? =
        if (packet.size < 2) null else NetBytes.getUShort(packet, 0)

    /** Builds an NXDOMAIN response for the given query packet, echoing the
     *  transaction ID and question section. The response has:
     *  - QR=1, AA=0, RD copied from query, RA=1
     *  - RCODE=3 (NXDOMAIN)
     *  - The original question section (so the client can match)
     *  - No answer records */
    fun buildNXDOMAIN(query: ByteArray): ByteArray {
        return buildResponse(query, rcode = RCODE_NXDOMAIN, answerBytes = null, answerType = 0)
    }

    /** Builds a REFUSED response for the given query packet. */
    fun buildREFUSED(query: ByteArray): ByteArray {
        return buildResponse(query, rcode = RCODE_REFUSED, answerBytes = null, answerType = 0)
    }

    /** Builds a NOERROR response with a single A record pointing to 0.0.0.0
     *  (the "null IP" sinkhole). Uses a compression pointer (0xC00C) for the
     *  answer name to point back at the question name — the standard pattern
     *  that real DNS servers use, and what real DNS clients expect. */
    fun buildNullIP(query: ByteArray): ByteArray {
        // A record: name=ptr-to-0xC00C, TYPE=A, CLASS=IN, TTL=60, RDLENGTH=4, RDATA=0.0.0.0
        val answer = ByteArray(16)
        // 0xC00C: compression pointer back to offset 12 (the question name)
        NetBytes.putUShort(answer, 0, 0xC00C)
        NetBytes.putUShort(answer, 2, TYPE_A)
        NetBytes.putUShort(answer, 4, CLASS_IN)
        NetBytes.putUInt(answer, 6, 60L)        // TTL
        NetBytes.putUShort(answer, 10, 4)       // RDLENGTH
        // RDATA = 0.0.0.0 (4 zero bytes)
        answer[12] = 0; answer[13] = 0; answer[14] = 0; answer[15] = 0
        return buildResponse(query, rcode = RCODE_NOERROR, answerBytes = answer, answerType = TYPE_A)
    }

    /** Internal response builder. [answerBytes] is the raw answer section
     *  (already-formatted RRs) or null for "no answers" responses like
     *  NXDOMAIN and REFUSED. [answerType] is the RR type of the first
     *  answer (used only for sanity logging). */
    private fun buildResponse(query: ByteArray, rcode: Int, answerBytes: ByteArray?, answerType: Int): ByteArray {
        if (query.size < 12) {
            Log.w(TAG, "Cannot build response: query too short (${query.size} bytes)")
            return ByteArray(12) // empty DNS response — best effort
        }
        // Find the end of the question section so we can echo it back
        val (qname, afterQname) = readName(query, 12) ?: Pair(".", 12)
        // QTYPE (2) + QCLASS (2) follow the name
        val qSectionEnd = afterQname + 4
        if (qSectionEnd > query.size) {
            Log.w(TAG, "Question section truncated in query, building minimal response")
        }
        val questionBytes = query.copyOfRange(12, minOf(qSectionEnd, query.size))

        val txId = NetBytes.getUShort(query, 0)
        val queryFlags = NetBytes.getUShort(query, 2)
        val rd = (queryFlags and FLAG_RD) != 0

        // Build response header (12 bytes) + question section + (optional) answer
        val answerLen = answerBytes?.size ?: 0
        val response = ByteArray(12 + questionBytes.size + answerLen)

        // Transaction ID — echo
        NetBytes.putUShort(response, 0, txId)
        // Flags: QR=1, OPCODE=0 (copy from query), AA=0, TC=0, RD=copy, RA=1, Z=0, RCODE=rcode
        val opcodeFromQuery = (queryFlags shr 11) and 0x0F
        var flags = FLAG_QR or FLAG_RA
        if (rd) flags = flags or FLAG_RD
        flags = flags or (opcodeFromQuery shl 11)
        flags = flags or (rcode and 0x0F)
        NetBytes.putUShort(response, 2, flags)
        // QDCOUNT = 1
        NetBytes.putUShort(response, 4, 1)
        // ANCOUNT = 1 if answer present, else 0
        NetBytes.putUShort(response, 6, if (answerBytes != null) 1 else 0)
        // NSCOUNT = 0, ARCOUNT = 0
        NetBytes.putUShort(response, 8, 0)
        NetBytes.putUShort(response, 10, 0)

        // Question section
        System.arraycopy(questionBytes, 0, response, 12, questionBytes.size)
        // Answer section (if any)
        if (answerBytes != null && answerLen > 0) {
            System.arraycopy(answerBytes, 0, response, 12 + questionBytes.size, answerLen)
        }
        return response
    }
}
