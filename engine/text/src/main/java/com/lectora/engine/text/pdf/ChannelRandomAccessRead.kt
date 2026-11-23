package com.lectora.engine.text.pdf

import com.tom_roush.pdfbox.io.RandomAccessRead
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * [RandomAccessRead] de PDFBox sobre un [FileChannel] (el descriptor que entrega SAF).
 *
 * PDFBox normalmente copia el PDF entero a memoria/archivo temporal cuando se le pasa un
 * InputStream; con libros de miles de páginas eso son segundos en cada apertura. Aquí se lee
 * solo lo que el parser pide, con lecturas posicionales y una pequeña caché de bloques
 * (la tabla xref y los objetos que se consultan seguido quedan en memoria).
 *
 * No es seguro para hilos: [PdfTextDocument] serializa el acceso con un Mutex.
 */
internal class ChannelRandomAccessRead(private val channel: FileChannel) : RandomAccessRead {
    private val length: Long = channel.size()
    private var position = 0L
    private var closed = false

    private val blocks = object : LinkedHashMap<Long, ByteArray>(MAX_BLOCKS + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?) = size > MAX_BLOCKS
    }
    private var current: ByteArray = ByteArray(0)
    private var currentStart = -1L

    /** Deja en [current] el bloque que contiene [pos]. */
    private fun ensure(pos: Long) {
        if (currentStart >= 0 && pos >= currentStart && pos < currentStart + current.size) return
        val start = pos - pos % BLOCK
        val cached = blocks[start]
        if (cached != null) {
            current = cached
            currentStart = start
            return
        }
        val size = minOf(BLOCK.toLong(), length - start).toInt()
        val data = ByteArray(size)
        val buffer = ByteBuffer.wrap(data)
        var read = 0
        while (read < size) {
            val n = channel.read(buffer, start + read)
            if (n <= 0) break
            read += n
        }
        val block = if (read == size) data else data.copyOf(read)
        blocks[start] = block
        current = block
        currentStart = start
    }

    override fun read(): Int {
        checkOpen()
        if (position >= length) return -1
        ensure(position)
        val b = current[(position - currentStart).toInt()].toInt() and 0xFF
        position++
        return b
    }

    override fun read(b: ByteArray): Int = read(b, 0, b.size)

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        checkOpen()
        if (len == 0) return 0
        if (position >= length) return -1
        var total = 0
        while (total < len && position < length) {
            ensure(position)
            val inBlock = (position - currentStart).toInt()
            val available = current.size - inBlock
            if (available <= 0) break
            val n = minOf(len - total, available)
            System.arraycopy(current, inBlock, b, off + total, n)
            position += n
            total += n
        }
        return if (total == 0) -1 else total
    }

    override fun getPosition(): Long = position

    override fun seek(pos: Long) {
        checkOpen()
        if (pos < 0) throw IOException("Posición inválida: $pos")
        position = pos
    }

    override fun length(): Long = length

    override fun isClosed(): Boolean = closed

    override fun peek(): Int {
        val r = read()
        if (r != -1) position--
        return r
    }

    override fun rewind(bytes: Int) {
        seek(position - bytes)
    }

    override fun readFully(length: Int): ByteArray {
        val out = ByteArray(length)
        var total = 0
        while (total < length) {
            val n = read(out, total, length - total)
            if (n < 0) throw EOFException("Fin del PDF tras $total de $length bytes")
            total += n
        }
        return out
    }

    override fun isEOF(): Boolean = position >= length

    override fun available(): Int = (length - position).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

    override fun close() {
        if (closed) return
        closed = true
        blocks.clear()
        channel.close()
    }

    private fun checkOpen() {
        if (closed) throw IOException("PDF cerrado")
    }

    private companion object {
        const val BLOCK = 64 * 1024
        /** 64 bloques × 64 KB = 4 MB como máximo. */
        const val MAX_BLOCKS = 64
    }
}
