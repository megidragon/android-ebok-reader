package com.lectora.app.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lectora.core.model.TranslationMode
import com.lectora.narration.NarrationSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

enum class ReaderTheme { SYSTEM, LIGHT, SEPIA, DARK }

data class ReaderSettings(
    val fontScale: Float = 1f,
    val theme: ReaderTheme = ReaderTheme.SYSTEM,
    val lineHeight: Float = 1.5f,
)

private val Context.dataStore by preferencesDataStore("settings")

class SettingsRepository(context: Context) {
    private val store = context.applicationContext.dataStore

    private object Keys {
        const val VOICE_PREFIX = "voice_"
        val rate = floatPreferencesKey("speech_rate")
        val pitch = floatPreferencesKey("pitch")
        val sentencePause = intPreferencesKey("sentence_pause")
        val paragraphPause = intPreferencesKey("paragraph_pause")
        val translationMode = stringPreferencesKey("translation_mode")
        val targetLanguage = stringPreferencesKey("target_language")
        val lookahead = intPreferencesKey("lookahead")
        val fontScale = floatPreferencesKey("font_scale")
        val theme = stringPreferencesKey("reader_theme")
        val lineHeight = floatPreferencesKey("line_height")
        val wifiOnly = booleanPreferencesKey("wifi_only")
    }

    val narration: Flow<NarrationSettings> = store.data.map { p -> p.toNarration() }.distinctUntilChanged()

    val reader: Flow<ReaderSettings> = store.data.map { p ->
        ReaderSettings(
            fontScale = p[Keys.fontScale] ?: 1f,
            theme = p[Keys.theme]?.let { runCatching { ReaderTheme.valueOf(it) }.getOrNull() } ?: ReaderTheme.SYSTEM,
            lineHeight = p[Keys.lineHeight] ?: 1.5f,
        )
    }.distinctUntilChanged()

    val wifiOnlyDownloads: Flow<Boolean> = store.data.map { it[Keys.wifiOnly] ?: false }.distinctUntilChanged()

    private fun Preferences.toNarration(): NarrationSettings {
        val defaults = NarrationSettings()
        val voices = asMap().entries
            .filter { it.key.name.startsWith(Keys.VOICE_PREFIX) }
            .associate { it.key.name.removePrefix(Keys.VOICE_PREFIX) to it.value.toString() }
        return NarrationSettings(
            voiceByLanguage = voices,
            speechRate = this[Keys.rate] ?: defaults.speechRate,
            pitch = this[Keys.pitch] ?: defaults.pitch,
            sentencePauseMs = this[Keys.sentencePause] ?: defaults.sentencePauseMs,
            paragraphPauseMs = this[Keys.paragraphPause] ?: defaults.paragraphPauseMs,
            translationMode = this[Keys.translationMode]?.let { runCatching { TranslationMode.valueOf(it) }.getOrNull() }
                ?: defaults.translationMode,
            targetLanguage = this[Keys.targetLanguage] ?: defaults.targetLanguage,
            lookaheadSeconds = this[Keys.lookahead] ?: defaults.lookaheadSeconds,
        )
    }

    suspend fun setVoice(language: String, voiceKey: String) =
        store.edit { it[stringPreferencesKey(Keys.VOICE_PREFIX + language)] = voiceKey }

    suspend fun setSpeechRate(value: Float) = store.edit { it[Keys.rate] = value }
    suspend fun setPitch(value: Float) = store.edit { it[Keys.pitch] = value }
    suspend fun setSentencePause(ms: Int) = store.edit { it[Keys.sentencePause] = ms }
    suspend fun setParagraphPause(ms: Int) = store.edit { it[Keys.paragraphPause] = ms }
    suspend fun setTranslationMode(mode: TranslationMode) = store.edit { it[Keys.translationMode] = mode.name }
    suspend fun setTargetLanguage(language: String) = store.edit { it[Keys.targetLanguage] = language }
    suspend fun setLookahead(seconds: Int) = store.edit { it[Keys.lookahead] = seconds }
    suspend fun setFontScale(value: Float) = store.edit { it[Keys.fontScale] = value }
    suspend fun setTheme(theme: ReaderTheme) = store.edit { it[Keys.theme] = theme.name }
    suspend fun setLineHeight(value: Float) = store.edit { it[Keys.lineHeight] = value }
    suspend fun setWifiOnly(value: Boolean) = store.edit { it[Keys.wifiOnly] = value }
}
