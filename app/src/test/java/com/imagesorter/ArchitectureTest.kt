package com.imagesorter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Invariantes de seguridad comprobados sobre el código fuente (I1, I2). */
class ArchitectureTest {
    private val sources: Map<String, String> = File("src/main/java").walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .associate { it.invariantSeparatorsPath to it.readText() }

    private fun filesMatching(regex: Regex) = sources.filter { regex.containsMatchIn(it.value) }.keys

    private fun named(vararg names: String) = sources.keys.filter { path -> names.any { path.endsWith("/$it") } }.toSet()

    @Test
    fun mediaOpsExistsAndUsesTheMutatingApis() {
        val ops = sources.entries.single { it.key.endsWith("/data/MediaOps.kt") }.value
        listOf("createTrashRequest", "createDeleteRequest", "createWriteRequest").forEach {
            assertTrue("MediaOps debe usar $it", ops.contains(it))
        }
    }

    @Test
    fun onlyMediaOpsModifiesMediaStore() {
        val mutating = Regex("""createTrashRequest|createDeleteRequest|createWriteRequest|[Rr]esolver\s*\.\s*update\s*\(""")
        assertEquals(named("MediaOps.kt"), filesMatching(mutating))
    }

    @Test
    fun nothingDeletesOrWritesFilesDirectly() {
        val forbidden = Regex(
            """[Rr]esolver\s*\.\s*(delete|insert|bulkInsert|applyBatch|openOutputStream)\s*\(|java\.io\.File\b|"_data"|MediaColumns\.DATA\b|openFileDescriptor\s*\([^)]*"[rw]*w""",
        )
        assertEquals(emptySet<String>(), filesMatching(forbidden))
    }

    @Test
    fun deleteForeverIsOnlyReachableFromTrashScreen() {
        val calls = filesMatching(Regex("""\bdeleteForever\s*\("""))
        assertTrue("fuera de lo permitido: $calls", named("MediaOps.kt", "TrashScreen.kt").containsAll(calls))
    }
}
