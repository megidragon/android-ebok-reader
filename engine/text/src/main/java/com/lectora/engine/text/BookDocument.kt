package com.lectora.engine.text

import com.lectora.core.model.BookFormat
import java.io.Closeable

/** Metadatos extraídos del propio archivo. */
data class BookMetadata(
    val title: String?,
    val author: String?,
    val language: String?,
    val sectionCount: Int,
)

/** Entrada del índice del libro: un capítulo que empieza en [section]. */
data class TocEntry(val title: String, val section: Int, val level: Int = 0)

/**
 * Vista de "texto puro" de un libro: secciones (capítulos o páginas) formadas por párrafos.
 *
 * Es la representación que usan la narración, la traducción y la vista de lectura refluida.
 * Las implementaciones deben ser seguras para usarse desde varias corrutinas.
 */
interface BookDocument : Closeable {
    val format: BookFormat
    val metadata: BookMetadata
    val sectionCount: Int get() = metadata.sectionCount

    /** Título de la sección si se conoce (capítulo del índice, o "Página N"). */
    fun sectionTitle(section: Int): String?

    /** Párrafos limpios de la sección. Puede ser una lista vacía (p. ej. una portada). */
    suspend fun paragraphs(section: Int): List<String>

    /**
     * Índice del libro (capítulos), ordenado por sección. Vacío si el archivo no trae índice.
     * Puede tardar en libros muy grandes: llamarlo fuera del hilo principal.
     */
    suspend fun tableOfContents(): List<TocEntry> = emptyList()
}

/** Caché LRU acotada de párrafos por sección: la memoria no crece al leer libros enormes. */
internal class SectionCache(private val capacity: Int) {
    private val map = object : LinkedHashMap<Int, List<String>>(capacity + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, List<String>>?): Boolean = size > capacity
    }

    @Synchronized fun get(section: Int): List<String>? = map[section]
    @Synchronized fun put(section: Int, paragraphs: List<String>) { map[section] = paragraphs }
    @Synchronized fun clear() = map.clear()
}
