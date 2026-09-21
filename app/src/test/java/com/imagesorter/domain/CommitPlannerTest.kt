package com.imagesorter.domain

import com.imagesorter.data.db.Decision
import com.imagesorter.domain.CommitPlanner.SkipReason
import com.imagesorter.item
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommitPlannerTest {
    private fun staged(id: Long, action: Action, seq: Long = id) = Decision.staged(item(id), action, seq)

    private fun current(vararg items: MediaItem) = items.associateBy { it.key }

    @Test
    fun partitionsByAction_intoDisjointSets() {
        val decisions = listOf(
            staged(1, Action.TRASH), staged(2, Action.FAVORITOS), staged(3, Action.LIKED),
            staged(4, Action.KEEP), staged(5, Action.TRASH),
        )
        val plan = CommitPlanner.plan(decisions, current(item(1), item(2), item(3), item(4), item(5)))

        assertEquals(listOf(1L, 5L), plan.trash.map { it.mediaId })
        assertEquals(listOf(2L to Folders.FAVORITOS, 3L to Folders.LIKED), plan.moves.map { it.decision.mediaId to it.target })
        assertTrue("nada en carpetas de otras apps se copia", plan.moves.none { it.viaCopy })
        assertEquals(listOf(4L), plan.keep.map { it.mediaId })
        assertTrue(plan.skipped.isEmpty())
        val all = plan.trash + plan.moves.map { it.decision } + plan.keep
        assertEquals(all.size, all.map { it.key() }.toSet().size)
    }

    @Test
    fun missingPhoto_isSkipped() {
        val plan = CommitPlanner.plan(listOf(staged(1, Action.TRASH)), current())
        assertTrue(plan.trash.isEmpty())
        assertEquals(SkipReason.MISSING, plan.skipped.single().reason)
    }

    @Test
    fun alreadyTrashed_isSkipped() {
        val plan = CommitPlanner.plan(listOf(staged(1, Action.FAVORITOS)), current(item(1).copy(isTrashed = true)))
        assertTrue(plan.moves.isEmpty())
        assertEquals(SkipReason.ALREADY_TRASHED, plan.skipped.single().reason)
    }

    @Test
    fun anyChangeSinceDecision_isSkipped() {
        val variants = listOf(
            item(1).copy(displayName = "otra.jpg"),
            item(1).copy(relativePath = "DCIM/Otra/"),
            item(1).copy(size = 1),
            item(1).copy(dateModified = 1),
        )
        variants.forEach { changed ->
            val plan = CommitPlanner.plan(listOf(staged(1, Action.TRASH)), current(changed))
            assertTrue("no debe ejecutarse: $changed", plan.trash.isEmpty())
            assertEquals(SkipReason.CHANGED, plan.skipped.single().reason)
        }
    }

    @Test
    fun moveIntoFolderItIsAlreadyIn_isSkipped() {
        val inFav = item(1, path = "pictures/favoritos/")
        val decision = Decision.staged(inFav, Action.FAVORITOS, 1)
        val plan = CommitPlanner.plan(listOf(decision), current(inFav))
        assertTrue(plan.moves.isEmpty())
        assertEquals(SkipReason.ALREADY_IN_TARGET, plan.skipped.single().reason)
    }

    @Test
    fun interruptedMove_isRecognizedAsAlreadyInTarget() {
        val decision = staged(1, Action.LIKED)
        val movedAndRenamed = item(1, name = "IMG_1 (1).jpg", path = Folders.LIKED).copy(dateModified = 5)
        val plan = CommitPlanner.plan(listOf(decision), current(movedAndRenamed))
        assertTrue(plan.moves.isEmpty())
        assertEquals(SkipReason.ALREADY_IN_TARGET, plan.skipped.single().reason)
    }

    @Test
    fun keep_needsNoSnapshot() {
        val plan = CommitPlanner.plan(listOf(staged(1, Action.KEEP)), current())
        assertEquals(1, plan.keep.size)
        assertTrue(plan.skipped.isEmpty())
    }

    @Test
    fun movingFilesFromAnotherAppsFolder_goesThroughACopy() {
        val whatsapp = item(1, path = "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video/")
        val decision = Decision.staged(whatsapp, Action.FAVORITOS, 1)

        val plan = CommitPlanner.plan(listOf(decision), current(whatsapp))

        val move = plan.moves.single()
        assertEquals(Folders.FAVORITOS, move.target)
        assertTrue("Android no deja moverlo: hay que copiarlo", move.viaCopy)
        assertTrue(plan.skipped.isEmpty())
    }

    @Test
    fun trashingFilesFromAnotherAppsFolder_isAllowed() {
        val whatsapp = item(1, path = "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video/")
        val plan = CommitPlanner.plan(listOf(Decision.staged(whatsapp, Action.TRASH, 1)), current(whatsapp))

        assertEquals(listOf(1L), plan.trash.map { it.mediaId })
        assertTrue(plan.skipped.isEmpty())
    }

    @Test
    fun keepOfAPhotoInTheTrash_isSkipped() {
        val plan = CommitPlanner.plan(listOf(staged(1, Action.KEEP)), current(item(1).copy(isTrashed = true)))
        assertTrue(plan.keep.isEmpty())
        assertEquals(SkipReason.ALREADY_TRASHED, plan.skipped.single().reason)
    }

    @Test(expected = IllegalArgumentException::class)
    fun executedDecisions_areRejected() {
        CommitPlanner.plan(listOf(staged(1, Action.TRASH).copy(status = Status.DONE)), current(item(1)))
    }

    @Test
    fun emptyPlan_producesNoBatches() {
        val plan = CommitPlanner.plan(emptyList(), emptyMap())
        assertTrue(CommitPlanner.batches(plan.trash).isEmpty())
        assertTrue(CommitPlanner.batches(plan.moves).isEmpty())
    }

    @Test
    fun batches_areChunkedWithoutLosingItems() {
        val items = (1..450).toList()
        val batches = CommitPlanner.batches(items)
        assertEquals(listOf(200, 200, 50), batches.map { it.size })
        assertEquals(items, batches.flatten())
    }

    @Test
    fun changed_detectsAnythingThatIsNotTheFileThatWasDecided() {
        val decision = Decision.staged(item(1), Action.FAVORITOS, 1)

        assertFalse(CommitPlanner.changed(decision, item(1)))
        assertTrue("ya no existe", CommitPlanner.changed(decision, null))
        assertTrue("está en la papelera", CommitPlanner.changed(decision, item(1).copy(isTrashed = true)))
        assertTrue("otro tamaño", CommitPlanner.changed(decision, item(1).copy(size = item(1).size + 1)))
        assertTrue("otra fecha", CommitPlanner.changed(decision, item(1).copy(dateModified = item(1).dateModified + 1)))
        assertTrue("otro nombre", CommitPlanner.changed(decision, item(1).copy(displayName = "otra.jpg")))
        assertTrue("otra carpeta", CommitPlanner.changed(decision, item(1).copy(relativePath = "DCIM/Otra/")))
    }
}
