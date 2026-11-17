package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.AppLanguage
import com.example.model.AuthSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.studioPrefsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "voice_pro_prefs",
)

class PreferencesStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private val KEY_LANGUAGE = stringPreferencesKey("language")
        private val KEY_AUTO_LOOP = booleanPreferencesKey("auto_loop")
        private val KEY_AUTH_SESSION = stringPreferencesKey("auth_session")
    }

    val languageFlow: Flow<AppLanguage> = context.studioPrefsDataStore.data.map { prefs ->
        AppLanguage.fromTag(prefs[KEY_LANGUAGE])
    }

    val autoLoopFlow: Flow<Boolean> = context.studioPrefsDataStore.data.map { prefs ->
        prefs[KEY_AUTO_LOOP] ?: false
    }

    val authSessionFlow: Flow<AuthSession> = context.studioPrefsDataStore.data.map { prefs ->
        val raw = prefs[KEY_AUTH_SESSION] ?: return@map AuthSession.SignedOut
        runCatching { json.decodeFromString<AuthSession>(raw) }.getOrDefault(AuthSession.SignedOut)
    }

    suspend fun saveLanguage(lang: AppLanguage) {
        context.studioPrefsDataStore.edit { prefs ->
            prefs[KEY_LANGUAGE] = lang.tag
        }
    }

    suspend fun saveAutoLoop(enabled: Boolean) {
        context.studioPrefsDataStore.edit { prefs ->
            prefs[KEY_AUTO_LOOP] = enabled
        }
    }

    suspend fun saveAuthSession(session: AuthSession) {
        context.studioPrefsDataStore.edit { prefs ->
            prefs[KEY_AUTH_SESSION] = json.encodeToString(AuthSession.serializer(), session)
        }
    }
}
