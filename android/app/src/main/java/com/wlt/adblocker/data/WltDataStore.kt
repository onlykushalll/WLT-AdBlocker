package com.wlt.adblocker.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "wlt_settings")

object WltDataStore {
    private lateinit var ctx: Context

    fun init(context: Context) {
        ctx = context.applicationContext
    }

    // --- Keys ---
    private val KEY_VPN_ENABLED = booleanPreferencesKey("vpn_enabled")
    private val KEY_DNS_LAYER = booleanPreferencesKey("layer_dns")
    private val KEY_SNI_LAYER = booleanPreferencesKey("layer_sni")
    private val KEY_HTTPS_LAYER = booleanPreferencesKey("layer_https")
    private val KEY_SCRIPTLET_LAYER = booleanPreferencesKey("layer_scriptlet")
    private val KEY_BLOCK_RESPONSE = intPreferencesKey("block_response")
    private val KEY_UPSTREAM_DNS = stringPreferencesKey("upstream_dns")
    private val KEY_AUTO_UPDATE = booleanPreferencesKey("auto_update_lists")
    private val KEY_THEME = stringPreferencesKey("theme")
    private val KEY_LAST_BLOCKLIST_UPDATE = intPreferencesKey("last_list_update")

    // --- Flows ---
    val vpnEnabled: Flow<Boolean> = ctx.dataStore.data.map { it[KEY_VPN_ENABLED] ?: false }
    val layerDns: Flow<Boolean> = ctx.dataStore.data.map { it[KEY_DNS_LAYER] ?: true }
    val layerSni: Flow<Boolean> = ctx.dataStore.data.map { it[KEY_SNI_LAYER] ?: false }
    val layerHttps: Flow<Boolean> = ctx.dataStore.data.map { it[KEY_HTTPS_LAYER] ?: false }
    val layerScriptlet: Flow<Boolean> = ctx.dataStore.data.map { it[KEY_SCRIPTLET_LAYER] ?: false }
    val blockResponse: Flow<Int> = ctx.dataStore.data.map { it[KEY_BLOCK_RESPONSE] ?: 0 }
    val upstreamDns: Flow<String> = ctx.dataStore.data.map { it[KEY_UPSTREAM_DNS] ?: "cloudflare" }
    val autoUpdate: Flow<Boolean> = ctx.dataStore.data.map { it[KEY_AUTO_UPDATE] ?: true }
    val theme: Flow<String> = ctx.dataStore.data.map { it[KEY_THEME] ?: "system" }

    // --- Setters ---
    suspend fun setVpnEnabled(v: Boolean) { ctx.dataStore.edit { it[KEY_VPN_ENABLED] = v } }
    suspend fun setLayerDns(v: Boolean) { ctx.dataStore.edit { it[KEY_DNS_LAYER] = v } }
    suspend fun setLayerSni(v: Boolean) { ctx.dataStore.edit { it[KEY_SNI_LAYER] = v } }
    suspend fun setLayerHttps(v: Boolean) { ctx.dataStore.edit { it[KEY_HTTPS_LAYER] = v } }
    suspend fun setLayerScriptlet(v: Boolean) { ctx.dataStore.edit { it[KEY_SCRIPTLET_LAYER] = v } }
    suspend fun setBlockResponse(v: Int) { ctx.dataStore.edit { it[KEY_BLOCK_RESPONSE] = v } }
    suspend fun setUpstreamDns(v: String) { ctx.dataStore.edit { it[KEY_UPSTREAM_DNS] = v } }
    suspend fun setAutoUpdate(v: Boolean) { ctx.dataStore.edit { it[KEY_AUTO_UPDATE] = v } }
    suspend fun setTheme(v: String) { ctx.dataStore.edit { it[KEY_THEME] = v } }
    suspend fun setLastUpdate(epoch: Int) { ctx.dataStore.edit { it[KEY_LAST_BLOCKLIST_UPDATE] = epoch } }
}
