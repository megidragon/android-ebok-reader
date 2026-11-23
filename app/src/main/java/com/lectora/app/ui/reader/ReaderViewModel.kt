package com.lectora.app.ui.reader

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lectora.app.AppContainer
import com.lectora.app.data.ReaderSettings
import com.lectora.app.data.ReaderTheme
import com.lectora.core.database.BookEntity
import com.lectora.core.model.BookFormat
import com.lectora.core.model.ReadingPosition
import com.lectora.core.model.TranslationMode
import com.lectora.engine.text.BookDocument
import com.lectora.engine.text.TocEntry
import com.lectora.engine.text.pdf.PdfPageRenderer
import com.lectora.engine.translation.ModelNotDownloadedException
import com.lectora.narration.NarrationSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale

data class ReaderUi(
    val loading: Boolean = true,
    val error: String? = null,
    val book: BookEntity? = null,
    val section: Int = 0,
    val sectionCount: Int = 0,
    val sectionTitle: String? = null,
    /** Título de la sección traducido (null mientras no esté listo). */
    val translatedTitle: String? = null,
    val paragraphs: List<String> = emptyList(),
    /** Traducción por índice de párrafo (se va completando). */
    val translations: Map<Int, String> = emptyMap(),
    val translating: Boolean = false,
    val translationError: String? = null,
    /** Párrafo al que desplazarse tras cargar la sección; cambia [scrollRequest] para forzarlo. */
    val scrollToParagraph: Int = 0,
    val scrollRequest: Int = 0,
) {
    val isPdf: Boolean get() = book?.format == BookFormat.PDF
}

class ReaderViewModel(private val container: AppContainer, private val bookId: Long) : ViewModel() {
    private val library = container.library
    private val _ui = MutableStateFlow(ReaderUi())
    val ui: StateFlow<ReaderUi> = _ui.asStateFlow()

    val readerSettings: StateFlow<ReaderSettings> =
        container.settings.reader.stateIn(viewModelScope, SharingStarted.Eagerly, ReaderSettings())
    val narrationSettings: StateFlow<NarrationSettings> =
        container.settings.narration.stateIn(viewModelScope, SharingStarted.Eagerly, NarrationSettings())

    private var document: BookDocument? = null
    private var translateJob: Job? = null
    private var saveJob: Job? = null
    /** Hay una sección cargándose: la lista aún muestra la anterior (o nada). */
    @Volatile private var sectionLoading = false
    private var pdfRenderer: PdfPageRenderer? = null
    private val pdfMutex = Mutex()

    init {
        viewModelScope.launch {
            val book = library.getBook(bookId)
            if (book == null) {
                _ui.update { it.copy(loading = false, error = "No se encontró el libro") }
                return@launch
            }
            val doc = runCatching { library.document(book) }.getOrElse { e ->
                _ui.update { it.copy(loading = false, error = "No se pudo abrir el archivo: ${e.message}") }
                return@launch
            }
            document = doc
            val position = library.progress(bookId) ?: ReadingPosition.START
            _ui.update { it.copy(book = book, sectionCount = doc.sectionCount) }
            loadSection(position.section.coerceIn(0, (doc.sectionCount - 1).coerceAtLeast(0)), position.paragraph)
            // Cargar el índice en segundo plano: en PDF añade el capítulo al título de cada página.
            launch {
                runCatching { doc.tableOfContents() }
                _ui.update { it.copy(sectionTitle = doc.sectionTitle(it.section)) }
            }
        }
        viewModelScope.launch {
            var previous: NarrationSettings? = null
            narrationSettings.collect { s ->
                val changed = previous != null &&
                    (previous!!.translationMode != s.translationMode || previous!!.targetLanguage != s.targetLanguage)
                previous = s
                if (changed) startTranslations()
            }
        }
    }

    val sourceLanguage: String get() = _ui.value.book?.language ?: "en"

    /**
     * Índice para el diálogo: los capítulos del propio libro (marcadores del PDF o índice del
     * EPUB). Si no trae índice y es corto, una entrada por sección; en libros enormes sin índice
     * se navega con "Ir a página".
     */
    suspend fun tableOfContents(): List<TocEntry> {
        val doc = document ?: return emptyList()
        val toc = runCatching { doc.tableOfContents() }.getOrDefault(emptyList())
        if (toc.isNotEmpty() || doc.sectionCount > 500) return toc
        return List(doc.sectionCount) { TocEntry(doc.sectionTitle(it) ?: "Sección ${it + 1}", it) }
    }

    fun loadSection(section: Int, scrollTo: Int = 0) {
        val doc = document ?: return
        if (section !in 0 until doc.sectionCount) return
        sectionLoading = true
        saveJob?.cancel()
        viewModelScope.launch {
            val paragraphs = try {
                doc.paragraphs(section)
            } finally {
                sectionLoading = false
            }
            _ui.update {
                it.copy(
                    loading = false,
                    section = section,
                    sectionTitle = doc.sectionTitle(section),
                    paragraphs = paragraphs,
                    translations = emptyMap(),
                    translatedTitle = null,
                    translationError = null,
                    scrollToParagraph = scrollTo.coerceIn(0, (paragraphs.size - 1).coerceAtLeast(0)),
                    scrollRequest = it.scrollRequest + 1,
                )
            }
            startTranslations()
            saveProgress(ReadingPosition(section, scrollTo, 0))
        }
    }

    fun nextSection() = loadSection(_ui.value.section + 1)
    fun previousSection() = loadSection(_ui.value.section - 1)

    /** Traduce la sección visible, empezando por los párrafos cacheados y siguiendo en orden. */
    private fun startTranslations() {
        translateJob?.cancel()
        val s = narrationSettings.value
        val ui = _ui.value
        if (!s.translates(sourceLanguage) || ui.paragraphs.isEmpty()) {
            _ui.update { it.copy(translations = emptyMap(), translatedTitle = null, translating = false, translationError = null) }
            return
        }
        translateJob = viewModelScope.launch {
            val section = ui.section
            val target = s.targetLanguage
            // Título de la sección (índice -1 en la caché para no chocar con los párrafos).
            ui.sectionTitle?.let { title ->
                val translatedTitle = runCatching {
                    container.translator.translate(bookId, section, TITLE_INDEX, title, sourceLanguage, target)
                }.getOrNull()
                _ui.update { if (it.section == section) it.copy(translatedTitle = translatedTitle) else it }
            }
            val result = HashMap<Int, String>()
            for ((i, p) in ui.paragraphs.withIndex()) {
                container.translator.cached(bookId, section, i, p, sourceLanguage, target)?.let { result[i] = it }
            }
            _ui.update { it.copy(translations = result.toMap(), translating = result.size < ui.paragraphs.size, translationError = null) }
            // Empezar por el párrafo visible para que lo que se lee aparezca antes.
            val start = ui.scrollToParagraph
            val order = (start until ui.paragraphs.size) + (0 until start)
            for (i in order) {
                if (result.containsKey(i)) continue
                try {
                    val t = container.translator.translate(bookId, section, i, ui.paragraphs[i], sourceLanguage, target)
                    result[i] = t
                    _ui.update { if (it.section == section) it.copy(translations = result.toMap()) else it }
                } catch (e: ModelNotDownloadedException) {
                    val lang = Locale.forLanguageTag(e.language).getDisplayLanguage(Locale.forLanguageTag("es"))
                    _ui.update { it.copy(translating = false, translationError = "Falta descargar el modelo de traducción: $lang") }
                    return@launch
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    _ui.update { it.copy(translationError = "Error de traducción: ${e.message}") }
                }
            }
            _ui.update { it.copy(translating = false) }
        }
    }

    fun setTranslationMode(mode: TranslationMode) {
        viewModelScope.launch { container.settings.setTranslationMode(mode) }
    }

    fun downloadMissingModels() {
        viewModelScope.launch {
            _ui.update { it.copy(translationError = "Descargando modelos de traducción…") }
            runCatching {
                val wifiOnly = false
                container.translationEngine.download(sourceLanguage, wifiOnly)
                container.translationEngine.download(narrationSettings.value.targetLanguage, wifiOnly)
            }.onFailure { e ->
                _ui.update { it.copy(translationError = "No se pudo descargar: ${e.message}") }
                return@launch
            }
            _ui.update { it.copy(translationError = null) }
            startTranslations()
        }
    }

    /** El usuario está viendo este párrafo (lectura manual): guardar progreso con un pequeño retraso. */
    fun onParagraphVisible(paragraph: Int, narrationOwnsProgress: Boolean) {
        if (narrationOwnsProgress) return
        // Mientras el libro se abre (segundos en PDF enormes) la lista está vacía y reporta el
        // párrafo 0: guardar eso pisaría el progreso real con la página 1.
        val ui = _ui.value
        if (ui.loading || ui.paragraphs.isEmpty() || sectionLoading) return
        saveProgress(ReadingPosition(ui.section, paragraph, 0), debounceMs = 1200)
    }

    private fun saveProgress(position: ReadingPosition, debounceMs: Long = 0) {
        val count = _ui.value.sectionCount
        val paragraphs = _ui.value.paragraphs.size
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            if (debounceMs > 0) delay(debounceMs)
            library.saveProgress(bookId, position, count, paragraphs)
        }
    }

    fun setFontScale(value: Float) = viewModelScope.launch { container.settings.setFontScale(value.coerceIn(0.7f, 2.2f)) }
    fun setTheme(theme: ReaderTheme) = viewModelScope.launch { container.settings.setTheme(theme) }
    fun setLineHeight(value: Float) = viewModelScope.launch { container.settings.setLineHeight(value) }

    // ---- Vista original de PDF ----

    suspend fun renderPdfPage(index: Int, width: Int): Bitmap? = pdfMutex.withLock {
        val book = _ui.value.book ?: return null
        if (book.format != BookFormat.PDF) return null
        withContext(Dispatchers.IO) {
            runCatching {
                val renderer = pdfRenderer ?: container.opener.openPdfRenderer(Uri.parse(book.uri)).also { pdfRenderer = it }
                renderer.render(index, width)
            }.getOrNull()
        }
    }

    private companion object {
        const val TITLE_INDEX = -1
    }

    override fun onCleared() {
        pdfRenderer?.close()
        pdfRenderer = null
    }
}
