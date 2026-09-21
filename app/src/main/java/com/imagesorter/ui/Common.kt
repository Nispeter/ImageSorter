package com.imagesorter.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.request.ImageRequest
import com.imagesorter.data.MediaOps
import com.imagesorter.domain.MediaItem
import com.imagesorter.data.db.Decision
import com.imagesorter.data.db.DecisionDao
import com.imagesorter.domain.Action
import com.imagesorter.domain.Folders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Botón "Confirmar (N)": muestra un resumen y ejecuta las decisiones registradas. Si hay fotos o videos
 * que van a la papelera, pide una SEGUNDA confirmación solo para eso antes de tocar nada.
 */
@Composable
fun CommitBar(ops: MediaOps, dao: DecisionDao, onFinished: () -> Unit, modifier: Modifier = Modifier) {
    val stagedCount by remember(dao) { dao.observeStagedCount() }.collectAsState(initial = 0)
    val scope = rememberCoroutineScope()
    var summary by remember { mutableStateOf<List<Decision>?>(null) }
    var confirmTrash by remember { mutableStateOf<List<Decision>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<MediaOps.OpResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    /** Aplica exactamente lo que el usuario vio, aunque llegue otra decisión mientras tanto. */
    fun apply(shown: List<Decision>) {
        if (busy) return
        busy = true
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
    }

    Button(
        onClick = {
            if (loading || busy) return@Button
            loading = true
            scope.launch {
                val staged = dao.staged()
                loading = false
                // Si mientras se consultaba ya empezó a aplicarse, no se abre otro resumen encima.
                if (!busy) summary = staged.takeIf { it.isNotEmpty() }
            }
        },
        enabled = stagedCount > 0 && !busy && !loading,
        modifier = modifier.fillMaxWidth(),
    ) { Text("Confirmar ($stagedCount)") }

    summary?.let { shown ->
        val toTrash = shown.count { it.action == Action.TRASH }
        AlertDialog(
            onDismissRequest = { summary = null },
            title = { Text("¿Aplicar cambios?") },
            text = { Text(describe(shown.groupingBy { it.action }.eachCount())) },
            confirmButton = {
                TextButton(onClick = {
                    summary = null
                    if (toTrash > 0) confirmTrash = shown else apply(shown)
                }) { Text(if (toTrash > 0) "Continuar" else "Aplicar") }
            },
            dismissButton = { TextButton(onClick = { summary = null }) { Text("Cancelar") } },
        )
    }

    confirmTrash?.let { shown ->
        val toTrash = shown.count { it.action == Action.TRASH }
        val confirm = afterShown {
            confirmTrash = null
            apply(shown)
        }
        AlertDialog(
            onDismissRequest = { confirmTrash = null },
            title = { Text("¿Mandar $toTrash a la papelera?") },
            text = {
                Text(
                    "Esta es la última confirmación. Podrás recuperarlas desde Papelera durante unos 30 días, " +
                        "después Android las borra solo.",
                )
            },
            confirmButton = {
                TextButton(onClick = confirm) { Text("Sí, a la papelera", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmTrash = null }) { Text("Cancelar") } },
        )
    }
    if (busy) BusyDialog("Aplicando cambios…")
    result?.let { ResultDialog(it) { result = null } }
    error?.let { ErrorDialog(it) { error = null } }
}

private fun describe(counts: Map<Action, Int>) = buildString {
    counts[Action.TRASH]?.let { appendLine("$it a la papelera (recuperables ~30 días)") }
    counts[Action.FAVORITOS]?.let { appendLine("$it a ${Folders.FAVORITOS}") }
    counts[Action.LIKED]?.let { appendLine("$it a ${Folders.LIKED}") }
    counts[Action.KEEP]?.let { appendLine("$it conservadas") }
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
                    Text("Avisos (${result.failures.size}):")
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

/** Lo mínimo que una confirmación destructiva está en pantalla antes de aceptar un toque. */
const val CONFIRM_MIN_VISIBLE_MS = 800L

/**
 * Envuelve el botón que confirma algo destructivo: ignora toques durante [CONFIRM_MIN_VISIBLE_MS]. El botón
 * queda donde estaba "Continuar", así que sin esto el segundo toque de un doble (o triple) toque en el
 * diálogo anterior aceptaría este sin haberlo leído.
 */
@Composable
fun afterShown(onClick: () -> Unit): () -> Unit {
    val shownAt = remember { SystemClock.uptimeMillis() }
    return { if (SystemClock.uptimeMillis() - shownAt >= CONFIRM_MIN_VISIBLE_MS) onClick() }
}

/**
 * Petición de imagen cuya clave de caché incluye tamaño y fecha: si el archivo cambió, nunca se ve la
 * versión vieja guardada en memoria (se decidiría sobre algo distinto de lo que muestra la pantalla).
 */
fun imageOf(context: android.content.Context, item: MediaItem): ImageRequest =
    ImageRequest.Builder(context)
        .data(MediaOps.uriOf(item))
        .memoryCacheKey("${MediaOps.uriOf(item)}#${item.size}#${item.dateModified}")
        .build()

@Composable
fun ActionButton(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.height(4.dp))
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}
