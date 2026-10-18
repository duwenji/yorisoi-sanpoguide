package io.github.duwenji.yorisoi.sanpoguide.diagnostics

import io.github.duwenji.yorisoi.sanpoguide.history.WalkJson
import io.github.duwenji.yorisoi.sanpoguide.history.WalkRecord
import org.json.JSONArray
import org.json.JSONObject

/**
 * The file the developer exports from the history screen: every saved walk in the history's own
 * format, each with its [WalkDiagnostics] events when the walk was recorded in developer mode.
 * The field names are what analysis scripts read; [VERSION] goes up when one changes meaning.
 */
object WalkExport {
    const val FORMAT = "sanpoguide-walk-export"
    const val VERSION = 1

    fun toJson(walks: List<WalkRecord>, diagnostics: (Long) -> JSONArray, appVersion: String, exportedAt: Long): JSONObject =
        JSONObject()
            .put("format", FORMAT)
            .put("formatVersion", VERSION)
            .put("app", appVersion)
            .put("exportedAt", exportedAt)
            .put("walks", JSONArray().apply {
                walks.forEach { w -> put(WalkJson.toJson(w).put("diagnostics", diagnostics(w.id))) }
            })

    /** A file name that sorts by date, e.g. `sanpoguide-walks-20261018-1530.json`. */
    fun fileName(stamp: String) = "sanpoguide-walks-$stamp.json"
}
