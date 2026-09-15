package com.imagesorter.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.imagesorter.data.MediaOps
import com.imagesorter.domain.Action
import com.imagesorter.domain.MediaItem
import kotlinx.coroutines.launch

/**
 * Tarjeta arrastrable: izquierda = borrar, derecha = conservar. [onSwipe] devuelve false si la
 * decisión no se registró; entonces la tarjeta vuelve a su sitio.
 */
@Composable
fun SwipeCard(item: MediaItem, onSwipe: suspend (Action) -> Boolean, modifier: Modifier = Modifier) {
    val offsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val threshold = with(LocalDensity.current) { 120.dp.toPx() }
    val progress = (offsetX.value / threshold).coerceIn(-1f, 1f)

    Box(
        modifier
            .fillMaxSize()
            .testTag("card")
            .graphicsLayer {
                translationX = offsetX.value
                rotationZ = offsetX.value / 60f
            }
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black)
            .pointerInput(item.key) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        scope.launch {
                            val action = when {
                                offsetX.value <= -threshold -> Action.TRASH
                                offsetX.value >= threshold -> Action.KEEP
                                else -> null
                            }
                            // Se decide en el instante en que se suelta, para ESTA foto. Si no se guardó
                            // (o ya no es la foto al frente), la tarjeta vuelve a su sitio.
                            if (action == null || !onSwipe(action)) offsetX.animateTo(0f)
                        }
                    },
                    onDragCancel = { scope.launch { offsetX.animateTo(0f) } },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        scope.launch { offsetX.snapTo(offsetX.value + dragAmount) }
                    },
                )
            },
    ) {
        AsyncImage(
            model = MediaOps.uriOf(item.key),
            contentDescription = item.displayName,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
        if (progress < 0f) SwipeLabel("BORRAR", Color(0xFFD32F2F), -progress, Modifier.align(Alignment.TopEnd))
        if (progress > 0f) SwipeLabel("CONSERVAR", Color(0xFF2E7D32), progress, Modifier.align(Alignment.TopStart))
        Text(
            "${item.displayName}\n${item.relativePath}",
            color = Color.White,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(8.dp),
        )
    }
}

@Composable
private fun SwipeLabel(text: String, color: Color, alpha: Float, modifier: Modifier) {
    Text(
        text,
        color = color,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        modifier = modifier
            .padding(16.dp)
            .graphicsLayer { this.alpha = alpha }
            .border(3.dp, color, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 4.dp),
    )
}
