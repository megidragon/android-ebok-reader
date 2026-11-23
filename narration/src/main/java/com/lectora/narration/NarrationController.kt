package com.lectora.narration

import android.content.Context
import android.util.Log
import com.lectora.core.model.ReadingPosition
import com.lectora.engine.text.SentenceSpan
import com.lectora.engine.text.SentenceSplitter
import com.lectora.engine.text.TextCleaner
import com.lectora.engine.translation.ModelNotDownloadedException
import com.lectora.engine.translation.ParagraphTranslator
import com.lectora.engine.tts.PcmFormat
import com.lectora.engine.tts.PcmSink
import com.lectora.engine.tts.SilenceTrimmer
import com.lectora.engine.tts.SpeechSynthesizer
import com.lectora.engine.tts.SynthesisRequest
import com.lectora.engine.tts.TtsEngineRegistry
import com.lectora.engine.tts.VoiceInfo
import com.lectora.engine.tts.audio.AudioItem
import com.lectora.engine.tts.audio.GaplessAudioOutput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Orquesta la narración de un libro.
 *
 * Pipeline de una sesión (desde una posición hasta el final del libro):
 *
 * ```
 *  párrafos ──(traducción)──► PreparedParagraph ──(síntesis + recorte de silencios)──► AudioItem ──► AudioTrack
 *  [corrutina 1, 4 párrafos     [canal]           [corrutina 2, hasta N s por delante]   [canal]     [hilo de audio]
 *   por delante]
 * ```
 *
 * El texto fluye de forma continua a través de capítulos/páginas, así que no hay cortes al
 * cambiar de página. Todo corre dentro del proceso de la app y lo mantiene vivo
 * [PlaybackService] (servicio en primer plano), por eso funciona con la pantalla bloqueada o
 * con la app minimizada.
 */
class NarrationController(
    context: Context,
    private val books: NarrationBookSource,
    private val progressSink: NarrationProgressSink,
    private val registry: TtsEngineRegistry,
    private val translator: ParagraphTranslator,
    private val settingsFlow: Flow<NarrationSettings>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(NarrationState())
    val state: StateFlow<NarrationState> = _state.asStateFlow()

    private val guard = PlaybackGuard(context, onPauseRequested = { pauseInternal() }, onResumeRequested = { play() })

    private var book: NarrationBook? = null
    private var coverBytes: ByteArray? = null
    private var session: Session? = null
    private var lastSettings: NarrationSettings? = null
    private var sleepJob: Job? = null
    private var lastSavedParagraph: Pair<Int, Int>? = null

    /** Portada (para la notificación y la pantalla de bloqueo). */
    val artwork: ByteArray? get() = coverBytes

    init {
        scope.launch {
            settingsFlow.collect { new ->
                val old = lastSettings
                lastSettings = new
                // Voz nueva elegida: precargarla ya, no al darle a play.
                if (old != null && old.voiceByLanguage != new.voiceByLanguage) warmUp()
                // Cambió la voz, velocidad o traducción mientras suena: regenerar desde la oración actual.
                if (old != null && old.affectsAudio(new) && session != null) restartFromCurrent()
            }
        }
    }

    // ---------------- API pública (hilo principal) ----------------

    /** Carga un libro. Si [position] es null se usa el progreso guardado. */
    suspend fun load(bookId: Long, position: ReadingPosition? = null) {
        if (book?.bookId == bookId) {
            if (position != null) seekTo(position)
            return
        }
        stopSession()
        _state.update { NarrationState(status = NarrationStatus.PREPARING, bookId = bookId) }
        val opened = try {
            books.open(bookId)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "No se pudo abrir el libro $bookId", e)
            _state.update { it.copy(status = NarrationStatus.ERROR, error = "No se pudo abrir el libro") }
            return
        }
        // El documento pertenece a NarrationBookSource (la app lo comparte con el lector).
        book = opened
        coverBytes = withContext(Dispatchers.IO) {
            opened.coverPath?.let { runCatching { java.io.File(it).readBytes() }.getOrNull() }
        }
        val start = position ?: opened.savedPosition ?: ReadingPosition.START
        lastSavedParagraph = null
        _state.update {
            NarrationState(
                status = NarrationStatus.PAUSED,
                bookId = opened.bookId,
                title = opened.title,
                author = opened.author,
                coverPath = opened.coverPath,
                sectionTitle = opened.document.sectionTitle(start.section),
                sectionCount = opened.document.sectionCount,
                position = start,
            )
        }
    }

    fun play() {
        val b = book ?: return
        val s = session
        when {
            s != null && _state.value.status == NarrationStatus.PAUSED -> {
                if (!guard.onPlay()) return
                s.output.resume()
                _state.update { it.copy(status = NarrationStatus.PLAYING, error = null) }
            }
            s == null -> {
                val from = if (_state.value.status == NarrationStatus.ENDED) ReadingPosition.START else _state.value.position
                if (!guard.onPlay()) return
                startSession(b, from)
            }
        }
    }

    /** Pausa pedida por el usuario. */
    fun pause() {
        guard.clearAutoResume()
        pauseInternal()
    }

    fun togglePlayPause() {
        if (_state.value.isActive) pause() else play()
    }

    /** Detiene la narración y libera el audio, conservando el libro y la posición. */
    fun stop() {
        stopSession()
        guard.onStop()
        if (book != null) _state.update { it.copy(status = NarrationStatus.PAUSED, current = null) }
    }

    fun seekTo(position: ReadingPosition) {
        val b = book ?: return
        val wasPlaying = _state.value.isActive
        stopSession()
        _state.update {
            it.copy(
                position = position,
                current = null,
                sectionTitle = b.document.sectionTitle(position.section),
                status = if (wasPlaying) NarrationStatus.PREPARING else NarrationStatus.PAUSED,
            )
        }
        if (wasPlaying) startSession(b, position)
    }

    /** Salta [delta] párrafos (cruzando secciones si hace falta). */
    fun skipParagraphs(delta: Int) {
        val b = book ?: return
        scope.launch {
            var section = _state.value.position.section
            var paragraph = _state.value.position.paragraph + delta
            val doc = b.document
            while (true) {
                if (paragraph < 0) {
                    if (section == 0) {
                        paragraph = 0
                        break
                    }
                    section--
                    paragraph += doc.paragraphs(section).size
                    continue
                }
                val count = doc.paragraphs(section).size
                if (paragraph >= count) {
                    if (section >= doc.sectionCount - 1) {
                        paragraph = (count - 1).coerceAtLeast(0)
                        break
                    }
                    paragraph -= count
                    section++
                    continue
                }
                break
            }
            seekTo(ReadingPosition(section, paragraph, 0))
        }
    }

    fun skipSections(delta: Int) {
        val b = book ?: return
        val target = (_state.value.position.section + delta).coerceIn(0, b.document.sectionCount - 1)
        seekTo(ReadingPosition(target, 0, 0))
    }

    fun setSleepTimer(timer: SleepTimer) {
        sleepJob?.cancel()
        _state.update { it.copy(sleepTimer = timer) }
        if (timer is SleepTimer.AtTime) {
            sleepJob = scope.launch {
                delay((timer.endsAtMillis - System.currentTimeMillis()).coerceAtLeast(0))
                pause()
                _state.update { it.copy(sleepTimer = SleepTimer.Off) }
            }
        }
    }

    /**
     * Inicializa los motores de voz en segundo plano (conectar con el servicio TTS del sistema
     * tarda unos segundos la primera vez); así el primer "play" suena casi al instante.
     */
    fun warmUp() {
        scope.launch(Dispatchers.IO) {
            runCatching { registry.voices() }
            // Precargar también las voces elegidas (un modelo de IA tarda ~2-5 s en cargarse).
            val chosen = runCatching { settingsFlow.first().voiceByLanguage.values }.getOrDefault(emptyList())
            for (key in chosen) {
                val engineId = VoiceInfo.parseKey(key)?.first ?: continue
                runCatching { registry.engine(engineId)?.warmUp() }
            }
        }
    }

    /** Reproduce una frase de muestra con una voz (pantalla de ajustes). */
    suspend fun previewVoice(voice: VoiceInfo, text: String, rate: Float, pitch: Float) {
        if (_state.value.isActive) pause()
        val engine = registry.engine(voice.engineId) ?: return
        if (!engine.prepare()) return
        val output = GaplessAudioOutput(object : GaplessAudioOutput.Listener {
            override fun onMarkerReached(tag: Any) = Unit
        })
        val audio = Channel<AudioItem>(Channel.UNLIMITED)
        scope.launch(Dispatchers.Default) {
            var lastFormat: PcmFormat? = null
            var trimmer: SilenceTrimmer? = null
            runCatching {
                engine.synthesize(
                    SynthesisRequest(text, voice.locale, voice.id, rate, pitch),
                    object : PcmSink {
                        override fun onFormat(format: PcmFormat) {
                            if (format != lastFormat) audio.trySend(AudioItem.Format(format))
                            lastFormat = format
                            trimmer = SilenceTrimmer(format, { d, o, l -> audio.trySend(AudioItem.Pcm(d.copyOfRange(o, o + l))) })
                        }

                        override fun onPcm(data: ByteArray, offset: Int, length: Int) {
                            trimmer?.feed(data, offset, length)
                        }
                    },
                )
                trimmer?.finish()
            }
            audio.trySend(AudioItem.End)
            audio.close()
        }
        output.play(audio)
    }

    fun release() {
        stopSession()
        guard.onStop()
        book = null
    }

    // ---------------- Sesión de reproducción ----------------

    private class Session(
        val id: Long,
        val output: GaplessAudioOutput,
        val produced: AtomicLong = AtomicLong(0),
        val played: MutableStateFlow<Long> = MutableStateFlow(0),
    ) {
        lateinit var job: Job
    }

    private class PreparedParagraph(
        val section: Int,
        val paragraph: Int,
        val text: String,
        val sentences: List<SentenceSpan>,
        val startSentence: Int,
        val translated: Boolean,
        val endsSentence: Boolean,
        val lastInSection: Boolean,
    )

    private class UtteranceMarker(val sessionId: Long, val utterance: Utterance, val producedMs: Long)

    private var sessionCounter = 0L

    private fun startSession(b: NarrationBook, from: ReadingPosition) {
        stopSession()
        val id = ++sessionCounter
        val output = GaplessAudioOutput(object : GaplessAudioOutput.Listener {
            override fun onMarkerReached(tag: Any) {
                val marker = tag as? UtteranceMarker ?: return
                scope.launch { onUtteranceStarted(marker) }
            }
        })
        val s = Session(id, output)
        session = s
        _state.update { it.copy(status = NarrationStatus.PREPARING, error = null, position = from) }
        s.job = scope.launch {
            try {
                val settings = settingsFlow.first()
                lastSettings = settings
                val translate = settings.translates(b.language)
                val spokenLanguage = if (translate) settings.targetLanguage else b.language
                val resolved = withContext(Dispatchers.IO) {
                    registry.resolve(settings.voiceByLanguage[spokenLanguage], spokenLanguage)
                } ?: throw NarrationException("No hay ningún motor de voz disponible. Instala uno en Ajustes de Android.")
                val (engine, voice) = resolved
                _state.update { it.copy(voiceName = voice?.displayName ?: engine.displayName) }

                val prepared = Channel<PreparedParagraph>(capacity = 4)
                val audio = Channel<AudioItem>(Channel.UNLIMITED)
                launch(Dispatchers.Default) {
                    produceParagraphs(b, from, settings, translate, spokenLanguage, prepared)
                }
                launch(Dispatchers.Default) {
                    synthesize(s, prepared, audio, engine, voice, spokenLanguage, settings)
                }
                output.play(audio)
                // Fin natural del libro.
                if (session === s) {
                    session = null
                    guard.onStop()
                    _state.update { it.copy(status = NarrationStatus.ENDED, current = null) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Fallo en la narración", e)
                if (session === s) {
                    session = null
                    guard.onStop()
                    val message = when (e) {
                        is ModelNotDownloadedException ->
                            "Falta el modelo de traducción «${Locale(e.language).getDisplayLanguage(Locale("es"))}». Descárgalo en Ajustes › Traducción."
                        is NarrationException -> e.message
                        else -> "Error en la narración: ${e.message}"
                    }
                    _state.update { it.copy(status = NarrationStatus.ERROR, error = message, current = null) }
                }
            }
        }
    }

    private fun stopSession() {
        session?.let { it.job.cancel() }
        session = null
    }

    private fun pauseInternal() {
        val s = session ?: return
        s.output.pause()
        guard.onPause()
        _state.update { it.copy(status = NarrationStatus.PAUSED) }
        saveProgressNow()
    }

    private fun restartFromCurrent() {
        val b = book ?: return
        val position = _state.value.position
        val wasPlaying = _state.value.isActive
        stopSession()
        if (wasPlaying) startSession(b, position) else _state.update { it.copy(current = null) }
    }

    private fun onUtteranceStarted(marker: UtteranceMarker) {
        val s = session ?: return
        if (marker.sessionId != s.id) return
        s.played.value = marker.producedMs
        val u = marker.utterance
        Log.i(TAG, "▶ ${u.position.section}/${u.position.paragraph}/${u.position.sentence} adelanto=${s.produced.get() - marker.producedMs}ms «${u.text.take(40)}»")
        val previous = _state.value
        val sectionChanged = previous.current != null && previous.current.position.section != u.position.section
        _state.update {
            it.copy(
                status = if (it.status == NarrationStatus.PREPARING) NarrationStatus.PLAYING else it.status,
                current = u,
                position = u.position,
                sectionTitle = book?.document?.sectionTitle(u.position.section),
            )
        }
        if (sectionChanged && previous.sleepTimer == SleepTimer.EndOfSection) {
            pause()
            _state.update { it.copy(sleepTimer = SleepTimer.Off) }
            return
        }
        val key = u.position.section to u.position.paragraph
        if (key != lastSavedParagraph) {
            lastSavedParagraph = key
            saveProgressNow()
        }
    }

    private fun saveProgressNow() {
        val b = book ?: return
        val position = _state.value.position
        scope.launch(Dispatchers.IO) {
            runCatching { progressSink.save(b.bookId, position, b.document.sectionCount) }
        }
    }

    // ---------------- Etapa 1: texto (y traducción) por delante ----------------

    private suspend fun produceParagraphs(
        b: NarrationBook,
        from: ReadingPosition,
        settings: NarrationSettings,
        translate: Boolean,
        spokenLanguage: String,
        out: SendChannel<PreparedParagraph>,
    ) {
        val locale = Locale.forLanguageTag(spokenLanguage)
        var first = true
        try {
            for (section in from.section until b.document.sectionCount) {
                val paragraphs = b.document.paragraphs(section)
                val startParagraph = if (section == from.section) from.paragraph else 0
                for (p in startParagraph until paragraphs.size) {
                    val original = paragraphs[p]
                    val text = if (translate) {
                        translator.translate(b.bookId, section, p, original, b.language, settings.targetLanguage)
                    } else {
                        original
                    }
                    val spans = SentenceSplitter.split(text, locale)
                    if (spans.isEmpty()) continue
                    out.send(
                        PreparedParagraph(
                            section = section,
                            paragraph = p,
                            text = text,
                            sentences = spans,
                            startSentence = if (first) from.sentence.coerceIn(0, spans.size - 1) else 0,
                            translated = translate,
                            endsSentence = TextCleaner.endsSentence(original),
                            lastInSection = p == paragraphs.size - 1,
                        )
                    )
                    first = false
                }
            }
        } finally {
            out.close()
        }
    }

    // ---------------- Etapa 2: síntesis continua ----------------

    private suspend fun synthesize(
        s: Session,
        prepared: Channel<PreparedParagraph>,
        audio: SendChannel<AudioItem>,
        engine: SpeechSynthesizer,
        voice: VoiceInfo?,
        spokenLanguage: String,
        settings: NarrationSettings,
    ) {
        val locale = voice?.locale ?: Locale.forLanguageTag(spokenLanguage)
        val lookaheadMs = settings.lookaheadSeconds * 1000L
        val rate = settings.speechRate.coerceIn(0.3f, 4f)
        var lastFormat: PcmFormat? = null
        var consecutiveFailures = 0

        for (para in prepared) {
            for (i in para.startSentence until para.sentences.size) {
                // Control de lookahead: no adelantarse más de N segundos a lo que suena.
                s.played.first { s.produced.get() - it < lookaheadMs }

                val span = para.sentences[i]
                val text = span.of(para.text)
                val utterance = Utterance(
                    position = ReadingPosition(para.section, para.paragraph, i),
                    start = span.start,
                    end = span.end,
                    translated = para.translated,
                    text = text,
                )
                var markerSent = false
                var trimmer: SilenceTrimmer? = null
                val sink = object : PcmSink {
                    override fun onFormat(format: PcmFormat) {
                        if (format != lastFormat) {
                            audio.trySend(AudioItem.Format(format))
                            lastFormat = format
                        }
                        trimmer = SilenceTrimmer(format, { data, off, len ->
                            if (!markerSent) {
                                audio.trySend(AudioItem.Marker(UtteranceMarker(s.id, utterance, s.produced.get())))
                                markerSent = true
                            }
                            audio.trySend(AudioItem.Pcm(data.copyOfRange(off, off + len)))
                            s.produced.addAndGet(format.millisForBytes(len.toLong()))
                        })
                    }

                    override fun onPcm(data: ByteArray, offset: Int, length: Int) {
                        trimmer?.feed(data, offset, length)
                    }
                }
                try {
                    engine.synthesize(SynthesisRequest(text, locale, voice?.id, rate, settings.pitch), sink)
                    trimmer?.finish()
                    consecutiveFailures = 0
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "No se pudo sintetizar: \"${text.take(60)}\"", e)
                    if (++consecutiveFailures >= 5) throw NarrationException("El motor de voz falla repetidamente (${e.message}).")
                    continue
                }

                val isLast = i == para.sentences.size - 1
                val basePause = when {
                    !isLast -> settings.sentencePauseMs
                    para.lastInSection -> settings.paragraphPauseMs * 2
                    // Párrafo cortado por un salto de página: sin pausa, la frase continúa.
                    !para.endsSentence -> 0
                    else -> settings.paragraphPauseMs
                }
                val pause = (basePause / rate).toInt()
                if (markerSent && pause > 0) {
                    audio.send(AudioItem.Silence(pause))
                    s.produced.addAndGet(pause.toLong())
                }
            }
        }
        audio.send(AudioItem.End)
        audio.close()
    }

    private class NarrationException(message: String) : Exception(message)

    private companion object {
        const val TAG = "Narration"
    }
}
