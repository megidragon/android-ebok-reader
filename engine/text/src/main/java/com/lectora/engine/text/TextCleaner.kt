package com.lectora.engine.text

/** Normalización de texto común a EPUB y PDF. */
object TextCleaner {
    private val ligatures = mapOf(
        "ﬀ" to "ff", "ﬁ" to "fi", "ﬂ" to "fl", "ﬃ" to "ffi", "ﬄ" to "ffl",
        "ﬅ" to "st", "ﬆ" to "st",
    )
    private val whitespace = Regex("[\\s\\u00A0\\u2007\\u202F]+")
    private val blankLines = Regex("\\n[ \\t\\u00A0]*\\n+")
    /** "12", "— 12 —", "- xii -", "Página 12", "12 / 6829", "[12]". */
    private val pageNumber = Regex(
        "^[\\s\\-\\u2013\\u2014\\[(]*(page|página|pag\\.?|p\\.)?\\s*[0-9ivxlcdm]{1,6}(\\s*(/|de|of)\\s*\\d{1,6})?[\\s\\-\\u2013\\u2014\\])]*$",
        RegexOption.IGNORE_CASE,
    )

    /** Normaliza espacios, ligaduras y caracteres invisibles de una línea o párrafo. */
    fun normalize(text: String): String {
        var t = text
        if (t.any { it in 'ﬀ'..'ﬆ' }) ligatures.forEach { (k, v) -> t = t.replace(k, v) }
        t = t.replace("­", "") // guion blando
            .replace("​", "")
            .replace("﻿", "")
        return whitespace.replace(t, " ").trim()
    }

    /**
     * Reconstruye párrafos a partir del texto crudo de una página PDF: une líneas partidas,
     * corrige palabras cortadas con guion al final de línea y descarta números de página.
     */
    fun rebuildPdfParagraphs(raw: String): List<String> {
        val result = ArrayList<String>()
        for (block in raw.replace("\r", "").split(blankLines)) {
            val lines = block.lines().map { normalize(it) }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) continue
            val sb = StringBuilder()
            for ((i, line) in lines.withIndex()) {
                if (sb.isEmpty()) {
                    sb.append(line)
                    continue
                }
                val prev = lines[i - 1]
                val endsHyphen = sb.endsWith("-") && sb.length > 1 && sb[sb.length - 2].isLetter()
                if (endsHyphen && line.first().isLowerCase()) {
                    sb.setLength(sb.length - 1)
                    sb.append(line)
                } else if (looksLikeParagraphBreak(prev, line, lines)) {
                    addParagraph(result, sb.toString())
                    sb.setLength(0)
                    sb.append(line)
                } else {
                    sb.append(' ').append(line)
                }
            }
            addParagraph(result, sb.toString())
        }
        return mergeBrokenParagraphs(result)
    }

    /**
     * Muchos PDF (sobre todo los convertidos desde EPUB o web) separan cada línea como si fuera
     * un párrafo. Si un "párrafo" no termina la oración y el siguiente empieza en minúscula (o el
     * anterior acaba en coma), en realidad es la misma frase: se unen para que la narración no
     * haga pausas a mitad de oración.
     */
    private fun mergeBrokenParagraphs(paragraphs: List<String>): List<String> {
        if (paragraphs.size < 2) return paragraphs
        val out = ArrayList<String>(paragraphs.size)
        for (p in paragraphs) {
            val prev = out.lastOrNull()
            if (prev != null && continues(prev, p)) {
                val hyphen = prev.endsWith("-") && prev.length > 1 && prev[prev.length - 2].isLetter() && p.first().isLowerCase()
                out[out.size - 1] = if (hyphen) prev.dropLast(1) + p else "$prev $p"
            } else {
                out.add(p)
            }
        }
        return out
    }

    private fun continues(prev: String, next: String): Boolean {
        if (endsSentence(prev)) return false
        val first = next.first()
        return first.isLowerCase() || prev.last() in ",;-–" || (first.isDigit() && prev.last().isLetter())
    }

    private fun looksLikeParagraphBreak(prev: String, next: String, block: List<String>): Boolean {
        // Una línea claramente más corta que el ancho típico y terminada en puntuación final
        // suele cerrar un párrafo cuando la siguiente empieza en mayúscula o con diálogo.
        if (block.size < 3) return false
        val typical = block.map { it.length }.sorted()[block.size * 3 / 4]
        val terminal = prev.last() in ".!?…:\"”»'’)"
        val nextStarts = next.first().isUpperCase() || next.first() in "—–-\"“«¿¡'"
        return terminal && nextStarts && prev.length < typical * 0.7
    }

    private fun addParagraph(out: MutableList<String>, text: String) {
        val t = normalize(text)
        // Vacío, número de página o línea decorativa ("=====", "* * *").
        if (t.isEmpty() || pageNumber.matches(t) || t.none { it.isLetterOrDigit() }) return
        out.add(t)
    }

    /** ¿El párrafo termina una oración? (si no, probablemente continúa en la página siguiente). */
    fun endsSentence(paragraph: String): Boolean {
        val t = paragraph.trimEnd()
        if (t.isEmpty()) return true
        return t.last() in ".!?…:;\"”»'’)]"
    }
}
