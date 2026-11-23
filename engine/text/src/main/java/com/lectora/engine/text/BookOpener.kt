package com.lectora.engine.text

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.lectora.core.model.BookFormat
import com.lectora.engine.text.epub.EpubDocument
import com.lectora.engine.text.pdf.PdfPageRenderer
import com.lectora.engine.text.pdf.PdfTextDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest

/** Punto de entrada para abrir libros desde URIs de contenido (SAF). */
class BookOpener(context: Context) {
    private val context = context.applicationContext
    private val resolver = context.contentResolver

    suspend fun open(uri: Uri, format: BookFormat): BookDocument = withContext(Dispatchers.IO) {
        when (format) {
            BookFormat.EPUB -> {
                val input = resolver.openInputStream(uri) ?: throw IOException("No se pudo abrir $uri")
                input.use { EpubDocument.open(it) }
            }
            // PDF: acceso aleatorio sobre el descriptor, sin copiar el archivo (libros de miles de páginas).
            BookFormat.PDF -> {
                val fd = resolver.openFileDescriptor(uri, "r") ?: throw IOException("No se pudo abrir $uri")
                PdfTextDocument.open(context, fd)
            }
        }
    }

    fun openPdfRenderer(uri: Uri): PdfPageRenderer {
        val fd = resolver.openFileDescriptor(uri, "r") ?: throw IOException("No se pudo abrir $uri")
        return PdfPageRenderer(fd)
    }

    /**
     * Huella estable del archivo: SHA-256 del primer MB + tamaño. Sirve para reconocer el mismo
     * libro aunque cambie de nombre o de carpeta, sin leer archivos enteros.
     */
    suspend fun fingerprint(uri: Uri, size: Long): String = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        resolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(64 * 1024)
            var remaining = 1024 * 1024
            while (remaining > 0) {
                val n = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (n <= 0) break
                digest.update(buffer, 0, n)
                remaining -= n
            }
        } ?: throw IOException("No se pudo leer $uri")
        digest.update(size.toString().toByteArray())
        digest.digest().joinToString("") { "%02x".format(it) }.take(32)
    }

    /** Extrae la portada como JPEG (EPUB: imagen declarada; PDF: primera página renderizada). */
    suspend fun extractCover(uri: Uri, format: BookFormat, document: BookDocument): ByteArray? =
        withContext(Dispatchers.IO) {
            runCatching {
                when (format) {
                    BookFormat.EPUB -> {
                        val path = (document as? EpubDocument)?.coverPath ?: return@runCatching null
                        resolver.openInputStream(uri)?.use { EpubDocument.readEntry(it, path) }
                    }
                    BookFormat.PDF -> openPdfRenderer(uri).use { renderer ->
                        if (renderer.pageCount == 0) return@runCatching null
                        val bitmap = renderer.render(0, 400)
                        ByteArrayOutputStream().use { out ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                            bitmap.recycle()
                            out.toByteArray()
                        }
                    }
                }
            }.getOrNull()
        }
}
