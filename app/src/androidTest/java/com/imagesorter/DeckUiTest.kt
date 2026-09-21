package com.imagesorter

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.view.ViewConfiguration
import androidx.compose.ui.test.assertIsDisplayed
import androidx.core.content.IntentCompat
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.imagesorter.data.MediaOps
import com.imagesorter.data.MediaQueries
import com.imagesorter.data.db.AppDatabase
import com.imagesorter.domain.Action
import com.imagesorter.domain.FolderIndex
import com.imagesorter.domain.MediaKey
import com.imagesorter.domain.MediaKind
import com.imagesorter.ui.CONFIRM_MIN_VISIBLE_MS
import com.imagesorter.ui.MainActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** De punta a punta por la UI: elegir carpeta, toques y botones registran la decisión correcta sin tocar archivos. */
@RunWith(AndroidJUnit4::class)
class DeckUiTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(*mediaPermissions())

    @get:Rule
    val compose = createEmptyComposeRule()

    private val fx = MediaFixture()
    private val db get() = AppDatabase.get(fx.context)
    private var scenario: ActivityScenario<MainActivity>? = null
    private var active = false

    @Before
    fun setUp() {
        assumeTrue("Solo en emulador", fx.isEmulator())
        active = true
        fx.setManageMedia(true)
        db.clearAllTables()
    }

    @After
    fun tearDown() {
        if (!active) return
        try {
            scenario?.close()
            db.clearAllTables()
            fx.cleanup()
        } finally {
            fx.setManageMedia(false)
        }
    }

    /** Las confirmaciones destructivas ignoran toques hasta haber estado en pantalla este tiempo. */
    private fun waitUntilConfirmationIsReadable() {
        compose.waitForIdle()
        Thread.sleep(CONFIRM_MIN_VISIBLE_MS + 300)
    }

    private fun staged() = runBlocking { db.decisions().staged() }

    private fun lastStaged(): Pair<Action, MediaKey> = staged().last().let { it.action to it.key() }

    /**
     * Los toques se ignoran hasta que lo mostrado estuvo en pantalla lo que dura un doble toque. En tests
     * Compose solo recompone cuando el test sincroniza, así que primero se deja mostrar lo nuevo.
     */
    private fun waitPastDoubleTapTimeout() {
        compose.waitForIdle()
        Thread.sleep(ViewConfiguration.getDoubleTapTimeout() + 200L)
    }

    private fun deckOrder(folder: String): List<MediaKey> {
        val queries = MediaQueries(fx.context.contentResolver)
        val bucket = FolderIndex.build(queries.folderEntries(), emptyList()).visible.single { it.name == folder }.bucketId
        return queries.deck(listOf(bucket)).map { it.key }
    }

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Todas (", substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun archivedNamed(folder: String) = runBlocking { db.archive().observeAll().first() }.filter { it.name == folder }

    private fun openFolder(folder: String) {
        launch()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(folder))
        compose.onNodeWithText(folder).performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Revisar seleccionadas (1)"))
        compose.onNodeWithText("Revisar seleccionadas (1)").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("card").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun tapsAndButtons_recordTheRightDecisionForTheRightPhoto_withoutTouchingFiles() {
        val folder = "${fx.runId}_A"
        val seeds = (0 until 4).map { fx.seed("Pictures/$folder/", "${fx.runId}_$it.jpg", variant = it) }
        val order = deckOrder(folder)

        openFolder(folder)

        waitPastDoubleTapTimeout()
        compose.onNodeWithTag("tap-left").performClick()
        compose.waitUntil(5_000) { staged().size == 1 }
        assertEquals(Action.TRASH to order[0], lastStaged())

        waitPastDoubleTapTimeout()
        compose.onNodeWithTag("tap-right").performClick()
        compose.waitUntil(5_000) { staged().size == 2 }
        assertEquals(Action.KEEP to order[1], lastStaged())

        waitPastDoubleTapTimeout()
        compose.onNodeWithText("Favoritos").performClick()
        compose.waitUntil(5_000) { staged().size == 3 }
        assertEquals(Action.FAVORITOS to order[2], lastStaged())

        compose.onNodeWithText("Deshacer").performClick()
        compose.waitUntil(5_000) { staged().size == 2 }
        assertFalse(order[2] in staged().map { it.key() })

        waitPastDoubleTapTimeout()
        compose.onNodeWithText("Liked").performClick()
        compose.waitUntil(5_000) { staged().size == 3 }
        assertEquals(Action.LIKED to order[2], lastStaged())

        seeds.forEach { s ->
            val row = checkNotNull(fx.row(s.key))
            assertFalse("registrar no manda a la papelera", row.isTrashed)
            assertEquals("registrar no mueve", s.path, row.path)
            assertEquals("registrar no cambia bytes", s.sha256, fx.sha256(s.path))
        }
        compose.onNodeWithText("Confirmar (3)").assertIsEnabled()
    }

    @Test
    fun secondTapOfADoubleTap_neverDecidesForThePhotoThatJustAppeared() {
        val folder = "${fx.runId}_B"
        repeat(3) { fx.seed("Pictures/$folder/", "${fx.runId}_$it.jpg", variant = it) }
        val order = deckOrder(folder)

        openFolder(folder)
        waitPastDoubleTapTimeout()

        compose.onNodeWithTag("tap-left").performClick()
        compose.waitUntil(5_000) { staged().size == 1 }
        // La siguiente foto acaba de aparecer: un segundo toque inmediato no debe registrarla.
        compose.onNodeWithTag("tap-left").performClick()
        Thread.sleep(1_000)

        assertEquals(listOf(order[0]), staged().map { it.key() })
    }

    @Test
    fun slidingAFolder_archivesIt_andUndoBringsItBack() {
        val folder = "${fx.runId}_C"
        repeat(2) { fx.seed("Pictures/$folder/", "${fx.runId}_$it.jpg", variant = it) }
        fun archivedNames() = runBlocking { db.archive().observeAll().first() }.map { it.name }

        launch()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(folder))
        compose.onNodeWithText(folder).performTouchInput { swipeLeft() }

        compose.waitUntil(5_000) { compose.onAllNodesWithText(folder).fetchSemanticsNodes().isEmpty() }
        assertEquals(listOf(folder), archivedNames())

        compose.onNodeWithText("Deshacer").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(folder).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(folder).assertIsDisplayed()
        assertTrue(archivedNames().isEmpty())
    }

    @Test
    fun anArchivedFolderThatGetsNewPhotos_reappearsVisibleWithTheNewBadge() {
        val folder = "${fx.runId}_N"
        repeat(2) { fx.seed("Pictures/$folder/", "${fx.runId}_$it.jpg", variant = it) }
        launch()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(folder))
        compose.onNodeWithText(folder).performTouchInput { swipeLeft() }
        compose.waitUntil(5_000) { compose.onAllNodesWithText(folder).fetchSemanticsNodes().isEmpty() }

        // Llega una foto nueva a la carpeta archivada mientras la app está en segundo plano.
        checkNotNull(scenario).moveToState(Lifecycle.State.CREATED)
        Thread.sleep(1_100) // date_added tiene resolución de segundos
        fx.seed("Pictures/$folder/", "${fx.runId}_new.jpg", variant = 9)
        checkNotNull(scenario).moveToState(Lifecycle.State.RESUMED)

        compose.waitUntil(10_000) { compose.onAllNodes(hasText(folder) and hasText("1 nuevas")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(folder))
        compose.onNode(hasText(folder) and hasText("1 nuevas")).assertIsDisplayed()
    }

    @Test
    fun anArchivedFolderThatGetsNewPhotosWhileTheAppIsOpen_reappearsWithTheBadge() {
        val folder = "${fx.runId}_L"
        repeat(2) { fx.seed("Pictures/$folder/", "${fx.runId}_$it.jpg", variant = it) }
        launch()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(folder))
        compose.onNodeWithText(folder).performTouchInput { swipeLeft() }
        compose.waitUntil(5_000) { compose.onAllNodesWithText(folder).fetchSemanticsNodes().isEmpty() }

        // Llega una foto sin salir de la app (p. ej. una captura de pantalla).
        Thread.sleep(1_100) // date_added tiene resolución de segundos
        fx.seed("Pictures/$folder/", "${fx.runId}_new.jpg", variant = 9)

        compose.waitUntil(10_000) { compose.onAllNodes(hasText(folder) and hasText("1 nuevas")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(folder))
        compose.onNode(hasText(folder) and hasText("1 nuevas")).assertIsDisplayed()
    }

    @Test
    fun archivingARowAfterTheListRefreshed_archivesWhatTheRowShowed() {
        val folder = "${fx.runId}_R"
        repeat(2) { fx.seed("Pictures/$folder/", "${fx.runId}_$it.jpg", variant = it) }
        launch()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(folder))
        compose.onNode(hasText(folder) and hasText("2")).assertIsDisplayed()

        // La lista se actualiza con la fila en pantalla: ahora muestra 3.
        Thread.sleep(1_100)
        fx.seed("Pictures/$folder/", "${fx.runId}_2.jpg", variant = 2)
        checkNotNull(scenario).moveToState(Lifecycle.State.CREATED)
        checkNotNull(scenario).moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(folder) and hasText("3")).fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithText(folder).performTouchInput { swipeLeft() }
        compose.waitUntil(5_000) { archivedNamed(folder).isNotEmpty() }
        compose.waitForIdle()

        assertTrue("las 3 que se veían quedan archivadas; ninguna aparece como nueva", compose.onAllNodesWithText(folder).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun share_sendsExactlyThePhotoOnScreen_withoutRecordingAnything() {
        val folder = "${fx.runId}_S"
        repeat(2) { fx.seed("Pictures/$folder/", "${fx.runId}_$it.jpg", variant = it) }
        val order = deckOrder(folder)
        var chooser: Intent? = null
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_CHOOSER) return null
                chooser = intent
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            openFolder(folder)
            waitPastDoubleTapTimeout()
            compose.onNodeWithText("Compartir").performClick()
            compose.waitUntil(5_000) { chooser != null }
        } finally {
            instrumentation.removeMonitor(monitor)
        }

        val send = checkNotNull(IntentCompat.getParcelableExtra(checkNotNull(chooser), Intent.EXTRA_INTENT, Intent::class.java))
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals(MediaOps.uriOf(order[0], MediaKind.IMAGE), IntentCompat.getParcelableExtra(send, Intent.EXTRA_STREAM, Uri::class.java))
        assertTrue("el receptor puede leer la foto", send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue("compartir no registra decisiones", staged().isEmpty())
    }

    @Test
    fun sendingToTheTrash_asksTwice_andCancellingTheSecondQuestionChangesNothing() {
        val folder = "${fx.runId}_T"
        val seeds = (0 until 2).map { fx.seed("Pictures/$folder/", "${fx.runId}_$it.jpg", variant = it) }
        val order = deckOrder(folder)
        val chosen = seeds.single { it.key == order[0] }

        openFolder(folder)
        waitPastDoubleTapTimeout()
        compose.onNodeWithTag("tap-left").performClick()
        compose.waitUntil(5_000) { staged().size == 1 }

        compose.onNodeWithText("Confirmar (1)").performClick()
        compose.onNodeWithText("¿Aplicar cambios?").assertIsDisplayed()
        compose.onNodeWithText("Continuar").performClick()
        compose.onNodeWithText("¿Mandar 1 a la papelera?").assertIsDisplayed()
        compose.onNodeWithText("Cancelar").performClick()
        compose.waitForIdle()
        Thread.sleep(1_500)

        seeds.forEach { s -> assertFalse("nada se aplica sin la segunda confirmación", checkNotNull(fx.row(s.key)).isTrashed) }
        assertEquals("la decisión sigue guardada", 1, staged().size)

        // Un doble toque: el segundo cae donde estaba "Continuar" y no debe aceptar la pregunta final.
        compose.onNodeWithText("Confirmar (1)").performClick()
        compose.onNodeWithText("Continuar").performClick()
        compose.onNodeWithText("Sí, a la papelera").performClick()
        Thread.sleep(1_500)
        assertFalse("un doble toque no confirma", checkNotNull(fx.row(chosen.key)).isTrashed)
        assertEquals(1, staged().size)

        // Leída la pregunta, sí se aplica.
        waitUntilConfirmationIsReadable()
        compose.onNodeWithText("Sí, a la papelera").performClick()
        compose.waitUntil(20_000) { checkNotNull(fx.row(chosen.key)).isTrashed }
        assertTrue("solo se aplicó la elegida", seeds.filter { it.key != chosen.key }.none { checkNotNull(fx.row(it.key)).isTrashed })
    }

    @Test
    fun deletingForever_asksTwice_ignoresADoubleTap_andOnlyDeletesTheSelected() {
        val folder = "${fx.runId}_P"
        val (doomed, spared) = (0 until 2).map { fx.seed("Pictures/$folder/", "${fx.runId}_$it.jpg", variant = it) }
        listOf(doomed, spared).forEach { fx.trash(it.key, MediaKind.IMAGE) }
        launch()
        compose.onNodeWithText("Papelera").performClick()
        // Lo recién mandado a la papelera vence último: queda al final de la grilla.
        compose.waitUntil(10_000) { compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription(doomed.displayName))
        compose.onNodeWithContentDescription(doomed.displayName).performClick()

        // Cancelar en la pregunta final no borra nada.
        compose.onNodeWithText("Borrar para siempre (1)").performClick()
        compose.onNodeWithText("Continuar").performClick()
        compose.onNodeWithText("Última confirmación").assertIsDisplayed()
        compose.onNodeWithText("Cancelar").performClick()
        // Un doble toque sobre "Continuar" tampoco.
        compose.onNodeWithText("Borrar para siempre (1)").performClick()
        compose.onNodeWithText("Continuar").performClick()
        compose.onNodeWithText("Borrar definitivamente").performClick()
        Thread.sleep(1_500)
        listOf(doomed, spared).forEach { assertTrue("${it.displayName} sigue en la papelera", checkNotNull(fx.row(it.key)).isTrashed) }

        // Con la pregunta leída, se borra solo la elegida.
        waitUntilConfirmationIsReadable()
        compose.onNodeWithText("Borrar definitivamente").performClick()
        compose.waitUntil(20_000) { fx.row(doomed.key) == null }
        val kept = checkNotNull(fx.row(spared.key)) { "la no elegida no debía borrarse" }
        assertTrue("sigue en la papelera", kept.isTrashed)
        assertEquals("con sus bytes", spared.sha256, fx.sha256(kept.path))
    }

    @Test
    fun swiping_decidesLikeTapping_andAShortSwipeDecidesNothing() {
        val folder = "${fx.runId}_W"
        repeat(3) { fx.seed("Pictures/$folder/", "${fx.runId}_$it.jpg", variant = it) }
        val order = deckOrder(folder)
        openFolder(folder)

        waitPastDoubleTapTimeout()
        compose.onNodeWithTag("card").performTouchInput { swipeLeft(startX = centerX, endX = centerX - width * 0.1f) }
        compose.waitForIdle()
        Thread.sleep(500)
        assertTrue("un deslizamiento corto no decide", staged().isEmpty())

        compose.onNodeWithTag("card").performTouchInput { swipeLeft() }
        compose.waitUntil(5_000) { staged().size == 1 }
        assertEquals(Action.TRASH to order[0], lastStaged())

        waitPastDoubleTapTimeout()
        compose.onNodeWithTag("card").performTouchInput { swipeRight() }
        compose.waitUntil(5_000) { staged().size == 2 }
        assertEquals(Action.KEEP to order[1], lastStaged())
    }
}
