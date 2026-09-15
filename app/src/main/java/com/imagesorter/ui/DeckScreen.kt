package com.imagesorter.ui

import android.os.SystemClock
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Star
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.size.Scale
import coil.size.Size
import com.imagesorter.data.MediaOps
import com.imagesorter.data.MediaQueries
import com.imagesorter.data.db.ArchiveDao
import com.imagesorter.data.db.DecisionDao
import com.imagesorter.domain.Action
import com.imagesorter.domain.FolderIndex
import com.imagesorter.domain.Folders
import com.imagesorter.domain.MediaKey
import com.imagesorter.domain.ReviewSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeckScreen(
    deck: Screen.Deck,
    queries: MediaQueries,
    ops: MediaOps,
    dao: DecisionDao,
    archiveDao: ArchiveDao,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val session = remember(deck) {
        ReviewSession(dao, { MediaStore.getExternalVolumeNames(context) }) { key ->
            withContext(Dispatchers.IO) { queries.snapshot(listOf(key))[key] }
        }
    }
    val cards by session.deck.collectAsState()
    var loading by remember { mutableStateOf(true) }
    var reload by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<Pair<String, String>?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(deck, reload) {
        loading = true
        val archives = archiveDao.observeAll().first()
        val items = withContext(Dispatchers.IO) { queries.deck(deck.bucketIds) }
        // Lo de carpetas archivadas no vuelve a aparecer, salvo lo que llegó después de archivarlas.
        session.load(items.filter { FolderIndex.isVisible(it, archives) })
        loading = false
    }

    /** Si guardar falla se informa y devuelve false. */
    suspend fun guarded(block: suspend () -> Boolean): Boolean = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        message = "Error" to "No se pudo guardar la decisión: ${e.message}"
        false
    }

    /** [key] es lo que se veía al tocar; si ya no está al frente no se registra nada. */
    fun decide(action: Action, key: MediaKey) {
        scope.launch { guarded { session.decide(action, key) } }
    }

    fun undo() {
        scope.launch {
            guarded {
                val undone = session.undo()
                if (undone != null && !undone.backInDeck) {
                    val where = Folders.targetFor(undone.decision.action)
                        ?: "la papelera (puedes restaurarla desde Papelera)"
                    message = "Aviso" to "\"${undone.decision.displayName}\" ya estaba en $where, así que no vuelve al mazo."
                }
                true
            }
        }
    }

    // Lo que se muestra en este frame: los toques actúan SOLO sobre eso, y solo después de que estuvo en
    // pantalla lo que dura un doble toque (así el segundo toque no decide sobre lo siguiente sin verlo).
    val shown = cards.firstOrNull()?.takeIf { !loading }
    val shownAt = remember(shown?.key) { SystemClock.uptimeMillis() }
    val minVisibleMs = LocalViewConfiguration.current.doubleTapTimeoutMillis
    fun act(action: Action) {
        val item = shown ?: return
        if (SystemClock.uptimeMillis() - shownAt < minVisibleMs) return
        decide(action, item.key)
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
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    ActionButton(Icons.AutoMirrored.Filled.ArrowBack, "Deshacer") { undo() }
                    ActionButton(Icons.Filled.Star, "Favoritos", shown != null) { act(Action.FAVORITOS) }
                    ActionButton(Icons.Filled.FavoriteBorder, "Liked", shown != null) { act(Action.LIKED) }
                }
                CommitBar(ops, dao, onFinished = { reload++ })
            }
        },
    ) { padding ->
        BoxWithConstraints(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            val cardSize = with(LocalDensity.current) { Size(maxWidth.roundToPx(), maxHeight.roundToPx()) }
            // Precarga las siguientes con el tamaño exacto de la tarjeta (así acierta el caché) para que al
            // avanzar no se vea el recuadro negro mientras carga.
            LaunchedEffect(cards.take(4).map { it.key }, cardSize) {
                for (item in cards.drop(1).take(3)) {
                    context.imageLoader.enqueue(
                        ImageRequest.Builder(context)
                            .data(MediaOps.uriOf(item))
                            .size(cardSize)
                            .scale(Scale.FIT)
                            .build(),
                    )
                }
            }
            when {
                loading -> CircularProgressIndicator()
                shown == null -> Text("No quedan fotos ni videos por revisar aquí.", textAlign = TextAlign.Center)
                else -> {
                    // La siguiente queda montada detrás: cuando avanza el mazo ya está cargada.
                    cards.getOrNull(1)?.let { next ->
                        key(next.key) {
                            AsyncImage(
                                model = MediaOps.uriOf(next),
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    key(shown.key) {
                        MediaCard(shown, onTapLeft = { act(Action.TRASH) }, onTapRight = { act(Action.KEEP) })
                    }
                }
            }
        }
    }
    message?.let { (title, text) -> ErrorDialog(text, title) { message = null } }
}
