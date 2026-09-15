package com.imagesorter.domain

import com.imagesorter.data.db.Decision

/**
 * Comprueba el estado REAL tras cada request. MediaStore puede devolver RESULT_OK aunque un ítem
 * haya fallado, así que ninguna operación se da por hecha sin pasar por aquí.
 */
object Verifier {
    sealed interface Outcome {
        data object Ok : Outcome
        data class Failed(val reason: String) : Outcome
    }

    private const val DAY_MS = 24L * 60 * 60 * 1000

    fun trashed(now: MediaItem?): Outcome = when {
        now == null -> Outcome.Failed("ya no existe")
        !now.isTrashed -> Outcome.Failed("no se movió a la papelera")
        else -> Outcome.Ok
    }

    fun restored(now: MediaItem?): Outcome = when {
        now == null -> Outcome.Failed("ya no existe")
        now.isTrashed -> Outcome.Failed("sigue en la papelera")
        else -> Outcome.Ok
    }

    /** El nombre puede cambiar ("x (1).jpg") si ya existía uno igual en la carpeta destino. */
    fun moved(expected: Decision, targetPath: String, now: MediaItem?): Outcome = when {
        now == null -> Outcome.Failed("ya no existe")
        now.isTrashed -> Outcome.Failed("está en la papelera")
        !now.relativePath.equals(targetPath, ignoreCase = true) -> Outcome.Failed("no se movió")
        now.size != expected.size -> Outcome.Failed("el tamaño cambió")
        else -> Outcome.Ok
    }

    fun deleted(now: MediaItem?): Outcome =
        if (now == null) Outcome.Ok else Outcome.Failed("sigue existiendo")

    /** Días restantes (redondeo hacia arriba) hasta DATE_EXPIRES, que MediaStore da en segundos. */
    fun daysLeft(dateExpiresSec: Long, nowMs: Long): Int {
        val remaining = dateExpiresSec * 1000 - nowMs
        if (remaining <= 0) return 0
        return ((remaining + DAY_MS - 1) / DAY_MS).toInt()
    }
}
