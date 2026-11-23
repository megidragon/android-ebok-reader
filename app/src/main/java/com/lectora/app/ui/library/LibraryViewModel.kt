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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LibraryViewModel(private val container: AppContainer) : ViewModel() {
    private val library = container.library

    val reading: StateFlow<List<BookWithProgress>> =
        library.readingBooks().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val all: StateFlow<List<BookWithProgress>?> =
        library.allBooks().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val collections: StateFlow<List<CollectionWithCount>> =
        library.collections().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val scanning: StateFlow<Boolean> = library.scanning

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
