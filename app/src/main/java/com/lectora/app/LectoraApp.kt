package com.lectora.app

import android.app.Application
import com.lectora.app.data.CachedParagraphTranslator
import com.lectora.app.data.DocumentCache
import com.lectora.app.data.LibraryRepository
import com.lectora.app.data.LibraryScanWorker
import com.lectora.app.data.SettingsRepository
import com.lectora.app.data.TextFilterRepository
import com.lectora.app.data.displayTitle
import kotlinx.coroutines.flow.first
import com.lectora.core.database.LectoraDatabase
import com.lectora.engine.text.BookOpener
import com.lectora.engine.translation.MlKitTranslationEngine
import com.lectora.engine.tts.TtsEngineRegistry
import com.lectora.engine.tts.neural.NeuralTtsProvider
import com.lectora.engine.tts.neural.NeuralVoiceInstaller
import com.lectora.engine.tts.neural.SherpaOnnxBackend
import com.lectora.engine.tts.system.SystemTtsProvider
import com.lectora.narration.NarrationBook
import com.lectora.narration.NarrationController
import com.lectora.narration.NarrationHost
import com.lectora.narration.prerender.NarrationRenderer
import com.lectora.narration.prerender.PrerenderStore
import kotlinx.coroutines.launch

/** Inyección de dependencias manual: un único contenedor por proceso. */
class AppContainer(app: Application) {
    val context: android.content.Context = app
    val database = LectoraDatabase.create(app)
    val opener = BookOpener(app)
    val textFilters = TextFilterRepository(app)
    val documents = DocumentCache(opener, textFilters)
    val settings = SettingsRepository(app)
    val translationEngine = MlKitTranslationEngine()
    val translator = CachedParagraphTranslator(database.translations(), translationEngine)
    val library = LibraryRepository(app, database, opener, translationEngine, documents)

    /**
     * Motores de voz: los del sistema + voces de IA en el dispositivo (Piper y Kokoro vía
     * sherpa-onnx), que se descargan desde Ajustes.
     */
    val neuralTts = NeuralTtsProvider(app, backends = listOf(SherpaOnnxBackend()))
    val voiceInstaller = NeuralVoiceInstaller(neuralTts.voicesDir)
    val ttsRegistry = TtsEngineRegistry(listOf(SystemTtsProvider(app), neuralTts))

    /** Narración pregenerada (audio guardado por páginas para voces pesadas). */
    val prerenderStore = PrerenderStore(java.io.File(app.filesDir, "prerender"))
    val renderer = NarrationRenderer(ttsRegistry, translator, prerenderStore)

    suspend fun openNarrationBook(bookId: Long): NarrationBook {
        val book = library.getBook(bookId) ?: error("Libro $bookId no encontrado")
        return NarrationBook(
            bookId = book.id,
            // Mismo título que se ve en la biblioteca (notificación, pantalla de bloqueo…).
            title = book.displayTitle(settings.titleSource.first()),
            author = book.author,
            coverPath = book.coverPath,
            language = book.language ?: "en",
            document = library.document(book),
            savedPosition = library.progress(book.id),
        )
    }

    val narration = NarrationController(
        context = app,
        books = { bookId -> openNarrationBook(bookId) },
        prerender = prerenderStore,
        progressSink = { bookId, position, sectionCount ->
            val paragraphs = library.getBook(bookId)?.let { library.document(it).paragraphs(position.section).size }
            library.saveProgress(bookId, position, sectionCount, paragraphs)
        },
        registry = ttsRegistry,
        translator = translator,
        settingsFlow = settings.narration,
    )
}

class LectoraApp : Application(), NarrationHost {
    lateinit var container: AppContainer
        private set

    override val narrationController: NarrationController get() = container.narration

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        LibraryScanWorker.schedulePeriodic(this)
        fixBookLanguagesOnce()
    }

    /** Una sola vez: corregir el idioma de libros importados con versiones anteriores. */
    private fun fixBookLanguagesOnce() {
        val prefs = getSharedPreferences("migrations", MODE_PRIVATE)
        if (prefs.getBoolean(KEY_LANG_REDETECT, false)) return
        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val changed = runCatching { container.library.redetectLanguages() }
            if (changed.isSuccess) prefs.edit().putBoolean(KEY_LANG_REDETECT, true).apply()
            android.util.Log.i("Lectora", "Revisión de idiomas: ${changed.getOrNull()} libros corregidos")
        }
    }

    private companion object {
        const val KEY_LANG_REDETECT = "lang_redetect_v1"
    }
}
