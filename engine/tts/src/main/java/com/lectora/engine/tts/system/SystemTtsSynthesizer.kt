package com.lectora.engine.tts.system

import android.content.Context
import android.media.AudioFormat
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import com.lectora.engine.tts.EngineKind
import com.lectora.engine.tts.PcmFormat
import com.lectora.engine.tts.PcmSink
import com.lectora.engine.tts.SpeechEngineProvider
import com.lectora.engine.tts.SpeechSynthesizer
import com.lectora.engine.tts.SynthesisRequest
import com.lectora.engine.tts.VoiceInfo
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Motor TTS del sistema usado como generador de PCM.
 *
 * En lugar de `speak()` (que reproduce cada frase por separado y deja huecos), usa
 * `synthesizeToFile()` y captura el audio en `onAudioAvailable` según se genera. Si el motor no
 * entrega audio por callback, se lee el WAV resultante como respaldo.
 */
class SystemTtsSynthesizer(
    context: Context,
    private val enginePackage: String,
    private val engineLabel: String,
) : SpeechSynthesizer {

    private val appContext = context.applicationContext
    override val engineId: String = "system:$enginePackage"
    override val displayName: String = engineLabel
    override val kind = EngineKind.SYSTEM

    private val initMutex = Mutex()
    private val synthMutex = Mutex()
    private var tts: TextToSpeech? = null
    private var initFailed = false
    private val pending = ConcurrentHashMap<String, Pending>()
    private var cachedVoices: List<VoiceInfo>? = null

    private var appliedVoice: String? = null
    private var appliedLocale: Locale? = null
    private var appliedRate = -1f
    private var appliedPitch = -1f

    private class Pending(val sink: PcmSink) {
        @Volatile var gotAudio = false
        @Volatile var continuation: CancellableContinuation<Unit>? = null
        @Volatile var format: PcmFormat? = null
    }

    override suspend fun prepare(): Boolean = ensureTts() != null

    private suspend fun ensureTts(): TextToSpeech? = initMutex.withLock {
        tts?.let { return it }
        if (initFailed) return null
        val created = withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine<TextToSpeech?> { cont ->
                val holder = arrayOfNulls<TextToSpeech>(1)
                holder[0] = TextToSpeech(appContext, { status ->
                    if (cont.isActive) cont.resume(if (status == TextToSpeech.SUCCESS) holder[0] else null)
                }, enginePackage)
            }
        }
        if (created == null) {
            initFailed = true
            Log.w(TAG, "No se pudo inicializar el motor $enginePackage")
            return null
        }
        created.setOnUtteranceProgressListener(listener)
        tts = created
        created
    }

    override suspend fun voices(): List<VoiceInfo> {
        cachedVoices?.let { return it }
        val t = ensureTts() ?: return emptyList()
        val voices = withContext(Dispatchers.Default) {
            runCatching { t.voices.orEmpty() }.getOrDefault(emptySet()).map { it.toInfo() }
        }
        cachedVoices = voices
        return voices
    }

    private fun Voice.toInfo(): VoiceInfo {
        val notInstalled = features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true
        val country = locale.getDisplayCountry(Locale("es")).takeIf { it.isNotBlank() }
        val language = locale.getDisplayLanguage(Locale("es")).replaceFirstChar { it.titlecase(Locale("es")) }
        val variant = name.removePrefix(locale.toLanguageTag().lowercase()).trim('-', '_')
            .replace("-local", "").replace("-network", "").replace("x-", "")
            .ifBlank { name }
        val label = buildString {
            append(language)
            if (country != null) append(" (").append(country).append(")")
            append(" · ").append(variant)
        }
        return VoiceInfo(
            id = name,
            engineId = engineId,
            displayName = label,
            locale = locale,
            quality = quality,
            requiresNetwork = isNetworkConnectionRequired,
            installed = !notInstalled,
        )
    }

    override suspend fun synthesize(request: SynthesisRequest, sink: PcmSink) {
        val t = ensureTts() ?: throw IOException("Motor TTS no disponible: $engineLabel")
        synthMutex.withLock {
            applyParams(t, request)
            val id = UUID.randomUUID().toString()
            val dir = File(appContext.cacheDir, "tts").apply { mkdirs() }
            val file = File(dir, "$id.wav")
            val p = Pending(sink)
            pending[id] = p
            try {
                suspendCancellableCoroutine { cont ->
                    p.continuation = cont
                    cont.invokeOnCancellation { runCatching { t.stop() } }
                    val params = Bundle()
                    val result = t.synthesizeToFile(request.text, params, file, id)
                    if (result != TextToSpeech.SUCCESS && cont.isActive) {
                        cont.resumeWithException(IOException("synthesizeToFile falló ($result)"))
                    }
                }
                if (!p.gotAudio && file.exists()) readWavInto(file, sink)
            } finally {
                pending.remove(id)
                file.delete()
            }
        }
    }

    private fun applyParams(t: TextToSpeech, request: SynthesisRequest) {
        if (request.voiceId != null && request.voiceId != appliedVoice) {
            val voice = runCatching { t.voices }.getOrNull()?.firstOrNull { it.name == request.voiceId }
            if (voice != null && t.setVoice(voice) == TextToSpeech.SUCCESS) {
                appliedVoice = voice.name
                appliedLocale = voice.locale
            }
        }
        if (request.voiceId == null && appliedLocale?.language != request.locale.language) {
            t.setLanguage(request.locale)
            appliedLocale = request.locale
            appliedVoice = null
        }
        if (request.speechRate != appliedRate) {
            t.setSpeechRate(request.speechRate)
            appliedRate = request.speechRate
        }
        if (request.pitch != appliedPitch) {
            t.setPitch(request.pitch)
            appliedPitch = request.pitch
        }
    }

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) = Unit

        override fun onBeginSynthesis(utteranceId: String, sampleRateInHz: Int, audioFormat: Int, channelCount: Int) {
            val p = pending[utteranceId] ?: return
            val format = PcmFormat(sampleRateInHz, channelCount, audioFormat)
            p.format = format
            p.sink.onFormat(PcmFormat(sampleRateInHz, channelCount))
        }

        override fun onAudioAvailable(utteranceId: String, audio: ByteArray) {
            val p = pending[utteranceId] ?: return
            val pcm16 = PcmConvert.to16Bit(audio, p.format?.encoding ?: AudioFormat.ENCODING_PCM_16BIT)
            if (pcm16.isNotEmpty()) {
                p.gotAudio = true
                p.sink.onPcm(pcm16, 0, pcm16.size)
            }
        }

        override fun onDone(utteranceId: String) {
            pending[utteranceId]?.continuation?.let { if (it.isActive) it.resume(Unit) }
        }

        override fun onStop(utteranceId: String, interrupted: Boolean) {
            pending[utteranceId]?.continuation?.let { if (it.isActive) it.resume(Unit) }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String) {
            onError(utteranceId, TextToSpeech.ERROR)
        }

        override fun onError(utteranceId: String, errorCode: Int) {
            pending[utteranceId]?.continuation?.let {
                if (it.isActive) it.resumeWithException(IOException("Error del motor TTS ($errorCode)"))
            }
        }
    }

    /** Respaldo: lee un WAV PCM y lo entrega al sink. */
    private fun readWavInto(file: File, sink: PcmSink) {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 44) return
            val header = ByteArray(12)
            raf.readFully(header)
            var sampleRate = 22050
            var channels = 1
            var bits = 16
            while (raf.filePointer + 8 <= raf.length()) {
                val chunkHeader = ByteArray(8)
                raf.readFully(chunkHeader)
                val bb = ByteBuffer.wrap(chunkHeader).order(ByteOrder.LITTLE_ENDIAN)
                val id = String(chunkHeader, 0, 4)
                val size = bb.getInt(4)
                if (id == "fmt ") {
                    val fmt = ByteArray(size)
                    raf.readFully(fmt)
                    val fb = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                    channels = fb.getShort(2).toInt()
                    sampleRate = fb.getInt(4)
                    bits = fb.getShort(14).toInt()
                } else if (id == "data") {
                    sink.onFormat(PcmFormat(sampleRate, channels))
                    val buffer = ByteArray(16 * 1024)
                    var remaining = minOf(size.toLong(), raf.length() - raf.filePointer)
                    val encoding = if (bits == 8) AudioFormat.ENCODING_PCM_8BIT else AudioFormat.ENCODING_PCM_16BIT
                    while (remaining > 0) {
                        val n = raf.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (n <= 0) break
                        val chunk = PcmConvert.to16Bit(buffer.copyOf(n), encoding)
                        sink.onPcm(chunk, 0, chunk.size)
                        remaining -= n
                    }
                    return
                } else {
                    raf.seek(raf.filePointer + size)
                }
            }
        }
    }

    override fun release() {
        tts?.let { runCatching { it.stop(); it.shutdown() } }
        tts = null
        cachedVoices = null
        appliedVoice = null
        appliedLocale = null
        appliedRate = -1f
        appliedPitch = -1f
    }

    companion object {
        private const val TAG = "SystemTts"
    }
}

/** Enumera los motores TTS instalados (Google, Samsung, RHVoice, eSpeak NG, …). */
class SystemTtsProvider(context: Context) : SpeechEngineProvider {
    private val appContext = context.applicationContext

    override suspend fun engines(): List<SpeechSynthesizer> {
        val probe = withTimeoutOrNull(10_000) {
            suspendCancellableCoroutine<TextToSpeech?> { cont ->
                val holder = arrayOfNulls<TextToSpeech>(1)
                holder[0] = TextToSpeech(appContext) { if (cont.isActive) cont.resume(holder[0]) }
            }
        } ?: return emptyList()
        val engines = runCatching { probe.engines }.getOrDefault(emptyList())
        val default = runCatching { probe.defaultEngine }.getOrNull()
        probe.shutdown()
        return engines
            .sortedByDescending { it.name == default }
            .map { SystemTtsSynthesizer(appContext, it.name, it.label) }
    }
}
