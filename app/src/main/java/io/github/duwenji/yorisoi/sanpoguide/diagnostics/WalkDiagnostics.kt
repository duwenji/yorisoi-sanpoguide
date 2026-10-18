package io.github.duwenji.yorisoi.sanpoguide.diagnostics

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * A detailed record of each walk taken in developer mode, for reviewing afterwards when the
 * companion talked and why, what the AI was asked and wrote, and how the searches went.
 * One JSON object per line in `diagnostics/<walk id>.jsonl`, kept on the device for the last
 * [MAX_WALKS] walks; nothing is sent anywhere. It is read back only by the export.
 */
class WalkDiagnostics(
    private val dir: File,
    private val clock: () -> Long = System::currentTimeMillis,
    // One writer, so lines keep their order and reads see every line written before them.
    private val writer: ExecutorService = Executors.newSingleThreadExecutor(),
) {
    @Volatile
    private var file: File? = null

    /** Whether a walk is being recorded. */
    val recording: Boolean get() = file != null

    /** Starts recording the walk [walkId], with [fields] describing how it was set up. */
    fun start(walkId: Long, fields: List<Pair<String, Any?>>) {
        val f = File(dir, "$walkId$SUFFIX")
        writer.execute {
            dir.mkdirs()
            prune(keep = MAX_WALKS - 1)
        }
        file = f
        event("walk_start", fields)
    }

    /** Adds an event of [type] to the walk being recorded; does nothing when none is. */
    fun event(type: String, vararg fields: Pair<String, Any?>) = event(type, fields.asList())

    fun event(type: String, fields: List<Pair<String, Any?>>) {
        val f = file ?: return
        val o = JSONObject().put("t", clock()).put("type", type)
        fields.forEach { (k, v) -> o.put(k, v ?: JSONObject.NULL) }
        val line = o.toString()
        writer.execute {
            try {
                f.appendText(line + "\n")
            } catch (e: IOException) {
                Log.w(TAG, "Could not write a diagnostics line", e)
            }
        }
    }

    /** Ends the record of [walkId]; a later walk already being recorded is left alone. */
    fun finish(walkId: Long) {
        if (file?.name != "$walkId$SUFFIX") return
        event("walk_end")
        file = null
    }

    /** The events recorded for [walkId], oldest first; empty when it wasn't recorded. */
    fun read(walkId: Long): JSONArray = writer.submit<JSONArray> {
        val f = File(dir, "$walkId$SUFFIX")
        JSONArray().apply {
            if (f.exists()) {
                f.forEachLine { line ->
                    // A line cut short (the app was killed mid-write) is skipped, not the whole walk.
                    if (line.isNotBlank()) runCatching { JSONObject(line) }.onSuccess { put(it) }
                }
            }
        }
    }.get()

    fun clear() {
        writer.submit { dir.listFiles()?.forEach { it.delete() } }.get()
    }

    private fun prune(keep: Int) {
        dir.listFiles { f -> f.name.endsWith(SUFFIX) }
            ?.sortedByDescending { it.name.removeSuffix(SUFFIX).toLongOrNull() ?: 0L }
            ?.drop(keep)
            ?.forEach { it.delete() }
    }

    companion object {
        private const val TAG = "WalkDiagnostics"
        private const val SUFFIX = ".jsonl"
        const val MAX_WALKS = 30
    }
}
