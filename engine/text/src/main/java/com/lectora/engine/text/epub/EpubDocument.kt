package com.lectora.engine.text.epub

import com.lectora.core.model.BookFormat
import com.lectora.engine.text.BookDocument
import com.lectora.engine.text.BookMetadata
import com.lectora.engine.text.SectionCache
import com.lectora.engine.text.TextCleaner
import com.lectora.engine.text.TocEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import java.io.IOException
import java.io.InputStream
import java.net.URLDecoder
import java.util.zip.ZipInputStream

/**
 * Lector EPUB ligero (EPUB 2 y 3) orientado a extraer texto: lee el contenedor ZIP una sola vez,
 * conserva en memoria solo los recursos de texto y expone cada documento del "spine" como sección.
 */
class EpubDocument private constructor(
    private val entries: Map<String, ByteArray>,
    private val spine: List<String>,
    private val titles: Map<String, String>,
    override val metadata: BookMetadata,
    /** Ruta interna de la imagen de portada, si se declaró. */
    val coverPath: String?,
) : BookDocument {

    override val format = BookFormat.EPUB
    private val cache = SectionCache(capacity = 24)

    override fun sectionTitle(section: Int): String? = spine.getOrNull(section)?.let { titles[it] }

    override suspend fun paragraphs(section: Int): List<String> {
        cache.get(section)?.let { return it }
        val path = spine.getOrNull(section) ?: return emptyList()
        val bytes = entries[path] ?: return emptyList()
        val result = withContext(Dispatchers.Default) { extractParagraphs(bytes) }
        cache.put(section, result)
        return result
    }

    override suspend fun tableOfContents(): List<TocEntry> =
        spine.mapIndexedNotNull { i, path -> titles[path]?.let { TocEntry(it, i) } }

    override fun close() {
        cache.clear()
    }

    companion object {
        private val TEXT_EXTENSIONS = setOf("xml", "opf", "ncx", "xhtml", "html", "htm", "xht")

        /** Abre el EPUB leyendo el flujo una sola vez. */
        @Throws(IOException::class)
        fun open(input: InputStream): EpubDocument {
            val entries = HashMap<String, ByteArray>()
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val ext = entry.name.substringAfterLast('.', "").lowercase()
                    if (ext in TEXT_EXTENSIONS || entry.name == "mimetype") {
                        entries[entry.name] = zip.readBytes()
                    }
                }
            }
            return parse(entries)
        }

        /** Lee una entrada binaria concreta del ZIP (p. ej. la portada). */
        fun readEntry(input: InputStream, path: String): ByteArray? {
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: return null
                    if (entry.name == path) return zip.readBytes()
                }
            }
        }

        private fun parse(entries: Map<String, ByteArray>): EpubDocument {
            val container = entries["META-INF/container.xml"]
                ?: throw IOException("EPUB inválido: falta META-INF/container.xml")
            val containerDoc = Jsoup.parse(String(container), "", Parser.xmlParser())
            val opfPath = containerDoc.getElementsByTag("rootfile").firstOrNull()?.attr("full-path")
                ?.takeIf { it.isNotBlank() }
                ?: entries.keys.firstOrNull { it.endsWith(".opf") }
                ?: throw IOException("EPUB inválido: no se encontró el paquete OPF")
            val opfBytes = entries[opfPath] ?: throw IOException("EPUB inválido: falta $opfPath")
            val opf = Jsoup.parse(String(opfBytes), "", Parser.xmlParser())
            val baseDir = opfPath.substringBeforeLast('/', "")

            fun meta(tag: String): String? =
                (opf.getElementsByTag("dc:$tag").firstOrNull() ?: opf.getElementsByTag(tag).firstOrNull())
                    ?.text()?.trim()?.takeIf { it.isNotEmpty() }

            data class Item(val id: String, val path: String, val mediaType: String, val properties: String)

            val items = opf.getElementsByTag("item").map {
                Item(
                    id = it.attr("id"),
                    path = resolve(baseDir, it.attr("href")),
                    mediaType = it.attr("media-type"),
                    properties = it.attr("properties"),
                )
            }
            val byId = items.associateBy { it.id }
            val spine = opf.getElementsByTag("itemref")
                .mapNotNull { byId[it.attr("idref")]?.path }
                .filter { entries.containsKey(it) }

            val coverId = opf.getElementsByTag("meta").firstOrNull { it.attr("name") == "cover" }?.attr("content")
            val cover = items.firstOrNull { "cover-image" in it.properties }
                ?: coverId?.let { byId[it] }?.takeIf { it.mediaType.startsWith("image") }
                ?: items.firstOrNull { it.mediaType.startsWith("image") && it.id.contains("cover", ignoreCase = true) }

            val titles = HashMap<String, String>()
            items.firstOrNull { "nav" in it.properties.split(' ') }?.let { nav ->
                entries[nav.path]?.let { parseNav(String(it), nav.path.substringBeforeLast('/', ""), titles) }
            }
            if (titles.isEmpty()) {
                items.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }?.let { ncx ->
                    entries[ncx.path]?.let { parseNcx(String(it), ncx.path.substringBeforeLast('/', ""), titles) }
                }
            }

            val metadata = BookMetadata(
                title = meta("title"),
                author = meta("creator"),
                language = meta("language")?.substringBefore('-')?.lowercase(),
                sectionCount = spine.size,
            )
            return EpubDocument(entries, spine, titles, metadata, cover?.path)
        }

        private fun parseNav(html: String, dir: String, out: MutableMap<String, String>) {
            val doc = Jsoup.parse(html, "", Parser.xmlParser())
            val toc = doc.getElementsByTag("nav").firstOrNull { it.attr("epub:type") == "toc" }
                ?: doc.getElementsByTag("nav").firstOrNull()
                ?: return
            for (a in toc.getElementsByTag("a")) {
                val href = a.attr("href").substringBefore('#')
                if (href.isEmpty()) continue
                val title = a.text().trim()
                if (title.isNotEmpty()) out.putIfAbsent(resolve(dir, href), title)
            }
        }

        private fun parseNcx(xml: String, dir: String, out: MutableMap<String, String>) {
            val doc = Jsoup.parse(xml, "", Parser.xmlParser())
            for (point in doc.getElementsByTag("navPoint")) {
                val src = point.getElementsByTag("content").firstOrNull()?.attr("src")?.substringBefore('#') ?: continue
                val title = point.getElementsByTag("navLabel").firstOrNull()?.text()?.trim().orEmpty()
                if (title.isNotEmpty()) out.putIfAbsent(resolve(dir, src), title)
            }
        }

        internal fun resolve(baseDir: String, href: String): String {
            val decoded = runCatching { URLDecoder.decode(href.replace("+", "%2B"), "UTF-8") }.getOrDefault(href)
            val parts = ArrayList<String>()
            if (baseDir.isNotEmpty() && !decoded.startsWith("/")) parts.addAll(baseDir.split('/'))
            for (p in decoded.trimStart('/').split('/')) {
                when (p) {
                    "", "." -> Unit
                    ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                    else -> parts.add(p)
                }
            }
            return parts.joinToString("/")
        }

        // ---- Extracción de párrafos ----

        private val BLOCK_TAGS = setOf(
            "p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "li", "blockquote", "pre", "dt", "dd",
            "figcaption", "td", "th", "section", "article", "aside", "header", "footer", "tr", "ul", "ol",
            "dl", "table", "tbody", "thead", "figure", "main", "nav", "hr", "br",
        )
        private val SKIP_TAGS = setOf("script", "style", "head", "svg", "math", "rt", "rp", "title")

        internal fun extractParagraphs(bytes: ByteArray): List<String> {
            val doc = Jsoup.parse(String(bytes, Charsets.UTF_8))
            val body = doc.body()
            val out = ArrayList<String>()
            val buffer = StringBuilder()
            fun flush() {
                val t = TextCleaner.normalize(buffer.toString())
                if (t.isNotEmpty() && t.any { it.isLetterOrDigit() }) out.add(t)
                buffer.setLength(0)
            }
            fun walk(node: Node) {
                when (node) {
                    is TextNode -> buffer.append(node.wholeText)
                    is Element -> {
                        val tag = node.normalName()
                        if (tag in SKIP_TAGS) return
                        if (tag == "br") {
                            buffer.append(' ')
                            return
                        }
                        val block = tag in BLOCK_TAGS
                        if (block) flush()
                        node.childNodes().forEach { walk(it) }
                        if (block) flush()
                    }
                }
            }
            walk(body)
            flush()
            return out
        }
    }
}
