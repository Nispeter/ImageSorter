package com.imagesorter.data

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import android.provider.MediaStore.MediaColumns
import com.imagesorter.data.db.Decision
import com.imagesorter.data.db.DecisionDao
import com.imagesorter.domain.Action
import com.imagesorter.domain.CommitPlanner
import com.imagesorter.domain.CommitPlanner.SkipReason
import com.imagesorter.domain.Folders
import com.imagesorter.domain.MediaItem
import com.imagesorter.domain.MediaKey
import com.imagesorter.domain.Verifier
import com.imagesorter.domain.Verifier.Outcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ÚNICA clase que modifica fotos. Cada operación:
 *  1. pide aprobación al sistema (sin diálogo si la app tiene "Gestión de multimedia"),
 *  2. vuelve a leer cada foto y solo da por hecho lo verificado: el sistema devuelve OK aunque
 *     algún ítem falle.
 * Si el usuario cancela, no se sigue con más lotes y lo no verificado queda como estaba.
 * Las fotos de volúmenes desconectados (tarjeta, USB) no se tocan ni se dan por inexistentes.
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

    /**
     * Ejecuta las decisiones registradas con seq <= [upToSeq] (las que el usuario vio en el resumen).
     * Solo por acción explícita del usuario ("Confirmar").
     */
    suspend fun commitStaged(upToSeq: Long = Long.MAX_VALUE): OpResult {
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
                dao.delete(d.volume, d.mediaId) // vuelve al mazo para revisarla de nuevo
                failures += Failure(d.displayName, skipText(s.reason))
            }
        }

        for (batch in CommitPlanner.batches(plan.trash)) {
            val approved = approve(MediaStore.createTrashRequest(resolver, batch.map { uriOf(it.key()) }, true))
            done += settle(batch, approved, failures, emptyMap()) { _, now -> Verifier.trashed(now) }
            if (!approved) return OpResult(done, failures, cancelled = true)
        }

        for ((target, decisions) in listOf(Folders.FAVORITOS to plan.favoritos, Folders.LIKED to plan.liked)) {
            for (batch in CommitPlanner.batches(decisions)) {
                val approved = approve(MediaStore.createWriteRequest(resolver, batch.map { uriOf(it.key()) }))
                val errors = HashMap<MediaKey, String>()
                if (approved) {
                    // El permiso de escritura vive con la Activity: mover inmediatamente.
                    io {
                        val values = ContentValues().apply { put(MediaColumns.RELATIVE_PATH, target) }
                        for (d in batch) {
                            try {
                                resolver.update(uriOf(d.key()), values, null, null)
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

        for (d in plan.keep) dao.markDone(d.volume, d.mediaId)
        return OpResult(done + plan.keep.size, failures, cancelled = false)
    }

    /** Saca fotos de la papelera. Vuelven a su carpeta (el nombre puede recibir " (1)"). */
    suspend fun restore(items: List<MediaItem>): OpResult {
        val failures = mutableListOf<Failure>()
        var done = 0
        for (batch in CommitPlanner.batches(onAttachedVolumes(items, failures, { it.volume }, { it.displayName }))) {
            val approved = approve(MediaStore.createTrashRequest(resolver, batch.map { uriOf(it.key) }, false))
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
     * Justo antes vuelve a comprobar que cada foto sigue en la papelera; las demás no se tocan.
     */
    suspend fun deleteForever(items: List<MediaItem>): OpResult {
        val failures = mutableListOf<Failure>()
        val available = onAttachedVolumes(items, failures, { it.volume }, { it.displayName })
        val before = io { queries.snapshot(available.map { it.key }) }
        val eligible = available.filter { item ->
            val inTrash = before[item.key]?.isTrashed == true
            if (!inTrash) failures += Failure(item.displayName, "ya no está en la papelera")
            inTrash
        }
        var done = 0
        for (batch in CommitPlanner.batches(eligible)) {
            val approved = approve(MediaStore.createDeleteRequest(resolver, batch.map { uriOf(it.key) }))
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

    private fun skipText(reason: SkipReason) = when (reason) {
        SkipReason.MISSING -> "ya no existe"
        SkipReason.ALREADY_TRASHED -> "está en la papelera"
        SkipReason.CHANGED -> "cambió desde que la revisaste"
        SkipReason.ALREADY_IN_TARGET -> "ya estaba en la carpeta"
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        fun uriOf(key: MediaKey): Uri = MediaStore.Images.Media.getContentUri(key.volume, key.mediaId)
    }
}
