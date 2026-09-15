package com.imagesorter

import com.imagesorter.data.db.Decision
import com.imagesorter.data.db.DecisionDao
import com.imagesorter.domain.MediaItem
import com.imagesorter.domain.MediaKey
import com.imagesorter.domain.Status
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.yield

/** DAO en memoria con la misma semántica que Room (clave única, orden por seq). */
class FakeDecisionDao : DecisionDao {
    val rows = linkedMapOf<MediaKey, Decision>()
    var failInserts = false

    override suspend fun insert(decision: Decision) {
        yield() // expone carreras entre llamadas concurrentes
        if (failInserts) error("disk full")
        check(decision.key() !in rows) { "UNIQUE constraint failed" }
        rows[decision.key()] = decision
    }

    override suspend fun latestStaged() = rows.values.filter { it.status == Status.STAGED }.maxByOrNull { it.seq }

    override suspend fun staged() = rows.values.filter { it.status == Status.STAGED }.sortedBy { it.seq }

    override fun observeStagedCount(): Flow<Int> = flowOf(rows.values.count { it.status == Status.STAGED })

    override suspend fun decidedKeys() = rows.keys.toList()

    override suspend fun maxSeq(): Long {
        yield()
        return rows.values.maxOfOrNull { it.seq } ?: 0
    }

    override suspend fun delete(volume: String, mediaId: Long) {
        rows.remove(MediaKey(volume, mediaId))
    }

    override suspend fun markDone(volume: String, mediaId: Long) {
        val key = MediaKey(volume, mediaId)
        rows[key]?.takeIf { it.status == Status.STAGED }?.let { rows[key] = it.copy(status = Status.DONE) }
    }

    override suspend fun resetReviewed() {
        rows.entries.removeAll { it.value.status == Status.DONE }
    }
}

fun item(id: Long, name: String = "IMG_$id.jpg", path: String = "DCIM/Camera/", volume: String = "external_primary") =
    MediaItem(volume = volume, id = id, displayName = name, relativePath = path, size = 1000 + id, dateModified = 1_700_000_000 + id)
