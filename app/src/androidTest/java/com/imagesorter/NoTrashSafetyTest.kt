package com.imagesorter

import android.provider.MediaStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.imagesorter.data.MediaOps
import com.imagesorter.data.MediaQueries
import com.imagesorter.data.db.AppDatabase
import com.imagesorter.data.db.Decision
import com.imagesorter.data.db.DecisionDao
import com.imagesorter.domain.Action
import com.imagesorter.domain.FolderIndex
import com.imagesorter.domain.Folders
import com.imagesorter.domain.MediaKind
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android 10: no hay papelera, así que "borrar" elimina para siempre y sin diálogo del sistema. Se comprueba que
 * se borra EXACTAMENTE lo confirmado, que mover no pierde bytes y que lo que cambió después de decidir no se toca.
 * Las fotos las crea el shell, así su dueño no es la app (como las de la cámara).
 */
@RunWith(AndroidJUnit4::class)
class NoTrashSafetyTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(*mediaPermissions())

    private val fx = MediaFixture()
    private val resolver = fx.context.contentResolver
    private val queries = MediaQueries(resolver)
    private lateinit var db: AppDatabase
    private lateinit var dao: DecisionDao
    private lateinit var ops: MediaOps

    private val dirA get() = "Pictures/${fx.runId}_A/"

    @Before
    fun setUp() {
        assumeTrue("Estos tests crean y borran fotos: solo se ejecutan en un emulador", fx.isEmulator())
        assumeTrue("Solo Android 10 (sin papelera del sistema)", !MediaOps.hasSystemTrash)
        db = Room.inMemoryDatabaseBuilder(fx.context, AppDatabase::class.java).build()
        dao = db.decisions()
        // Android 10 no tiene solicitudes por lotes: pedir una aprobación sería un error.
        ops = MediaOps(resolver, queries, dao, { MediaStore.getExternalVolumeNames(fx.context) }) {
            error("en Android 10 no se pide aprobación al sistema")
        }
    }

    @After
    fun tearDown() {
        if (!::db.isInitialized) return
        try {
            db.close()
        } finally {
            fx.cleanup()
        }
    }

    private fun <T> blocking(block: suspend () -> T): T = runBlocking { withTimeout(90_000) { block() } }

    private fun seed(dir: String, count: Int, from: Int = 0) =
        (from until from + count).map { fx.seed(dir, "${fx.runId}_$it.jpg", variant = it) }

    private fun stage(s: MediaFixture.Seeded, action: Action) = blocking {
        val item = queries.snapshot(listOf(s.key)).getValue(s.key)
        dao.insert(Decision.staged(item, action, dao.maxSeq() + 1))
    }

    private fun doneKeys() = blocking { dao.decidedKeys().toSet() - dao.staged().map { it.key() }.toSet() }

    private fun bucketOf(dir: String) = FolderIndex.build(queries.folderEntries(), emptyList()).visible
        .single { it.name == dir.trimEnd('/').substringAfterLast('/') }.bucketId

    private fun assertUntouched(s: MediaFixture.Seeded) {
        val row = checkNotNull(fx.row(s.key)) { "${s.displayName} desapareció" }
        assertEquals("${s.displayName} no debía moverse", s.path, row.path)
        assertEquals("${s.displayName} bytes intactos", s.sha256, fx.sha256(s.path))
    }

    private fun assertDeleted(s: MediaFixture.Seeded) {
        assertNull("${s.displayName} sigue en MediaStore", fx.row(s.key))
        assertNull("${s.displayName} sigue en disco", fx.sha256(s.path))
    }

    private fun assertMoved(s: MediaFixture.Seeded, target: String): MediaFixture.Row {
        val row = checkNotNull(fx.row(s.key)) { "${s.displayName} desapareció al mover" }
        assertEquals(target, row.relativePath)
        assertEquals("${s.displayName} bytes intactos tras mover", s.sha256, fx.sha256(row.path))
        assertNull("ya no debe estar en el origen", fx.sha256(s.path))
        return row
    }

    /** Todas las fotos y videos del teléfono (no solo los de prueba). */
    private fun countEverything(): Int = MediaKind.entries.sumOf { kind ->
        checkNotNull(resolver.query(MediaQueries.collection(kind, MediaStore.VOLUME_EXTERNAL), arrayOf(MediaStore.MediaColumns._ID), null, null, null))
            .use { it.count }
    }

    @Test
    fun onlyTheAddressOfASinglePhotoOrVideoCanBeDeleted() {
        val s = seed(dirA, 1).single()
        assertTrue(MediaOps.isItemUri(MediaOps.uriOf(s.key, MediaKind.IMAGE)))
        assertTrue(MediaOps.isItemUri(MediaOps.uriOf(s.key, MediaKind.VIDEO)))
        listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL, s.key.mediaId),
            MediaQueries.collection(MediaKind.IMAGE, s.key.volume),
            android.net.Uri.parse("content://media/external_primary/images/media/0"),
            android.net.Uri.parse("content://media/external_primary/images/media/-1"),
            android.net.Uri.parse("content://otra.app/external_primary/images/media/${s.key.mediaId}"),
        ).forEach { assertFalse("no debe poder borrarse: $it", MediaOps.isItemUri(it)) }
    }

    @Test
    fun deletesExactlyWhatWasConfirmed_andNothingElseOnThePhone() {
        val inA = seed(dirA, 20)
        val inB = seed("DCIM/${fx.runId}_B/", 6, from = 100)
        val doomed = inA.filterIndexed { i, _ -> i % 3 == 0 } + inB.take(2)
        val kept = inA.filterIndexed { i, _ -> i % 3 == 1 }
        doomed.forEach { stage(it, Action.TRASH) }
        kept.forEach { stage(it, Action.KEEP) }
        val before = countEverything()

        val result = blocking { ops.commitStaged() }

        assertEquals(MediaOps.OpResult(doomed.size + kept.size, emptyList(), cancelled = false), result)
        assertEquals("se borraron exactamente ${doomed.size} en todo el teléfono", before - doomed.size, countEverything())
        doomed.forEach { assertDeleted(it) }
        (inA + inB - doomed.toSet()).forEach { assertUntouched(it) }
    }

    @Test
    fun onlyWhatTheSummaryShowed_isDeleted_notWhatWasDecidedAfterwards() {
        val (shown, later) = seed(dirA, 2)
        stage(shown, Action.TRASH)
        val upToSeq = blocking { dao.staged() }.maxOf { it.seq }
        stage(later, Action.TRASH) // llega mientras el resumen está en pantalla

        val result = blocking { ops.commitStaged(upToSeq) }

        assertEquals(MediaOps.OpResult(1, emptyList(), cancelled = false), result)
        assertDeleted(shown)
        assertUntouched(later)
        assertEquals("sigue pendiente, sin ejecutar", listOf(later.key), blocking { dao.staged() }.map { it.key() })
    }

    @Test
    fun aFileAlreadyInTheTarget_isNeverTouchedByTheVerifiedCopy() {
        val name = "${fx.runId}_same.jpg"
        val existing = fx.seed(Folders.FAVORITOS, name, variant = 40)
        val fromWhatsapp = fx.seed("Android/media/com.whatsapp/WhatsApp/Media/${fx.runId}_WA/", name, variant = 41)
        stage(fromWhatsapp, Action.FAVORITOS)

        val result = blocking { ops.commitStaged() }

        assertEquals(MediaOps.OpResult(1, emptyList(), cancelled = false), result)
        assertUntouched(existing)
        assertDeleted(fromWhatsapp)
        val base = name.substringBeforeLast('.')
        assertEquals("la existente y la copia", 2, fx.countFiles("/storage/emulated/0/${Folders.FAVORITOS}".trimEnd('/'), base))
    }

    @Test
    fun theDeckAndFoldersLoad_andThereIsNoTrash() {
        val (a, b) = seed(dirA, 2)
        assertEquals(setOf(a.key, b.key), queries.deck(listOf(bucketOf(dirA))).map { it.key }.toSet())
        assertTrue(queries.snapshot(listOf(a.key)).getValue(a.key).let { !it.isTrashed })
        assertEquals(emptyList<Any>(), queries.trash())
    }

    @Test
    fun commit_deletesForeverExactlyTheStagedPhotos() {
        val (a, b, kept, control) = seed(dirA, 4)
        stage(a, Action.TRASH)
        stage(b, Action.TRASH)
        stage(kept, Action.KEEP)

        val result = blocking { ops.commitStaged() }

        assertEquals(MediaOps.OpResult(3, emptyList(), cancelled = false), result)
        assertDeleted(a)
        assertDeleted(b)
        assertUntouched(kept)
        assertUntouched(control)
        assertEquals(setOf(kept.key, control.key), queries.deck(listOf(bucketOf(dirA))).map { it.key }.toSet())
        assertEquals(setOf(a.key, b.key, kept.key), doneKeys())
    }

    @Test
    fun move_toFavoritosAndLiked_keepsBytes_andNeverOverwrites() {
        val (toFav, toLiked) = seed(dirA, 2)
        val dupName = "${fx.runId}_dup.jpg"
        val existing = fx.seed(Folders.FAVORITOS, dupName, variant = 50)
        val incoming = fx.seed(dirA, dupName, variant = 51)
        stage(toFav, Action.FAVORITOS)
        stage(toLiked, Action.LIKED)
        stage(incoming, Action.FAVORITOS)

        val result = blocking { ops.commitStaged() }

        assertEquals(MediaOps.OpResult(3, emptyList(), cancelled = false), result)
        assertMoved(toFav, Folders.FAVORITOS)
        assertMoved(toLiked, Folders.LIKED)
        assertNotEquals("renombrada, no sobrescrita", dupName, assertMoved(incoming, Folders.FAVORITOS).displayName)
        assertUntouched(existing)
        assertEquals(setOf(toFav.key, toLiked.key, incoming.key), doneKeys())
    }

    @Test
    fun whatsappFiles_areDeleted_andFavoritesGoThroughAVerifiedCopy() {
        val dirW = "Android/media/com.whatsapp/WhatsApp/Media/${fx.runId}_WA/"
        val toDelete = fx.seed(dirW, "${fx.runId}_w0.jpg", variant = 0)
        val photo = fx.seed(dirW, "${fx.runId}_w1.jpg", variant = 1)
        val video = fx.seed(dirW, "${fx.runId}_w2.mp4", variant = 2)
        stage(toDelete, Action.TRASH)
        stage(photo, Action.FAVORITOS)
        stage(video, Action.TRASH)

        val result = blocking { ops.commitStaged() }

        assertEquals(MediaOps.OpResult(3, emptyList(), cancelled = false), result)
        assertDeleted(toDelete)
        assertDeleted(photo)
        assertDeleted(video)
        assertCopyIsIdentical(photo, Folders.FAVORITOS)
        assertEquals(setOf(toDelete.key, photo.key, video.key), doneKeys())
    }

    /** Android 10 no deja poner videos en Pictures/: no se intenta, no se toca nada y vuelve al mazo. */
    @Test
    fun videosToFavoritosOrLiked_areRefused_andLeftUntouched() {
        val dirW = "Android/media/com.whatsapp/WhatsApp/Media/${fx.runId}_WA/"
        val fromWhatsapp = fx.seed(dirW, "${fx.runId}_v0.mp4", variant = 0)
        val fromCamera = fx.seed("DCIM/${fx.runId}_B/", "${fx.runId}_v1.mp4", variant = 1)
        val photo = fx.seed("DCIM/${fx.runId}_B/", "${fx.runId}_p2.jpg", variant = 2)
        stage(fromWhatsapp, Action.LIKED)
        stage(fromCamera, Action.FAVORITOS)
        stage(photo, Action.FAVORITOS)

        val result = blocking { ops.commitStaged() }

        assertEquals(1, result.done)
        assertEquals(setOf(fromWhatsapp.displayName, fromCamera.displayName), result.failures.map { it.displayName }.toSet())
        assertUntouched(fromWhatsapp)
        assertUntouched(fromCamera)
        assertNull("sin copias", fx.findKey(Folders.LIKED, fromWhatsapp.displayName))
        assertMoved(photo, Folders.FAVORITOS)
        assertEquals("los videos vuelven al mazo", setOf(photo.key), doneKeys())
    }

    /** Hay UNA sola copia en [target] y con exactamente los mismos bytes que el original. */
    private fun assertCopyIsIdentical(original: MediaFixture.Seeded, target: String) {
        val copyKey = checkNotNull(fx.findKey(target, original.displayName)) { "no se creó la copia en $target" }
        assertNotEquals("la copia es otro archivo", original.key, copyKey)
        val row = checkNotNull(fx.row(copyKey))
        assertEquals(target, row.relativePath)
        assertEquals("la copia es idéntica", original.sha256, fx.sha256(row.path))
        val base = original.displayName.substringBeforeLast('.')
        assertEquals("no se duplicó", 1, fx.countFiles("/storage/emulated/0/$target".trimEnd('/'), base))
    }

    @Test
    fun aPhotoChangedAfterDeciding_isNeverDeleted() {
        val (changed, other) = seed(dirA, 2)
        stage(changed, Action.TRASH)
        stage(other, Action.TRASH)
        val newSha = fx.rewrite(changed, variant = 9)

        val result = blocking { ops.commitStaged() }

        assertEquals(1, result.done)
        assertEquals(listOf(changed.displayName), result.failures.map { it.displayName })
        val row = checkNotNull(fx.row(changed.key)) { "la foto cambiada no debía borrarse" }
        assertEquals("con los bytes nuevos", newSha, fx.sha256(row.path))
        assertDeleted(other)
        assertEquals("vuelve al mazo para revisarla de nuevo", setOf(other.key), doneKeys())
    }
}
