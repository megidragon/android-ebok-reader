package com.lectora.engine.text.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable

/** Renderiza páginas de PDF a bitmaps con el PdfRenderer del sistema (vista "original"). */
class PdfPageRenderer(fd: ParcelFileDescriptor) : Closeable {
    private val renderer = PdfRenderer(fd)
    private val mutex = Mutex()

    val pageCount: Int get() = renderer.pageCount

    /** Relación de aspecto (alto / ancho) de la página. */
    suspend fun aspectRatio(index: Int): Float = mutex.withLock {
        renderer.openPage(index).use { it.height.toFloat() / it.width.toFloat() }
    }

    suspend fun render(index: Int, targetWidth: Int): Bitmap = withContext(Dispatchers.IO) {
        mutex.withLock {
            renderer.openPage(index).use { page ->
                val width = targetWidth.coerceIn(200, 2400)
                val height = (width * page.height.toFloat() / page.width).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            }
        }
    }

    override fun close() {
        runCatching { renderer.close() }
    }
}
