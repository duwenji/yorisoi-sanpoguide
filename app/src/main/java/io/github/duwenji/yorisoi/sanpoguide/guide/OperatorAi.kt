package io.github.duwenji.yorisoi.sanpoguide.guide

import android.content.Context
import androidx.core.content.edit
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityManager.PrepareIntegrityTokenRequest
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenProvider
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenRequest
import io.github.duwenji.yorisoi.kit.llm.IntegrityTokenProvider
import io.github.duwenji.yorisoi.kit.llm.RelayApp
import io.github.duwenji.yorisoi.kit.llm.RelayClient
import io.github.duwenji.yorisoi.kit.llm.RelaySessionStore
import io.github.duwenji.yorisoi.sanpoguide.BuildConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import java.time.Instant
import java.util.UUID

/**
 * The operator's AI (platform ADR-AI-RELAY): the relay this build talks to, or null when it has none
 * (release builds until production's relay is deployed).
 */
object OperatorAi {
    fun create(context: Context): RelayClient? {
        if (BuildConfig.AI_RELAY_URL.isEmpty()) return null
        val app = RelayApp(context.packageName, context.packageManager.getPackageInfo(context.packageName, 0).longVersionCodeCompat())
        val integrity = BuildConfig.PLAY_CLOUD_PROJECT_NUMBER.takeIf { it != 0L }?.let { PlayIntegrity(context, it) }
        return RelayClient(BuildConfig.AI_RELAY_URL, app, PrefsSessionStore(context), integrity)
    }

    @Suppress("DEPRECATION")
    private fun android.content.pm.PackageInfo.longVersionCodeCompat(): Long =
        if (android.os.Build.VERSION.SDK_INT >= 28) longVersionCode else versionCode.toLong()
}

/**
 * The install id and the session, in their own preferences: like every preference, they stay out
 * of backups and device transfers (data_extraction_rules.xml; API-AI-RELAY AR-2).
 */
private class PrefsSessionStore(context: Context) : RelaySessionStore {
    private val prefs = context.getSharedPreferences("ai_relay", Context.MODE_PRIVATE)

    @Synchronized
    override fun installId(): String =
        prefs.getString(KEY_INSTALL, null) ?: UUID.randomUUID().toString().also { id -> prefs.edit { putString(KEY_INSTALL, id) } }

    override fun session(): Pair<String, Instant>? {
        val token = prefs.getString(KEY_TOKEN, null) ?: return null
        val expires = prefs.getString(KEY_EXPIRES, null)?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
        return token to expires
    }

    override fun saveSession(token: String, expiresAt: Instant) = prefs.edit { putString(KEY_TOKEN, token).putString(KEY_EXPIRES, expiresAt.toString()) }

    override fun clearSession() = prefs.edit { remove(KEY_TOKEN).remove(KEY_EXPIRES) }

    private companion object {
        const val KEY_INSTALL = "install_id"
        const val KEY_TOKEN = "token"
        const val KEY_EXPIRES = "expires_at"
    }
}

/** Play Integrity's Standard request (API-AI-RELAY AR-3); the provider is prepared once and kept. */
private class PlayIntegrity(context: Context, private val cloudProjectNumber: Long) : IntegrityTokenProvider {
    private val manager = IntegrityManagerFactory.createStandard(context.applicationContext)
    private val lock = Mutex()
    private var provider: StandardIntegrityTokenProvider? = null

    override suspend fun token(requestHash: String): String {
        val p = lock.withLock {
            provider ?: manager.prepareIntegrityToken(
                PrepareIntegrityTokenRequest.builder().setCloudProjectNumber(cloudProjectNumber).build(),
            ).await().also { provider = it }
        }
        return try {
            p.request(StandardIntegrityTokenRequest.builder().setRequestHash(requestHash).build()).await().token()
        } catch (e: Exception) {
            // A provider can go stale; prepare a new one next time.
            lock.withLock { provider = null }
            throw e
        }
    }
}
