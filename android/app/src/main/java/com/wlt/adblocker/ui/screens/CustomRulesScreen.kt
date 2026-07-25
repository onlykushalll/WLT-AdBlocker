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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wlt.adblocker.data.RuleStore
import com.wlt.adblocker.filter.BlocklistManager
import com.wlt.adblocker.filter.Verdict

/**
 * Custom rules screen.
 *
 * Reads [RuleStore.customRules] StateFlow and renders each rule with visual
 * distinction (block = red tint, allow = green tint). Add dialog with
 * domain input + Block/Allow segmented toggle. Delete rules with a trash
 * icon. Add/remove calls [RuleStore.addRule] / [RuleStore.removeRule] —
 * changes are immediately visible to the VPN engine because
 * [KotlinBlockEngine.shouldBlock] checks RuleStore FIRST in its cascade.
 *
 * We ALSO push the rule to the BlocklistManager trie via
 * [BlocklistManager.addUserRule] so the change is reflected in the trie
 * immediately (RuleStore.checkCustomRule already handles it, but keeping
 * the trie in sync makes the BlocklistManager.current() snapshot accurate
 * for display in Settings).
 */
@Composable
fun CustomRulesScreen() {
    val context = LocalContext.current
    val ruleStore = remember { RuleStore.get(context) }
    val blocklistManager = remember { BlocklistManager(context) }
    val rules by ruleStore.customRules.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text = "Custom Rules",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.size(4.dp))
        Text(
            text = "Your rules override the blocklist. Block rules win " +
                "over allow rules (and over the allowlist).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.size(8.dp))
        InfoCard()
        Spacer(modifier = Modifier.size(8.dp))

        if (rules.isEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(
                    text = "No custom rules yet. Tap + to add one.",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(rules, key = { it.domain }) { rule ->
                    RuleRow(
                        rule = rule,
                        onDelete = {
                            ruleStore.removeRule(rule.domain)
                            // Mirror the trie mutation so the BlocklistManager
                            // snapshot stays consistent for display.
                            when (rule.type) {
                                RuleStore.RuleType.BLOCK ->
                                    blocklistManager.removeUserRule(rule.domain, Verdict.BLOCK)
                                RuleStore.RuleType.ALLOW ->
                                    blocklistManager.removeUserRule(rule.domain, Verdict.ALLOW)
                            }
                        },
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AddRuleDialog(
            onConfirm = { domain, type ->
                if (domain.contains('.')) {
                    ruleStore.addRule(domain, type)
                    when (type) {
                        RuleStore.RuleType.BLOCK ->
                            blocklistManager.addUserRule(domain.lowercase().trim(), Verdict.BLOCK)
                        RuleStore.RuleType.ALLOW ->
                            blocklistManager.addUserRule(domain.lowercase().trim(), Verdict.ALLOW)
                    }
                }
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false },
        )
    }

    // FAB for adding a rule. We use a Box overlay anchored bottom-end so
    // the FAB floats above the LazyColumn.
    androidx.compose.foundation.layout.Box(
        modifier = Modifier.fillMaxSize(),
    ) {
        FloatingActionButton(
            onClick = { showAddDialog = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) {
            Icon(Icons.Filled.Add, contentDescription = "Add rule")
        }
    }
}

@Composable
private fun RuleRow(
    rule: RuleStore.CustomRule,
    onDelete: () -> Unit,
) {
    val isBlock = rule.type == RuleStore.RuleType.BLOCK
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isBlock) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (isBlock) Icons.Filled.Block
                else Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = if (isBlock) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(modifier = Modifier.size(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = rule.domain,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = if (isBlock) MaterialTheme.colorScheme.onErrorContainer
                    else MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = if (isBlock) "Block" else "Allow (passthrough)",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isBlock) MaterialTheme.colorScheme.onErrorContainer
                    else MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Delete rule",
                    tint = if (isBlock) MaterialTheme.colorScheme.onErrorContainer
                    else MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun AddRuleDialog(
    onConfirm: (String, RuleStore.RuleType) -> Unit,
    onDismiss: () -> Unit,
) {
    var domain by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(RuleStore.RuleType.BLOCK) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add custom rule") },
        text = {
            Column {
                OutlinedTextField(
                    value = domain,
                    onValueChange = {
                        domain = it
                        error = null
                    },
                    label = { Text("Domain (e.g., ads.example.com)") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                )
                Spacer(modifier = Modifier.size(12.dp))
                Text("Type:")
                Spacer(modifier = Modifier.size(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { type = RuleStore.RuleType.BLOCK },
                        colors = if (type == RuleStore.RuleType.BLOCK) {
                            androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            )
                        } else {
                            androidx.compose.material3.ButtonDefaults.outlinedButtonColors()
                        },
                    ) { Text("Block") }
                    OutlinedButton(
                        onClick = { type = RuleStore.RuleType.ALLOW },
                        colors = if (type == RuleStore.RuleType.ALLOW) {
                            androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            androidx.compose.material3.ButtonDefaults.outlinedButtonColors()
                        },
                    ) { Text("Allow") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val trimmed = domain.trim().lowercase()
                if (trimmed.isEmpty()) {
                    error = "Domain can't be empty"
                } else if (!trimmed.contains('.')) {
                    error = "Domain must contain a dot (e.g., example.com)"
                } else {
                    onConfirm(trimmed, type)
                }
            }) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun InfoCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Rule precedence",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                text = "1. Your block rules (highest priority)\n" +
                    "2. Your allow rules\n" +
                    "3. Blocklist\n" +
                    "4. Game SDK detection\n" +
                    "5. DoH bypass prevention",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}
