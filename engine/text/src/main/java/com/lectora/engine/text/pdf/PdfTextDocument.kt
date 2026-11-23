package com.lectora.engine.text.pdf

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import com.lectora.core.model.BookFormat
import com.lectora.engine.text.BookDocument
import com.lectora.engine.text.BookMetadata
import com.lectora.engine.text.SectionCache
import com.lectora.engine.text.TextCleaner
import com.lectora.engine.text.TocEntry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.io.ScratchFile
import com.tom_roush.pdfbox.pdfparser.PDFParser
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageTree
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.util.IdentityHashMap

/**
 * Texto de un PDF, una sección por página. Usa PdfBox-Android (Apache 2.0) para extraer el
 * texto y [TextCleaner] para reconstruir párrafos. El renderizado visual de páginas lo hace
 * [PdfPageRenderer] con el PdfRenderer del sistema.
 *
 * Pensado para libros de más de 10.000 páginas:
 * - se abre con acceso aleatorio sobre el archivo (no se copia ni se lee entero);
 * - cada página se extrae yendo directo a ella en el árbol de páginas (coste independiente
 *   del tamaño del libro);
 * - la caché de párrafos está acotada;
 * - el índice (marcadores del PDF) se calcula bajo demanda, una sola vez.
 */
class PdfTextDocument private constructor(
    private val document: PDDocument,
    private val fd: ParcelFileDescriptor?,
    override val metadata: BookMetadata,
) : BookDocument {

    override val format = BookFormat.PDF
    private val mutex = Mutex()
    private val cache = SectionCache(capacity = 48)

    @Volatile private var toc: List<TocEntry>? = null

    override fun sectionTitle(section: Int): String {
        val page = "Página ${section + 1}"
        val chapter = toc?.let { entries -> chapterAt(entries, section) } ?: return page
        return "$page · $chapter"
    }

    /** Último capítulo que empieza en o antes de [section] (búsqueda binaria). */
    private fun chapterAt(entries: List<TocEntry>, section: Int): String? {
        var lo = 0
        var hi = entries.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (entries[mid].section <= section) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return if (found >= 0) entries[found].title else null
    }

    override suspend fun paragraphs(section: Int): List<String> {
        if (section !in 0 until sectionCount) return emptyList()
        cache.get(section)?.let { return it }
        val t0 = System.nanoTime()
        val raw = withContext(Dispatchers.IO) {
            mutex.withLock {
                val stripper = SinglePageStripper(section).apply {
                    sortByPosition = true
                    lineSeparator = "\n"
                    paragraphStart = ""
                    paragraphEnd = "\n\n"
                }
                runCatching { stripper.getText(document) }
                    .onFailure { Log.w(TAG, "No se pudo extraer la página ${section + 1}", it) }
                    .getOrDefault("")
            }
        }
        val result = TextCleaner.rebuildPdfParagraphs(raw)
        Log.i(TAG, "página ${section + 1}: ${(System.nanoTime() - t0) / 1_000_000} ms, ${result.size} párrafos")
        cache.put(section, result)
        return result
    }

    override suspend fun tableOfContents(): List<TocEntry> {
        toc?.let { return it }
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                toc ?: buildToc().also { toc = it }
            }
        }
    }

    private fun buildToc(): List<TocEntry> {
        val t0 = System.nanoTime()
        val outline = runCatching { document.documentCatalog.documentOutline }.getOrNull() ?: return emptyList()
        // Índice de página por diccionario: una sola pasada por el árbol (evita indexOf O(n) por capítulo).
        val pageIndex = IdentityHashMap<COSDictionary, Int>(sectionCount)
        document.pages.forEachIndexed { i, page -> pageIndex[page.cosObject] = i }
        val out = ArrayList<TocEntry>()
        fun walk(node: PDOutlineNode, level: Int) {
            if (level > 3) return
            for (item in node.children()) {
                if (out.size >= MAX_TOC) return
                val title = item.title?.let { TextCleaner.normalize(it) }.orEmpty()
                val page = runCatching { item.findDestinationPage(document) }.getOrNull()
                val index = page?.let { pageIndex[it.cosObject] }
                if (title.isNotEmpty() && index != null) out.add(TocEntry(title, index, level))
                walk(item, level + 1)
            }
        }
        runCatching { walk(outline, 0) }.onFailure { Log.w(TAG, "Índice del PDF ilegible", it) }
        out.sortBy { it.section }
        Log.i(TAG, "índice: ${out.size} entradas en ${(System.nanoTime() - t0) / 1_000_000} ms")
        return out
    }

    override fun close() {
        cache.clear()
        runCatching { document.close() }
        runCatching { fd?.close() }
    }

    /**
     * PDFTextStripper recorre normalmente TODAS las páginas y descarta las que no están en el
     * rango: en un libro de 15.000 páginas eso es lento en cada cambio de página. Esta versión
     * va directa a la página pedida con [PDPageTree.get] (usa los contadores /Count del árbol),
     * y conserva los recursos heredados porque no toca la estructura del documento.
     */
    private class SinglePageStripper(private val pageIndex: Int) : PDFTextStripper() {
        init {
            // processPage() exige currentPageNo >= startPage; aquí currentPageNo queda en 0.
            startPage = 0
            endPage = Int.MAX_VALUE
        }

        override fun processPages(pages: PDPageTree) {
            val page = pages.get(pageIndex)
            if (page.hasContents()) processPage(page)
        }
    }

    companion object {
        private const val TAG = "PdfText"
        private const val MAX_TOC = 5000
        @Volatile private var initialized = false

        fun init(context: Context) {
            if (!initialized) {
                synchronized(this) {
                    if (!initialized) {
                        PDFBoxResourceLoader.init(context.applicationContext)
                        initialized = true
                    }
                }
            }
        }

        /**
         * Abre el PDF con acceso aleatorio sobre el descriptor (lecturas posicionales del
         * FileChannel), sin copiarlo. Si el proveedor no permite acceso aleatorio (p. ej. un
         * archivo que se descarga de la nube), se lee como flujo.
         */
        fun open(context: Context, fd: ParcelFileDescriptor): PdfTextDocument {
            init(context)
            val t0 = System.nanoTime()
            val (doc, keepFd) = try {
                val source = ChannelRandomAccessRead(FileInputStream(fd.fileDescriptor).channel)
                val parser = PDFParser(source, "", ScratchFile(MemoryUsageSetting.setupMixed(16L * 1024 * 1024)))
                parser.parse()
                parser.pdDocument to fd
            } catch (e: Exception) {
                Log.w(TAG, "Sin acceso aleatorio al PDF; se lee completo como flujo", e)
                ParcelFileDescriptor.AutoCloseInputStream(fd).use {
                    PDDocument.load(it, MemoryUsageSetting.setupMixed(32L * 1024 * 1024))
                } to null
            }
            Log.i(TAG, "PDF abierto: ${doc.numberOfPages} páginas en ${(System.nanoTime() - t0) / 1_000_000} ms")
            val info = doc.documentInformation
            val metadata = BookMetadata(
                title = info?.title?.trim()?.takeIf { it.isNotEmpty() },
                author = info?.author?.trim()?.takeIf { it.isNotEmpty() },
                language = runCatching { doc.documentCatalog.language }.getOrNull()
                    ?.substringBefore('-')?.lowercase()?.takeIf { it.length in 2..3 },
                sectionCount = doc.numberOfPages,
            )
            return PdfTextDocument(doc, keepFd, metadata)
        }
    }
}
