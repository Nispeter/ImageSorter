package com.imagesorter.domain

enum class Action { TRASH, FAVORITOS, LIKED, KEEP }

enum class Status { STAGED, DONE }

enum class MediaKind { IMAGE, VIDEO }

data class MediaKey(val volume: String, val mediaId: Long)

data class FolderKey(val volume: String, val bucketId: String)

/** Fila de MediaStore (foto o video) tal como se leyó en un instante. */
data class MediaItem(
    val volume: String,
    val id: Long,
    val displayName: String,
    val relativePath: String,
    val size: Long,
    val dateModified: Long,
    val isTrashed: Boolean = false,
    /** A medio crear: solo lo ve quien lo creó y el sistema lo borra a los ~7 días. */
    val isPending: Boolean = false,
    val dateExpires: Long? = null,
    val kind: MediaKind = MediaKind.IMAGE,
    val bucketId: String = "",
    /** Segundos, cuando MediaStore indexó el archivo. */
    val dateAdded: Long = 0,
    /** Milisegundos, fecha de captura (EXIF); null si no hay. */
    val dateTaken: Long? = null,
) {
    val key: MediaKey get() = MediaKey(volume, id)

    val folder: FolderKey get() = FolderKey(volume, bucketId)

    /** "Más recientes primero": fecha de captura o, si no hay, la del archivo. */
    val sortTime: Long get() = dateTaken ?: (dateModified * 1000)
}

object Folders {
    const val FAVORITOS = "Pictures/Favoritos/"
    const val LIKED = "Pictures/Liked/"

    /** Carpetas de otras apps (WhatsApp, Telegram…): Android nunca deja mover lo que hay ahí. */
    private val OTHER_APP_PATH = Regex("(?i)^Android/(data|media|obb)/")

    fun isInsideAnotherApp(relativePath: String) = OTHER_APP_PATH.containsMatchIn(relativePath)

    fun targetFor(action: Action): String? = when (action) {
        Action.FAVORITOS -> FAVORITOS
        Action.LIKED -> LIKED
        Action.TRASH, Action.KEEP -> null
    }
}
