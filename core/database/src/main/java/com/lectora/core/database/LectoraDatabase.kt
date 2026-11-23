package com.lectora.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
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
    version = 1,
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
        fun create(context: Context): LectoraDatabase =
            Room.databaseBuilder(context.applicationContext, LectoraDatabase::class.java, "lectora.db")
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
