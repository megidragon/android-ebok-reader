package com.lectora.narration.prerender

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.Closeable
import java.io.File
import java.io.IOException

/**
 * Codifica PCM 16-bit mono a AAC-LC dentro de un .m4a (unas 8 veces menos espacio que PCM).
 * Uso: [write] repetidamente y al final [finish].
 */
class AacFileWriter(private val file: File, private val sampleRate: Int, bitRate: Int = 64_000) : Closeable {
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val info = MediaCodec.BufferInfo()
    private var track = -1
    private var muxerStarted = false
    private var samplesQueued = 0L
    private var finished = false

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    /** Duración de lo escrito hasta ahora, en milisegundos. */
    val writtenMs: Long get() = samplesQueued * 1000 / sampleRate

    fun write(pcm: ByteArray, offset: Int = 0, length: Int = pcm.size) {
        var off = offset
        var remaining = length - length % 2
        while (remaining > 0) {
            val index = codec.dequeueInputBuffer(10_000)
            if (index >= 0) {
                val buffer = codec.getInputBuffer(index)!!
                buffer.clear()
                val n = minOf(remaining, buffer.remaining()) and 1.inv()
                buffer.put(pcm, off, n)
                codec.queueInputBuffer(index, 0, n, samplesQueued * 1_000_000 / sampleRate, 0)
                samplesQueued += n / 2
                off += n
                remaining -= n
            }
            drain(false)
        }
    }

    fun writeSilence(millis: Int) {
        val bytes = (sampleRate.toLong() * millis / 1000).toInt() * 2
        if (bytes > 0) write(ByteArray(bytes))
    }

    fun finish() {
        if (finished) return
        finished = true
        while (true) {
            val index = codec.dequeueInputBuffer(10_000)
            if (index >= 0) {
                codec.queueInputBuffer(index, 0, 0, samplesQueued * 1_000_000 / sampleRate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                break
            }
            drain(false)
        }
        drain(true)
        close()
    }

    private fun drain(untilEnd: Boolean) {
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (untilEnd) 10_000 else 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!untilEnd) return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                index >= 0 -> {
                    val buffer = codec.getOutputBuffer(index)!!
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!isConfig && info.size > 0 && muxerStarted) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buffer, info)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    override fun close() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        if (muxerStarted) runCatching { muxer.stop() }
        runCatching { muxer.release() }
    }
}

/** Decodifica un .m4a AAC a PCM 16-bit, en trozos, desde una posición dada. */
object AacFileReader {
    /**
     * Llama a [onPcm] con trozos de PCM (16-bit) a partir de [startMs]. Devuelve la frecuencia de
     * muestreo real. [onPcm] puede suspender (control de ritmo) y devolver false para abortar.
     */
    suspend fun decode(file: File, startMs: Long, onFormat: (sampleRate: Int, channels: Int) -> Unit, onPcm: suspend (ByteArray) -> Boolean) {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)
        val trackIndex = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: throw IOException("Sin pista de audio en ${file.name}")
        extractor.selectTrack(trackIndex)
        val format = extractor.getTrackFormat(trackIndex)
        val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        decoder.configure(format, null, null, 0)
        decoder.start()
        var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        onFormat(sampleRate, channels)
        val startUs = startMs * 1000
        // Saltar directamente cerca del punto pedido (en AAC cada trama es punto de sincronía);
        // el recorte fino lo hace el bucle de abajo.
        if (startUs > 0) extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        try {
            while (true) {
                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buffer = decoder.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = decoder.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        sampleRate = decoder.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = decoder.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        onFormat(sampleRate, channels)
                    }
                    outIndex >= 0 -> {
                        val end = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        if (info.size > 0) {
                            val buffer = decoder.getOutputBuffer(outIndex)!!
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            var chunk = ByteArray(info.size).also { buffer.get(it) }
                            val frameBytes = 2 * channels
                            val chunkEndUs = info.presentationTimeUs + (chunk.size / frameBytes) * 1_000_000L / sampleRate
                            if (chunkEndUs > startUs) {
                                if (info.presentationTimeUs < startUs) {
                                    // Recortar el principio del trozo hasta startMs.
                                    val skipFrames = ((startUs - info.presentationTimeUs) * sampleRate / 1_000_000L).toInt()
                                    chunk = chunk.copyOfRange((skipFrames * frameBytes).coerceAtMost(chunk.size), chunk.size)
                                }
                                if (chunk.isNotEmpty() && !onPcm(chunk)) {
                                    decoder.releaseOutputBuffer(outIndex, false)
                                    return
                                }
                            }
                        }
                        decoder.releaseOutputBuffer(outIndex, false)
                        if (end) return
                    }
                }
            }
        } finally {
            runCatching { decoder.stop() }
            runCatching { decoder.release() }
            runCatching { extractor.release() }
        }
    }
}
