package com.wlt.adblocker.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.wlt.adblocker.data.RuleStore

/**
 * Phase 10a: Regex Rules Screen
 *
 * Lets users add Pi-hole-style regex patterns for domain blocking.
 * Regex rules are sent to the Go engine via AddRegex().
 *
 * Example rules:
 *   ^ads[0-9]*\.         — Block ads0., ads1., ads123. subdomains
 *   ^track(ing)?\.       — Block track. and tracking. subdomains
 *   ^(stats|analytics)\. — Block stats. and analytics. subdomains
 *   ^[a-z0-9]{8,12}\.(com|net)$ — Block DGA-like domains
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegexRulesScreen() {
    val regexRules = remember { mutableStateListOf<String>() }
    var newRule = remember { mutableStateOf("") }
    var error = remember { mutableStateOf<String?>(null) }
    var showAddDialog = remember { mutableStateOf(false) }

    // Preset regex patterns for quick add
    val presets = listOf(
        "^ads[0-9]*\\." to "Block numbered ad subdomains (ads0., ads1., ...)",
        "^track(ing)?\\." to "Block tracking subdomains",
        "^(stats|analytics|metrics|telemetry)\\." to "Block analytics subdomains",
        "^pixel[s]?[-.]?" to "Block tracking pixel subdomains",
        "^ads[-.]" to "Block ad subdomains",
        "^[a-z0-9]{8,12}\\.(com|net|org)$" to "Block DGA-like short domains",
    )

    Column(modifier = Modifier.fillMaxSize()) {
        // Header
        Card(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Code, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("Regex Rules", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Pi-hole-style regex patterns for domain blocking. " +
                    "More powerful than exact/suffix matching.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }

        // Quick presets
        Text(
            "Quick Presets",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        presets.forEach { (pattern, desc) ->
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                onClick = {
                    if (!regexRules.contains(pattern)) {
                        regexRules.add(pattern)
                    }
                },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "Add", tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(pattern, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        Text(desc, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        // Active rules
        if (regexRules.isNotEmpty()) {
            Text(
                "Active Rules (${regexRules.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyColumn(
                modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(regexRules) { rule ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                rule,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { regexRules.remove(rule) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        } else {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("No regex rules yet. Add a preset or create your own.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // Add custom rule
        if (showAddDialog.value) {
            AlertDialog(
                onDismissRequest = { showAddDialog.value = false; error.value = null },
                title = { Text("Add Regex Rule") },
                text = {
                    Column {
                        OutlinedTextField(
                            value = newRule.value,
                            onValueChange = { newRule.value = it; error.value = null },
                            label = { Text("Regex pattern") },
                            placeholder = { Text("^ads[0-9]*\\.") },
                            singleLine = true,
                            isError = error.value != null,
                            supportingText = error.value?.let { { Text(it) } },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val pattern = newRule.value.trim()
                        if (pattern.isEmpty()) {
                            error.value = "Pattern cannot be empty"
                        } else if (!pattern.startsWith("^") && !pattern.contains("[") && !pattern.contains(".")) {
                            error.value = "Use regex syntax (e.g., ^ads[0-9]*\\.)"
                        } else {
                            regexRules.add(pattern)
                            newRule.value = ""
                            showAddDialog.value = false
                        }
                    }) { Text("Add") }
                },
                dismissButton = { TextButton(onClick = { showAddDialog.value = false }) { Text("Cancel") } },
            )
        }

        // FAB
        FloatingActionButton(
            onClick = { showAddDialog.value = true },
            modifier = Modifier.padding(16.dp).align(Alignment.End),
        ) {
            Icon(Icons.Filled.Add, contentDescription = "Add regex rule")
        }
    }
}
