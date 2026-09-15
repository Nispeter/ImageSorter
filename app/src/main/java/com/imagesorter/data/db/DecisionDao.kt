package com.imagesorter.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.imagesorter.domain.MediaKey
import kotlinx.coroutines.flow.Flow

@Dao
interface DecisionDao {
    /** ABORT: una sola decisión por foto (volumen, id). */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(decision: Decision)

    @Query("SELECT * FROM decisions WHERE status = 'STAGED' ORDER BY seq DESC LIMIT 1")
    suspend fun latestStaged(): Decision?

    @Query("SELECT * FROM decisions WHERE status = 'STAGED' ORDER BY seq")
    suspend fun staged(): List<Decision>

    @Query("SELECT COUNT(*) FROM decisions WHERE status = 'STAGED'")
    fun observeStagedCount(): Flow<Int>

    @Query("SELECT volume, mediaId FROM decisions")
    suspend fun decidedKeys(): List<MediaKey>

    @Query("SELECT COALESCE(MAX(seq), 0) FROM decisions")
    suspend fun maxSeq(): Long

    @Query("DELETE FROM decisions WHERE volume = :volume AND mediaId = :mediaId")
    suspend fun delete(volume: String, mediaId: Long)

    @Query("UPDATE decisions SET status = 'DONE' WHERE volume = :volume AND mediaId = :mediaId AND status = 'STAGED'")
    suspend fun markDone(volume: String, mediaId: Long)

    /** "Reiniciar revisadas": las fotos ya ejecutadas vuelven a poder aparecer en los mazos. */
    @Query("DELETE FROM decisions WHERE status = 'DONE'")
    suspend fun resetReviewed()
}
