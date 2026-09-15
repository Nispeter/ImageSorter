package com.imagesorter

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.imagesorter.data.db.AppDatabase
import com.imagesorter.data.db.ArchivedFolder
import com.imagesorter.domain.MediaKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Quien tiene instalada la 1.0.0 no pierde sus decisiones al actualizar. */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "migration-test.db"

    @After
    fun tearDown() {
        context.deleteDatabase(name)
    }

    @Test
    fun v1Database_keepsItsDecisions_afterUpgradingToV2() {
        context.deleteDatabase(name)
        // Base de datos tal como la creó la versión 1.0.0 (esquema v1 de Room).
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `decisions` (`volume` TEXT NOT NULL, `mediaId` INTEGER NOT NULL, " +
                    "`action` TEXT NOT NULL, `status` TEXT NOT NULL, `seq` INTEGER NOT NULL, `displayName` TEXT NOT NULL, " +
                    "`relativePath` TEXT NOT NULL, `size` INTEGER NOT NULL, `dateModified` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`volume`, `mediaId`))",
            )
            db.execSQL("INSERT INTO decisions VALUES ('external_primary', 7, 'TRASH', 'STAGED', 1, 'a.jpg', 'DCIM/Camera/', 10, 20)")
            db.execSQL("INSERT INTO decisions VALUES ('external_primary', 8, 'KEEP', 'DONE', 2, 'b.jpg', 'DCIM/Camera/', 11, 21)")
            db.version = 1
        }

        val room = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .build()
        try {
            val staged = runBlocking { room.decisions().staged() }
            assertEquals(listOf(7L), staged.map { it.mediaId })
            assertEquals(MediaKind.IMAGE, staged.single().kind)
            assertEquals(2, runBlocking { room.decisions().decidedKeys() }.size)
            runBlocking { room.archive().archive(ArchivedFolder("external_primary", "123", 5, "Camera", "DCIM/Camera/")) }
            assertEquals(1, runBlocking { room.archive().observeAll().first() }.size)
        } finally {
            room.close()
        }
    }
}
