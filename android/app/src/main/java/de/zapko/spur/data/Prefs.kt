package de.zapko.spur.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

enum class StorageMode(val label: String, val hint: String) {
    BOTH("Handy + Server", "Jede Aufnahme bleibt auf dem Handy und wird zusätzlich auf deinen Server geladen."),
    CLOUD("Nur Server", "Nach dem erfolgreichen Upload wird die Datei vom Handy gelöscht. Vorschau bleibt."),
    LOCAL("Nur Handy", "Nichts wird hochgeladen. Achtung: Beim Deinstallieren der App ist alles weg."),
}

enum class MapStyle(val label: String) { DARK("Dunkel"), LIGHT("Hell"), OSM("Standard"), SATELLITE("Satellit") }

data class Settings(
    val loaded: Boolean = false,
    val onboarded: Boolean = false,
    val serverUrl: String = "",
    val token: String = "",
    val storage: StorageMode = StorageMode.BOTH,
    val wifiOnly: Boolean = false,
    val saveToGallery: Boolean = false,
    val openCamera: Boolean = true,
    val grid: Boolean = false,
    val volumeShutter: Boolean = true,
    val maxQuality: Boolean = false,
    val haptics: Boolean = true,
    val mapStyle: MapStyle = MapStyle.DARK,
    val appLock: Boolean = false,
    val showSnapchat: Boolean = true,
    val cursor: Long = 0,
) {
    val serverConfigured get() = serverUrl.isNotBlank() && token.isNotBlank()
    val uploads get() = serverConfigured && storage != StorageMode.LOCAL
}

private val Context.store by preferencesDataStore(name = "spur_settings")

class Prefs(private val ctx: Context, scope: CoroutineScope) {
    private object K {
        val onboarded = booleanPreferencesKey("onboarded")
        val serverUrl = stringPreferencesKey("server_url")
        val token = stringPreferencesKey("token")
        val storage = stringPreferencesKey("storage")
        val wifiOnly = booleanPreferencesKey("wifi_only")
        val saveToGallery = booleanPreferencesKey("save_gallery")
        val openCamera = booleanPreferencesKey("open_camera")
        val grid = booleanPreferencesKey("grid")
        val volumeShutter = booleanPreferencesKey("volume_shutter")
        val maxQuality = booleanPreferencesKey("max_quality")
        val haptics = booleanPreferencesKey("haptics")
        val mapStyle = stringPreferencesKey("map_style")
        val appLock = booleanPreferencesKey("app_lock")
        val showSnapchat = booleanPreferencesKey("show_snapchat")
        val cursor = longPreferencesKey("cursor")
    }

    private fun read(p: Preferences) = Settings(
        loaded = true,
        onboarded = p[K.onboarded] ?: false,
        serverUrl = p[K.serverUrl] ?: "",
        token = p[K.token] ?: "",
        storage = runCatching { StorageMode.valueOf(p[K.storage] ?: "BOTH") }.getOrDefault(StorageMode.BOTH),
        wifiOnly = p[K.wifiOnly] ?: false,
        saveToGallery = p[K.saveToGallery] ?: false,
        openCamera = p[K.openCamera] ?: true,
        grid = p[K.grid] ?: false,
        volumeShutter = p[K.volumeShutter] ?: true,
        maxQuality = p[K.maxQuality] ?: false,
        haptics = p[K.haptics] ?: true,
        mapStyle = runCatching { MapStyle.valueOf(p[K.mapStyle] ?: "DARK") }.getOrDefault(MapStyle.DARK),
        appLock = p[K.appLock] ?: false,
        showSnapchat = p[K.showSnapchat] ?: true,
        cursor = p[K.cursor] ?: 0L,
    )

    val flow: StateFlow<Settings> = ctx.store.data.map { read(it) }
        .stateIn(scope, SharingStarted.Eagerly, Settings())

    val current: Settings get() = flow.value

    suspend fun load(): Settings = read(ctx.store.data.first())

    suspend fun update(transform: (Settings) -> Settings) {
        ctx.store.edit { p ->
            val s = transform(read(p))
            p[K.onboarded] = s.onboarded
            p[K.serverUrl] = s.serverUrl.trim().trimEnd('/')
            p[K.token] = s.token.trim()
            p[K.storage] = s.storage.name
            p[K.wifiOnly] = s.wifiOnly
            p[K.saveToGallery] = s.saveToGallery
            p[K.openCamera] = s.openCamera
            p[K.grid] = s.grid
            p[K.volumeShutter] = s.volumeShutter
            p[K.maxQuality] = s.maxQuality
            p[K.haptics] = s.haptics
            p[K.mapStyle] = s.mapStyle.name
            p[K.appLock] = s.appLock
            p[K.showSnapchat] = s.showSnapchat
            p[K.cursor] = s.cursor
        }
    }
}
