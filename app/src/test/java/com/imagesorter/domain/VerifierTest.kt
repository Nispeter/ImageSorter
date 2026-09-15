package com.imagesorter.domain

import com.imagesorter.data.db.Decision
import com.imagesorter.domain.Verifier.Outcome
import com.imagesorter.item
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifierTest {
    private val day = 24L * 60 * 60 * 1000

    @Test
    fun trashed_requiresIsTrashedFlag() {
        assertEquals(Outcome.Ok, Verifier.trashed(item(1).copy(isTrashed = true)))
        // RESULT_OK con la fila sin cambios = fallo.
        assertTrue(Verifier.trashed(item(1)) is Outcome.Failed)
        assertTrue(Verifier.trashed(null) is Outcome.Failed)
    }

    @Test
    fun restored_requiresNotTrashed() {
        assertEquals(Outcome.Ok, Verifier.restored(item(1)))
        assertTrue(Verifier.restored(item(1).copy(isTrashed = true)) is Outcome.Failed)
        assertTrue(Verifier.restored(null) is Outcome.Failed)
    }

    @Test
    fun moved_acceptsRenamedCollision() {
        val d = Decision.staged(item(1, name = "x.jpg"), Action.FAVORITOS, 1)
        val now = item(1, name = "x (1).jpg", path = Folders.FAVORITOS)
        assertEquals(Outcome.Ok, Verifier.moved(d, Folders.FAVORITOS, now))
    }

    @Test
    fun moved_rejectsUnchangedTrashedOrResized() {
        val d = Decision.staged(item(1), Action.LIKED, 1)
        assertTrue(Verifier.moved(d, Folders.LIKED, item(1)) is Outcome.Failed)
        assertTrue(Verifier.moved(d, Folders.LIKED, item(1, path = Folders.LIKED).copy(isTrashed = true)) is Outcome.Failed)
        assertTrue(Verifier.moved(d, Folders.LIKED, item(1, path = Folders.LIKED).copy(size = 1)) is Outcome.Failed)
        assertTrue(Verifier.moved(d, Folders.LIKED, null) is Outcome.Failed)
    }

    @Test
    fun deleted_requiresRowGone() {
        assertEquals(Outcome.Ok, Verifier.deleted(null))
        assertTrue(Verifier.deleted(item(1).copy(isTrashed = true)) is Outcome.Failed)
    }

    @Test
    fun daysLeft_usesSecondsAndRoundsUp() {
        val nowMs = 1_700_000_000_000L
        fun expiresIn(ms: Long) = (nowMs + ms) / 1000
        assertEquals(30, Verifier.daysLeft(expiresIn(30 * day), nowMs))
        assertEquals(30, Verifier.daysLeft(expiresIn(29 * day + 1000), nowMs))
        assertEquals(1, Verifier.daysLeft(expiresIn(1000), nowMs))
        assertEquals(0, Verifier.daysLeft(expiresIn(0), nowMs))
        assertEquals(0, Verifier.daysLeft(expiresIn(-5 * day), nowMs))
    }
}
