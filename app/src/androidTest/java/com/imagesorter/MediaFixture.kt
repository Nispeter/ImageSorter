package com.imagesorter

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
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
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import com.imagesorter.data.MediaQueries
import com.imagesorter.domain.MediaKey
import com.imagesorter.domain.MediaKind
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern
import kotlin.concurrent.thread

/** Permisos según la versión: Android 13+ separa la lectura por tipo; Android 10 además necesita escritura. */
fun mediaPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 33) {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.ACCESS_MEDIA_LOCATION)
    } else {
        listOfNotNull(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            if (Build.VERSION.SDK_INT < 30) Manifest.permission.WRITE_EXTERNAL_STORAGE else null,
            Manifest.permission.ACCESS_MEDIA_LOCATION,
        ).toTypedArray()
    }

/**
 * Crea fotos de prueba A TRAVÉS DEL SHELL, así su dueño es el shell y no la app: los tests pasan
 * por el mismo camino de permisos que las fotos reales de la cámara. El estado se comprueba también
 * desde el shell (MediaStore + sha256sum), sin fiarse de la app. Todo lleva el prefijo [runId] y
 * [cleanup] borra solo eso.
 */
class MediaFixture(val runId: String = "IST_${System.currentTimeMillis()}") {
    init {
        // cleanup() borra por este prefijo: un runId vacío o corto borraría fotos que no son de prueba.
        require(Regex("IST_\\d{13}").matches(runId)) { "runId inválido: '$runId'" }
    }

    val context: Context = ApplicationProvider.getApplicationContext()
    private val automation = InstrumentationRegistry.getInstrumentation().uiAutomation

    data class Seeded(val key: MediaKey, val displayName: String, val relativePath: String, val path: String, val sha256: String)

    data class Row(val values: Map<String, String>) {
        val path get() = values.getValue("_data")
        val displayName get() = values.getValue("_display_name")
        val relativePath get() = values.getValue("relative_path")
        /** Android 10 no tiene papelera ni la columna is_trashed. */
        val isTrashed get() = values["is_trashed"] == "1"
        val dateExpires get() = values["date_expires"]?.toLongOrNull()
        val owner get() = values["owner_package_name"]
    }

    fun sh(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use {
            it.readBytes().decodeToString()
        }

    fun isEmulator(): Boolean =
        Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish" || sh("getprop ro.boot.qemu").trim() == "1"

    /**
     * Android 12+: concede o quita "Gestión de multimedia" (el sistema deja de preguntar).
     * Android 11 no la tiene y el sistema pregunta siempre: con [allow] se aprueba solo el diálogo de
     * MediaProvider, que es lo que haría el usuario; sin él, el test maneja el diálogo por su cuenta.
     */
    fun setManageMedia(allow: Boolean) {
        if (Build.VERSION.SDK_INT >= 31) {
            sh("appops set ${context.packageName} MANAGE_MEDIA ${if (allow) "allow" else "default"}")
            return
        }
        stopAutoApprover()
        if (!allow) return
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val allowButton = By.pkg(Pattern.compile(".*providers\\.media.*")).res("android", "button1")
        val running = AtomicBoolean(true)
        approverRunning = running
        autoApprover = thread(isDaemon = true, name = "aprobar-mediaprovider") {
            // Una bandera y no la interrupción: UiAutomation.waitForIdle se traga las interrupciones.
            while (running.get()) {
                runCatching { device.findObject(allowButton)?.click() }
                runCatching { Thread.sleep(250) }
            }
        }
    }

    companion object {
        // Global y no por test: un test siguiente siempre puede detener el aprobador de uno anterior.
        @Volatile private var approverRunning: AtomicBoolean? = null
        @Volatile private var autoApprover: Thread? = null

        private fun stopAutoApprover() {
            approverRunning?.set(false)
            autoApprover?.let {
                it.interrupt()
                it.join(15_000) // un findObject puede esperar hasta 10 s a que la UI esté quieta
                check(!it.isAlive) { "el aprobador automático no se detuvo" }
            }
            autoApprover = null
            approverRunning = null
        }
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
        if (Build.VERSION.SDK_INT >= 30) {
            sh("content call --uri content://media --method scan_file --arg $path")
        } else {
            // Android 10 no acepta scan_file desde el shell; el aviso al escáner sí.
            sh("am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://$path")
        }
    }

    /** Crea la foto y espera a que MediaStore la indexe con su tamaño final. */
    private fun isVideo(name: String) = name.endsWith(".mp4")

    /**
     * Bytes distintos por [variant] con extensión .mp4: MediaStore lo indexa como video aunque no se reproduzca.
     * Empieza con una cabecera MP4 (ftyp): sin ella el escáner de Android 10 lo ignora.
     */
    fun fakeVideo(variant: Int, size: Int = 4096): ByteArray {
        val ftyp = byteArrayOf(0, 0, 0, 0x18) + "ftypisom".toByteArray() + byteArrayOf(0, 0, 2, 0) + "isommp41".toByteArray()
        return ftyp + ByteArray(size - ftyp.size) { ((it * 31) xor variant).toByte() }
    }

    fun seed(relativePath: String, name: String, variant: Int, videoBytes: Int = 4096): Seeded {
        val bytes = if (isVideo(name)) fakeVideo(variant, videoBytes) else jpeg(variant)
        val path = "/storage/emulated/0/$relativePath$name"
        writeViaShell(path, bytes)
        scan(path)
        val key = waitFor("indexar $path") {
            findKey(relativePath, name)?.takeIf { row(it)?.values?.get("_size") == bytes.size.toString() }
        }
        check(sha256(path) == sha256(bytes)) { "El archivo sembrado no coincide: $path" }
        return Seeded(key, name, relativePath, path, sha256(bytes))
    }

    /** Crea el archivo con el resolver de la app: su dueño es la app, igual que las copias que ella crea. */
    fun seedAsApp(relativePath: String, name: String, variant: Int): Seeded {
        val bytes = if (isVideo(name)) fakeVideo(variant) else jpeg(variant)
        val kind = if (isVideo(name)) MediaKind.VIDEO else MediaKind.IMAGE
        val resolver = context.contentResolver
        val uri = checkNotNull(
            resolver.insert(
                MediaQueries.collection(kind, MediaStore.VOLUME_EXTERNAL_PRIMARY),
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                },
            ),
        )
        checkNotNull(resolver.openOutputStream(uri)).use { it.write(bytes) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        val key = MediaKey(MediaStore.VOLUME_EXTERNAL_PRIMARY, ContentUris.parseId(uri))
        val path = checkNotNull(row(key)) { "no se indexó $name" }.path
        check(sha256(path) == sha256(bytes)) { "el archivo creado no coincide: $path" }
        return Seeded(key, name, relativePath, path, sha256(bytes))
    }

    /** Foto NO en papelera con esa ruta y nombre. */
    fun findKey(relativePath: String, name: String): MediaKey? {
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "relative_path = ? AND _display_name = ?")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(relativePath, name))
        }
        context.contentResolver.query(
            MediaQueries.collection(if (isVideo(name)) MediaKind.VIDEO else MediaKind.IMAGE, MediaStore.VOLUME_EXTERNAL),
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.VOLUME_NAME),
            args,
            null,
        )?.use { c -> if (c.moveToFirst()) return MediaKey(c.getString(1), c.getLong(0)) }
        return null
    }

    /** Estado de la fila leído por el shell (incluye papelera). null si ya no existe. */
    fun row(key: MediaKey): Row? {
        val trashed = if (Build.VERSION.SDK_INT >= 30) "is_trashed:" else ""
        for (collection in listOf("images", "video")) {
            val out = sh(
                "content query --uri content://media/${key.volume}/$collection/media/${key.mediaId} " +
                    "--projection _data:_display_name:relative_path:${trashed}date_expires:owner_package_name:_size",
            )
            val line = out.lineSequence().firstOrNull { it.startsWith("Row:") } ?: continue
            val body = line.substringAfter("Row:").trim().substringAfter(' ')
            val values = Regex("""(\w+)=(.*?)(?=, \w+=|$)""").findAll(body).associate { it.groupValues[1] to it.groupValues[2] }
            return Row(values)
        }
        return null
    }

    /** SHA-256 calculado por el shell; null si el archivo no existe. Soporta nombres con espacios. */
    fun sha256(path: String): String? {
        val dir = path.substringBeforeLast('/')
        val pattern = path.substringAfterLast('/').replace(' ', '?')
        val out = sh("find $dir -maxdepth 1 -name $pattern -exec sha256sum {} ;")
        return out.lineSequence().firstOrNull { it.isNotBlank() }?.substringBefore(' ')
    }

    /** Manda algo a la papelera desde el shell, como haría la galería u otra app. */
    fun trash(key: MediaKey, kind: MediaKind) {
        val collection = if (kind == MediaKind.VIDEO) "video" else "images"
        sh("content update --uri content://media/${key.volume}/$collection/media/${key.mediaId} --bind is_trashed:i:1")
        waitFor("mandar a la papelera ${key.mediaId}") { row(key)?.takeIf { it.isTrashed } }
    }

    /** Saca algo de la papelera desde el shell, como haría la galería u otra app. */
    fun untrash(key: MediaKey, kind: MediaKind) {
        val collection = if (kind == MediaKind.VIDEO) "video" else "images"
        sh("content update --uri content://media/${key.volume}/$collection/media/${key.mediaId} --bind is_trashed:i:0")
        waitFor("sacar de la papelera ${key.mediaId}") { row(key)?.takeIf { !it.isTrashed } }
    }

    /** Reescribe el archivo desde el shell (como otra app) y espera a que MediaStore lo reindexe. */
    fun rewrite(seeded: Seeded, variant: Int): String {
        val bytes = if (isVideo(seeded.displayName)) fakeVideo(variant, size = 8192) else jpeg(variant, padding = 4096)
        writeViaShell(seeded.path, bytes)
        scan(seeded.path)
        waitFor("reindexar ${seeded.displayName}") {
            row(seeded.key)?.takeIf { it.values["_size"] == bytes.size.toString() }
        }
        return sha256(bytes)
    }

    /** Cuántos archivos hay en [absoluteDir] que empiezan por [prefix] (para detectar duplicados). */
    fun countFiles(absoluteDir: String, prefix: String): Int =
        sh("find $absoluteDir -maxdepth 1 -type f -name $prefix*").lineSequence().count { it.isNotBlank() }

    /** Cuántos archivos de [absoluteDir] contienen [token] en el nombre, incluidos .pending-* y .trashed-*. */
    fun countFilesContaining(absoluteDir: String, token: String): Int =
        sh("find $absoluteDir -maxdepth 1 -type f -name *$token*").lineSequence().count { it.isNotBlank() }

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
        val whatsapp = "/storage/emulated/0/Android/media/com.whatsapp"
        val roots = "/storage/emulated/0/Pictures /storage/emulated/0/DCIM /storage/emulated/0/Movies $whatsapp"
        sh("find $roots -type f -name *$runId* -delete")
        sh("find $roots -depth -type d -name $runId* -empty -delete")
        // Android 10 no se entera de lo que el shell borra: sin esto quedan filas de archivos que ya no existen.
        // Sin espacios: executeShellCommand no respeta comillas.
        if (Build.VERSION.SDK_INT < 30) sh("content delete --uri content://media/external/file --where instr(_data,'$runId')>0")
    }
}
