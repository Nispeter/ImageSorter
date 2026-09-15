package com.imagesorter.domain

import com.imagesorter.FakeDecisionDao
import com.imagesorter.data.db.Decision
import com.imagesorter.item
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ReviewSessionTest {
    private val dao = FakeDecisionDao()
    private var attached = setOf("external_primary")
    private val remote = mutableMapOf<MediaKey, MediaItem>()
    private var lookupFails = false
    private val session = ReviewSession(dao, { attached }) { key ->
        if (lookupFails) error("volumen no disponible")
        remote[key] ?: item(key.mediaId)
    }

    private fun deckIds() = session.deck.value.map { it.id }

    private fun key(id: Long) = MediaKey("external_primary", id)

    private suspend fun decideFront(action: Action) = session.decide(action, session.deck.value.first().key)

    @Test
    fun load_excludesStagedAndDoneDecisions() = runTest {
        dao.insert(Decision.staged(item(2), Action.TRASH, 1))
        dao.insert(Decision.staged(item(3), Action.KEEP, 2))
        dao.markDone("external_primary", 3)

        session.load(listOf(item(1), item(2), item(3), item(4)))

        assertEquals(listOf(1L, 4L), deckIds())
    }

    @Test
    fun decide_persistsDecisionWithSnapshot_thenAdvances() = runTest {
        session.load(listOf(item(1), item(2)))

        assertTrue(session.decide(Action.TRASH, key(1)))

        val saved = dao.rows.values.single()
        assertEquals(1L, saved.mediaId)
        assertEquals(Action.TRASH, saved.action)
        assertEquals(Status.STAGED, saved.status)
        assertEquals(item(1).displayName, saved.displayName)
        assertEquals(item(1).size, saved.size)
        assertEquals(listOf(2L), deckIds())
    }

    @Test
    fun decide_onEmptyDeck_writesNothing() = runTest {
        session.load(emptyList())
        assertFalse(session.decide(Action.TRASH, key(1)))
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun decide_forAPhotoThatIsNotInFront_writesNothing() = runTest {
        session.load(listOf(item(1), item(2)))
        assertFalse(session.decide(Action.TRASH, key(2)))
        assertTrue(dao.rows.isEmpty())
        assertEquals(listOf(1L, 2L), deckIds())
    }

    @Test
    fun doubleTapOnTheSameCard_stagesOnlyThatPhoto() = runTest {
        session.load(listOf(item(1), item(2), item(3)))

        val results = listOf(
            async { session.decide(Action.TRASH, key(1)) },
            async { session.decide(Action.TRASH, key(1)) },
        ).awaitAll()

        assertEquals(1, results.count { it })
        assertEquals(setOf(1L), dao.rows.values.map { it.mediaId }.toSet())
        assertEquals(listOf(2L, 3L), deckIds())
    }

    @Test
    fun decide_whenPersistFails_doesNotAdvance() = runTest {
        session.load(listOf(item(1), item(2)))
        dao.failInserts = true
        try {
            session.decide(Action.TRASH, key(1))
            fail("debía fallar")
        } catch (_: IllegalStateException) {
        }
        assertEquals(listOf(1L, 2L), deckIds())
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun undo_isLifo_andRestoresCardsInOrder() = runTest {
        session.load(listOf(item(1), item(2), item(3), item(4)))
        decideFront(Action.TRASH)
        decideFront(Action.KEEP)
        decideFront(Action.LIKED)

        assertEquals(3L, session.undo()!!.decision.mediaId)
        assertEquals(listOf(3L, 4L), deckIds())
        assertEquals(2L, session.undo()!!.decision.mediaId)
        assertEquals(listOf(2L, 3L, 4L), deckIds())
        assertEquals(setOf(1L), dao.rows.values.map { it.mediaId }.toSet())
    }

    @Test
    fun undo_withNothingStaged_returnsNull() = runTest {
        session.load(listOf(item(1)))
        assertNull(session.undo())
        assertEquals(listOf(1L), deckIds())
    }

    @Test
    fun undo_neverTouchesExecutedDecisions() = runTest {
        session.load(listOf(item(1), item(2)))
        decideFront(Action.TRASH) // 1: registrada
        decideFront(Action.TRASH) // 2: registrada, luego ejecutada
        dao.markDone("external_primary", 2)

        assertEquals(1L, session.undo()!!.decision.mediaId)
        assertNull(session.undo())
        assertEquals(Status.DONE, dao.rows.values.single().status)
    }

    @Test
    fun undo_thenDecideAgain_replacesDecision() = runTest {
        session.load(listOf(item(1)))
        session.decide(Action.TRASH, key(1))
        session.undo()
        session.decide(Action.FAVORITOS, key(1))

        assertEquals(Action.FAVORITOS, dao.rows.values.single().action)
    }

    @Test
    fun undo_ofATrashAlreadyApplied_marksDoneInsteadOfReturningToDeck() = runTest {
        session.load(listOf(item(1), item(2)))
        decideFront(Action.TRASH)
        remote[key(1)] = item(1).copy(isTrashed = true) // una confirmación interrumpida ya la mandó a la papelera

        val undone = session.undo()!!

        assertFalse(undone.backInDeck)
        assertEquals(Status.DONE, dao.rows.getValue(key(1)).status)
        assertEquals(listOf(2L), deckIds())
    }

    @Test
    fun undo_ofAMoveAlreadyApplied_marksDoneInsteadOfReturningToDeck() = runTest {
        session.load(listOf(item(1), item(2)))
        decideFront(Action.FAVORITOS)
        remote[key(1)] = item(1, name = "IMG_1 (1).jpg", path = Folders.FAVORITOS)

        val undone = session.undo()!!

        assertFalse(undone.backInDeck)
        assertEquals(Status.DONE, dao.rows.getValue(key(1)).status)
        assertEquals(listOf(2L), deckIds())
    }

    @Test
    fun undo_onADisconnectedVolume_undoesNormallyWithoutQuerying() = runTest {
        session.load(listOf(item(1), item(2)))
        decideFront(Action.TRASH)
        attached = emptySet()
        lookupFails = true

        val undone = session.undo()!!

        assertTrue(undone.backInDeck)
        assertTrue(dao.rows.isEmpty())
        assertEquals(listOf(1L, 2L), deckIds())
    }
}
