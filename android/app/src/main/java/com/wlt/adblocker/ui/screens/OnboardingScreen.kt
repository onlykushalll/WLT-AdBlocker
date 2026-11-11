package com.wlt.adblocker.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Http
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * 4-page welcome flow shown on first launch.
 *
 * Pages:
 *  1. WLT intro — shield logo + "No root, no cloud, no telemetry"
 *  2. Game Ad Intelligence — lists the 12 SDKs we detect
 *  3. Privacy by Architecture — 5 guarantees with checkmarks
 *  4. Smart Cascade — 4-layer defense diagram + Ad Forensics mention
 *
 * Navigation: Back / Next / Skip (top-right). Page indicator dots at the
 * bottom. Final page's primary button is "Get Started" → onComplete().
 *
 * Uses Compose Foundation's [HorizontalPager] (NOT the Accompanist version —
 * Accompanist has been deprecated since Compose 1.4).
 *
 * Polish (Task C-ANDROID-UI-POLISH):
 *  - Hero icons rendered inside colored circular badges (more visual weight)
 *  - Page title with "Page X of N" subtitle for clear progress
 *  - Better spacing (24dp hero icon, 16dp subtitle)
 *  - Wider "Get Started" button on the final page for emphasis
 *  - Pill-shaped page indicator instead of plain dots
 */
@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
) {
    val pageCount = 4
    val pagerState = rememberPagerState(initialPage = 0) { pageCount }
    val coroutineScope = rememberCoroutineScope()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
        ) {
            // Top row: progress indicator (left) + Skip button (right).
            // Skip is hidden on the last page so the user must explicitly
            // choose "Get Started" or "Back" from the last page.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        text = "Step ${pagerState.currentPage + 1} of $pageCount",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
                if (pagerState.currentPage < pageCount - 1) {
                    TextButton(onClick = onComplete) {
                        Text("Skip")
                    }
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) { page ->
                when (page) {
                    0 -> Page1Intro()
                    1 -> Page2GameAdIntelligence()
                    2 -> Page3PrivacyByArchitecture()
                    3 -> Page4SmartCascade()
                }
            }

            // Page indicator dots — pill-shaped, animated width.
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                repeat(pageCount) { index ->
                    val isSelected = pagerState.currentPage == index
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .height(if (isSelected) 10.dp else 8.dp)
                            .width(if (isSelected) 24.dp else 8.dp)
                            .clip(RoundedCornerShape(50))
                            .background(
                                if (isSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant
                                }
                            ),
                    )
                }
            }

            // Bottom navigation: Back / Next (or Get Started on last page)
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (pagerState.currentPage > 0) {
                    OutlinedButton(
                        onClick = {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage - 1)
                            }
                        },
                    ) { Text("Back") }
                } else {
                    Spacer(modifier = Modifier.width(0.dp))
                }

                if (pagerState.currentPage < pageCount - 1) {
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                            }
                        },
                    ) {
                        Text("Next")
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("→", fontWeight = FontWeight.Bold)
                    }
                } else {
                    Button(onClick = onComplete) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Get Started", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// --- Hero icon badge — a circular tinted container that gives the icon
//     visual weight at the top of each onboarding page. ---

@Composable
private fun HeroIconBadge(
    icon: ImageVector,
    size: Int = 96,
    iconSize: Int = 48,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
    bg: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primaryContainer,
) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(iconSize.dp),
        )
    }
}

// --- Page 1: WLT intro ---

@Composable
private fun Page1Intro() {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        HeroIconBadge(
            icon = Icons.Filled.Shield,
            size = 120,
            iconSize = 64,
        )
        Spacer(modifier = Modifier.size(24.dp))
        Text(
            text = "WLT-Adblocker",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.size(8.dp))
        // Tagline in a pill — makes it stand out from the body text.
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(50),
        ) {
            Text(
                text = "No root · No cloud · No telemetry",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        Spacer(modifier = Modifier.size(24.dp))
        Text(
            text = "A local VPN-based ad blocker for Android that puts " +
                "your traffic under your control. Every DNS query your " +
                "phone makes is filtered through your blocklists — and " +
                "nothing leaves your device.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.size(24.dp))
        // Three quick guarantees as small inline chips.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            InlineFeatureChip(Icons.Filled.Lock, "Local-only")
            Spacer(modifier = Modifier.width(8.dp))
            InlineFeatureChip(Icons.Filled.Block, "Ad-free")
            Spacer(modifier = Modifier.width(8.dp))
            InlineFeatureChip(Icons.Filled.Security, "Private")
        }
    }
}

@Composable
private fun InlineFeatureChip(icon: ImageVector, label: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(50),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

// --- Page 2: Game Ad Intelligence ---

@Composable
private fun Page2GameAdIntelligence() {
    val sdks = listOf(
        "AdMob", "Unity", "AppLovin", "ironSource", "Chartboost", "Vungle",
        "Meta", "AdColony", "Mintegral", "Fyber", "Tapjoy", "InMobi",
    )
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        HeroIconBadge(
            icon = Icons.Filled.SportsEsports,
            size = 96,
            iconSize = 48,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            bg = MaterialTheme.colorScheme.secondaryContainer,
        )
        Spacer(modifier = Modifier.size(16.dp))
        Text(
            text = "Game Ad Intelligence",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.size(8.dp))
        Text(
            text = "WLT recognizes the 12 SDKs behind most mobile game ads — " +
                "so even when the game is allowed to connect, we know which " +
                "ad networks are talking and can sinkhole them surgically.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.size(24.dp))
        // Render SDKs as a 3-column grid of small badges.
        val rows = sdks.chunked(3)
        for (row in rows) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                for (sdk in row) {
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.SportsEsports,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(12.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = sdk,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }
    }
}

// --- Page 3: Privacy by Architecture ---

@Composable
private fun Page3PrivacyByArchitecture() {
    val guarantees = listOf(
        "DNS filtering happens locally — no upstream server sees your queries",
        "Blocklist downloads go directly to your device, no cloud proxy",
        "No analytics, no crash reporting, no telemetry of any kind",
        "Query log is in-memory only — wiped on every app restart",
        "Zero-cloud architecture — all filtering and parsing runs strictly on-device",
    )
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        HeroIconBadge(
            icon = Icons.Filled.Security,
            size = 96,
            iconSize = 48,
        )
        Spacer(modifier = Modifier.size(16.dp))
        Text(
            text = "Privacy by Architecture",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.size(16.dp))
        // Each guarantee rendered as a card row with a green checkmark
        // circle on the left, for visual scanning.
        for (g in guarantees) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = g,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
    }
}

// --- Page 4: Smart Cascade ---

@Composable
private fun Page4SmartCascade() {
    // Each layer now has its own icon — gives visual differentiation.
    val layers = listOf(
        CascadeLayerInfo("DNS", "Block by domain at the resolver level", Icons.Filled.Dns),
        CascadeLayerInfo("SNI", "Inspect TLS ClientHello to block by hostname", Icons.Filled.Public),
        CascadeLayerInfo("HTTPS", "MITM trusted connections to prune ads (CA-gated)", Icons.Filled.Http),
        CascadeLayerInfo("Scriptlet", "Inject anti-adblock JS into web responses", Icons.Filled.Block),
    )
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        HeroIconBadge(
            icon = Icons.Filled.Shield,
            size = 96,
            iconSize = 48,
        )
        Spacer(modifier = Modifier.size(16.dp))
        Text(
            text = "Smart Cascade",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.size(8.dp))
        Text(
            text = "Four defense layers, each catching what the one above " +
                "missed. Turn them on individually from Settings.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.size(16.dp))
        for ((idx, layer) in layers.withIndex()) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Numbered circle for the cascade order
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "${idx + 1}",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Icon(
                        imageVector = layer.icon,
                        contentDescription = "${layer.name} layer",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = layer.name,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = layer.desc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.size(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Security,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Ad Forensics explains every miss honestly — " +
                    "YouTube app, cert-pinned SDKs, etc.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private data class CascadeLayerInfo(
    val name: String,
    val desc: String,
    val icon: ImageVector,
)
