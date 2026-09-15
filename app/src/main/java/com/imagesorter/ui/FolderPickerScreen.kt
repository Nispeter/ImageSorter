package com.imagesorter.ui

import android.provider.MediaStore
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.imagesorter.data.db.DecisionDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderPickerScreen(
    queries: MediaQueries,
    ops: MediaOps,
    dao: DecisionDao,
    access: Permissions.Status,
    onOpenDeck: (Screen.Deck) -> Unit,
    onOpenTrash: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var folders by remember { mutableStateOf<List<MediaQueries.Folder>?>(null) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var reload by remember { mutableIntStateOf(0) }
    var confirmReset by remember { mutableStateOf(false) }

    LaunchedEffect(reload) { folders = withContext(Dispatchers.IO) { queries.folders() } }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { reload++ }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("¿Qué quieres revisar?") },
                actions = { TextButton(onClick = onOpenTrash) { Text("Papelera") } },
            )
        },
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
                val total = folders?.sumOf { it.count }
                Button(
                    onClick = { onOpenDeck(Screen.Deck(null, "Todas las fotos")) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (total == null) "Todas las fotos" else "Todas las fotos ($total)") }
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Carpetas", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Button(
                        enabled = selected.isNotEmpty(),
                        onClick = {
                            val names = folders.orEmpty().filter { it.bucketId in selected }.joinToString(", ") { it.name }
                            onOpenDeck(Screen.Deck(selected, names))
                        },
                    ) { Text("Revisar seleccionadas (${selected.size})") }
                }
            }
            val list = folders
            if (list == null) {
                item { CircularProgressIndicator() }
            } else {
                items(list, key = { it.bucketId }) { folder ->
                    val checked = folder.bucketId in selected
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = checked,
                                role = Role.Checkbox,
                                onValueChange = { on -> selected = if (on) selected + folder.bucketId else selected - folder.bucketId },
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(folder.name)
                            // Distingue carpetas con el mismo nombre (p. ej. Camera en memoria y en tarjeta).
                            val onCard = folder.volume != MediaStore.VOLUME_EXTERNAL_PRIMARY
                            Text(
                                if (onCard) "${folder.relativePath} · tarjeta/USB" else folder.relativePath,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text("${folder.count}", style = MaterialTheme.typography.bodySmall)
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
            text = { Text("Las fotos que ya revisaste y aplicaste volverán a aparecer en los mazos. No se modifica ninguna foto.") },
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
