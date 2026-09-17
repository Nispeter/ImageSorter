package com.imagesorter.domain

import com.imagesorter.data.db.ArchivedFolder

/** Un archivo tal como lo necesita el selector de carpetas. */
data class FolderEntry(
    val volume: String,
    val bucketId: String,
    val name: String,
    val relativePath: String,
    val dateAdded: Long,
)

data class FolderSummary(
    val volume: String,
    val bucketId: String,
    val name: String,
    val relativePath: String,
    /** Por revisar: todo, o si está archivada solo lo llegado después de archivarla. */
    val count: Int,
    val total: Int,
    /** date_added más reciente: archivar "hasta aquí". */
    val latestAdded: Long,
    val archived: Boolean,
) {
    val key: FolderKey get() = FolderKey(volume, bucketId)

    /** Archivada pero con fotos o videos nuevos: se muestra con el aviso de novedades. */
    val hasNew: Boolean get() = archived && count > 0
}

/** Lógica pura de carpetas archivadas. */
object FolderIndex {
    data class Result(val visible: List<FolderSummary>, val archived: List<FolderSummary>)

    fun build(entries: List<FolderEntry>, archives: List<ArchivedFolder>): Result {
        val upTo = archives.associate { it.key() to it.archivedUpTo }
        val summaries = entries.groupBy { FolderKey(it.volume, it.bucketId) }.map { (key, files) ->
            val limit = upTo[key]
            val first = files.first()
            FolderSummary(
                volume = key.volume,
                bucketId = key.bucketId,
                name = first.name,
                relativePath = first.relativePath,
                count = if (limit == null) files.size else files.count { it.dateAdded > limit },
                total = files.size,
                latestAdded = files.maxOf { it.dateAdded },
                archived = limit != null,
            )
        }.sortedWith(compareBy({ it.name.lowercase() }, { it.relativePath }))
        return Result(
            // Las archivadas con novedades van primero, para que el aviso se vea.
            visible = summaries.filter { it.count > 0 }.sortedByDescending { it.hasNew },
            archived = summaries.filter { it.archived && it.count == 0 },
        )
    }

    /** Si el archivo debe aparecer en un mazo: se ocultan los de carpetas archivadas anteriores al archivo. */
    fun isVisible(item: MediaItem, archives: List<ArchivedFolder>): Boolean {
        val limit = archives.firstOrNull { it.volume == item.volume && it.bucketId == item.bucketId }?.archivedUpTo
        return limit == null || item.dateAdded > limit
    }
}
