package io.github.duwenji.yorisoi.sanpoguide.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.duwenji.yorisoi.sanpoguide.SanpoApp
import io.github.duwenji.yorisoi.sanpoguide.report.Report
import io.github.duwenji.yorisoi.sanpoguide.report.ReportTarget
import io.github.duwenji.yorisoi.sanpoguide.settings.AiRoute
import io.github.duwenji.yorisoi.sanpoguide.station.AiCost
import io.github.duwenji.yorisoi.sanpoguide.station.BuiltInStations
import io.github.duwenji.yorisoi.sanpoguide.station.remote.ChannelNotice
import io.github.duwenji.yorisoi.sanpoguide.station.remote.ChannelSyncException
import io.github.duwenji.yorisoi.sanpoguide.station.remote.ProviderView
import io.github.duwenji.yorisoi.sanpoguide.station.remote.TrialChannel
import io.github.duwenji.yorisoi.sanpoguide.walk.WalkService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** A channel just taken onto the device, and what it asks of the AI. */
data class PendingUse(val providerId: String, val channelId: String, val cost: AiCost)

/** The providers the user added and the channels they deliver (API-002 設定画面のリスト). */
class ChannelsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as SanpoApp
    private val channels = app.channels

    val providers: StateFlow<List<ProviderView>> = channels.providers
    val notices: StateFlow<List<ChannelNotice>> = channels.notices
    val busy: StateFlow<Boolean> = channels.busy
    val walking: StateFlow<Boolean> = WalkService.walking
    val trials: StateFlow<List<TrialChannel>> = channels.trials
    val developerMode: StateFlow<Boolean> = app.settings.settings.map { it.developerMode }
        .stateIn(viewModelScope, SharingStarted.Eagerly, app.settings.settings.value.developerMode)

    private val _message = MutableStateFlow<String?>(null)
    /** The outcome of the last action that failed, in the user's words. */
    val message: StateFlow<String?> = _message.asStateFlow()

    fun addProvider(url: String, providerId: String, onDone: () -> Unit) = act { channels.addProvider(url, providerId); onDone() }
    fun removeProvider(id: String) = act { channels.removeProvider(id) }
    fun setEnabled(id: String, on: Boolean) = act { channels.setEnabled(id, on) }
    fun refresh() = act { channels.refreshNow() }

    private val _pendingUse = MutableStateFlow<PendingUse?>(null)
    /** A channel taken onto the device, waiting for the user to accept what it asks of the AI (platform ADR-004 H-9). */
    val pendingUse: StateFlow<PendingUse?> = _pendingUse.asStateFlow()

    /**
     * Takes the channel onto the device and shows what it asks of the user's AI service; it becomes
     * the one in use only when the user accepts ([confirmUse]). Nothing calls the AI before that.
     */
    fun use(providerId: String, channelId: String) = act {
        channels.install(providerId, channelId)
        val id = "$providerId/$channelId"
        val station = withTimeoutOrNull(5_000) { app.stations.list.first { l -> l.any { it.id == id } } }?.first { it.id == id }
        if (station == null) {
            app.settings.save(app.settings.settings.value.copy(stationId = id))
            return@act
        }
        val settings = app.settings.settings.value
        val operatorAi = settings.aiRoute == AiRoute.OPERATOR
        val limit = if (operatorAi) app.relay?.quota?.value?.limit else settings.dailyAiLimit
        _pendingUse.value = PendingUse(providerId, channelId, AiCost.of(station, app.stations.byId(BuiltInStations.STANDARD), operatorAi, limit))
    }

    /** Who answers for an installed channel, for a report (platform ADR-004 H-11). */
    fun reportTarget(providerId: String, channelId: String): ReportTarget? =
        app.stations.list.value.firstOrNull { it.id == "$providerId/$channelId" }?.let(Report::target)

    fun confirmUse() {
        val p = _pendingUse.value ?: return
        _pendingUse.value = null
        act { app.settings.save(app.settings.settings.value.copy(stationId = "${p.providerId}/${p.channelId}")) }
    }

    /** Not used after all: the package goes too. */
    fun cancelUse() {
        val p = _pendingUse.value ?: return
        _pendingUse.value = null
        act { channels.uninstall(p.providerId, p.channelId) }
    }

    fun update(providerId: String, channelId: String) = act { channels.install(providerId, channelId) }
    fun remove(providerId: String, channelId: String) = act { channels.uninstall(providerId, channelId) }
    fun dismissNotices() = act { channels.dismissNotices() }

    /** A test ticket from the QR code: the trial channel becomes the one in use. */
    fun loadTicket(text: String) = act {
        val id = channels.loadTicket(text)
        app.settings.save(app.settings.settings.value.copy(stationId = id))
    }

    fun removeTrial(providerId: String, channelId: String) = act { channels.removeTrial(providerId, channelId) }

    fun showMessage(text: String) {
        _message.value = text
    }
    fun clearMessage() {
        _message.value = null
    }

    private fun act(work: suspend () -> Unit) {
        _message.value = null
        viewModelScope.launch {
            try {
                work()
            } catch (e: ChannelSyncException) {
                _message.value = e.message
            }
        }
    }
}
