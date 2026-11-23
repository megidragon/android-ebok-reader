package com.lectora.engine.tts.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import com.lectora.engine.tts.PcmFormat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/** Elementos que fluyen del productor (síntesis) al consumidor (altavoz). */
sealed interface AudioItem {
    data class Format(val format: PcmFormat) : AudioItem
    class Pcm(val data: ByteArray, val length: Int = data.size) : AudioItem
    data class Silence(val millis: Int) : AudioItem

    /** Se notifica cuando la reproducción real alcanza este punto (no cuando se escribe). */
    data class Marker(val tag: Any) : AudioItem
    data object End : AudioItem
}

/**
 * Reproduce un flujo continuo de PCM por un único [AudioTrack] en modo streaming.
 *
 * Como nunca se detiene entre oraciones o páginas, no hay huecos: el productor va varios
 * segundos por delante y aquí solo se escribe. Los [AudioItem.Marker] se resuelven contra la
 * posición real del cabezal de reproducción, para resaltar la oración que de verdad suena.
 *
 * Cada sesión de reproducción (play desde una posición) usa una instancia nueva; buscar otra
 * posición = cancelar la corrutina de [play] y crear otra.
 */
class GaplessAudioOutput(private val listener: Listener) {

    interface Listener {
        /** Se llama en el hilo de audio. Debe ser rápido. */
        fun onMarkerReached(tag: Any)

        /** Milisegundos de audio escritos que aún no suenan (para control de lookahead). */
        fun onQueuedAudioChanged(queuedMs: Long) = Unit
    }

    @Volatile private var paused = false
    private var track: AudioTrack? = null
    private var format: PcmFormat? = null
    private var framesWritten = 0L
    private val markers = ArrayDeque<Pair<Long, Any>>()
    private val lock = Any()

    fun pause() {
        paused = true
        runCatching { track?.pause() }
    }

    fun resume() {
        paused = false
        runCatching { track?.play() }
    }

    /** Consume [items] hasta [AudioItem.End] o cancelación. Libera el AudioTrack al salir. */
    suspend fun play(items: ReceiveChannel<AudioItem>) = withContext(audioDispatcher) {
        coroutineScope {
            val ticker = launch {
                while (isActive) {
                    deliverMarkers()
                    delay(30)
                }
            }
            try {
                for (item in items) {
                    when (item) {
                        is AudioItem.Format -> ensureTrack(item.format)
                        is AudioItem.Pcm -> write(item.data, 0, item.length)
                        is AudioItem.Silence -> writeSilence(item.millis)
                        is AudioItem.Marker -> addMarker(item.tag)
                        AudioItem.End -> {
                            drain()
                            break
                        }
                    }
                }
            } finally {
                ticker.cancel()
                releaseTrack()
            }
        }
    }

    private fun addMarker(tag: Any) {
        synchronized(lock) { markers.addLast(framesWritten to tag) }
        if (track == null) deliverMarkers()
    }

    private fun headFrames(): Long {
        val t = track ?: return framesWritten
        return t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
    }

    private fun deliverMarkers() {
        val head = headFrames()
        while (true) {
            val tag = synchronized(lock) {
                val first = markers.firstOrNull()
                if (first != null && first.first <= head) markers.removeFirst().second else null
            } ?: break
            listener.onMarkerReached(tag)
        }
        format?.let { f ->
            val queued = (framesWritten - head).coerceAtLeast(0)
            listener.onQueuedAudioChanged(queued * 1000 / f.sampleRate)
        }
    }

    private suspend fun ensureTrack(newFormat: PcmFormat) {
        if (newFormat == format && track != null) return
        if (track != null) {
            drain()
            releaseTrack()
        }
        val channelMask = if (newFormat.channelCount == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minBuffer = AudioTrack.getMinBufferSize(newFormat.sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        val bufferBytes = maxOf(minBuffer * 2, newFormat.bytesForMillis(250))
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(newFormat.sampleRate)
                    .setChannelMask(channelMask)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        format = newFormat
        framesWritten = 0
        if (!paused) t.play()
    }

    private suspend fun write(data: ByteArray, offset: Int, length: Int) {
        val t = track ?: return // sin formato todavía: el motor no informó; se descarta
        val frameSize = format!!.bytesPerFrame
        var off = offset
        var remaining = length - length % frameSize
        while (remaining > 0) {
            val n = t.write(data, off, remaining, AudioTrack.WRITE_NON_BLOCKING)
            when {
                n < 0 -> throw IllegalStateException("AudioTrack.write error $n")
                n == 0 -> {
                    deliverMarkers()
                    delay(if (paused) 50 else 8)
                }
                else -> {
                    off += n
                    remaining -= n
                    framesWritten += n / frameSize
                }
            }
        }
    }

    private val silenceBuffer = ByteArray(8192)

    private suspend fun writeSilence(millis: Int) {
        val f = format ?: return
        var bytes = f.bytesForMillis(millis)
        while (bytes > 0) {
            val n = minOf(bytes, silenceBuffer.size)
            write(silenceBuffer, 0, n)
            bytes -= n
        }
    }

    /** Espera a que suene todo lo escrito (los markers pendientes se entregan por el camino). */
    private suspend fun drain() {
        val t = track ?: run {
            deliverMarkers()
            return
        }
        while (paused) delay(50)
        // stop() en modo streaming deja terminar el contenido del buffer.
        runCatching { t.stop() }
        var lastHead = -1L
        var stalled = 0
        while (headFrames() < framesWritten) {
            deliverMarkers()
            delay(20)
            val head = headFrames()
            if (head == lastHead && !paused) {
                if (++stalled > 50) break // ~1 s sin avanzar: no esperar más
            } else {
                stalled = 0
            }
            lastHead = head
        }
        deliverMarkers()
        synchronized(lock) { markers.clear() }
    }

    private fun releaseTrack() {
        track?.let {
            android.util.Log.i("GaplessAudio", "Fin de pista: ${framesWritten} frames, underruns=${runCatching { it.underrunCount }.getOrDefault(-1)}")
            runCatching { it.pause() }
            runCatching { it.flush() }
            runCatching { it.release() }
        }
        track = null
        format = null
        framesWritten = 0
    }

    companion object {
        /** Hilo dedicado con prioridad de audio: sigue alimentando el altavoz con la pantalla apagada. */
        private val audioDispatcher: CoroutineDispatcher = Executors.newSingleThreadExecutor { r ->
            Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                r.run()
            }, "lectora-audio").apply { isDaemon = true }
        }.asCoroutineDispatcher()
    }
}
