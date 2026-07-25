package com.wlt.adblocker.data

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Phase 13f: PCAP Exporter
 *
 * Captures network packets and exports them as .pcap files for
 * analysis in Wireshark. Uses the standard pcap format (libpcap).
 *
 * File format: Global header (24 bytes) + packet records (16-byte header + data)
 */
class PcapExporter(private val context: Context) {

    companion object {
        private const val TAG = "PcapExporter"
        private const val PCAP_MAGIC = 0xa1b2c3d4
        private const val PCAP_VERSION_MAJOR = 2
        private const val PCAP_VERSION_MINOR = 4
        private const val LINKTYPE_RAW = 101 // Raw IP
    }

    private var file: RandomAccessFile? = null
    private var packetCount = 0
    @Volatile private var capturing = false

    /** Starts capturing packets to a .pcap file. */
    fun startCapture(filename: String = "wlt-capture.pcap"): Boolean {
        try {
            val outFile = File(context.cacheDir, filename)
            file = RandomAccessFile(outFile, "rw")

            // Write global header
            val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            header.putInt(PCAP_MAGIC.toInt())   // magic
            header.putShort(PCAP_VERSION_MAJOR.toShort()) // version major
            header.putShort(PCAP_VERSION_MINOR.toShort()) // version minor
            header.putInt(0)                     // thiszone
            header.putInt(0)                     // sigfigs
            header.putInt(65535)                 // snaplen
            header.putInt(LINKTYPE_RAW)          // network (raw IP)
            file?.write(header.array())

            capturing = true
            packetCount = 0
            Log.i(TAG, "PCAP capture started: ${outFile.absolutePath}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start capture", e)
            return false
        }
    }

    /** Writes a raw IP packet to the pcap file. */
    fun writePacket(packet: ByteArray, length: Int = packet.size) {
        if (!capturing || file == null) return
        try {
            val timestamp = System.currentTimeMillis()
            val tsSec = (timestamp / 1000).toInt()
            val tsUsec = ((timestamp % 1000).toInt() * 1000)

            // Packet header (16 bytes)
            val pktHeader = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            pktHeader.putInt(tsSec)        // timestamp seconds
            pktHeader.putInt(tsUsec)       // timestamp microseconds
            pktHeader.putInt(length)       // captured length
            pktHeader.putInt(length)       // original length

            file?.write(pktHeader.array())
            file?.write(packet, 0, length)
            packetCount++
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write packet", e)
        }
    }

    /** Stops capturing and returns the file. */
    fun stopCapture(): File? {
        capturing = false
        try {
            file?.close()
            file = null
            Log.i(TAG, "PCAP capture stopped: $packetCount packets")
            return File(context.cacheDir, "wlt-capture.pcap")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop capture", e)
            return null
        }
    }

    /** Returns whether capture is active. */
    fun isCapturing(): Boolean = capturing

    /** Returns the number of packets captured. */
    fun packetCount(): Int = packetCount
}
