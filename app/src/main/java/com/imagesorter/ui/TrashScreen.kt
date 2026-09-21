package com.imagesorter.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.imagesorter.data.MediaOps
import com.imagesorter.data.MediaQueries
import com.imagesorter.domain.MediaItem
import com.imagesorter.domain.MediaKey
import com.imagesorter.domain.MediaKind
import com.imagesorter.domain.Verifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Papelera de imágenes del sistema. Único lugar desde el que se puede borrar definitivamente. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(queries: MediaQueries, ops: MediaOps, onBack: () -> Unit) {
    var busy by remember { mutableStateOf(false) }
    BackHandler(enabled = !busy, onBack = onBack)
    var items by remember { mutableStateOf<List<MediaItem>?>(null) }
    var selected by remember { mutableStateOf(emptySet<MediaKey>()) }
    var reload by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<MediaOps.OpResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<List<MediaItem>?>(null) }
    var confirmDeleteFinal by remember { mutableStateOf<List<MediaItem>?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(reload) {
        val loaded = withContext(Dispatchers.IO) { queries.trash() }
        items = loaded
        selected = selected intersect loaded.map { it.key }.toSet()
    }
    val nowMs = remember(items) { System.currentTimeMillis() }
    val chosen = items.orEmpty().filter { it.key in selected }

    fun perform(block: suspend () -> MediaOps.OpResult) {
        busy = true
        scope.launch {
            try {
                result = block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            } finally {
                busy = false
                reload++
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Papelera") },
                navigationIcon = { TextButton(onClick = onBack, enabled = !busy) { Text("←") } },
                actions = {
                    val all = items.orEmpty()
                    if (all.isNotEmpty()) {
                        val allSelected = selected.size == all.size
                        TextButton(onClick = { selected = if (allSelected) emptySet() else all.map { it.key }.toSet() }) {
                            Text(if (allSelected) "Ninguna" else "Todas")
                        }
                    }
                },
            )
        },
        bottomBar = {
            Row(Modifier.navigationBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val toRestore = chosen
                        perform { ops.restore(toRestore) }
                    },
                    enabled = chosen.isNotEmpty() && !busy,
                    modifier = Modifier.weight(1f),
                ) { Text("Restaurar (${chosen.size})") }
                OutlinedButton(
                    onClick = { confirmDelete = chosen },
                    enabled = chosen.isNotEmpty() && !busy,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Borrar para siempre (${chosen.size})") }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                "Android borra cada foto o video de la papelera cuando vence su plazo (~30 días desde que se envió). " +
                    "Si desinstalas la app, siguen en la papelera del sistema hasta que venzan.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            val list = items
            when {
                list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                list.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("La papelera está vacía.") }
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(110.dp),
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(list, key = { "${it.volume}:${it.id}" }) { item ->
                        TrashCell(
                            item = item,
                            checked = item.key in selected,
                            daysLeft = item.dateExpires?.let { Verifier.daysLeft(it, nowMs) },
                            onToggle = { on -> selected = if (on) selected + item.key else selected - item.key },
                        )
                    }
                }
            }
        }
    }

    confirmDelete?.let { toDelete ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("¿Borrar para siempre?") },
            text = { Text("Se borrarán definitivamente ${toDelete.size} fotos o videos. Esto NO se puede deshacer.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    confirmDeleteFinal = toDelete
                }) { Text("Continuar", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancelar") } },
        )
    }

    // Segunda confirmación: es lo único que no se puede deshacer en toda la app.
    confirmDeleteFinal?.let { toDelete ->
        val confirm = afterShown {
            confirmDeleteFinal = null
            perform { ops.deleteForever(toDelete) }
        }
        AlertDialog(
            onDismissRequest = { confirmDeleteFinal = null },
            title = { Text("Última confirmación") },
            text = {
                val what = if (toDelete.size == 1) "1 foto o video se borra" else "${toDelete.size} fotos o videos se borran"
                Text("$what ahora del teléfono. No quedan en la papelera ni se pueden recuperar.")
            },
            confirmButton = {
                TextButton(onClick = confirm) { Text("Borrar definitivamente", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteFinal = null }) { Text("Cancelar") } },
        )
    }
    if (busy) BusyDialog("Procesando…")
    result?.let { ResultDialog(it) { result = null } }
    error?.let { ErrorDialog(it) { error = null } }
}

@Composable
private fun TrashCell(item: MediaItem, checked: Boolean, daysLeft: Int?, onToggle: (Boolean) -> Unit) {
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onToggle),
    ) {
        AsyncImage(
            model = imageOf(LocalContext.current, item),
            contentDescription = item.displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (item.kind == MediaKind.VIDEO) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = "Video",
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                    .padding(4.dp),
            )
        }
        Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.align(Alignment.TopEnd))
        if (daysLeft != null) {
            Text(
                "~$daysLeft d",
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}
