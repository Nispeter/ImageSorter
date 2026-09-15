package com.imagesorter.data

import com.imagesorter.domain.Folders

/** Construye el WHERE de MediaStore para el mazo. Columnas literales para poder testear en JVM. */
object SelectionBuilder {
    data class Selection(val clause: String, val args: List<String>)

    /**
     * @param bucketIds carpetas elegidas; `null` = todas las fotos. Nunca vacío.
     * Excluye Pictures/Favoritos/ y Pictures/Liked/ con sus subcarpetas (LIKE de SQLite no distingue
     * mayúsculas ASCII).
     */
    fun forDeck(bucketIds: Collection<String>?): Selection {
        require(bucketIds == null || bucketIds.isNotEmpty()) { "Selección de carpetas vacía" }
        val parts = mutableListOf("relative_path NOT LIKE ?", "relative_path NOT LIKE ?")
        val args = mutableListOf("${Folders.FAVORITOS}%", "${Folders.LIKED}%")
        if (bucketIds != null) {
            parts += "bucket_id IN (${bucketIds.joinToString(",") { "?" }})"
            args += bucketIds
        }
        return Selection(parts.joinToString(" AND "), args)
    }
}
