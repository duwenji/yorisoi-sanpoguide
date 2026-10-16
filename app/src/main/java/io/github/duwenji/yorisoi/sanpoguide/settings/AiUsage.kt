package io.github.duwenji.yorisoi.sanpoguide.settings

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate

/** Where [AiUsage] keeps today's count; replaceable so it can be tested off the device. */
interface UsageStore {
    fun read(): Pair<String, Int>?
    fun write(day: String, count: Int)
}

/**
 * How many times the AI was called today, against the limit the user set (platform ADR-004 H-9).
 * The count starts again each local day. Only calls that reach the AI service count, not cached narrations.
 */
class AiUsage(private val store: UsageStore, private val today: () -> LocalDate = LocalDate::now) {
    private val _count = MutableStateFlow(current())
    /** Today's calls so far. */
    val count: StateFlow<Int> = _count.asStateFlow()

    /** Whether [limit] (null for none) leaves no call for today. */
    fun reached(limit: Int?): Boolean = limit != null && current() >= limit

    /** Counts one call, unless [limit] is already reached; false then. */
    @Synchronized
    fun tryUse(limit: Int?): Boolean {
        val n = current()
        if (limit != null && n >= limit) return false
        store.write(today().toString(), n + 1)
        _count.value = n + 1
        return true
    }

    private fun current(): Int = store.read()?.takeIf { (day, _) -> day == today().toString() }?.second ?: 0

    companion object {
        fun of(context: Context): AiUsage {
            val prefs = context.getSharedPreferences("ai_usage", Context.MODE_PRIVATE)
            return AiUsage(object : UsageStore {
                override fun read() = prefs.getString(KEY_DAY, null)?.let { it to prefs.getInt(KEY_COUNT, 0) }
                override fun write(day: String, count: Int) = prefs.edit { putString(KEY_DAY, day).putInt(KEY_COUNT, count) }
            })
        }

        private const val KEY_DAY = "day"
        private const val KEY_COUNT = "count"
    }
}
