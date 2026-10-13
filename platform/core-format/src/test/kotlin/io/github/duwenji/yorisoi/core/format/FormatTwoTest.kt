package io.github.duwenji.yorisoi.core.format

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** API-003 2.0: format 2's fields, how review and an app read unknown values, and choosing channels (V-23〜V-28). */
class FormatTwoTest {
    private class MemoryFiles(val files: Map<String, ByteArray>) : StationFiles {
        override fun list() = files.keys.toList()
        override fun read(path: String) = files[path]
    }

    private fun manifest(format: Int = 2, change: JSONObject.() -> Unit = {}) = JSONObject(
        """
        {
          "format": $format, "id": "kamakura-history", "version": 3,
          "name": "鎌倉歴史散歩", "summary": "鎌倉の寺社と武士の歴史を、語り部の口調で", "lang": "ja",
          "activities": ["walk", "cycle"], "genres": ["history"], "requires": { "features": ["slot:guide"] },
          "greeting": "ここからは、鎌倉の歴史をたどりながら歩きましょう。",
          "talk": { "level": "normal",
                    "events": { "spot": true, "revisit": true, "milestone": true, "rest": false, "start": true, "finish": true } },
          "spots": { "prefer": ["temple", "shrine"], "skip": ["artwork"] },
          "guide": { "length": "long" },
          "mood": { "tone": true, "sound": "temple" }
        }
        """.trimIndent(),
    ).apply(change)

    private fun files(manifest: JSONObject, vararg extra: Pair<String, String>) = MemoryFiles(
        mapOf("channel.json" to manifest.toString().toByteArray()) + extra.associate { (p, t) -> p to t.toByteArray() },
    )

    private fun read(files: StationFiles, strictness: Strictness, mediaStage: Int = 0, listedAs: ListedAs? = null): StationCheck =
        StationValidator.check(files, StationOrigin.BUILT_IN, listedAs, mediaStage = mediaStage, strictness = strictness)

    private fun ok(result: StationCheck): StationPackage {
        assertTrue("expected Ok, got $result", result is StationCheck.Ok)
        return (result as StationCheck.Ok).station
    }

    private fun code(result: StationCheck): RejectCode {
        assertTrue("expected a rejection, got $result", result is StationCheck.Rejected)
        return (result as StationCheck.Rejected).code
    }

    private val walkingApp = AppProfile(AppIds.SANPOGUIDE, Activity.WALK, Feature.entries.toSet(), versionCode = 10)

    @Test
    fun `format 2 carries activities, genres and required features`() {
        val m = ok(read(files(manifest()), Strictness.REVIEW)).manifest
        assertEquals(setOf(Activity.WALK, Activity.CYCLE), m.activities)
        assertEquals(setOf(Genre.HISTORY), m.genres)
        assertEquals(setOf(Feature.SLOT_GUIDE), m.requires)
        assertEquals(emptyList<String>(), m.unknownRestrictions)
        assertEquals(emptyList<String>(), Compatibility.reasons(m, walkingApp))
    }

    @Test
    fun `format 1 is a walking channel with no genre and no requirement (V-23)`() {
        val m = ok(read(files(manifest(format = 1) { remove("activities"); remove("genres"); remove("requires") }), Strictness.APP)).manifest
        assertEquals(setOf(Activity.WALK), m.activities)
        assertEquals(emptySet<Genre>(), m.genres)
        assertEquals(emptySet<Feature>(), m.requires)
        // Format 1 knows nothing of these fields: written anyway, they're ignored like any unknown field.
        assertEquals(setOf(Activity.WALK), ok(read(files(manifest(format = 1) { put("activities", JSONArray().put("cycle")) }), Strictness.REVIEW)).manifest.activities)
    }

    @Test
    fun `review turns away what the lists don't hold, and format 2 without activities (V-24)`() {
        fun reviewed(change: JSONObject.() -> Unit) = code(read(files(manifest(change = change)), Strictness.REVIEW))
        assertEquals(RejectCode.BAD_MANIFEST, reviewed { remove("activities") })
        assertEquals(RejectCode.BAD_VALUE, reviewed { put("activities", JSONArray()) })
        assertEquals(RejectCode.BAD_VALUE, reviewed { put("activities", JSONArray().put("walk").put("walk")) })
        assertEquals(RejectCode.BAD_VALUE, reviewed { put("activities", JSONArray().put("drive")) })
        assertEquals(RejectCode.BAD_VALUE, reviewed { put("genres", JSONArray().put("sports")) })
        assertEquals(RejectCode.BAD_VALUE, reviewed { put("genres", JSONArray().put("history").put("nature").put("food").put("shopping")) })
        assertEquals(RejectCode.BAD_VALUE, reviewed { put("requires", JSONObject().put("features", JSONArray().put("present:hologram"))) })
        assertEquals(RejectCode.BAD_VALUE, reviewed { getJSONObject("talk").put("level", "nonstop") })
        assertEquals(RejectCode.UNEXPECTED_FILE, code(read(files(manifest(), "prompts/guide/history.md" to "年号を添える\n"), Strictness.REVIEW)))
    }

    @Test
    fun `an app leaves out optional values it doesn't know, and the channel stays usable (V-26)`() {
        val m = ok(
            read(
                files(
                    manifest {
                        put("activities", JSONArray().put("walk").put("drive"))
                        put("genres", JSONArray().put("history").put("sports"))
                        getJSONObject("spots").put("prefer", JSONArray().put("temple").put("onsen"))
                        getJSONObject("talk").getJSONObject("events").put("sunrise", true)
                        getJSONObject("mood").put("sound", "bells")
                    },
                    // A slot of a newer app: unused, not a reason to turn the channel away.
                    "prompts/guide/history.md" to "年号を添える\n",
                ),
                Strictness.APP,
            ),
        ).manifest
        assertEquals(setOf(Activity.WALK), m.activities)
        assertEquals(setOf(Genre.HISTORY), m.genres)
        assertEquals(listOf(SpotKind.TEMPLE), m.prefer)
        assertEquals(SoundChoice.AUTO, m.sound)
        assertEquals(emptyList<String>(), Compatibility.reasons(m, walkingApp))
    }

    @Test
    fun `an app doesn't use a channel whose restrictions it can't read (V-26)`() {
        fun reasons(change: JSONObject.() -> Unit) = Compatibility.reasons(ok(read(files(manifest(change = change)), Strictness.APP)).manifest, walkingApp)
        assertEquals(listOf("unknown requires.features \"present:hologram\""), reasons { put("requires", JSONObject().put("features", JSONArray().put("present:hologram"))) })
        assertEquals(listOf("unknown talk.level \"nonstop\""), reasons { getJSONObject("talk").put("level", "nonstop") })
        assertEquals(listOf("unknown guide.length \"epic\""), reasons { getJSONObject("guide").put("length", "epic") })
        assertEquals(listOf("unknown spots.skip \"onsen\""), reasons { getJSONObject("spots").put("skip", JSONArray().put("onsen")) })
        // Still a format error, whatever the app knows.
        assertEquals(RejectCode.BAD_MANIFEST, code(read(files(manifest { remove("activities") }), Strictness.APP)))
        assertEquals(RejectCode.UNEXPECTED_FILE, code(read(files(manifest(), "secret.txt" to "x"), Strictness.APP)))
    }

    @Test
    fun `an app still rejects unknown values in format 1 (G-3)`() {
        val old = manifest(format = 1) { getJSONObject("talk").put("level", "nonstop") }
        assertEquals(RejectCode.BAD_VALUE, code(read(files(old), Strictness.APP)))
    }

    @Test
    fun `a channel for another activity, or needing what the app lacks, isn't offered (V-25)`() {
        val cycling = ok(read(files(manifest { put("activities", JSONArray().put("cycle")) }), Strictness.REVIEW)).manifest
        assertEquals(listOf("not for walk (cycle)"), Compatibility.reasons(cycling, walkingApp))
        val needsFullscreen = ok(read(files(manifest { put("requires", JSONObject().put("features", JSONArray().put("present:fullscreen"))) }), Strictness.REVIEW)).manifest
        assertEquals(listOf("needs present:fullscreen"), Compatibility.reasons(needsFullscreen, walkingApp.copy(features = Feature.entries.toSet() - Feature.PRESENT_FULLSCREEN)))
    }

    @Test
    fun `an app leaves out cues and presentations it doesn't know`() {
        var n = 0
        fun cue(trigger: JSONObject, vararg present: String): JSONObject =
            JSONObject().put("id", "c${n++}").put("trigger", trigger)
                .put("present", JSONArray().apply { present.forEach { put(JSONObject().put("as", it).put("source", "builtin:ai-guide")) } })
        val point = JSONObject().put("type", "point").put("lat", 35.0).put("lon", 139.0).put("radiusM", 50)
        val withCues = manifest {
            put("cues", JSONArray()
                .put(cue(point, "speak", "hologram"))
                .put(cue(JSONObject().put("type", "time").put("at", "18:00"), "speak"))
                .put(cue(JSONObject().put("type", "event").put("event", "sunrise"), "speak"))
                .put(cue(point.toString().let(::JSONObject).put("radiusM", 60), "hologram")))
        }
        val media = ok(read(files(withCues), Strictness.APP, mediaStage = StationValidator.ALL_STAGES)).media
        assertEquals(1, media.cues.size)
        assertEquals(listOf(PresentAs.SPEAK), media.cues[0].present.map { it.presentAs })
        assertEquals(RejectCode.BAD_CUE, code(read(files(withCues), Strictness.REVIEW, mediaStage = StationValidator.ALL_STAGES)))
    }

    @Test
    fun `the package must agree with what the list copied from it (API-002 P-19)`() {
        val publisher = TestPublisher()
        val signed = MemoryStationFiles(publisher.signed(packageFiles(manifestJson(publisher.accountId) {
            put("format", 2); put("activities", JSONArray().put("walk").put("cycle")); put("genres", JSONArray().put("history"))
        })))
        fun check(activities: List<String>?, genres: List<String>? = listOf("history"), requires: List<String>? = emptyList()) = StationValidator.check(
            signed, StationOrigin.THIRD_PARTY, ListedAs("kamakura-history", 3, publisher.accountId, activities, genres, requires), JcaEd25519,
        )
        assertTrue(check(listOf("cycle", "walk")) is StationCheck.Ok)
        assertTrue(check(null, null, null) is StationCheck.Ok)
        assertEquals(RejectCode.BAD_MANIFEST, code(check(listOf("walk"))))
        assertEquals(RejectCode.BAD_MANIFEST, code(check(listOf("walk", "cycle"), genres = emptyList())))
        assertEquals(RejectCode.BAD_MANIFEST, code(check(listOf("walk", "cycle"), requires = listOf("slot:guide"))))
    }

    @Test
    fun `every presentation and trigger has a feature`() {
        PresentAs.entries.forEach { assertEquals("present:${it.json}", Feature.of(it).json) }
        assertEquals(setOf("trigger:spot", "trigger:point", "trigger:event"), Feature.entries.map { it.json }.filter { it.startsWith("trigger:") }.toSet())
    }

    @Test
    fun `the list's values decide before fetching (API-002 P-19, P-20)`() {
        fun listed(activities: List<String> = listOf("walk"), requires: List<String> = emptyList(), versions: Map<String, Int>? = null) = ListedChannel(
            id = "c", version = 1, publisher = "p", publisherName = "p", publisherChange = null, name = "n", summary = "s", description = null,
            lang = listOf("ja"), tags = emptyList(), regions = emptyList(), icon = FileRef("u", "h"), pkg = PackageRef("u", "h", 1, 2),
            minAppVersion = 12, approvedAt = Instant.EPOCH, activities = activities, requires = requires, minAppVersions = versions,
        )
        assertEquals(emptyList<String>(), Compatibility.reasons(listed(listOf("walk", "boat")), walkingApp))
        assertEquals(listOf("not for walk (cycle)"), Compatibility.reasons(listed(listOf("cycle")), walkingApp))
        assertEquals(listOf("needs present:hologram"), Compatibility.reasons(listed(requires = listOf("slot:guide", "present:hologram")), walkingApp))
        // P-20: an app's own entry; none means no requirement; an older list has only SanpoGuide's.
        assertEquals(14, listed(versions = mapOf("sanpoguide" to 14)).minAppVersionFor("sanpoguide"))
        assertNull(listed(versions = mapOf("cycle" to 3)).minAppVersionFor("sanpoguide"))
        assertEquals(12, listed().minAppVersionFor("sanpoguide"))
        assertNull(listed().minAppVersionFor("cycle"))
    }
}
