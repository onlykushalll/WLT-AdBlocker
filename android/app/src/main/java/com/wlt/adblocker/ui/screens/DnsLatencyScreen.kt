package com.wlt.adblocker.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wlt.adblocker.vpn.DnsResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

data class DnsServerResult(
    val name: String,
    val provider: String,
    val dohUrl: String,
    val udpIp: String,
    var latencyMs: Long? = null,
    var status: TestStatus = TestStatus.IDLE
)

enum class TestStatus { IDLE, TESTING, SUCCESS, FAILED }

@Composable
fun DnsLatencyScreen() {
    val scope = rememberCoroutineScope()
    val servers = remember {
        mutableStateListOf(
            DnsServerResult("Cloudflare", "1.1.1.1", "https://cloudflare-dns.com/dns-query", "1.1.1.1"),
            DnsServerResult("Google", "8.8.8.8", "https://dns.google/dns-query", "8.8.8.8"),
            DnsServerResult("Quad9", "9.9.9.9", "https://dns.quad9.net/dns-query", "9.9.9.9"),
            DnsServerResult("AdGuard", "94.140.14.14", "https://dns.adguard-dns.com/dns-query", "94.140.14.14"),
        )
    }
    var testing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // Auto-test on first load
        testAll(servers, scope) { testing = it }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("DNS Server Latency", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            "Test upstream DNS resolver performance. Lower latency = faster browsing.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Button(
            onClick = { testAll(servers, scope) { testing = it } },
            enabled = !testing,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (testing) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                Spacer(Modifier.width(8.dp))
                Text("Testing...")
            } else {
                Icon(Icons.Filled.Speed, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Run Latency Test")
            }
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(servers) { server ->
                DnsServerCard(server)
            }
        }

        // Info card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Dns, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("How it works", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text("Sends a DNS query for 'google.com' via UDP and measures round-trip time. DoH (HTTPS) adds ~20-50ms overhead but is encrypted.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun DnsServerCard(server: DnsServerResult) {
    val statusColor = when (server.status) {
        TestStatus.SUCCESS -> if (server.latencyMs != null && server.latencyMs!! < 50) MaterialTheme.colorScheme.primary
                              else MaterialTheme.colorScheme.secondary
        TestStatus.FAILED -> MaterialTheme.colorScheme.error
        TestStatus.TESTING -> MaterialTheme.colorScheme.tertiary
        TestStatus.IDLE -> MaterialTheme.colorScheme.outline
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (server.status == TestStatus.SUCCESS)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.08f)
            else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                when (server.status) {
                    TestStatus.TESTING -> Icons.Filled.Cloud
                    TestStatus.SUCCESS -> Icons.Filled.Cloud
                    TestStatus.FAILED -> Icons.Filled.CloudOff
                    TestStatus.IDLE -> Icons.Filled.Dns
                },
                contentDescription = null,
                tint = statusColor,
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(server.name, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(server.provider, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
            }
            when (server.status) {
                TestStatus.TESTING -> {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = statusColor)
                }
                TestStatus.SUCCESS -> {
                    Text(
                        "${server.latencyMs}ms",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = statusColor,
                        fontFamily = FontFamily.Monospace
                    )
                }
                TestStatus.FAILED -> {
                    Text("Failed", fontSize = 12.sp, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
                TestStatus.IDLE -> {
                    Text("—", fontSize = 16.sp, color = MaterialTheme.colorScheme.outline)
                }
            }
        }
        if (server.status == TestStatus.TESTING) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = statusColor,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }
    }
}

private fun testAll(
    servers: MutableList<DnsServerResult>,
    scope: kotlinx.coroutines.CoroutineScope,
    onTestingChange: (Boolean) -> Unit = {}
) {
    scope.launch {
        onTestingChange(true)
        for (i in servers.indices) {
            servers[i] = servers[i].copy(status = TestStatus.TESTING)
            val latency = withContext(Dispatchers.IO) { testUdpLatency(servers[i].udpIp) }
            servers[i] = servers[i].copy(
                latencyMs = latency,
                status = if (latency != null) TestStatus.SUCCESS else TestStatus.FAILED
            )
        }
        onTestingChange(false)
    }
}

private fun testUdpLatency(ip: String): Long? {
    return try {
        // Build a DNS query for google.com (A record)
        val query = buildDnsQuery("google.com")
        val socket = DatagramSocket()
        socket.soTimeout = 5000
        val addr = InetAddress.getByName(ip)
        val reqPacket = DatagramPacket(query, query.size, addr, 53)

        val start = System.nanoTime()
        socket.send(reqPacket)
        val respBuf = ByteArray(1024)
        val respPacket = DatagramPacket(respBuf, respBuf.size)
        socket.receive(respPacket)
        val elapsed = System.nanoTime() - start
        socket.close()

        elapsed / 1_000_000 // nanos to millis
    } catch (e: Exception) {
        null
    }
}

private fun buildDnsQuery(domain: String): ByteArray {
    val buf = mutableListOf<Byte>()
    // Header: ID=1, flags=0x0100 (RD=1), QDCOUNT=1
    buf.addAll(listOf(0x00, 0x01).map { it.toByte() })
    buf.addAll(listOf(0x01, 0x00).map { it.toByte() })
    buf.addAll(listOf(0x00, 0x01).map { it.toByte() })
    buf.addAll(listOf(0x00, 0x00).map { it.toByte() })
    buf.addAll(listOf(0x00, 0x00).map { it.toByte() })
    buf.addAll(listOf(0x00, 0x00).map { it.toByte() })
    // QNAME
    for (label in domain.split(".")) {
        buf.add(label.length.toByte())
        buf.addAll(label.map { it.code.toByte() })
    }
    buf.add(0)
    // QTYPE=A (1), QCLASS=IN (1)
    buf.addAll(listOf(0x00, 0x01).map { it.toByte() })
    buf.addAll(listOf(0x00, 0x01).map { it.toByte() })
    return buf.toByteArray()
}
