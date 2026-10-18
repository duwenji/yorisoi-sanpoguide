package io.github.duwenji.yorisoi.sanpoguide.diagnostics

import io.github.duwenji.yorisoi.core.format.TalkEventKind
import io.github.duwenji.yorisoi.kit.geo.Facility
import io.github.duwenji.yorisoi.kit.geo.FacilityKind
import io.github.duwenji.yorisoi.kit.geo.Poi
import io.github.duwenji.yorisoi.sanpoguide.companion.FacilityAdvice
import io.github.duwenji.yorisoi.sanpoguide.companion.FacilityNeed
import io.github.duwenji.yorisoi.sanpoguide.companion.TalkDecision
import io.github.duwenji.yorisoi.sanpoguide.companion.TalkSituation
import io.github.duwenji.yorisoi.sanpoguide.settings.TalkLevel
import io.github.duwenji.yorisoi.sanpoguide.settings.Thresholds
import okhttp3.FormBody
import okhttp3.Request
import io.github.duwenji.yorisoi.sanpoguide.station.BuiltInStations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class DiagnosticFieldsTest {
    private val situation = TalkSituation(
        now = 1_000_000L, weather = null, limits = Thresholds(),
        level = TalkLevel.NORMAL, events = TalkEventKind.entries.toSet(), preferredCategories = emptySet(), skippedCategories = emptySet(),
        spots = emptyList(), facilities = emptyList(), pointCue = null,
        elapsedMs = 600_000L, distanceM = 300.0, restMinutes = 0, restRemarked = false,
        lastSpokeAt = 0L, lastSpotTalkAt = 0L, lastMilestoneAt = 0L, lastMilestoneDistanceM = 0.0,
        sunsetWarned = false, weatherWarnedAt = emptyMap(), mentionedFacilities = emptySet(), facilityMentionAt = emptyMap(),
        pendingGreeting = null, pendingSelect = false, justSwitched = false,
    )

    @Test
    fun `a decision's fields and the situation's are written side by side without overwriting each other`() {
        val decisions = listOf(
            TalkDecision.Spot(Poi("way/1", "鶴岡八幡宮", 35.0, 139.0, "神社", emptyMap()), 40),
            TalkDecision.NearFacility(FacilityAdvice(Facility("t1", FacilityKind.TOILETS, 35.0, 139.0, null), FacilityNeed.entries.first(), 40)),
        )
        val situationKeys = DiagnosticFields.situation(situation).map { it.first }.toSet()
        decisions.forEach { d ->
            val keys = DiagnosticFields.decision(d).map { it.first }.toSet()
            assertEquals(emptySet<String>(), keys intersect situationKeys)
        }
    }

    @Test
    fun `the spot's distance and the distance walked are both kept`() {
        val fields = (DiagnosticFields.decision(TalkDecision.Spot(Poi("way/1", "鶴岡八幡宮", 35.0, 139.0, "神社", emptyMap()), 40)) +
            DiagnosticFields.situation(situation)).toMap()
        assertEquals(40, fields["distanceM"])
        assertEquals(300, fields["walkedM"])
    }

    private fun overpassRequest(query: String) = Request.Builder()
        .url("https://overpass-api.de/api/interpreter")
        .post(FormBody.Builder().add("data", query).build())
        .build()

    private val spotsQuery = """
        [out:json][timeout:25];
        (
          nwr(around:600,35.3260829,139.5564994)["name"]["historic"];
        );
        out tags geom 80;
        nwr(around:250,35.3260829,139.5564994)["amenity"="bench"];
        out center 40;
    """.trimIndent()

    @Test
    fun `an Overpass query is recorded by where it searched, with the shops' radius only when shops were asked for`() {
        val plain = DiagnosticFields.overpassQuery(overpassRequest(spotsQuery)).toMap()
        assertEquals(35.3260829, plain["lat"])
        assertEquals(139.5564994, plain["lon"])
        assertEquals(600, plain["radiusM"])
        assertNull(plain["shopRadiusM"])
        assertEquals(spotsQuery.length, plain["queryChars"])

        val withShops = spotsQuery + "\n(\n  nwr(around:400,35.3260829,139.5564994)[\"name\"][\"amenity\"=\"cafe\"];\n);\nout tags center 60;"
        assertEquals(400, DiagnosticFields.overpassQuery(overpassRequest(withShops)).toMap()["shopRadiusM"])
    }

    @Test
    fun `an Overpass answer is recorded by its size and elements, and a broken one without the count`() {
        val answer = DiagnosticFields.overpassAnswer("""{"elements":[{"id":1},{"id":2}]}""").toMap()
        assertEquals(2, answer["elements"])
        assertEquals(32, answer["bytes"])
        assertNull(DiagnosticFields.overpassAnswer("<html>busy</html>").toMap()["elements"])
    }

    @Test
    fun `a spot search names the channel, lists the spots nearest first and counts the amenities by kind`() {
        val standard = BuiltInStations.load().first { it.id == BuiltInStations.STANDARD }
        val far = Poi("way/1", "鶴岡八幡宮", 35.0, 139.0, "神社", emptyMap())
        val near = Poi("node/2", "時計台の由来", 35.0, 139.0, "史跡", emptyMap())
        val fields = DiagnosticFields.spotSearch(
            listOf(far to 320.5f, near to 12f),
            listOf(Facility("t1", FacilityKind.TOILETS, 35.0, 139.0, null), Facility("b1", FacilityKind.BENCH, 35.0, 139.0, null)),
            standard,
            listOf(standard),
        ).toMap()
        assertEquals(BuiltInStations.STANDARD, (fields["channel"] as org.json.JSONObject).getString("id"))
        assertEquals(2, fields["spotCount"])
        val spots = fields["spots"] as org.json.JSONArray
        assertEquals("node/2", spots.getJSONObject(0).getString("poiId"))
        assertEquals(320, spots.getJSONObject(1).getInt("distanceM"))
        // A built-in channel holds no materials, so no spot names it.
        assertFalse(spots.getJSONObject(0).has("channels"))
        val facilities = fields["facilities"] as org.json.JSONObject
        assertEquals(1, facilities.getInt("TOILETS"))
        assertEquals(0, facilities.getInt("SHELTER"))
    }
}
