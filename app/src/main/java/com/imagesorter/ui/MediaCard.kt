package com.imagesorter.ui

import android.widget.VideoView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.imagesorter.data.MediaOps
import com.imagesorter.domain.MediaItem
import com.imagesorter.domain.MediaKind
import kotlinx.coroutines.launch

/**
 * Foto o video al frente del mazo. Tocar la mitad izquierda = borrar, la mitad derecha = conservar.
 * Los videos muestran su primer cuadro y un botón de reproducción que los abre aquí mismo.
 */
@Composable
fun MediaCard(item: MediaItem, onTapLeft: () -> Unit, onTapRight: () -> Unit, modifier: Modifier = Modifier) {
    var playing by remember(item.key) { mutableStateOf(false) }
    // Destello de color al elegir: rojo para borrar, verde para conservar.
    val flash = remember(item.key) { Animatable(0f) }
    var flashColor by remember(item.key) { mutableStateOf(Color.Transparent) }
    var deciding by remember(item.key) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun decideWithFlash(color: Color, onDecided: () -> Unit) {
        if (deciding) return
        deciding = true
        scope.launch {
            flashColor = color
            flash.animateTo(0.45f, tween(90))
            flash.animateTo(0f, tween(170))
            onDecided()
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .testTag("card")
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black),
    ) {
        if (playing) {
            AndroidView(
                factory = { context ->
                    VideoView(context).apply {
                        setVideoURI(MediaOps.uriOf(item))
                        setOnPreparedListener { player ->
                            player.isLooping = true
                            start()
                        }
                        setOnErrorListener { _, _, _ ->
                            playing = false
                            true
                        }
                    }
                },
                onRelease = { it.stopPlayback() },
                modifier = Modifier.align(Alignment.Center).fillMaxSize(),
            )
        } else {
            AsyncImage(
                model = MediaOps.uriOf(item),
                contentDescription = item.displayName,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Zonas de toque encima de la imagen o el video.
        Row(Modifier.fillMaxSize()) {
            TapZone(Icons.Filled.Delete, "Borrar", Alignment.BottomStart, Modifier.weight(1f).testTag("tap-left")) {
                decideWithFlash(BORRAR, onTapLeft)
            }
            TapZone(Icons.Filled.Check, "Conservar", Alignment.BottomEnd, Modifier.weight(1f).testTag("tap-right")) {
                decideWithFlash(CONSERVAR, onTapRight)
            }
        }

        if (item.kind == MediaKind.VIDEO) {
            Surface(
                onClick = { playing = !playing },
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.55f),
                contentColor = Color.White,
                modifier = Modifier.align(Alignment.Center).size(64.dp).testTag("play"),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (playing) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            repeat(2) {
                                Box(Modifier.width(5.dp).height(20.dp).background(Color.White, RoundedCornerShape(2.dp)))
                            }
                        }
                    } else {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "Reproducir", modifier = Modifier.size(32.dp))
                    }
                }
            }
        }

        Text(
            "${item.displayName}\n${item.relativePath}",
            color = Color.White,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(8.dp),
        )

        // Va encima de todo, sin modificadores de toque, así que no intercepta nada.
        if (flash.value > 0f) {
            Box(Modifier.matchParentSize().background(flashColor.copy(alpha = flash.value)))
        }
    }
}

private val BORRAR = Color(0xFFD32F2F)
private val CONSERVAR = Color(0xFF2E7D32)

@Composable
private fun TapZone(icon: ImageVector, label: String, align: Alignment, modifier: Modifier, onTap: () -> Unit) {
    Box(modifier.fillMaxHeight().clickable(onClick = onTap)) {
        Row(
            Modifier
                .align(align)
                .padding(12.dp)
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge)
        }
    }
}
