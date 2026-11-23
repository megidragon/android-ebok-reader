package com.lectora.engine.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class SilenceTrimmerTest {
    private val format = PcmFormat(sampleRate = 1000) // 1 muestra = 1 ms, 2 bytes

    private fun pcm(vararg samples: Int): ByteArray = ByteArray(samples.size * 2).also { b ->
        samples.forEachIndexed { i, s ->
            b[2 * i] = (s and 0xFF).toByte()
            b[2 * i + 1] = (s shr 8).toByte()
        }
    }

    private fun samplesOf(bytes: ByteArray): List<Int> =
        (bytes.indices step 2).map { (bytes[it].toInt() and 0xFF) or (bytes[it + 1].toInt() shl 8) }

    private fun run(chunks: List<ByteArray>, preRoll: Int = 2, tail: Int = 3): List<Int> {
        val out = ByteArrayOutputStream()
        val t = SilenceTrimmer(format, { d, o, l -> out.write(d, o, l) }, thresholdAmplitude = 100, preRollMs = preRoll, tailMs = tail)
        chunks.forEach { t.feed(it, 0, it.size) }
        t.finish()
        return samplesOf(out.toByteArray())
    }

    @Test
    fun trimsLeadingAndTrailingSilence() {
        val silence = IntArray(50).toTypedArray().map { 0 }
        val voice = listOf(1000, -1000, 500)
        val result = run(listOf(pcm(*(silence + voice + silence).toIntArray())))
        // 2 ms de pre-roll + voz + 3 ms de cola
        assertEquals(2 + voice.size + 3, result.size)
        assertEquals(voice, result.subList(2, 2 + voice.size))
    }

    @Test
    fun keepsInternalPausesAcrossChunks() {
        val result = run(
            listOf(
                pcm(0, 0, 0, 0, 800, 800),
                pcm(0, 0, 0, 0, 0, 0, 0, 0), // pausa interna de 8 ms
                pcm(900, 0, 0, 0, 0, 0, 0, 0, 0, 0),
            ),
            preRoll = 0,
            tail = 0,
        )
        assertEquals(listOf(800, 800, 0, 0, 0, 0, 0, 0, 0, 0, 900), result)
    }

    @Test
    fun allSilenceProducesNothing() {
        assertTrue(run(listOf(pcm(0, 0, 0, 0), pcm(5, -5, 0))).isEmpty())
    }
}
