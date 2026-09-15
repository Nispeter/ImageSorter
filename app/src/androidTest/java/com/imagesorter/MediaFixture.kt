package com.imagesorter

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.imagesorter.domain.MediaKey
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * Crea fotos de prueba A TRAVÉS DEL SHELL, así su dueño es el shell y no la app: los tests pasan
 * por el mismo camino de permisos que las fotos reales de la cámara. El estado se comprueba también
 * desde el shell (MediaStore + sha256sum), sin fiarse de la app. Todo lleva el prefijo [runId] y
 * [cleanup] borra solo eso.
 */
class MediaFixture(val runId: String = "IST_${System.currentTimeMillis()}") {
    val context: Context = ApplicationProvider.getApplicationContext()
    private val automation = InstrumentationRegistry.getInstrumentation().uiAutomation

    data class Seeded(val key: MediaKey, val displayName: String, val relativePath: String, val path: String, val sha256: String)

    data class Row(val values: Map<String, String>) {
        val path get() = values.getValue("_data")
        val displayName get() = values.getValue("_display_name")
        val relativePath get() = values.getValue("relative_path")
        val isTrashed get() = values.getValue("is_trashed") == "1"
        val dateExpires get() = values["date_expires"]?.toLongOrNull()
        val owner get() = values["owner_package_name"]
    }

    fun sh(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use {
            it.readBytes().decodeToString()
        }

    fun isEmulator(): Boolean =
        Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish" || sh("getprop ro.boot.qemu").trim() == "1"

    fun setManageMedia(allow: Boolean) {
        sh("appops set ${context.packageName} MANAGE_MEDIA ${if (allow) "allow" else "default"}")
    }

    /** JPEG distinto para cada [variant]; [padding] añade bytes para cambiar el tamaño. */
    fun jpeg(variant: Int, padding: Int = 0): ByteArray {
        val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
        for (x in 0 until 48) for (y in 0 until 48) {
            bitmap.setPixel(x, y, Color.rgb((x * 5 + variant * 37) % 256, (y * 5 + variant * 11) % 256, (x * y + variant) % 256))
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        return out.toByteArray() + ByteArray(padding)
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Escribe el archivo como el usuario shell (sin pasar por la app). */
    fun writeViaShell(path: String, bytes: ByteArray) {
        sh("mkdir -p ${path.substringBeforeLast('/')}")
        val (stdout, stdin) = automation.executeShellCommandRw("dd of=$path")
        ParcelFileDescriptor.AutoCloseOutputStream(stdin).use { it.write(bytes) }
        ParcelFileDescriptor.AutoCloseInputStream(stdout).use { it.readBytes() }
    }

    fun scan(path: String) {
        sh("content call --uri content://media --method scan_file --arg $path")
    }

    /** Crea la foto y espera a que MediaStore la indexe con su tamaño final. */
    fun seed(relativePath: String, name: String, variant: Int): Seeded {
        val bytes = jpeg(variant)
        val path = "/storage/emulated/0/$relativePath$name"
        writeViaShell(path, bytes)
        scan(path)
        val key = waitFor("indexar $path") {
            findKey(relativePath, name)?.takeIf { row(it)?.values?.get("_size") == bytes.size.toString() }
        }
        check(sha256(path) == sha256(bytes)) { "El archivo sembrado no coincide: $path" }
        return Seeded(key, name, relativePath, path, sha256(bytes))
    }

    /** Foto NO en papelera con esa ruta y nombre. */
    fun findKey(relativePath: String, name: String): MediaKey? {
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "relative_path = ? AND _display_name = ?")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(relativePath, name))
        }
        context.contentResolver.query(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.VOLUME_NAME),
            args,
            null,
        )?.use { c -> if (c.moveToFirst()) return MediaKey(c.getString(1), c.getLong(0)) }
        return null
    }

    /** Estado de la fila leído por el shell (incluye papelera). null si ya no existe. */
    fun row(key: MediaKey): Row? {
        val out = sh(
            "content query --uri content://media/${key.volume}/images/media/${key.mediaId} " +
                "--projection _data:_display_name:relative_path:is_trashed:date_expires:owner_package_name:_size",
        )
        val line = out.lineSequence().firstOrNull { it.startsWith("Row:") } ?: return null
        val body = line.substringAfter("Row:").trim().substringAfter(' ')
        val values = Regex("""(\w+)=(.*?)(?=, \w+=|$)""").findAll(body).associate { it.groupValues[1] to it.groupValues[2] }
        return Row(values)
    }

    /** SHA-256 calculado por el shell; null si el archivo no existe. Soporta nombres con espacios. */
    fun sha256(path: String): String? {
        val dir = path.substringBeforeLast('/')
        val pattern = path.substringAfterLast('/').replace(' ', '?')
        val out = sh("find $dir -maxdepth 1 -name $pattern -exec sha256sum {} ;")
        return out.lineSequence().firstOrNull { it.isNotBlank() }?.substringBefore(' ')
    }

    fun <T : Any> waitFor(what: String, timeoutMs: Long = 15_000, probe: () -> T?): T {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            probe()?.let { return it }
            check(SystemClock.uptimeMillis() < deadline) { "Timeout esperando: $what" }
            Thread.sleep(200)
        }
    }

    /** Borra SOLO lo creado por este test (nombres con [runId]), incluidas copias en papelera y movidas. */
    fun cleanup() {
        val roots = "/storage/emulated/0/Pictures /storage/emulated/0/DCIM"
        sh("find $roots -type f -name *$runId* -delete")
        sh("find $roots -depth -type d -name $runId* -empty -delete")
    }
}
