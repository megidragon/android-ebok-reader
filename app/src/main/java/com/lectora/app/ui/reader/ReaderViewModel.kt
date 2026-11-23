package com.lectora.app.ui.reader

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lectora.app.AppContainer
import com.lectora.app.data.PageMode
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale

/** Contenido ya cargado de una sección (capítulo o página), con su traducción parcial. */
data class SectionContent(
    val section: Int,
    val title: String?,
    val paragraphs: List<String>,
    /** Traducción por índice de párrafo (se va completando). */
    val translations: Map<Int, String> = emptyMap(),
    /** Título traducido (null mientras no esté listo). */
    val translatedTitle: String? = null,
)

data class ReaderUi(
    val loading: Boolean = true,
    val error: String? = null,
    val book: BookEntity? = null,
    /** Sección "actual": la que se ve (o la primera visible en scroll continuo). */
    val section: Int = 0,
    val sectionCount: Int = 0,
    val sectionTitle: String? = null,
    val paragraphs: List<String> = emptyList(),
    val translating: Boolean = false,
    val translationError: String? = null,
    /** Párrafo al que desplazarse tras un salto; cambia [scrollRequest] para forzarlo. */
    val scrollToParagraph: Int = 0,
    val scrollRequest: Int = 0,
) {
    val isPdf: Boolean get() = book?.format == BookFormat.PDF
}

class ReaderViewModel(private val container: AppContainer, private val bookId: Long) : ViewModel() {
    private val library = container.library
    private val _ui = MutableStateFlow(ReaderUi())
    val ui: StateFlow<ReaderUi> = _ui.asStateFlow()

    /**
     * Secciones cargadas (la actual y sus vecinas). Las usan los modos "deslizar" y "scroll
     * continuo", que muestran varias secciones a la vez; se mantienen acotadas en memoria.
     */
    private val _sections = MutableStateFlow<Map<Int, SectionContent>>(emptyMap())
    val sections: StateFlow<Map<Int, SectionContent>> = _sections.asStateFlow()

    val readerSettings: StateFlow<ReaderSettings> =
        container.settings.reader.stateIn(viewModelScope, SharingStarted.Eagerly, ReaderSettings())
    val narrationSettings: StateFlow<NarrationSettings> =
        container.settings.narration.stateIn(viewModelScope, SharingStarted.Eagerly, NarrationSettings())

    private var document: BookDocument? = null
    private val loadJobs = HashMap<Int, Job>()
    private val translateJobs = HashMap<Int, Job>()
    private var saveJob: Job? = null
    /** Hay una sección cargándose tras un salto: la lista aún muestra la anterior (o nada). */
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
                _sections.update { map -> map.mapValues { (s, c) -> c.copy(title = doc.sectionTitle(s)) } }
            }
        }
        viewModelScope.launch {
            var previous: NarrationSettings? = null
            narrationSettings.collect { s ->
                val changed = previous != null &&
                    (previous!!.translationMode != s.translationMode || previous!!.targetLanguage != s.targetLanguage)
                previous = s
                if (changed) restartTranslations()
            }
        }
    }

    val sourceLanguage: String get() = _ui.value.book?.language ?: "en"

    private val translates: Boolean get() = narrationSettings.value.translates(sourceLanguage)

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

    // ---------------- Carga de secciones ----------------

    /** Pide (sin esperar) el contenido de una sección: la UI lo recibe por [sections]. */
    fun requestSection(section: Int, priorityParagraph: Int = 0) {
        val doc = document ?: return
        if (section !in 0 until doc.sectionCount) return
        if (_sections.value.containsKey(section)) {
            ensureTranslations(section, priorityParagraph)
            return
        }
        if (loadJobs[section]?.isActive == true) return
        loadJobs[section] = viewModelScope.launch {
            val paragraphs = doc.paragraphs(section)
            _sections.update { it + (section to SectionContent(section, doc.sectionTitle(section), paragraphs)) }
            ensureTranslations(section, priorityParagraph)
            evictFarSections()
        }
    }

    private suspend fun awaitSection(section: Int): SectionContent? {
        requestSection(section)
        return _sections.mapNotNull { it[section] }.first()
    }

    /** Salto explícito a una sección (índice, narración, botones). */
    fun loadSection(section: Int, scrollTo: Int = 0) {
        val doc = document ?: return
        if (section !in 0 until doc.sectionCount) return
        sectionLoading = true
        saveJob?.cancel()
        viewModelScope.launch {
            val content = try {
                awaitSection(section)
            } finally {
                sectionLoading = false
            } ?: return@launch
            _ui.update {
                it.copy(
                    loading = false,
                    section = section,
                    sectionTitle = content.title,
                    paragraphs = content.paragraphs,
                    translationError = null,
                    scrollToParagraph = scrollTo.coerceIn(0, (content.paragraphs.size - 1).coerceAtLeast(0)),
                    scrollRequest = it.scrollRequest + 1,
                )
            }
            ensureTranslations(section, scrollTo)
            prefetchNeighbours(section)
            saveProgress(ReadingPosition(section, scrollTo, 0))
        }
    }

    fun nextSection() = loadSection(_ui.value.section + 1)
    fun previousSection() = loadSection(_ui.value.section - 1)

    /**
     * El usuario llegó a otra sección deslizando o desplazándose (sin salto): actualizar la
     * sección actual, el título y el progreso, sin mover la vista.
     */
    fun onSectionVisible(section: Int, paragraph: Int, narrationOwnsProgress: Boolean) {
        val doc = document ?: return
        if (section !in 0 until doc.sectionCount) return
        if (section != _ui.value.section) {
            val content = _sections.value[section]
            _ui.update {
                it.copy(
                    section = section,
                    sectionTitle = content?.title ?: doc.sectionTitle(section),
                    paragraphs = content?.paragraphs ?: emptyList(),
                )
            }
            requestSection(section, paragraph)
            prefetchNeighbours(section)
        }
        onParagraphVisible(paragraph, narrationOwnsProgress)
    }

    private fun prefetchNeighbours(section: Int) {
        requestSection(section + 1)
        requestSection(section - 1)
    }

    /** Mantiene en memoria solo las secciones cercanas a la actual. */
    private fun evictFarSections() {
        val current = _ui.value.section
        val keep = _sections.value.keys.sortedBy { kotlin.math.abs(it - current) }.take(MAX_SECTIONS).toSet()
        if (keep.size == _sections.value.size) return
        (_sections.value.keys - keep).forEach { s -> translateJobs.remove(s)?.cancel() }
        _sections.update { it.filterKeys { s -> s in keep } }
    }

    // ---------------- Traducción ----------------

    private fun restartTranslations() {
        translateJobs.values.forEach { it.cancel() }
        translateJobs.clear()
        _sections.update { map -> map.mapValues { (_, c) -> c.copy(translations = emptyMap(), translatedTitle = null) } }
        _ui.update { it.copy(translating = false, translationError = null) }
        val current = _ui.value.section
        listOf(current, current + 1, current - 1).forEach { ensureTranslations(it, if (it == current) _ui.value.scrollToParagraph else 0) }
    }

    /** Traduce una sección: primero lo cacheado, luego desde [priorityParagraph] en adelante. */
    private fun ensureTranslations(section: Int, priorityParagraph: Int = 0) {
        if (!translates) return
        val content = _sections.value[section] ?: return
        if (translateJobs[section]?.isActive == true) return
        if (content.paragraphs.isNotEmpty() && content.translations.size >= content.paragraphs.size && content.translatedTitle != null) return
        val target = narrationSettings.value.targetLanguage
        val source = sourceLanguage
        translateJobs[section] = viewModelScope.launch {
            fun publish(transform: (SectionContent) -> SectionContent) {
                _sections.update { map -> map[section]?.let { map + (section to transform(it)) } ?: map }
            }
            content.title?.let { title ->
                val translatedTitle = runCatching {
                    container.translator.translate(bookId, section, TITLE_INDEX, title, source, target)
                }.getOrNull()
                publish { it.copy(translatedTitle = translatedTitle) }
            }
            val result = HashMap<Int, String>()
            for ((i, p) in content.paragraphs.withIndex()) {
                container.translator.cached(bookId, section, i, p, source, target)?.let { result[i] = it }
            }
            publish { it.copy(translations = result.toMap()) }
            if (section == _ui.value.section) {
                _ui.update { it.copy(translating = result.size < content.paragraphs.size, translationError = null) }
            }
            val start = priorityParagraph.coerceIn(0, content.paragraphs.size)
            val order = (start until content.paragraphs.size) + (0 until start)
            for (i in order) {
                if (result.containsKey(i)) continue
                try {
                    result[i] = container.translator.translate(bookId, section, i, content.paragraphs[i], source, target)
                    publish { it.copy(translations = result.toMap()) }
                } catch (e: ModelNotDownloadedException) {
                    val lang = Locale.forLanguageTag(e.language).getDisplayLanguage(Locale.forLanguageTag("es"))
                    _ui.update { it.copy(translating = false, translationError = "Falta descargar el modelo de traducción: $lang") }
                    return@launch
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    _ui.update { it.copy(translationError = "Error de traducción: ${e.message}") }
                }
            }
            if (section == _ui.value.section) _ui.update { it.copy(translating = false) }
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
            restartTranslations()
        }
    }

    // ---------------- Progreso ----------------

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

    // ---------------- Apariencia ----------------

    fun setFontScale(value: Float) = viewModelScope.launch { container.settings.setFontScale(value.coerceIn(0.7f, 2.2f)) }
    fun setTheme(theme: ReaderTheme) = viewModelScope.launch { container.settings.setTheme(theme) }
    fun setLineHeight(value: Float) = viewModelScope.launch { container.settings.setLineHeight(value) }
    fun setPageMode(mode: PageMode) = viewModelScope.launch { container.settings.setPageMode(mode) }

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
        /** Secciones en memoria a la vez (scroll continuo/deslizar). */
        const val MAX_SECTIONS = 12
    }

    override fun onCleared() {
        pdfRenderer?.close()
        pdfRenderer = null
    }
}
