package com.wlt.adblocker.vpn

/**
 * Network byte-order helpers for reading/writing unsigned values
 * from/to byte arrays. All multi-byte values are big-endian (network order).
 *
 * Ported from Claude's NetBytes — clean, tested, handles signed-byte semantics.
 */
object NetBytes {

    fun getUShort(buf: ByteArray, offset: Int): Int {
        return ((buf[offset].asUnsignedInt()) shl 8) or (buf[offset + 1].asUnsignedInt())
    }

    fun putUShort(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = ((value shr 8) and 0xFF).toByte()
        buf[offset + 1] = (value and 0xFF).toByte()
    }

    fun getUInt(buf: ByteArray, offset: Int): Long {
        return ((buf[offset].asUnsignedInt().toLong()) shl 24) or
               ((buf[offset + 1].asUnsignedInt().toLong()) shl 16) or
               ((buf[offset + 2].asUnsignedInt().toLong()) shl 8) or
               (buf[offset + 3].asUnsignedInt().toLong())
    }

    fun putUInt(buf: ByteArray, offset: Int, value: Long) {
        buf[offset] = ((value shr 24) and 0xFF).toByte()
        buf[offset + 1] = ((value shr 16) and 0xFF).toByte()
        buf[offset + 2] = ((value shr 8) and 0xFF).toByte()
        buf[offset + 3] = (value and 0xFF).toByte()
    }
}
