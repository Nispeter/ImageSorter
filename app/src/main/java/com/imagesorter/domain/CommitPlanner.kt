package com.imagesorter.domain

import com.imagesorter.data.db.Decision

/** Decide qué se ejecuta a partir de las decisiones registradas y el estado ACTUAL de MediaStore. */
object CommitPlanner {
    /** Tamaño máximo de cada request a MediaStore (evita TransactionTooLargeException). */
    const val MAX_BATCH = 200

    enum class SkipReason { MISSING, ALREADY_TRASHED, CHANGED, ALREADY_IN_TARGET }

    data class Skipped(val decision: Decision, val reason: SkipReason)

    /**
     * Un movimiento a Favoritos/Liked. [viaCopy] es para lo que está en carpetas de otras apps: Android
     * no deja moverlo, así que se copia al destino y el original va a la papelera.
     */
    data class Move(val decision: Decision, val target: String, val viaCopy: Boolean)

    data class Plan(
        val trash: List<Decision>,
        val moves: List<Move>,
        val keep: List<Decision>,
        val skipped: List<Skipped>,
    )

    /**
     * @param current estado actual por clave; ausente = el archivo ya no existe.
     * Lo que cambió (nombre, ruta, tamaño o fecha) desde que se decidió se omite: nunca se ejecuta una
     * acción sobre algo distinto a lo que el usuario vio.
     */
    fun plan(staged: List<Decision>, current: Map<MediaKey, MediaItem>): Plan {
        val trash = mutableListOf<Decision>()
        val moves = mutableListOf<Move>()
        val keep = mutableListOf<Decision>()
        val skipped = mutableListOf<Skipped>()

        for (d in staged) {
            require(d.status == Status.STAGED) { "Solo se planifican decisiones registradas" }
            if (d.action == Action.KEEP) {
                // Conservar no toca archivos, pero no se da por hecho si está en la papelera:
                // quedaría oculto del mazo mientras el sistema lo borra.
                if (current[d.key()]?.isTrashed == true) skipped += Skipped(d, SkipReason.ALREADY_TRASHED) else keep += d
                continue
            }
            val now = current[d.key()]
            val target = Folders.targetFor(d.action)
            val reason = when {
                now == null -> SkipReason.MISSING
                now.isTrashed -> SkipReason.ALREADY_TRASHED
                // Antes que CHANGED: un movimiento interrumpido deja el archivo en destino (quizá renombrado).
                target != null && target.equals(now.relativePath, ignoreCase = true) && now.size == d.size ->
                    SkipReason.ALREADY_IN_TARGET
                now.displayName != d.displayName || now.relativePath != d.relativePath ||
                    now.size != d.size || now.dateModified != d.dateModified -> SkipReason.CHANGED
                else -> null
            }
            if (reason != null) {
                skipped += Skipped(d, reason)
                continue
            }
            checkNotNull(now)
            when (d.action) {
                Action.TRASH -> trash += d
                Action.FAVORITOS, Action.LIKED ->
                    moves += Move(d, checkNotNull(target), viaCopy = Folders.isInsideAnotherApp(now.relativePath))
                Action.KEEP -> Unit
            }
        }
        return Plan(trash, moves, keep, skipped)
    }

    /** Lotes para requests; lista vacía → cero lotes (MediaStore.create*Request falla con vacío). */
    fun <T> batches(items: List<T>): List<List<T>> = items.chunked(MAX_BATCH)
}
