package com.lectora.narration.prerender

import android.util.Log
import com.lectora.engine.text.SentenceSplitter
import com.lectora.engine.text.TextCleaner
import com.lectora.engine.translation.ParagraphTranslator
import com.lectora.engine.tts.PcmFormat
import com.lectora.engine.tts.PcmSink
import com.lectora.engine.tts.SilenceTrimmer
import com.lectora.engine.tts.SynthesisRequest
import com.lectora.engine.tts.TtsEngineRegistry
import com.lectora.narration.NarrationBook
import com.lectora.narration.NarrationSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.coroutineContext

/**
 * Pregenera la narración de un rango de secciones y la guarda en [PrerenderStore].
 *
 * Aplica exactamente las mismas reglas que la narración en vivo (traducción, división en
 * oraciones, recorte de silencios y pausas), de modo que reproducir lo pregenerado suena igual
 * y se puede encadenar con la narración en vivo sin cortes. Pensado para voces pesadas que no
 * llegan a tiempo real en el móvil.
 */
class NarrationRenderer(
    private val registry: TtsEngineRegistry,
    private val translator: ParagraphTranslator,
    private val store: PrerenderStore,
) {
    data class Progress(val done: Int, val total: Int, val section: Int, val audioMs: Long, val elapsedMs: Long)

    /** Variante con la que se renderizaría ahora (para mostrar qué voz/idioma se usará). */
    suspend fun variantFor(book: NarrationBook, settings: NarrationSettings): Pair<PrerenderVariant, String>? {
        val translate = settings.translates(book.language)
        val spoken = if (translate) settings.targetLanguage else book.language
        val (engine, voice) = registry.resolve(settings.voiceByLanguage[spoken], spoken) ?: return null
        return PrerenderVariant.of(settings, voice?.key ?: engine.engineId, spoken, translate) to (voice?.displayName ?: engine.displayName)
    }

    /**
     * Renderiza [count] secciones desde [fromSection]. Las ya pregeneradas con la misma variante
     * se saltan, así que se puede reanudar un trabajo interrumpido.
     */
    suspend fun render(
        book: NarrationBook,
        settings: NarrationSettings,
        fromSection: Int,
        count: Int,
        onProgress: suspend (Progress) -> Unit,
    ): Int = withContext(Dispatchers.Default) {
        val translate = settings.translates(book.language)
        val spoken = if (translate) settings.targetLanguage else book.language
        val (engine, voice) = registry.resolve(settings.voiceByLanguage[spoken], spoken)
            ?: throw IllegalStateException("No hay ningún motor de voz disponible")
        engine.warmUp()
        val variant = PrerenderVariant.of(settings, voice?.key ?: engine.engineId, spoken, translate)
        val voiceName = voice?.displayName ?: engine.displayName
        val locale = voice?.locale ?: Locale.forLanguageTag(spoken)
        val rate = settings.speechRate.coerceIn(0.3f, 4f)
        val last = minOf(book.document.sectionCount, fromSection + count)
        val total = (last - fromSection).coerceAtLeast(0)
        var done = 0
        var totalAudioMs = 0L
        val started = System.currentTimeMillis()

        for (section in fromSection until last) {
            coroutineContext.ensureActive()
            if (store.has(book.bookId, variant, section)) {
                done++
                onProgress(Progress(done, total, section, totalAudioMs, System.currentTimeMillis() - started))
                continue
            }
            val partial = store.newSectionAudioFile(book.bookId, variant, voiceName, section)
            var writer: AacFileWriter? = null
            val utterances = ArrayList<RenderedUtterance>()
            try {
                val paragraphs = book.document.paragraphs(section)
                for ((p, original) in paragraphs.withIndex()) {
                    val text = if (translate) {
                        translator.translate(book.bookId, section, p, original, book.language, settings.targetLanguage)
                    } else {
                        original
                    }
                    val spans = SentenceSplitter.split(text, Locale.forLanguageTag(spoken))
                    for ((i, span) in spans.withIndex()) {
                        coroutineContext.ensureActive()
                        val sentence = span.of(text)
                        var started_ = false
                        var trimmer: SilenceTrimmer? = null
                        val sink = object : PcmSink {
                            override fun onFormat(format: PcmFormat) {
                                if (writer == null) writer = AacFileWriter(partial, format.sampleRate)
                                trimmer = SilenceTrimmer(format, { data, off, len ->
                                    val w = writer!!
                                    if (!started_) {
                                        utterances.add(RenderedUtterance(p, i, span.start, span.end, sentence, translate, w.writtenMs))
                                        started_ = true
                                    }
                                    w.write(data, off, len)
                                })
                            }

                            override fun onPcm(data: ByteArray, offset: Int, length: Int) {
                                trimmer?.feed(data, offset, length)
                            }
                        }
                        try {
                            engine.synthesize(SynthesisRequest(sentence, locale, voice?.id, rate, settings.pitch), sink)
                            trimmer?.finish()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "No se pudo sintetizar: \"${sentence.take(60)}\"", e)
                            continue
                        }
                        val isLast = i == spans.size - 1
                        val basePause = when {
                            !isLast -> settings.sentencePauseMs
                            p == paragraphs.size - 1 -> settings.paragraphPauseMs * 2
                            !TextCleaner.endsSentence(original) -> 0
                            else -> settings.paragraphPauseMs
                        }
                        if (started_) writer?.writeSilence((basePause / rate).toInt())
                    }
                }
                val w = writer ?: AacFileWriter(partial, 22050).also { it.writeSilence(50) }
                val duration = w.writtenMs
                w.finish()
                writer = null
                store.commitSection(book.bookId, variant, section, partial, utterances, duration)
                totalAudioMs += duration
            } catch (e: Throwable) {
                runCatching { writer?.close() }
                partial.delete()
                throw e
            }
            done++
            onProgress(Progress(done, total, section, totalAudioMs, System.currentTimeMillis() - started))
        }
        done
    }

    private companion object {
        const val TAG = "Prerender"
    }
}
