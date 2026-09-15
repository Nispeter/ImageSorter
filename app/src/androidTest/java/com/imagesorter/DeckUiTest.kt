package com.imagesorter

import android.Manifest
import android.view.ViewConfiguration
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.imagesorter.data.MediaQueries
import com.imagesorter.data.db.AppDatabase
import com.imagesorter.domain.Action
import com.imagesorter.domain.FolderIndex
import com.imagesorter.domain.MediaKey
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
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.ACCESS_MEDIA_LOCATION,
    )

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
        scenario?.close()
        db.clearAllTables()
        fx.cleanup()
        fx.setManageMedia(false)
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
    }

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
        assertTrue(archivedNames().isEmpty())
    }
}
