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
        // MediaOps también inserta/borra/escribe: es la copia verificada para carpetas de otras apps.
        // Abrir un descriptor se prohíbe en cualquier modo: "w" trunca la foto del usuario.
        val mutating = Regex(
            """createTrashRequest|createDeleteRequest|createWriteRequest|openOutputStream\s*\(|open(Asset|TypedAsset)?FileDescriptor\s*\(|""" +
                """[Rr]esolver(\(\))?\s*(\?\.|\.)\s*(update|insert|delete|bulkInsert|applyBatch|call)\s*\(""",
        )
        assertEquals(named("MediaOps.kt"), filesMatching(mutating))
    }

    /**
     * Fuera de MediaOps el resolver solo puede leer. Es una lista blanca: cualquier llamada nueva
     * (update, insert, delete, applyBatch, call…) hace fallar este test aunque el nombre sea otro.
     */
    @Test
    fun outsideMediaOps_theResolverOnlyReads() {
        val readOnly = setOf("query", "getType", "openInputStream", "registerContentObserver", "unregisterContentObserver")
        // Solo llamadas (minúscula): ContentResolver.QUERY_ARG_* son constantes, no tocan nada.
        val used = Regex("""\w*[Rr]esolver(?:\(\))?\s*(?:\?\.|\.)\s*([a-z]\w*)""")
        val offenders = sources
            .filterKeys { it !in named("MediaOps.kt") }
            .mapValues { (_, text) -> used.findAll(text).map { it.groupValues[1] }.filterNot { it in readOnly }.toList() }
            .filterValues { it.isNotEmpty() }
        assertEquals(emptyMap<String, List<String>>(), offenders)
        // Formas que esconden el receptor: with(resolver) { delete(...) }, resolver::delete, resolver.run { ... }.
        val hidden = Regex("""with\s*\([^)]*[Rr]esolver|[Rr]esolver(\(\))?\s*::|[Rr]esolver(\(\))?\s*(\?\.|\.)\s*(run|let|apply|also)\b""")
        assertEquals(emptySet<String>(), filesMatching(hidden) - named("MediaOps.kt"))
    }

    /**
     * Cualquier archivo nuevo que use el resolver (aunque sea con otro nombre de variable) hace fallar
     * este test: hay que revisarlo y agregarlo aquí a propósito.
     */
    @Test
    fun theResolverAppearsOnlyInReviewedFiles() {
        val reviewed = named("MediaOps.kt", "MediaQueries.kt", "DeckScreen.kt", "FolderPickerScreen.kt", "MainActivity.kt")
        assertEquals(emptySet<String>(), filesMatching(Regex("""[Cc]ontentResolver""")) - reviewed)
    }

    @Test
    fun nothingTouchesFilesBehindMediaStore() {
        val forbidden = Regex(
            """[Rr]esolver\s*\.\s*(bulkInsert|applyBatch)\s*\(|java\.io\.File\b|"_data"|MediaColumns\.DATA\b""",
        )
        assertEquals(emptySet<String>(), filesMatching(forbidden))
    }

    @Test
    fun deleteForeverIsOnlyReachableFromTrashScreen() {
        val calls = filesMatching(Regex("""\bdeleteForever\s*\("""))
        assertTrue("fuera de lo permitido: $calls", named("MediaOps.kt", "TrashScreen.kt").containsAll(calls))
    }
}
