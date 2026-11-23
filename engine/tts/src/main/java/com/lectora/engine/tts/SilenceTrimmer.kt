package com.lectora.engine.tts

import java.io.ByteArrayOutputStream
import kotlin.math.abs

/**
 * Recorta el silencio al inicio y al final de cada oración sintetizada, en streaming.
 *
 * Los motores TTS añaden entre 100 y 500 ms de silencio a cada lado de cada frase; al
 * encadenar oraciones eso se percibe como "tirones". La app quita ese relleno y luego
 * inserta una pausa propia, corta y uniforme, que el usuario puede configurar.
 *
 * Los silencios internos (comas, pausas dramáticas) se conservan: solo se retiene el
 * silencio "pendiente" hasta saber si viene más voz detrás.
 */
class SilenceTrimmer(
    private val format: PcmFormat,
    private val emit: (ByteArray, Int, Int) -> Unit,
    thresholdAmplitude: Int = 350,
    preRollMs: Int = 15,
    private val tailMs: Int = 40,
) {
    private val threshold = thresholdAmplitude
    private val frame = format.bytesPerFrame
    private val preRollBytes = format.bytesForMillis(preRollMs)
    private var started = false
    private val pending = ByteArrayOutputStream()

    fun feed(data: ByteArray, offset: Int, length: Int) {
        var off = offset
        var len = length - (length % frame)
        if (len <= 0) return
        if (!started) {
            val first = firstLoud(data, off, len)
            if (first < 0) {
                // Todo silencio: guardar solo los últimos ms como "pre-roll".
                pending.reset()
                val keep = minOf(len, preRollBytes)
                pending.write(data, off + len - keep, keep)
                return
            }
            started = true
            val preStart = maxOf(off, first - preRollBytes).let { it - ((it - off) % frame) }
            val carried = pending.toByteArray()
            val need = preRollBytes - (first - preStart)
            if (need > 0 && carried.isNotEmpty()) {
                val take = minOf(need - need % frame, carried.size - carried.size % frame)
                if (take > 0) emit(carried, carried.size - take, take)
            }
            pending.reset()
            len -= preStart - off
            off = preStart
        }
        val last = lastLoud(data, off, len)
        if (last < 0) {
            pending.write(data, off, len)
            return
        }
        // Hay voz: el silencio retenido era interno, se emite tal cual.
        if (pending.size() > 0) {
            val held = pending.toByteArray()
            emit(held, 0, held.size)
            pending.reset()
        }
        val cut = last + frame - ((last - off) % frame)
        emit(data, off, cut - off)
        if (cut < off + len) pending.write(data, cut, off + len - cut)
    }

    /** Fin de la oración: emite una pequeña cola y descarta el resto del silencio final. */
    fun finish() {
        if (!started) return
        val tail = minOf(pending.size(), format.bytesForMillis(tailMs))
        if (tail > 0) {
            val held = pending.toByteArray()
            emit(held, 0, tail - tail % frame)
        }
        pending.reset()
    }

    private fun sample(data: ByteArray, i: Int): Int =
        (data[i].toInt() and 0xFF) or (data[i + 1].toInt() shl 8)

    private fun loudAt(data: ByteArray, frameStart: Int): Boolean {
        var i = frameStart
        val end = frameStart + frame
        while (i < end) {
            if (abs(sample(data, i)) > threshold) return true
            i += 2
        }
        return false
    }

    private fun firstLoud(data: ByteArray, off: Int, len: Int): Int {
        var i = off
        while (i < off + len) {
            if (loudAt(data, i)) return i
            i += frame
        }
        return -1
    }

    private fun lastLoud(data: ByteArray, off: Int, len: Int): Int {
        var i = off + len - frame
        while (i >= off) {
            if (loudAt(data, i)) return i
            i -= frame
        }
        return -1
    }
}
