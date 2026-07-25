package com.wlt.adblocker.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * DataStore-backed preferences for app-level state that doesn't fit in
 * [RuleStore] (which is for user-editable rule data).
 *
 * Currently holds:
 *  - [isFirstLaunch] — true until the user completes onboarding. Gates the
 *    OnboardingScreen on app start.
 *  - [getPauseUntil] — epoch millis timestamp; the VPN is "paused" (allows
 *    everything through) until this time, 0 means "not paused".
 *
 * DataStore is preferred over SharedPreferences for the same reason
 * Google recommends it: atomic writes, no risk of `apply()` data loss
 * on crash, and coroutine-friendly. The VPN service reads the pause
 * timestamp on every query via a non-susending getter that returns the
 * last cached value (updated by a hot collector in the service).
 */
private val Context.prefsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "wlt_prefs"
)

class PrefsRepository(private val context: Context) {

    companion object {
        private val KEY_FIRST_LAUNCH = booleanPreferencesKey("first_launch")
        private val KEY_PAUSE_UNTIL = longPreferencesKey("pause_until")
        private val KEY_LAST_BLOCKLIST_UPDATE = longPreferencesKey("last_blocklist_update")

        @Volatile
        private var cachedPauseUntil: Long = 0L

        /** Returns the last-known pause timestamp without suspending.
         *  Updated by the VPN service's hot collector. Returns 0 if never set. */
        fun getCachedPauseUntil(): Long = cachedPauseUntil
    }

    // --- First-launch flag ---

    suspend fun isFirstLaunch(): Boolean =
        context.prefsDataStore.data.map { it[KEY_FIRST_LAUNCH] ?: true }.first()

    suspend fun setFirstLaunchDone() {
        context.prefsDataStore.edit { it[KEY_FIRST_LAUNCH] = false }
    }

    // --- Pause state ---

    suspend fun getPauseUntil(): Long {
        val v = context.prefsDataStore.data.map { it[KEY_PAUSE_UNTIL] ?: 0L }.first()
        cachedPauseUntil = v
        return v
    }

    suspend fun setPauseUntil(millis: Long) {
        cachedPauseUntil = millis
        context.prefsDataStore.edit { it[KEY_PAUSE_UNTIL] = millis }
    }

    /** Non-suspending snapshot of the cached pause timestamp. The VPN loop
     *  calls this on every query; we don't want a coroutine suspension
     *  (and the underlying DataStore read) per DNS packet. */
    fun pauseUntilSnapshot(): Long = cachedPauseUntil

    // --- Blocklist update tracking ---

    suspend fun getLastBlocklistUpdate(): Long =
        context.prefsDataStore.data.map { it[KEY_LAST_BLOCKLIST_UPDATE] ?: 0L }.first()

    suspend fun setLastBlocklistUpdate(millis: Long) {
        context.prefsDataStore.edit { it[KEY_LAST_BLOCKLIST_UPDATE] = millis }
    }
}
