package com.lectora.engine.tts

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale

/** Reúne los motores de todos los proveedores y resuelve voces por clave o idioma. */
class TtsEngineRegistry(private val providers: List<SpeechEngineProvider>) {
    private val mutex = Mutex()
    private var cached: List<SpeechSynthesizer>? = null

    suspend fun engines(refresh: Boolean = false): List<SpeechSynthesizer> = mutex.withLock {
        if (refresh || cached == null) {
            val previous = cached.orEmpty().associateBy { it.engineId }
            val fresh = providers.flatMap { runCatching { it.engines() }.getOrDefault(emptyList()) }
            // Reutilizar las instancias existentes: la narración en curso puede estar usándolas.
            val merged = fresh.map { previous[it.engineId] ?: it }
            val keptIds = merged.map { it.engineId }.toSet()
            previous.values.filter { it.engineId !in keptIds }.forEach { it.release() }
            cached = merged
        }
        cached!!
    }

    suspend fun engine(engineId: String): SpeechSynthesizer? = engines().firstOrNull { it.engineId == engineId }

    /** Todas las voces disponibles, opcionalmente filtradas por idioma (solo el código). */
    suspend fun voices(language: String? = null, includeNetwork: Boolean = false): List<VoiceInfo> =
        engines().flatMap { engine ->
            if (!engine.prepare()) emptyList() else runCatching { engine.voices() }.getOrDefault(emptyList())
        }.filter { v ->
            (language == null || v.locale.language == Locale(language).language) &&
                v.installed && (includeNetwork || !v.requiresNetwork)
        }.sortedWith(compareByDescending<VoiceInfo> { it.quality }.thenBy { it.displayName })

    /**
     * Resuelve qué motor y voz usar: la preferida si existe, si no la mejor voz offline del idioma.
     */
    suspend fun resolve(preferredKey: String?, language: String): Pair<SpeechSynthesizer, VoiceInfo?>? {
        preferredKey?.let(VoiceInfo::parseKey)?.let { (engineId, voiceId) ->
            val engine = engine(engineId)
            if (engine != null && engine.prepare()) {
                val voice = runCatching { engine.voices() }.getOrDefault(emptyList()).firstOrNull { it.id == voiceId }
                if (voice != null && voice.locale.language == Locale(language).language) return engine to voice
            }
        }
        val best = voices(language).firstOrNull()
        if (best != null) engine(best.engineId)?.let { return it to best }
        // Último recurso: el primer motor disponible con el idioma por defecto.
        val fallback = engines().firstOrNull { it.prepare() } ?: return null
        return fallback to null
    }

    fun release() {
        cached?.forEach { it.release() }
        cached = null
    }
}
