package com.wlt.adblocker.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wlt.adblocker.data.RuleStore

@Composable
fun CustomRulesScreen() {
    val rules by RuleStore.customRules.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }

    val blockedCount = rules.count { it.isBlock }
    val allowedCount = rules.count { !it.isBlock }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add rule", tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Custom Rules", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(
                "$blockedCount block · $allowedCount allow · ${rules.size} total",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.padding(8.dp))
                    Column {
                        Text("How rules work", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text(
                            "Block rules override all lists. Allow rules (passthrough) skip all blocking. Custom rules are checked first — before any blocklist.",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (rules.isEmpty()) {
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("No custom rules yet", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        Text("Tap + to add a block or allow rule", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(rules) { rule ->
                        CustomRuleItem(rule) { RuleStore.removeRule(rule.domain) }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddRuleDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { domain, isBlock ->
                RuleStore.addRule(domain, isBlock)
                showAddDialog = false
            }
        )
    }
}

@Composable
private fun CustomRuleItem(rule: RuleStore.CustomRule, onDelete: () -> Unit) {
    val accentColor = if (rule.isBlock) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (rule.isBlock)
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.08f)
            else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.08f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (rule.isBlock) Icons.Filled.Block else Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.padding(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(rule.domain, fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, maxLines = 1)
                Text(if (rule.isBlock) "Block" else "Allow (passthrough)", fontSize = 10.sp, color = accentColor, fontWeight = FontWeight.Medium)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun AddRuleDialog(
    onDismiss: () -> Unit,
    onAdd: (domain: String, isBlock: Boolean) -> Unit
) {
    var domain by remember { mutableStateOf("") }
    var isBlock by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Custom Rule", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = domain,
                    onValueChange = { domain = it; error = "" },
                    label = { Text("Domain") },
                    placeholder = { Text("example.com") },
                    singleLine = true,
                    isError = error.isNotEmpty(),
                    supportingText = if (error.isNotEmpty()) { { Text(error) } } else null,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Rule type:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SingleChoiceSegmentedButtonRow {
                    SegmentedButton(
                        selected = isBlock,
                        onClick = { isBlock = true },
                        shape = SegmentedButtonDefaults.itemShape(0, 2)
                    ) {
                        Icon(Icons.Filled.Block, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.padding(4.dp))
                        Text("Block")
                    }
                    SegmentedButton(
                        selected = !isBlock,
                        onClick = { isBlock = false },
                        shape = SegmentedButtonDefaults.itemShape(1, 2)
                    ) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.padding(4.dp))
                        Text("Allow")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val d = domain.trim().lowercase().removePrefix("*.")
                if (d.isEmpty() || !d.contains(".")) {
                    error = "Enter a valid domain (e.g., ads.com)"
                } else {
                    onAdd(d, isBlock)
                }
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
