package com.lectora.narration.prerender

import com.lectora.narration.NarrationSettings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Lo que determina cómo suena una narración. El audio pregenerado solo se reutiliza si la
 * variante coincide con la de la reproducción (misma voz, idioma, traducción, velocidad…).
 */
data class PrerenderVariant(
    val voiceKey: String,
    val spokenLanguage: String,
    val translated: Boolean,
    val speechRate: Float,
    val pitch: Float,
    val sentencePauseMs: Int,
    val paragraphPauseMs: Int,
) {
    val id: String by lazy {
        val digest = MessageDigest.getInstance("SHA-1").digest(toString().toByteArray())
        digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("voiceKey", voiceKey).put("spokenLanguage", spokenLanguage).put("translated", translated)
        .put("speechRate", speechRate.toDouble()).put("pitch", pitch.toDouble())
        .put("sentencePauseMs", sentencePauseMs).put("paragraphPauseMs", paragraphPauseMs)

    companion object {
        fun of(settings: NarrationSettings, voiceKey: String, spokenLanguage: String, translated: Boolean) = PrerenderVariant(
            voiceKey = voiceKey,
            spokenLanguage = spokenLanguage,
            translated = translated,
            speechRate = settings.speechRate,
            pitch = settings.pitch,
            sentencePauseMs = settings.sentencePauseMs,
            paragraphPauseMs = settings.paragraphPauseMs,
        )

        fun fromJson(o: JSONObject) = PrerenderVariant(
            voiceKey = o.getString("voiceKey"),
            spokenLanguage = o.getString("spokenLanguage"),
            translated = o.getBoolean("translated"),
            speechRate = o.getDouble("speechRate").toFloat(),
            pitch = o.getDouble("pitch").toFloat(),
            sentencePauseMs = o.getInt("sentencePauseMs"),
            paragraphPauseMs = o.getInt("paragraphPauseMs"),
        )
    }
}

/** Una oración dentro del audio de una sección: dónde empieza y qué texto resaltar. */
data class RenderedUtterance(
    val paragraph: Int,
    val sentence: Int,
    val start: Int,
    val end: Int,
    val text: String,
    val translated: Boolean,
    val startMs: Long,
)

/** Audio (.m4a) + índice de oraciones de una sección ya pregenerada. */
class RenderedSection(
    val section: Int,
    val audio: File,
    val utterances: List<RenderedUtterance>,
    val durationMs: Long,
)

/** Resumen para la pantalla de gestión. */
data class PrerenderSummary(
    val bookId: Long,
    val variant: PrerenderVariant,
    val voiceName: String,
    val sections: List<Int>,
    val bytes: Long,
)

/**
 * Almacén en disco: filesDir/prerender/<libro>/<variante>/s<N>.m4a + s<N>.json + meta.json.
 */
class PrerenderStore(private val root: File) {

    private fun dir(bookId: Long, variantId: String) = File(File(root, bookId.toString()), variantId)

    fun section(bookId: Long, variant: PrerenderVariant, section: Int): RenderedSection? {
        val d = dir(bookId, variant.id)
        val audio = File(d, "s$section.m4a")
        val index = File(d, "s$section.json")
        if (!audio.exists() || !index.exists()) return null
        return runCatching {
            val o = JSONObject(index.readText())
            val arr = o.getJSONArray("utterances")
            RenderedSection(
                section = section,
                audio = audio,
                utterances = List(arr.length()) {
                    val u = arr.getJSONObject(it)
                    RenderedUtterance(
                        u.getInt("p"), u.getInt("s"), u.getInt("a"), u.getInt("b"),
                        u.getString("t"), u.optBoolean("tr"), u.getLong("ms"),
                    )
                },
                durationMs = o.optLong("durationMs"),
            )
        }.getOrNull()
    }

    fun has(bookId: Long, variant: PrerenderVariant, section: Int): Boolean =
        File(dir(bookId, variant.id), "s$section.json").exists()

    /** Escribe primero el audio en un temporal y el índice al final: una sección a medias nunca cuenta como lista. */
    fun newSectionAudioFile(bookId: Long, variant: PrerenderVariant, voiceName: String, section: Int): File {
        val d = dir(bookId, variant.id).apply { mkdirs() }
        val meta = File(d, "meta.json")
        if (!meta.exists()) {
            meta.writeText(JSONObject().put("variant", variant.toJson()).put("voiceName", voiceName).toString())
        }
        return File(d, "s$section.m4a.partial").apply { delete() }
    }

    fun commitSection(bookId: Long, variant: PrerenderVariant, section: Int, partial: File, utterances: List<RenderedUtterance>, durationMs: Long) {
        val d = dir(bookId, variant.id)
        val arr = JSONArray()
        utterances.forEach { u ->
            arr.put(JSONObject().put("p", u.paragraph).put("s", u.sentence).put("a", u.start).put("b", u.end)
                .put("t", u.text).put("tr", u.translated).put("ms", u.startMs))
        }
        val audio = File(d, "s$section.m4a")
        audio.delete()
        partial.renameTo(audio)
        File(d, "s$section.json").writeText(JSONObject().put("utterances", arr).put("durationMs", durationMs).toString())
    }

    fun summaries(bookId: Long? = null): List<PrerenderSummary> {
        val books = root.listFiles()?.filter { it.isDirectory && (bookId == null || it.name == bookId.toString()) }.orEmpty()
        return books.flatMap { b ->
            b.listFiles()?.filter { it.isDirectory }?.mapNotNull { v ->
                val meta = runCatching { JSONObject(File(v, "meta.json").readText()) }.getOrNull() ?: return@mapNotNull null
                val sections = v.listFiles()?.mapNotNull { f ->
                    Regex("""s(\d+)\.json""").matchEntire(f.name)?.groupValues?.get(1)?.toInt()
                }?.sorted().orEmpty()
                PrerenderSummary(
                    bookId = b.name.toLong(),
                    variant = PrerenderVariant.fromJson(meta.getJSONObject("variant")),
                    voiceName = meta.optString("voiceName"),
                    sections = sections,
                    bytes = v.walkTopDown().filter { it.isFile }.sumOf { it.length() },
                )
            }.orEmpty()
        }
    }

    fun delete(bookId: Long, variantId: String? = null) {
        val book = File(root, bookId.toString())
        if (variantId == null) book.deleteRecursively() else File(book, variantId).deleteRecursively()
    }

    fun deleteAll() = root.deleteRecursively()

    fun totalBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
