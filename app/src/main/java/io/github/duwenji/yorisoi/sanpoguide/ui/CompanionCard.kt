package io.github.duwenji.yorisoi.sanpoguide.ui

import io.github.duwenji.yorisoi.kit.mood.MoodScene
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.duwenji.yorisoi.sanpoguide.companion.WalkCompanion
import io.github.duwenji.yorisoi.sanpoguide.companion.LiveWalk
import io.github.duwenji.yorisoi.sanpoguide.companion.Utterance
import io.github.duwenji.yorisoi.sanpoguide.material.CueDetail
import io.github.duwenji.yorisoi.kit.mood.Mood
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The companion's latest words, plus live walk figures, floating over the map. [onReplay] reads
 * the latest line again; earlier lines open below it.
 * With a [scene], the figures sit on a small picture of the current mood. A channel's line may
 * carry an image and its credit, and a detail the user opens with [onOpen].
 */
@Composable
fun CompanionCard(
    live: LiveWalk?,
    lines: List<Utterance>,
    scene: Mood? = null,
    onOpen: (CueDetail) -> Unit = {},
    onReplay: (Utterance) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val now by produceState(System.currentTimeMillis(), live) {
        while (live != null) {
            value = System.currentTimeMillis()
            delay(15_000)
        }
    }
    val timeFormat = SimpleDateFormat("H:mm", Locale.JAPAN)

    val status = if (live != null) {
        "散策中　${WalkCompanion.minutes(now - live.startedAt)}分・" +
            "${WalkCompanion.km(live.distanceM)}km・案内${live.spotCount}か所"
    } else {
        "散策の振り返り"
    }

    val faint = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        shape = RoundedCornerShape(20.dp),
        shadowElevation = 4.dp,
        modifier = modifier.fillMaxWidth().animateContentSize(),
    ) {
        Column {
            if (scene != null) SceneHeader(scene, status)
            // Opened, the earlier lines scroll inside the card so the map stays in view.
            Column(
                Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())
                    .padding(16.dp, if (scene != null) 4.dp else 12.dp, 16.dp, 0.dp),
            ) {
                if (scene == null) {
                    Text(status, style = MaterialTheme.typography.labelLarge)
                }
                val shown = if (expanded) lines.take(6) else lines.take(1)
                if (shown.isEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "歩きはじめたら話しかけますね", style = MaterialTheme.typography.bodyMedium,
                    )
                }
                shown.forEach { line ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        buildString {
                            append(timeFormat.format(Date(line.at)))
                            line.spotName?.let { append("　").append(it) }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = faint,
                    )
                    if (line.text.isNotBlank()) {
                        Text(
                            line.text,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = if (expanded) Int.MAX_VALUE else 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    line.image?.let { image ->
                        Spacer(Modifier.height(4.dp))
                        MaterialImage(
                            image, Modifier.fillMaxWidth().heightIn(max = 160.dp).clip(RoundedCornerShape(8.dp)), maxPx = 800,
                        )
                    }
                    // An image's credit opens its source; a text's is only a line.
                    if (line.image != null) {
                        MaterialCredit(line.image, color = faint)
                    } else {
                        line.credit?.let {
                            Text(
                                it, style = MaterialTheme.typography.labelSmall,
                                color = faint,
                            )
                        }
                    }
                    line.detail?.let { detail -> TextButton(onClick = { onOpen(detail) }) { Text("詳しく見る") } }
                    // Opened lines only: a report is written while standing, not in passing.
                    if (expanded) line.channel?.let { ReportLink(it, line.at to line.text, "この発言を知らせる") }
                }
            }
            val latest = lines.firstOrNull()
            val canReplay = latest != null && latest.text.isNotBlank()
            if (canReplay || lines.size > 1) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (canReplay) {
                        TextButton(onClick = { onReplay(latest!!) }) {
                            Icon(Icons.Filled.Replay, contentDescription = null, Modifier.size(20.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("もう一度聞く")
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    if (lines.size > 1) {
                        TextButton(onClick = { expanded = !expanded }) {
                            Text(if (expanded) "閉じる" else "これまでの話", color = faint)
                            Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null, tint = faint)
                        }
                    }
                }
            } else {
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

/** The walk figures over the mood picture, with the mood in words in the corner. */
@Composable
private fun SceneHeader(scene: Mood, status: String) {
    Box(Modifier.fillMaxWidth().height(64.dp)) {
        MoodScene(scene, Modifier.matchParentSize())
        // Keeps the white figures readable over a bright sky.
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.25f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.55f))))
        val onScene = MaterialTheme.typography.labelLarge.copy(
            color = Color.White, shadow = Shadow(Color.Black.copy(alpha = 0.7f), blurRadius = 6f),
        )
        Text(
            scene.summary, style = onScene.copy(fontSize = 11.sp),
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp, 6.dp),
        )
        Text(status, style = onScene, modifier = Modifier.align(Alignment.BottomStart).padding(16.dp, 8.dp))
    }
}
