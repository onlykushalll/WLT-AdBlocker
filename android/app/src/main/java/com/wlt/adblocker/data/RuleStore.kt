package com.wlt.adblocker.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide singleton holding custom rules and app firewall config.
 *
 * Both the UI (CustomRulesScreen, AppFirewallScreen) and the VPN service
 * read from this. The VPN service checks custom rules on every DNS query
 * and applies app firewall bypass via VpnService.Builder.addDisallowedApplication.
 */
object RuleStore {

    data class CustomRule(
        val domain: String,
        val isBlock: Boolean,
        val createdAt: Long = System.currentTimeMillis()
    )

    private val _customRules = MutableStateFlow<List<CustomRule>>(emptyList())
    val customRules: StateFlow<List<CustomRule>> = _customRules.asStateFlow()

    fun addRule(domain: String, isBlock: Boolean) {
        val d = domain.trim().lowercase().removePrefix("*.").removeSuffix(".")
        if (d.isEmpty() || !d.contains(".")) return
        val current = _customRules.value.toMutableList()
        current.removeAll { it.domain == d }
        current.add(CustomRule(d, isBlock))
        _customRules.value = current
    }

    fun removeRule(domain: String) {
        _customRules.value = _customRules.value.filterNot { it.domain == domain }
    }

    fun getBlockRules(): Set<String> = _customRules.value.filter { it.isBlock }.map { it.domain }.toSet()
    fun getAllowRules(): Set<String> = _customRules.value.filter { !it.isBlock }.map { it.domain }.toSet()

    fun checkCustomRule(domain: String): Boolean? {
        val d = domain.lowercase().trim('.')
        val rules = _customRules.value
        var allowMatch = false
        for (rule in rules) {
            if (d == rule.domain || d.endsWith(".${rule.domain}")) {
                if (rule.isBlock) return true
                allowMatch = true
            }
        }
        return if (allowMatch) false else null
    }

    private val _appFirewall = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val appFirewall: StateFlow<Map<String, Boolean>> = _appFirewall.asStateFlow()

    fun setAppBypass(packageName: String, bypass: Boolean) {
        val current = _appFirewall.value.toMutableMap()
        if (bypass) current[packageName] = true else current.remove(packageName)
        _appFirewall.value = current
    }

    fun getBypassApps(): Set<String> = _appFirewall.value.filter { it.value }.keys
    fun isBypassed(packageName: String): Boolean = _appFirewall.value[packageName] == true

    fun clearAll() {
        _customRules.value = emptyList()
        _appFirewall.value = emptyMap()
    }
}
