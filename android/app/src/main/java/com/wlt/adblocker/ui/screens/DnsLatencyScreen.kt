package com.wlt.adblocker.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wlt.adblocker.vpn.DnsResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * DNS latency tester.
 *
 * Sends a real UDP DNS query for "google.com" to each of the 4 upstream
 * providers (Cloudflare, Google, Quad9, AdGuard) and measures round-trip
 * time in milliseconds.
 *
 * Color coding:
 *  - < 50ms: green (excellent)
 *  - 50ms+: amber (acceptable)
 *  - failed: red
 *
 * Auto-tests on screen load and on demand via the "Re-test" button.
 *
 * Note: this measures raw UDP DNS latency, NOT DoH (HTTPS) latency. DoH
 * adds ~50-150ms of TLS handshake overhead on the first request and
 * ~5-20ms on subsequent requests (HTTP/2 connection reuse). The VPN
 * uses DoH-first for privacy, but raw UDP is what the latency test
 * measures because it's a cleaner signal.
 */
@Composable
fun DnsLatencyScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dnsResolver = remember { DnsResolver(context) }
    var results by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var testing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        runTests(dnsResolver, onTesting = { testing = it }) { results = it }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = "DNS Latency",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = "UDP DNS round-trip per upstream",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(
                onClick = {
                    scope.launch {
                        runTests(dnsResolver, onTesting = { testing = it }) { results = it }
                    }
                },
                enabled = !testing,
            ) {
                if (testing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("Re-test")
                }
            }
        }
        Spacer(modifier = Modifier.size(12.dp))

        for (upstream in DnsResolver.UPSTREAMS) {
            val latency = results[upstream.name]
            ProviderCard(
                name = upstream.name.replaceFirstChar { it.uppercase() },
                ip = upstream.udpAddress,
                latencyMs = latency,
                testing = testing && latency == null,
            )
            Spacer(modifier = Modifier.size(8.dp))
        }

        Spacer(modifier = Modifier.size(8.dp))
        InfoCard()
    }
}

private suspend fun runTests(
    resolver: DnsResolver,
    onTesting: (Boolean) -> Unit,
    onResults: (Map<String, Long>) -> Unit,
) {
    onTesting(true)
    val out = LinkedHashMap<String, Long>()
    for (upstream in DnsResolver.UPSTREAMS) {
        // Null-check the resolver — guarding against a misconfigured device.
        val latency = withContext(Dispatchers.IO) {
            try {
                resolver.measureUdpLatency(upstream)
            } catch (e: Exception) {
                -1L
            }
        }
        out[upstream.name] = latency
        onResults(out.toMap()) // incremental update so user sees results come in
    }
    onTesting(false)
}

@Composable
private fun ProviderCard(
    name: String,
    ip: String,
    latencyMs: Long?,
    testing: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Dns,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = ip,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when {
                latencyMs == null && testing -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                }
                latencyMs == null -> {
                    Text(
                        text = "—",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                latencyMs < 0 -> {
                    StatusBadge(
                        icon = Icons.Filled.Close,
                        text = "Failed",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                latencyMs < 50 -> {
                    StatusBadge(
                        icon = Icons.Filled.CheckCircle,
                        text = "${latencyMs}ms",
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                else -> {
                    StatusBadge(
                        icon = Icons.Filled.Schedule,
                        text = "${latencyMs}ms",
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    color: Color,
) {
    Surface(
        color = color.copy(alpha = 0.15f),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = color,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun InfoCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "UDP vs DoH latency",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(modifier = Modifier.size(4.dp))
            Text(
                text = "This test measures raw UDP DNS latency (one packet " +
                    "each way). WLT uses DoH (DNS-over-HTTPS) by default " +
                    "for privacy, which adds ~5-20ms per query after the " +
                    "initial TLS handshake. Use these numbers as a relative " +
                    "comparison between providers, not as an absolute " +
                    "prediction of WLT's query time.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}
