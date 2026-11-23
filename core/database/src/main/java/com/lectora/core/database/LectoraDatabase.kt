package com.lectora.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.lectora.core.model.BookFormat

class Converters {
    @TypeConverter fun formatToString(format: BookFormat): String = format.name
    @TypeConverter fun stringToFormat(value: String): BookFormat = BookFormat.valueOf(value)
}

@Database(
    entities = [
        BookEntity::class,
        CollectionEntity::class,
        BookCollectionCrossRef::class,
        ProgressEntity::class,
        TranslationEntity::class,
        FolderEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class LectoraDatabase : RoomDatabase() {
    abstract fun books(): BookDao
    abstract fun collections(): CollectionDao
    abstract fun progress(): ProgressDao
    abstract fun translations(): TranslationDao
    abstract fun folders(): FolderDao

    companion object {
        /** v2: ruta del libro dentro de su carpeta sincronizada (para ver la estructura de subcarpetas). */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN relativePath TEXT")
            }
        }

        fun create(context: Context): LectoraDatabase =
            Room.databaseBuilder(context.applicationContext, LectoraDatabase::class.java, "lectora.db")
                // Las actualizaciones conservan la biblioteca, el progreso y las colecciones.
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                .build()
    }
}
