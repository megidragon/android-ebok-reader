package com.lectora.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import com.lectora.core.database.BookCollectionCrossRef
import com.lectora.core.database.BookEntity
import com.lectora.core.database.BookWithProgress
import com.lectora.core.database.CollectionEntity
import com.lectora.core.database.CollectionWithCount
import com.lectora.core.database.FolderEntity
import com.lectora.core.database.LectoraDatabase
import com.lectora.core.database.ProgressEntity
import com.lectora.core.model.BookFormat
import com.lectora.core.model.ReadingPosition
import com.lectora.engine.text.BookOpener
import com.lectora.engine.translation.TextTranslator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

data class ScanResult(val added: Int = 0, val moved: Int = 0, val missing: Int = 0, val failed: Int = 0) {
    operator fun plus(o: ScanResult) = ScanResult(added + o.added, moved + o.moved, missing + o.missing, failed + o.failed)
}

class LibraryRepository(
    context: Context,
    private val db: LectoraDatabase,
    private val opener: BookOpener,
    private val translator: TextTranslator,
    private val documents: DocumentCache,
) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val coversDir = File(appContext.filesDir, "covers").apply { mkdirs() }
    private val scanMutex = Mutex()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    // ---------- Consultas ----------

    fun allBooks(): Flow<List<BookWithProgress>> = db.books().observeAll()
    fun readingBooks(): Flow<List<BookWithProgress>> = db.books().observeReading()
    fun booksIn(collectionId: Long): Flow<List<BookWithProgress>> = db.books().observeInCollection(collectionId)
    fun book(id: Long): Flow<BookEntity?> = db.books().observe(id)
    suspend fun getBook(id: Long): BookEntity? = db.books().get(id)
    fun collections(): Flow<List<CollectionWithCount>> = db.collections().observeAll()
    fun collection(id: Long): Flow<CollectionEntity?> = db.collections().observe(id)
    fun collectionIdsFor(bookId: Long): Flow<List<Long>> = db.collections().observeCollectionIdsFor(bookId)
    fun folders(): Flow<List<FolderEntity>> = db.folders().observeAll()

    suspend fun document(book: BookEntity) = documents.get(book)

    // ---------- Progreso ----------

    suspend fun progress(bookId: Long): ReadingPosition? =
        db.progress().get(bookId)?.let { ReadingPosition(it.section, it.paragraph, it.sentence) }

    suspend fun saveProgress(bookId: Long, position: ReadingPosition, sectionCount: Int, paragraphCount: Int? = null) {
        val within = paragraphCount?.takeIf { it > 0 }?.let { (position.paragraph + 1).toFloat() / it } ?: 0f
        val fraction = if (sectionCount <= 0) 0f else ((position.section + within) / sectionCount).coerceIn(0f, 1f)
        db.progress().upsert(
            ProgressEntity(bookId, position.section, position.paragraph, position.sentence, fraction, System.currentTimeMillis())
        )
        db.books().markOpened(bookId, System.currentTimeMillis())
    }

    // ---------- Colecciones ----------

    suspend fun createCollection(name: String): Long =
        db.collections().insert(CollectionEntity(name = name.trim(), createdAt = System.currentTimeMillis()))

    suspend fun renameCollection(id: Long, name: String) = db.collections().rename(id, name.trim())
    suspend fun deleteCollection(id: Long) = db.collections().delete(id)
    suspend fun addToCollection(bookId: Long, collectionId: Long) =
        db.collections().addBook(BookCollectionCrossRef(bookId, collectionId))

    suspend fun removeFromCollection(bookId: Long, collectionId: Long) =
        db.collections().removeBook(BookCollectionCrossRef(bookId, collectionId))

    suspend fun removeBook(book: BookEntity) {
        documents.invalidate(book.id)
        db.books().delete(book.id)
        book.coverPath?.let { File(it).delete() }
    }

    // ---------- Importación ----------

    /** Importa archivos elegidos a mano (ACTION_OPEN_DOCUMENT). */
    suspend fun importFiles(uris: List<Uri>): ScanResult = withContext(Dispatchers.IO) {
        var result = ScanResult()
        for (uri in uris) {
            runCatching {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val (name, size) = queryNameAndSize(uri)
            val format = BookFormat.fromFileName(name) ?: BookFormat.fromMime(resolver.getType(uri))
            if (format == null) {
                result += ScanResult(failed = 1)
                continue
            }
            result += importOrUpdate(uri, name, size, format, folderUri = null)
        }
        result
    }

    /** Registra una carpeta (ACTION_OPEN_DOCUMENT_TREE) y la sincroniza. */
    suspend fun addFolder(treeUri: Uri): ScanResult {
        resolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val name = runCatching {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            docId.substringAfterLast(':').ifBlank { docId }
        }.getOrDefault(treeUri.lastPathSegment ?: "Carpeta")
        val folder = FolderEntity(treeUri.toString(), name)
        db.folders().upsert(folder)
        return scanMutex.withLock { scanFolder(folder) }
    }

    suspend fun removeFolder(uri: String) {
        db.folders().delete(uri)
        runCatching { resolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    /** Sincroniza todas las carpetas: libros nuevos, movidos o eliminados. */
    suspend fun scanAll(): ScanResult = scanMutex.withLock {
        _scanning.value = true
        try {
            db.folders().all().fold(ScanResult()) { acc, f -> acc + scanFolder(f) }
        } finally {
            _scanning.value = false
        }
    }

    private suspend fun scanFolder(folder: FolderEntity): ScanResult = withContext(Dispatchers.IO) {
        val tree = Uri.parse(folder.uri)
        val files = runCatching { listBookFiles(tree) }.getOrElse {
            Log.w(TAG, "No se pudo leer la carpeta ${folder.uri}", it)
            return@withContext ScanResult(failed = 1)
        }
        var result = ScanResult()
        val seen = HashSet<String>()
        for (f in files) {
            seen.add(f.uri.toString())
            result += importOrUpdate(f.uri, f.name, f.size, f.format, folder.uri, f.relativePath)
        }
        for (book in db.books().inFolder(folder.uri)) {
            if (book.uri !in seen && !book.missing) {
                db.books().setMissing(book.id, true)
                result += ScanResult(missing = 1)
            }
        }
        db.folders().upsert(folder.copy(lastScanAt = System.currentTimeMillis()))
        result
    }

    private suspend fun importOrUpdate(
        uri: Uri,
        name: String,
        size: Long,
        format: BookFormat,
        folderUri: String?,
        relativePath: String? = null,
    ): ScanResult {
        val books = db.books()
        books.findByUri(uri.toString())?.let { existing ->
            if (existing.sizeBytes == size) {
                if (existing.missing) books.setMissing(existing.id, false)
                // Libros importados con versiones anteriores: completar su ruta en la carpeta.
                if (relativePath != null && existing.relativePath != relativePath) {
                    books.update(existing.copy(relativePath = relativePath, missing = false))
                }
                return ScanResult()
            }
        }
        return try {
            val fingerprint = opener.fingerprint(uri, size)
            val known = books.findByFingerprint(fingerprint)
            if (known != null) {
                // Mismo libro en otra ruta (movido/renombrado): se conserva el progreso.
                if (known.uri != uri.toString() || known.missing) {
                    books.update(
                        known.copy(
                            uri = uri.toString(),
                            fileName = name,
                            folderUri = folderUri ?: known.folderUri,
                            relativePath = relativePath ?: known.relativePath,
                            missing = false,
                        )
                    )
                    documents.invalidate(known.id)
                    ScanResult(moved = 1)
                } else {
                    ScanResult()
                }
            } else {
                importNew(uri, name, size, format, fingerprint, folderUri, relativePath)
                ScanResult(added = 1)
            }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo importar $name", e)
            ScanResult(failed = 1)
        }
    }

    private suspend fun importNew(
        uri: Uri,
        name: String,
        size: Long,
        format: BookFormat,
        fingerprint: String,
        folderUri: String?,
        relativePath: String? = null,
    ) {
        val doc = opener.open(uri, format)
        try {
            val meta = doc.metadata
            // Manda el idioma real del texto: muchos PDF/EPUB (sobre todo traducciones o
            // conversiones) declaran un idioma incorrecto en sus metadatos.
            val language = detectLanguage(doc) ?: meta.language
            val coverPath = opener.extractCover(uri, format, doc)?.let { bytes ->
                File(coversDir, "$fingerprint.jpg").apply { writeBytes(bytes) }.absolutePath
            }
            db.books().insert(
                BookEntity(
                    uri = uri.toString(),
                    fileName = name,
                    format = format,
                    title = meta.title ?: name.substringBeforeLast('.'),
                    author = meta.author,
                    language = language,
                    coverPath = coverPath,
                    fingerprint = fingerprint,
                    sizeBytes = size,
                    sectionCount = meta.sectionCount,
                    folderUri = folderUri,
                    relativePath = relativePath,
                    addedAt = System.currentTimeMillis(),
                )
            )
        } finally {
            doc.close()
        }
    }

    /**
     * Detecta el idioma con varias muestras repartidas por el libro (las primeras páginas suelen
     * ser portada, créditos o índice) y se queda con el idioma mayoritario.
     */
    private suspend fun detectLanguage(doc: com.lectora.engine.text.BookDocument): String? {
        val n = doc.sectionCount
        if (n == 0) return null
        val sections = (listOf(0, 1, 2, 3, n / 10, n / 4, n / 2, 3 * n / 4)).filter { it in 0 until n }.distinct()
        val votes = HashMap<String, Int>()
        for (s in sections) {
            val sample = StringBuilder()
            var section = s
            // Si la sección está vacía o es muy corta, seguir con las siguientes.
            while (sample.length < 600 && section < minOf(n, s + 5)) {
                doc.paragraphs(section).forEach { if (sample.length < 1200) sample.append(it).append(' ') }
                section++
            }
            if (sample.length < 80) continue
            val lang = runCatching { translator.detectLanguage(sample.toString()) }.getOrNull() ?: continue
            votes[lang] = (votes[lang] ?: 0) + 1
        }
        return votes.maxByOrNull { it.value }?.key
    }

    /**
     * Corrige el idioma de los libros ya importados (antes se priorizaban los metadatos).
     * Se ejecuta una vez tras actualizar la app.
     */
    suspend fun redetectLanguages(): Int = withContext(Dispatchers.IO) {
        var changed = 0
        for (item in db.books().observeAll().first()) {
            val book = item.book
            val detected = runCatching { detectLanguage(documents.get(book)) }.getOrNull() ?: continue
            if (detected != book.language) {
                Log.i(TAG, "Idioma corregido: «${book.title}» ${book.language} → $detected")
                db.books().update(book.copy(language = detected))
                changed++
            }
        }
        changed
    }

    /** [relativePath]: ruta dentro de la carpeta sincronizada, p. ej. "Sagas/Dune/dune-1.epub". */
    private data class FoundFile(val uri: Uri, val name: String, val size: Long, val format: BookFormat, val relativePath: String)

    /** Recorre la carpeta (y subcarpetas) con DocumentsContract, mucho más rápido que DocumentFile. */
    private fun listBookFiles(tree: Uri): List<FoundFile> {
        val out = ArrayList<FoundFile>()
        // (id del documento, ruta de la subcarpeta relativa a la raíz)
        val pending = ArrayDeque<Pair<String, String>>().apply { add(DocumentsContract.getTreeDocumentId(tree) to "") }
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        while (pending.isNotEmpty()) {
            val (parent, dir) = pending.removeFirst()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
            resolver.query(children, projection, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2)
                    val path = if (dir.isEmpty()) name else "$dir/$name"
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (!name.startsWith(".")) pending.add(id to path)
                        continue
                    }
                    val format = BookFormat.fromFileName(name) ?: BookFormat.fromMime(mime) ?: continue
                    val size = if (c.isNull(3)) 0L else c.getLong(3)
                    out.add(FoundFile(DocumentsContract.buildDocumentUriUsingTree(tree, id), name, size, format, path))
                }
            }
        }
        return out
    }

    private fun queryNameAndSize(uri: Uri): Pair<String, Long> {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val name = c.getString(0) ?: uri.lastPathSegment.orEmpty()
                val size = if (c.isNull(1)) 0L else c.getLong(1)
                return name to size
            }
        }
        return (uri.lastPathSegment ?: "libro") to 0L
    }

    private companion object {
        const val TAG = "Library"
    }
}
