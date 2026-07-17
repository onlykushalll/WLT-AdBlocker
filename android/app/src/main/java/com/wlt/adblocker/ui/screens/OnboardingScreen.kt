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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    var page by remember { mutableStateOf(0) }
    val totalPages = 4

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Skip button
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (page < totalPages - 1) {
                TextButton(onClick = onFinish) { Text("Skip", fontSize = 13.sp) }
            }
        }

        // Page content
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            when (page) {
                0 -> OnboardingPage1()
                1 -> OnboardingPage2()
                2 -> OnboardingPage3()
                3 -> OnboardingPage4()
            }
        }

        // Page indicator dots
        Row(
            modifier = Modifier.padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            for (i in 0 until totalPages) {
                Box(
                    modifier = Modifier
                        .size(if (i == page) 24.dp else 8.dp, 8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (i == page) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                )
            }
        }

        // Navigation buttons
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (page > 0) {
                Button(
                    onClick = { page-- },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors()
                ) { Text("Back") }
            }
            Button(
                onClick = {
                    if (page < totalPages - 1) page++
                    else onFinish()
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text(if (page < totalPages - 1) "Next" else "Get Started", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun OnboardingPage1() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // Logo/shield
        Box(
            modifier = Modifier.size(140.dp).clip(CircleShape).background(
                Brush.radialGradient(
                    colors = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
                )
            ),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Shield, contentDescription = null, modifier = Modifier.size(80.dp), tint = MaterialTheme.colorScheme.onPrimary)
        }
        Spacer(Modifier.height(32.dp))
        Text("WLT Adblocker", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Production-grade ad blocking for Android.\nNo root. No cloud. No telemetry.\n100% on-device.",
            fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, lineHeight = 20.sp
        )
        Spacer(Modifier.height(24.dp))
        Text("Built from 35+ adblocker analyses", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
        Text("Synthesizing uBlock, AdGuard, Rethink, HostShield, BlockAds", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun OnboardingPage2() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Filled.SportsEsports, contentDescription = null, modifier = Modifier.size(80.dp), tint = MaterialTheme.colorScheme.tertiary)
        Spacer(Modifier.height(24.dp))
        Text("Game Ad Intelligence", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.tertiary)
        Spacer(Modifier.height(12.dp))
        Text(
            "Dedicated engine for mobile game ad SDKs that goes beyond static domain lists.",
            fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        FeatureRow("AdMob", "Google's dominant mobile ad network")
        FeatureRow("Unity Ads", "Unity-based games")
        FeatureRow("AppLovin", "MAX mediation SDK")
        FeatureRow("ironSource", "Acquired by Unity")
        FeatureRow("Chartboost", "Game-focused network")
        FeatureRow("+7 more", "Vungle, Meta, AdColony, Mintegral, Fyber, Tapjoy, InMobi")
    }
}

@Composable
private fun OnboardingPage3() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Filled.Security, contentDescription = null, modifier = Modifier.size(80.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(24.dp))
        Text("Privacy by Architecture", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Text(
            "WLT is trustworthy by design, not by policy.",
            fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        PrivacyRow("All filtering on-device", "No cloud dependency for blocking decisions")
        PrivacyRow("Zero telemetry", "No data leaves your device, ever")
        PrivacyRow("Open source (GPL-3.0)", "Every line auditable on GitHub")
        PrivacyRow("Minimal permissions", "VPN + notification only")
        PrivacyRow("No account required", "No signup, no email, no tracking")
    }
}

@Composable
private fun OnboardingPage4() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Filled.Bolt, contentDescription = null, modifier = Modifier.size(80.dp), tint = MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.height(24.dp))
        Text("Smart Cascade", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.height(12.dp))
        Text(
            "4-layer defense: each layer catches what the previous one misses.",
            fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        LayerCard("Layer 1: DNS", "Blocks 85-90% — games, apps, trackers", true)
        LayerCard("Layer 2: SNI", "Blocks 5-8% more — hardcoded IP SDKs", false)
        LayerCard("Layer 3: HTTPS", "Blocks 3-5% more — URL patterns", false)
        LayerCard("Layer 4: Scriptlet", "Anti-adblock + YouTube web", false)
        Spacer(Modifier.height(16.dp))
        Text("Ad Forensics: WLT explains why ads got through and gives one-tap fixes.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun FeatureRow(name: String, desc: String) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), shape = RoundedCornerShape(10.dp)) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Block, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(name, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(100.dp))
            Text(desc, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PrivacyRow(title: String, desc: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(desc, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LayerCard(name: String, desc: String, active: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (active) Icons.Filled.CheckCircle else Icons.Filled.Lock,
                contentDescription = null,
                tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(name, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text(desc, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
