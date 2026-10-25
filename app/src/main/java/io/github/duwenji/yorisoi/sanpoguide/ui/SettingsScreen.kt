package io.github.duwenji.yorisoi.sanpoguide.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.duwenji.yorisoi.sanpoguide.BuildConfig
import io.github.duwenji.yorisoi.kit.llm.Provider
import io.github.duwenji.yorisoi.kit.geo.map.MapStyle
import io.github.duwenji.yorisoi.kit.llm.AiRoute
import io.github.duwenji.yorisoi.sanpoguide.settings.GuideSettings
import io.github.duwenji.yorisoi.sanpoguide.settings.TalkLevel
import io.github.duwenji.yorisoi.sanpoguide.settings.Threshold
import io.github.duwenji.yorisoi.sanpoguide.settings.Thresholds
import io.github.duwenji.yorisoi.kit.channel.Station
import io.github.duwenji.yorisoi.sanpoguide.station.StationLabels
import io.github.duwenji.yorisoi.core.format.GuideLength
import io.github.duwenji.yorisoi.core.format.SoundChoice
import io.github.duwenji.yorisoi.core.format.SpotKind
import io.github.duwenji.yorisoi.core.format.TalkEventKind
import kotlin.math.roundToInt

/**
 * The app's settings, edited as a draft and saved from the top bar. Leaving with changes asks first.
 * Sections: the AI, the channel, the safety and amenity remarks, screen and sound, the map, what stays
 * on the device, and the app itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onClose: () -> Unit, onOpenChannels: () -> Unit) {
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    LaunchedEffect(draft.aiRoute) { if (draft.aiRoute == AiRoute.OPERATOR) viewModel.refreshQuota() }

    val thresholdsValid = draft.thresholds.values.all { (t, v) -> t.isValid(v) }
    val conflicts = draft.thresholds.conflicts()
    val canSave = thresholdsValid && conflicts.isEmpty()
    fun save() {
        viewModel.save()
        onClose()
    }

    var askLeave by rememberSaveable { mutableStateOf(false) }
    fun leave() {
        if (viewModel.hasChanges()) askLeave = true else onClose()
    }
    BackHandler(onBack = ::leave)
    if (askLeave) {
        AlertDialog(
            onDismissRequest = { askLeave = false },
            title = { Text("変更を保存しますか") },
            text = {
                Text(
                    if (canSave) "保存しないで戻ると、この画面で変えた内容は元に戻ります。"
                    else "入力に誤りがあるため、保存できません。保存しないで戻ると、この画面で変えた内容は元に戻ります。",
                )
            },
            confirmButton = {
                if (canSave) TextButton(onClick = ::save) { Text("保存して戻る") }
                else TextButton(onClick = { askLeave = false }) { Text("入力を直す") }
            },
            dismissButton = { TextButton(onClick = onClose) { Text("保存しないで戻る") } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("設定") },
                navigationIcon = {
                    IconButton(onClick = ::leave) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = { TextButton(onClick = ::save, enabled = canSave) { Text("保存") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val station = viewModel.draftStation(draft)
            AiSection(draft, viewModel)
            ChannelSection(station, viewModel, onOpenChannels)
            RemarksSection(draft.thresholds, thresholdsValid, conflicts, viewModel)
            ScreenAndSoundSection(draft, station, viewModel)
            MapSection(draft, viewModel)
            DeviceDataSection(draft, viewModel)
            AboutSection(viewModel)
        }
    }
}

/** Which AI is used and how to reach it, what is sent to it, and a test of the connection. */
@Composable
private fun AiSection(draft: GuideSettings, viewModel: SettingsViewModel) {
    val test by viewModel.test.collectAsStateWithLifecycle()
    Section("AI", first = true) {
        Note(AI_MAY_BE_WRONG)
        AiRouteChoice(draft.aiRoute, viewModel)
        if (draft.aiRoute == AiRoute.OWN_KEY) OwnKeySettings(draft, viewModel)
        OutlinedButton(onClick = viewModel::runTest, enabled = test != TestState.Running) { Text("接続テスト") }
        TestResult(test)
        SwitchRow(
            "AI に緯度経度と歩いた経路を送る",
            "オンにすると、スポットと現在地の緯度経度、今回歩いた経路（間引いたもの）も AI に送り、" +
                "場所に即した解説や話しかけにします。オフのときは、スポット名や距離などだけを送ります",
            draft.shareLocationWithAi, viewModel::setShareLocationWithAi,
        )
        Note(
            if (draft.aiRoute == AiRoute.OPERATOR) {
                "解説と話しかけに使う情報は、運営の中継を通って AI サービスに送られます。中継は内容を保存しません。"
            } else {
                "API キーはこの端末内に暗号化して保存し、選んだ AI サービスへの通信にだけ使います。" +
                    "利用料金は、その API キーのアカウントに請求されます。"
            },
        )
    }
}

/**
 * How the AI is reached (platform ADR-AI-RELAY R-1, R-11): the operator's AI or the user's own key.
 * A build without a relay shows nothing: only the own key, as before.
 */
@Composable
private fun AiRouteChoice(route: AiRoute, viewModel: SettingsViewModel) {
    if (!viewModel.operatorAiAvailable) return
    val quota by viewModel.relayQuota.collectAsStateWithLifecycle()
    Column(Modifier.selectableGroup()) {
        RouteOption(
            selected = route == AiRoute.OPERATOR,
            title = GuideSettings.OPERATOR_AI_NAME + "（設定不要）",
            detail = "運営が費用を持ち、1 日に使える回数を運営が決めています。" +
                (quota?.let { "今日の残りは ${it.remaining} 回（1 日 ${it.limit} 回まで）。" }.orEmpty()) +
                "使い切った日は、決まった文で案内します",
            onClick = { viewModel.selectRoute(AiRoute.OPERATOR) },
        )
        RouteOption(
            selected = route == AiRoute.OWN_KEY,
            title = "自分の API キー",
            detail = "選んだ AI サービスに端末から直接つなぎます。運営の中継を通らず、利用料はそのキーのアカウントに請求されます",
            onClick = { viewModel.selectRoute(AiRoute.OWN_KEY) },
        )
    }
}

@Composable
private fun RouteOption(selected: Boolean, title: String, detail: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The service, key, model and daily limit for the user's own key (platform ADR-004 H-9). */
@Composable
private fun OwnKeySettings(draft: GuideSettings, viewModel: SettingsViewModel) {
    val provider = draft.provider
    Label("AI サービス")
    Column(Modifier.selectableGroup()) {
        Provider.entries.forEach { p ->
            Row(
                Modifier.fillMaxWidth()
                    .selectable(p == provider, role = Role.RadioButton) { viewModel.selectProvider(p) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = p == provider, onClick = null)
                Spacer(Modifier.width(12.dp))
                Text(p.label, modifier = Modifier.weight(1f))
                if (draft.apiKey(p).isNotBlank()) {
                    Text("キー設定済み", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }

    Label("API キー")
    ApiKeyField(value = draft.apiKey(), onValueChange = viewModel::setApiKey)
    if (provider.keyPageUrl.isNotEmpty()) {
        val uriHandler = LocalUriHandler.current
        TextButton(onClick = { uriHandler.openUri(provider.keyPageUrl) }) { Text("${provider.label} の API キーを取得する") }
    }

    if (provider == Provider.CUSTOM) {
        Label("接続先 URL")
        OutlinedTextField(
            value = draft.customBaseUrl,
            onValueChange = viewModel::setCustomBaseUrl,
            placeholder = { Text("https://example.com/v1") },
            supportingText = { Text("/chat/completions の手前までを入力します") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
    }

    Label("モデル")
    if (provider == Provider.CLAUDE) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Provider.CLAUDE_MODELS.forEach { m ->
                FilterChip(selected = draft.model() == m, onClick = { viewModel.setModel(m) }, label = { Text(m) })
            }
        }
    } else {
        OutlinedTextField(
            value = draft.models[provider].orEmpty(),
            onValueChange = viewModel::setModel,
            placeholder = { Text(provider.defaultModel.ifEmpty { "モデル名" }) },
            supportingText = if (provider.defaultModel.isNotEmpty()) {
                { Text("空欄の場合は ${provider.defaultModel} を使います") }
            } else null,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    Label("1 日の上限")
    val callsToday by viewModel.aiCallsToday.collectAsStateWithLifecycle()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GuideSettings.DAILY_AI_LIMITS.forEach { limit ->
            FilterChip(
                selected = draft.dailyAiLimit == limit,
                onClick = { viewModel.setDailyAiLimit(limit) },
                label = { Text(limit?.let { "$it 回" } ?: "上限なし") },
            )
        }
    }
    Note(
        "今日は $callsToday 回使いました。上限に達すると、その日は AI の解説と話しかけを止め、決まった文で案内します。" +
            "チャンネルが決める話す量も、この上限の中で動きます。",
    )
}

@Composable
private fun TestResult(test: TestState) {
    when (test) {
        TestState.Idle -> Unit
        TestState.Running -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text("接続を確認しています…")
        }
        is TestState.Success -> Text("接続できました（応答: ${test.reply}）", color = MaterialTheme.colorScheme.primary)
        is TestState.Failed -> Text(test.message, color = MaterialTheme.colorScheme.error)
    }
}

/** The channel in use and, for a built-in one, the user's changes to it (docs/channels.md「散策者のカスタマイズ」). */
@Composable
private fun ChannelSection(station: Station, viewModel: SettingsViewModel, onOpenChannels: () -> Unit) {
    val stations by viewModel.stations.collectAsStateWithLifecycle()
    val developerMode by viewModel.developerMode.collectAsStateWithLifecycle()
    Section("チャンネル") {
        Note(
            "チャンネルによって、語り手や話題、話しかける場面が変わります。" +
                "天気の急変・日の入り・施設の知らせは、どのチャンネルでも、AI の上限に達したあとも続けます。",
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            stations.forEach { s ->
                FilterChip(selected = station.id == s.id, onClick = { viewModel.selectStation(s.id) }, label = { Text(s.displayName) })
            }
        }
        Note(station.manifest.summary)
        station.source?.let { source ->
            Note("チャンネル提供者: ${source.publisherName}（リスト: ${source.providerName}）。話しかけの頻度や場面はチャンネル提供者が決めます")
        }
        // Built-in channels only until a list runs in production: lists and test tickets are for developer mode.
        if (developerMode) OutlinedButton(onClick = onOpenChannels) { Text("チャンネル・リストと試用チケット") }
        // A third party's channel is as its publisher made it (ADR-001): nothing to adjust here.
        if (station.isBuiltIn) StationSettings(station, viewModel)
    }
}

/** The chosen built-in channel's own settings, saved per channel. Its voice and topics can't be changed. */
@Composable
private fun StationSettings(station: Station, viewModel: SettingsViewModel) {
    Label("話しかけの頻度（${station.name}）")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TalkLevel.entries.forEach { level ->
            FilterChip(selected = station.talkLevel == level.level, onClick = { viewModel.setTalkLevel(level) }, label = { Text(level.label) })
        }
    }
    Note("スポットの案内のほかに、歩いた距離や時間、休憩のときに話しかける頻度です。ここから下はチャンネルごとに保存します。")

    Label("話しかける場面")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TalkEventKind.entries.forEach { kind ->
            FilterChip(
                selected = station.talksOn(kind),
                onClick = { viewModel.setEvent(kind, !station.talksOn(kind)) },
                label = { Text(StationLabels.of(kind)) },
            )
        }
    }

    Label("解説の長さ")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GuideLength.entries.forEach { length ->
            FilterChip(
                selected = station.guideLength == length,
                onClick = { viewModel.setGuideLength(length) },
                label = { Text(StationLabels.of(length)) },
            )
        }
    }

    Label("優先する話題")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SpotKind.entries.forEach { kind ->
            val on = kind in station.prefer
            FilterChip(selected = on, onClick = { viewModel.setPrefer(kind, !on) }, label = { Text(kind.category) })
        }
    }
    Note(
        "近くに複数のスポットがあるとき、選んだ種類から先に話題にします。" +
            "飲食店から専門店までは、選んだときだけ探して地図と一覧に出します（チェーン店は除きます）。",
    )

    SwitchRow(
        "話し方を雰囲気に合わせる", "時間帯・季節・天気・場所に合わせて、話しかけのトーンを変えます",
        station.moodTone, viewModel::setMoodTone,
    )

    TextButton(onClick = viewModel::resetStation, enabled = !station.overrides.isEmpty) {
        Text("「${station.name}」を初期値に戻す")
    }
}

/** When the weather, sunset and amenity remarks come, which no channel can turn off. */
@Composable
private fun RemarksSection(thresholds: Thresholds, valid: Boolean, conflicts: List<String>, viewModel: SettingsViewModel) {
    Section("天気・日の入り・施設の知らせ") {
        Note(
            "どんなときに知らせるかです。" +
                "施設の距離は、周辺の検索範囲（ベンチ・自動販売機は 250m、ほかは 600m）より遠くは案内できません。",
        )
        ThresholdFields(thresholds, onChange = viewModel::setThreshold)
        if (!valid) Text("範囲外の値があります", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        conflicts.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        TextButton(onClick = viewModel::resetThresholds, enabled = thresholds.values.isNotEmpty()) {
            Text("既定値に戻す")
        }
    }
}

/** One number field per [Threshold], under its group's heading. */
@Composable
private fun ThresholdFields(thresholds: Thresholds, onChange: (Threshold, Int?) -> Unit) {
    Threshold.entries.groupBy { it.group }.forEach { (group, items) ->
        Label(group)
        items.forEach { t ->
            val value = thresholds.values[t] ?: t.default
            val valid = t.isValid(value)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t.label, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "既定値 ${t.default}${t.unit}（${t.min}〜${t.max}）",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (valid) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.width(12.dp))
                ThresholdField(t, value, valid, onChange = { onChange(t, it) })
            }
        }
    }
}

@Composable
private fun ThresholdField(t: Threshold, value: Int, valid: Boolean, onChange: (Int?) -> Unit) {
    fun textOf(v: Int) = if (v == SettingsViewModel.INVALID) "" else v.toString()
    // The field keeps its own text: rewriting "08" to "8" under the cursor made it jump.
    var text by remember(t) { mutableStateOf(textOf(value)) }
    // Follow changes from outside the field (reset to defaults), not the field's own edits.
    LaunchedEffect(value) {
        if ((text.toIntOrNull() ?: SettingsViewModel.INVALID) != value) text = textOf(value)
    }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input.filter(Char::isDigit).take(4)
            onChange(text.toIntOrNull())
        },
        suffix = { Text(t.unit) },
        isError = !valid,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.width(120.dp),
    )
}

/** The screen's mood, the background sound (its kind is the built-in channel's), and spot photos. */
@Composable
private fun ScreenAndSoundSection(draft: GuideSettings, station: Station, viewModel: SettingsViewModel) {
    Section("画面と音") {
        SwitchRow(
            "画面を雰囲気に合わせる", "時間帯・季節・天気・場所に合わせて、画面の色と散策中の上部の絵を変えます",
            draft.moodEnabled, viewModel::setMoodEnabled,
        )
        SwitchRow(
            "散策中に背景音を流す",
            "雨音・波・鳥の声・虫の音などを、その場の雰囲気に合わせて流します。" +
                "読み上げ中は小さくし、ほかのアプリで音楽などを再生しているときは流しません",
            draft.ambientEnabled, viewModel::setAmbientEnabled,
        )
        if (draft.ambientEnabled) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("音量", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(48.dp))
                Slider(
                    value = draft.ambientVolume.toFloat(),
                    onValueChange = { viewModel.setAmbientVolume(it.roundToInt()) },
                    valueRange = 0f..100f,
                    modifier = Modifier.weight(1f),
                )
                Text("${draft.ambientVolume}%", modifier = Modifier.width(48.dp), textAlign = TextAlign.End)
            }
            SwitchRow(
                "イヤホンのときだけ流す", "オフにすると、スマートフォンのスピーカーからも流します",
                draft.ambientEarphonesOnly, viewModel::setAmbientEarphonesOnly,
            )
            if (station.isBuiltIn) {
                Label("背景音の種類（${station.name}）")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SoundChoice.entries.forEach { sound ->
                        FilterChip(
                            selected = station.sound == sound,
                            onClick = { viewModel.setSound(sound) },
                            label = { Text(StationLabels.of(sound)) },
                        )
                    }
                }
                Note("チャンネルごとに保存します。「自動」は雰囲気に合わせて選びます。")
            }
            Text(
                "車や自転車の近づく音が聞こえる音量にしてください。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        SwitchRow(
            "スポットの写真を表示する", "解説の画面に Wikimedia Commons の写真を表示します（写真が登録されているスポットのみ）",
            draft.spotPhotos, viewModel::setSpotPhotos,
        )
        if (draft.spotPhotos) {
            SwitchRow(
                "モバイル通信でも写真を取得する", "オフのときは Wi-Fi などの定額の回線でだけ取得します",
                draft.photosOnMobileData, viewModel::setPhotosOnMobileData,
            )
        }
    }
}

@Composable
private fun MapSection(draft: GuideSettings, viewModel: SettingsViewModel) {
    Section("地図") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MapStyle.entries.forEach { style ->
                FilterChip(selected = draft.mapStyle == style, onClick = { viewModel.setMapStyle(style) }, label = { Text(style.label) })
            }
        }
        Note(draft.mapStyle.description)
        if (draft.mapStyle.needsGoogleKey) {
            Label("Google Maps Platform の API キー")
            ApiKeyField(value = draft.googleMapsApiKey, onValueChange = viewModel::setGoogleMapsApiKey)
            Note(
                "Google Cloud でプロジェクトに請求先を登録し、Map Tiles API を有効にしてからキーを作成してください。" +
                    "表示した地図の量に応じて、そのアカウントに料金がかかることがあります（無料枠あり）。" +
                    "キーは「API の制限」で Map Tiles API だけに絞り、割り当て（上限）も設定しておくと安全です。" +
                    "地図を表示すると、表示している場所の地図画像を Google から取得します。",
            )
            val uriHandler = LocalUriHandler.current
            TextButton(onClick = { uriHandler.openUri(MAP_TILES_API_URL) }) { Text("Map Tiles API を有効にする（Google Cloud）") }
            if (draft.googleMapsApiKey.isBlank()) {
                Text(
                    "キーが未設定のあいだは、地理院の標準地図で表示します。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        Note("ルートの表示では、AI の設定にかかわらず、現在地とスポットの緯度経度を経路検索サービス（OpenStreetMap）に送ります。")
    }
}

private const val MAP_TILES_API_URL = "https://console.cloud.google.com/apis/library/tile.googleapis.com"

/** What the app keeps on the device: the spot searches (kit-geo SpotCache) and the walks. */
@Composable
private fun DeviceDataSection(draft: GuideSettings, viewModel: SettingsViewModel) {
    Section("端末に残すデータ") {
        SwitchRow(
            "スポット検索の結果を残す",
            "近くで検索した結果があれば、OpenStreetMap に問い合わせずに使います。" +
                "問い合わせは混んでいて時間がかかることが多いため、オンのほうが早く表示できます。" +
                "検索に失敗したときも、残っている結果を表示します",
            draft.spotCacheEnabled, viewModel::setSpotCacheEnabled,
        )
        if (draft.spotCacheEnabled) {
            Label("問い合わせずに使う期間")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GuideSettings.SPOT_CACHE_FRESH_DAYS.forEach { days ->
                    FilterChip(
                        selected = draft.spotCacheFreshDays == days,
                        onClick = { viewModel.setSpotCacheFreshDays(days) },
                        label = { Text("$days 日") },
                    )
                }
            }
            Note("これより古い結果は、問い合わせのあいだだけ表示します。結果は最長 90 日残します。")
        }
        val spotCacheCleared by viewModel.spotCacheCleared.collectAsStateWithLifecycle()
        TextButton(onClick = viewModel::clearSpotCache, enabled = !spotCacheCleared) {
            Text(if (spotCacheCleared) "スポット検索の結果を消しました" else "スポット検索の結果を消す")
        }
        Note(
            "残した結果から、おおよそ歩いた場所が分かります。散歩の記録をすべて削除したときも一緒に消えます。" +
                "散歩の記録は、この端末内にだけ保存します。",
        )
    }
}

/**
 * The sources and the version; tapping the version seven times turns developer mode on (as Android's
 * own), for publishers trying their channel from a test ticket before review (API-002 試用チケット).
 */
@Composable
private fun AboutSection(viewModel: SettingsViewModel) {
    val developerMode by viewModel.developerMode.collectAsStateWithLifecycle()
    var taps by remember { mutableIntStateOf(0) }
    Section("このアプリについて") {
        Note(
            "地図: 国土地理院　スポット: © OpenStreetMap contributors　天気: Open-Meteo.com　" +
                "写真: Wikimedia Commons（作者・ライセンスは各写真の下に表示）",
        )
        Text(
            "バージョン ${BuildConfig.VERSION_NAME}" + when {
                developerMode -> "（開発者モード）"
                taps in 3..6 -> "（あと ${7 - taps} 回で開発者モード）"
                else -> ""
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().clickable(enabled = !developerMode) {
                taps += 1
                if (taps >= 7) viewModel.setDeveloperMode(true)
            }.padding(vertical = 8.dp),
        )
        if (developerMode) {
            SwitchRow(
                "開発者モード",
                "チャンネル提供者が審査の前に、自分のチャンネルを試用チケット（QR コード）で試すためのものです。" +
                    "チャンネルの欄から読み込みます。オフにすると、試用中のチャンネルは使えなくなります",
                true,
            ) { on ->
                if (!on) {
                    taps = 0
                    viewModel.setDeveloperMode(false)
                }
            }
        }
    }
}

@Composable
private fun ApiKeyField(value: String, onValueChange: (String) -> Unit) {
    var visible by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text("API キーを貼り付け") },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (visible) "キーを隠す" else "キーを表示",
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SwitchRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** A section of the screen under its title, set off from the one above by a line. */
@Composable
private fun Section(title: String, first: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    if (!first) HorizontalDivider(Modifier.padding(top = 8.dp))
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

/** The heading of one setting within a section. */
@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
