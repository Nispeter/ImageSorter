package com.imagesorter.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Decision::class, ArchivedFolder::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun decisions(): DecisionDao

    abstract fun archive(): ArchiveDao

    companion object {
        /** v1 → v2: videos (columna kind) y carpetas archivadas. Conserva las decisiones existentes. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `decisions` ADD COLUMN `kind` TEXT NOT NULL DEFAULT 'IMAGE'")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `archived_folders` (`volume` TEXT NOT NULL, `bucketId` TEXT NOT NULL, " +
                        "`archivedUpTo` INTEGER NOT NULL, `name` TEXT NOT NULL, `relativePath` TEXT NOT NULL, " +
                        "PRIMARY KEY(`volume`, `bucketId`))",
                )
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "imagesorter.db")
                .addMigrations(MIGRATION_1_2)
                .build()
                .also { instance = it }
        }
    }
}
