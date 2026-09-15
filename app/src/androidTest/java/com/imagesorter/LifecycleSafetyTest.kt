package com.imagesorter

import android.Manifest
import android.os.Bundle
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.imagesorter.data.MediaOps
import com.imagesorter.data.MediaQueries
import com.imagesorter.data.db.AppDatabase
import com.imagesorter.data.db.Decision
import com.imagesorter.domain.Action
import com.imagesorter.domain.MediaItem
import com.imagesorter.domain.MediaKey
import com.imagesorter.ui.ApprovalLauncher
import com.imagesorter.ui.MainActivity
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pasos de T14 (muerte del proceso) y T15 (desinstalar con fotos en la papelera). Los orquestan
 * scripts/test-process-death.ps1 y scripts/test-uninstall-trash.ps1; en una ejecución normal se omiten.
 */
@RunWith(AndroidJUnit4::class)
class LifecycleSafetyTest {
    @get:Rule
    val permissions: GrantPermissionRule =
        GrantPermissionRule.grant(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.ACCESS_MEDIA_LOCATION,
        )

    private val args: Bundle = InstrumentationRegistry.getArguments()
    private val fx = MediaFixture(args.getString("runId")?.takeUnless { it.isBlank() } ?: "IST_${System.currentTimeMillis()}")
    private val resolver = fx.context.contentResolver
    private val queries = MediaQueries(resolver)
    private val dir get() = "Pictures/${fx.runId}_L/"

    private fun requireStep(name: String) {
        assumeTrue("Paso orquestado por scripts/test-*.ps1", args.getString("lifecycleStep") == name)
        assumeTrue("Solo en emulador", fx.isEmulator())
    }

    private fun report(key: String, value: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("imagesorter.$key", value) })
    }

    private fun arg(name: String) = checkNotNull(args.getString(name)) { "Falta el argumento $name" }

    /** Ejecuta [block] con un MediaOps conectado a una Activity real (necesaria para las solicitudes). */
    private fun <T> withOps(block: suspend (MediaOps, AppDatabase) -> T): T {
        val db = Room.inMemoryDatabaseBuilder(fx.context, AppDatabase::class.java).build()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var approvals: ApprovalLauncher
                scenario.onActivity { approvals = it.approvals }
                val ops = MediaOps(
                    resolver, queries, db.decisions(), { MediaStore.getExternalVolumeNames(fx.context) }, approvals::approve,
                )
                return runBlocking { withTimeout(60_000) { block(ops, db) } }
            }
        } finally {
            db.close()
        }
    }

    // ---------- T14 ----------

    @Test
    fun stageThenDie() {
        requireStep("stageThenDie")
        val db = AppDatabase.get(fx.context)
        db.clearAllTables()
        val photos = (0..1).map { fx.seed(dir, "${fx.runId}_$it.jpg", variant = it) }
        runBlocking {
            val items = queries.snapshot(photos.map { it.key })
            db.decisions().insert(Decision.staged(items.getValue(photos[0].key), Action.TRASH, 1))
            db.decisions().insert(Decision.staged(items.getValue(photos[1].key), Action.KEEP, 2))
        }
        report("sha0", photos[0].sha256)
        report("sha1", photos[1].sha256)
        report("completed", "stageThenDie")
    }

    @Test
    fun afterRestartQueuePersistsAndNothingExecuted() {
        requireStep("afterRestartQueuePersistsAndNothingExecuted")
        val db = AppDatabase.get(fx.context)
        try {
            // Abrir la app como lo haría el usuario: no debe ejecutar nada por su cuenta.
            ActivityScenario.launch(MainActivity::class.java).use { Thread.sleep(3_000) }

            val staged = runBlocking { db.decisions().staged() }
            assertEquals(listOf(Action.TRASH, Action.KEEP), staged.map { it.action })
            listOf(arg("sha0"), arg("sha1")).forEachIndexed { i, sha ->
                val key = checkNotNull(fx.findKey(dir, "${fx.runId}_$i.jpg")) { "La foto $i ya no está en su sitio" }
                assertEquals(key, staged[i].key())
                val row = checkNotNull(fx.row(key))
                assertFalse("nada se envió a la papelera sin confirmar", row.isTrashed)
                assertEquals(sha, fx.sha256(row.path))
            }
            report("completed", "afterRestartQueuePersistsAndNothingExecuted")
        } finally {
            db.clearAllTables()
            fx.cleanup()
        }
    }

    // ---------- T15 ----------

    @Test
    fun trashBeforeUninstall() {
        requireStep("trashBeforeUninstall")
        fx.setManageMedia(true)
        val photo = fx.seed(dir, "${fx.runId}_u.jpg", variant = 7)

        val result = withOps { ops, db ->
            val item = queries.snapshot(listOf(photo.key)).getValue(photo.key)
            db.decisions().insert(Decision.staged(item, Action.TRASH, 1))
            ops.commitStaged()
        }

        assertEquals(MediaOps.OpResult(1, emptyList(), cancelled = false), result)
        val row = checkNotNull(fx.row(photo.key))
        assertTrue(row.isTrashed)
        assertEquals(photo.sha256, fx.sha256(row.path))
        report("key", "${photo.key.volume}:${photo.key.mediaId}")
        report("path", row.path)
        report("sha", photo.sha256)
        report("completed", "trashBeforeUninstall")
    }

    @Test
    fun restoreAfterReinstall() {
        requireStep("restoreAfterReinstall")
        fx.setManageMedia(true)
        val (volume, id) = arg("key").split(":")
        val key = MediaKey(volume, id.toLong())
        try {
            val trashed: MediaItem = checkNotNull(queries.trash().singleOrNull { it.key == key }) {
                "La app reinstalada no ve la foto en la papelera"
            }

            val result = withOps { ops, _ -> ops.restore(listOf(trashed)) }

            assertEquals(MediaOps.OpResult(1, emptyList(), cancelled = false), result)
            val row = checkNotNull(fx.row(key))
            assertFalse(row.isTrashed)
            assertEquals(dir, row.relativePath)
            assertEquals(arg("sha"), fx.sha256(row.path))
            report("completed", "restoreAfterReinstall")
        } finally {
            fx.cleanup()
            fx.setManageMedia(false)
        }
    }
}
