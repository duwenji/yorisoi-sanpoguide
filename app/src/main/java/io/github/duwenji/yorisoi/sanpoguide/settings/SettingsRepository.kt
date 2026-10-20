package io.github.duwenji.yorisoi.sanpoguide.settings

import io.github.duwenji.yorisoi.kit.geo.map.MapStyle
import io.github.duwenji.yorisoi.sanpoguide.BuildConfig
import io.github.duwenji.yorisoi.kit.llm.KeyCipher
import android.content.Context
import androidx.core.content.edit
import io.github.duwenji.yorisoi.kit.llm.Provider
import io.github.duwenji.yorisoi.sanpoguide.station.StationOverrides
import io.github.duwenji.yorisoi.core.format.GuideLength
import io.github.duwenji.yorisoi.core.format.SoundChoice
import io.github.duwenji.yorisoi.core.format.SpotKind
import io.github.duwenji.yorisoi.core.format.TalkEventKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How the AI is reached (platform ADR-AI-RELAY R-1): the operator's AI through its relay, paid by
 * the operator up to its daily limit, or the user's own key straight to the service they chose.
 */
enum class AiRoute { OPERATOR, OWN_KEY }

/**
 * How the AI is reached and, for the user's own key, which AI service to use, with per-provider
 * keys and models so switching back keeps them,
 * plus how often the walking companion speaks up and the limits for its safety and amenity remarks.
 */
data class GuideSettings(
    val aiRoute: AiRoute = AiRoute.OPERATOR,
    val provider: Provider = Provider.CLAUDE,
    val apiKeys: Map<Provider, String> = emptyMap(),
    val models: Map<Provider, String> = emptyMap(),
    val customBaseUrl: String = "",
    /** The channel in use (docs/channels.md); how chatty the companion is comes with it. */
    val stationId: String = "standard",
    /** The user's changes to built-in channels, by channel id. */
    val stationOverrides: Map<String, StationOverrides> = emptyMap(),
    val thresholds: Thresholds = Thresholds(),
    /** Screen colors and the header picture follow the mood. The companion's tone is the channel's. */
    val moodEnabled: Boolean = true,
    /** Background sound during walk mode; off unless the user asks for it. */
    val ambientEnabled: Boolean = false,
    /** Percent. */
    val ambientVolume: Int = 40,
    /** Play only through earphones, never out loud from the phone's speaker. */
    val ambientEarphonesOnly: Boolean = true,
    /** Wikimedia Commons photos of spots in the guide sheet. */
    val spotPhotos: Boolean = true,
    /** Download photos on mobile data too, not only on Wi-Fi. */
    val photosOnMobileData: Boolean = false,
    /**
     * Send coordinates (the spot's, the user's and the route walked) to the AI service.
     * Off by default: without it the AI gets names, distances and figures only.
     */
    val shareLocationWithAi: Boolean = false,
    /** How many times a day the AI may be called; null for no limit (platform ADR-004 H-9). Channels talk within it. */
    val dailyAiLimit: Int? = 100,
    val mapStyle: MapStyle = MapStyle.GSI_STANDARD,
    /** The user's own Google Maps Platform key (Map Tiles API), for the Google map styles. */
    val googleMapsApiKey: String = "",
    /**
     * For publishers trying their channel before review: test tickets can be read (API-002).
     * Turned on by tapping the version seven times; saved at once, not with the rest of the settings.
     */
    val developerMode: Boolean = false,
) {
    fun apiKey(p: Provider = provider): String = apiKeys[p].orEmpty()
    fun model(p: Provider = provider): String = models[p]?.takeIf { it.isNotBlank() } ?: p.defaultModel
    fun baseUrl(p: Provider = provider): String = if (p == Provider.CUSTOM) customBaseUrl else p.defaultBaseUrl

    val isConfigured: Boolean
        get() = when (aiRoute) {
            AiRoute.OPERATOR -> operatorAiAvailable
            AiRoute.OWN_KEY -> apiKey().isNotBlank() && model().isNotBlank() &&
                (provider == Provider.CLAUDE || baseUrl().isNotBlank())
        }

    /** The model named beside what the AI said: the service's own for a user's key. */
    val aiName: String
        get() = if (aiRoute == AiRoute.OPERATOR) OPERATOR_AI_NAME else model()

    companion object {
        /** The daily limits offered on the settings screen; null is「上限なし」. */
        val DAILY_AI_LIMITS: List<Int?> = listOf(30, 60, 100, 200, null)

        /** Whether this build has a relay to talk to (release builds don't until production's is up). */
        val operatorAiAvailable: Boolean get() = BuildConfig.AI_RELAY_URL.isNotEmpty()

        const val OPERATOR_AI_NAME = "運営の AI"

        /**
         * The route when none is saved (ADR-AI-RELAY R-4): someone who already put in a key keeps it
         * after the update; everyone else starts on the operator's AI where this build has one.
         */
        fun defaultRoute(hasOwnKey: Boolean, operatorAvailable: Boolean = operatorAiAvailable): AiRoute =
            if (hasOwnKey || !operatorAvailable) AiRoute.OWN_KEY else AiRoute.OPERATOR
    }
}

/** Persists [GuideSettings]; API keys (AI and Google Maps) are stored encrypted with [KeyCipher]. */
class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("guide_settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<GuideSettings> = _settings.asStateFlow()

    /** Saved at once, apart from [save]'s draft, which leaves it as it is. */
    fun setDeveloperMode(on: Boolean) {
        prefs.edit { putBoolean(KEY_DEVELOPER_MODE, on) }
        _settings.value = load()
    }

    fun save(settings: GuideSettings) {
        prefs.edit {
            putString(KEY_AI_ROUTE, settings.aiRoute.name)
            putString(KEY_PROVIDER, settings.provider.name)
            putString(KEY_CUSTOM_BASE_URL, settings.customBaseUrl.trim())
            putString(KEY_STATION, settings.stationId)
            prefs.all.keys.filter { it.startsWith(STATION_PREFIX) }.forEach { remove(it) }
            settings.stationOverrides.forEach { (id, o) ->
                o.talkLevel?.let { putString(stationPref(id, FIELD_TALK_LEVEL), it.name) }
                o.moodTone?.let { putBoolean(stationPref(id, FIELD_MOOD_TONE), it) }
                o.events?.let { putString(stationPref(id, FIELD_EVENTS), OverrideCodec.encode(it)) }
                o.guideLength?.let { putString(stationPref(id, FIELD_GUIDE_LENGTH), it.json) }
                o.sound?.let { putString(stationPref(id, FIELD_SOUND), it.json) }
                o.prefer?.let { putString(stationPref(id, FIELD_PREFER), OverrideCodec.encode(it)) }
            }
            putBoolean(KEY_MOOD, settings.moodEnabled)
            putBoolean(KEY_AMBIENT, settings.ambientEnabled)
            putInt(KEY_AMBIENT_VOLUME, settings.ambientVolume.coerceIn(0, 100))
            putBoolean(KEY_AMBIENT_EARPHONES_ONLY, settings.ambientEarphonesOnly)
            putBoolean(KEY_SPOT_PHOTOS, settings.spotPhotos)
            putBoolean(KEY_PHOTOS_ON_MOBILE, settings.photosOnMobileData)
            putBoolean(KEY_SHARE_LOCATION_WITH_AI, settings.shareLocationWithAi)
            putInt(KEY_DAILY_AI_LIMIT, settings.dailyAiLimit ?: NO_LIMIT)
            putString(KEY_MAP_STYLE, settings.mapStyle.name)
            val mapsKey = settings.googleMapsApiKey.trim()
            if (mapsKey.isEmpty()) remove(KEY_GOOGLE_MAPS_KEY) else putString(KEY_GOOGLE_MAPS_KEY, KeyCipher.encrypt(mapsKey))
            Provider.entries.forEach { p ->
                val key = settings.apiKeys[p]?.trim().orEmpty()
                if (key.isEmpty()) remove(apiKeyPref(p)) else putString(apiKeyPref(p), KeyCipher.encrypt(key))
                val model = settings.models[p]?.trim().orEmpty()
                if (model.isEmpty()) remove(modelPref(p)) else putString(modelPref(p), model)
            }
            // Only overrides are stored, so a changed default in a later version still applies.
            Threshold.entries.forEach { t ->
                val value = settings.thresholds.values[t]
                if (value == null || value == t.default || !t.isValid(value)) remove(thresholdPref(t))
                else putInt(thresholdPref(t), value)
            }
        }
        _settings.value = load()
    }

    private fun load(): GuideSettings {
        val provider = prefs.getString(KEY_PROVIDER, null)
            ?.let { name -> Provider.entries.firstOrNull { it.name == name } }
            ?: Provider.CLAUDE
        val apiKeys = Provider.entries.mapNotNull { p ->
            prefs.getString(apiKeyPref(p), null)?.let(KeyCipher::decrypt)?.let { p to it }
        }.toMap()
        return GuideSettings(
            aiRoute = prefs.getString(KEY_AI_ROUTE, null)
                ?.let { name -> AiRoute.entries.firstOrNull { it.name == name } }
                ?: GuideSettings.defaultRoute(hasOwnKey = apiKeys.values.any { it.isNotBlank() }),
            provider = provider,
            apiKeys = apiKeys,
            models = Provider.entries.mapNotNull { p ->
                prefs.getString(modelPref(p), null)?.let { p to it }
            }.toMap(),
            customBaseUrl = prefs.getString(KEY_CUSTOM_BASE_URL, "").orEmpty(),
            stationId = prefs.getString(KEY_STATION, null) ?: DEFAULTS.stationId,
            stationOverrides = loadStationOverrides(),
            thresholds = Thresholds(
                Threshold.entries.filter { prefs.contains(thresholdPref(it)) }
                    .associateWith { prefs.getInt(thresholdPref(it), it.default) },
            ),
            moodEnabled = prefs.getBoolean(KEY_MOOD, DEFAULTS.moodEnabled),
            ambientEnabled = prefs.getBoolean(KEY_AMBIENT, DEFAULTS.ambientEnabled),
            ambientVolume = prefs.getInt(KEY_AMBIENT_VOLUME, DEFAULTS.ambientVolume),
            ambientEarphonesOnly = prefs.getBoolean(KEY_AMBIENT_EARPHONES_ONLY, DEFAULTS.ambientEarphonesOnly),
            spotPhotos = prefs.getBoolean(KEY_SPOT_PHOTOS, DEFAULTS.spotPhotos),
            photosOnMobileData = prefs.getBoolean(KEY_PHOTOS_ON_MOBILE, DEFAULTS.photosOnMobileData),
            shareLocationWithAi = prefs.getBoolean(KEY_SHARE_LOCATION_WITH_AI, DEFAULTS.shareLocationWithAi),
            dailyAiLimit = if (prefs.contains(KEY_DAILY_AI_LIMIT)) prefs.getInt(KEY_DAILY_AI_LIMIT, NO_LIMIT).takeIf { it != NO_LIMIT } else DEFAULTS.dailyAiLimit,
            mapStyle = prefs.getString(KEY_MAP_STYLE, null)
                ?.let { name -> MapStyle.entries.firstOrNull { it.name == name } }
                ?: DEFAULTS.mapStyle,
            googleMapsApiKey = prefs.getString(KEY_GOOGLE_MAPS_KEY, null)?.let(KeyCipher::decrypt).orEmpty(),
            developerMode = prefs.getBoolean(KEY_DEVELOPER_MODE, DEFAULTS.developerMode),
        )
    }

    private fun loadStationOverrides(): Map<String, StationOverrides> {
        val all = prefs.all
        val ids = all.keys.filter { it.startsWith(STATION_PREFIX) }
            .mapNotNull { key -> FIELDS.firstOrNull { key.endsWith("_$it") }?.let { key.removePrefix(STATION_PREFIX).removeSuffix("_$it") } }
            .toSet()
        return ids.associateWith { id ->
            StationOverrides(
                talkLevel = (all[stationPref(id, FIELD_TALK_LEVEL)] as? String)
                    ?.let { name -> TalkLevel.entries.firstOrNull { it.name == name } },
                moodTone = all[stationPref(id, FIELD_MOOD_TONE)] as? Boolean,
                events = OverrideCodec.decodeSet<TalkEventKind>(all[stationPref(id, FIELD_EVENTS)] as? String),
                guideLength = OverrideCodec.decode<GuideLength>(all[stationPref(id, FIELD_GUIDE_LENGTH)] as? String),
                sound = OverrideCodec.decode<SoundChoice>(all[stationPref(id, FIELD_SOUND)] as? String),
                prefer = OverrideCodec.decodeSet<SpotKind>(all[stationPref(id, FIELD_PREFER)] as? String),
            )
        }.filterValues { !it.isEmpty }
    }

    private fun stationPref(id: String, field: String) = "$STATION_PREFIX${id}_$field"
    private fun apiKeyPref(p: Provider) = "api_key_${p.name}"
    private fun thresholdPref(t: Threshold) = "threshold_${t.name}"
    private fun modelPref(p: Provider) = "model_${p.name}"

    private companion object {
        const val KEY_AI_ROUTE = "ai_route"
        const val KEY_PROVIDER = "provider"
        const val KEY_CUSTOM_BASE_URL = "custom_base_url"
        const val KEY_STATION = "selected_station"
        /** `station_{id}_{field}`: the user's changes to a built-in channel. */
        const val STATION_PREFIX = "station_"
        const val FIELD_TALK_LEVEL = "talk_level"
        const val FIELD_MOOD_TONE = "mood_tone"
        const val FIELD_EVENTS = "events"
        const val FIELD_GUIDE_LENGTH = "guide_length"
        const val FIELD_SOUND = "sound"
        const val FIELD_PREFER = "prefer"
        val FIELDS = listOf(FIELD_TALK_LEVEL, FIELD_MOOD_TONE, FIELD_EVENTS, FIELD_GUIDE_LENGTH, FIELD_SOUND, FIELD_PREFER)
        const val STANDARD = "standard"
        const val KEY_MOOD = "mood"
        const val KEY_AMBIENT = "ambient"
        const val KEY_AMBIENT_VOLUME = "ambient_volume"
        const val KEY_AMBIENT_EARPHONES_ONLY = "ambient_earphones_only"
        const val KEY_SPOT_PHOTOS = "spot_photos"
        const val KEY_PHOTOS_ON_MOBILE = "photos_on_mobile"
        const val KEY_SHARE_LOCATION_WITH_AI = "share_location_with_ai"
        const val KEY_MAP_STYLE = "map_style"
        const val KEY_GOOGLE_MAPS_KEY = "google_maps_api_key"
        const val KEY_DEVELOPER_MODE = "developer_mode"
        const val KEY_DAILY_AI_LIMIT = "daily_ai_limit"
        private const val NO_LIMIT = -1
        val DEFAULTS = GuideSettings()
    }
}
