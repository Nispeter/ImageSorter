package com.imagesorter.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.imagesorter.data.MediaOps
import com.imagesorter.data.MediaQueries
import com.imagesorter.data.db.DecisionDao
import com.imagesorter.domain.Action
import com.imagesorter.domain.MediaKey
import com.imagesorter.domain.ReviewSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeckScreen(deck: Screen.Deck, queries: MediaQueries, ops: MediaOps, dao: DecisionDao, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val session = remember(deck) {
        ReviewSession(dao) { key -> withContext(Dispatchers.IO) { queries.snapshot(listOf(key))[key] } }
    }
    val cards by session.deck.collectAsState()
    var loading by remember { mutableStateOf(true) }
    var reload by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<Pair<String, String>?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(deck, reload) {
        loading = true
        session.load(withContext(Dispatchers.IO) { queries.deck(deck.bucketIds) })
        loading = false
    }

    /** Si guardar falla se informa y devuelve false: la tarjeta no avanza. */
    suspend fun guarded(block: suspend () -> Boolean): Boolean = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        message = "Error" to "No se pudo guardar la decisión: ${e.message}"
        false
    }

    /** [key] es la foto que se veía al tocar el botón; si ya no está al frente no se registra nada. */
    fun decide(action: Action, key: MediaKey) {
        scope.launch { guarded { session.decide(action, key) } }
    }

    fun undo() {
        scope.launch {
            guarded {
                val undone = session.undo()
                if (undone != null && !undone.backInDeck) {
                    message = "Aviso" to "\"${undone.decision.displayName}\" ya estaba en la papelera, así que no vuelve " +
                        "al mazo. Puedes restaurarla desde Papelera."
                }
                true
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(deck.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { TextButton(onClick = onBack) { Text("←") } },
                actions = {
                    if (!loading) {
                        Text("${cards.size} restantes", Modifier.padding(end = 16.dp), style = MaterialTheme.typography.labelLarge)
                    }
                },
            )
        },
        bottomBar = {
            Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                // La foto que se está mostrando en este frame: los botones actúan SOLO sobre ella.
                val shown = cards.firstOrNull()?.takeIf { !loading }
                val enabled = shown != null
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    ActionButton("🗑", "Borrar", enabled) { shown?.let { decide(Action.TRASH, it.key) } }
                    ActionButton("↶", "Deshacer") { undo() }
                    ActionButton("⭐", "Favoritos", enabled) { shown?.let { decide(Action.FAVORITOS, it.key) } }
                    ActionButton("❤️", "Liked", enabled) { shown?.let { decide(Action.LIKED, it.key) } }
                    ActionButton("✓", "Conservar", enabled) { shown?.let { decide(Action.KEEP, it.key) } }
                }
                CommitBar(ops, dao, onFinished = { reload++ })
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).padding(16.dp), contentAlignment = Alignment.Center) {
            val current = cards.firstOrNull()
            when {
                loading -> CircularProgressIndicator()
                current == null -> Text("No quedan fotos por revisar aquí.", textAlign = TextAlign.Center)
                else -> key(current.key) {
                    SwipeCard(current, onSwipe = { action -> guarded { session.decide(action, current.key) } })
                }
            }
        }
    }
    message?.let { (title, text) -> ErrorDialog(text, title) { message = null } }
}
