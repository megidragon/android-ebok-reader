package com.lectora.engine.text

import android.icu.text.BreakIterator
import android.icu.util.ULocale
import java.util.Locale

/** Un tramo [start, end) de un párrafo que corresponde a una oración. */
data class SentenceSpan(val start: Int, val end: Int) {
    fun of(text: String): String = text.substring(start, end)
}

/**
 * Divide párrafos en oraciones usando ICU, con correcciones para abreviaturas frecuentes y
 * un límite de longitud para que cada unidad de síntesis sea razonablemente corta.
 */
object SentenceSplitter {
    private const val MAX_LEN = 600

    private val abbreviations = setOf(
        // inglés
        "mr", "mrs", "ms", "dr", "st", "jr", "sr", "prof", "vs", "etc", "e.g", "i.e", "no", "vol", "ch", "fig", "mt", "capt", "col", "gen", "lt", "sgt",
        // español
        "sra", "srta", "dra", "lic", "ing", "pág", "págs", "cap", "núm", "ud", "uds", "vd", "vds", "aprox", "dpto", "av", "gral",
    )

    fun split(text: String, locale: Locale): List<SentenceSpan> {
        if (text.isBlank()) return emptyList()
        val iterator = BreakIterator.getSentenceInstance(ULocale.forLocale(locale))
        iterator.setText(text)
        val raw = ArrayList<SentenceSpan>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            trimmed(text, start, end)?.let { raw.add(it) }
            start = end
            end = iterator.next()
        }
        return limitLength(text, mergeFalseBreaks(text, raw))
    }

    private fun trimmed(text: String, start: Int, end: Int): SentenceSpan? {
        var s = start
        var e = end
        while (s < e && text[s].isWhitespace()) s++
        while (e > s && text[e - 1].isWhitespace()) e--
        return if (s < e) SentenceSpan(s, e) else null
    }

    private fun mergeFalseBreaks(text: String, spans: List<SentenceSpan>): List<SentenceSpan> {
        if (spans.size < 2) return spans
        val out = ArrayList<SentenceSpan>(spans.size)
        for (span in spans) {
            val last = out.lastOrNull()
            if (last != null && (endsWithAbbreviation(text, last) || isTooShort(text, span))) {
                out[out.size - 1] = SentenceSpan(last.start, span.end)
            } else {
                out.add(span)
            }
        }
        return out
    }

    private fun endsWithAbbreviation(text: String, span: SentenceSpan): Boolean {
        val s = span.of(text)
        if (!s.endsWith('.')) return false
        val word = s.dropLast(1).takeLastWhile { it.isLetter() || it == '.' }.lowercase()
        if (word.length == 1 && word[0].isLetter()) return true // iniciales: "J. R. R. Tolkien"
        return word in abbreviations
    }

    /** Fragmentos sin letras ni dígitos (comillas, rayas sueltas) se pegan a la oración anterior. */
    private fun isTooShort(text: String, span: SentenceSpan): Boolean =
        span.of(text).none { it.isLetterOrDigit() }

    private fun limitLength(text: String, spans: List<SentenceSpan>): List<SentenceSpan> {
        if (spans.all { it.end - it.start <= MAX_LEN }) return spans
        val out = ArrayList<SentenceSpan>()
        for (span in spans) {
            var s = span.start
            while (span.end - s > MAX_LEN) {
                val window = text.substring(s, s + MAX_LEN)
                val cut = listOf("; ", ": ", ", ", " — ", " ")
                    .map { window.lastIndexOf(it) }
                    .firstOrNull { it > MAX_LEN / 3 }
                    ?: (MAX_LEN - 1)
                val e = s + cut + 1
                trimmed(text, s, e)?.let { out.add(it) }
                s = e
            }
            trimmed(text, s, span.end)?.let { out.add(it) }
        }
        return out
    }
}
