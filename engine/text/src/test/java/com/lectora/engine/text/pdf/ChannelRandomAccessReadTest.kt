package com.lectora.engine.text.pdf

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

class ChannelRandomAccessReadTest {

    private fun withFile(size: Int, block: (ByteArray, ChannelRandomAccessRead) -> Unit) {
        val data = ByteArray(size) { (it * 31 + 7).toByte() }
        val file = File.createTempFile("cra", ".bin").apply { writeBytes(data); deleteOnExit() }
        RandomAccessFile(file, "r").use { raf -> block(data, ChannelRandomAccessRead(raf.channel)) }
    }

    @Test
    fun readsAcrossBlockBoundariesAndSeeks() = withFile(300_000) { data, r ->
        assertEquals(300_000L, r.length())
        // Lectura que cruza dos bloques de 64 KB.
        r.seek(65_000)
        val buf = ByteArray(2_000)
        assertEquals(2_000, r.read(buf))
        assertArrayEquals(data.copyOfRange(65_000, 67_000), buf)
        assertEquals(67_000L, r.position)
        // peek no avanza; rewind retrocede.
        val p = r.peek()
        assertEquals(data[67_000].toInt() and 0xFF, p)
        assertEquals(67_000L, r.position)
        r.rewind(10)
        assertEquals(data[66_990].toInt() and 0xFF, r.read())
        // Saltos aleatorios hacia atrás (como hace el parser con la tabla xref).
        r.seek(5)
        assertArrayEquals(data.copyOfRange(5, 105), r.readFully(100))
    }

    @Test
    fun endOfFileBehaviour() = withFile(1_000) { data, r ->
        r.seek(990)
        val buf = ByteArray(50)
        assertEquals(10, r.read(buf, 0, 50))
        assertArrayEquals(data.copyOfRange(990, 1000), buf.copyOf(10))
        assertTrue(r.isEOF)
        assertEquals(-1, r.read())
        assertEquals(-1, r.read(buf, 0, 5))
        assertEquals(0, r.available())
    }
}
