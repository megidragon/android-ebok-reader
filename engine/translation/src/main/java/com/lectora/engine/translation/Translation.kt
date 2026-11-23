package com.lectora.engine.translation

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import java.io.IOException
import java.util.Locale

/** Motor de traducción de texto plano, 100% en el dispositivo tras descargar los modelos. */
interface TextTranslator {
    /** Idiomas soportados (códigos BCP-47 cortos: "en", "es", "fr"…). */
    fun supportedLanguages(): List<String>

    suspend fun downloadedLanguages(): Set<String>

    /** Descarga el modelo de un idioma (unos 30 MB). Necesita internet una sola vez. */
    suspend fun download(language: String, wifiOnly: Boolean = false)

    suspend fun delete(language: String)

    /** Traduce sin conexión. Lanza [ModelNotDownloadedException] si falta algún modelo. */
    suspend fun translate(text: String, source: String, target: String): String

    /** Detecta el idioma de un fragmento (offline). null si no está claro. */
    suspend fun detectLanguage(text: String): String?
}

class ModelNotDownloadedException(val language: String) :
    IOException("Falta el modelo de traducción para '$language'")

/** Traducción por párrafo con caché persistente (la implementa la app sobre la base de datos). */
interface ParagraphTranslator {
    suspend fun translate(bookId: Long, section: Int, paragraph: Int, text: String, source: String, target: String): String
}

/** Implementación con Google ML Kit (on-device, gratuita). */
class MlKitTranslationEngine : TextTranslator {
    private val modelManager = RemoteModelManager.getInstance()
    private val clients = HashMap<Pair<String, String>, Translator>()
    private val mutex = Mutex()
    private val languageId by lazy { LanguageIdentification.getClient() }

    override fun supportedLanguages(): List<String> =
        TranslateLanguage.getAllLanguages().sortedBy { Locale(it).getDisplayLanguage(Locale("es")) }

    override suspend fun downloadedLanguages(): Set<String> =
        modelManager.getDownloadedModels(TranslateRemoteModel::class.java).await().map { it.language }.toSet()

    override suspend fun download(language: String, wifiOnly: Boolean) {
        val code = TranslateLanguage.fromLanguageTag(language) ?: throw IOException("Idioma no soportado: $language")
        val conditions = DownloadConditions.Builder().apply { if (wifiOnly) requireWifi() }.build()
        modelManager.download(TranslateRemoteModel.Builder(code).build(), conditions).await()
    }

    override suspend fun delete(language: String) {
        val code = TranslateLanguage.fromLanguageTag(language) ?: return
        if (code == TranslateLanguage.ENGLISH) return // el inglés es el pivote, ML Kit lo necesita
        modelManager.deleteDownloadedModel(TranslateRemoteModel.Builder(code).build()).await()
    }

    private suspend fun client(source: String, target: String): Translator {
        val src = TranslateLanguage.fromLanguageTag(source) ?: throw IOException("Idioma no soportado: $source")
        val tgt = TranslateLanguage.fromLanguageTag(target) ?: throw IOException("Idioma no soportado: $target")
        val downloaded = downloadedLanguages()
        listOf(src, tgt).firstOrNull { it !in downloaded }?.let { throw ModelNotDownloadedException(it) }
        return mutex.withLock {
            clients.getOrPut(src to tgt) {
                Translation.getClient(
                    TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(tgt).build()
                )
            }
        }
    }

    override suspend fun translate(text: String, source: String, target: String): String {
        if (text.isBlank() || source.equals(target, ignoreCase = true)) return text
        val translator = client(source, target)
        if (text.length <= CHUNK) return translator.translate(text).await()
        return chunks(text).map { translator.translate(it).await() }.joinToString(" ")
    }

    override suspend fun detectLanguage(text: String): String? {
        val code = languageId.identifyLanguage(text.take(2000)).await()
        return code.takeIf { it != "und" }
    }

    private fun chunks(text: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (sentence in text.split(Regex("(?<=[.!?…])\\s+"))) {
            if (sb.length + sentence.length > CHUNK && sb.isNotEmpty()) {
                out.add(sb.toString())
                sb.setLength(0)
            }
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(sentence)
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    fun close() {
        clients.values.forEach { it.close() }
        clients.clear()
    }

    private companion object {
        const val CHUNK = 2500
    }
}
