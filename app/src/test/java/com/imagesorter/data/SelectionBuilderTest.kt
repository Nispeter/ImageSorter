package com.imagesorter.data

import com.imagesorter.domain.Folders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SelectionBuilderTest {
    private fun placeholders(clause: String) = clause.count { it == '?' }

    @Test
    fun allPhotos_hasNoBucketFilter_andExcludesSortedFolders() {
        val s = SelectionBuilder.forDeck(null)
        assertFalse(s.clause.contains("bucket_id"))
        assertEquals("relative_path NOT LIKE ? AND relative_path NOT LIKE ?", s.clause)
        assertEquals(listOf("Pictures/Favoritos/%", "Pictures/Liked/%"), s.args)
    }

    @Test
    fun selectedFolders_filterExactlyThoseBuckets() {
        val s = SelectionBuilder.forDeck(listOf("111", "222", "333"))
        assertEquals(
            "relative_path NOT LIKE ? AND relative_path NOT LIKE ? AND bucket_id IN (?,?,?)",
            s.clause,
        )
        assertEquals(listOf("${Folders.FAVORITOS}%", "${Folders.LIKED}%", "111", "222", "333"), s.args)
        assertEquals(placeholders(s.clause), s.args.size)
    }

    @Test
    fun singleFolder_placeholdersMatchArgs() {
        val s = SelectionBuilder.forDeck(setOf("42"))
        assertEquals(placeholders(s.clause), s.args.size)
        assertEquals("42", s.args.last())
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyFolderSelection_isRejected() {
        SelectionBuilder.forDeck(emptyList())
    }

    @Test
    fun sortedFolderPatterns_haveNoLikeWildcards() {
        // LIKE trata '%' y '_' como comodines: los patrones deben ser literales.
        listOf(Folders.FAVORITOS, Folders.LIKED).forEach {
            assertFalse(it.contains('%') || it.contains('_'))
        }
    }
}
