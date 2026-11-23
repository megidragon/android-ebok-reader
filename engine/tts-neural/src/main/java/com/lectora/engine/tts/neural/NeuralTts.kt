package com.lectora.engine.tts.neural

import android.content.Context
import android.util.Log
import com.lectora.engine.tts.EngineKind
import com.lectora.engine.tts.PcmFormat
import com.lectora.engine.tts.PcmSink
import com.lectora.engine.tts.SpeechEngineProvider
import com.lectora.engine.tts.SpeechSynthesizer
import com.lectora.engine.tts.SynthesisRequest
import com.lectora.engine.tts.VoiceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.coroutines.coroutineContext

/*
 * TTS NEURONAL EN EL DISPOSITIVO
 * ------------------------------
 * Todo el pipeline de narración trabaja contra SpeechSynthesizer (texto -> PCM en streaming).
 * Aquí se adaptan modelos de IA a ese contrato:
 *
 *   NeuralVoiceModel  (voices/<id>/manifest.json)  ->  NeuralBackend (SherpaOnnxBackend)
 *        -> NeuralSession.generate()  ->  OnDeviceNeuralSynthesizer  ->  narración
 *
 * Para añadir otra familia de modelos basta con otro NeuralBackend o ampliar SherpaOnnxBackend.
 */

/** Un hablante dentro de un modelo (Piper suele tener 1; Kokoro, decenas). */
data class NeuralSpeaker(
    /** Índice de hablante (sid) dentro del modelo. */
    val sid: Int,
    val name: String,
    /** Etiqueta BCP-47 corta: "es", "en-us", "en-gb"… */
    val lang: String,
    /** "f" / "m" / "" */
    val gender: String = "",
)

/** Modelo instalado. Se lee de voices/<id>/manifest.json (lo escribe [NeuralVoiceInstaller]). */
data class NeuralVoiceModel(
    val id: String,
    val name: String,
    /** "piper" o "kokoro". */
    val family: String,
    val sampleRate: Int,
    val speakers: List<NeuralSpeaker>,
    /** Rutas relativas al directorio: model, tokens, dataDir, voices, lexicon… */
    val files: Map<String, String>,
    val directory: File,
) {
    fun file(key: String): String? = files[key]?.let { File(directory, it).absolutePath }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("family", family)
        put("sampleRate", sampleRate)
        put("files", JSONObject(files))
        put("speakers", JSONArray().apply {
            speakers.forEach { s ->
                put(JSONObject().put("sid", s.sid).put("name", s.name).put("lang", s.lang).put("gender", s.gender))
            }
        })
    }

    companion object {
        const val MANIFEST = "manifest.json"

        fun fromManifest(dir: File): NeuralVoiceModel? = runCatching {
            val json = JSONObject(File(dir, MANIFEST).readText())
            val files = json.getJSONObject("files").let { f -> f.keys().asSequence().associateWith { f.getString(it) } }
            val arr = json.getJSONArray("speakers")
            val speakers = List(arr.length()) {
                val s = arr.getJSONObject(it)
                NeuralSpeaker(s.getInt("sid"), s.getString("name"), s.getString("lang"), s.optString("gender"))
            }
            NeuralVoiceModel(
                id = json.getString("id"),
                name = json.getString("name"),
                family = json.getString("family"),
                sampleRate = json.getInt("sampleRate"),
                speakers = speakers,
                files = files,
                directory = dir,
            )
        }.onFailure { Log.w("NeuralTts", "Manifest inválido en $dir", it) }.getOrNull()
    }
}

/** Runtime de inferencia (sherpa-onnx, ONNX Runtime directo, TFLite…). */
interface NeuralBackend {
    fun supports(model: NeuralVoiceModel): Boolean

    /** Carga el modelo (puede tardar segundos; se llama una vez por modelo). */
    suspend fun load(model: NeuralVoiceModel): NeuralSession
}

interface NeuralSession {
    /** Frecuencia de muestreo real del audio generado. */
    val sampleRate: Int

    /**
     * Genera audio para [text]. Puede llamar a [onSamples] varias veces con muestras float en
     * [-1, 1]. Si [onSamples] devuelve false, se aborta la síntesis (cancelación).
     */
    fun generate(text: String, speaker: NeuralSpeaker, speed: Float, onSamples: (FloatArray) -> Boolean)

    fun close()
}

/** Adapta un modelo neuronal al contrato común [SpeechSynthesizer]. */
class OnDeviceNeuralSynthesizer(
    private val model: NeuralVoiceModel,
    private val backend: NeuralBackend,
) : SpeechSynthesizer {
    override val engineId = "neural:${model.id}"
    override val displayName = "IA · ${model.name}"
    override val kind = EngineKind.NEURAL_ON_DEVICE

    private val mutex = Mutex()
    private var session: NeuralSession? = null

    /**
     * No carga el modelo (cientos de MB de RAM y varios segundos de CPU): solo comprueba que los
     * archivos estén. La carga real ocurre en la primera síntesis con esta voz.
     */
    override suspend fun prepare(): Boolean = model.files.keys.all { key -> model.file(key)?.let { File(it).exists() } == true }

    override suspend fun warmUp() {
        runCatching { ensureSession() }.onFailure { Log.e(TAG, "No se pudo precargar ${model.id}", it) }
    }

    private suspend fun ensureSession(): NeuralSession = mutex.withLock {
        session ?: withContext(Dispatchers.Default) { backend.load(model) }.also { session = it }
    }

    override suspend fun voices(): List<VoiceInfo> = model.speakers.map { s ->
        VoiceInfo(
            id = s.sid.toString(),
            engineId = engineId,
            displayName = buildString {
                append(s.name)
                when (s.gender) {
                    "f" -> append(" (mujer)")
                    "m" -> append(" (hombre)")
                }
                append(" · ").append(model.name)
            },
            locale = Locale.forLanguageTag(s.lang),
            // La "calidad" decide la voz automática. Piper (rápida) va por encima de las del
            // sistema; Kokoro, por debajo: en móviles modestos es más lenta que tiempo real y
            // solo debe usarse si el usuario la elige a mano.
            quality = if (model.family == "kokoro") 350 else 460,
        )
    }

    override suspend fun synthesize(request: SynthesisRequest, sink: PcmSink) {
        val s = ensureSession()
        val speaker = model.speakers.firstOrNull { it.sid.toString() == request.voiceId }
            ?: model.speakers.firstOrNull { it.lang.substringBefore('-') == request.locale.language }
            ?: model.speakers.first()
        val context = coroutineContext
        withContext(Dispatchers.Default) {
            sink.onFormat(PcmFormat(s.sampleRate))
            val t0 = System.nanoTime()
            var samples = 0L
            // Una sola síntesis a la vez por modelo (ONNX Runtime ya usa varios hilos dentro).
            mutex.withLock {
                s.generate(request.text, speaker, request.speechRate) { chunk ->
                    if (!context.isActive) return@generate false
                    samples += chunk.size
                    val bytes = ByteArray(chunk.size * 2)
                    for (i in chunk.indices) {
                        val v = (chunk[i].coerceIn(-1f, 1f) * 32767f).toInt()
                        bytes[2 * i] = (v and 0xFF).toByte()
                        bytes[2 * i + 1] = (v shr 8).toByte()
                    }
                    sink.onPcm(bytes, 0, bytes.size)
                    true
                }
            }
            val elapsedMs = (System.nanoTime() - t0) / 1_000_000
            val audioMs = samples * 1000 / s.sampleRate
            if (audioMs > 0) {
                Log.i(TAG, "${model.id}/${speaker.name}: ${audioMs} ms de audio en ${elapsedMs} ms (RTF %.2f)".format(elapsedMs.toFloat() / audioMs))
            }
            context.ensureActive()
        }
    }

    override fun release() {
        session?.close()
        session = null
    }

    private companion object {
        const val TAG = "NeuralTts"
    }
}

/** Expone como motores los modelos instalados en filesDir/voices para los que haya backend. */
class NeuralTtsProvider(
    context: Context,
    private val backends: List<NeuralBackend>,
) : SpeechEngineProvider {
    val voicesDir = File(context.filesDir, "voices").apply { mkdirs() }

    fun installedModels(): List<NeuralVoiceModel> =
        voicesDir.listFiles()?.filter { it.isDirectory }?.mapNotNull { NeuralVoiceModel.fromManifest(it) }.orEmpty()

    override suspend fun engines(): List<SpeechSynthesizer> =
        installedModels().mapNotNull { model ->
            backends.firstOrNull { it.supports(model) }?.let { OnDeviceNeuralSynthesizer(model, it) }
        }
}
