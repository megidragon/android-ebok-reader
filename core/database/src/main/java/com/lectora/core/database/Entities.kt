package com.lectora.core.database

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import com.lectora.core.model.BookFormat

@Entity(
    tableName = "books",
    indices = [Index(value = ["fingerprint"], unique = true), Index("uri")],
)
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** URI de contenido (SAF) del archivo. */
    val uri: String,
    val fileName: String,
    val format: BookFormat,
    val title: String,
    val author: String?,
    /** Código de idioma BCP-47 declarado o detectado (p. ej. "en"). */
    val language: String?,
    val coverPath: String?,
    /** Huella del contenido: permite reconocer el libro aunque se mueva o renombre. */
    val fingerprint: String,
    val sizeBytes: Long,
    val sectionCount: Int,
    /** URI de la carpeta sincronizada de la que proviene (null si se importó a mano). */
    val folderUri: String?,
    val addedAt: Long,
    val lastOpenedAt: Long? = null,
    /** El archivo ya no se encuentra en la carpeta sincronizada. */
    val missing: Boolean = false,
    /** Ruta dentro de la carpeta sincronizada, p. ej. "Sagas/Dune/dune-1.epub" (null si se importó suelto). */
    val relativePath: String? = null,
) {
    /** Subcarpeta que contiene el libro dentro de su carpeta sincronizada ("" = raíz). */
    val relativeDir: String get() = relativePath?.substringBeforeLast('/', "").orEmpty()
}

@Entity(tableName = "collections", indices = [Index(value = ["name"], unique = true)])
data class CollectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
)

@Entity(
    tableName = "book_collection",
    primaryKeys = ["bookId", "collectionId"],
    foreignKeys = [
        ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = CollectionEntity::class, parentColumns = ["id"], childColumns = ["collectionId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("collectionId")],
)
data class BookCollectionCrossRef(val bookId: Long, val collectionId: Long)

@Entity(
    tableName = "progress",
    foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)],
)
data class ProgressEntity(
    @PrimaryKey val bookId: Long,
    val section: Int,
    val paragraph: Int,
    val sentence: Int,
    /** Progreso aproximado 0..1. */
    val fraction: Float,
    val updatedAt: Long,
)

@Entity(
    tableName = "translations",
    primaryKeys = ["bookId", "section", "paragraph", "targetLang"],
    foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)],
)
data class TranslationEntity(
    val bookId: Long,
    val section: Int,
    val paragraph: Int,
    val targetLang: String,
    val sourceLang: String,
    /** Hash del texto original, para invalidar si el extractor cambia. */
    val sourceHash: Int,
    val text: String,
)

@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey val uri: String,
    val displayName: String,
    val lastScanAt: Long? = null,
)

data class BookWithProgress(
    @Embedded val book: BookEntity,
    @Relation(parentColumn = "id", entityColumn = "bookId")
    val progress: ProgressEntity?,
)

data class CollectionWithCount(
    @Embedded val collection: CollectionEntity,
    @ColumnInfo(name = "bookCount") val bookCount: Int,
)
