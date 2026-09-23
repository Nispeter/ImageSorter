package com.imagesorter.data

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.MediaStore.MediaColumns
import androidx.annotation.ChecksSdkIntAtLeast
import com.imagesorter.data.db.Decision
import com.imagesorter.data.db.DecisionDao
import com.imagesorter.domain.Action
import com.imagesorter.domain.CommitPlanner
import com.imagesorter.domain.CommitPlanner.Move
import com.imagesorter.domain.CommitPlanner.SkipReason
import com.imagesorter.domain.Folders
import com.imagesorter.domain.MediaItem
import com.imagesorter.domain.MediaKey
import com.imagesorter.domain.MediaKind
import com.imagesorter.domain.Verifier
import com.imagesorter.domain.Verifier.Outcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * ÚNICA clase que modifica fotos y videos. Cada operación:
 *  1. pide aprobación al sistema (sin diálogo si la app tiene "Gestión de multimedia"),
 *  2. vuelve a leer cada archivo y solo da por hecho lo verificado: el sistema devuelve OK aunque
 *     algún ítem falle.
 * Si el usuario cancela, no se sigue con más lotes y lo no verificado queda como estaba.
 * Lo de volúmenes desconectados (tarjeta, USB) no se toca ni se da por inexistente.
 * Cada lote contiene un solo tipo (fotos o videos).
 *
 * Android 10 no tiene papelera ni solicitudes por lotes ([hasSystemTrash]): ahí la app, con acceso "legacy",
 * BORRA para siempre y mueve sin diálogo. La UI avisa antes; la verificación posterior es la misma.
 */
class MediaOps(
    private val resolver: ContentResolver,
    private val queries: MediaQueries,
    private val dao: DecisionDao,
    private val attachedVolumes: () -> Set<String>,
    private val approve: suspend (PendingIntent) -> Boolean,
) {
    data class Failure(val displayName: String, val reason: String)

    data class OpResult(val done: Int, val failures: List<Failure>, val cancelled: Boolean)

    private val running = Mutex()

    /**
     * Una sola operación a la vez: dos en paralelo (doble toque, pantalla recreada) podrían duplicar
     * copias o decidir con datos ya viejos.
     */
    private suspend fun exclusive(what: String, block: suspend () -> OpResult): OpResult {
        if (!running.tryLock()) {
            return OpResult(0, listOf(Failure(what, "ya hay cambios aplicándose; espera a que terminen")), cancelled = false)
        }
        return try {
            block()
        } finally {
            running.unlock()
        }
    }

    /**
     * Ejecuta las decisiones registradas con seq <= [upToSeq] (las que el usuario vio en el resumen).
     * Solo por acción explícita del usuario ("Confirmar").
     */
    suspend fun commitStaged(upToSeq: Long = Long.MAX_VALUE): OpResult =
        exclusive("Confirmar") { commitStagedNow(upToSeq) }

    private suspend fun commitStagedNow(upToSeq: Long): OpResult {
        val failures = mutableListOf<Failure>()
        val staged = onAttachedVolumes(dao.staged().filter { it.seq <= upToSeq }, failures, { it.volume }, { it.displayName })
        if (staged.isEmpty()) return OpResult(0, failures, cancelled = false)
        val current = io { queries.snapshot(staged.map { it.key() }) }
        val plan = CommitPlanner.plan(staged, current)
        var done = 0

        for (s in plan.skipped) {
            val d = s.decision
            val alreadyDone = s.reason == SkipReason.ALREADY_IN_TARGET ||
                (s.reason == SkipReason.ALREADY_TRASHED && d.action == Action.TRASH)
            if (alreadyDone) {
                dao.markDone(d.volume, d.mediaId)
                done++
            } else {
                dao.delete(d.volume, d.mediaId) // vuelve al mazo para revisarlo de nuevo
                failures += Failure(d.displayName, skipText(d, s.reason))
            }
        }

        for (batch in batchesByKind(plan.trash) { it.kind }) {
            val approved = discard(batch.map { uriOf(it.key(), it.kind) })
            done += settle(batch, approved, failures, emptyMap()) { _, now ->
                if (hasSystemTrash) Verifier.trashed(now) else Verifier.deleted(now)
            }
            if (!approved) return OpResult(done, failures, cancelled = true)
        }

        // Android 10 no admite videos dentro de Pictures/: ni se intenta, y el video vuelve al mazo.
        val (moves, videosOnAndroid10) = plan.moves.partition { hasSystemTrash || it.decision.kind != MediaKind.VIDEO }
        for (m in videosOnAndroid10) {
            dao.delete(m.decision.volume, m.decision.mediaId)
            failures += Failure(m.decision.displayName, "Android 10 no permite guardar videos en ${m.target}; no se tocó")
        }
        val (toCopy, toMove) = moves.partition { it.viaCopy }

        for ((target, moves) in toMove.groupBy { it.target }) {
            for (batch in batchesByKind(moves.map { it.decision }) { it.kind }) {
                val approved = !hasSystemTrash ||
                    approve(MediaStore.createWriteRequest(resolver, batch.map { uriOf(it.key(), it.kind) }))
                val errors = HashMap<MediaKey, String>()
                if (approved) {
                    // El permiso de escritura vive con la Activity: mover inmediatamente.
                    io {
                        val values = ContentValues().apply { put(MediaColumns.RELATIVE_PATH, target) }
                        for (d in batch) {
                            try {
                                resolver.update(uriOf(d.key(), d.kind), values, null, null)
                            } catch (e: Exception) {
                                errors[d.key()] = e.message ?: e.javaClass.simpleName
                            }
                        }
                    }
                }
                done += settle(batch, approved, failures, errors) { d, now -> Verifier.moved(d, target, now) }
                if (!approved) return OpResult(done, failures, cancelled = true)
            }
        }

        if (toCopy.isNotEmpty()) {
            val (copied, cancelled) = copyThenTrashOriginal(toCopy, failures)
            done += copied
            if (cancelled) return OpResult(done, failures, cancelled = true)
        }

        for (d in plan.keep) dao.markDone(d.volume, d.mediaId)
        return OpResult(done + plan.keep.size, failures, cancelled = false)
    }

    /**
     * Para lo que vive en carpetas de otras apps (WhatsApp, Telegram…), que Android no deja mover:
     * copia el archivo al destino, comprueba que la copia es idéntica (SHA-256) y recién entonces manda
     * el original a la papelera.
     *
     * Regla: solo se borra una copia creada en esta misma llamada, y solo cuando es seguro que su original
     * sigue intacto (aún no se pidió mandarlo a la papelera, o el sistema ya respondió y se comprobó que
     * sigue ahí). Ante la duda la copia se conserva: un duplicado se puede borrar, un archivo perdido no.
     */
    private suspend fun copyThenTrashOriginal(moves: List<Move>, failures: MutableList<Failure>): Pair<Int, Boolean> {
        val targets = moves.associate { it.decision.key() to it.target }
        val decisions = moves.associate { it.decision.key() to it.decision }
        val copies = LinkedHashMap<MediaKey, Uri>() // creadas en esta llamada y aún sin liquidar
        val requested = HashSet<MediaKey>() // originales cuya papelera ya se pidió al sistema
        var done = 0
        try {
            for (move in moves) {
                currentCoroutineContext().ensureActive()
                val d = move.decision
                try {
                    // Se anota dentro del bloque no cancelable: una copia terminada nunca queda sin registrar.
                    val taken = copies.values.toSet()
                    withContext(NonCancellable + Dispatchers.IO) { copies[d.key()] = copyPending(d, move.target, taken) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    dao.delete(d.volume, d.mediaId)
                    failures += Failure(d.displayName, "no se pudo copiar: ${e.message ?: e.javaClass.simpleName}")
                }
            }

            for (candidates in batchesByKind(copies.keys.map { decisions.getValue(it) }) { it.kind }) {
                // Se publican justo antes de pedir la papelera: si la app muere antes, las copias siguen pendientes
                // (invisibles, y el sistema las borra solas). Lo que no quedó bien se deshace sin riesgo.
                val batch = withContext(NonCancellable + Dispatchers.IO) { publishCopies(candidates, copies, targets, failures) }
                if (batch.isEmpty()) continue
                // Copiar videos tarda: el original pudo cambiar mientras tanto. Se comprueba justo antes de
                // pedir la papelera.
                val batchCopies = batch.associate { it.key() to MediaKey(it.volume, ContentUris.parseId(copies.getValue(it.key()))) }
                val before = io { queries.snapshot(batch.map { it.key() } + batchCopies.values) }
                val fresh = batch.filter { d ->
                    val original = before[d.key()]
                    when {
                        original == null || original.isTrashed -> {
                            // Otra app lo borró o lo mandó a la papelera: la copia verificada puede ser lo único que
                            // queda, así que se conserva siempre y no se pide nada para el original.
                            copies.remove(d.key())
                            if (Verifier.moved(d, targets.getValue(d.key()), before[batchCopies.getValue(d.key())]) == Outcome.Ok) {
                                dao.markDone(d.volume, d.mediaId)
                                done++
                                val where = if (original == null) "ya no estaba" else "ya estaba en la papelera"
                                failures += Failure(d.displayName, "el original $where; se conservó la copia")
                            } else {
                                dao.delete(d.volume, d.mediaId)
                                failures += Failure(d.displayName, "la copia no quedó en su sitio; revisa la Papelera")
                            }
                            false
                        }
                        CommitPlanner.changed(d, original) -> {
                            // Sigue ahí pero ya no es lo que el usuario vio: no se toca, y su copia sobra.
                            copies.remove(d.key())?.let { copy ->
                                withContext(NonCancellable + Dispatchers.IO) { runCatching { resolver.delete(copy, null, null) } }
                            }
                            dao.delete(d.volume, d.mediaId)
                            failures += Failure(d.displayName, "cambió mientras se copiaba; no se tocó el original")
                            false
                        }
                        else -> true
                    }
                }
                if (fresh.isEmpty()) continue
                // Antes de lanzar la solicitud: el diálogo puede aprobarse aunque después algo falle aquí.
                fresh.forEach { requested += it.key() }
                val approved = discard(fresh.map { uriOf(it.key(), it.kind) })
                val copyKeys = fresh.associate { it.key() to MediaKey(it.volume, ContentUris.parseId(copies.getValue(it.key()))) }
                val now = io { queries.snapshot(fresh.map { it.key() } + copyKeys.values) }
                for (d in fresh) {
                    val original = now[d.key()]
                    val copyUri = copies.getValue(d.key())
                    if (original == null || original.isTrashed) {
                        // El original ya no está fuera de la papelera: la copia se conserva siempre.
                        copies.remove(d.key())
                        if (Verifier.moved(d, targets.getValue(d.key()), now[copyKeys.getValue(d.key())]) == Outcome.Ok) {
                            dao.markDone(d.volume, d.mediaId)
                            done++
                            // En Android 10 que el original ya no esté es justamente el borrado pedido.
                            if (original == null && hasSystemTrash) {
                                failures += Failure(d.displayName, "el original ya no estaba; se conservó la copia")
                            }
                        } else {
                            dao.delete(d.volume, d.mediaId)
                            failures += Failure(d.displayName, "la copia no quedó en su sitio; revisa la Papelera")
                        }
                    } else {
                        // El sistema ya respondió y el original sigue intacto: la copia es nuestra y sobra.
                        copies.remove(d.key())
                        withContext(NonCancellable + Dispatchers.IO) { runCatching { resolver.delete(copyUri, null, null) } }
                        if (approved) {
                            dao.delete(d.volume, d.mediaId)
                            val reason = if (hasSystemTrash) "no se movió a la papelera" else "no se pudo borrar el original"
                            failures += Failure(d.displayName, reason)
                        }
                    }
                }
                if (!approved) return done to true
            }
        } finally {
            // Copias de originales que nunca se pidió mandar a la papelera. Se borran solo si el original sigue
            // ahí y fuera de la papelera: otra app pudo borrarlo mientras tanto, y entonces la copia es lo único
            // que queda y se publica (una copia pendiente la borra el sistema). Si no se puede comprobar, también.
            val unrequested = copies.filterKeys { it !in requested }
            if (unrequested.isNotEmpty()) {
                withContext(NonCancellable + Dispatchers.IO) {
                    val originals = runCatching { queries.snapshot(unrequested.keys.toList()) }.getOrNull()
                    for ((key, copy) in unrequested) {
                        val original = originals?.get(key)
                        if (original != null && !original.isTrashed) {
                            runCatching { resolver.delete(copy, null, null) }
                        } else {
                            runCatching { resolver.update(copy, ContentValues().apply { put(MediaColumns.IS_PENDING, 0) }, null, null) }
                        }
                    }
                }
            }
        }
        return done to false
    }

    /**
     * Copia [d] a [target] y la devuelve TODAVÍA PENDIENTE (invisible, y el sistema la borra sola si la app
     * muere) solo si sus bytes son idénticos a los del original. Nunca reutiliza archivos existentes.
     */
    private fun copyPending(d: Decision, target: String, taken: Set<Uri>): Uri {
        // setRequireOriginal: se copian también los datos de ubicación. Sin ese permiso falla y no se toca nada.
        val source = MediaStore.setRequireOriginal(uriOf(d.key(), d.kind))
        val values = ContentValues().apply {
            put(MediaColumns.DISPLAY_NAME, d.displayName)
            put(MediaColumns.RELATIVE_PATH, target)
            put(MediaColumns.IS_PENDING, 1)
        }
        val copy = resolver.insert(MediaQueries.collection(d.kind, d.volume), values)
            ?: throw IllegalStateException("MediaStore no creó el archivo de destino")
        // Si devolviera una fila que ya es de otra copia, se aborta SIN borrarla: no es nuestra.
        check(copy !in taken) { "MediaStore devolvió un destino que ya estaba en uso" }
        try {
            val sourceHash = resolver.openInputStream(source)?.use { input ->
                resolver.openFileDescriptor(copy, "w")?.use { descriptor ->
                    FileOutputStream(descriptor.fileDescriptor).use { output ->
                        input.copyHashing(output).also { descriptor.fileDescriptor.sync() } // en disco antes de seguir
                    }
                } ?: throw IllegalStateException("no se pudo escribir la copia")
            } ?: throw IllegalStateException("no se pudo leer el original")

            val copyHash = resolver.openInputStream(copy)?.use { it.sha256() }
                ?: throw IllegalStateException("no se pudo releer la copia")
            check(sourceHash == copyHash) { "la copia no coincide con el original" }
            return copy
        } catch (e: Exception) {
            runCatching { resolver.delete(copy, null, null) } // no dejar copias a medias
            throw e
        }
    }

    /**
     * Publica las copias del lote y devuelve solo las decisiones cuya copia quedó visible, en su carpeta y
     * del tamaño correcto. Las demás se deshacen: para esos originales todavía no se pidió nada.
     */
    private suspend fun publishCopies(
        batch: List<Decision>,
        copies: MutableMap<MediaKey, Uri>,
        targets: Map<MediaKey, String>,
        failures: MutableList<Failure>,
    ): List<Decision> {
        val published = mutableListOf<Decision>()
        for (d in batch) {
            val copy = copies.getValue(d.key())
            val ok = runCatching {
                resolver.update(copy, ContentValues().apply { put(MediaColumns.IS_PENDING, 0) }, null, null)
                val copyKey = MediaKey(d.volume, ContentUris.parseId(copy))
                val row = queries.snapshot(listOf(copyKey))[copyKey]
                row != null && !row.isPending && Verifier.moved(d, targets.getValue(d.key()), row) == Outcome.Ok
            }.getOrDefault(false)
            if (ok) {
                published += d
            } else {
                copies.remove(d.key())
                runCatching { resolver.delete(copy, null, null) }
                dao.delete(d.volume, d.mediaId)
                failures += Failure(d.displayName, "la copia no quedó bien; el original no se tocó")
            }
        }
        return published
    }

    /** Saca fotos y videos de la papelera. Vuelven a su carpeta (el nombre puede recibir " (1)"). */
    suspend fun restore(items: List<MediaItem>): OpResult = exclusive("Restaurar") { restoreNow(items) }

    private suspend fun restoreNow(items: List<MediaItem>): OpResult {
        if (!hasSystemTrash) return OpResult(0, emptyList(), cancelled = false)
        val failures = mutableListOf<Failure>()
        var done = 0
        val available = onAttachedVolumes(items, failures, { it.volume }, { it.displayName })
        for (batch in batchesByKind(available) { it.kind }) {
            val approved = approve(MediaStore.createTrashRequest(resolver, batch.map { uriOf(it) }, false))
            val now = io { queries.snapshot(batch.map { it.key }) }
            for (item in batch) {
                when (val outcome = Verifier.restored(now[item.key])) {
                    Outcome.Ok -> {
                        dao.delete(item.volume, item.id) // puede volver a revisarse
                        done++
                    }
                    is Outcome.Failed -> if (approved) failures += Failure(item.displayName, outcome.reason)
                }
            }
            if (!approved) return OpResult(done, failures, cancelled = true)
        }
        return OpResult(done, failures, cancelled = false)
    }

    /**
     * BORRADO DEFINITIVO. Solo desde la pantalla Papelera, tras confirmación del usuario.
     * Justo antes vuelve a comprobar que cada archivo sigue en la papelera; los demás no se tocan.
     */
    suspend fun deleteForever(items: List<MediaItem>): OpResult =
        exclusive("Borrar para siempre") { deleteForeverNow(items) }

    private suspend fun deleteForeverNow(items: List<MediaItem>): OpResult {
        if (!hasSystemTrash) return OpResult(0, emptyList(), cancelled = false)
        val failures = mutableListOf<Failure>()
        val available = onAttachedVolumes(items, failures, { it.volume }, { it.displayName })
        var done = 0
        for (candidates in batchesByKind(available) { it.kind }) {
            // Se comprueba lote a lote, no una sola vez: entre un lote y el siguiente algo pudo restaurarse.
            val before = io { queries.snapshot(candidates.map { it.key }) }
            val batch = candidates.filter { item ->
                val inTrash = before[item.key]?.isTrashed == true
                if (!inTrash) failures += Failure(item.displayName, "ya no está en la papelera")
                inTrash
            }
            if (batch.isEmpty()) continue
            val approved = approve(MediaStore.createDeleteRequest(resolver, batch.map { uriOf(it) }))
            val now = io { queries.snapshot(batch.map { it.key }) }
            for (item in batch) {
                when (val outcome = Verifier.deleted(now[item.key])) {
                    Outcome.Ok -> {
                        dao.delete(item.volume, item.id)
                        done++
                    }
                    is Outcome.Failed -> if (approved) failures += Failure(item.displayName, outcome.reason)
                }
            }
            if (!approved) return OpResult(done, failures, cancelled = true)
        }
        return OpResult(done, failures, cancelled = false)
    }

    /**
     * Manda [uris] a la papelera del sistema y devuelve si el usuario aprobó. En Android 10 no hay papelera: se
     * borran directamente (cuenta como aprobado). Lo que no se pudo borrar lo detecta la verificación posterior.
     */
    private suspend fun discard(uris: List<Uri>): Boolean {
        if (hasSystemTrash) return approve(MediaStore.createTrashRequest(resolver, uris, true))
        io { for (uri in uris) runCatching { resolver.delete(uri, null, null) } }
        return true
    }

    /**
     * Actualiza cada decisión del lote según el estado REAL. Verificada → hecha. Fallida tras
     * aprobación → se informa y vuelve al mazo. Fallida por cancelación → sigue registrada.
     */
    private suspend fun settle(
        batch: List<Decision>,
        approved: Boolean,
        failures: MutableList<Failure>,
        errors: Map<MediaKey, String>,
        check: (Decision, MediaItem?) -> Outcome,
    ): Int {
        val now = io { queries.snapshot(batch.map { it.key() }) }
        var ok = 0
        for (d in batch) {
            when (val outcome = check(d, now[d.key()])) {
                Outcome.Ok -> {
                    dao.markDone(d.volume, d.mediaId)
                    ok++
                }
                is Outcome.Failed -> if (approved) {
                    dao.delete(d.volume, d.mediaId)
                    failures += Failure(d.displayName, errors[d.key()] ?: outcome.reason)
                }
            }
        }
        return ok
    }

    /** Deja pasar solo lo que está en volúmenes conectados; lo demás se informa y no se toca. */
    private fun <T> onAttachedVolumes(
        items: List<T>,
        failures: MutableList<Failure>,
        volume: (T) -> String,
        name: (T) -> String,
    ): List<T> {
        val attached = attachedVolumes()
        return items.filter { item ->
            val available = volume(item) in attached
            if (!available) failures += Failure(name(item), "su tarjeta o USB no está conectado; queda pendiente")
            available
        }
    }

    private fun <T> batchesByKind(items: List<T>, kind: (T) -> MediaKind): List<List<T>> =
        items.groupBy(kind).values.flatMap { CommitPlanner.batches(it) }

    private fun skipText(decision: Decision, reason: SkipReason) = when (reason) {
        SkipReason.MISSING -> "ya no existe"
        SkipReason.ALREADY_TRASHED -> Folders.targetFor(decision.action)
            ?.let { "está en la papelera; si venía de otra app, revisa $it: la copia puede estar ahí" }
            ?: "está en la papelera"
        SkipReason.CHANGED -> "cambió desde que lo revisaste"
        SkipReason.ALREADY_IN_TARGET -> "ya estaba en la carpeta"
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        /** Android 11+: papelera del sistema y solicitudes por lotes (papelera, escritura, borrado). */
        @get:ChecksSdkIntAtLeast(api = 30)
        val hasSystemTrash: Boolean get() = Build.VERSION.SDK_INT >= 30

        // Igual que Media.getContentUri(volumen, id), que no existe en Android 10.
        fun uriOf(key: MediaKey, kind: MediaKind): Uri =
            ContentUris.withAppendedId(MediaQueries.collection(kind, key.volume), key.mediaId)

        fun uriOf(item: MediaItem): Uri = uriOf(item.key, item.kind)

        private fun InputStream.copyHashing(out: OutputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
                out.write(buffer, 0, read)
            }
            out.flush()
            return digest.hex()
        }

        private fun InputStream.sha256(): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
            return digest.hex()
        }

        private fun MessageDigest.hex() = digest().joinToString("") { "%02x".format(it) }
    }
}
