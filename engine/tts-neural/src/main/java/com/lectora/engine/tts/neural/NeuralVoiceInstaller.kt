package com.lectora.engine.tts.neural

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/** Voz descargable. Los paquetes son los oficiales de sherpa-onnx (GitHub releases "tts-models"). */
data class CatalogVoice(
    val id: String,
    val name: String,
    val family: String,
    val description: String,
    val sizeMb: Int,
    val sampleRate: Int,
    val archive: String,
    val files: Map<String, String>,
    val speakers: List<NeuralSpeaker>,
) {
    val url: String get() = "$BASE/$archive"

    companion object {
        const val BASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models"
    }
}

object NeuralVoiceCatalog {
    private fun piper(
        voice: String,
        name: String,
        description: String,
        sizeMb: Int,
        sampleRate: Int,
        speakers: List<NeuralSpeaker>,
    ) = CatalogVoice(
        id = "piper-$voice",
        name = name,
        family = "piper",
        description = description,
        sizeMb = sizeMb,
        sampleRate = sampleRate,
        archive = "vits-piper-$voice.tar.bz2",
        files = mapOf("model" to "$voice.onnx", "tokens" to "tokens.txt", "dataDir" to "espeak-ng-data"),
        speakers = speakers,
    )

    val voices: List<CatalogVoice> = listOf(
        CatalogVoice(
            id = "kokoro-multi-v1_0-int8",
            name = "Kokoro",
            family = "kokoro",
            description = "La más natural (español: Dora, Alex, Santa; e inglés). Necesita un móvil de gama alta: en gamas media/baja va más lenta que la lectura y habrá pausas.",
            sizeMb = 126,
            sampleRate = 24000,
            archive = "kokoro-int8-multi-lang-v1_0.tar.bz2",
            files = mapOf(
                "model" to "model.int8.onnx",
                "voices" to "voices.bin",
                "tokens" to "tokens.txt",
                "dataDir" to "espeak-ng-data",
                "lexiconUs" to "lexicon-us-en.txt",
                "lexiconZh" to "lexicon-zh.txt",
                "dictDir" to "dict",
            ),
            speakers = listOf(
                NeuralSpeaker(28, "Dora", "es", "f"),
                NeuralSpeaker(29, "Alex", "es", "m"),
                NeuralSpeaker(53, "Santa", "es", "m"),
                NeuralSpeaker(3, "Heart", "en-us", "f"),
                NeuralSpeaker(2, "Bella", "en-us", "f"),
                NeuralSpeaker(16, "Michael", "en-us", "m"),
                NeuralSpeaker(21, "Emma", "en-gb", "f"),
                NeuralSpeaker(26, "George", "en-gb", "m"),
            ),
        ),
        piper("es_ES-davefx-medium", "Davefx (España)", "Voz masculina, clara y rápida.", 64, 22050,
            listOf(NeuralSpeaker(0, "Davefx", "es", "m"))),
        piper("es_ES-sharvard-medium", "Sharvard (España)", "Dos voces: masculina y femenina.", 76, 22050,
            listOf(NeuralSpeaker(0, "Sharvard M", "es", "m"), NeuralSpeaker(1, "Sharvard F", "es", "f"))),
        piper("es_MX-claude-high", "México (alta calidad)", "Voz latinoamericana de alta calidad.", 64, 22050,
            listOf(NeuralSpeaker(0, "México", "es", ""))),
        piper("es_AR-daniela-high", "Daniela (Argentina)", "Voz femenina rioplatense de alta calidad.", 110, 22050,
            listOf(NeuralSpeaker(0, "Daniela", "es", "f"))),
        piper("es_MX-ald-medium", "Ald (México)", "Voz masculina latinoamericana.", 64, 22050,
            listOf(NeuralSpeaker(0, "Ald", "es", "m"))),
    )

    fun byId(id: String) = voices.firstOrNull { it.id == id }
}

sealed interface InstallState {
    data object NotInstalled : InstallState
    data class Downloading(val fraction: Float) : InstallState
    data object Extracting : InstallState
    data object Installed : InstallState
    data class Failed(val message: String) : InstallState
}

/** Descarga, descomprime e instala voces en filesDir/voices/<id>/ con su manifest.json. */
class NeuralVoiceInstaller(private val voicesDir: File) {
    private val _states = MutableStateFlow<Map<String, InstallState>>(emptyMap())
    val states: StateFlow<Map<String, InstallState>> = _states.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _states.value = NeuralVoiceCatalog.voices.associate { v ->
            v.id to if (File(File(voicesDir, v.id), NeuralVoiceModel.MANIFEST).exists()) InstallState.Installed else InstallState.NotInstalled
        }
    }

    private fun set(id: String, state: InstallState) = _states.update { it + (id to state) }

    suspend fun install(voice: CatalogVoice) = withContext(Dispatchers.IO) {
        val target = File(voicesDir, voice.id)
        val tmp = File(voicesDir, "${voice.id}.partial").apply { deleteRecursively(); mkdirs() }
        try {
            set(voice.id, InstallState.Downloading(0f))
            val connection = openFollowingRedirects(voice.url)
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: (voice.sizeMb * 1024L * 1024L)
            var lastReported = 0f
            val counting = object : FilterInputStream(connection.inputStream.buffered(256 * 1024)) {
                var read = 0L
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val n = super.read(b, off, len)
                    if (n > 0) {
                        read += n
                        val f = (read.toFloat() / total).coerceAtMost(1f)
                        if (f - lastReported >= 0.01f) {
                            lastReported = f
                            set(voice.id, InstallState.Downloading(f))
                        }
                    }
                    return n
                }
            }
            // Se descomprime mientras se descarga: no hace falta espacio para el .tar.bz2.
            counting.use { extractTarBz2(it, tmp) }
            coroutineContext.ensureActive()
            set(voice.id, InstallState.Extracting)

            val root = tmp.listFiles()?.singleOrNull { it.isDirectory } ?: tmp
            val model = NeuralVoiceModel(voice.id, voice.name, voice.family, voice.sampleRate, voice.speakers, voice.files, target)
            voice.files.forEach { (key, rel) ->
                if (!File(root, rel).exists()) throw IOException("Falta '$rel' ($key) en el paquete")
            }
            File(root, NeuralVoiceModel.MANIFEST).writeText(model.toJson().toString(2))
            target.deleteRecursively()
            if (!root.renameTo(target)) throw IOException("No se pudo mover la voz a ${target.name}")
            tmp.deleteRecursively()
            set(voice.id, InstallState.Installed)
        } catch (e: Exception) {
            tmp.deleteRecursively()
            Log.e("NeuralVoiceInstaller", "Falló la instalación de ${voice.id}", e)
            set(voice.id, if (e is kotlinx.coroutines.CancellationException) InstallState.NotInstalled else InstallState.Failed(e.message ?: "Error"))
            if (e is kotlinx.coroutines.CancellationException) throw e
        }
    }

    fun uninstall(voice: CatalogVoice) {
        File(voicesDir, voice.id).deleteRecursively()
        set(voice.id, InstallState.NotInstalled)
    }

    private fun openFollowingRedirects(url: String): HttpURLConnection {
        var current = url
        repeat(6) {
            val c = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 60_000
            }
            when (c.responseCode) {
                in 200..299 -> return c
                in 300..399 -> {
                    current = c.getHeaderField("Location") ?: throw IOException("Redirección sin destino")
                    c.disconnect()
                }
                else -> throw IOException("HTTP ${c.responseCode} al descargar")
            }
        }
        throw IOException("Demasiadas redirecciones")
    }

    private suspend fun extractTarBz2(input: InputStream, dest: File) {
        val canonical = dest.canonicalPath + File.separator
        TarArchiveInputStream(BZip2CompressorInputStream(input)).use { tar ->
            while (true) {
                coroutineContext.ensureActive()
                val entry = tar.nextEntry ?: break
                val out = File(dest, entry.name)
                if (!out.canonicalPath.startsWith(canonical)) throw IOException("Ruta inválida en el paquete: ${entry.name}")
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { tar.copyTo(it, 256 * 1024) }
                }
            }
        }
    }
}
