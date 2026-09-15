package com.imagesorter.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Upsert
import com.imagesorter.domain.FolderKey
import kotlinx.coroutines.flow.Flow

/**
 * Carpeta archivada: se oculta todo lo que MediaStore indexó hasta [archivedUpTo] (segundos, date_added).
 * No toca archivos. Si llegan fotos o videos nuevos, la carpeta reaparece solo con esos.
 */
@Entity(tableName = "archived_folders", primaryKeys = ["volume", "bucketId"])
data class ArchivedFolder(
    val volume: String,
    val bucketId: String,
    val archivedUpTo: Long,
    val name: String,
    val relativePath: String,
) {
    fun key() = FolderKey(volume, bucketId)
}

@Dao
interface ArchiveDao {
    @Upsert
    suspend fun archive(folder: ArchivedFolder)

    @Query("DELETE FROM archived_folders WHERE volume = :volume AND bucketId = :bucketId")
    suspend fun unarchive(volume: String, bucketId: String)

    @Query("SELECT * FROM archived_folders")
    fun observeAll(): Flow<List<ArchivedFolder>>
}
