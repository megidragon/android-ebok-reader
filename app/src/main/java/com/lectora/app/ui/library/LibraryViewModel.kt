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
import com.lectora.core.database.FolderEntity
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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

class LibraryViewModel(private val container: AppContainer) : ViewModel() {
    private val library = container.library

    val reading: StateFlow<List<BookWithProgress>> =
        library.readingBooks().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val all: StateFlow<List<BookWithProgress>?> =
        library.allBooks().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
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
    val folderContent: StateFlow<FolderContent?> = combine(library.allBooks(), library.folders(), _location) { books, roots, loc ->
        buildFolderContent(books, roots, loc)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private fun buildFolderContent(books: List<BookWithProgress>, roots: List<FolderEntity>, loc: FolderLocation?): FolderContent {
        val rootUris = roots.map { it.uri }.toSet()
        if (loc == null) {
            val loose = books.filter { it.book.folderUri == null || it.book.folderUri !in rootUris }
            val rootEntries = roots.mapNotNull { r ->
                val count = books.count { it.book.folderUri == r.uri }
                if (count == 0) null else FolderEntry(r.displayName, FolderLocation(r.uri, ""), count)
            }
            // Con una sola carpeta sincronizada y nada suelto, entrar directamente en ella.
            if (rootEntries.size == 1 && loose.isEmpty()) {
                return buildFolderContent(books, roots, rootEntries[0].location).copy(isTop = true)
            }
            return FolderContent(path = emptyList(), folders = rootEntries, books = loose, isTop = true)
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
