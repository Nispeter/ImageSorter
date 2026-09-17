package com.imagesorter.domain

import com.imagesorter.data.db.ArchivedFolder
import com.imagesorter.item
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderIndexTest {
    private fun entry(bucket: String, added: Long, volume: String = "external_primary", name: String = bucket) =
        FolderEntry(volume, bucket, name, "DCIM/$name/", added)

    private fun archived(bucket: String, upTo: Long, volume: String = "external_primary") =
        ArchivedFolder(volume, bucket, upTo, bucket, "DCIM/$bucket/")

    @Test
    fun notArchived_countsEverything() {
        val result = FolderIndex.build(listOf(entry("a", 10), entry("a", 20), entry("b", 5)), emptyList())

        assertEquals(listOf("a" to 2, "b" to 1), result.visible.map { it.bucketId to it.count })
        assertTrue(result.archived.isEmpty())
        assertFalse(result.visible.any { it.hasNew })
    }

    @Test
    fun archivedWithoutNewFiles_isHiddenAndListedAsArchived() {
        val result = FolderIndex.build(listOf(entry("a", 10), entry("a", 20)), listOf(archived("a", 20)))

        assertTrue(result.visible.isEmpty())
        assertEquals(listOf("a"), result.archived.map { it.bucketId })
        assertEquals(2, result.archived.single().total)
    }

    @Test
    fun archivedWithNewFiles_reappearsCountingOnlyTheNewOnes() {
        val result = FolderIndex.build(
            listOf(entry("a", 10), entry("a", 20), entry("a", 21), entry("a", 30)),
            listOf(archived("a", 20)),
        )

        val folder = result.visible.single()
        assertEquals(2, folder.count)
        assertEquals(4, folder.total)
        assertTrue(folder.hasNew)
        assertEquals(30, folder.latestAdded)
        assertTrue(result.archived.isEmpty())
    }

    @Test
    fun archiveAppliesOnlyToItsOwnVolume() {
        val result = FolderIndex.build(
            listOf(entry("cam", 10), entry("cam", 10, volume = "1234-abcd")),
            listOf(archived("cam", 10)),
        )

        assertEquals(listOf("1234-abcd"), result.visible.map { it.volume })
        assertEquals(listOf("external_primary"), result.archived.map { it.volume })
    }

    @Test
    fun archiveOfAFolderThatNoLongerExists_isIgnored() {
        val result = FolderIndex.build(listOf(entry("a", 1)), listOf(archived("gone", 99)))

        assertEquals(listOf("a"), result.visible.map { it.bucketId })
        assertTrue(result.archived.isEmpty())
    }

    @Test
    fun foldersAreSortedByNameThenPath() {
        val result = FolderIndex.build(listOf(entry("2", 1, name = "b"), entry("1", 1, name = "A"), entry("3", 1, name = "c")), emptyList())

        assertEquals(listOf("A", "b", "c"), result.visible.map { it.name })
    }

    @Test
    fun foldersWithNewFiles_comeFirst_soTheBadgeIsSeen() {
        val result = FolderIndex.build(
            listOf(entry("a", 1), entry("z", 1), entry("z", 9), entry("m", 1), entry("m", 9)),
            listOf(archived("z", 1), archived("m", 1)),
        )

        assertEquals(listOf("m", "z", "a"), result.visible.map { it.name })
    }

    @Test
    fun isVisible_hidesOnlyFilesIndexedUpToTheArchive() {
        val archives = listOf(archived("a", 20))

        assertFalse(FolderIndex.isVisible(item(1).copy(bucketId = "a", dateAdded = 20), archives))
        assertFalse(FolderIndex.isVisible(item(2).copy(bucketId = "a", dateAdded = 5), archives))
        assertTrue(FolderIndex.isVisible(item(3).copy(bucketId = "a", dateAdded = 21), archives))
        assertTrue(FolderIndex.isVisible(item(4).copy(bucketId = "b", dateAdded = 1), archives))
        assertTrue(FolderIndex.isVisible(item(5, volume = "1234-abcd").copy(bucketId = "a", dateAdded = 1), archives))
    }
}
