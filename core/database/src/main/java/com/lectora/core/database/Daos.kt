package com.lectora.core.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Transaction
    @Query("SELECT * FROM books WHERE missing = 0 ORDER BY title COLLATE NOCASE")
    fun observeAll(): Flow<List<BookWithProgress>>

    @Transaction
    @Query(
        """SELECT books.* FROM books JOIN progress ON progress.bookId = books.id
           WHERE books.missing = 0 AND progress.fraction < 0.999
           ORDER BY progress.updatedAt DESC"""
    )
    fun observeReading(): Flow<List<BookWithProgress>>

    @Transaction
    @Query(
        """SELECT books.* FROM books JOIN book_collection bc ON bc.bookId = books.id
           WHERE bc.collectionId = :collectionId ORDER BY books.title COLLATE NOCASE"""
    )
    fun observeInCollection(collectionId: Long): Flow<List<BookWithProgress>>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun get(id: Long): BookEntity?

    @Query("SELECT * FROM books WHERE id = :id")
    fun observe(id: Long): Flow<BookEntity?>

    @Query("SELECT * FROM books WHERE fingerprint = :fingerprint")
    suspend fun findByFingerprint(fingerprint: String): BookEntity?

    @Query("SELECT * FROM books WHERE uri = :uri")
    suspend fun findByUri(uri: String): BookEntity?

    @Query("SELECT * FROM books WHERE folderUri = :folderUri")
    suspend fun inFolder(folderUri: String): List<BookEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(book: BookEntity): Long

    @Update
    suspend fun update(book: BookEntity)

    @Query("UPDATE books SET lastOpenedAt = :time WHERE id = :id")
    suspend fun markOpened(id: Long, time: Long)

    @Query("UPDATE books SET missing = :missing WHERE id = :id")
    suspend fun setMissing(id: Long, missing: Boolean)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface CollectionDao {
    @Query(
        """SELECT collections.*, (SELECT COUNT(*) FROM book_collection bc WHERE bc.collectionId = collections.id) AS bookCount
           FROM collections ORDER BY name COLLATE NOCASE"""
    )
    fun observeAll(): Flow<List<CollectionWithCount>>

    @Query("SELECT * FROM collections WHERE id = :id")
    fun observe(id: Long): Flow<CollectionEntity?>

    @Query("SELECT collectionId FROM book_collection WHERE bookId = :bookId")
    fun observeCollectionIdsFor(bookId: Long): Flow<List<Long>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(collection: CollectionEntity): Long

    @Query("UPDATE collections SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM collections WHERE id = :id")
    suspend fun delete(id: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addBook(ref: BookCollectionCrossRef)

    @Delete
    suspend fun removeBook(ref: BookCollectionCrossRef)
}

@Dao
interface ProgressDao {
    @Query("SELECT * FROM progress WHERE bookId = :bookId")
    suspend fun get(bookId: Long): ProgressEntity?

    @Query("SELECT * FROM progress WHERE bookId = :bookId")
    fun observe(bookId: Long): Flow<ProgressEntity?>

    @Upsert
    suspend fun upsert(progress: ProgressEntity)
}

@Dao
interface TranslationDao {
    @Query(
        """SELECT * FROM translations WHERE bookId = :bookId AND section = :section
           AND paragraph = :paragraph AND targetLang = :targetLang"""
    )
    suspend fun get(bookId: Long, section: Int, paragraph: Int, targetLang: String): TranslationEntity?

    @Upsert
    suspend fun upsert(entity: TranslationEntity)

    @Query("DELETE FROM translations WHERE bookId = :bookId")
    suspend fun clearBook(bookId: Long)
}

@Dao
interface FolderDao {
    @Query("SELECT * FROM folders ORDER BY displayName")
    fun observeAll(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders")
    suspend fun all(): List<FolderEntity>

    @Upsert
    suspend fun upsert(folder: FolderEntity)

    @Query("DELETE FROM folders WHERE uri = :uri")
    suspend fun delete(uri: String)
}
