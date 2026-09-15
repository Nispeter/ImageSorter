package com.imagesorter.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import com.imagesorter.domain.Action
import com.imagesorter.domain.MediaItem
import com.imagesorter.domain.MediaKey
import com.imagesorter.domain.MediaKind
import com.imagesorter.domain.Status

/**
 * Decisión del usuario sobre una foto o video. Guarda un snapshot al momento de decidir para
 * detectar, antes de ejecutar, si el archivo cambió o desapareció.
 */
@Entity(tableName = "decisions", primaryKeys = ["volume", "mediaId"])
data class Decision(
    val volume: String,
    val mediaId: Long,
    val action: Action,
    val status: Status,
    val seq: Long,
    val displayName: String,
    val relativePath: String,
    val size: Long,
    val dateModified: Long,
    @ColumnInfo(defaultValue = "IMAGE")
    val kind: MediaKind = MediaKind.IMAGE,
) {
    fun key() = MediaKey(volume, mediaId)

    fun toItem() = MediaItem(volume, mediaId, displayName, relativePath, size, dateModified, kind = kind)

    companion object {
        fun staged(item: MediaItem, action: Action, seq: Long) = Decision(
            volume = item.volume,
            mediaId = item.id,
            action = action,
            status = Status.STAGED,
            seq = seq,
            displayName = item.displayName,
            relativePath = item.relativePath,
            size = item.size,
            dateModified = item.dateModified,
            kind = item.kind,
        )
    }
}
