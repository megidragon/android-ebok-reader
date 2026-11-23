package com.lectora.app.data

import android.net.Uri
import com.lectora.core.database.BookEntity
import com.lectora.engine.text.BookDocument
import com.lectora.engine.text.BookOpener
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Comparte los documentos abiertos entre el lector y la narración (evita parsear dos veces el
 * mismo libro). Mantiene como mucho [capacity] libros abiertos.
 */
class DocumentCache(private val opener: BookOpener, private val capacity: Int = 2) {
    private val mutex = Mutex()
    private val open = LinkedHashMap<Long, BookDocument>(capacity + 1, 0.75f, true)

    suspend fun get(book: BookEntity): BookDocument = mutex.withLock {
        open[book.id]?.let { return it }
        val doc = opener.open(Uri.parse(book.uri), book.format)
        open[book.id] = doc
        while (open.size > capacity) {
            val eldest = open.entries.first()
            open.remove(eldest.key)
            // No cerramos explícitamente: el GC liberará el documento cuando nadie lo use.
            // (Los PDF sí guardan un descriptor; se cierran con finalize de PdfBox.)
        }
        doc
    }

    suspend fun invalidate(bookId: Long) = mutex.withLock {
        open.remove(bookId)?.close()
    }
}
