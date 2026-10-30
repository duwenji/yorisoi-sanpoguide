package io.github.duwenji.yorisoi.sanpoguide.ui

import android.location.Location
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.duwenji.yorisoi.kit.ui.ScreenBar
import io.github.duwenji.yorisoi.kit.geo.map.MapTiles
import io.github.duwenji.yorisoi.kit.geo.map.SpotItem
import io.github.duwenji.yorisoi.kit.geo.map.SpotMap
import io.github.duwenji.yorisoi.kit.geo.map.rememberHeading
import io.github.duwenji.yorisoi.sanpoguide.companion.PlannedStop

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalkPlanScreen(
    viewModel: MainViewModel,
    startError: String?,
    onBack: () -> Unit,
    onCancel: () -> Unit,
    onStartWithPlan: () -> Unit,
    onStartWithoutPlan: () -> Unit,
) {
    val plan by viewModel.planDraft.collectAsStateWithLifecycle()
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()
    val spots by viewModel.spots.collectAsStateWithLifecycle()
    val facilities by viewModel.facilities.collectAsStateWithLifecycle()
    val location by viewModel.location.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val locationOff by viewModel.locationOff.collectAsStateWithLifecycle()
    val map by viewModel.map.collectAsStateWithLifecycle()
    val googleCopyright by viewModel.googleCopyright.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            ScreenBar("散策プラン", onBack = onBack)
        },
        bottomBar = {
            Surface(shadowElevation = 4.dp) {
                Column(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    startError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                            Text("キャンセル")
                        }
                        TextButton(onClick = onStartWithoutPlan, modifier = Modifier.weight(1.7f)) {
                            Text("プランなしですぐ開始", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Button(onClick = onStartWithPlan, modifier = Modifier.fillMaxWidth()) {
                        Text("このプランで散策を開始")
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("テーマ", style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = plan.themeCategory == null,
                            onClick = { viewModel.updatePlan { it.copy(themeCategory = null) } },
                            label = { Text("指定なし") },
                        )
                        spots.map { it.poi.category }.distinct().sorted().forEach { category ->
                            FilterChip(
                                selected = plan.themeCategory == category,
                                onClick = { viewModel.updatePlan { it.copy(themeCategory = category) } },
                                label = { Text(category) },
                            )
                        }
                    }

                    Text("時間の目安", style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(null, 30, 60, 90).forEach { minutes ->
                            FilterChip(
                                selected = plan.durationMinutes == minutes,
                                onClick = { viewModel.updatePlan { it.copy(durationMinutes = minutes) } },
                                label = { Text(minutes?.let { "${it}分" } ?: "指定なし") },
                            )
                        }
                    }

                    Text("距離の目安", style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(null, 1, 3, 5).forEach { distance ->
                            FilterChip(
                                selected = plan.distanceKm == distance,
                                onClick = { viewModel.updatePlan { it.copy(distanceKm = distance) } },
                                label = { Text(distance?.let { "${it}km" } ?: "指定なし") },
                            )
                        }
                    }

                    OutlinedTextField(
                        value = plan.note.orEmpty(),
                        onValueChange = { note ->
                            viewModel.updatePlan { it.copy(note = note.takeUnless(String::isBlank)) }
                        },
                        label = { Text("メモ（端末内のみ）") },
                        supportingText = { Text("メモや立ち寄り先の詳細はAIに送信されません") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4,
                    )

                    Text(
                        "候補は立ち寄り先の提案です。道沿いのルートや所要時間を保証するものではありません。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider()
            }

            item {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("立ち寄り候補", style = MaterialTheme.typography.titleMedium)
                    when {
                        loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                        error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                        locationOff -> Text("位置情報がオフのため、周辺の候補を取得できません。設定を確認してください。")
                        suggestions.isEmpty() -> Text("周辺に候補スポットがありません。候補なしでも散策を開始できます。")
                        else -> {
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                suggestions.forEach { candidate ->
                                    val item = spots.firstOrNull { it.poi.id == candidate.poi.id }
                                    if (item != null) {
                                        val selected = plan.stops.any { it.poiId == candidate.poi.id }
                                        AssistChip(
                                            onClick = { viewModel.updatePlan { it.toggleStop(item.toPlannedStop()) } },
                                            label = { Text("${if (selected) "✓ " else ""}${candidate.poi.name}") },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (plan.stops.isNotEmpty()) {
                        Text("選択した立ち寄り先", style = MaterialTheme.typography.titleSmall)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            plan.stops.forEach { stop ->
                                AssistChip(
                                    onClick = { viewModel.updatePlan { it.toggleStop(stop) } },
                                    label = { Text("${stop.name} ×") },
                                )
                            }
                        }
                    }
                }
                HorizontalDivider()
            }

            item {
                map.notice?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                Box(Modifier.fillMaxWidth().height(240.dp)) {
                    SpotMap(
                        tiles = map.tiles,
                        location = location,
                        heading = rememberHeading(location),
                        spots = spots,
                        facilities = facilities,
                        walked = emptyList(),
                        route = null,
                        focus = null,
                        onSpotClick = { item -> viewModel.updatePlan { it.toggleStop(item.toPlannedStop()) } },
                        onViewportChanged = viewModel::onMapViewport,
                        modifier = Modifier.fillMaxSize(),
                    )
                    (map.tiles as? MapTiles.Google)?.let { tiles ->
                        GoogleAttribution(tiles, googleCopyright, Modifier.align(Alignment.BottomStart).fillMaxWidth())
                    }
                }
                Text(
                    "地図のスポットをタップして立ち寄り先に追加・解除できます。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                HorizontalDivider()
            }

            item {
                Text("周辺スポット", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
            }
            items(spots, key = { it.poi.id }) { item ->
                val selected = plan.stops.any { it.poiId == item.poi.id }
                ListItem(
                    modifier = Modifier.clickable { viewModel.updatePlan { it.toggleStop(item.toPlannedStop()) } },
                    headlineContent = { Text(item.poi.name) },
                    supportingContent = {
                        Text(buildString {
                            append(item.poi.category)
                            item.distanceM?.let { append("・${it}m") }
                        })
                    },
                    trailingContent = {
                        if (selected) Icon(Icons.Filled.CheckCircle, contentDescription = "選択済み")
                    },
                )
                HorizontalDivider()
            }
        }
    }
}

private fun SpotItem.toPlannedStop() = PlannedStop(
    poiId = poi.id,
    name = poi.name,
    category = poi.category,
    lat = poi.lat,
    lon = poi.lon,
)
