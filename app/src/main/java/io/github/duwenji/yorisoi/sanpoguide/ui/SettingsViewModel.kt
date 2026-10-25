package io.github.duwenji.yorisoi.sanpoguide.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.duwenji.yorisoi.sanpoguide.SanpoApp
import io.github.duwenji.yorisoi.kit.llm.GuideException
import io.github.duwenji.yorisoi.sanpoguide.guide.GuideRepository
import io.github.duwenji.yorisoi.kit.llm.Provider
import io.github.duwenji.yorisoi.kit.llm.RelayQuota
import io.github.duwenji.yorisoi.kit.llm.AiRoute
import io.github.duwenji.yorisoi.sanpoguide.prompt.Prompts
import io.github.duwenji.yorisoi.sanpoguide.settings.GuideSettings
import io.github.duwenji.yorisoi.kit.geo.map.MapStyle
import io.github.duwenji.yorisoi.sanpoguide.settings.TalkLevel
import io.github.duwenji.yorisoi.sanpoguide.settings.Threshold
import io.github.duwenji.yorisoi.sanpoguide.settings.Thresholds
import io.github.duwenji.yorisoi.kit.channel.Station
import io.github.duwenji.yorisoi.kit.channel.StationOverrides
import io.github.duwenji.yorisoi.core.format.GuideLength
import io.github.duwenji.yorisoi.core.format.SoundChoice
import io.github.duwenji.yorisoi.core.format.SpotKind
import io.github.duwenji.yorisoi.core.format.TalkEventKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface TestState {
    data object Idle : TestState
    data object Running : TestState
    data class Success(val reply: String) : TestState
    data class Failed(val message: String) : TestState
}

/** Edits a draft of the settings; nothing is persisted until [save], apart from developer mode and clearing the spot cache. */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = (application as SanpoApp).settings
    private val prompts = (application as SanpoApp).prompts
    private val stationRepo = (application as SanpoApp).stations
    private val spotCache = (application as SanpoApp).spotCache

    /** Today's AI calls, shown beside the limit. */
    val aiCallsToday: StateFlow<Int> = (application as SanpoApp).aiUsage.count

    private val relay = (application as SanpoApp).relay
    /** Whether this build can use the operator's AI (ADR-AI-RELAY). */
    val operatorAiAvailable: Boolean = relay != null
    /** The operator's AI: today's calls as the relay last reported them. */
    val relayQuota: StateFlow<RelayQuota?> = relay?.quota ?: MutableStateFlow(null)

    /** Asks the relay for today's calls, without using one; quietly keeps the last answer on failure. */
    fun refreshQuota() {
        val r = relay ?: return
        viewModelScope.launch {
            try {
                r.refreshQuota()
            } catch (e: GuideException) {
                // Shown by the connection test when the user asks.
            }
        }
    }

    /** Saved at once (not part of the draft). */
    val developerMode: StateFlow<Boolean> = repo.settings.map { it.developerMode }
        .stateIn(viewModelScope, SharingStarted.Eagerly, repo.settings.value.developerMode)
    fun setDeveloperMode(on: Boolean) = repo.setDeveloperMode(on)

    /** Every channel usable now, in the order to list them. */
    val stations: StateFlow<List<Station>> = stationRepo.list

    private val _draft = MutableStateFlow(repo.settings.value)
    val draft: StateFlow<GuideSettings> = _draft.asStateFlow()

    private val _test = MutableStateFlow<TestState>(TestState.Idle)
    val test: StateFlow<TestState> = _test.asStateFlow()
    private var testJob: Job? = null

    fun reset() {
        _draft.value = repo.settings.value
        clearTest()
    }

    fun selectRoute(route: AiRoute) = edit { it.copy(aiRoute = route) }
    fun selectProvider(p: Provider) = edit { it.copy(provider = p) }
    fun setApiKey(key: String) = edit { it.copy(apiKeys = it.apiKeys + (it.provider to key)) }
    fun setModel(model: String) = edit { it.copy(models = it.models + (it.provider to model)) }
    fun setCustomBaseUrl(url: String) = edit { it.copy(customBaseUrl = url) }
    fun selectStation(id: String) = edit { it.copy(stationId = id) }

    /** After the channels screen: takes the channel chosen there, keeping the rest of the draft. */
    fun takeSavedStation() = _draft.update { it.copy(stationId = repo.settings.value.stationId) }

    /** The chosen channel as the draft would have it, with the user's changes applied. */
    fun draftStation(settings: GuideSettings): Station = stationRepo.select(settings.channelChoice)

    // The chosen channel's settings (docs/channels.md「散策者のカスタマイズ」). Setting a value back to
    // the channel's own drops the change, so a later default still reaches it.
    fun setTalkLevel(level: TalkLevel) = editStation { _, o -> o.copy(talkLevel = level.level) }
    fun setEvent(kind: TalkEventKind, on: Boolean) = editStation { st, o -> o.copy(events = if (on) st.events + kind else st.events - kind) }
    fun setGuideLength(length: GuideLength) = editStation { _, o -> o.copy(guideLength = length) }
    fun setMoodTone(on: Boolean) = editStation { _, o -> o.copy(moodTone = on) }
    fun setSound(sound: SoundChoice) = editStation { _, o -> o.copy(sound = sound) }
    fun setPrefer(kind: SpotKind, on: Boolean) = editStation { st, o -> o.copy(prefer = if (on) st.prefer + kind else st.prefer - kind) }
    fun resetStation() = editStation { _, _ -> StationOverrides() }

    private fun editStation(change: (Station, StationOverrides) -> StationOverrides) = edit { s ->
        val station = stationRepo.select(s.channelChoice)
        val changed = station.normalize(change(station, station.overrides))
        s.copy(stationOverrides = (s.stationOverrides - station.id) + if (changed.isEmpty) emptyMap() else mapOf(station.id to changed))
    }
    fun setMoodEnabled(on: Boolean) = edit { it.copy(moodEnabled = on) }
    fun setAmbientEnabled(on: Boolean) = edit { it.copy(ambientEnabled = on) }
    fun setAmbientVolume(percent: Int) = edit { it.copy(ambientVolume = percent) }
    fun setAmbientEarphonesOnly(on: Boolean) = edit { it.copy(ambientEarphonesOnly = on) }
    fun setSpotPhotos(on: Boolean) = edit { it.copy(spotPhotos = on) }
    fun setPhotosOnMobileData(on: Boolean) = edit { it.copy(photosOnMobileData = on) }
    fun setDailyAiLimit(limit: Int?) = edit { it.copy(dailyAiLimit = limit) }

    fun setShareLocationWithAi(on: Boolean) = edit { it.copy(shareLocationWithAi = on) }
    fun setSpotCacheEnabled(on: Boolean) = edit { it.copy(spotCacheEnabled = on) }
    fun setSpotCacheFreshDays(days: Int) = edit { it.copy(spotCacheFreshDays = days) }

    private val _spotCacheCleared = MutableStateFlow(false)
    /** Whether the kept spot searches were cleared while on this screen. */
    val spotCacheCleared: StateFlow<Boolean> = _spotCacheCleared.asStateFlow()

    /** Clears the kept spot searches at once (not part of the draft). */
    fun clearSpotCache() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { spotCache.clear() }
            _spotCacheCleared.value = true
        }
    }
    fun setMapStyle(style: MapStyle) = edit { it.copy(mapStyle = style) }
    fun setGoogleMapsApiKey(key: String) = edit { it.copy(googleMapsApiKey = key) }

    /** [value] null (blank or not a number) is kept as invalid, so the field shows an error. */
    fun setThreshold(t: Threshold, value: Int?) = edit { it.copy(thresholds = it.thresholds.with(t, value ?: INVALID)) }
    fun resetThresholds() = edit { it.copy(thresholds = Thresholds()) }

    fun save() = repo.save(_draft.value)

    /** Whether the draft differs from what is saved; developer mode is saved at once, so it isn't a change. */
    fun hasChanges(): Boolean {
        val saved = repo.settings.value
        return _draft.value.copy(developerMode = saved.developerMode) != saved
    }

    fun runTest() {
        val settings = _draft.value
        if (settings.aiRoute == AiRoute.OPERATOR) {
            testRelay()
            return
        }
        if (!settings.isConfigured) {
            _test.value = TestState.Failed("API キーとモデル名（カスタムの場合は接続先 URL も）を入力してください")
            return
        }
        testJob?.cancel()
        testJob = viewModelScope.launch {
            _test.value = TestState.Running
            _test.value = try {
                val reply = GuideRepository.createClient(settings)
                    .generate(
                        prompts.render(Prompts.ConnectionTest.SYSTEM),
                        prompts.render(Prompts.ConnectionTest.USER),
                    )
                TestState.Success(reply.take(40))
            } catch (e: GuideException) {
                TestState.Failed(e.message.orEmpty())
            } catch (e: Exception) {
                TestState.Failed("接続に失敗しました: ${e.message}")
            }
        }
    }

    /** The operator's AI: asks for today's calls, which opens a session without using a call. */
    private fun testRelay() {
        val r = relay ?: run {
            _test.value = TestState.Failed("この版では運営の AI を使えません。自分の API キーを使ってください")
            return
        }
        testJob?.cancel()
        testJob = viewModelScope.launch {
            _test.value = TestState.Running
            _test.value = try {
                val q = r.refreshQuota()
                TestState.Success("今日の残り ${q.remaining} 回")
            } catch (e: GuideException) {
                TestState.Failed(e.message.orEmpty())
            }
        }
    }

    private fun edit(change: (GuideSettings) -> GuideSettings) {
        _draft.update(change)
        clearTest()
    }

    private fun clearTest() {
        testJob?.cancel()
        _test.value = TestState.Idle
    }

    companion object {
        /** Below every [Threshold.min]. */
        const val INVALID = -1
    }
}
