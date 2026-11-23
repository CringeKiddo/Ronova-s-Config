package com.ronova.config.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.ronovaDataStore: DataStore<Preferences> by preferencesDataStore(name = "ronova_config")

class ConfigRepository private constructor(context: Context) {

    companion object {
        private val KEY_CONFIG = stringPreferencesKey("config_json")
        private val KEY_ACTIVE = stringPreferencesKey("active_config_json")

        @Volatile
        private var instance: ConfigRepository? = null

        fun get(context: Context): ConfigRepository =
            instance ?: synchronized(this) {
                instance ?: ConfigRepository(context.applicationContext).also { instance = it }
            }
    }

    private val store = context.applicationContext.ronovaDataStore

    val configFlow: Flow<ConfigState> =
        store.data.map { configFromJson(it[KEY_CONFIG]) }.distinctUntilChanged()

    /** The config that is currently applied to the device, or null when the engine is off. */
    val activeConfigFlow: Flow<ConfigState?> =
        store.data.map { prefs -> prefs[KEY_ACTIVE]?.let { configFromJson(it) } }.distinctUntilChanged()

    suspend fun load(): ConfigState = configFlow.first()

    suspend fun save(config: ConfigState) {
        store.edit { it[KEY_CONFIG] = config.toJson() }
    }

    suspend fun update(transform: (ConfigState) -> ConfigState): ConfigState {
        var result = ConfigState()
        store.edit { prefs ->
            result = transform(configFromJson(prefs[KEY_CONFIG]))
            prefs[KEY_CONFIG] = result.toJson()
        }
        return result
    }

    suspend fun loadActiveConfig(): ConfigState? = activeConfigFlow.first()

    suspend fun saveActiveConfig(config: ConfigState?) {
        store.edit { prefs ->
            if (config == null) prefs.remove(KEY_ACTIVE) else prefs[KEY_ACTIVE] = config.toJson()
        }
    }
}

/**
 * Process-wide engine status. ACTIVE survives app kills because it is restored from the
 * persisted active config; Starting/Stopping are transient and live only in memory.
 */
object EngineRuntime {
    private val _state = MutableStateFlow<EngineRuntimeState>(EngineRuntimeState.Idle)
    val state: StateFlow<EngineRuntimeState> = _state.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    /** Last human-readable result (errors or step summary) for the UI. */
    val message: StateFlow<String?> = _message.asStateFlow()

    fun set(newState: EngineRuntimeState) {
        _state.value = newState
    }

    fun setMessage(text: String?) {
        _message.value = text
    }

    /** Call once on app start so reopening the app immediately shows ACTIVE / DISABLE. */
    suspend fun restore(context: Context) {
        val active = ConfigRepository.get(context).loadActiveConfig()
        if (active != null && _state.value is EngineRuntimeState.Idle) {
            _state.value = EngineRuntimeState.Active
        }
    }
}
