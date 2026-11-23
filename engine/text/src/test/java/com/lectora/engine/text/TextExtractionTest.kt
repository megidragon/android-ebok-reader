package com.lectora.engine.text

import com.lectora.engine.text.epub.EpubDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class TextExtractionTest {

    @Test
    fun pdfLinesAreJoinedAndHyphensRepaired() {
        val raw = "It was a bright cold day in Ap-\nril, and the clocks were\nstriking thirteen.\n\n42\n\nSecond paragraph."
        val paragraphs = TextCleaner.rebuildPdfParagraphs(raw)
        assertEquals(
            listOf("It was a bright cold day in April, and the clocks were striking thirteen.", "Second paragraph."),
            paragraphs,
        )
    }

    @Test
    fun linesSplitAsParagraphsAreMergedBack() {
        val raw = "Habiendo gastado tanto, su familia\n\nterminó gastando dinero en conseguir ayuda, lo\n\nque les provocó una pérdida.\n\nAhora todos se habían ido.\n\n— 1 —"
        assertEquals(
            listOf(
                "Habiendo gastado tanto, su familia terminó gastando dinero en conseguir ayuda, lo que les provocó una pérdida.",
                "Ahora todos se habían ido.",
            ),
            TextCleaner.rebuildPdfParagraphs(raw),
        )
    }

    @Test
    fun pageNumberVariantsAreDropped() {
        for (n in listOf("— 12 —", "- xii -", "Página 12", "12 / 6829", "[12]", "7")) {
            assertEquals(n, emptyList<String>(), TextCleaner.rebuildPdfParagraphs("$n\n\n"))
        }
        assertEquals(listOf("Capítulo 12"), TextCleaner.rebuildPdfParagraphs("Capítulo 12"))
    }

    @Test
    fun ligaturesAndSoftHyphensAreNormalized() {
        assertEquals("difficult offer", TextCleaner.normalize("diﬃcult  o­ffer "))
    }

    @Test
    fun detectsUnterminatedParagraphs() {
        assertTrue(TextCleaner.endsSentence("The end."))
        assertTrue(TextCleaner.endsSentence("«¿Vienes?»"))
        assertFalse(TextCleaner.endsSentence("and then he"))
    }

    @Test
    fun epubHrefResolution() {
        assertEquals("OEBPS/Text/ch1.xhtml", EpubDocument.resolve("OEBPS", "Text/ch1.xhtml"))
        assertEquals("OEBPS/Images/a b.jpg", EpubDocument.resolve("OEBPS/Text", "../Images/a%20b.jpg"))
        assertEquals("ch1.xhtml", EpubDocument.resolve("", "ch1.xhtml"))
    }

    @Test
    fun epubParagraphsKeepBlocksAndInlineText() {
        val html = """
            <html><head><title>x</title><style>p{}</style></head><body>
              <h1>Chapter <em>One</em></h1>
              <div>Loose text <p>First <b>bold</b> paragraph.</p> tail</div>
              <p>Second&nbsp;paragraph<br/>with break.</p>
              <p>   </p>
            </body></html>
        """.trimIndent()
        val paragraphs = EpubDocument.extractParagraphs(html.toByteArray())
        assertEquals(
            listOf("Chapter One", "Loose text", "First bold paragraph.", "tail", "Second paragraph with break."),
            paragraphs,
        )
    }

    @Test
    fun opensMinimalEpub() {
        val bytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                fun put(name: String, content: String) {
                    zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray()); zip.closeEntry()
                }
                put("mimetype", "application/epub+zip")
                put(
                    "META-INF/container.xml",
                    """<?xml version="1.0"?><container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>""",
                )
                put(
                    "OEBPS/content.opf",
                    """<?xml version="1.0"?>
                    <package xmlns:dc="http://purl.org/dc/elements/1.1/">
                      <metadata><dc:title>Test Book</dc:title><dc:creator>Ann Author</dc:creator><dc:language>en-US</dc:language></metadata>
                      <manifest>
                        <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                        <item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                        <item id="c2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
                      </manifest>
                      <spine><itemref idref="c1"/><itemref idref="c2"/></spine>
                    </package>""",
                )
                put("OEBPS/nav.xhtml", """<html><body><nav epub:type="toc"><ol><li><a href="ch1.xhtml">Uno</a></li><li><a href="ch2.xhtml#x">Dos</a></li></ol></nav></body></html>""")
                put("OEBPS/ch1.xhtml", "<html><body><p>Hello world.</p></body></html>")
                put("OEBPS/ch2.xhtml", "<html><body><p>Bye.</p></body></html>")
            }
        }.toByteArray()
        val doc = EpubDocument.open(ByteArrayInputStream(bytes))
        assertEquals("Test Book", doc.metadata.title)
        assertEquals("Ann Author", doc.metadata.author)
        assertEquals("en", doc.metadata.language)
        assertEquals(2, doc.sectionCount)
        assertEquals("Uno", doc.sectionTitle(0))
        assertEquals("Dos", doc.sectionTitle(1))
        val p = kotlinx.coroutines.runBlocking { doc.paragraphs(0) }
        assertEquals(listOf("Hello world."), p)
    }
}
