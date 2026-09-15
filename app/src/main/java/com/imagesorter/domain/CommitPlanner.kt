package com.imagesorter.domain

import com.imagesorter.data.db.Decision

/** Decide qué se ejecuta a partir de las decisiones registradas y el estado ACTUAL de MediaStore. */
object CommitPlanner {
    /** Tamaño máximo de cada request a MediaStore (evita TransactionTooLargeException). */
    const val MAX_BATCH = 200

    enum class SkipReason { MISSING, ALREADY_TRASHED, CHANGED, ALREADY_IN_TARGET, NOT_MOVABLE }

    /** Carpetas de otras apps (WhatsApp, Telegram…): Android nunca deja mover lo que hay ahí. */
    private val OWNED_PATH = Regex("(?i)^Android/(data|media|obb)/")

    data class Skipped(val decision: Decision, val reason: SkipReason)

    data class Plan(
        val trash: List<Decision>,
        val favoritos: List<Decision>,
        val liked: List<Decision>,
        val keep: List<Decision>,
        val skipped: List<Skipped>,
    )

    /**
     * @param current estado actual por clave; ausente = la foto ya no existe.
     * Una foto que cambió (nombre, ruta, tamaño o fecha) desde que se decidió se omite: nunca se
     * ejecuta una acción sobre algo distinto a lo que el usuario vio.
     */
    fun plan(staged: List<Decision>, current: Map<MediaKey, MediaItem>): Plan {
        val trash = mutableListOf<Decision>()
        val favoritos = mutableListOf<Decision>()
        val liked = mutableListOf<Decision>()
        val keep = mutableListOf<Decision>()
        val skipped = mutableListOf<Skipped>()

        for (d in staged) {
            require(d.status == Status.STAGED) { "Solo se planifican decisiones registradas" }
            if (d.action == Action.KEEP) {
                // Conservar no toca archivos, pero no se da por hecho si la foto está en la papelera:
                // quedaría oculta del mazo mientras el sistema la borra.
                if (current[d.key()]?.isTrashed == true) skipped += Skipped(d, SkipReason.ALREADY_TRASHED) else keep += d
                continue
            }
            val now = current[d.key()]
            val target = Folders.targetFor(d.action)
            val reason = when {
                now == null -> SkipReason.MISSING
                now.isTrashed -> SkipReason.ALREADY_TRASHED
                // Antes que CHANGED: un movimiento interrumpido deja la foto en destino (quizá renombrada).
                target != null && target.equals(now.relativePath, ignoreCase = true) && now.size == d.size ->
                    SkipReason.ALREADY_IN_TARGET
                target != null && OWNED_PATH.containsMatchIn(now.relativePath) -> SkipReason.NOT_MOVABLE
                now.displayName != d.displayName || now.relativePath != d.relativePath ||
                    now.size != d.size || now.dateModified != d.dateModified -> SkipReason.CHANGED
                else -> null
            }
            if (reason != null) {
                skipped += Skipped(d, reason)
                continue
            }
            when (d.action) {
                Action.TRASH -> trash += d
                Action.FAVORITOS -> favoritos += d
                Action.LIKED -> liked += d
                Action.KEEP -> Unit
            }
        }
        return Plan(trash, favoritos, liked, keep, skipped)
    }

    /** Lotes para requests; lista vacía → cero lotes (MediaStore.create*Request falla con vacío). */
    fun <T> batches(items: List<T>): List<List<T>> = items.chunked(MAX_BATCH)
}
