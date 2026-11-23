package com.lectora.engine.tts.neural

import android.util.Log
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig

/**
 * Backend con sherpa-onnx (Apache 2.0): ONNX Runtime en CPU, con fonemización espeak-ng incluida.
 *
 * - "piper": modelos VITS de Piper (muy rápidos, una voz por modelo).
 * - "kokoro": Kokoro-82M multilingüe (más natural; un modelo con muchas voces y el idioma
 *   elegido por frase, así una misma instancia sirve para español e inglés).
 */
class SherpaOnnxBackend(
    /**
     * Hilos de ONNX Runtime. Medido en un Dimensity 6300 (2×A76 + 6×A55): 2 hilos es lo más
     * rápido; con 4 entran los núcleos lentos y Kokoro pasa de RTF ~2,0 a ~2,5.
     */
    private val numThreads: Int = 2,
) : NeuralBackend {

    override fun supports(model: NeuralVoiceModel): Boolean = model.family == "piper" || model.family == "kokoro"

    override suspend fun load(model: NeuralVoiceModel): NeuralSession {
        val t0 = System.nanoTime()
        val modelConfig = when (model.family) {
            "piper" -> OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = model.file("model")!!,
                    tokens = model.file("tokens")!!,
                    dataDir = model.file("dataDir")!!,
                ),
                numThreads = numThreads,
                provider = "cpu",
            )
            "kokoro" -> OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = model.file("model")!!,
                    voices = model.file("voices")!!,
                    tokens = model.file("tokens")!!,
                    dataDir = model.file("dataDir")!!,
                    // Obligatorio para el modelo multilingüe; el idioma real va por frase (extra "lang").
                    lexicon = listOfNotNull(model.file("lexiconUs"), model.file("lexiconZh")).joinToString(","),
                    dictDir = model.file("dictDir").orEmpty(),
                ),
                numThreads = numThreads,
                provider = "cpu",
            )
            else -> error("Familia no soportada: ${model.family}")
        }
        val tts = OfflineTts(assetManager = null, config = OfflineTtsConfig(model = modelConfig, maxNumSentences = 1))
        Log.i(TAG, "${model.id} cargado en ${(System.nanoTime() - t0) / 1_000_000} ms (${tts.sampleRate()} Hz, $numThreads hilos)")
        return SherpaSession(tts, model.family == "kokoro")
    }

    private class SherpaSession(private val tts: OfflineTts, private val kokoro: Boolean) : NeuralSession {
        override val sampleRate: Int = tts.sampleRate()

        override fun generate(text: String, speaker: NeuralSpeaker, speed: Float, onSamples: (FloatArray) -> Boolean) {
            val callback = SamplesCallback(onSamples)
            if (kokoro) {
                val config = GenerationConfig(
                    sid = speaker.sid,
                    speed = speed,
                    // Fonemización espeak-ng en el idioma de la voz (es, en-us, en-gb…).
                    extra = mapOf("lang" to speaker.lang),
                )
                tts.generateWithConfigAndCallback(text, config, callback)
            } else {
                tts.generateWithCallback(text, speaker.sid, speed, callback)
            }
        }

        override fun close() {
            runCatching { tts.release() }
        }
    }

    /**
     * El código nativo de sherpa-onnx busca por JNI el método `invoke([F)Ljava/lang/Integer;` en
     * la clase del callback. Una lambda de Kotlin 2 (invokedynamic) no lo tiene y la app aborta;
     * una clase explícita sí lo genera.
     */
    private class SamplesCallback(private val onSamples: (FloatArray) -> Boolean) : (FloatArray) -> Int {
        override fun invoke(samples: FloatArray): Int = if (onSamples(samples)) 1 else 0
    }

    private companion object {
        const val TAG = "SherpaOnnx"
    }
}
