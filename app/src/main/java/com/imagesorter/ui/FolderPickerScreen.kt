package com.imagesorter.ui

import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.imagesorter.data.MediaOps
import com.imagesorter.data.MediaQueries
import com.imagesorter.data.db.ArchiveDao
import com.imagesorter.data.db.ArchivedFolder
import com.imagesorter.data.db.DecisionDao
import com.imagesorter.domain.FolderEntry
import com.imagesorter.domain.FolderIndex
import com.imagesorter.domain.FolderKey
import com.imagesorter.domain.FolderSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderPickerScreen(
    queries: MediaQueries,
    ops: MediaOps,
    dao: DecisionDao,
    archiveDao: ArchiveDao,
    access: Permissions.Status,
    onOpenDeck: (Screen.Deck) -> Unit,
    onOpenTrash: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var entries by remember { mutableStateOf<List<FolderEntry>?>(null) }
    val archives by remember(archiveDao) { archiveDao.observeAll() }.collectAsState(initial = null)
    val index = remember(entries, archives) {
        val e = entries
        val a = archives
        if (e == null || a == null) null else FolderIndex.build(e, a)
    }
    var selected by remember { mutableStateOf(emptySet<FolderKey>()) }
    var showArchived by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var confirmReset by remember { mutableStateOf(false) }

    LaunchedEffect(reload) { entries = withContext(Dispatchers.IO) { queries.folderEntries() } }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { reload++ }

    val chosen = index?.visible.orEmpty().filter { it.key in selected }

    fun archive(folder: FolderSummary) {
        val previous = archives?.firstOrNull { it.key() == folder.key }
        selected = selected - folder.key
        scope.launch {
            archiveDao.archive(ArchivedFolder(folder.volume, folder.bucketId, folder.latestAdded, folder.name, folder.relativePath))
            val result = snackbar.showSnackbar("\"${folder.name}\" archivada", actionLabel = "Deshacer", duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) {
                if (previous != null) archiveDao.archive(previous) else archiveDao.unarchive(folder.volume, folder.bucketId)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("¿Qué quieres revisar?") },
                actions = { TextButton(onClick = onOpenTrash) { Text("Papelera") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            CommitBar(ops, dao, onFinished = { reload++ }, modifier = Modifier.navigationBarsPadding().padding(16.dp))
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!access.manageMedia) {
                item {
                    Card {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "Consejo: activa \"Gestión de multimedia\" para que Android no pida confirmación " +
                                    "en cada lote. Aun así, nada se aplica hasta que pulses Confirmar.",
                            )
                            OutlinedButton(onClick = { context.startActivity(Permissions.manageMediaSettings(context)) }) {
                                Text("Activar")
                            }
                        }
                    }
                }
            }
            item {
                val total = index?.visible?.sumOf { it.count }
                Button(
                    onClick = { onOpenDeck(Screen.Deck(null, "Todo")) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (total == null) "Todas" else "Todas ($total)") }
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Carpetas", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Button(
                        enabled = chosen.isNotEmpty(),
                        onClick = {
                            onOpenDeck(Screen.Deck(chosen.map { it.bucketId }.toSet(), chosen.joinToString(", ") { it.name }))
                        },
                    ) { Text("Revisar seleccionadas (${chosen.size})") }
                }
            }
            item {
                Text(
                    "Desliza una carpeta hacia un lado para archivarla. Vuelve a aparecer si llegan fotos o videos nuevos.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val current = index
            if (current == null) {
                item { CircularProgressIndicator() }
            } else {
                items(current.visible, key = { "${it.volume}:${it.bucketId}" }) { folder ->
                    ArchivableRow(onArchive = { archive(folder) }) {
                        FolderRow(
                            folder = folder,
                            checked = folder.key in selected,
                            onToggle = { on -> selected = if (on) selected + folder.key else selected - folder.key },
                        )
                    }
                }
                if (current.archived.isNotEmpty()) {
                    item {
                        TextButton(onClick = { showArchived = !showArchived }) {
                            Text(if (showArchived) "Ocultar archivadas" else "Archivadas (${current.archived.size})")
                        }
                    }
                    if (showArchived) {
                        items(current.archived, key = { "archived:${it.volume}:${it.bucketId}" }) { folder ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(folder.name)
                                    Text(
                                        location(folder),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TextButton(onClick = { scope.launch { archiveDao.unarchive(folder.volume, folder.bucketId) } }) {
                                    Text("Restaurar")
                                }
                            }
                        }
                    }
                }
            }
            item { TextButton(onClick = { confirmReset = true }) { Text("Reiniciar revisadas") } }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("¿Reiniciar revisadas?") },
            text = { Text("Las fotos y videos que ya revisaste y aplicaste volverán a aparecer en los mazos. No se modifica ningún archivo.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    scope.launch { dao.resetReviewed() }
                }) { Text("Reiniciar") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun FolderRow(folder: FolderSummary, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onToggle)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(folder.name)
            Text(location(folder), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (folder.hasNew) {
            // Carpeta archivada que recibió fotos o videos nuevos.
            Badge(containerColor = MaterialTheme.colorScheme.tertiary, contentColor = MaterialTheme.colorScheme.onTertiary) {
                Text("${folder.count} nuevas")
            }
        } else {
            Text("${folder.count}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Deslizar hacia cualquier lado archiva la carpeta. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArchivableRow(onArchive: () -> Unit, content: @Composable () -> Unit) {
    var fired by remember { mutableStateOf(false) }
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            val dismiss = value != SwipeToDismissBoxValue.Settled
            if (dismiss && !fired) {
                fired = true
                onArchive()
            }
            dismiss
        },
        // Hay que arrastrar más de media fila: así no se archiva sin querer al desplazar la lista.
        positionalThreshold = { totalDistance -> totalDistance * 0.6f },
    )
    SwipeToDismissBox(
        state = state,
        backgroundContent = {
            val alignment = if (state.dismissDirection == SwipeToDismissBoxValue.EndToStart) Alignment.CenterEnd else Alignment.CenterStart
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .padding(horizontal = 24.dp),
                contentAlignment = alignment,
            ) {
                Text("Archivar", color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        },
    ) { content() }
}

private fun location(folder: FolderSummary): String =
    if (folder.volume != MediaStore.VOLUME_EXTERNAL_PRIMARY) "${folder.relativePath} · tarjeta/USB" else folder.relativePath
