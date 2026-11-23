package com.lectora.engine.tts

import android.media.AudioFormat
import java.util.Locale

/** Formato del PCM que entrega un motor. */
data class PcmFormat(
    val sampleRate: Int,
    val channelCount: Int = 1,
    /** Siempre [AudioFormat.ENCODING_PCM_16BIT] tras pasar por los motores de esta app. */
    val encoding: Int = AudioFormat.ENCODING_PCM_16BIT,
) {
    val bytesPerFrame: Int get() = channelCount * 2
    fun bytesForMillis(ms: Int): Int = (sampleRate.toLong() * ms / 1000).toInt() * bytesPerFrame
    fun millisForBytes(bytes: Long): Long = bytes * 1000 / (sampleRate.toLong() * bytesPerFrame)
}

enum class EngineKind {
    /** Motor TTS instalado en el sistema (Google, Samsung, RHVoice, eSpeak…). */
    SYSTEM,

    /** Modelo de IA que corre dentro de la app (Piper, Kokoro, etc.). */
    NEURAL_ON_DEVICE,
}

data class VoiceInfo(
    /** Identificador único dentro del motor. */
    val id: String,
    val engineId: String,
    val displayName: String,
    val locale: Locale,
    /** 0..500, igual que [android.speech.tts.Voice.getQuality]. */
    val quality: Int = 300,
    val requiresNetwork: Boolean = false,
    val installed: Boolean = true,
) {
    /** Clave estable para guardar la selección: "motor|voz". */
    val key: String get() = "$engineId|$id"

    companion object {
        fun parseKey(key: String): Pair<String, String>? =
            key.split('|', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
    }
}

data class SynthesisRequest(
    val text: String,
    val locale: Locale,
    val voiceId: String?,
    /** 1.0 = normal. */
    val speechRate: Float = 1f,
    /** 1.0 = normal. */
    val pitch: Float = 1f,
)

/**
 * Receptor de audio. Los motores pueden llamar desde cualquier hilo, pero siempre de forma
 * secuencial para una misma síntesis: primero [onFormat], luego cero o más [onPcm].
 */
interface PcmSink {
    fun onFormat(format: PcmFormat)

    /** PCM 16-bit little-endian intercalado. El arreglo puede reutilizarse tras la llamada. */
    fun onPcm(data: ByteArray, offset: Int, length: Int)
}

/**
 * Contrato común de todos los motores de voz.
 *
 * Los motores entregan PCM en streaming (no reproducen por sí mismos): así la app controla un
 * único flujo de audio continuo, sin huecos entre oraciones ni entre páginas, y cualquier motor
 * futuro (p. ej. un modelo neuronal ligero en el dispositivo) se integra implementando esta interfaz.
 */
interface SpeechSynthesizer {
    val engineId: String
    val displayName: String
    val kind: EngineKind

    /** Inicializa el motor si hace falta. Devuelve false si no está disponible. */
    suspend fun prepare(): Boolean

    suspend fun voices(): List<VoiceInfo>

    /**
     * Precarga lo costoso (p. ej. un modelo neuronal) para que la primera frase suene antes.
     * Se llama solo para la voz elegida por el usuario.
     */
    suspend fun warmUp() = Unit

    /**
     * Sintetiza [request] y entrega el audio a [sink] a medida que se genera.
     * Suspende hasta terminar; la cancelación de la corrutina debe abortar la síntesis.
     */
    suspend fun synthesize(request: SynthesisRequest, sink: PcmSink)

    fun release()
}

/** Proveedor de motores (uno para los del sistema, otro para los modelos neuronales, …). */
interface SpeechEngineProvider {
    suspend fun engines(): List<SpeechSynthesizer>
}
