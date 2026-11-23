package com.lectora.app.data

import android.content.Context
import android.util.Log
import com.lectora.core.model.BookFormat
import com.lectora.engine.text.BookDocument
import com.lectora.engine.text.BookMetadata
import com.lectora.engine.text.TocEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Regla del filtro personal de texto: reemplaza (o quita, si [replace] está vacío) un texto o
 * una expresión regular. Sirve para limpiar caracteres rotos de PDF/EPUB que estropean la
 * lectura y, sobre todo, la narración.
 */
data class TextFilterRule(
    val id: String = UUID.randomUUID().toString(),
    val find: String,
    val replace: String = "",
    val regex: Boolean = false,
    /** true = se aplica a todos los libros; false = solo al libro en el que se creó. */
    val global: Boolean = false,
    val enabled: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("find", find).put("replace", replace)
        .put("regex", regex).put("global", global).put("enabled", enabled)

    companion object {
        fun fromJson(o: JSONObject) = TextFilterRule(
            id = o.optString("id").ifEmpty { UUID.randomUUID().toString() },
            find = o.getString("find"),
            replace = o.optString("replace"),
            regex = o.optBoolean("regex"),
            global = o.optBoolean("global"),
            enabled = o.optBoolean("enabled", true),
        )
    }
}

/** Reglas compiladas, listas para aplicarse a cada párrafo. */
class TextFilter(rules: List<TextFilterRule>) {
    private val steps: List<(String) -> String> = rules.filter { it.enabled && it.find.isNotEmpty() }.mapNotNull { rule ->
        if (rule.regex) {
            val re = runCatching { Regex(rule.find) }
                .onFailure { Log.w("TextFilter", "Expresión regular inválida: ${rule.find}") }
                .getOrNull() ?: return@mapNotNull null
            { text: String -> re.replace(text, rule.replace) }
        } else {
            { text: String -> text.replace(rule.find, rule.replace) }
        }
    }

    val isEmpty: Boolean get() = steps.isEmpty()

    fun apply(text: String): String {
        if (steps.isEmpty()) return text
        var t = text
        for (step in steps) t = step(t)
        return spaces.replace(t, " ").trim()
    }

    /** Coincidencias de una regla en un texto (para la vista previa). */
    companion object {
        private val spaces = Regex("[ \\t\\u00A0]{2,}")

        fun countMatches(rule: TextFilterRule, text: String): Int = when {
            rule.find.isEmpty() -> 0
            rule.regex -> runCatching { Regex(rule.find).findAll(text).count() }.getOrDefault(0)
            else -> {
                var count = 0
                var i = text.indexOf(rule.find)
                while (i >= 0) {
                    count++
                    i = text.indexOf(rule.find, i + rule.find.length)
                }
                count
            }
        }
    }
}

/** Guarda las reglas en filesDir/text_filters.json (globales + por libro). */
class TextFilterRepository(context: Context) {
    private val file = File(context.filesDir, "text_filters.json")

    data class State(val global: List<TextFilterRule> = emptyList(), val books: Map<Long, List<TextFilterRule>> = emptyMap()) {
        fun rulesFor(bookId: Long): List<TextFilterRule> = global + books[bookId].orEmpty()
    }

    private val _state = MutableStateFlow(load())
    val state: StateFlow<State> = _state.asStateFlow()

    fun rulesFor(bookId: Long): List<TextFilterRule> = _state.value.rulesFor(bookId)

    fun observe(bookId: Long): Flow<List<TextFilterRule>> = state.map { it.rulesFor(bookId) }.distinctUntilChanged()

    suspend fun add(bookId: Long, rule: TextFilterRule) = update { s ->
        if (rule.global) s.copy(global = s.global + rule)
        else s.copy(books = s.books + (bookId to (s.books[bookId].orEmpty() + rule)))
    }

    suspend fun remove(ruleId: String) = update { s ->
        s.copy(
            global = s.global.filterNot { it.id == ruleId },
            books = s.books.mapValues { (_, list) -> list.filterNot { it.id == ruleId } }.filterValues { it.isNotEmpty() },
        )
    }

    suspend fun setEnabled(ruleId: String, enabled: Boolean) = update { s ->
        s.copy(
            global = s.global.map { if (it.id == ruleId) it.copy(enabled = enabled) else it },
            books = s.books.mapValues { (_, list) -> list.map { if (it.id == ruleId) it.copy(enabled = enabled) else it } },
        )
    }

    private suspend fun update(transform: (State) -> State) {
        val next = transform(_state.value)
        _state.value = next
        withContext(Dispatchers.IO) { save(next) }
    }

    private fun load(): State = runCatching {
        if (!file.exists()) return State()
        val o = JSONObject(file.readText())
        fun list(a: JSONArray?) = a?.let { arr -> List(arr.length()) { TextFilterRule.fromJson(arr.getJSONObject(it)) } }.orEmpty()
        val books = o.optJSONObject("books")?.let { b ->
            b.keys().asSequence().associate { key -> key.toLong() to list(b.getJSONArray(key)) }
        }.orEmpty()
        State(list(o.optJSONArray("global")), books)
    }.onFailure { Log.w("TextFilter", "No se pudieron leer los filtros", it) }.getOrDefault(State())

    private fun save(s: State) {
        val books = JSONObject()
        s.books.forEach { (id, list) -> books.put(id.toString(), JSONArray(list.map { it.toJson() })) }
        val o = JSONObject().put("global", JSONArray(s.global.map { it.toJson() })).put("books", books)
        file.writeText(o.toString(2))
    }
}

/**
 * Documento con el filtro personal aplicado. Lo usan el lector, la traducción y la narración,
 * así todos ven el mismo texto limpio. Los párrafos que quedan vacíos se omiten.
 */
class FilteredBookDocument(
    private val inner: BookDocument,
    private val bookId: Long,
    private val filters: TextFilterRepository,
) : BookDocument {
    override val format: BookFormat get() = inner.format
    override val metadata: BookMetadata get() = inner.metadata

    private var cachedState: TextFilterRepository.State? = null
    private var cachedFilter = TextFilter(emptyList())

    private fun filter(): TextFilter {
        val state = filters.state.value
        if (state !== cachedState) {
            cachedFilter = TextFilter(state.rulesFor(bookId))
            cachedState = state
        }
        return cachedFilter
    }

    override fun sectionTitle(section: Int): String? = inner.sectionTitle(section)?.let { filter().apply(it) }

    override suspend fun paragraphs(section: Int): List<String> {
        val raw = inner.paragraphs(section)
        val f = filter()
        if (f.isEmpty) return raw
        return raw.map { f.apply(it) }.filter { it.isNotBlank() }
    }

    /** Texto original, sin filtro (para la vista previa del filtro). */
    suspend fun rawParagraphs(section: Int): List<String> = inner.paragraphs(section)

    override suspend fun tableOfContents(): List<TocEntry> {
        val f = filter()
        return inner.tableOfContents().map { it.copy(title = f.apply(it.title)) }
    }

    override fun close() = inner.close()
}
