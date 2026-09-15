package com.imagesorter.ui

import android.app.Activity
import android.app.PendingIntent
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Lanza las solicitudes de MediaStore (papelera, escritura, borrado) y espera la respuesta.
 * Debe crearse antes de onStart de la Activity. Devuelve true solo si el usuario aprobó; eso NO
 * garantiza que cada foto se haya modificado (ver MediaOps).
 */
class ApprovalLauncher(activity: ComponentActivity) {
    private val mutex = Mutex()
    private var pending: CompletableDeferred<Boolean>? = null

    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        pending?.complete(result.resultCode == Activity.RESULT_OK)
        pending = null
    }

    suspend fun approve(request: PendingIntent): Boolean = mutex.withLock {
        val answer = CompletableDeferred<Boolean>()
        withContext(Dispatchers.Main) {
            pending = answer
            launcher.launch(IntentSenderRequest.Builder(request.intentSender).build())
        }
        answer.await()
    }
}
