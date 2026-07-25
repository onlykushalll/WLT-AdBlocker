package com.wlt.adblocker.vpn

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Low-level byte buffer helpers used throughout the VPN packet parsing path.
 *
 * Why this exists as its own file: Android VPN packets arrive as raw
 * [ByteArray]s containing big-endian network-ordered fields, but Kotlin's
 * [Byte] is signed, which makes every read involving `and 0xFF` a typing
 * tax. Centralizing the read/write helpers here keeps the rest of the
 * packet code readable and means the endianness assumptions are spelled
 * out in exactly one place.
 *
 * All multi-byte getters/setters here assume **big-endian** (network byte
 * order), matching RFC 791 (IPv4) and RFC 768 (UDP). Little-endian helpers
 * are intentionally omitted to avoid accidental misuse.
 */
object NetBytes {

    /** Returns the unsigned 8-bit value at [offset] as an Int in 0..255. */
    fun getUByte(buf: ByteArray, offset: Int): Int =
        buf[offset].asUnsignedInt()

    /** Returns the unsigned 16-bit big-endian value at [offset..offset+2] as an Int in 0..65535. */
    fun getUShort(buf: ByteArray, offset: Int): Int =
        ((buf[offset].asUnsignedInt() shl 8) or buf[offset + 1].asUnsignedInt())

    /** Returns the unsigned 32-bit big-endian value at [offset..offset+4] as a Long in 0..2^32-1. */
    fun getUInt(buf: ByteArray, offset: Int): Long =
        ((buf[offset].asUnsignedInt().toLong() shl 24) or
            (buf[offset + 1].asUnsignedInt().toLong() shl 16) or
            (buf[offset + 2].asUnsignedInt().toLong() shl 8) or
            buf[offset + 3].asUnsignedInt().toLong())

    /** Writes [value] as an unsigned 8-bit field at [offset]. High bits are masked off. */
    fun putUByte(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
    }

    /** Writes [value] as an unsigned 16-bit big-endian field at [offset..offset+2]. */
    fun putUShort(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = ((value ushr 8) and 0xFF).toByte()
        buf[offset + 1] = (value and 0xFF).toByte()
    }

    /** Writes [value] as an unsigned 32-bit big-endian field at [offset..offset+4]. */
    fun putUInt(buf: ByteArray, offset: Int, value: Long) {
        buf[offset] = ((value ushr 24) and 0xFF).toByte()
        buf[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        buf[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        buf[offset + 3] = (value and 0xFF).toByte()
    }

    /** Wraps a [ByteArray] in a big-endian [ByteBuffer] positioned at 0. */
    fun bigEndianBuffer(bytes: ByteArray): ByteBuffer =
        ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
}

/** Kotlin's [Byte] is signed; this extension converts to the unsigned Int value
 *  in 0..255 without the visual noise of `and 0xFF` everywhere. */
fun Byte.asUnsignedInt(): Int = this.toInt() and 0xFF
