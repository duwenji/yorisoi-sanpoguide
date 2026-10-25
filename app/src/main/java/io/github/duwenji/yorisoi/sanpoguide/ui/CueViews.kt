package io.github.duwenji.yorisoi.sanpoguide.ui

import io.github.duwenji.yorisoi.sanpoguide.material.BitmapContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.duwenji.yorisoi.kit.channel.material.Content
import io.github.duwenji.yorisoi.sanpoguide.material.CueDetail
import io.github.duwenji.yorisoi.kit.channel.material.Material
import io.github.duwenji.yorisoi.sanpoguide.material.bitmap
import io.github.duwenji.yorisoi.sanpoguide.material.creditLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** An image material, decoded off the main thread; its `alt` is what a screen reader says. */
@Composable
fun MaterialImage(m: Material, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Fit, maxPx: Int = 1600) {
    val image by produceState(if (m.content is BitmapContent) m.bitmap(maxPx) else null, m) {
        if (value == null) value = withContext(Dispatchers.Default) { m.bitmap(maxPx) }
    }
    image?.let { Image(it.asImageBitmap(), contentDescription = m.alt, contentScale = contentScale, modifier = modifier) }
}

/** A material's credit (API-003 F-11); a tap opens its source, when it has one. */
@Composable
fun MaterialCredit(m: Material, color: Color = MaterialTheme.colorScheme.onSurfaceVariant, modifier: Modifier = Modifier) {
    val line = creditLine(m) ?: return
    val uriHandler = LocalUriHandler.current
    val source = m.credit?.source
    Text(
        line, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis,
        modifier = modifier.then(if (source != null) Modifier.clickable { uriHandler.openUri(source) } else Modifier),
    )
}

/**
 * The sheet of a cue (API-003 `sheet`), opened only by the user's tap: its material in order,
 * and its full-screen image, which opens only while standing.
 */
@Composable
fun CueSheet(detail: CueDetail, moving: Boolean, onFullscreen: (Material) -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 32.dp).verticalScroll(rememberScrollState()),
    ) {
        detail.sheet.forEach { m ->
            when (val c = m.content) {
                is Content.Text -> Text(c.text, style = MaterialTheme.typography.bodyLarge)
                else -> MaterialImage(m, Modifier.fillMaxWidth().heightIn(max = 280.dp).clip(RoundedCornerShape(12.dp)))
            }
            MaterialCredit(m, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(16.dp))
        }
        detail.fullscreen?.let { m ->
            Button(onClick = { onFullscreen(m) }, enabled = !moving) { Text("全画面で見る") }
            if (moving) {
                Text(
                    "立ち止まって見てください", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** A cue's image over the whole screen (API-003 `fullscreen`). Walking off closes it. */
@Composable
fun FullscreenImage(m: Material, moving: Boolean, onClose: () -> Unit) {
    LaunchedEffect(moving) { if (moving) onClose() }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            MaterialImage(m, Modifier.fillMaxSize().align(Alignment.Center))
            IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "閉じる", tint = Color.White)
            }
            MaterialCredit(
                m, color = Color.White,
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.5f)).padding(16.dp, 8.dp),
            )
        }
    }
}
