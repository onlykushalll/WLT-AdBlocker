package com.wlt.adblocker.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore

private val Context.prefsDataStore by preferencesDataStore(name = "wlt_prefs")

/**
 * Simple preferences for first-launch detection and pause state.
 * Separate from WltDataStore to keep concerns isolated.
 */
object PrefsRepository {
    private val KEY_FIRST_LAUNCH = booleanPreferencesKey("first_launch_done")
    private val KEY_PAUSED_UNTIL = longPreferencesKey("paused_until")

    suspend fun isFirstLaunch(context: Context): Boolean {
        var result = true
        context.prefsDataStore.data.collect { prefs ->
            result = prefs[KEY_FIRST_LAUNCH] ?: true
            return@collect
        }
        return result
    }

    suspend fun setFirstLaunchDone(context: Context) {
        context.prefsDataStore.edit { it[KEY_FIRST_LAUNCH] = false }
    }

    suspend fun getPausedUntil(context: Context): Long {
        var result = 0L
        context.prefsDataStore.data.collect { prefs ->
            result = prefs[KEY_PAUSED_UNTIL] ?: 0L
            return@collect
        }
        return result
    }

    suspend fun setPausedUntil(context: Context, untilEpochMs: Long) {
        context.prefsDataStore.edit { it[KEY_PAUSED_UNTIL] = untilEpochMs }
    }

    suspend fun clearPause(context: Context) {
        context.prefsDataStore.edit { it.remove(KEY_PAUSED_UNTIL) }
    }
}
