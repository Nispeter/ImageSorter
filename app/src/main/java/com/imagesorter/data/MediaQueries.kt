package com.imagesorter.data

import android.content.ContentResolver
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.MediaStore.Images
import android.provider.MediaStore.MediaColumns
import com.imagesorter.domain.MediaItem
import com.imagesorter.domain.MediaKey

/** Consultas de SOLO LECTURA a MediaStore. Nunca filtra por owner_package_name (Android 14+ lo recorta a propias). */
class MediaQueries(private val resolver: ContentResolver) {
    data class Folder(val bucketId: String, val name: String, val relativePath: String, val volume: String, val count: Int)

    /** Carpetas con fotos (sin Favoritos/Liked), ordenadas por nombre. */
    fun folders(): List<Folder> {
        val sel = SelectionBuilder.forDeck(null)
        val byBucket = linkedMapOf<String, Folder>()
        resolver.query(
            COLLECTION,
            arrayOf(Images.Media.BUCKET_ID, Images.Media.BUCKET_DISPLAY_NAME, MediaColumns.RELATIVE_PATH, MediaColumns.VOLUME_NAME),
            sel.clause,
            sel.args.toTypedArray(),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val prev = byBucket[id]
                byBucket[id] = prev?.copy(count = prev.count + 1)
                    ?: Folder(id, c.getString(1) ?: "(sin nombre)", c.getString(2) ?: "", c.getString(3) ?: "", 1)
            }
        }
        return byBucket.values.sortedWith(compareBy({ it.name.lowercase() }, { it.relativePath }))
    }

    /** Fotos del mazo, más recientes primero. MediaStore excluye por defecto papelera y pendientes. */
    fun deck(bucketIds: Collection<String>?): List<MediaItem> {
        val sel = SelectionBuilder.forDeck(bucketIds)
        return queryItems(COLLECTION, Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, sel.clause)
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, sel.args.toTypedArray())
            // Sin fecha EXIF (WhatsApp, descargas) se usa la fecha del archivo.
            putString(
                ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                "COALESCE(${MediaColumns.DATE_TAKEN}, ${MediaColumns.DATE_MODIFIED} * 1000) DESC, ${MediaColumns._ID} DESC",
            )
        })
    }

    /** Toda la papelera de imágenes del sistema (de cualquier app); primero las que vencen antes. */
    fun trash(): List<MediaItem> = queryItems(COLLECTION, Bundle().apply {
        putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
        putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaColumns.DATE_EXPIRES} ASC")
    })

    /**
     * Estado actual (papelera incluida) de cada clave. Las fotos que ya no existen no están en el mapa.
     * Si MediaStore no responde lanza una excepción: una respuesta vacía nunca debe leerse como "no existe".
     */
    fun snapshot(keys: Collection<MediaKey>): Map<MediaKey, MediaItem> {
        val result = HashMap<MediaKey, MediaItem>()
        keys.groupBy { it.volume }.forEach { (volume, volumeKeys) ->
            volumeKeys.chunked(500).forEach { chunk ->
                queryItems(Images.Media.getContentUri(volume), Bundle().apply {
                    putString(
                        ContentResolver.QUERY_ARG_SQL_SELECTION,
                        "${MediaColumns._ID} IN (${chunk.joinToString(",") { "?" }})",
                    )
                    putStringArray(
                        ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                        chunk.map { it.mediaId.toString() }.toTypedArray(),
                    )
                    putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
                }, requireCursor = true).forEach { result[it.key] = it }
            }
        }
        return result
    }

    private fun queryItems(uri: Uri, args: Bundle, requireCursor: Boolean = false): List<MediaItem> {
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
            val trashed = c.getColumnIndexOrThrow(MediaColumns.IS_TRASHED)
            val expires = c.getColumnIndexOrThrow(MediaColumns.DATE_EXPIRES)
            while (c.moveToNext()) {
                out += MediaItem(
                    volume = c.getString(volume),
                    id = c.getLong(id),
                    displayName = c.getString(name) ?: "",
                    relativePath = c.getString(path) ?: "",
                    size = c.getLong(size),
                    dateModified = c.getLong(modified),
                    isTrashed = c.getInt(trashed) == 1,
                    dateExpires = if (c.isNull(expires)) null else c.getLong(expires),
                )
            }
        }
        return out
    }

    companion object {
        val COLLECTION: Uri = Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

        private val PROJECTION = arrayOf(
            MediaColumns._ID,
            MediaColumns.VOLUME_NAME,
            MediaColumns.DISPLAY_NAME,
            MediaColumns.RELATIVE_PATH,
            MediaColumns.SIZE,
            MediaColumns.DATE_MODIFIED,
            MediaColumns.IS_TRASHED,
            MediaColumns.DATE_EXPIRES,
        )
    }
}
