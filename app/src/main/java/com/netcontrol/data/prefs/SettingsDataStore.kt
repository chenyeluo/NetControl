package com.netcontrol.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.netcontrol.core.backend.BackendType
import com.netcontrol.core.backend.shizuku.FirewallUserService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class GlobalEmergencyMode {
    NORMAL,
    BLOCK_ALL,
    ALLOW_ALL
}

data class FirewallSettings(
    val firewallEnabled: Boolean = false,
    val preferredBackend: BackendType = BackendType.SHIZUKU,
    val shizukuDriverName: String = FirewallUserService.DRIVER_CONNECTIVITY_CHAIN3,
    val globalEmergencyMode: GlobalEmergencyMode = GlobalEmergencyMode.NORMAL,
    val blockUnknownWifi: Boolean = false,
    val gracefulDegradeOnUnreadableBssid: Boolean = true,
    val blockUnknownMobile: Boolean = false,
    val strongAlarmClockMode: Boolean = false,
    val splashBlockGlobalEnabled: Boolean = false,
    val gkdEngineEnabled: Boolean = true,
    val initialSeedCompleted: Boolean = false
)

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "netcontrol_settings"
)

class SettingsDataStore(private val context: Context) {

    private object Keys {
        val FIREWALL_ENABLED = booleanPreferencesKey("firewall_enabled")
        val PREFERRED_BACKEND = stringPreferencesKey("preferred_backend")
        val SHIZUKU_DRIVER = stringPreferencesKey("shizuku_driver")
        val EMERGENCY_MODE = stringPreferencesKey("global_emergency_mode")
        val BLOCK_UNKNOWN_WIFI = booleanPreferencesKey("block_unknown_wifi")
        val GRACEFUL_DEGRADE_BSSID = booleanPreferencesKey("graceful_degrade_bssid")
        val BLOCK_UNKNOWN_MOBILE = booleanPreferencesKey("block_unknown_mobile")
        val STRONG_ALARM_MODE = booleanPreferencesKey("strong_alarm_clock_mode")
        val SPLASH_BLOCK_GLOBAL = booleanPreferencesKey("splash_block_global_enabled")
        val GKD_ENGINE_ENABLED = booleanPreferencesKey("gkd_engine_enabled")
        val INITIAL_SEED_DONE = booleanPreferencesKey("initial_seed_completed")
    }

    val settingsFlow: Flow<FirewallSettings> = context.settingsDataStore.data.map { prefs ->
        FirewallSettings(
            firewallEnabled = prefs[Keys.FIREWALL_ENABLED] ?: false,
            preferredBackend = prefs[Keys.PREFERRED_BACKEND]
                ?.let { runCatching { BackendType.valueOf(it) }.getOrNull() }
                ?: BackendType.SHIZUKU,
            shizukuDriverName = prefs[Keys.SHIZUKU_DRIVER]
                ?: FirewallUserService.DRIVER_CONNECTIVITY_CHAIN3,
            globalEmergencyMode = prefs[Keys.EMERGENCY_MODE]
                ?.let { runCatching { GlobalEmergencyMode.valueOf(it) }.getOrNull() }
                ?: GlobalEmergencyMode.NORMAL,
            blockUnknownWifi = prefs[Keys.BLOCK_UNKNOWN_WIFI] ?: false,
            gracefulDegradeOnUnreadableBssid = prefs[Keys.GRACEFUL_DEGRADE_BSSID] ?: true,
            blockUnknownMobile = prefs[Keys.BLOCK_UNKNOWN_MOBILE] ?: false,
            strongAlarmClockMode = prefs[Keys.STRONG_ALARM_MODE] ?: false,
            splashBlockGlobalEnabled = prefs[Keys.SPLASH_BLOCK_GLOBAL] ?: false,
            gkdEngineEnabled = prefs[Keys.GKD_ENGINE_ENABLED] ?: true,
            initialSeedCompleted = prefs[Keys.INITIAL_SEED_DONE] ?: false
        )
    }

    suspend fun getSnapshot(): FirewallSettings = settingsFlow.first()

    suspend fun setFirewallEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.FIREWALL_ENABLED] = enabled }
    }

    suspend fun setPreferredBackend(backend: BackendType) {
        context.settingsDataStore.edit { it[Keys.PREFERRED_BACKEND] = backend.name }
    }

    suspend fun setShizukuDriver(driverName: String) {
        context.settingsDataStore.edit { it[Keys.SHIZUKU_DRIVER] = driverName }
    }

    suspend fun setGlobalEmergencyMode(mode: GlobalEmergencyMode) {
        context.settingsDataStore.edit { it[Keys.EMERGENCY_MODE] = mode.name }
    }

    suspend fun setUnknownWifiPolicy(blockUnknown: Boolean, gracefulDegrade: Boolean) {
        context.settingsDataStore.edit {
            it[Keys.BLOCK_UNKNOWN_WIFI] = blockUnknown
            it[Keys.GRACEFUL_DEGRADE_BSSID] = gracefulDegrade
        }
    }

    suspend fun setBlockUnknownMobile(blockUnknown: Boolean) {
        context.settingsDataStore.edit {
            it[Keys.BLOCK_UNKNOWN_MOBILE] = blockUnknown
        }
    }

    suspend fun setStrongAlarmClockMode(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.STRONG_ALARM_MODE] = enabled }
    }

    suspend fun setSplashBlockGlobalEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.SPLASH_BLOCK_GLOBAL] = enabled }
    }

    suspend fun setGkdEngineEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.GKD_ENGINE_ENABLED] = enabled }
    }

    suspend fun markInitialSeedCompleted() {
        context.settingsDataStore.edit { it[Keys.INITIAL_SEED_DONE] = true }
    }
}
