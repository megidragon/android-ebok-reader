package com.lectora.app.ui.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lectora.app.AppContainer
import com.lectora.app.data.ScanResult
import com.lectora.core.database.BookEntity
import com.lectora.core.database.BookWithProgress
import com.lectora.core.database.CollectionWithCount
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import com.lectora.app.data.LibraryView
import com.lectora.app.data.displayTitle
import com.lectora.core.database.FolderEntity
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.lectora.app.data.LibraryDisplay
import com.lectora.app.data.LibrarySort
import com.lectora.app.data.LibrarySortField
import com.lectora.app.data.TitleSource
import com.lectora.core.model.BookFormat
import java.text.Collator
import java.text.Normalizer

data class FolderLocation(val rootUri: String, val dir: String)

data class FolderEntry(val name: String, val location: FolderLocation, val bookCount: Int)

/** Lo que se ve en un nivel de la vista por carpetas. */
data class FolderContent(
    /** Ruta desde la carpeta sincronizada (vacía en el nivel superior). */
    val path: List<FolderEntry>,
    val folders: List<FolderEntry>,
    val books: List<BookWithProgress>,
    /** Nivel superior (no se puede subir más). */
    val isTop: Boolean,
)

/** Estado de lectura de un libro, para filtrar. */
enum class ReadState(val label: String) {
    NOT_STARTED("Sin empezar"),
    IN_PROGRESS("En curso"),
    FINISHED("Terminados"),
}

val BookWithProgress.readState: ReadState
    get() {
        val fraction = progress?.fraction ?: 0f
        return when {
            fraction >= 0.999f -> ReadState.FINISHED
            fraction > 0f -> ReadState.IN_PROGRESS
            else -> ReadState.NOT_STARTED
        }
    }

/** Idioma sin región ("es-ES" → "es"), o null si no se conoce. */
val BookEntity.primaryLanguage: String?
    get() = language?.substringBefore('-')?.substringBefore('_')?.lowercase()?.takeIf { it.isNotBlank() && it != "und" }

/** Filtros de la biblioteca. Un grupo vacío no filtra; dentro de un grupo basta con cumplir uno. */
data class LibraryFilters(
    val formats: Set<BookFormat> = emptySet(),
    val states: Set<ReadState> = emptySet(),
    val languages: Set<String> = emptySet(),
) {
    val count: Int get() = formats.size + states.size + languages.size
    val active: Boolean get() = count > 0

    fun matches(item: BookWithProgress): Boolean =
        (formats.isEmpty() || item.book.format in formats) &&
            (states.isEmpty() || item.readState in states) &&
            (languages.isEmpty() || item.book.primaryLanguage in languages)
}

private fun <T> Set<T>.toggle(value: T): Set<T> = if (value in this) this - value else this + value

/** Minúsculas y sin tildes, para que "camión" se encuentre escribiendo "camion". */
private fun String.searchKey(): String =
    Normalizer.normalize(lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").trim()

private fun bookComparator(sort: LibrarySort, source: TitleSource): Comparator<BookWithProgress> {
    // Ignora mayúsculas y tildes al comparar textos.
    val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }
    val byTitle = Comparator<BookWithProgress> { a, b ->
        collator.compare(a.book.displayTitle(source), b.book.displayTitle(source))
    }
    fun directed(c: Comparator<BookWithProgress>) = if (sort.ascending) c else c.reversed()
    /** Los libros sin el dato van siempre al final, sea cual sea el sentido. */
    fun <T : Any> optional(cmp: Comparator<T>, key: (BookWithProgress) -> T?) = Comparator<BookWithProgress> { a, b ->
        val ka = key(a)
        val kb = key(b)
        when {
            ka == null && kb == null -> 0
            ka == null -> 1
            kb == null -> -1
            else -> if (sort.ascending) cmp.compare(ka, kb) else cmp.compare(kb, ka)
        }
    }
    val primary = when (sort.field) {
        LibrarySortField.TITLE -> directed(byTitle)
        LibrarySortField.AUTHOR -> optional<String>(collator::compare) { it.book.author?.takeIf(String::isNotBlank) }
        LibrarySortField.ADDED -> directed(compareBy { it.book.addedAt })
        LibrarySortField.LAST_OPENED -> optional<Long>(naturalOrder()) { it.book.lastOpenedAt }
        LibrarySortField.PROGRESS -> directed(compareBy { it.progress?.fraction ?: 0f })
        LibrarySortField.SIZE -> directed(compareBy { it.book.sizeBytes })
    }
    return if (sort.field == LibrarySortField.TITLE) primary else primary.then(byTitle)
}

class LibraryViewModel(private val container: AppContainer) : ViewModel() {
    private val library = container.library

    val reading: StateFlow<List<BookWithProgress>> =
        library.readingBooks().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    // ---------------- Orden, filtros, búsqueda y tamaño ----------------

    val display: StateFlow<LibraryDisplay> =
        container.settings.libraryDisplay.stateIn(viewModelScope, SharingStarted.Eagerly, LibraryDisplay.LARGE)

    fun setDisplay(display: LibraryDisplay) = viewModelScope.launch { container.settings.setLibraryDisplay(display) }

    val sort: StateFlow<LibrarySort> =
        container.settings.librarySort.stateIn(viewModelScope, SharingStarted.Eagerly, LibrarySort())

    /** Elegir el criterio ya activo invierte el sentido; uno nuevo empieza en su sentido natural. */
    fun selectSort(field: LibrarySortField) = viewModelScope.launch {
        val current = sort.value
        container.settings.setLibrarySort(
            if (current.field == field) current.copy(ascending = !current.ascending) else LibrarySort(field)
        )
    }

    private val _filters = MutableStateFlow(LibraryFilters())
    val filters: StateFlow<LibraryFilters> = _filters.asStateFlow()

    fun toggleFormat(format: BookFormat) = _filters.update { it.copy(formats = it.formats.toggle(format)) }
    fun toggleState(state: ReadState) = _filters.update { it.copy(states = it.states.toggle(state)) }
    fun toggleLanguage(language: String) = _filters.update { it.copy(languages = it.languages.toggle(language)) }
    fun clearFilters() {
        _filters.value = LibraryFilters()
    }

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    fun setQuery(text: String) {
        _query.value = text
    }

    /** Todos los libros, en el orden elegido (el título es el que se muestra: archivo o interno). */
    private val sortedBooks = combine(library.allBooks(), container.settings.titleSource, sort) { books, source, sort ->
        books.sortedWith(bookComparator(sort, source))
    }

    /** Libros que pasan los filtros activos (sin contar la búsqueda). */
    private val filteredBooks = combine(sortedBooks, _filters) { books, filters ->
        if (filters.active) books.filter(filters::matches) else books
    }

    /** Idiomas presentes en la biblioteca, para ofrecerlos como filtro. */
    val languages: StateFlow<List<String>> = library.allBooks()
        .map { books -> books.mapNotNull { it.book.primaryLanguage }.distinct().sorted() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Libros de la vista "Todo": ordenados y filtrados. */
    val visible: StateFlow<List<BookWithProgress>> =
        filteredBooks.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Resultado de la búsqueda en toda la biblioteca; null si no se está buscando. */
    val searchResults: StateFlow<List<BookWithProgress>?> =
        combine(filteredBooks, _query, container.settings.titleSource) { books, query, source ->
            val words = query.searchKey().split(' ').filter { it.isNotEmpty() }
            if (words.isEmpty()) return@combine null
            books.filter { item ->
                val b = item.book
                val haystack = "${b.displayTitle(source)} ${b.title} ${b.fileName} ${b.author.orEmpty()}".searchKey()
                words.all { it in haystack }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val all: StateFlow<List<BookWithProgress>?> =
        sortedBooks.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val collections: StateFlow<List<CollectionWithCount>> =
        library.collections().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val scanning: StateFlow<Boolean> = library.scanning

    // ---------------- Vista por carpetas ----------------

    val libraryView: StateFlow<LibraryView> =
        container.settings.libraryView.stateIn(viewModelScope, SharingStarted.Eagerly, LibraryView.FOLDERS)

    fun setLibraryView(view: LibraryView) = viewModelScope.launch { container.settings.setLibraryView(view) }

    /** Dónde estamos: null = nivel superior; si no, una carpeta sincronizada y una subcarpeta. */
    private val _location = MutableStateFlow<FolderLocation?>(null)
    val location: StateFlow<FolderLocation?> = _location.asStateFlow()

    fun openFolder(location: FolderLocation?) {
        _location.value = location
    }

    /** Sube un nivel. Devuelve false si ya está arriba del todo. */
    fun goUp(): Boolean {
        val loc = _location.value ?: return false
        _location.value = when {
            loc.dir.isNotEmpty() -> loc.copy(dir = loc.dir.substringBeforeLast('/', ""))
            else -> null
        }
        return true
    }

    /** Contenido del nivel actual: subcarpetas y libros, respetando la estructura del disco. */
    val folderContent: StateFlow<FolderContent?> =
        combine(sortedBooks, filteredBooks, library.folders(), _location) { everything, books, roots, loc ->
            buildFolderContent(everything, books, roots, loc)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * [everything] decide la estructura (para que no cambie al filtrar); [books] son los libros
     * que pasan los filtros y es lo que se muestra y se cuenta.
     */
    private fun buildFolderContent(
        everything: List<BookWithProgress>,
        books: List<BookWithProgress>,
        roots: List<FolderEntity>,
        loc: FolderLocation?,
    ): FolderContent {
        val rootUris = roots.map { it.uri }.toSet()
        if (loc == null) {
            fun BookWithProgress.isLoose() = book.folderUri == null || book.folderUri !in rootUris
            val usedRoots = roots.filter { r -> everything.any { it.book.folderUri == r.uri } }
            // Con una sola carpeta sincronizada y nada suelto, entrar directamente en ella.
            if (usedRoots.size == 1 && everything.none { it.isLoose() }) {
                return buildFolderContent(everything, books, roots, FolderLocation(usedRoots[0].uri, "")).copy(isTop = true)
            }
            val rootEntries = usedRoots.mapNotNull { r ->
                val count = books.count { it.book.folderUri == r.uri }
                if (count == 0) null else FolderEntry(r.displayName, FolderLocation(r.uri, ""), count)
            }
            return FolderContent(path = emptyList(), folders = rootEntries, books = books.filter { it.isLoose() }, isTop = true)
        }
        val root = roots.firstOrNull { it.uri == loc.rootUri }
        val inRoot = books.filter { it.book.folderUri == loc.rootUri }
        val prefix = if (loc.dir.isEmpty()) "" else loc.dir + "/"
        val sub = inRoot.filter { it.book.relativeDir.startsWith(prefix) && it.book.relativeDir != loc.dir }
        val folders = sub.groupBy { it.book.relativeDir.removePrefix(prefix).substringBefore('/') }
            .map { (name, list) -> FolderEntry(name, FolderLocation(loc.rootUri, prefix + name), list.size) }
            .sortedBy { it.name.lowercase() }
        val here = inRoot.filter { it.book.relativeDir == loc.dir }
        // Ruta para la barra de navegación: carpeta raíz + cada subcarpeta.
        val path = buildList {
            add(FolderEntry(root?.displayName ?: "Carpeta", FolderLocation(loc.rootUri, ""), inRoot.size))
            var acc = ""
            loc.dir.split('/').filter { it.isNotEmpty() }.forEach { part ->
                acc = if (acc.isEmpty()) part else "$acc/$part"
                add(FolderEntry(part, FolderLocation(loc.rootUri, acc), 0))
            }
        }
        return FolderContent(path = path, folders = folders, books = here, isTop = false)
    }

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    val message = MutableStateFlow<String?>(null)

    fun booksIn(collectionId: Long): Flow<List<BookWithProgress>> = library.booksIn(collectionId)
    fun collectionIdsFor(bookId: Long): Flow<List<Long>> = library.collectionIdsFor(bookId)
    fun collection(id: Long) = library.collection(id)

    fun importFiles(uris: List<Uri>) = work { report(library.importFiles(uris)) }
    fun addFolder(uri: Uri) = work { report(library.addFolder(uri)) }
    fun sync() = work { report(library.scanAll()) }

    fun createCollection(name: String, thenAddBook: Long? = null) = viewModelScope.launch {
        if (name.isBlank()) return@launch
        val id = library.createCollection(name)
        if (thenAddBook != null && id > 0) library.addToCollection(thenAddBook, id)
    }

    fun setInCollection(bookId: Long, collectionId: Long, inCollection: Boolean) = viewModelScope.launch {
        if (inCollection) library.addToCollection(bookId, collectionId) else library.removeFromCollection(bookId, collectionId)
    }

    fun renameCollection(id: Long, name: String) = viewModelScope.launch { library.renameCollection(id, name) }
    fun deleteCollection(id: Long) = viewModelScope.launch { library.deleteCollection(id) }
    fun removeBook(book: BookEntity) = viewModelScope.launch { library.removeBook(book) }

    private fun work(block: suspend () -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            try {
                block()
            } catch (e: Exception) {
                message.value = "Error: ${e.message}"
            } finally {
                _busy.value = false
            }
        }
    }

    private fun report(r: ScanResult) {
        val parts = buildList {
            if (r.added > 0) add("${r.added} nuevos")
            if (r.moved > 0) add("${r.moved} movidos")
            if (r.missing > 0) add("${r.missing} ya no están")
            if (r.failed > 0) add("${r.failed} con error")
        }
        message.value = if (parts.isEmpty()) "Biblioteca al día" else parts.joinToString(" · ")
    }
}
