package com.imagesorter.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

object Permissions {
    /**
     * @param fullRead acceso a TODAS las fotos (requisito para usar la app).
     * @param partialRead Android 14+: el usuario eligió solo algunas fotos.
     * @param manageMedia "Gestión de multimedia": el sistema no pide confirmación en cada lote.
     */
    data class Status(val fullRead: Boolean, val partialRead: Boolean, val manageMedia: Boolean)

    fun status(context: Context): Status {
        fun granted(permission: String) =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        val full = if (Build.VERSION.SDK_INT >= 33) {
            granted(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            granted(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val partial = !full && Build.VERSION.SDK_INT >= 34 &&
            granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        return Status(full, partial, MediaStore.canManageMedia(context))
    }

    fun runtimeRequest(): Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.READ_MEDIA_IMAGES) else add(Manifest.permission.READ_EXTERNAL_STORAGE)
        if (Build.VERSION.SDK_INT >= 34) add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        add(Manifest.permission.ACCESS_MEDIA_LOCATION)
    }.toTypedArray()

    fun manageMediaSettings(context: Context) =
        Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA, Uri.parse("package:${context.packageName}"))

    fun appSettings(context: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
}

@Composable
fun PermissionScreen(status: Permissions.Status, onResult: () -> Unit) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onResult() }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text("ImageSorter necesita acceso a tus fotos", style = MaterialTheme.typography.headlineSmall)
        Text("Permite el acceso a TODAS las fotos para revisarlas. Nada se mueve ni se borra hasta que pulses Confirmar.")
        if (status.partialRead) {
            Text(
                "Diste acceso solo a algunas fotos: la app vería tu galería incompleta. Elige \"Permitir todo\".",
                color = MaterialTheme.colorScheme.error,
            )
        }
        Button(onClick = { launcher.launch(Permissions.runtimeRequest()) }) { Text("Dar acceso a las fotos") }
        OutlinedButton(onClick = { context.startActivity(Permissions.appSettings(context)) }) {
            Text("Abrir ajustes de la app")
        }
    }
}
