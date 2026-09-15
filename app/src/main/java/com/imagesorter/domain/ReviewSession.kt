package com.imagesorter.domain

import com.imagesorter.data.db.Decision
import com.imagesorter.data.db.DecisionDao
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Mazo de revisión. Solo registra decisiones: nunca toca archivos.
 * @param lookup estado actual de una foto en MediaStore (null si ya no existe).
 */
class ReviewSession(
    private val dao: DecisionDao,
    private val lookup: suspend (MediaKey) -> MediaItem?,
) {
    data class Undone(val decision: Decision, val backInDeck: Boolean)

    private val mutex = Mutex()
    private val _deck = MutableStateFlow<List<MediaItem>>(emptyList())
    val deck: StateFlow<List<MediaItem>> = _deck

    /** Carga el mazo sin las fotos que ya tienen decisión (registrada o ejecutada). */
    suspend fun load(items: List<MediaItem>) = mutex.withLock {
        val decided = dao.decidedKeys().toHashSet()
        _deck.value = items.filterNot { it.key in decided }
    }

    /**
     * Registra [action] para [expected], la foto que el usuario tiene en pantalla. Si esa foto ya no
     * está al frente (doble toque, deshacer a mitad de un gesto) no hace nada y devuelve false.
     * Persiste ANTES de avanzar, y guardar + avanzar no se interrumpen a medias.
     */
    suspend fun decide(action: Action, expected: MediaKey): Boolean = withContext(NonCancellable) {
        mutex.withLock {
            val item = _deck.value.firstOrNull()
            if (item == null || item.key != expected) return@withLock false
            dao.insert(Decision.staged(item, action, seq = dao.maxSeq() + 1))
            _deck.value = _deck.value.drop(1)
            true
        }
    }

    /**
     * Deshace la última decisión aún no ejecutada y devuelve la foto al frente del mazo. Excepción: si
     * era "borrar" y la foto ya está en la papelera (una confirmación anterior se interrumpió tras
     * aplicarse), se marca como hecha para que siga visible en Papelera en vez de quedar oculta.
     */
    suspend fun undo(): Undone? = withContext(NonCancellable) {
        mutex.withLock {
            val last = dao.latestStaged() ?: return@withLock null
            if (last.action == Action.TRASH && lookup(last.key())?.isTrashed == true) {
                dao.markDone(last.volume, last.mediaId)
                return@withLock Undone(last, backInDeck = false)
            }
            dao.delete(last.volume, last.mediaId)
            val item = last.toItem()
            _deck.value = listOf(item) + _deck.value.filterNot { it.key == item.key }
            Undone(last, backInDeck = true)
        }
    }
}
