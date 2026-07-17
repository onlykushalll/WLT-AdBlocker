package com.wlt.adblocker.vpn

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.wlt.adblocker.R
import com.wlt.adblocker.data.QueryLog
import com.wlt.adblocker.data.QueryLogEntry
import com.wlt.adblocker.data.RuleStore
import com.wlt.adblocker.data.StatsHistory
import com.wlt.adblocker.data.WltDataStore
import com.wlt.adblocker.ui.MainActivity
import com.wlt.adblocker.util.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.nio.ByteBuffer

/**
 * WLT VPN Service — the heart of the Android app.
 * Establishes a TUN interface, intercepts all DNS traffic, routes each
 * query through the WLT block engine. Non-DNS traffic passes through.
 * Applies per-app firewall bypass via addDisallowedApplication.
 */
class WltVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var vpnInterface: ParcelFileDescriptor? = null
    private var packetLoopJob: Job? = null
    private var statsJob: Job? = null
    @Volatile private var isRunning = false
    @Volatile var pausedUntil: Long = 0L // epoch millis; 0 = not paused

    private val kotlinFallbackEngine = KotlinBlockEngine()
    private val dnsResolver = DnsResolver("cloudflare")

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand")
        when (intent?.action) {
            ACTION_PAUSE -> {
                val minutes = intent.getIntExtra(EXTRA_PAUSE_MINUTES, 5)
                pausedUntil = System.currentTimeMillis() + minutes * 60_000L
                updateNotification()
                Log.i(TAG, "Paused for $minutes minutes until $pausedUntil")
                return START_STICKY
            }
            ACTION_RESUME -> {
                pausedUntil = 0L
                updateNotification()
                Log.i(TAG, "Resumed protection")
                return START_STICKY
            }
        }
        startForeground(NotificationHelper.NOTIF_VPN_ID, buildNotification("WLT active — protecting"))
        startVpn()
        return START_STICKY
    }

    private fun updateNotification() {
        val notif = if (pausedUntil > System.currentTimeMillis()) {
            val remaining = ((pausedUntil - System.currentTimeMillis()) / 60000).coerceAtLeast(1)
            buildNotification("Paused — resumes in ${remaining}m")
        } else {
            buildNotification("WLT active — protecting")
        }
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(NotificationHelper.NOTIF_VPN_ID, notif)
    }

    private fun isPaused(): Boolean = pausedUntil > System.currentTimeMillis()

    private fun startVpn() {
        if (isRunning) { Log.w(TAG, "VPN already running"); return }
        try {
            val builder = Builder()
                .setSession(getString(R.string.app_name))
                .addAddress("10.111.222.1", 24)
                .addDnsServer("10.111.222.1")
                .addRoute("10.111.222.1", 32)
                .addRoute("8.8.8.8", 32)
                .addRoute("8.8.4.4", 32)
                .addRoute("1.1.1.1", 32)
                .addRoute("1.0.0.1", 32)
                .setMtu(1500)
                .setBlocking(true)

            // Apply per-app firewall: bypass VPN for selected apps
            val bypassApps = RuleStore.getBypassApps()
            for (pkg in bypassApps) {
                try {
                    builder.addDisallowedApplication(pkg)
                    Log.i(TAG, "App bypass: $pkg")
                } catch (e: Exception) {
                    Log.w(TAG, "Cannot bypass $pkg: ${e.message}")
                }
            }

            vpnInterface = builder.establish()
            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN")
                stopSelf()
                return
            }
            isRunning = true
            Log.i(TAG, "VPN established, starting packet loop")

            kotlinFallbackEngine.loadBundledLists(this)

            packetLoopJob = scope.launch { packetLoop(vpnInterface!!) }
            // Stats history recorder — snapshots every 60s for the dashboard chart
            statsJob = scope.launch { statsRecorder() }
        } catch (e: Exception) {
            Log.e(TAG, "startVpn failed", e)
            stopSelf()
        }
    }

    private suspend fun statsRecorder() {
        while (isRunning) {
            StatsHistory.record(BlockStats.totalBlocked(), BlockStats.totalAllowed())
            kotlinx.coroutines.delay(60_000) // 1 minute
        }
    }

    private suspend fun packetLoop(tun: ParcelFileDescriptor) {
        val input = FileInputStream(tun.fileDescriptor)
        val output = FileOutputStream(tun.fileDescriptor)
        val buffer = ByteBuffer.allocate(32767)

        Log.i(TAG, "Packet loop started")
        try {
            while (isRunning) {
                val length = input.read(buffer.array())
                if (length <= 0) continue

                val packet = buffer.array()
                val version = (packet[0].toInt() shr 4) and 0x0F
                if (version != 4) continue

                val ihl = (packet[0].toInt() and 0x0F) * 4
                if (ihl < 20 || length < ihl + 8) continue

                val protocol = packet[9].toInt() and 0xFF
                if (protocol != 17) continue

                val srcPort = ((packet[ihl].toInt() and 0xFF) shl 8) or (packet[ihl + 1].toInt() and 0xFF)
                val dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)
                if (srcPort != 53 && dstPort != 53) continue

                val dnsOffset = ihl + 8
                val dnsLength = length - dnsOffset
                if (dnsLength < 12) continue

                handleDnsQuery(packet, dnsOffset, dnsLength, ihl, length, output)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Packet loop error", e)
        } finally {
            Log.i(TAG, "Packet loop ended")
        }
    }

    private fun handleDnsQuery(
        packet: ByteArray, dnsOffset: Int, dnsLength: Int,
        ipHeaderLen: Int, packetLen: Int,
        output: FileOutputStream
    ) {
        val dnsPayload = ByteArray(dnsLength)
        System.arraycopy(packet, dnsOffset, dnsPayload, 0, dnsLength)

        val domain = DnsPacketParser.extractQueryDomain(dnsPayload, dnsLength)
        if (domain == null) {
            forwardUpstream(dnsPayload, packet, ipHeaderLen, packetLen, output)
            return
        }

        // If paused, forward everything without blocking
        if (isPaused()) {
            forwardUpstream(dnsPayload, packet, ipHeaderLen, packetLen, output)
            return
        }

        val shouldBlock = kotlinFallbackEngine.shouldBlock(domain)
        val sdk = kotlinFallbackEngine.detectSdk(domain)
        BlockStats.onQuery(domain, shouldBlock)
        QueryLog.add(
            QueryLogEntry(
                domain = domain,
                timestamp = System.currentTimeMillis(),
                blocked = shouldBlock,
                reason = if (shouldBlock) kotlinFallbackEngine.lastBlockReason else "allowed",
                sdk = sdk
            )
        )

        if (shouldBlock) {
            val dnsResp = DnsPacketParser.buildNxDomain(dnsPayload, dnsLength)
            val ipResp = buildIpUdpResponse(packet, ipHeaderLen, dnsResp)
            if (ipResp.isNotEmpty()) {
                try { output.write(ipResp) } catch (e: Exception) { Log.w(TAG, "write blocked resp failed", e) }
            }
        } else {
            forwardUpstream(dnsPayload, packet, ipHeaderLen, packetLen, output)
        }
    }

    private fun forwardUpstream(
        dnsPayload: ByteArray, packet: ByteArray,
        ipHeaderLen: Int, packetLen: Int,
        output: FileOutputStream
    ) {
        try {
            val socket = DatagramSocket()
            protect(socket)
            val respBytes = dnsResolver.resolve(dnsPayload, socket)
            socket.close()
            if (respBytes == null || respBytes.size < 12) {
                Log.w(TAG, "upstream DNS resolution failed")
                return
            }
            val ipResp = buildIpUdpResponse(packet, ipHeaderLen, respBytes)
            if (ipResp.isNotEmpty()) output.write(ipResp)
        } catch (e: Exception) {
            Log.w(TAG, "upstream DNS forward failed: ${e.message}")
        }
    }

    private fun buildIpUdpResponse(originalPacket: ByteArray, ipHeaderLen: Int, dnsPayload: ByteArray): ByteArray {
        if (originalPacket.size < ipHeaderLen + 8) return ByteArray(0)
        val totalLen = ipHeaderLen + 8 + dnsPayload.size
        if (totalLen > 65535) return ByteArray(0)
        val resp = ByteArray(totalLen)

        System.arraycopy(originalPacket, 0, resp, 0, ipHeaderLen)
        for (i in 0 until 4) {
            val tmp = resp[12 + i]; resp[12 + i] = resp[16 + i]; resp[16 + i] = tmp
        }
        resp[2] = ((totalLen shr 8) and 0xFF).toByte()
        resp[3] = (totalLen and 0xFF).toByte()
        resp[6] = 0x40.toByte(); resp[7] = 0
        resp[8] = 64; resp[9] = 17
        resp[10] = 0; resp[11] = 0
        var checksum = 0
        var i = 0
        while (i < ipHeaderLen) {
            checksum += ((resp[i].toInt() and 0xFF) shl 8) or (resp[i + 1].toInt() and 0xFF)
            i += 2
        }
        while (checksum shr 16 != 0) checksum = (checksum and 0xFFFF) + (checksum shr 16)
        checksum = checksum.inv() and 0xFFFF
        resp[10] = ((checksum shr 8) and 0xFF).toByte()
        resp[11] = (checksum and 0xFF).toByte()

        val srcPort = ((originalPacket[ipHeaderLen + 2].toInt() and 0xFF) shl 8) or (originalPacket[ipHeaderLen + 3].toInt() and 0xFF)
        val dstPort = ((originalPacket[ipHeaderLen].toInt() and 0xFF) shl 8) or (originalPacket[ipHeaderLen + 1].toInt() and 0xFF)
        resp[ipHeaderLen] = ((srcPort shr 8) and 0xFF).toByte()
        resp[ipHeaderLen + 1] = (srcPort and 0xFF).toByte()
        resp[ipHeaderLen + 2] = ((dstPort shr 8) and 0xFF).toByte()
        resp[ipHeaderLen + 3] = (dstPort and 0xFF).toByte()
        val udpLen = 8 + dnsPayload.size
        resp[ipHeaderLen + 4] = ((udpLen shr 8) and 0xFF).toByte()
        resp[ipHeaderLen + 5] = (udpLen and 0xFF).toByte()
        resp[ipHeaderLen + 6] = 0; resp[ipHeaderLen + 7] = 0
        System.arraycopy(dnsPayload, 0, resp, ipHeaderLen + 8, dnsPayload.size)
        return resp
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, NotificationHelper.CHANNEL_VPN)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "onDestroy — stopping VPN")
        isRunning = false
        packetLoopJob?.cancel()
        statsJob?.cancel()
        vpnInterface?.close()
        vpnInterface = null
        scope.cancel()
    }

    override fun onRevoke() {
        Log.i(TAG, "onRevoke — VPN permission revoked")
        isRunning = false
        packetLoopJob?.cancel()
        statsJob?.cancel()
        vpnInterface?.close()
        vpnInterface = null
        stopSelf()
        scope.launch { WltDataStore.setVpnEnabled(false) }
    }

    companion object {
        private const val TAG = "WltVpnService"
        const val ACTION_PAUSE = "com.wlt.adblocker.PAUSE"
        const val ACTION_RESUME = "com.wlt.adblocker.RESUME"
        const val EXTRA_PAUSE_MINUTES = "pause_minutes"
    }
}
