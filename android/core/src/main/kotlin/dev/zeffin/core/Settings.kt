// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 samartined
package dev.zeffin.core

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class ScreenOrientation { AUTO, HORIZONTAL }

data class FlipperChoice(val address: String, val name: String)

data class AppSettings(
    val flipper: FlipperChoice? = null,
    val autoStart: Boolean = true,
    val orientation: ScreenOrientation = ScreenOrientation.AUTO,
    /** The chat conversation open on Home, reopened next time. */
    val chatConversation: String? = null,
    /** In a conversation, the Flipper screen is shown large (the owner's last choice). */
    val chatScreenLarge: Boolean = false,
    /** Notify when a turn ends while the app is not on screen. */
    val notifyTurns: Boolean = true,
    /** Notify when the agent asks for permission while the app is not on screen. */
    val notifyPermissions: Boolean = true,
)

/** Ordinary settings (no secrets) in DataStore. The server and its tokens are the pairing record (PairingStore). */
class SettingsStore(private val store: DataStore<Preferences>) {
    private object K {
        val address = stringPreferencesKey("flipper_address")
        val name = stringPreferencesKey("flipper_name")
        val autoStart = booleanPreferencesKey("auto_start")
        val orientation = stringPreferencesKey("orientation")
        val chatConversation = stringPreferencesKey("chat_conversation")
        val chatScreenLarge = booleanPreferencesKey("chat_screen_large")
        val notifyTurns = booleanPreferencesKey("notify_turns")
        val notifyPermissions = booleanPreferencesKey("notify_permissions")
    }

    val settings: Flow<AppSettings> = store.data.map { p ->
        AppSettings(
            flipper = p[K.address]?.let { FlipperChoice(it, p[K.name] ?: it) },
            autoStart = p[K.autoStart] ?: true,
            orientation = p[K.orientation]?.let { runCatching { ScreenOrientation.valueOf(it) }.getOrNull() } ?: ScreenOrientation.AUTO,
            chatConversation = p[K.chatConversation],
            chatScreenLarge = p[K.chatScreenLarge] ?: false,
            notifyTurns = p[K.notifyTurns] ?: true,
            notifyPermissions = p[K.notifyPermissions] ?: true,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setFlipper(choice: FlipperChoice?) {
        store.edit {
            if (choice == null) {
                it.remove(K.address)
                it.remove(K.name)
            } else {
                it[K.address] = choice.address
                it[K.name] = choice.name
            }
        }
    }

    suspend fun setAutoStart(on: Boolean) {
        store.edit { it[K.autoStart] = on }
    }

    suspend fun setOrientation(value: ScreenOrientation) {
        store.edit { it[K.orientation] = value.name }
    }

    suspend fun setChatScreenLarge(large: Boolean) {
        store.edit { it[K.chatScreenLarge] = large }
    }

    suspend fun setNotifyTurns(on: Boolean) {
        store.edit { it[K.notifyTurns] = on }
    }

    suspend fun setNotifyPermissions(on: Boolean) {
        store.edit { it[K.notifyPermissions] = on }
    }

    suspend fun setChatConversation(id: String?) {
        store.edit { if (id == null) it.remove(K.chatConversation) else it[K.chatConversation] = id }
    }
}
