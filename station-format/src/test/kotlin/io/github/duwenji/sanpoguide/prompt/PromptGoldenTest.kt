package io.github.duwenji.sanpoguide.prompt

import io.github.duwenji.sanpoguide.station.format.BuiltInChannels
import io.github.duwenji.sanpoguide.station.format.GuideLength
import io.github.duwenji.sanpoguide.station.format.TalkEventKind
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Pins what the app sends to the AI (RFC-002 G-2): every built-in channel's system prompts, its
 * extra instructions per event and the review samples, plus a hash of every prompt and channel
 * file. Moving code or files between modules must leave this byte for byte the same.
 *
 * On a mismatch the current output is written to `build/prompt-golden.txt`; if the change is
 * intended, copy it over `src/test/resources/prompt-golden.txt`.
 */
class PromptGoldenTest {
    @Test
    fun `prompts are what they were`() {
        val actual = dump()
        val golden = PromptGoldenTest::class.java.classLoader.getResourceAsStream(GOLDEN)
            ?.use { it.readBytes().toString(Charsets.UTF_8).replace("\r\n", "\n") }
        if (golden == actual) return
        val out = File("build/$GOLDEN").apply { parentFile.mkdirs(); writeText(actual) }
        val line = if (golden == null) null else firstDifference(golden, actual)
        fail(
            (if (golden == null) "No $GOLDEN yet." else "Prompts changed (first difference at line $line).") +
                " The current output is in ${out.absolutePath}",
        )
    }

    private fun dump(): String = buildString {
        val prompts = Prompts.fromResources()
        val packages = BuiltInChannels.IDS.associateWith { BuiltInChannels.read(it, BuiltInChannels.files(it)) }
        val standard = packages.getValue(BuiltInChannels.STANDARD)

        section("files")
        resourceFiles().forEach { (path, bytes) -> append(sha256(bytes)).append("  ").append(path).append('\n') }

        packages.forEach { (id, pkg) ->
            val slots = StationPrompts.slots(pkg, standard)
            GuideLength.entries.forEach { length ->
                section("$id guide/system length=${length.json}")
                append(prompts.render(Prompts.Guide.SYSTEM, StationPrompts.guideSystemVars(slots, length))).append('\n')
            }
            section("$id companion/system")
            append(prompts.render(Prompts.Talk.SYSTEM, StationPrompts.companionSystemVars(slots))).append('\n')
            TalkEventKind.entries.forEach { kind ->
                section("$id event ${kind.json}")
                append(StationPrompts.eventInstructions(slots, kind) ?: "(none)").append('\n')
            }
            ReviewSamples.build(pkg, standard, prompts).forEach { sample ->
                section("$id sample ${sample.id} ${sample.label}")
                append("[system]\n").append(sample.system).append("\n[user]\n").append(sample.user).append('\n')
            }
        }
    }

    private fun StringBuilder.section(title: String) {
        append("\n===== ").append(title).append(" =====\n")
    }

    /** The prompt files and the built-in channels' files, by their path under `sanpoguide/`. */
    private fun resourceFiles(): List<Pair<String, ByteArray>> {
        val loader = PromptGoldenTest::class.java.classLoader
        val prompts = File(PROMPT_SOURCES).walkTopDown().filter { it.isFile }
            .map { "prompts/" + it.relativeTo(File(PROMPT_SOURCES)).invariantSeparatorsPath }
            .toList()
        check(prompts.isNotEmpty()) { "No prompt files under ${File(PROMPT_SOURCES).absolutePath}" }
        val channels = BuiltInChannels.IDS.flatMap { id -> BuiltInChannels.files(id).list().map { "channels/$id/$it" } }
        return (prompts + channels).sorted().map { path ->
            path to (loader.getResourceAsStream("sanpoguide/$path")?.use { it.readBytes() } ?: error("No resource sanpoguide/$path"))
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun firstDifference(a: String, b: String): Int {
        val x = a.lines()
        val y = b.lines()
        return (x.indices.firstOrNull { it >= y.size || x[it] != y[it] } ?: y.size) + 1
    }

    private companion object {
        const val GOLDEN = "prompt-golden.txt"
        /** The prompt folder in this module's sources (tests run in the module's directory). */
        const val PROMPT_SOURCES = "src/main/resources/sanpoguide/prompts"
    }
}
