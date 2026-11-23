package com.lectora.app.data

import com.lectora.core.database.TranslationDao
import com.lectora.core.database.TranslationEntity
import com.lectora.engine.translation.ParagraphTranslator
import com.lectora.engine.translation.TextTranslator

/** Traduce párrafo a párrafo y guarda el resultado: cada párrafo se traduce una sola vez. */
class CachedParagraphTranslator(
    private val dao: TranslationDao,
    private val engine: TextTranslator,
) : ParagraphTranslator {

    override suspend fun translate(
        bookId: Long,
        section: Int,
        paragraph: Int,
        text: String,
        source: String,
        target: String,
    ): String {
        if (source.equals(target, ignoreCase = true)) return text
        val hash = text.hashCode()
        dao.get(bookId, section, paragraph, target)
            ?.takeIf { it.sourceHash == hash && it.sourceLang == source }
            ?.let { return it.text }
        val translated = engine.translate(text, source, target)
        dao.upsert(TranslationEntity(bookId, section, paragraph, target, source, hash, translated))
        return translated
    }

    /** Solo la caché (para pintar rápido sin esperar al motor). */
    suspend fun cached(bookId: Long, section: Int, paragraph: Int, text: String, source: String, target: String): String? =
        dao.get(bookId, section, paragraph, target)
            ?.takeIf { it.sourceHash == text.hashCode() && it.sourceLang == source }
            ?.text
}
