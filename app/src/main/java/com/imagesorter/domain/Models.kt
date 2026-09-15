package com.imagesorter.domain

enum class Action { TRASH, FAVORITOS, LIKED, KEEP }

enum class Status { STAGED, DONE }

data class MediaKey(val volume: String, val mediaId: Long)

/** Fila de MediaStore.Images tal como se leyó en un instante. */
data class MediaItem(
    val volume: String,
    val id: Long,
    val displayName: String,
    val relativePath: String,
    val size: Long,
    val dateModified: Long,
    val isTrashed: Boolean = false,
    val dateExpires: Long? = null,
) {
    val key: MediaKey get() = MediaKey(volume, id)
}

object Folders {
    const val FAVORITOS = "Pictures/Favoritos/"
    const val LIKED = "Pictures/Liked/"

    fun targetFor(action: Action): String? = when (action) {
        Action.FAVORITOS -> FAVORITOS
        Action.LIKED -> LIKED
        Action.TRASH, Action.KEEP -> null
    }
}
