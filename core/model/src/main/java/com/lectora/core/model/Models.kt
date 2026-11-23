package com.lectora.core.model

/** Formatos de libro soportados. */
enum class BookFormat(val extensions: Set<String>, val mimeTypes: Set<String>) {
    EPUB(setOf("epub"), setOf("application/epub+zip")),
    PDF(setOf("pdf"), setOf("application/pdf"));

    companion object {
        fun fromFileName(name: String): BookFormat? {
            val ext = name.substringAfterLast('.', "").lowercase()
            return entries.firstOrNull { ext in it.extensions }
        }

        fun fromMime(mime: String?): BookFormat? =
            mime?.let { m -> entries.firstOrNull { m.lowercase() in it.mimeTypes } }
    }
}

/**
 * Posición dentro de un libro.
 *
 * - [section]: capítulo (EPUB) o página (PDF), base 0.
 * - [paragraph]: índice del párrafo dentro de la sección.
 * - [sentence]: índice de la oración dentro del párrafo (según el texto que se narra).
 */
data class ReadingPosition(
    val section: Int = 0,
    val paragraph: Int = 0,
    val sentence: Int = 0,
) : Comparable<ReadingPosition> {
    override fun compareTo(other: ReadingPosition): Int =
        compareValuesBy(this, other, { it.section }, { it.paragraph }, { it.sentence })

    companion object {
        val START = ReadingPosition()
    }
}

/** Cómo se muestra/narra el texto respecto de la traducción. */
enum class TranslationMode {
    /** Texto original, sin traducir. */
    ORIGINAL,
    /** Solo el texto traducido. */
    TRANSLATED,
    /** Original y traducción alternados (la narración usa la traducción). */
    BILINGUAL,
}
