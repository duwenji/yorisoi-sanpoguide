package io.github.duwenji.yorisoi.sanpoguide.ui

import android.app.Application
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.duwenji.yorisoi.sanpoguide.BuildConfig
import io.github.duwenji.yorisoi.sanpoguide.SanpoApp
import io.github.duwenji.yorisoi.sanpoguide.companion.WalkCompanion
import io.github.duwenji.yorisoi.kit.ui.ScreenBar
import io.github.duwenji.yorisoi.kit.companion.SessionRecorder
import io.github.duwenji.yorisoi.sanpoguide.diagnostics.WalkExport
import io.github.duwenji.yorisoi.sanpoguide.history.WalkRecord
import io.github.duwenji.yorisoi.kit.geo.LatLon
import io.github.duwenji.yorisoi.kit.geo.map.NewWayFromTrip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as SanpoApp
    private val history = app.history
    private val stations = app.stations
    val walks = history.walks

    /** The export is for the developer, to review walks afterwards; it shows only in developer mode. */
    val developerMode = app.settings.settings.map { it.developerMode }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), app.settings.settings.value.developerMode)

    fun exportFileName(): String = WalkExport.fileName(SimpleDateFormat("yyyyMMdd-HHmm", Locale.JAPAN).format(Date()))

    /** Writes every walk, with its detailed record if it has one, to [uri]; [done] hears how it went. */
    fun export(uri: Uri, done: (String) -> Unit) {
        viewModelScope.launch {
            val message = try {
                val count = withContext(Dispatchers.IO) {
                    val walks = history.walks.value
                    val json = WalkExport.toJson(walks, app.diagnostics::read, BuildConfig.VERSION_NAME, System.currentTimeMillis())
                    val out = app.contentResolver.openOutputStream(uri) ?: error("Could not open $uri")
                    out.use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
                    walks.size
                }
                "${count}件の散歩を書き出しました"
            } catch (e: Exception) {
                "書き出せませんでした（${e.message}）"
            }
            done(message)
        }
    }

    fun stationsOf(walk: WalkRecord): String = stations.namesOf(walk.stations.map { it.station })

    /** A frequent way made from part of [walk] (platform REQ-FREQUENT-WAYS F-16). */
    fun saveWay(walk: WalkRecord, name: String, points: List<LatLon>) {
        app.ways.add(name, points, passedAt = walk.endedAt)
    }

    fun clearAll() {
        viewModelScope.launch {
            history.clear()
            // The ways found in the walks go with them; the ones saved stay (F-2).
            app.ways.clearFound()
            withContext(Dispatchers.IO) {
                app.diagnostics.clear()
                app.spotCache.clear()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(viewModel: HistoryViewModel, onClose: () -> Unit, onOpenWays: () -> Unit) {
    val walks by viewModel.walks.collectAsStateWithLifecycle()
    val developerMode by viewModel.developerMode.collectAsStateWithLifecycle()
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var makingWay by rememberSaveable { mutableStateOf<Long?>(null) }
    val context = LocalContext.current
    walks.find { it.id == makingWay }?.let { walk ->
        BackHandler { makingWay = null }
        Scaffold { padding ->
            Column(Modifier.padding(padding).padding(16.dp)) {
                NewWayFromTrip(
                    walk.route,
                    onSave = { name, points ->
                        viewModel.saveWay(walk, name, points)
                        makingWay = null
                        Toast.makeText(context, "「$name」をよく通る道にしました", Toast.LENGTH_SHORT).show()
                    },
                    onCancel = { makingWay = null },
                )
            }
        }
        return
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { viewModel.export(it) { message -> Toast.makeText(context, message, Toast.LENGTH_LONG).show() } }
    }

    Scaffold(
        topBar = {
            ScreenBar(
                "散歩の記録", onBack = onClose,
                actions = {
                    TextButton(onClick = onOpenWays) { Text("よく通る道") }
                    if (walks.isNotEmpty() && developerMode) {
                        IconButton(onClick = { exporter.launch(viewModel.exportFileName()) }) {
                            Icon(Icons.Filled.FileDownload, contentDescription = "記録を書き出す（開発者向け）")
                        }
                    }
                    if (walks.isNotEmpty()) {
                        IconButton(onClick = { confirmClear = true }) {
                            Icon(Icons.Filled.DeleteOutline, contentDescription = "記録をすべて削除")
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (walks.isEmpty()) {
            Text(
                "まだ記録はありません。「散策をはじめる」で散歩すると、ここに残ります。",
                modifier = Modifier.padding(padding).padding(24.dp),
            )
            return@Scaffold
        }
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { Summary(walks) }
            items(walks, key = { it.id }) { walk ->
                WalkRow(walk, viewModel.stationsOf(walk))
                if (walk.route.size >= 2) {
                    TextButton(onClick = { makingWay = walk.id }, modifier = Modifier.padding(start = 8.dp)) {
                        Text("この散歩からよく通る道を作る")
                    }
                }
                HorizontalDivider()
            }
            item {
                Text(
                    if (developerMode) {
                        "記録はこの端末内にだけ保存されます。開発者モードでは、散歩ごとの詳しい記録（位置・判定・AI とのやり取り）も最新 ${SessionRecorder.MAX_SESSIONS} 件まで残り、右上のボタンで書き出せます。"
                    } else {
                        "記録はこの端末内にだけ保存されます。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("記録をすべて削除しますか？") },
            text = { Text("散歩の履歴と訪れた場所の記録が消え、元に戻せません。") },
            confirmButton = {
                TextButton(onClick = { viewModel.clearAll(); confirmClear = false }) { Text("削除") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("キャンセル") } },
        )
    }
}

@Composable
private fun Summary(walks: List<WalkRecord>) {
    val totalKm = WalkCompanion.km(walks.sumOf { it.distanceM })
    val totalMin = WalkCompanion.minutes(walks.sumOf { it.durationMs })
    val favorites = walks.flatMap { w -> w.visits.distinctBy { it.name } }
        .groupBy { it.name }
        .map { (_, v) -> v.first().name to v.size }
        .filter { it.second >= 2 }
        .sortedByDescending { it.second }
        .take(3)

    Card(Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("これまでの散歩", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Stat("回数", "${walks.size}回")
                Stat("距離", "${totalKm}km")
                Stat("時間", formatDuration(totalMin))
            }
            if (favorites.isNotEmpty()) {
                Text(
                    "よく訪れる場所：" + favorites.joinToString("、") { (name, n) -> "$name（${n}回）" },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun WalkRow(walk: WalkRecord, stations: String) {
    val date = SimpleDateFormat("M月d日（E）H:mm", Locale.JAPAN).format(Date(walk.startedAt))
    ListItem(
        headlineContent = { Text(date) },
        supportingContent = {
            val details = buildList {
                add("${WalkCompanion.km(walk.distanceM)}km・${formatDuration(WalkCompanion.minutes(walk.durationMs))}")
                add("チャンネル: $stations")
                if (walk.visits.isNotEmpty()) add(walk.visits.joinToString("、") { it.name })
                walk.plan.themeCategory?.takeIf { it.isNotBlank() }?.let { add("テーマ: $it") }
                walk.plan.durationMinutes?.let { add("時間の目安: ${it}分") }
                walk.plan.distanceKm?.let { add("距離の目安: ${it}km") }
                walk.plan.stops.takeIf { it.isNotEmpty() }
                    ?.let { stops -> add("立ち寄り先: ${stops.joinToString("、") { it.name }}") }
                walk.plan.note?.takeIf { it.isNotBlank() }?.let { add("メモ: $it") }
                // The frequent ways gone along, and those this walk made (platform REQ-FREQUENT-WAYS F-12, D-12).
                walk.ways.filter { it.new }.forEach { add("よく通る道にしました: ${it.name}") }
                walk.ways.filter { !it.new }.takeIf { it.isNotEmpty() }
                    ?.let { ways -> add("よく通る道: " + ways.joinToString("、") { "${it.name}（${it.passes}回目）" }) }
            }.joinToString("\n")
            Text(details)
        },
    )
}

private fun formatDuration(minutes: Int): String =
    if (minutes < 60) "${minutes}分" else "${minutes / 60}時間${minutes % 60}分"
