package com.imagesorter.data

import android.content.ContentResolver
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.MediaStore.Images
import android.provider.MediaStore.MediaColumns
import android.provider.MediaStore.Video
import com.imagesorter.domain.FolderEntry
import com.imagesorter.domain.MediaItem
import com.imagesorter.domain.MediaKey
import com.imagesorter.domain.MediaKind

/**
 * Consultas de SOLO LECTURA a MediaStore, sobre fotos y videos.
 * Nunca filtra por owner_package_name (Android 14+ lo recorta a propias).
 * Android 10 no tiene papelera: ahí nada figura como "en la papelera" (ver [MediaOps.hasSystemTrash]).
 */
class MediaQueries(private val resolver: ContentResolver) {

    /** Un registro por foto o video (sin Favoritos/Liked) para armar el selector de carpetas. */
    fun folderEntries(): List<FolderEntry> {
        val sel = SelectionBuilder.forDeck(null)
        val out = ArrayList<FolderEntry>()
        for (kind in MediaKind.entries) {
            resolver.query(collection(kind, MediaStore.VOLUME_EXTERNAL), FOLDER_PROJECTION, sel.clause, sel.args.toTypedArray(), null)
                ?.use { c ->
                    while (c.moveToNext()) {
                        val bucketId = c.getString(0) ?: continue
                        out += FolderEntry(
                            volume = c.getString(3) ?: "",
                            bucketId = bucketId,
                            name = c.getString(1) ?: "(sin nombre)",
                            relativePath = c.getString(2) ?: "",
                            dateAdded = c.getLong(4),
                        )
                    }
                }
        }
        return out
    }

    /** Fotos y videos del mazo, más recientes primero. MediaStore excluye por defecto papelera y pendientes. */
    fun deck(bucketIds: Collection<String>?): List<MediaItem> {
        val sel = SelectionBuilder.forDeck(bucketIds)
        return MediaKind.entries.flatMap { kind ->
            queryItems(collection(kind, MediaStore.VOLUME_EXTERNAL), kind, Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, sel.clause)
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, sel.args.toTypedArray())
            })
        }.sortedWith(compareByDescending<MediaItem> { it.sortTime }.thenByDescending { it.id })
    }

    /** Toda la papelera de fotos y videos del sistema (de cualquier app); primero lo que vence antes. */
    fun trash(): List<MediaItem> = if (!MediaOps.hasSystemTrash) emptyList() else MediaKind.entries.flatMap { kind ->
        queryItems(collection(kind, MediaStore.VOLUME_EXTERNAL), kind, Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
        })
    }.sortedBy { it.dateExpires ?: Long.MAX_VALUE }

    /**
     * Estado actual (papelera incluida) de cada clave. Lo que ya no existe no está en el mapa.
     * Si MediaStore no responde lanza una excepción: una respuesta vacía nunca debe leerse como "no existe".
     */
    fun snapshot(keys: Collection<MediaKey>): Map<MediaKey, MediaItem> {
        val result = HashMap<MediaKey, MediaItem>()
        keys.groupBy { it.volume }.forEach { (volume, volumeKeys) ->
            volumeKeys.chunked(500).forEach { chunk ->
                val args = Bundle().apply {
                    putString(
                        ContentResolver.QUERY_ARG_SQL_SELECTION,
                        "${MediaColumns._ID} IN (${chunk.joinToString(",") { "?" }})",
                    )
                    putStringArray(
                        ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                        chunk.map { it.mediaId.toString() }.toTypedArray(),
                    )
                    // Constante copiada al compilar: Android 10 simplemente la ignora (no hay papelera).
                    putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
                }
                for (kind in MediaKind.entries) {
                    queryItems(collection(kind, volume), kind, args, requireCursor = true).forEach { result[it.key] = it }
                }
            }
        }
        return result
    }

    private fun queryItems(uri: Uri, kind: MediaKind, args: Bundle, requireCursor: Boolean = false): List<MediaItem> {
        val cursor = resolver.query(uri, PROJECTION, args, null)
            ?: if (requireCursor) throw IllegalStateException("MediaStore no respondió. Vuelve a intentarlo.") else return emptyList()
        val out = ArrayList<MediaItem>()
        cursor.use { c ->
            val id = c.getColumnIndexOrThrow(MediaColumns._ID)
            val volume = c.getColumnIndexOrThrow(MediaColumns.VOLUME_NAME)
            val name = c.getColumnIndexOrThrow(MediaColumns.DISPLAY_NAME)
            val path = c.getColumnIndexOrThrow(MediaColumns.RELATIVE_PATH)
            val size = c.getColumnIndexOrThrow(MediaColumns.SIZE)
            val modified = c.getColumnIndexOrThrow(MediaColumns.DATE_MODIFIED)
            val trashed = if (MediaOps.hasSystemTrash) c.getColumnIndexOrThrow(MediaColumns.IS_TRASHED) else -1
            val pending = c.getColumnIndexOrThrow(MediaColumns.IS_PENDING)
            val expires = c.getColumnIndexOrThrow(MediaColumns.DATE_EXPIRES)
            val bucket = c.getColumnIndexOrThrow(MediaColumns.BUCKET_ID)
            val added = c.getColumnIndexOrThrow(MediaColumns.DATE_ADDED)
            val taken = c.getColumnIndexOrThrow(MediaColumns.DATE_TAKEN)
            while (c.moveToNext()) {
                out += MediaItem(
                    volume = c.getString(volume),
                    id = c.getLong(id),
                    displayName = c.getString(name) ?: "",
                    relativePath = c.getString(path) ?: "",
                    size = c.getLong(size),
                    dateModified = c.getLong(modified),
                    isTrashed = trashed >= 0 && c.getInt(trashed) == 1,
                    isPending = c.getInt(pending) == 1,
                    dateExpires = if (c.isNull(expires)) null else c.getLong(expires),
                    kind = kind,
                    bucketId = c.getString(bucket) ?: "",
                    dateAdded = c.getLong(added),
                    dateTaken = if (c.isNull(taken)) null else c.getLong(taken),
                )
            }
        }
        return out
    }

    companion object {
        fun collection(kind: MediaKind, volume: String): Uri = when (kind) {
            MediaKind.IMAGE -> Images.Media.getContentUri(volume)
            MediaKind.VIDEO -> Video.Media.getContentUri(volume)
        }

        private val FOLDER_PROJECTION = arrayOf(
            MediaColumns.BUCKET_ID,
            MediaColumns.BUCKET_DISPLAY_NAME,
            MediaColumns.RELATIVE_PATH,
            MediaColumns.VOLUME_NAME,
            MediaColumns.DATE_ADDED,
        )

        // IS_TRASHED no existe en Android 10: pedirla hace fallar la consulta.
        private val PROJECTION = listOfNotNull(
            MediaColumns._ID,
            MediaColumns.VOLUME_NAME,
            MediaColumns.DISPLAY_NAME,
            MediaColumns.RELATIVE_PATH,
            MediaColumns.SIZE,
            MediaColumns.DATE_MODIFIED,
            if (Build.VERSION.SDK_INT >= 30) MediaColumns.IS_TRASHED else null,
            MediaColumns.IS_PENDING,
            MediaColumns.DATE_EXPIRES,
            MediaColumns.BUCKET_ID,
            MediaColumns.DATE_ADDED,
            MediaColumns.DATE_TAKEN,
        ).toTypedArray()
    }
}
