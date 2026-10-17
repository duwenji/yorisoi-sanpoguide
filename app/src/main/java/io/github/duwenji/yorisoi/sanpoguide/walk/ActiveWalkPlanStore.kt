package io.github.duwenji.yorisoi.sanpoguide.walk

import io.github.duwenji.yorisoi.sanpoguide.companion.WalkPlan
import io.github.duwenji.yorisoi.sanpoguide.companion.WalkPlanJson
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import org.json.JSONObject

class ActiveWalkPlanStore(private val file: File) {
    fun save(plan: WalkPlan) {
        val parent = file.parentFile ?: throw IOException("Active walk plan must have a parent directory")
        if (!parent.isDirectory && !parent.mkdirs()) {
            throw IOException("Could not create active walk plan directory: $parent")
        }
        val temporary = File.createTempFile("${file.name}.", ".tmp", parent)
        try {
            temporary.writeText(WalkPlanJson.toJson(plan).toString(), Charsets.UTF_8)
            Files.move(temporary.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary.toPath())
        }
    }

    fun load(): WalkPlan? {
        if (!file.exists()) return null
        return WalkPlanJson.fromJson(JSONObject(file.readText(Charsets.UTF_8)))
    }

    fun clear() {
        Files.deleteIfExists(file.toPath())
    }
}
