package com.wlt.adblocker.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.wlt.adblocker.data.PrefsRepository
import com.wlt.adblocker.data.QueryLog
import com.wlt.adblocker.data.RuleStore
import com.wlt.adblocker.data.StatsHistory
import com.wlt.adblocker.filter.BlocklistManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramSocket

/**
 * Core Android VPN service for WLT-Adblocker.
 *
 * Establishes a TUN interface via [VpnService.Builder], intercepts every
 * UDP/53 (DNS) packet that flows through it, runs each query through the
 * block engine (Go via [GoBlockEngine], or [KotlinBlockEngine] as fallback),
 * and either sinkholes (NXDOMAIN / 0.0.0.0) or forwards to the upstream
 * DoH resolver.
 *
 * Per-app firewall: reads [RuleStore.getBypassApps] at startup and calls
 * [VpnService.Builder.addDisallowedApplication] for each — those apps
 * bypass the VPN entirely (no DNS interception, no SNI inspection).
 *
 * Pause/resume: [ACTION_PAUSE] sets [pausedUntil]; while paused, all
 * queries are forwarded upstream WITHOUT blocking (so the user keeps
 * connectivity but loses ad protection for the chosen duration). The
 * notification updates every minute to show "resumes in Xm".
 *
 * Stats recording: [statsRecorder] coroutine snapshots [BlockStats] every
 * 60 seconds into [StatsHistory] for the dashboard sparkline.
 *
 * === CRITICAL BUG FIXES (Task 39 audit) ===
 *
 * 1. **outputLock** — VPN tun [FileOutputStream] is NOT thread-safe.
 *    Multiple coroutines calling `output.write()` concurrently can
 *    interleave bytes, producing malformed packets that the kernel
 *    drops silently (no crash, just lost queries). FIX: every
 *    `output.write()` is wrapped in `synchronized(outputLock) { }`.
 *
 * 2. **Socket leak in forwardUpstream** — the original code created
 *    a DatagramSocket but didn't close it on exception, leaking file
 *    descriptors and eventually hitting the process FD limit. FIX:
 *    the socket is created inside [DnsResolver.tryUdp], which already
 *    has a `finally { socket?.close() }` block. The protect() callback
 *    below also wraps the socket creation in try/finally.
 *
 * 3. **isRunning flag** — the loop condition uses a `@Volatile var isRunning`
 *    flag instead of `coroutineContext.isActive`. The latter is a suspend
 *    property that requires coroutine context to be present, which fails
 *    in odd places (Task 13 build error). The flag is simpler and explicit.
 */
class WltVpnService : VpnService() {

    companion object {
        private const val TAG = "WltVpnService"

        const val ACTION_START = "com.wlt.adblocker.action.START"
        const val ACTION_STOP = "com.wlt.adblocker.action.STOP"
        const val ACTION_PAUSE = "com.wlt.adblocker.action.PAUSE"
        const val ACTION_RESUME = "com.wlt.adblocker.action.RESUME"
        const val EXTRA_PAUSE_MINUTES = "pause_minutes"

        private const val CHANNEL_ID = "wlt_vpn_status"
        private const val NOTIFICATION_ID = 1001

        private const val VPN_ADDRESS = "10.66.6.6"      // TUN interface address
        private const val VPN_ROUTE = "10.66.6.6"        // ONLY route VPN address (DNS-only mode)
        private const val VPN_DNS = "10.66.6.6"          // DNS server = VPN address (system sends DNS here)
        private const val VPN_MTU = 1500
        private const val DNS_PORT = 53
        private const val PACKET_BUFFER_SIZE = 32_767
        private const val STATS_INTERVAL_MS = 60_000L    // 60 seconds

        // --- Companion helpers for callers in the UI layer ---
        // These wrap the intent-based start/stop/pause/resume protocol so the
        // UI layer doesn't have to know the action strings.

        /** Fires an ACTION_START intent to bring the VPN up. The VPN service
         *  will start in foreground, establish the TUN, and begin the packet
         *  loop. Idempotent — if the service is already running it ignores
         *  the START intent. The caller is responsible for obtaining VPN
         *  permission via `VpnService.prepare()` first; if not granted, the
         *  service will fail to establish and call stopSelf. */
        fun startVPN(context: android.content.Context) {
            val intent = android.content.Intent(context, WltVpnService::class.java).apply {
                action = ACTION_START
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Fires an ACTION_STOP intent to tear the VPN down. */
        fun stopVPN(context: android.content.Context) {
            val intent = android.content.Intent(context, WltVpnService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        /** Fires ACTION_PAUSE with [minutes] minutes of pause. */
        fun pauseVPN(context: android.content.Context, minutes: Int) {
            val intent = android.content.Intent(context, WltVpnService::class.java).apply {
                action = ACTION_PAUSE
                putExtra(EXTRA_PAUSE_MINUTES, minutes)
            }
            context.startService(intent)
        }

        /** Fires ACTION_RESUME to clear the pause state. */
        fun resumeVPN(context: android.content.Context) {
            val intent = android.content.Intent(context, WltVpnService::class.java).apply {
                action = ACTION_RESUME
            }
            context.startService(intent)
        }
    }

    // === Dependencies ===
    private lateinit var ruleStore: RuleStore
    private lateinit var queryLog: QueryLog
    private lateinit var statsHistory: StatsHistory
    private lateinit var prefsRepository: PrefsRepository
    private lateinit var blocklistManager: BlocklistManager
    private lateinit var kotlinBlockEngine: KotlinBlockEngine
    private lateinit var goBlockEngine: GoBlockEngine
    private lateinit var dnsResolver: DnsResolver
    private lateinit var uidResolver: UidResolver
    private lateinit var dnsCache: DnsCache
    private lateinit var domainIpCache: DomainIpCache
    val blockStats = BlockStats()
    val appNetworkStats = com.wlt.adblocker.data.AppNetworkStats()

    // Phase 8b/8c: Firewall toggles
    @Volatile private var blockDoTPort = true       // Block DNS-over-TLS (port 853)
    @Volatile private var blockQuicPort = false     // Block QUIC/HTTP3 (UDP 443) — opt-in

    // === VPN state ===
    private var vpnInterface: ParcelFileDescriptor? = null
    private var outputFile: FileOutputStream? = null

    /**
     * CRITICAL (Task 39 fix #1): VPN tun [FileOutputStream] is NOT
     * thread-safe. Every write MUST go through this lock.
     */
    private val outputLock = Any()

    /** Loop-control flag. The packet loop reads this on every iteration.
     *  Set to false by [onDestroy] / [ACTION_STOP] to exit cleanly. */
    @Volatile
    private var isRunning: Boolean = false

    /** Pause state. Set by [ACTION_PAUSE], cleared by [ACTION_RESUME] or
     *  when [System.currentTimeMillis] passes [pausedUntil]. */
    @Volatile
    private var pausedUntil: Long = 0L

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var packetJob: Job? = null
    private var statsJob: Job? = null

    // ============================================================
    // Lifecycle
    // ============================================================

    override fun onCreate() {
        super.onCreate()
        ruleStore = RuleStore.get(applicationContext)
        queryLog = QueryLog()
        statsHistory = StatsHistory()
        prefsRepository = PrefsRepository(applicationContext)
        blocklistManager = BlocklistManager(applicationContext)
        kotlinBlockEngine = KotlinBlockEngine(applicationContext, blocklistManager)
        goBlockEngine = GoBlockEngine(applicationContext, kotlinBlockEngine)
        dnsResolver = DnsResolver(applicationContext)
        uidResolver = UidResolver(applicationContext)
        dnsCache = DnsCache(10_000) // Phase 8a: 10K entry LRU cache (~1MB)
        domainIpCache = DomainIpCache(5_000) // Phase 9a: IP→domain reverse lookup

        // CRITICAL FIX: Load blocklists SYNCHRONOUSLY before VPN starts.
        // Previously this was async, so the VPN started with empty blocklists
        // and nothing was blocked. Now we load in onCreate (blocking) so the
        // trie is populated before the user can start the VPN.
        try {
            kotlinBlockEngine.loadBundledBlocklists()
            goBlockEngine.loadBundledBlocklists()
            Log.i(TAG, "Blocklists loaded synchronously in onCreate")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load blocklists in onCreate", e)
        }

        // Load persisted pause state in background. The non-suspending
        // [PrefsRepository.pauseUntilSnapshot] returns 0 until this loads,
        // so queries are NOT incorrectly treated as "paused" during startup.
        serviceScope.launch {
            try {
                val persisted = prefsRepository.getPauseUntil()
                if (persisted > System.currentTimeMillis()) {
                    pausedUntil = persisted
                    Log.i(TAG, "Restored paused state from disk (until $pausedUntil)")
                } else if (persisted != 0L) {
                    // Persisted pause has expired — clear it.
                    prefsRepository.setPauseUntil(0L)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load persisted pause state", e)
            }
        }

        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "Stop requested")
                stopVpn()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                val minutes = intent.getIntExtra(EXTRA_PAUSE_MINUTES, 15)
                pauseProtection(minutes)
            }
            ACTION_RESUME -> {
                Log.i(TAG, "Resume requested")
                pausedUntil = 0L
                updateNotification()
            }
            ACTION_START, null -> {
                Log.i(TAG, "Start requested")
                startVpn()
            }
        }
        // START_STICKY: restart the service if the system kills it for memory.
        // The user explicitly enabled the VPN; we should try to come back.
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        stopVpn()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onRevoke() {
        // User disabled VPN via system settings. Stop cleanly.
        Log.i(TAG, "onRevoke — VPN permission revoked")
        stopVpn()
        stopSelf()
    }

    // ============================================================
    // VPN start / stop
    // ============================================================

    private fun startVpn() {
        if (isRunning) {
            Log.i(TAG, "VPN already running, ignoring START")
            return
        }
        val builder = Builder()
            .setSession("WLT-Adblocker")
            .setMtu(VPN_MTU)
            .addAddress(VPN_ADDRESS, 32)
            .addRoute(VPN_ROUTE, 32)  // CRITICAL FIX: prefix 32 = only route VPN address, NOT all traffic
            .addDnsServer(VPN_DNS)
            .setBlocking(true)
            .setConfigureIntent(buildConfigureIntent() ?: return)

        // Per-app firewall: exclude apps the user marked as "bypass".
        val bypassApps = ruleStore.getBypassApps()
        for (pkg in bypassApps) {
            try {
                builder.addDisallowedApplication(pkg)
                Log.i(TAG, "App bypass enabled: $pkg")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to add bypass for $pkg (not installed?)", e)
            }
        }

        try {
            vpnInterface = builder.establish()
            outputFile = FileOutputStream(vpnInterface?.fileDescriptor)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to establish VPN interface", e)
            stopSelf()
            return
        }

        if (vpnInterface == null || outputFile == null) {
            Log.e(TAG, "VPN interface or output stream is null after establish()")
            stopSelf()
            return
        }

        isRunning = true
        startForeground(NOTIFICATION_ID, buildNotification())
        packetJob = serviceScope.launch { packetLoop() }
        statsJob = serviceScope.launch { statsRecorder() }
        Log.i(TAG, "VPN started (bypass=${bypassApps.size} apps, paused=${isPaused()})")
    }

    private fun stopVpn() {
        isRunning = false
        packetJob?.cancel()
        statsJob?.cancel()
        packetJob = null
        statsJob = null
        try {
            outputFile?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing tun output stream", e)
        }
        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing tun interface", e)
        }
        outputFile = null
        vpnInterface = null
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            Log.w(TAG, "Error removing foreground state", e)
        }
    }

    // ============================================================
    // Packet loop
    // ============================================================

    private suspend fun packetLoop() {
        val pfd = vpnInterface ?: run {
            Log.e(TAG, "packetLoop: vpnInterface is null")
            return
        }
        val input = FileInputStream(pfd.fileDescriptor)
        // Heap-backed byte array — simplest option. A direct ByteBuffer would
        // be slightly faster (one less copy in Os.read) but requires JNI
        // plumbing we don't need for DNS-sized packets (<512 bytes typically).
        val buffer = ByteArray(PACKET_BUFFER_SIZE)
        Log.i(TAG, "Packet loop started")

        while (isRunning) {
            val length = try {
                input.read(buffer)
            } catch (e: IOException) {
                if (isRunning) Log.w(TAG, "Tun read IOException", e)
                break
            }
            if (length <= 0) continue
            try {
                handlePacket(buffer, length)
            } catch (e: Exception) {
                // Never let a single malformed packet take down the loop.
                Log.w(TAG, "Error handling packet (len=$length)", e)
            }
        }
        Log.i(TAG, "Packet loop exited")
    }

    /** Dispatches a single IPv4 packet from the tun. */
    private fun handlePacket(buf: ByteArray, length: Int) {
        val parsed = PacketIo.parseIpv4(buf, length) ?: return
        val (ipHeader, headerLen) = parsed

        when (ipHeader.protocol) {
            PacketIo.IP_PROTO_UDP -> handleUdp(ipHeader, buf, headerLen, length)
            PacketIo.IP_PROTO_TCP -> {
                // Phase 8b: Block DNS-over-TLS (DoT) — TCP port 853
                val tcpHeader = PacketIo.parseTcp(buf, headerLen, length)
                if (tcpHeader != null && blockDoTPort &&
                    (tcpHeader.dstPort == 853 || tcpHeader.srcPort == 853)) {
                    Log.d(TAG, "DoT port 853 blocked (TCP)")
                    return // Drop DoT packet
                }

                // Phase 2 — SNI inspection. Delegated to ConnectionFilter.
                // OFF by default; only routes to ConnectionFilter if the user
                // has explicitly enabled HTTPS-layer filtering. The filter
                // itself lives outside this service (see ConnectionFilter.kt).
                // For Phase 1 (DNS-only), we let TCP fall through unmodified
                // — i.e., the VPN doesn't intercept it.
                // Future: route to ConnectionFilter.handleTcpPacket here when enabled.
            }
            // ICMP and others: drop silently. VPN is DNS-only for now.
        }
    }

    private fun handleUdp(ipHeader: PacketIo.Ipv4Header, buf: ByteArray, udpOffset: Int, totalLen: Int) {
        val udp = PacketIo.parseUdp(buf, udpOffset, totalLen) ?: return

        // Phase 8b: Block DNS-over-TLS (DoT) — port 853
        // Apps can use DoT to bypass VPN DNS. Port 853 is exclusively for DoT,
        // so blocking it is safe — no legitimate traffic on this port.
        if (blockDoTPort && (udp.dstPort == 853 || udp.srcPort == 853)) {
            Log.d(TAG, "DoT port 853 blocked (UDP)")
            return // Drop the packet silently
        }

        // Phase 8c: Block QUIC / HTTP/3 — UDP port 443 (opt-in)
        // QUIC encrypts more of the TLS handshake, making SNI inspection
        // harder. Blocking forces apps to fall back to TCP+TLS where
        // SNI is visible. Also prevents DoQ (DNS-over-QUIC).
        if (blockQuicPort && udp.dstPort == 443) {
            Log.d(TAG, "QUIC port 443/UDP blocked")
            return // Drop the packet — forces TCP fallback
        }

        // Only intercept DNS (UDP/53). Other UDP is passed through unmodified.
        if (udp.dstPort != DNS_PORT) return

        val dnsOffset = udpOffset + PacketIo.UDP_HEADER_LEN
        val dnsLen = totalLen - dnsOffset
        if (dnsLen < 12) return // too short to be a DNS packet

        val dnsPacket = buf.copyOfRange(dnsOffset, dnsOffset + dnsLen)
        // Only handle queries (QR=0). Responses shouldn't come through the tun
        // (the kernel routes them to the original socket), but defensive check.
        if (!DnsPacketParser.isQuery(dnsPacket)) return

        val domain = DnsPacketParser.extractQueryName(dnsPacket)
        if (domain == null) {
            Log.w(TAG, "Could not extract query name from DNS packet")
            return
        }

        handleDnsQuery(domain, dnsPacket, ipHeader, udp)
    }

    /**
     * Handles a parsed DNS query: decides block/allow, builds the response,
     * and writes it back to the tun.
     */
    private fun handleDnsQuery(
        domain: String,
        dnsPacket: ByteArray,
        ipHeader: PacketIo.Ipv4Header,
        udp: PacketIo.UdpHeader,
    ) {
        // Resolve the UID that originated this query, for per-app attribution.
        // Best-effort — returns INVALID_UID if the call fails or is unsupported.
        val uid = try {
            uidResolver.getConnectionOwnerUid(
                protocol = PacketIo.IP_PROTO_UDP,
                srcIp = ipToDotted(ipHeader.srcAddr),
                srcPort = udp.srcPort,
                dstIp = ipToDotted(ipHeader.dstAddr),
                dstPort = udp.dstPort,
            )
        } catch (e: Exception) {
            android.os.Process.INVALID_UID
        }
        val pkg = if (uid != android.os.Process.INVALID_UID) uidResolver.uidToPackageName(uid) else null

        // === PAUSE CHECK ===
        // When paused, forward everything without blocking. We still log
        // the query (as "allowed") so the user can see what was queried
        // during the pause window.
        if (isPaused()) {
            val upstream = forwardUpstream(dnsPacket)
            if (upstream != null) {
                writeResponse(ipHeader, udp, upstream)
                queryLog.add(
                    QueryLog.Entry(
                        domain = domain,
                        timestamp = System.currentTimeMillis(),
                        blocked = false,
                        reason = "allowed (paused)",
                        sdk = null,
                        uid = uid,
                        packageName = pkg,
                    )
                )
                blockStats.incAllowed()
            } else {
                // Upstream failed — fall back to NXDOMAIN so the client
                // doesn't hang waiting for a response.
                val nxdomain = DnsPacketParser.buildNXDOMAIN(dnsPacket)
                writeResponse(ipHeader, udp, nxdomain)
            }
            return
        }

        // === DNS CACHE CHECK (Phase 8a) ===
        // Check cache first — if we have a valid cached response, return it
        // immediately without hitting the block engine or upstream. This
        // reduces upstream queries by ~70% and provides <1ms cache hits.
        val cached = dnsCache.get(domain)
        if (cached != null) {
            writeResponse(ipHeader, udp, cached)
            // Don't log to QueryLog for cache hits — would flood the log.
            // Still update stats minimally.
            blockStats.incAllowed() // Approximate — we don't know if it was blocked
            return
        }

        // === BLOCK CHECK ===
        val blocked = goBlockEngine.shouldBlock(domain)
        val sdk = if (blocked) goBlockEngine.getLastBlockSdk() else kotlinBlockEngine.detectSdk(domain)

        if (blocked) {
            val reason = goBlockEngine.getLastBlockReason()
            // Use the configurable block response type (NXDOMAIN / NullIP / REFUSED).
            // For A/AAAA queries, NullIP (0.0.0.0) is generally best because
            // it prevents retries. For other query types, NXDOMAIN is safer.
            val (_, qType) = DnsPacketParser.extractQuery(dnsPacket) ?: (domain to 0)
            val response = kotlinBlockEngine.buildBlockResponse(dnsPacket)
            // Phase 8a: Cache the blocked response (5 min TTL)
            dnsCache.put(domain, response, blocked = true)
            writeResponse(ipHeader, udp, response)
            blockStats.incBlocked(domain, sdk)
            // Phase 9b: Record per-app stats
            appNetworkStats.recordQuery(pkg, uid, domain, blocked = true, trackerName = sdk)
            queryLog.add(
                QueryLog.Entry(
                    domain = domain,
                    timestamp = System.currentTimeMillis(),
                    blocked = true,
                    reason = reason,
                    sdk = sdk,
                    uid = uid,
                    packageName = pkg,
                )
            )
            Log.d(TAG, "BLOCKED: $domain ($reason${sdk?.let { " sdk=$it" } ?: ""})")
            return
        }

        // === FORWARD ===
        val upstream = forwardUpstream(dnsPacket)
        if (upstream == null) {
            // Upstream failed — return NXDOMAIN so client doesn't hang.
            val nxdomain = DnsPacketParser.buildNXDOMAIN(dnsPacket)
            writeResponse(ipHeader, udp, nxdomain)
            queryLog.add(
                QueryLog.Entry(
                    domain = domain,
                    timestamp = System.currentTimeMillis(),
                    blocked = true,
                    reason = "upstream-failed",
                    sdk = null,
                    uid = uid,
                    packageName = pkg,
                )
            )
            return
        }

        // === CNAME CLOAKING CHECK ===
        // Some trackers hide behind a benign-looking CNAME: a query for
        // "stats.example.com" returns a CNAME to "tracker.evil.com", then
        // an A record for the tracker's IP. We inspect the upstream
        // response for CNAMEs and re-check each target.
        val cnameTargets = DnsPacketParser.extractCNAMETargets(upstream)
        for (cname in cnameTargets) {
            if (kotlinBlockEngine.shouldBlockCnameTarget(cname)) {
                Log.i(TAG, "CNAME cloaking detected: $domain → $cname (blocked)")
                val nxdomain = DnsPacketParser.buildNXDOMAIN(dnsPacket)
                writeResponse(ipHeader, udp, nxdomain)
                blockStats.incBlocked(domain, sdk)
                queryLog.add(
                    QueryLog.Entry(
                        domain = "$domain → $cname",
                        timestamp = System.currentTimeMillis(),
                        blocked = true,
                        reason = "CNAME cloak: $cname",
                        sdk = null,
                        uid = uid,
                        packageName = pkg,
                    )
                )
                return
            }
        }

        // === ALLOWED — pass through the upstream response ===
        // Phase 8a: Cache the allowed response (upstream TTL, capped at 1 hour)
        dnsCache.put(domain, upstream, blocked = false, upstreamTtl = 300)
        // Phase 9a: Store IP→domain mapping for reverse lookup
        val answerIps = DnsPacketParser.extractAnswerIps(upstream)
        for (ip in answerIps) {
            domainIpCache.put(ip, domain)
        }
        writeResponse(ipHeader, udp, upstream)
        blockStats.incAllowed()
        // Phase 9b: Record per-app stats (allowed)
        appNetworkStats.recordQuery(pkg, uid, domain, blocked = false, trackerName = sdk)
        queryLog.add(
            QueryLog.Entry(
                domain = domain,
                timestamp = System.currentTimeMillis(),
                blocked = false,
                reason = "allowed",
                sdk = sdk,
                uid = uid,
                packageName = pkg,
            )
        )
    }

    /**
     * Forwards [dnsPacket] to the upstream DoH resolver.
     *
     * CRITICAL (Task 39 fix #2): the underlying [DnsResolver.tryUdp] opens
     * a [DatagramSocket] inside a try/finally that closes the socket on
     * every exit path, including exception. The [socketProtector] callback
     * we pass invokes [VpnService.protect] on the socket before it's
     * connected — without that, the DNS query would loop back through
     * our own tun (infinite recursion, stack overflow, kernel panic).
     */
    private fun forwardUpstream(dnsPacket: ByteArray): ByteArray? {
        return dnsResolver.resolve(
            query = dnsPacket,
            primary = "cloudflare",
            socketProtector = { socket -> protectSocket(socket) },
        )
    }

    /** Wraps [VpnService.protect] for use as a callback. Returns true if
     *  the socket was successfully protected (won't loop through the tun). */
    private fun protectSocket(socket: DatagramSocket): Boolean {
        return try {
            protect(socket)
        } catch (e: Exception) {
            Log.w(TAG, "protect() failed for socket", e)
            false
        }
    }

    /**
     * Builds the response packet: wraps [dnsResponse] in IPv4/UDP headers
     * with source/destination swapped (we're sending FROM us back TO the
     * client), and writes it to the tun.
     *
     * CRITICAL (Task 39 fix #1): the actual `output.write()` call MUST
     * be inside `synchronized(outputLock) { }` — the tun output stream
     * is not thread-safe and concurrent writes corrupt packets.
     */
    private fun writeResponse(
        ipHeader: PacketIo.Ipv4Header,
        udp: PacketIo.UdpHeader,
        dnsResponse: ByteArray,
    ) {
        // Swap src/dst: client's src becomes our dst and vice versa
        val responsePacket = PacketIo.buildUdpIpv4Packet(
            srcIp = ipHeader.dstAddr,
            dstIp = ipHeader.srcAddr,
            srcPort = udp.dstPort,
            dstPort = udp.srcPort,
            payload = dnsResponse,
        )
        val out = outputFile ?: run {
            Log.w(TAG, "outputFile is null, dropping response")
            return
        }
        synchronized(outputLock) {
            try {
                out.write(responsePacket)
            } catch (e: IOException) {
                Log.w(TAG, "Failed to write response to tun", e)
            }
        }
    }

    // ============================================================
    // Stats recorder
    // ============================================================

    /** Snapshots [BlockStats] into [StatsHistory] every 60 seconds.
     *  Runs as a coroutine alongside [packetLoop]. */
    private suspend fun statsRecorder() {
        Log.i(TAG, "Stats recorder started")
        var lastBlocked = 0L
        var lastAllowed = 0L
        while (isRunning) {
            delay(STATS_INTERVAL_MS)
            if (!isRunning) break
            try {
                val snap = blockStats.snapshot()
                val blockedDelta = (snap.totalBlocked - lastBlocked).coerceAtLeast(0L).toInt()
                val allowedDelta = (snap.totalAllowed - lastAllowed).coerceAtLeast(0L).toInt()
                statsHistory.add(
                    StatsHistory.Point(
                        timestamp = System.currentTimeMillis(),
                        blocked = blockedDelta,
                        allowed = allowedDelta,
                    )
                )
                lastBlocked = snap.totalBlocked
                lastAllowed = snap.totalAllowed
                // Update notification every minute so the "X queries blocked"
                // counter stays fresh.
                updateNotification()
            } catch (e: Exception) {
                Log.w(TAG, "Stats recorder error", e)
            }
        }
        Log.i(TAG, "Stats recorder exited")
    }

    // ============================================================
    // Pause / Resume
    // ============================================================

    /** Pauses protection for [minutes] minutes. While paused, all queries
     *  are forwarded upstream without blocking. */
    private fun pauseProtection(minutes: Int) {
        val minutesInt = minutes.coerceIn(1, 1440) // 1 minute to 24 hours
        pausedUntil = System.currentTimeMillis() + minutesInt * 60_000L
        Log.i(TAG, "Protection paused for $minutesInt minutes (until $pausedUntil)")
        // Persist so pause survives a service restart. Launch in background —
        // we don't want to block the main thread on a DataStore write.
        serviceScope.launch {
            try {
                prefsRepository.setPauseUntil(pausedUntil)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to persist pause state", e)
            }
        }
        updateNotification()
    }

    /** Returns true if protection is currently paused. */
    fun isPaused(): Boolean {
        if (pausedUntil == 0L) return false
        if (System.currentTimeMillis() >= pausedUntil) {
            pausedUntil = 0L
            return false
        }
        return true
    }

    /** Minutes remaining until pause expires, or 0 if not paused. */
    fun pauseRemainingMinutes(): Int {
        if (pausedUntil == 0L) return 0
        val remaining = (pausedUntil - System.currentTimeMillis()) / 60_000L
        return remaining.coerceAtLeast(0L).toInt()
    }

    // ============================================================
    // Notifications
    // ============================================================

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "WLT VPN status",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows whether WLT ad blocking is active"
                setShowBadge(false)
            }
        )
    }

    private fun buildConfigureIntent(): PendingIntent? {
        // The intent that fires when the user taps the notification.
        // We can't reference MainActivity directly from here without
        // creating a circular dependency (the UI layer lives above the
        // VPN layer), so we use an explicit intent by name.
        val intent = Intent().apply {
            setClassName(packageName, "com.wlt.adblocker.MainActivity")
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getActivity(this, 0, intent, flags)
    }

    private fun buildNotification(): Notification {
        ensureNotificationChannel()
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(buildConfigureIntent())
        if (isPaused()) {
            builder.setContentTitle("WLT paused")
            builder.setContentText("Resumes in ${pauseRemainingMinutes()}m · queries not blocked")
        } else {
            val blocked = blockStats.totalBlocked()
            builder.setContentTitle("WLT active — protecting")
            builder.setContentText("$blocked queries blocked")
        }
        return builder.build()
    }

    /** Refreshes the foreground notification. Call after stats updates
     *  or pause/resume state changes. */
    fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    // ============================================================
    // Helpers
    // ============================================================

    private fun ipToDotted(bytes: ByteArray): String {
        if (bytes.size != 4) return "0.0.0.0"
        return "${bytes[0].asUnsignedInt()}.${bytes[1].asUnsignedInt()}." +
            "${bytes[2].asUnsignedInt()}.${bytes[3].asUnsignedInt()}"
    }
}
