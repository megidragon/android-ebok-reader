package com.lectora.engine.tts.system

import android.media.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object PcmConvert {
    /** Convierte PCM de 8 bits o float a 16 bits little-endian. */
    fun to16Bit(data: ByteArray, encoding: Int): ByteArray = when (encoding) {
        AudioFormat.ENCODING_PCM_8BIT -> ByteArray(data.size * 2).also { out ->
            for (i in data.indices) {
                val s = ((data[i].toInt() and 0xFF) - 128) shl 8
                out[2 * i] = (s and 0xFF).toByte()
                out[2 * i + 1] = (s shr 8).toByte()
            }
        }
        AudioFormat.ENCODING_PCM_FLOAT -> {
            val fb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val out = ByteArray(fb.remaining() * 2)
            var i = 0
            while (fb.hasRemaining()) {
                val s = (fb.get().coerceIn(-1f, 1f) * 32767f).toInt()
                out[i++] = (s and 0xFF).toByte()
                out[i++] = (s shr 8).toByte()
            }
            out
        }
        else -> data
    }
}
