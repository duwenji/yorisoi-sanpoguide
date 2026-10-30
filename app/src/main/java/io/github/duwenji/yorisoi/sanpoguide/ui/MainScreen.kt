package io.github.duwenji.yorisoi.sanpoguide.ui

import io.github.duwenji.yorisoi.kit.llm.AiRoute
import io.github.duwenji.yorisoi.kit.geo.map.FacilityItem
import io.github.duwenji.yorisoi.kit.geo.map.MapTiles
import io.github.duwenji.yorisoi.kit.geo.map.RouteToSpot
import io.github.duwenji.yorisoi.kit.geo.map.SpotGroup
import io.github.duwenji.yorisoi.kit.geo.map.SpotItem
import io.github.duwenji.yorisoi.kit.geo.map.SpotMap
import io.github.duwenji.yorisoi.kit.geo.map.SpotFactsView
import io.github.duwenji.yorisoi.kit.geo.map.summaryLine
import io.github.duwenji.yorisoi.kit.geo.SpotFacts
import io.github.duwenji.yorisoi.kit.geo.map.facilityColor
import io.github.duwenji.yorisoi.kit.geo.map.rememberHeading
import io.github.duwenji.yorisoi.sanpoguide.R
import io.github.duwenji.yorisoi.kit.geo.FacilityKind
import io.github.duwenji.yorisoi.kit.geo.Poi
import io.github.duwenji.yorisoi.kit.geo.map.GoogleMapType
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Chair
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocalConvenienceStore
import androidx.compose.material.icons.filled.LocalDrink
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PedalBike
import androidx.compose.material.icons.filled.Roofing
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Wc
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import io.github.duwenji.yorisoi.kit.channel.material.Material
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

/** Something the user should know, and where a tap on it takes them ([onClick] null: nowhere). */
private class Notice(val text: String, val onClick: (() -> Unit)?)

/**
 * The map fills the screen; the companion, the nearest facilities and one notice float over its
 * top, and the nearby spots are in a sheet below it. Picking a spot, on the map or in the list,
 * turns the sheet into that spot's card.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    hasLocationPermission: Boolean,
    onRequestPermission: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onOpenVoiceSettings: () -> Unit,
    onToggleWalk: (walking: Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenChannels: () -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val liveWalk by viewModel.liveWalk.collectAsStateWithLifecycle()
    val lines by viewModel.companionLines.collectAsStateWithLifecycle()
    val location by viewModel.location.collectAsStateWithLifecycle()
    val spots by viewModel.spots.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val locationOff by viewModel.locationOff.collectAsStateWithLifecycle()
    val voiceMissing by viewModel.japaneseVoiceMissing.collectAsStateWithLifecycle()
    val walking by viewModel.walking.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val guideOpen by viewModel.guideOpen.collectAsStateWithLifecycle()
    val speaking by viewModel.speaking.collectAsStateWithLifecycle()
    val facilities by viewModel.facilities.collectAsStateWithLifecycle()
    val nearestFacilities by viewModel.nearestFacilities.collectAsStateWithLifecycle()
    val focus by viewModel.focus.collectAsStateWithLifecycle()
    val route by viewModel.route.collectAsStateWithLifecycle()
    val walked by viewModel.walked.collectAsStateWithLifecycle()
    val map by viewModel.map.collectAsStateWithLifecycle()
    val googleCopyright by viewModel.googleCopyright.collectAsStateWithLifecycle()
    val mood by viewModel.mood.collectAsStateWithLifecycle()
    val station by viewModel.station.collectAsStateWithLifecycle()
    val stations by viewModel.stations.collectAsStateWithLifecycle()
    val channelNotices by viewModel.channelNotices.collectAsStateWithLifecycle()
    val relayQuota by viewModel.relayQuota.collectAsStateWithLifecycle()

    val topBar = @Composable {
        MainTopBar(
            station = { StationPicker(station, stations, viewModel::chooseStation) },
            canRefresh = hasLocationPermission && !loading,
            onRefresh = viewModel::refresh,
            onOpenHistory = onOpenHistory,
            onOpenSettings = onOpenSettings,
        )
    }
    if (!hasLocationPermission) {
        Scaffold(topBar = topBar) { padding ->
            Box(Modifier.padding(padding)) { PermissionPrompt(onRequestPermission) }
        }
        return
    }

    // Most pressing first: only the first is shown, the rest behind "ほか n 件".
    val notices = buildList {
        error?.let { add(Notice(it, null)) }
        if (locationOff) add(Notice("端末の位置情報がオフのため、現在地を取得できません。タップして設定を開く", onOpenLocationSettings))
        if (voiceMissing) add(Notice("端末に日本語の読み上げ音声がないため、案内が日本語で読まれません。タップして音声を追加", onOpenVoiceSettings))
        if (!settings.isConfigured) {
            val why = if (settings.aiRoute == AiRoute.OPERATOR) "この版では運営の AI を使えない" else "AI の API キーが未設定の"
            add(Notice("${why}ため、簡易解説で動作しています。タップして設定", onOpenSettings))
        } else if (settings.aiRoute == AiRoute.OPERATOR && relayQuota?.exhausted() == true) {
            add(Notice("今日の運営の AI の回数を使い切りました。日付が変わるまで、決まった文で案内します。タップして設定", onOpenSettings))
        }
        map.notice?.let { add(Notice(it, onOpenSettings)) }
        channelNotices.lastOrNull()?.let { add(Notice("チャンネルのお知らせ: ${it.text}（タップして確認）", onOpenChannels)) }
    }
    var allNotices by remember { mutableStateOf(false) }

    var group by rememberSaveable { mutableStateOf<SpotGroup?>(null) }
    val shown = group?.let { g -> spots.filter { SpotGroup.of(it.poi.category) == g } } ?: spots
    var recenter by remember { mutableIntStateOf(0) }

    val scaffoldState = rememberBottomSheetScaffoldState()
    val sheet = scaffoldState.bottomSheetState
    val scope = rememberCoroutineScope()
    val expanded = sheet.targetValue == SheetValue.Expanded
    // The peek is measured: the card, or the list's top with its first rows (none while walking),
    // each with the navigation bar below it.
    var peekContent by remember { mutableIntStateOf(0) }
    val peek = SHEET_HANDLE_HEIGHT + with(LocalDensity.current) { peekContent.toDp() }

    // A spot picked from the opened list: back to the map, where it's now highlighted.
    LaunchedEffect(selected?.item?.poi?.id) { if (selected != null) sheet.partialExpand() }
    BackHandler(enabled = selected != null) { viewModel.dismiss() }
    BackHandler(enabled = selected == null && expanded) { scope.launch { sheet.partialExpand() } }

    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        topBar = topBar,
        sheetPeekHeight = peek,
        sheetShape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        sheetDragHandle = { SheetHandle() },
        // The card isn't a list to pull up.
        sheetSwipeEnabled = selected == null,
        sheetContent = {
            val picked = selected
            if (picked != null) {
                // The distance moves on as the user walks.
                val item = spots.firstOrNull { it.poi.id == picked.item.poi.id } ?: picked.item
                SpotCard(
                    item, picked.guide, speaking,
                    onListen = viewModel::listen,
                    onStop = viewModel::stopSpeaking,
                    onRead = viewModel::readGuide,
                    onClose = viewModel::dismiss,
                    modifier = Modifier.onSizeChanged { peekContent = it.height },
                )
            } else {
                SpotSheet(
                    spots, shown, group, onGroup = { group = it },
                    walking = walking,
                    showGroups = expanded,
                    onToggleWalk = { onToggleWalk(walking) },
                    onPick = viewModel::select,
                    onPeekMeasured = { peekContent = it },
                )
            }
        },
    ) { padding ->
        // The padding ends where the sheet begins: so does the map, keeping its center and its
        // attribution in view.
        Box(Modifier.padding(padding).fillMaxSize()) {
            Box(Modifier.fillMaxSize().clipToBounds()) {
                SpotMap(
                    tiles = map.tiles,
                    location = location,
                    heading = rememberHeading(location),
                    spots = shown,
                    facilities = facilities,
                    walked = walked,
                    route = route,
                    focus = focus,
                    onSpotClick = viewModel::select,
                    onViewportChanged = viewModel::onMapViewport,
                    modifier = Modifier.fillMaxSize(),
                    selected = selected?.item?.poi,
                    recenterRequests = recenter,
                    onTilesFailed = viewModel::onMapTilesFailed,
                )
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
                (map.tiles as? MapTiles.Google)?.let { tiles ->
                    GoogleAttribution(tiles, googleCopyright, Modifier.align(Alignment.BottomStart).fillMaxWidth())
                }
                route?.let {
                    RouteLabel(it, onClear = viewModel::clearRoute, modifier = Modifier.align(Alignment.BottomStart).padding(bottom = 28.dp))
                }
                SmallFloatingActionButton(
                    onClick = { recenter++ },
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 32.dp),
                ) {
                    Icon(Icons.Filled.MyLocation, contentDescription = "現在地に戻る")
                }
            }
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (walking || lines.isNotEmpty()) {
                    // The scene is part of walk mode, not something to look at while walking.
                    CompanionCard(
                        liveWalk, lines,
                        scene = mood.takeIf { settings.moodEnabled && walking },
                        onOpen = viewModel::openCue,
                        onReplay = viewModel::replay,
                        onDismiss = viewModel::dismissReview.takeIf { !walking },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
                if (nearestFacilities.isNotEmpty()) {
                    FacilityBar(nearestFacilities, onClick = { viewModel.focus(it.facility) })
                }
                notices.firstOrNull()?.let { first ->
                    NoticePill(
                        first, more = notices.size - 1,
                        onClick = if (notices.size > 1) ({ allNotices = true }) else first.onClick,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }
        }
    }

    if (allNotices) {
        ModalBottomSheet(onDismissRequest = { allNotices = false }) {
            NoticeList(notices, onDone = { allNotices = false })
        }
    }

    val openedCue by viewModel.openedCue.collectAsStateWithLifecycle()
    var fullscreen by remember { mutableStateOf<Material?>(null) }
    if (openedCue != null || fullscreen != null) {
        // Watched only while open: it keeps the location coming.
        val moving by viewModel.moving.collectAsStateWithLifecycle()
        openedCue?.let { detail ->
            ModalBottomSheet(onDismissRequest = viewModel::closeCue) {
                CueSheet(detail, moving, onFullscreen = { fullscreen = it })
            }
        }
        fullscreen?.let { m -> FullscreenImage(m, moving, onClose = { fullscreen = null }) }
    }

    selected?.takeIf { guideOpen }?.let { spot ->
        ModalBottomSheet(onDismissRequest = viewModel::closeGuide) {
            GuideSheet(
                spot = spot,
                onSpeak = { text -> viewModel.speak(spot.item.poi, text) },
                onStop = viewModel::stopSpeaking,
            )
        }
    }
}

/** The channel as the title; refreshing the search, seldom needed, waits in the menu. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainTopBar(
    station: @Composable () -> Unit,
    canRefresh: Boolean,
    onRefresh: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    TopAppBar(
        title = station,
        actions = {
            IconButton(onClick = onOpenHistory) {
                Icon(Icons.Filled.History, contentDescription = "散歩の記録")
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "設定")
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "その他")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("周辺を再検索") },
                        enabled = canRefresh,
                        onClick = { menu = false; onRefresh() },
                    )
                }
            }
        },
    )
}

@Composable
private fun SheetHandle() {
    Box(Modifier.fillMaxWidth().height(SHEET_HANDLE_HEIGHT), contentAlignment = Alignment.Center) {
        Box(Modifier.size(32.dp, 4.dp).background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp)))
    }
}

/**
 * The nearby spots, nearest first. Its top (and, outside a walk, the first rows) shows below the
 * map; pulled up, it can be narrowed to one [SpotGroup], which the map follows.
 */
@Composable
private fun SpotSheet(
    spots: List<SpotItem>,
    shown: List<SpotItem>,
    group: SpotGroup?,
    onGroup: (SpotGroup?) -> Unit,
    walking: Boolean,
    showGroups: Boolean,
    onToggleWalk: () -> Unit,
    onPick: (SpotItem) -> Unit,
    onPeekMeasured: (Int) -> Unit,
) {
    val density = LocalDensity.current
    val peekRows = if (walking) 0 else minOf(shown.size, PEEK_ROWS)
    val navigationBar = WindowInsets.navigationBars.getBottom(density)
    Column(Modifier.fillMaxWidth().fillMaxHeight(SHEET_MAX_FRACTION)) {
        Column(
            Modifier.onSizeChanged {
                onPeekMeasured(it.height + navigationBar + with(density) { (SPOT_ROW_HEIGHT * peekRows).roundToPx() })
            },
        ) {
            Row(Modifier.padding(start = 20.dp, end = 16.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("近くのスポット", style = MaterialTheme.typography.titleSmall)
                    Text(
                        listOfNotNull(
                            group?.let { "${it.label}のみ" },
                            "${shown.size} 件・近い順",
                            "引き上げて一覧".takeIf { walking && !showGroups },
                        ).joinToString("・"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (walking) {
                    FilledTonalButton(onClick = onToggleWalk, modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(Icons.Filled.Stop, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("散策を終える")
                    }
                }
            }
            if (!walking) {
                Button(
                    onClick = onToggleWalk,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp).height(52.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.DirectionsWalk, contentDescription = null)
                    Spacer(Modifier.width(10.dp))
                    Text("散策をはじめる", style = MaterialTheme.typography.titleSmall)
                }
            }
        }
        if (showGroups || group != null) GroupFilter(spots, group, onGroup)
        // The list is sorted by distance; when the nearest spot changes, show it again
        // instead of staying anchored to an item that has drifted down the list.
        // Another group is another list, read from its top.
        val listState = remember(group) { LazyListState() }
        LaunchedEffect(shown.firstOrNull()?.poi?.id) { listState.scrollToItem(0) }
        LazyColumn(
            state = listState, modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 16.dp + with(density) { navigationBar.toDp() }),
        ) {
            items(shown, key = { it.poi.id }) { item ->
                SpotRow(item, onClick = { onPick(item) })
            }
        }
    }
}

/** "すべて" and each group there are spots of, with how many. */
@Composable
private fun GroupFilter(spots: List<SpotItem>, group: SpotGroup?, onGroup: (SpotGroup?) -> Unit) {
    val counts = spots.groupingBy { SpotGroup.of(it.poi.category) }.eachCount()
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(selected = group == null, onClick = { onGroup(null) }, label = { Text("すべて") })
        SpotGroup.entries.filter { it in counts }.forEach { g ->
            FilterChip(
                selected = group == g,
                onClick = { onGroup(if (group == g) null else g) },
                label = { Text("${g.label} ${counts.getValue(g)}") },
                leadingIcon = { Box(Modifier.size(8.dp).background(g.color, CircleShape)) },
            )
        }
    }
}

/**
 * The picked spot: what its map data says in a line (kit-geo SpotFacts), then hear or read its
 * guide (written only now), or go back to the list.
 */
@Composable
private fun SpotCard(
    item: SpotItem,
    guide: GuideState,
    speaking: Boolean,
    onListen: () -> Unit,
    onStop: () -> Unit,
    onRead: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val poi = item.poi
    val facts = remember(poi.id) { SpotFacts.of(poi.tags) }
    Column(modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 8.dp, bottom = 20.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                Modifier.padding(top = 8.dp, end = 12.dp).size(12.dp)
                    .background(SpotGroup.of(poi.category).color, CircleShape),
            )
            Column(Modifier.weight(1f)) {
                Text(poi.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                facts.reading?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text(
                    listOfNotNull(poi.category, item.distanceM?.let { formatDistance(poi, it) }).joinToString("・"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                facts.summaryLine()?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                if (item.visitCount > 0) {
                    Text(
                        "これまでに ${item.visitCount} 回立ち寄りました", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "一覧に戻る")
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.padding(end = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val writing = guide == GuideState.Loading
            if (speaking) {
                Button(onClick = onStop, modifier = Modifier.weight(1f).height(48.dp)) {
                    Icon(Icons.Filled.Stop, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("止める")
                }
            } else {
                Button(onClick = onListen, enabled = !writing, modifier = Modifier.weight(1f).height(48.dp)) {
                    if (writing) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("準備しています")
                    } else {
                        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("解説を聞く")
                    }
                }
            }
            OutlinedButton(onClick = onRead, modifier = Modifier.weight(1f).height(48.dp)) {
                Text("解説を読む")
            }
        }
        if (guide is GuideState.Failed) {
            Text(
                guide.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp, end = 12.dp),
            )
        }
    }
}

/**
 * Required while Google tiles are shown (Map Tiles API policies): the Google Maps logo,
 * unmodified, 16–19dp tall with clear space around it, and the attribution for the area in full.
 * The outlined logo is the one for busy backgrounds like a map; light outline on the road map,
 * dark on photos.
 */
@Composable
internal fun GoogleAttribution(tiles: MapTiles.Google, copyright: String?, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        Image(
            painterResource(
                if (tiles.type == GoogleMapType.SATELLITE) R.drawable.google_maps_logo_dark_outline
                else R.drawable.google_maps_logo_light_outline,
            ),
            contentDescription = "Google マップ",
            modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 5.dp).height(18.dp),
        )
        Spacer(Modifier.weight(1f))
        copyright?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF202124),
                modifier = Modifier
                    .padding(start = 8.dp)
                    .background(Color.White.copy(alpha = 0.75f))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    }
}

/** Which spot the line on the map leads to, and a way to hide it. */
@Composable
private fun RouteLabel(route: RouteToSpot, onClear: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        shadowElevation = 2.dp,
        modifier = modifier.padding(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp)) {
            Column(Modifier.weight(1f, fill = false)) {
                Text(
                    "${route.spot.name}へ", style = MaterialTheme.typography.labelLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when {
                        route.loading -> "ルートを検索中…"
                        route.route.onPaths -> "道沿いのルート"
                        else -> "方向のみ（ルートを取得できませんでした）"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onClear) {
                Icon(Icons.Filled.Close, contentDescription = "ルートを消す")
            }
        }
    }
}

@Composable
private fun SpotRow(item: SpotItem, onClick: () -> Unit) {
    val color = SpotGroup.of(item.poi.category).color
    Row(
        Modifier.fillMaxWidth().heightIn(min = SPOT_ROW_HEIGHT).clickable(onClick = onClick).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The map marker's color, so a row and its dot can be matched.
        Box(Modifier.size(22.dp).background(color.copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
            Box(Modifier.size(12.dp).background(color, CircleShape))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(item.poi.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.poi.category, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (item.visitCount > 0) {
            Icon(
                Icons.Filled.CheckCircle, contentDescription = null,
                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                "${item.visitCount}回", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(10.dp))
        }
        item.distanceM?.let { Text(formatDistance(item.poi, it), style = MaterialTheme.typography.labelLarge) }
    }
}

@Composable
private fun GuideSheet(spot: SelectedSpot, onSpeak: (String) -> Unit, onStop: () -> Unit) {
    val poi = spot.item.poi
    val facts = remember(poi.id) { SpotFacts.of(poi.tags) }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 32.dp)
            .verticalScroll(rememberScrollState())
    ) {
        SpotPhotoView(spot.photo)
        Text(poi.name, style = MaterialTheme.typography.headlineSmall)
        facts.reading?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Text(
            listOfNotNull(poi.category, spot.item.distanceM?.let { formatDistance(poi, it) }).joinToString(" ・ "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // The map data's facts, there whether or not the AI's guide can be had.
        if (facts.details.isNotEmpty() || facts.links.isNotEmpty()) {
            SpotFactsView(facts, Modifier.padding(top = 16.dp))
            HorizontalDivider(Modifier.padding(top = 16.dp))
        }
        Spacer(Modifier.height(16.dp))
        when (val guide = spot.guide) {
            GuideState.NotAsked, GuideState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("ガイドが解説を準備しています…")
            }
            is GuideState.Failed -> Text(guide.message, color = MaterialTheme.colorScheme.error)
            is GuideState.Ready -> {
                Text(guide.text, style = MaterialTheme.typography.bodyLarge)
                guide.credit?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (guide.byAi) {
                    Text(
                        AI_MAY_BE_WRONG, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { onSpeak(guide.text) }) {
                        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("読み上げ")
                    }
                    OutlinedButton(onClick = onStop) { Text("停止") }
                }
            }
        }
    }
}

/** The spot's photo with its credit line (the license requires it); nothing when there's none. */
@Composable
private fun SpotPhotoView(state: PhotoState) {
    when (state) {
        PhotoState.None -> Unit
        PhotoState.Loading -> Text(
            "写真を読み込んでいます…", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp),
        )
        PhotoState.WaitingForWifi -> Text(
            "写真は Wi-Fi 接続時に表示します（設定で変更できます）", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp),
        )
        is PhotoState.Ready -> {
            val credit = state.credit
            val uriHandler = LocalUriHandler.current
            Image(
                state.image.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(12.dp)),
            )
            Text(
                listOfNotNull("写真: ${credit?.author ?: "作者不明"}", credit?.license, credit?.via).joinToString(" / "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().clickable { credit?.source?.let(uriHandler::openUri) }.padding(top = 4.dp, bottom = 12.dp),
            )
        }
    }
}

/** The nearest facility of each kind, over the map; tapping one pans the map to it. */
@Composable
private fun FacilityBar(items: List<FacilityItem>, onClick: (FacilityItem) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { item ->
            val kind = item.facility.kind
            AssistChip(
                onClick = { onClick(item) },
                label = { Text(listOfNotNull(kind.label, item.distanceM?.let(::formatMeters)).joinToString(" ")) },
                leadingIcon = {
                    // The map marker's rounded square, so the chips also serve as the map legend.
                    Box(Modifier.size(20.dp).background(facilityColor(kind), RoundedCornerShape(5.dp)), contentAlignment = Alignment.Center) {
                        Icon(facilityIcon(kind), contentDescription = null, Modifier.size(12.dp), tint = Color.White)
                    }
                },
                colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = AssistChipDefaults.assistChipElevation(elevation = 2.dp),
                border = null,
            )
        }
    }
}

private fun facilityIcon(kind: FacilityKind) = when (kind) {
    FacilityKind.TOILETS -> Icons.Filled.Wc
    FacilityKind.DRINKING_WATER -> Icons.Filled.WaterDrop
    FacilityKind.VENDING_MACHINE -> Icons.Filled.LocalDrink
    FacilityKind.SHELTER -> Icons.Filled.Roofing
    FacilityKind.BENCH -> Icons.Filled.Chair
    // A cyclist's kinds; the walk doesn't search them (kit-geo FacilityKind.WALK).
    FacilityKind.BICYCLE_PARKING, FacilityKind.BICYCLE_SHOP, FacilityKind.BICYCLE_PUMP -> Icons.Filled.PedalBike
    FacilityKind.CONVENIENCE_STORE -> Icons.Filled.LocalConvenienceStore
}

/** The most pressing notice, with how many more there are. */
@Composable
private fun NoticePill(notice: Notice, more: Int, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        shadowElevation = 2.dp,
        modifier = modifier.then(if (onClick != null) Modifier.clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick) else Modifier),
    ) {
        Row(Modifier.padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Info, contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                notice.text, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (more > 0) {
                Spacer(Modifier.width(8.dp))
                Text("ほか $more 件", style = MaterialTheme.typography.labelMedium)
            }
            if (onClick != null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, Modifier.size(18.dp))
        }
    }
}

@Composable
private fun NoticeList(notices: List<Notice>, onDone: () -> Unit) {
    Column(Modifier.padding(bottom = 24.dp)) {
        Text("お知らせ", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        notices.forEach { notice ->
            val onClick = notice.onClick
            ListItem(
                headlineContent = { Text(notice.text, style = MaterialTheme.typography.bodyMedium) },
                modifier = if (onClick != null) Modifier.clickable { onDone(); onClick() } else Modifier,
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun PermissionPrompt(onRequest: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("周辺のスポットを案内するには、位置情報の許可が必要です。")
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRequest) { Text("位置情報を許可する") }
        }
    }
}

private fun formatDistance(poi: Poi, m: Int): String = if (poi.isInside(m)) "敷地内" else formatMeters(m)

private fun formatMeters(m: Int): String = if (m < 1000) "${m}m" else "%.1fkm".format(m / 1000f)

private val SHEET_HANDLE_HEIGHT = 20.dp
private val SPOT_ROW_HEIGHT = 64.dp
/** Spots shown below the map outside a walk, before the list is pulled up. */
private const val PEEK_ROWS = 2
/** Pulled up, the list leaves the top of the map in view. */
private const val SHEET_MAX_FRACTION = 0.72f
