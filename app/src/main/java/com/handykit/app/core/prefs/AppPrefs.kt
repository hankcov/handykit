package com.handykit.app.core.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.handykit.app.noteDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val NOTE_ENABLED = booleanPreferencesKey("note_enabled")
private val LAST_VEHICLE = longPreferencesKey("last_vehicle_id")
private val LAST_MEMBER = stringPreferencesKey("last_scale_member")
private val AI_NETWORK_ENABLED = booleanPreferencesKey("ai_network_enabled")
private val MUSIC_WIFI_ONLY = booleanPreferencesKey("music_wifi_only")

class AppPrefs(private val context: Context) {
    private val ds = (context.applicationContext as android.app.Application).noteDataStore

    val noteEnabled: Flow<Boolean> = ds.data.map { it[NOTE_ENABLED] ?: false }
    val lastVehicleId: Flow<Long?> = ds.data.map { it[LAST_VEHICLE] }
    val lastMemberId: Flow<String?> = ds.data.map { it[LAST_MEMBER] }
    val aiNetworkEnabled: Flow<Boolean> = ds.data.map { it[AI_NETWORK_ENABLED] ?: true }
    val musicWifiOnly: Flow<Boolean> = ds.data.map { it[MUSIC_WIFI_ONLY] ?: true }

    suspend fun setNoteEnabled(v: Boolean) = ds.edit { it[NOTE_ENABLED] = v }
    suspend fun setLastVehicle(id: Long?) = ds.edit {
        if (id == null) it.remove(LAST_VEHICLE) else it[LAST_VEHICLE] = id
    }
    suspend fun setLastMember(id: String?) = ds.edit {
        if (id == null) it.remove(LAST_MEMBER) else it[LAST_MEMBER] = id
    }
    suspend fun setAiNetwork(v: Boolean) = ds.edit { it[AI_NETWORK_ENABLED] = v }
    suspend fun setMusicWifiOnly(v: Boolean) = ds.edit { it[MUSIC_WIFI_ONLY] = v }
}
