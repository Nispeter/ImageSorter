package com.imagesorter

import android.Manifest
import android.app.PendingIntent
import android.os.Build
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.imagesorter.data.MediaOps
import com.imagesorter.data.MediaQueries
import com.imagesorter.data.db.AppDatabase
import com.imagesorter.data.db.Decision
import com.imagesorter.data.db.DecisionDao
import com.imagesorter.domain.Action
import com.imagesorter.data.db.ArchivedFolder
import com.imagesorter.domain.FolderIndex
import com.imagesorter.domain.Folders
import com.imagesorter.domain.MediaKind
import com.imagesorter.domain.ReviewSession
import com.imagesorter.domain.Status
import com.imagesorter.ui.ApprovalLauncher
import com.imagesorter.ui.MainActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
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
 * Tests de seguridad contra el MediaProvider REAL del emulador, con fotos que NO pertenecen a la app.
 * Cada estado se verifica desde el shell (fila de MediaStore + SHA-256 del archivo).
 */
@RunWith(AndroidJUnit4::class)
class MediaSafetyTest {
    @get:Rule
    val permissions: GrantPermissionRule =
        GrantPermissionRule.grant(*mediaPermissions())

    private val fx = MediaFixture()
    private val resolver = fx.context.contentResolver
    private val queries = MediaQueries(resolver)
    private lateinit var db: AppDatabase
    private lateinit var dao: DecisionDao
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var approvals: ApprovalLauncher
    private lateinit var ops: MediaOps

    private val dirA get() = "Pictures/${fx.runId}_A/"
    private val dirB get() = "DCIM/${fx.runId}_B/"

    @Before
    fun setUp() {
        assumeTrue("Estos tests crean y borran fotos: solo se ejecutan en un emulador", fx.isEmulator())
        assumeTrue("Usan la papelera del sistema (Android 11+); Android 10 está en NoTrashSafetyTest", MediaOps.hasSystemTrash)
        fx.setManageMedia(true)
        if (Build.VERSION.SDK_INT >= 31) assertTrue("MANAGE_MEDIA no quedó concedido", MediaStore.canManageMedia(fx.context))
        db = Room.inMemoryDatabaseBuilder(fx.context, AppDatabase::class.java).build()
        dao = db.decisions()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { approvals = it.approvals }
        ops = MediaOps(resolver, queries, dao, { attachedVolumes() }, approvals::approve)
    }

    @After
    fun tearDown() {
        if (!::db.isInitialized) return
        try {
            if (::scenario.isInitialized) scenario.close()
            db.close()
        } finally {
            fx.cleanup()
            fx.setManageMedia(false)
        }
    }

    // ---------- helpers ----------

    private fun <T> blocking(block: suspend () -> T): T = runBlocking { withTimeout(90_000) { block() } }

    private fun seed(dir: String, count: Int, from: Int = 0) =
        (from until from + count).map { fx.seed(dir, "${fx.runId}_$it.jpg", variant = it) }

    private fun folders() = FolderIndex.build(queries.folderEntries(), emptyList()).visible

    private fun bucketOf(dir: String) = folders().single { it.name == dir.trimEnd('/').substringAfterLast('/') }.bucketId

    private fun stage(s: MediaFixture.Seeded, action: Action) = blocking {
        val item = queries.snapshot(listOf(s.key)).getValue(s.key)
        dao.insert(Decision.staged(item, action, dao.maxSeq() + 1))
    }

    private fun doneKeys() = blocking { dao.decidedKeys().toSet() - dao.staged().map { it.key() }.toSet() }

    private fun attachedVolumes(): Set<String> = MediaStore.getExternalVolumeNames(fx.context)

    private fun newSession() = ReviewSession(dao, { attachedVolumes() }) { key -> queries.snapshot(listOf(key))[key] }

    private suspend fun ReviewSession.decideFront(action: Action) = decide(action, deck.value.first().key)

    private fun assertUntouched(s: MediaFixture.Seeded) {
        val row = checkNotNull(fx.row(s.key)) { "${s.displayName} desapareció" }
        assertFalse("${s.displayName} no debía ir a la papelera", row.isTrashed)
        assertEquals("${s.displayName} no debía moverse", s.path, row.path)
        assertEquals("${s.displayName} bytes intactos", s.sha256, fx.sha256(s.path))
    }

    private fun assertTrashedIntact(s: MediaFixture.Seeded) {
        val row = checkNotNull(fx.row(s.key)) { "${s.displayName} desapareció en vez de ir a la papelera" }
        assertTrue("${s.displayName} debía estar en la papelera", row.isTrashed)
        assertTrue("renombrada a .trashed-: ${row.path}", row.path.substringAfterLast('/').startsWith(".trashed-"))
        assertEquals("${s.displayName} bytes intactos en la papelera", s.sha256, fx.sha256(row.path))
    }

    private fun assertMoved(s: MediaFixture.Seeded, target: String): MediaFixture.Row {
        val row = checkNotNull(fx.row(s.key)) { "${s.displayName} desapareció al mover" }
        assertFalse(row.isTrashed)
        assertEquals(target, row.relativePath)
        assertEquals("${s.displayName} bytes intactos tras mover", s.sha256, fx.sha256(row.path))
        assertNull("ya no debe estar en el origen", fx.sha256(s.path))
        return row
    }

    // ---------- T1 ----------

    @Test
    fun folderSelection_returnsExactlyTheChosenFolders() {
        val a = seed(dirA, 3, from = 0)
        val b = seed(dirB, 2, from = 10)
        val fav = fx.seed(Folders.FAVORITOS, "${fx.runId}_fav.jpg", variant = 20)
        val liked = fx.seed(Folders.LIKED, "${fx.runId}_liked.jpg", variant = 21)

        val folders = folders()
        val bucketA = folders.single { it.name == "${fx.runId}_A" }
        val bucketB = folders.single { it.name == "${fx.runId}_B" }
        assertEquals(3, bucketA.count)
        assertEquals(2, bucketB.count)
        assertTrue(
            "Favoritos/Liked no se ofrecen como carpetas",
            folders.none { it.name.equals("Favoritos", ignoreCase = true) || it.name.equals("Liked", ignoreCase = true) },
        )

        val keysA = a.map { it.key }.toSet()
        val keysB = b.map { it.key }.toSet()
        assertEquals(keysA, queries.deck(listOf(bucketA.bucketId)).map { it.key }.toSet())
        assertEquals(keysB, queries.deck(listOf(bucketB.bucketId)).map { it.key }.toSet())
        assertEquals(keysA + keysB, queries.deck(listOf(bucketA.bucketId, bucketB.bucketId)).map { it.key }.toSet())

        val all = queries.deck(null).map { it.key }.toSet()
        assertTrue(all.containsAll(keysA + keysB))
        assertFalse("Favoritos excluida del mazo", fav.key in all)
        assertFalse("Liked excluida del mazo", liked.key in all)
    }

    // ---------- T2 ----------

    @Test
    fun swipingOnlyRecordsDecisions_filesAreUntouched() {
        val seeds = seed(dirA, 4)
        val session = newSession()
        blocking {
            session.load(queries.deck(listOf(bucketOf(dirA))))
            listOf(Action.TRASH, Action.FAVORITOS, Action.LIKED, Action.KEEP).forEach {
                assertTrue(session.decideFront(it))
            }
        }
        assertEquals(4, blocking { dao.staged() }.size)
        assertTrue(session.deck.value.isEmpty())
        seeds.forEach(::assertUntouched)
    }

    // ---------- T3 ----------

    @Test
    fun undoneDecision_isNotExecuted() {
        val seeds = seed(dirA, 3)
        val bucket = bucketOf(dirA)
        val session = newSession()
        val undone = blocking {
            session.load(queries.deck(listOf(bucket)))
            repeat(3) { assertTrue(session.decideFront(Action.TRASH)) }
            checkNotNull(session.undo()).decision
        }

        val result = blocking { ops.commitStaged() }

        assertEquals(MediaOps.OpResult(2, emptyList(), cancelled = false), result)
        seeds.forEach { if (it.key == undone.key()) assertUntouched(it) else assertTrashedIntact(it) }
        blocking { session.load(queries.deck(listOf(bucket))) }
        assertEquals(listOf(undone.key()), session.deck.value.map { it.key })
    }

    // ---------- T4, T5, T7 ----------

    @Test
    fun commit_trashesExactlyTheStagedPhotos_recoverableFor30Days() {
        val (a, b, control) = seed(dirA, 3)
        stage(a, Action.TRASH)
        stage(b, Action.TRASH)
        val beforeSec = System.currentTimeMillis() / 1000

        val result = blocking { ops.commitStaged() }

        val afterSec = System.currentTimeMillis() / 1000
        assertEquals(MediaOps.OpResult(2, emptyList(), cancelled = false), result)
        assertTrashedIntact(a)
        assertTrashedIntact(b)
        assertUntouched(control)

        val thirtyDays = 30L * 24 * 3600
        listOf(a, b).forEach {
            val expires = checkNotNull(fx.row(it.key)?.dateExpires) { "sin DATE_EXPIRES" }
            assertTrue("DATE_EXPIRES=$expires", expires in (beforeSec + thirtyDays - 300)..(afterSec + thirtyDays + 300))
        }

        assertEquals(listOf(control.key), queries.deck(listOf(bucketOf(dirA))).map { it.key })
        val trash = queries.trash().map { it.key }
        assertTrue("la papelera lista fotos que no son de la app", trash.containsAll(listOf(a.key, b.key)))
        listOf(a, b).forEach { assertNotEquals("dueño no es la app", fx.context.packageName, fx.row(it.key)?.owner) }
        assertEquals(setOf(a.key, b.key), doneKeys())
    }

    // ---------- T6 ----------

    @Test
    fun restore_bringsPhotosBackWithSameBytes_evenWithNameCollision() {
        val (plain, colliding) = seed(dirA, 2)
        stage(plain, Action.TRASH)
        stage(colliding, Action.TRASH)
        blocking { ops.commitStaged() }
        // Mientras está en la papelera aparece otra foto con el mismo nombre en la misma carpeta.
        val newcomer = fx.seed(dirA, colliding.displayName, variant = 99)

        val trashed = queries.trash().filter { it.key == plain.key || it.key == colliding.key }
        assertEquals(2, trashed.size)
        val result = blocking { ops.restore(trashed) }

        assertEquals(MediaOps.OpResult(2, emptyList(), cancelled = false), result)
        assertUntouched(plain)
        val restored = checkNotNull(fx.row(colliding.key))
        assertFalse(restored.isTrashed)
        assertEquals(dirA, restored.relativePath)
        assertNotEquals("no debe pisar la otra foto", newcomer.path, restored.path)
        assertEquals(colliding.sha256, fx.sha256(restored.path))
        assertUntouched(newcomer)
        assertTrue(blocking { dao.decidedKeys() }.none { it == plain.key || it == colliding.key })
    }

    // ---------- T8 ----------

    @Test
    fun deleteForever_removesOnlyTheSelectedTrashedPhoto() {
        val (doomed, kept, notInTrash) = seed(dirA, 3)
        stage(doomed, Action.TRASH)
        stage(kept, Action.TRASH)
        blocking { ops.commitStaged() }
        val doomedPath = checkNotNull(fx.row(doomed.key)).path
        val doomedItem = queries.trash().single { it.key == doomed.key }
        val notInTrashItem = queries.snapshot(listOf(notInTrash.key)).getValue(notInTrash.key)

        val result = blocking { ops.deleteForever(listOf(doomedItem, notInTrashItem)) }

        assertEquals(1, result.done)
        assertEquals(listOf(notInTrash.displayName), result.failures.map { it.displayName })
        assertNull("fila borrada", fx.row(doomed.key))
        assertNull("archivo borrado", fx.sha256(doomedPath))
        assertTrashedIntact(kept)
        assertUntouched(notInTrash)
    }

    // ---------- T9 ----------

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
        val movedDup = assertMoved(incoming, Folders.FAVORITOS)
        assertNotEquals("renombrada, no sobrescrita", dupName, movedDup.displayName)
        assertUntouched(existing)
        assertEquals(setOf(toFav.key, toLiked.key, incoming.key), doneKeys())
    }

    // ---------- T10 ----------

    @Test
    fun mixedBatch_reachesTheExactExpectedState() {
        val seeds = seed(dirA, 10).associateBy { it.key }
        val bucket = bucketOf(dirA)
        val session = newSession()
        val order = blocking {
            session.load(queries.deck(listOf(bucket)))
            session.deck.value.map { it.key }
        }
        assertEquals(seeds.keys, order.toSet())
        val plan = listOf(
            Action.TRASH, Action.TRASH, Action.TRASH, Action.FAVORITOS, Action.FAVORITOS,
            Action.LIKED, Action.LIKED, Action.KEEP, Action.KEEP, Action.TRASH,
        )
        val undoneKey = order.last()
        blocking {
            plan.forEach { assertTrue(session.decideFront(it)) }
            assertEquals(undoneKey, checkNotNull(session.undo()).decision.key())
        }

        val result = blocking { ops.commitStaged() }

        assertEquals(MediaOps.OpResult(9, emptyList(), cancelled = false), result)
        order.forEachIndexed { i, key ->
            val s = seeds.getValue(key)
            when {
                key == undoneKey -> assertUntouched(s)
                plan[i] == Action.TRASH -> assertTrashedIntact(s)
                plan[i] == Action.FAVORITOS -> assertMoved(s, Folders.FAVORITOS)
                plan[i] == Action.LIKED -> assertMoved(s, Folders.LIKED)
                else -> assertUntouched(s)
            }
        }
        assertTrue(blocking { dao.staged() }.isEmpty())
        blocking { session.load(queries.deck(listOf(bucket))) }
        assertEquals("solo la deshecha vuelve a aparecer", listOf(undoneKey), session.deck.value.map { it.key })
    }

    // ---------- T11 ----------

    @Test
    fun photosChangedOrDeletedAfterDeciding_areSkipped() {
        val (changed, deleted, ok1, ok2) = seed(dirA, 4)
        listOf(changed, deleted, ok1, ok2).forEach { stage(it, Action.TRASH) }

        val newBytes = fx.jpeg(variant = 77, padding = 333)
        fx.writeViaShell(changed.path, newBytes)
        fx.scan(changed.path)
        fx.waitFor("reindexar la foto modificada") {
            val row = fx.row(changed.key)
            if (row == null || row.values["_size"] == newBytes.size.toString()) true else null
        }
        fx.sh("rm ${deleted.path}")
        fx.waitFor("desindexar la foto borrada") { if (fx.row(deleted.key) == null) true else null }

        val result = blocking { ops.commitStaged() }

        assertEquals(2, result.done)
        assertFalse(result.cancelled)
        assertEquals(setOf(changed.displayName, deleted.displayName), result.failures.map { it.displayName }.toSet())
        assertEquals("la modificada sigue en su sitio, sin tocar", fx.sha256(newBytes), fx.sha256(changed.path))
        assertTrashedIntact(ok1)
        assertTrashedIntact(ok2)
        assertTrue(blocking { dao.decidedKeys() }.none { it == changed.key || it == deleted.key })
    }

    // ---------- T12 ----------

    @Test
    fun resultOkIsNeverTrustedBlindly_photoVanishesBeforeApproval() {
        val (vanishing, normal) = seed(dirA, 2)
        stage(vanishing, Action.TRASH)
        stage(normal, Action.TRASH)
        val sabotaged = MediaOps(resolver, queries, dao, { attachedVolumes() }) { request ->
            fx.sh("rm ${vanishing.path}")
            fx.waitFor("desindexar") { if (fx.row(vanishing.key) == null) true else null }
            approvals.approve(request)
        }

        val result = blocking { sabotaged.commitStaged() }

        // Con "Gestión de multimedia" el sistema aprueba siempre: el resultado es determinista.
        assertFalse(result.cancelled)
        assertEquals(1, result.done)
        assertEquals("la desaparecida se informa", listOf(vanishing.displayName), result.failures.map { it.displayName })
        assertTrashedIntact(normal)
        assertEquals("solo la verificada queda hecha", setOf(normal.key), doneKeys())
        assertFalse("la desaparecida no queda registrada", vanishing.key in blocking { dao.decidedKeys() })
    }

    // ---------- T13 ----------

    @Test
    fun withoutMediaManagement_cancellingTheSystemDialogChangesNothing() {
        fx.setManageMedia(false)
        if (Build.VERSION.SDK_INT >= 31) assertFalse(MediaStore.canManageMedia(fx.context))
        val (photo) = seed(dirA, 1)
        stage(photo, Action.TRASH)
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

        val result = runBlocking {
            val commit = async(Dispatchers.Default) { ops.commitStaged() }
            assertTrue("no apareció el diálogo del sistema", device.wait(Until.hasObject(By.res("android", "button2")), 15_000) == true)
            // El diálogo de MediaProvider no se cierra con Atrás: se cancela con "Denegar".
            device.findObject(By.res("android", "button2")).click()
            withTimeout(30_000) { commit.await() }
        }

        assertTrue(result.cancelled)
        assertEquals(0, result.done)
        assertUntouched(photo)
        assertEquals(listOf(photo.key), blocking { dao.staged() }.map { it.key() })
    }

    @Test
    fun withoutMediaManagement_approvingTheSystemDialogCommits() {
        fx.setManageMedia(false)
        if (Build.VERSION.SDK_INT >= 31) assertFalse(MediaStore.canManageMedia(fx.context))
        val (photo) = seed(dirA, 1)
        stage(photo, Action.TRASH)
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

        val result = runBlocking {
            val commit = async(Dispatchers.Default) { ops.commitStaged() }
            assertTrue("no apareció el diálogo del sistema", device.wait(Until.hasObject(By.res("android", "button1")), 15_000) == true)
            device.findObject(By.res("android", "button1")).click()
            withTimeout(30_000) { commit.await() }
        }

        assertEquals(MediaOps.OpResult(1, emptyList(), cancelled = false), result)
        assertTrashedIntact(photo)
    }

    // ---------- Regresiones de la revisión adversarial ----------

    @Test
    fun decisionsOnADisconnectedVolume_stayPending_andDoNotBlockTheRest() {
        val (photo) = seed(dirA, 1)
        stage(photo, Action.TRASH)
        val onMissingCard = Decision(
            volume = "0000-0000", mediaId = 1, action = Action.TRASH, status = Status.STAGED, seq = 99,
            displayName = "${fx.runId}_sd.jpg", relativePath = "DCIM/Camera/", size = 10, dateModified = 1,
        )
        blocking { dao.insert(onMissingCard) }

        val result = blocking { ops.commitStaged() }

        assertEquals(1, result.done)
        assertEquals(listOf(onMissingCard.displayName), result.failures.map { it.displayName })
        assertTrashedIntact(photo)
        assertEquals("sigue pendiente, no se da por inexistente", listOf(onMissingCard.key()), blocking { dao.staged() }.map { it.key() })
    }

    @Test
    fun commitAppliesOnlyWhatTheSummaryShowed() {
        val (shown, stagedLater) = seed(dirA, 2)
        stage(shown, Action.TRASH)
        val shownSeq = blocking { dao.maxSeq() }
        stage(stagedLater, Action.TRASH)

        val result = blocking { ops.commitStaged(upToSeq = shownSeq) }

        assertEquals(MediaOps.OpResult(1, emptyList(), cancelled = false), result)
        assertTrashedIntact(shown)
        assertUntouched(stagedLater)
        assertEquals(listOf(stagedLater.key), blocking { dao.staged() }.map { it.key() })
    }

    @Test
    fun keepingAPhotoThatIsAlreadyInTheTrash_isReportedInsteadOfHidden() {
        val (photo) = seed(dirA, 1)
        stage(photo, Action.TRASH)
        blocking { ops.commitStaged() }
        val trashedItem = queries.snapshot(listOf(photo.key)).getValue(photo.key)
        blocking {
            dao.delete(photo.key.volume, photo.key.mediaId)
            dao.insert(Decision.staged(trashedItem, Action.KEEP, dao.maxSeq() + 1))
        }

        val result = blocking { ops.commitStaged() }

        assertEquals(0, result.done)
        assertTrue(result.failures.single().reason.contains("papelera"))
        assertTrue("sin fila: no queda oculta como revisada", blocking { dao.decidedKeys() }.isEmpty())
        assertTrashedIntact(photo)
    }

    @Test
    fun undoingATrashThatWasAlreadyApplied_keepsItVisibleInTheTrash() {
        val (photo) = seed(dirA, 1)
        val session = newSession()
        blocking {
            session.load(queries.deck(listOf(bucketOf(dirA))))
            assertTrue(session.decideFront(Action.TRASH))
        }
        // Simula una confirmación interrumpida: la foto llegó a la papelera pero la decisión sigue registrada.
        val other = Room.inMemoryDatabaseBuilder(fx.context, AppDatabase::class.java).build()
        try {
            val otherOps = MediaOps(resolver, queries, other.decisions(), { attachedVolumes() }, approvals::approve)
            blocking {
                val item = queries.snapshot(listOf(photo.key)).getValue(photo.key)
                other.decisions().insert(Decision.staged(item, Action.TRASH, 1))
                otherOps.commitStaged()
            }
        } finally {
            other.close()
        }
        assertTrashedIntact(photo)

        val undone = checkNotNull(blocking { session.undo() })

        assertFalse("no vuelve al mazo", undone.backInDeck)
        assertTrue(session.deck.value.isEmpty())
        assertEquals(setOf(photo.key), doneKeys())
        assertTrue("sigue visible en Papelera", queries.trash().any { it.key == photo.key })
    }

    // ---------- Videos, WhatsApp y carpetas archivadas ----------

    @Test
    fun videos_areListedTrashedRestoredAndMovedLikePhotos() {
        val dirV = "Movies/${fx.runId}_V/"
        val toTrash = fx.seed(dirV, "${fx.runId}_v1.mp4", variant = 1)
        val toFav = fx.seed(dirV, "${fx.runId}_v2.mp4", variant = 2)
        val folder = folders().single { it.name == "${fx.runId}_V" }
        assertEquals(2, folder.count)
        val deck = queries.deck(listOf(folder.bucketId))
        assertEquals(setOf(toTrash.key, toFav.key), deck.map { it.key }.toSet())
        assertTrue(deck.all { it.kind == MediaKind.VIDEO })
        stage(toTrash, Action.TRASH)
        stage(toFav, Action.FAVORITOS)

        val result = blocking { ops.commitStaged() }

        assertEquals(MediaOps.OpResult(2, emptyList(), cancelled = false), result)
        assertTrashedIntact(toTrash)
        assertMoved(toFav, Folders.FAVORITOS)
        val trashed = queries.trash().filter { it.key == toTrash.key }
        assertEquals(MediaKind.VIDEO, trashed.single().kind)
        assertEquals(MediaOps.OpResult(1, emptyList(), cancelled = false), blocking { ops.restore(trashed) })
        assertUntouched(toTrash)
    }

    @Test
    fun whatsappFiles_canBeTrashed_andFavoritesGoThroughAVerifiedCopy() {
        val dirW = "Android/media/com.whatsapp/WhatsApp/Media/${fx.runId}_WA/"
        val toTrash = fx.seed(dirW, "${fx.runId}_w0.jpg", variant = 0)
        val photo = fx.seed(dirW, "${fx.runId}_w1.jpg", variant = 1)
        val video = fx.seed(dirW, "${fx.runId}_w2.mp4", variant = 2)
        assertTrue("la carpeta de WhatsApp aparece", folders().any { it.name == "${fx.runId}_WA" && it.count == 3 })
        stage(toTrash, Action.TRASH)
        stage(photo, Action.FAVORITOS)
        stage(video, Action.LIKED)

        val result = blocking { ops.commitStaged() }

        assertEquals(MediaOps.OpResult(3, emptyList(), cancelled = false), result)
        // Borrar sí funciona ahí; mover no, así que se copia y el original va a la papelera.
        assertTrashedIntact(toTrash)
        assertTrashedIntact(photo)
        assertTrashedIntact(video)
        assertCopyIsIdentical(photo, Folders.FAVORITOS)
        assertCopyIsIdentical(video, Folders.LIKED)
        assertEquals(setOf(toTrash.key, photo.key, video.key), doneKeys())
    }

    /** Hay UNA sola copia en [target], fuera de la papelera y con exactamente los mismos bytes. */
    private fun assertCopyIsIdentical(original: MediaFixture.Seeded, target: String) {
        val copyKey = checkNotNull(fx.findKey(target, original.displayName)) { "no se creó la copia en $target" }
        assertNotEquals("la copia es otro archivo", original.key, copyKey)
        val row = checkNotNull(fx.row(copyKey))
        assertFalse(row.isTrashed)
        assertEquals(target, row.relativePath)
        assertEquals("la copia es idéntica", original.sha256, fx.sha256(row.path))
        val base = original.displayName.substringBeforeLast('.')
        assertEquals("no se duplicó", 1, fx.countFiles("/storage/emulated/0/$target".trimEnd('/'), base))
    }

    @Test
    fun denyingTheTrashDialog_leavesNoDuplicateBehind() {
        val dirW = "Android/media/com.whatsapp/WhatsApp/Media/${fx.runId}_WA/"
        val photo = fx.seed(dirW, "${fx.runId}_c1.jpg", variant = 1)
        val video = fx.seed(dirW, "${fx.runId}_c2.mp4", variant = 2)
        stage(photo, Action.FAVORITOS)
        stage(video, Action.LIKED)
        // El usuario deniega el diálogo del sistema al mandar los originales a la papelera.
        val denying = MediaOps(resolver, queries, dao, { attachedVolumes() }) { false }

        val result = blocking { denying.commitStaged() }

        assertTrue(result.cancelled)
        assertEquals(0, result.done)
        assertUntouched(photo)
        assertUntouched(video)
        assertEquals("ninguna copia queda suelta", 0, fx.countFilesContaining("/storage/emulated/0/Pictures/Favoritos", fx.runId))
        assertEquals("ninguna copia queda suelta", 0, fx.countFilesContaining("/storage/emulated/0/Pictures/Liked", fx.runId))
        assertEquals(setOf(photo.key, video.key), blocking { dao.staged() }.map { it.key() }.toSet())
    }

    @Test
    fun ifTheOriginalVanishesAfterCopying_theCopyIsKept() {
        val dirW = "Android/media/com.whatsapp/WhatsApp/Media/${fx.runId}_WA/"
        val photo = fx.seed(dirW, "${fx.runId}_v1.jpg", variant = 3)
        stage(photo, Action.FAVORITOS)
        // Otra app borra el original justo después de copiarlo, antes de mandarlo a la papelera.
        val sabotaged = MediaOps(resolver, queries, dao, { attachedVolumes() }) { request ->
            fx.sh("rm ${photo.path}")
            fx.waitFor("desindexar") { if (fx.row(photo.key) == null) true else null }
            approvals.approve(request)
        }

        val result = blocking { sabotaged.commitStaged() }

        assertFalse(result.cancelled)
        assertEquals(1, result.done)
        val copyKey = checkNotNull(fx.findKey(Folders.FAVORITOS, photo.displayName)) { "la copia debía conservarse" }
        assertEquals("la copia sigue intacta", photo.sha256, fx.sha256(checkNotNull(fx.row(copyKey)).path))
        assertTrue(result.failures.single().reason.contains("se conservó la copia"))
    }

    @Test
    fun aFileAlreadyInTheTarget_isNeverDeletedByADeniedCopy() {
        val dirW = "Android/media/com.whatsapp/WhatsApp/Media/${fx.runId}_WA/"
        val photo = fx.seed(dirW, "${fx.runId}_p1.jpg", variant = 5)
        // Un favorito anterior con los mismos bytes y creado por la propia app, como sus copias.
        val existing = fx.seedAsApp(Folders.FAVORITOS, "${fx.runId}_p1.jpg", variant = 5)
        stage(photo, Action.FAVORITOS)
        val denying = MediaOps(resolver, queries, dao, { attachedVolumes() }) { false }

        val result = blocking { denying.commitStaged() }

        assertTrue(result.cancelled)
        assertUntouched(photo)
        assertUntouched(existing)
        assertEquals("solo queda el favorito que ya estaba", 1, fx.countFiles("/storage/emulated/0/Pictures/Favoritos", fx.runId))
    }

    @Test
    fun anErrorAfterTheTrashRequestWasSent_neverDeletesTheCopy() {
        val dirW = "Android/media/com.whatsapp/WhatsApp/Media/${fx.runId}_WA/"
        val photo = fx.seed(dirW, "${fx.runId}_e1.jpg", variant = 6)
        stage(photo, Action.FAVORITOS)
        // El sistema aprueba y manda el original a la papelera, pero después algo falla en la app.
        val failingAfterApproval = MediaOps(resolver, queries, dao, { attachedVolumes() }) { request ->
            approvals.approve(request)
            throw IllegalStateException("fallo simulado")
        }

        val error = runCatching { blocking { failingAfterApproval.commitStaged() } }.exceptionOrNull()

        assertTrue("el fallo llega a la pantalla", error is IllegalStateException)
        assertTrashedIntact(photo)
        val copyKey = checkNotNull(fx.findKey(Folders.FAVORITOS, photo.displayName)) { "la copia debía conservarse" }
        assertEquals("la copia sigue intacta", photo.sha256, fx.sha256(checkNotNull(fx.row(copyKey)).path))
    }

    @Test
    fun cancellingTheCopyFlow_atAnyMoment_neverLosesOrCorruptsAnything() {
        val dirW = "Android/media/com.whatsapp/WhatsApp/Media/${fx.runId}_WA/"
        // Un video grande para que la cancelación tenga buenas chances de caer a mitad de la copia.
        val video = fx.seed(dirW, "${fx.runId}_big.mp4", variant = 7, videoBytes = 32 * 1024 * 1024)
        stage(video, Action.LIKED)
        // Si la cancelación llega tarde, el sistema "deniega": en ningún caso se manda nada a la papelera.
        val ops = MediaOps(resolver, queries, dao, { attachedVolumes() }) { false }
        val liked = "/storage/emulated/0/Pictures/Liked"

        runBlocking {
            val job = launch(Dispatchers.Default) { ops.commitStaged() }
            fx.waitFor("que empiece la copia", timeoutMs = 30_000) {
                if (job.isCompleted || fx.countFilesContaining(liked, fx.runId) > 0) true else null
            }
            job.cancelAndJoin()
        }

        // Cancelar a mitad de la copia la borra; cancelar después de pedir la papelera la conserva (regla
        // conservadora). En los dos casos: el original intacto y nunca una copia a medias.
        assertUntouched(video)
        assertEquals(listOf(video.key), blocking { dao.staged() }.map { it.key() })
        val leftovers = fx.sh("find $liked -maxdepth 1 -type f -name *${fx.runId}* -exec sha256sum {} ;")
            .lineSequence().filter { it.isNotBlank() }.map { it.substringBefore(' ') }.toList()
        assertTrue("como mucho queda una copia: $leftovers", leftovers.size <= 1)
        assertTrue("una copia que quede debe ser idéntica", leftovers.all { it == video.sha256 })
    }

    @Test
    fun cancellingRightAfterTheTrashWasApproved_keepsTheOnlyCopy() {
        val dirW = "Android/media/com.whatsapp/WhatsApp/Media/${fx.runId}_WA/"
        val photo = fx.seed(dirW, "${fx.runId}_k1.jpg", variant = 10)
        stage(photo, Action.FAVORITOS)
        lateinit var job: Job
        // El sistema manda el original a la papelera y justo después se cancela (p. ej. se cierra la pantalla).
        val ops = MediaOps(resolver, queries, dao, { attachedVolumes() }) { request ->
            approvals.approve(request).also { job.cancel() }
        }

        runBlocking {
            job = launch(Dispatchers.Default, start = CoroutineStart.LAZY) { ops.commitStaged() }
            job.start()
            job.join()
        }

        assertTrue(job.isCancelled)
        assertTrashedIntact(photo)
        val copyKey = checkNotNull(fx.findKey(Folders.FAVORITOS, photo.displayName)) { "la única copia debía conservarse" }
        assertEquals("la copia sigue intacta", photo.sha256, fx.sha256(checkNotNull(fx.row(copyKey)).path))
        assertEquals(1, fx.countFilesContaining("/storage/emulated/0/Pictures/Favoritos", fx.runId))
        assertEquals("queda pendiente para liquidarla después", listOf(photo.key), blocking { dao.staged() }.map { it.key() })
    }

    @Test
    fun anErrorWhileTheTrashDialogIsStillOpen_neverDeletesTheCopy() {
        val dirW = "Android/media/com.whatsapp/WhatsApp/Media/${fx.runId}_WA/"
        val photo = fx.seed(dirW, "${fx.runId}_o1.jpg", variant = 8)
        stage(photo, Action.FAVORITOS)
        var captured: PendingIntent? = null
        val failsBeforeTheAnswer = MediaOps(resolver, queries, dao, { attachedVolumes() }) { request ->
            captured = request
            throw IllegalStateException("fallo con el diálogo abierto")
        }

        val error = runCatching { blocking { failsBeforeTheAnswer.commitStaged() } }.exceptionOrNull()
        // El diálogo seguía en pantalla y el usuario lo aprueba después del fallo.
        assertTrue(blocking { approvals.approve(checkNotNull(captured)) })

        assertTrue("el fallo llega a la pantalla", error is IllegalStateException)
        fx.waitFor("que el original llegue a la papelera") { if (fx.row(photo.key)?.isTrashed == true) true else null }
        assertTrashedIntact(photo)
        val copyKey = checkNotNull(fx.findKey(Folders.FAVORITOS, photo.displayName)) { "la copia debía conservarse" }
        assertEquals("la copia sigue intacta", photo.sha256, fx.sha256(checkNotNull(fx.row(copyKey)).path))
    }

    @Test
    fun aCopyThatDisappearsBeforeSettling_isNotReportedAsDone() {
        val dirW = "Android/media/com.whatsapp/WhatsApp/Media/${fx.runId}_WA/"
        val photo = fx.seed(dirW, "${fx.runId}_d1.jpg", variant = 9)
        stage(photo, Action.FAVORITOS)
        // Alguien borra la copia ya publicada justo antes de que el original vaya a la papelera.
        val sabotaged = MediaOps(resolver, queries, dao, { attachedVolumes() }) { request ->
            val copyKey = checkNotNull(fx.findKey(Folders.FAVORITOS, photo.displayName))
            fx.sh("rm ${checkNotNull(fx.row(copyKey)).path}")
            fx.waitFor("que desaparezca la copia") { if (fx.findKey(Folders.FAVORITOS, photo.displayName) == null) true else null }
            approvals.approve(request)
        }

        val result = blocking { sabotaged.commitStaged() }

        assertEquals("no se da por hecho", 0, result.done)
        assertTrue(result.failures.single().reason.contains("la copia no quedó en su sitio"))
        assertFalse(photo.key in blocking { dao.decidedKeys() })
        assertTrashedIntact(photo)
    }

    @Test
    fun archivedFolder_hidesWhatWasThere_andReappearsOnlyWithNewFiles() {
        val (old1, old2) = seed(dirA, 2)
        val name = "${fx.runId}_A"
        val before = folders().single { it.name == name }
        val archive = ArchivedFolder(before.volume, before.bucketId, before.latestAdded, before.name, before.relativePath)

        val archivedIndex = FolderIndex.build(queries.folderEntries(), listOf(archive))
        assertTrue(archivedIndex.visible.none { it.name == name })
        assertTrue(archivedIndex.archived.any { it.name == name })
        val allVisible = queries.deck(null).filter { FolderIndex.isVisible(it, listOf(archive)) }.map { it.key }
        assertFalse(old1.key in allVisible || old2.key in allVisible)

        Thread.sleep(1_100) // date_added tiene resolución de segundos
        val fresh = fx.seed(dirA, "${fx.runId}_new.jpg", variant = 9)

        val reappeared = FolderIndex.build(queries.folderEntries(), listOf(archive)).visible.single { it.name == name }
        assertTrue(reappeared.hasNew)
        assertEquals(1, reappeared.count)
        val deck = queries.deck(listOf(reappeared.bucketId)).filter { FolderIndex.isVisible(it, listOf(archive)) }
        assertEquals(listOf(fresh.key), deck.map { it.key })
        assertUntouched(old1)
        assertUntouched(old2)
    }

    // ---------- comprobaciones justo antes de tocar cada lote ----------

    @Test
    fun deleteForever_checksEachBatch_soSomethingRestoredMeanwhileSurvives() {
        val photo = fx.seed(dirA, "${fx.runId}_d1.jpg", variant = 61)
        val video = fx.seed(dirA, "${fx.runId}_d2.mp4", variant = 62)
        stage(photo, Action.TRASH)
        stage(video, Action.TRASH)
        blocking { ops.commitStaged() }
        val photoPath = checkNotNull(fx.row(photo.key)).path
        val trashed = queries.trash()
        val photoItem = trashed.single { it.key == photo.key }
        val videoItem = trashed.single { it.key == video.key }
        // Mientras se aprueba el primer lote (las fotos), otra app saca el video de la papelera.
        var firstRequest = true
        val restoringMeanwhile = MediaOps(resolver, queries, dao, { attachedVolumes() }) { request ->
            if (firstRequest) {
                firstRequest = false
                fx.untrash(video.key, MediaKind.VIDEO)
            }
            approvals.approve(request)
        }

        val result = blocking { restoringMeanwhile.deleteForever(listOf(photoItem, videoItem)) }

        assertEquals(1, result.done)
        assertNull("la foto del primer lote sí se borra", fx.row(photo.key))
        assertNull("y su archivo también", fx.sha256(photoPath))
        val row = checkNotNull(fx.row(video.key)) { "el video restaurado NO debía borrarse" }
        assertFalse("el video quedó fuera de la papelera", row.isTrashed)
        assertEquals("con sus bytes intactos", video.sha256, fx.sha256(row.path))
        assertEquals(listOf(video.displayName), result.failures.map { it.displayName })
    }

    @Test
    fun anOriginalChangedWhileCopying_isNeverTrashed_andItsCopyIsRemoved() {
        val dirW = "Android/media/com.whatsapp/WhatsApp/Media/${fx.runId}_WA/"
        val photo = fx.seed(dirW, "${fx.runId}_c1.jpg", variant = 81)
        val video = fx.seed(dirW, "${fx.runId}_c2.mp4", variant = 82)
        stage(photo, Action.FAVORITOS)
        stage(video, Action.FAVORITOS)
        // Todo se copia primero y los lotes van por tipo: mientras se aprueba el de fotos, otra app
        // reescribe el video original.
        var firstRequest = true
        val changingMeanwhile = MediaOps(resolver, queries, dao, { attachedVolumes() }) { request ->
            if (firstRequest) {
                firstRequest = false
                fx.rewrite(video, variant = 99)
            }
            approvals.approve(request)
        }

        val result = blocking { changingMeanwhile.commitStaged() }

        assertTrashedIntact(photo)
        val copy = checkNotNull(fx.findKey(Folders.FAVORITOS, photo.displayName)) { "la foto sí se copia" }
        assertEquals(photo.sha256, fx.sha256(checkNotNull(fx.row(copy)).path))
        val videoRow = checkNotNull(fx.row(video.key)) { "el video cambiado no debía desaparecer" }
        assertFalse("el video cambiado NO va a la papelera", videoRow.isTrashed)
        assertEquals(
            "no queda copia del video en Favoritos",
            0,
            fx.countFilesContaining("/storage/emulated/0/Pictures/Favoritos", video.displayName),
        )
        assertTrue(
            "se avisa del video: ${result.failures}",
            result.failures.any { it.displayName == video.displayName && it.reason.contains("cambió") },
        )
    }

    @Test
    fun aSecondCommitWhileOneIsRunning_doesNothing() {
        val (a, b) = seed(dirA, 2, from = 70)
        stage(a, Action.TRASH)
        stage(b, Action.TRASH)
        val inside = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val slow = MediaOps(resolver, queries, dao, { attachedVolumes() }) { request ->
            inside.complete(Unit)
            release.await()
            approvals.approve(request)
        }

        runBlocking {
            val first = async(Dispatchers.Default) { slow.commitStaged() }
            try {
                withTimeout(30_000) { inside.await() } // el primero ya está dentro
                // Con timeout: si el candado fallara, el segundo se quedaría esperando y el test colgaría.
                val second = withTimeout(10_000) { slow.commitStaged() }

                assertEquals(0, second.done)
                assertTrue("avisa que hay algo en curso: ${second.failures}", second.failures.single().reason.contains("ya hay cambios"))
            } finally {
                release.complete(Unit)
            }
            assertEquals("el primero termina completo", 2, withTimeout(60_000) { first.await() }.done)
        }
        assertTrashedIntact(a)
        assertTrashedIntact(b)
    }

    @Test
    fun anOriginalDeletedByAnotherAppWhileCopying_keepsTheVerifiedCopy() {
        val dirW = "Android/media/com.whatsapp/WhatsApp/Media/${fx.runId}_WA/"
        val photo = fx.seed(dirW, "${fx.runId}_v1.jpg", variant = 91)
        val video = fx.seed(dirW, "${fx.runId}_v2.mp4", variant = 92)
        stage(photo, Action.FAVORITOS)
        stage(video, Action.FAVORITOS)
        // Mientras se aprueba el lote de fotos, otra app (una limpieza de WhatsApp) borra el video original.
        var firstRequest = true
        val deletingMeanwhile = MediaOps(resolver, queries, dao, { attachedVolumes() }) { request ->
            if (firstRequest) {
                firstRequest = false
                fx.sh("rm ${video.path}")
                fx.scan(video.path)
                fx.waitFor("que desaparezca el original") { if (fx.row(video.key) == null) true else null }
            }
            approvals.approve(request)
        }

        val result = blocking { deletingMeanwhile.commitStaged() }

        assertTrashedIntact(photo)
        val copy = checkNotNull(fx.findKey(Folders.FAVORITOS, video.displayName)) { "la copia del video debía conservarse" }
        assertEquals("la copia tiene los bytes del original", video.sha256, fx.sha256(checkNotNull(fx.row(copy)).path))
        assertTrue("queda como hecha", video.key in doneKeys())
        assertTrue("se avisa: ${result.failures}", result.failures.any { it.displayName == video.displayName && it.reason.contains("se conservó la copia") })
    }

    @Test
    fun aFailureWhileAnotherAppDeletesAnOriginal_keepsAndPublishesItsCopy() {
        val dirW = "Android/media/com.whatsapp/WhatsApp/Media/${fx.runId}_WA/"
        val photo = fx.seed(dirW, "${fx.runId}_f1.jpg", variant = 95)
        val video = fx.seed(dirW, "${fx.runId}_f2.mp4", variant = 96)
        stage(photo, Action.FAVORITOS)
        stage(video, Action.FAVORITOS)
        // Todo ya está copiado. En el primer lote otra app borra el video original y algo falla en la app.
        val failing = MediaOps(resolver, queries, dao, { attachedVolumes() }) { _ ->
            fx.sh("rm ${video.path}")
            fx.scan(video.path)
            fx.waitFor("que desaparezca el original") { if (fx.row(video.key) == null) true else null }
            throw IllegalStateException("fallo simulado")
        }

        val error = runCatching { blocking { failing.commitStaged() } }.exceptionOrNull()

        assertTrue("el fallo llega a la pantalla", error is IllegalStateException)
        assertUntouched(photo)
        val copy = checkNotNull(fx.findKey(Folders.FAVORITOS, video.displayName)) { "la copia del video debía quedar visible" }
        assertEquals("con los bytes del original", video.sha256, fx.sha256(checkNotNull(fx.row(copy)).path))
    }
}
