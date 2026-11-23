package com.lectora.app.ui.reader

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lectora.app.AppContainer
import com.lectora.app.data.PageMode
import com.lectora.app.data.ReaderSettings
import com.lectora.app.data.ReaderTheme
import com.lectora.app.data.FilteredBookDocument
import com.lectora.app.data.PrerenderWorker
import com.lectora.engine.tts.VoiceInfo
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.lectora.app.data.TextFilter
import com.lectora.app.data.TextFilterRule
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
        // Filtro personal: al cambiar las reglas, recargar el texto (y la narración si suena).
        viewModelScope.launch {
            var first = true
            container.textFilters.observe(bookId).collect {
                if (first) {
                    first = false
                } else {
                    reloadAfterFilterChange()
                }
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

    // ---------------- Narración pregenerada ----------------

    data class PrerenderUi(
        val voiceName: String? = null,
        val voiceKey: String? = null,
        val spokenLanguage: String? = null,
        val translated: Boolean = false,
        val voices: List<VoiceInfo> = emptyList(),
        /** Secciones ya pregeneradas con la voz/idioma/velocidad actuales. */
        val rendered: List<Int> = emptyList(),
        val bytes: Long = 0,
        val lastReadSection: Int? = null,
        val running: Boolean = false,
        val done: Int = 0,
        val total: Int = 0,
        val currentSection: Int = 0,
        val error: String? = null,
    )

    private val _prerender = MutableStateFlow(PrerenderUi())
    val prerender: StateFlow<PrerenderUi> = _prerender.asStateFlow()

    init {
        observePrerenderWork()
    }

    fun refreshPrerender() = viewModelScope.launch {
        runCatching {
            val book = container.openNarrationBook(bookId)
            val settings = container.settings.narration.first()
            val (variant, voiceName) = container.renderer.variantFor(book, settings) ?: return@launch
            val summary = withContext(Dispatchers.IO) {
                container.prerenderStore.summaries(bookId).firstOrNull { it.variant.id == variant.id }
            }
            val voices = container.ttsRegistry.voices(variant.spokenLanguage)
            _prerender.update {
                it.copy(
                    voiceName = voiceName,
                    voiceKey = variant.voiceKey,
                    spokenLanguage = variant.spokenLanguage,
                    translated = variant.translated,
                    voices = voices,
                    rendered = summary?.sections.orEmpty(),
                    bytes = summary?.bytes ?: 0,
                    lastReadSection = library.progress(bookId)?.section,
                )
            }
        }
    }

    fun startPrerender(fromSection: Int, count: Int) {
        _prerender.update { it.copy(error = null) }
        PrerenderWorker.start(container.context, bookId, fromSection, count)
    }

    fun cancelPrerender() = PrerenderWorker.cancel(container.context, bookId)

    fun deletePrerender() = viewModelScope.launch {
        withContext(Dispatchers.IO) { container.prerenderStore.delete(bookId) }
        refreshPrerender()
    }

    /** Cambia la voz del idioma narrado (la misma que usará la reproducción). */
    fun choosePrerenderVoice(voice: VoiceInfo) = viewModelScope.launch {
        val lang = _prerender.value.spokenLanguage ?: return@launch
        container.settings.setVoice(lang, voice.key)
        refreshPrerender()
    }

    private fun observePrerenderWork() = viewModelScope.launch {
        WorkManager.getInstance(container.context)
            .getWorkInfosForUniqueWorkFlow(PrerenderWorker.uniqueName(bookId))
            .collect { infos ->
                val info = infos.firstOrNull() ?: return@collect
                val running = info.state == WorkInfo.State.RUNNING || info.state == WorkInfo.State.ENQUEUED
                _prerender.update {
                    it.copy(
                        running = running,
                        done = info.progress.getInt(PrerenderWorker.KEY_DONE, if (running) it.done else 0),
                        total = info.progress.getInt(PrerenderWorker.KEY_TOTAL, it.total),
                        currentSection = info.progress.getInt(PrerenderWorker.KEY_SECTION, it.currentSection),
                        error = if (info.state == WorkInfo.State.FAILED) info.outputData.getString(PrerenderWorker.KEY_ERROR) ?: "Error" else null,
                    )
                }
                if (!running || info.progress.getInt(PrerenderWorker.KEY_DONE, 0) > 0) refreshPrerender()
            }
    }

    // ---------------- Filtro personal de texto ----------------

    val filterRules: StateFlow<List<TextFilterRule>> =
        container.textFilters.observe(bookId).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun addFilter(find: String, replace: String, regex: Boolean, global: Boolean) = viewModelScope.launch {
        if (find.isEmpty()) return@launch
        container.textFilters.add(bookId, TextFilterRule(find = find, replace = replace, regex = regex, global = global))
    }

    fun removeFilter(id: String) = viewModelScope.launch { container.textFilters.remove(id) }
    fun setFilterEnabled(id: String, enabled: Boolean) = viewModelScope.launch { container.textFilters.setEnabled(id, enabled) }

    /** Coincidencias de una regla en el texto original de la sección actual (vista previa). */
    suspend fun previewMatches(find: String, regex: Boolean): Int {
        val doc = document as? FilteredBookDocument ?: return 0
        val text = runCatching { doc.rawParagraphs(_ui.value.section) }.getOrDefault(emptyList()).joinToString("\n")
        return TextFilter.countMatches(TextFilterRule(find = find, regex = regex), text)
    }

    /**
     * Caracteres "raros" que siguen en la sección actual (ya filtrada): uso privado, de control,
     * de reemplazo (�), sueltos de pares UTF-16 o símbolos poco habituales. Son los que suelen
     * romper la narración.
     */
    suspend fun suspiciousCharacters(): List<Pair<String, Int>> = withContext(Dispatchers.Default) {
        val doc = document ?: return@withContext emptyList()
        val text = runCatching { doc.paragraphs(_ui.value.section) }.getOrDefault(emptyList()).joinToString("\n")
        val counts = HashMap<String, Int>()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val type = Character.getType(cp)
            val odd = cp == 0xFFFD ||
                type == Character.PRIVATE_USE.toInt() || type == Character.UNASSIGNED.toInt() ||
                type == Character.SURROGATE.toInt() ||
                (type == Character.CONTROL.toInt() && cp != '\n'.code && cp != '\t'.code) ||
                (type == Character.OTHER_SYMBOL.toInt() && cp !in 0x2600..0x27BF && cp < 0x1F000) ||
                cp in 0x2500..0x259F // dibujos de cajas
            if (odd) {
                val s = String(Character.toChars(cp))
                counts[s] = (counts[s] ?: 0) + 1
            }
            i += Character.charCount(cp)
        }
        counts.entries.sortedByDescending { it.value }.take(12).map { it.key to it.value }
    }

    private var lastVisibleParagraph = 0

    private fun reloadAfterFilterChange() {
        translateJobs.values.forEach { it.cancel() }
        translateJobs.clear()
        loadJobs.values.forEach { it.cancel() }
        loadJobs.clear()
        _sections.value = emptyMap()
        val section = _ui.value.section
        loadSection(section, lastVisibleParagraph)
        // Si se está narrando este libro, regenerar el audio desde donde suena con el texto nuevo.
        val narration = container.narration
        val state = narration.state.value
        if (state.bookId == bookId && state.isActive) narration.seekTo(state.position)
    }

    // ---------------- Progreso ----------------

    /** El usuario está viendo este párrafo (lectura manual): guardar progreso con un pequeño retraso. */
    fun onParagraphVisible(paragraph: Int, narrationOwnsProgress: Boolean) {
        lastVisibleParagraph = paragraph
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
