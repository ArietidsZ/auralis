package com.dialect.interpreter.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.userSettingsStore: DataStore<Preferences> by preferencesDataStore(name = "auralis_settings")

/**
 * Persistent user preferences (spec 03 U03): source dialect, target language and
 * the selected voice profile survive process death; the transcript intentionally
 * does not (default no transcript persistence).
 */
class SettingsRepository(private val context: Context) {

    data class UserSettings(
        val sourceDialectId: String = DEFAULT_SOURCE_DIALECT_ID,
        val targetLanguageId: String = DEFAULT_TARGET_LANGUAGE_ID,
        val voiceProfileId: String? = null,
    )

    companion object {
        const val DEFAULT_SOURCE_DIALECT_ID = "sichuan"
        const val DEFAULT_TARGET_LANGUAGE_ID = "zh"

        private val KEY_SOURCE = stringPreferencesKey("source_dialect_id")
        private val KEY_TARGET = stringPreferencesKey("target_language_id")
        private val KEY_VOICE = stringPreferencesKey("voice_profile_id")
    }

    val settings: Flow<UserSettings> = context.userSettingsStore.data.map { prefs ->
        UserSettings(
            sourceDialectId = prefs[KEY_SOURCE] ?: DEFAULT_SOURCE_DIALECT_ID,
            targetLanguageId = prefs[KEY_TARGET] ?: DEFAULT_TARGET_LANGUAGE_ID,
            voiceProfileId = prefs[KEY_VOICE],
        )
    }

    suspend fun setSourceDialect(id: String) {
        context.userSettingsStore.edit { it[KEY_SOURCE] = id }
    }

    suspend fun setTargetLanguage(id: String) {
        context.userSettingsStore.edit { it[KEY_TARGET] = id }
    }

    suspend fun setVoiceProfile(id: String?) {
        context.userSettingsStore.edit { prefs ->
            if (id == null) prefs.remove(KEY_VOICE) else prefs[KEY_VOICE] = id
        }
    }
}
