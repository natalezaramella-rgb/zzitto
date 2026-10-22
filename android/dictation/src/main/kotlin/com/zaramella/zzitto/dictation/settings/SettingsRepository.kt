package com.zaramella.zzitto.dictation.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.zaramella.zzitto.engine.CorrectionMode
import com.zaramella.zzitto.engine.TranslateTarget
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Lettura e scrittura delle impostazioni (Spec §5.7). */
class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    val settings: Flow<DictationSettings> = dataStore.data
        .map { prefs -> prefs.toSettings() }
        .distinctUntilChanged()

    suspend fun update(transform: (DictationSettings) -> DictationSettings) {
        dataStore.edit { prefs ->
            val updated = transform(prefs.toSettings()).sanitized()
            prefs[Keys.LANGUAGE] = updated.language
            prefs[Keys.MODE] = updated.mode.code
            prefs[Keys.TRANSLATE] = updated.translate.code
            prefs[Keys.AUTO_STOP] = updated.autoStopOnSilence
            prefs[Keys.SILENCE_MS] = updated.silenceTimeoutMs
            prefs[Keys.MAX_DURATION_MS] = updated.maxDurationMs
            prefs[Keys.VOICE_COMMANDS] = updated.voiceCommandsEnabled
            prefs[Keys.HISTORY_ENABLED] = updated.historyEnabled
            prefs[Keys.HISTORY_RETENTION_DAYS] = updated.historyRetentionDays
            prefs[Keys.VIBRATE] = updated.vibrateOnStart
            prefs[Keys.SOUND] = updated.soundOnStart
            prefs[Keys.UNLOAD_MINUTES] = updated.unloadAfterMinutes
            prefs[Keys.TOUR_COMPLETED] = updated.tourCompleted
            prefs[Keys.ICON_X] = updated.iconX
            prefs[Keys.ICON_Y] = updated.iconY
        }
    }

    private fun Preferences.toSettings(): DictationSettings {
        val d = DictationSettings()
        return DictationSettings(
            language = this[Keys.LANGUAGE] ?: d.language,
            mode = this[Keys.MODE]?.let(CorrectionMode::fromCode) ?: d.mode,
            translate = this[Keys.TRANSLATE]?.let(TranslateTarget::fromCode) ?: d.translate,
            autoStopOnSilence = this[Keys.AUTO_STOP] ?: d.autoStopOnSilence,
            silenceTimeoutMs = this[Keys.SILENCE_MS] ?: d.silenceTimeoutMs,
            maxDurationMs = this[Keys.MAX_DURATION_MS] ?: d.maxDurationMs,
            voiceCommandsEnabled = this[Keys.VOICE_COMMANDS] ?: d.voiceCommandsEnabled,
            historyEnabled = this[Keys.HISTORY_ENABLED] ?: d.historyEnabled,
            historyRetentionDays = this[Keys.HISTORY_RETENTION_DAYS] ?: d.historyRetentionDays,
            vibrateOnStart = this[Keys.VIBRATE] ?: d.vibrateOnStart,
            soundOnStart = this[Keys.SOUND] ?: d.soundOnStart,
            unloadAfterMinutes = this[Keys.UNLOAD_MINUTES] ?: d.unloadAfterMinutes,
            tourCompleted = this[Keys.TOUR_COMPLETED] ?: d.tourCompleted,
            iconX = this[Keys.ICON_X] ?: d.iconX,
            iconY = this[Keys.ICON_Y] ?: d.iconY,
        ).sanitized()
    }

    private object Keys {
        val LANGUAGE = stringPreferencesKey("language")
        val MODE = intPreferencesKey("correction_mode")
        val TRANSLATE = intPreferencesKey("translate_target")
        val AUTO_STOP = booleanPreferencesKey("auto_stop")
        val SILENCE_MS = intPreferencesKey("silence_ms")
        val MAX_DURATION_MS = longPreferencesKey("max_duration_ms")
        val VOICE_COMMANDS = booleanPreferencesKey("voice_commands")
        val HISTORY_ENABLED = booleanPreferencesKey("history_enabled")
        val HISTORY_RETENTION_DAYS = intPreferencesKey("history_retention_days")
        val VIBRATE = booleanPreferencesKey("vibrate_on_start")
        val SOUND = booleanPreferencesKey("sound_on_start")
        val UNLOAD_MINUTES = intPreferencesKey("unload_after_minutes")
        val TOUR_COMPLETED = booleanPreferencesKey("tour_completed")
        val ICON_X = intPreferencesKey("icon_x")
        val ICON_Y = intPreferencesKey("icon_y")
    }
}
