package com.imagesorter.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.imagesorter.data.MediaOps
import com.imagesorter.data.db.Decision
import com.imagesorter.data.db.DecisionDao
import com.imagesorter.domain.Action
import com.imagesorter.domain.Folders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Botón "Confirmar (N)": muestra un resumen, pide confirmación y ejecuta las decisiones registradas. */
@Composable
fun CommitBar(ops: MediaOps, dao: DecisionDao, onFinished: () -> Unit, modifier: Modifier = Modifier) {
    val stagedCount by dao.observeStagedCount().collectAsState(initial = 0)
    val scope = rememberCoroutineScope()
    var summary by remember { mutableStateOf<List<Decision>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<MediaOps.OpResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    Button(
        onClick = { scope.launch { summary = dao.staged().takeIf { it.isNotEmpty() } } },
        enabled = stagedCount > 0 && !busy,
        modifier = modifier.fillMaxWidth(),
    ) { Text("Confirmar ($stagedCount)") }

    summary?.let { shown ->
        AlertDialog(
            onDismissRequest = { summary = null },
            title = { Text("¿Aplicar cambios?") },
            text = { Text(describe(shown.groupingBy { it.action }.eachCount())) },
            confirmButton = {
                TextButton(onClick = {
                    summary = null
                    busy = true
                    // Solo se aplica lo que el resumen mostró, aunque llegue otra decisión mientras tanto.
                    val upToSeq = shown.maxOf { it.seq }
                    scope.launch {
                        try {
                            result = ops.commitStaged(upToSeq)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = e.message ?: e.javaClass.simpleName
                        } finally {
                            busy = false
                            onFinished()
                        }
                    }
                }) { Text("Aplicar") }
            },
            dismissButton = { TextButton(onClick = { summary = null }) { Text("Cancelar") } },
        )
    }
    if (busy) BusyDialog("Aplicando cambios…")
    result?.let { ResultDialog(it) { result = null } }
    error?.let { ErrorDialog(it) { error = null } }
}

private fun describe(counts: Map<Action, Int>) = buildString {
    counts[Action.TRASH]?.let { appendLine("🗑 $it a la papelera (recuperables ~30 días)") }
    counts[Action.FAVORITOS]?.let { appendLine("⭐ $it a ${Folders.FAVORITOS}") }
    counts[Action.LIKED]?.let { appendLine("❤️ $it a ${Folders.LIKED}") }
    counts[Action.KEEP]?.let { appendLine("✓ $it conservadas") }
}.trim()

@Composable
fun BusyDialog(text: String) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        Card {
            Row(
                Modifier.padding(24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CircularProgressIndicator()
                Text(text)
            }
        }
    }
}

@Composable
fun ResultDialog(result: MediaOps.OpResult, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        title = { Text(if (result.cancelled) "Cancelado" else "Listo") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Hechas: ${result.done}")
                if (result.cancelled) Text("No se hizo nada más. Lo pendiente sigue guardado.")
                if (result.failures.isNotEmpty()) {
                    Text("No se pudieron procesar ${result.failures.size}:")
                    result.failures.take(20).forEach {
                        Text("• ${it.displayName}: ${it.reason}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (result.failures.size > 20) Text("…y ${result.failures.size - 20} más")
                }
            }
        },
    )
}

@Composable
fun ErrorDialog(message: String, title: String = "Error", onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        title = { Text(title) },
        text = { Text(message) },
    )
}

@Composable
fun ActionButton(emoji: String, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(emoji, fontSize = 24.sp)
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}
