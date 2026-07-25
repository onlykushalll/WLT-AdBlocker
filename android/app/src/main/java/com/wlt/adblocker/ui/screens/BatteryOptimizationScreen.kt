package com.wlt.adblocker.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Phase 10c: Battery Optimization Screen
 *
 * Detects device OEM and shows specific instructions to prevent
 * the OS from killing WLT's VPN service.
 *
 * OEM-specific issues:
 * - Samsung: "Sleeping Apps" kills apps after 3 days
 * - Xiaomi/MIUI: Aggressive battery killer
 * - Huawei/EMUI: PowerGenie kills background apps
 * - Oppo/ColorOS: Battery optimization kills VPNs
 * - OnePlus/OxygenOS: Battery optimization kills VPNs
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatteryOptimizationScreen() {
    // Detect OEM from Build.MANUFACTURER
    val manufacturer = android.os.Build.MANUFACTURER?.lowercase() ?: ""
    val brand = android.os.Build.BRAND?.lowercase() ?: ""

    val oemInstructions = getOemInstructions(manufacturer, brand)

    Column(modifier = Modifier.fillMaxSize()) {
        // Header
        Card(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.BatteryFull, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("Battery Optimization", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Android OEMs aggressively kill VPN apps to save battery. " +
                    "Follow these steps to keep WLT alive.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }

        // Detected device
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.PhoneAndroid, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Detected: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text("Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // OEM-specific instructions
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            items(oemInstructions) { instruction ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (instruction.critical) Icons.Filled.BatteryAlert else Icons.Filled.BatteryFull,
                                contentDescription = null,
                                tint = if (instruction.critical) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(instruction.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(8.dp))
                        instruction.steps.forEachIndexed { index, step ->
                            Text(
                                "${index + 1}. $step",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(start = 28.dp, bottom = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

data class OemInstruction(
    val title: String,
    val steps: List<String>,
    val critical: Boolean = false,
)

private fun getOemInstructions(manufacturer: String, brand: String): List<OemInstruction> {
    val instructions = mutableListOf<OemInstruction>()

    // Universal instructions (all devices)
    instructions.add(OemInstruction(
        title = "Disable Battery Optimization (All Devices)",
        steps = listOf(
            "Go to Settings → Apps → WLT-Adblocker",
            "Tap Battery → Unrestricted (or Don't optimize)",
            "This prevents Android Doze mode from killing the VPN",
        ),
        critical = true,
    ))

    instructions.add(OemInstruction(
        title = "Enable Autostart (All Devices)",
        steps = listOf(
            "Go to Settings → Apps → WLT-Adblocker",
            "Enable Auto-start / Start on boot",
            "This ensures WLT starts after device restart",
        ),
    ))

    // OEM-specific
    when {
        manufacturer.contains("samsung") || brand.contains("samsung") -> {
            instructions.add(OemInstruction(
                title = "Samsung: Sleeping Apps",
                steps = listOf(
                    "Go to Settings → Battery and device care → Background usage limits",
                    "Remove WLT-Adblocker from 'Sleeping apps'",
                    "Add WLT-Adblocker to 'Never sleeping apps'",
                    "Note: OTA updates may reset this setting silently",
                ),
                critical = true,
            ))
        }
        manufacturer.contains("xiaomi") || brand.contains("xiaomi") || brand.contains("redmi") -> {
            instructions.add(OemInstruction(
                title = "Xiaomi/MIUI: Battery Saver",
                steps = listOf(
                    "Go to Settings → Apps → WLT-Adblocker",
                    "Enable Auto-start",
                    "Go to Battery saver → No restrictions",
                    "Security app → Manage apps → WLT → Allow background activity",
                ),
                critical = true,
            ))
        }
        manufacturer.contains("huawei") || brand.contains("huawei") || brand.contains("honor") -> {
            instructions.add(OemInstruction(
                title = "Huawei/EMUI: App Launch",
                steps = listOf(
                    "Go to Settings → Battery → App launch",
                    "Find WLT-Adblocker → Manage manually",
                    "Enable: Auto-launch, Secondary launch, Run in background",
                    "Disable PowerGenie if it's killing WLT",
                ),
                critical = true,
            ))
        }
        manufacturer.contains("oppo") || brand.contains("oppo") || brand.contains("realme") || brand.contains("oneplus") -> {
            instructions.add(OemInstruction(
                title = "Oppo/OnePlus/RealMe: Battery Optimization",
                steps = listOf(
                    "Go to Settings → Battery → WLT-Adblocker",
                    "Battery optimization → Don't optimize",
                    "Settings → Apps → WLT → Battery → Allow background activity",
                ),
                critical = true,
            ))
        }
        manufacturer.contains("asus") -> {
            instructions.add(OemInstruction(
                title = "Asus/ZenUI: Auto-start Manager",
                steps = listOf(
                    "Go to Settings → Auto-start Manager",
                    "Find WLT-Adblocker → Allow",
                    "This prevents Asus from blocking VPN start",
                ),
            ))
        }
    }

    // Universal: Don't Kill My App reference
    instructions.add(OemInstruction(
        title = "Still having issues?",
        steps = listOf(
            "Visit dontkillmyapp.com for device-specific guides",
            "Consider using a Custom ROM with less aggressive battery management",
            "Report issues on GitHub: github.com/onlykushalll/WLT-AdBlocker/issues",
        ),
    ))

    return instructions
}
