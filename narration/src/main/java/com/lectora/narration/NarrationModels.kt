package com.lectora.narration

import com.lectora.core.model.ReadingPosition
import com.lectora.core.model.TranslationMode
import com.lectora.engine.text.BookDocument

/** Preferencias que afectan a la narración. */
data class NarrationSettings(
    /** Voz elegida por idioma: "es" -> "system:com.google.android.tts|es-es-x-eee-local". */
    val voiceByLanguage: Map<String, String> = emptyMap(),
    val speechRate: Float = 1.0f,
    val pitch: Float = 1.0f,
    /** Pausa entre oraciones (ms), tras recortar el silencio que añade el motor. */
    val sentencePauseMs: Int = 180,
    val paragraphPauseMs: Int = 450,
    val translationMode: TranslationMode = TranslationMode.ORIGINAL,
    val targetLanguage: String = "es",
    /** Cuánto audio se sintetiza por delante de lo que suena. */
    val lookaheadSeconds: Int = 20,
) {
    /** ¿Cambiar de [this] a [other] obliga a regenerar el audio ya preparado? */
    fun affectsAudio(other: NarrationSettings): Boolean =
        voiceByLanguage != other.voiceByLanguage || speechRate != other.speechRate || pitch != other.pitch ||
            sentencePauseMs != other.sentencePauseMs || paragraphPauseMs != other.paragraphPauseMs ||
            translationMode != other.translationMode || targetLanguage != other.targetLanguage

    fun translates(sourceLanguage: String): Boolean =
        translationMode != TranslationMode.ORIGINAL && !sourceLanguage.equals(targetLanguage, ignoreCase = true)
}

/** Libro abierto para narrar. */
class NarrationBook(
    val bookId: Long,
    val title: String,
    val author: String?,
    val coverPath: String?,
    /** Idioma original del libro (código corto). */
    val language: String,
    val document: BookDocument,
    val savedPosition: ReadingPosition?,
)

/** La app provee los libros (base de datos + extractor). */
fun interface NarrationBookSource {
    suspend fun open(bookId: Long): NarrationBook
}

/** La app persiste el progreso. */
fun interface NarrationProgressSink {
    suspend fun save(bookId: Long, position: ReadingPosition, sectionCount: Int)
}

/** Oración que está sonando: posición + tramo dentro del texto mostrado del párrafo. */
data class Utterance(
    val position: ReadingPosition,
    val start: Int,
    val end: Int,
    /** El texto del párrafo que se narra es la traducción (y no el original). */
    val translated: Boolean,
    val text: String,
)

enum class NarrationStatus { IDLE, PREPARING, PLAYING, PAUSED, ENDED, ERROR }

sealed interface SleepTimer {
    data object Off : SleepTimer
    data class AtTime(val endsAtMillis: Long) : SleepTimer
    data object EndOfSection : SleepTimer
}

data class NarrationState(
    val status: NarrationStatus = NarrationStatus.IDLE,
    val bookId: Long? = null,
    val title: String? = null,
    val author: String? = null,
    val coverPath: String? = null,
    val sectionTitle: String? = null,
    val sectionCount: Int = 0,
    /** Posición desde donde continuará (o la oración actual si suena). */
    val position: ReadingPosition = ReadingPosition.START,
    val current: Utterance? = null,
    val voiceName: String? = null,
    val error: String? = null,
    val sleepTimer: SleepTimer = SleepTimer.Off,
) {
    val isActive: Boolean get() = status == NarrationStatus.PLAYING || status == NarrationStatus.PREPARING
}

/** La Application implementa esto para que el servicio obtenga el controlador. */
interface NarrationHost {
    val narrationController: NarrationController
}
