package io.github.duwenji.sanpoguide.material

import io.github.duwenji.sanpoguide.material.MaterialResult.Reason
import io.github.duwenji.michizure.core.format.material.BuiltInProducers
import io.github.duwenji.michizure.core.format.material.Exposure
import io.github.duwenji.michizure.core.format.material.Fetch
import io.github.duwenji.michizure.core.format.material.MaterialType
import io.github.duwenji.michizure.core.format.material.ProducerDescriptor
import io.github.duwenji.michizure.core.format.material.SubjectKind
import io.github.duwenji.michizure.core.format.material.Trust
import io.github.duwenji.sanpoguide.station.BuiltInStations
import io.github.duwenji.sanpoguide.station.Station
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Trying candidates in order (API-004 I-7, 確認観点 W-02) and the policy check (P-1). */
class ProducersTest {
    private val standard = BuiltInStations.standardPackage()
    private val request = MaterialRequest(Subject.None, MaterialType.TEXT, MaterialContext(Station(standard, standard, "builtin:standard@1")))

    /** Answers [result] and remembers what it was asked. */
    private class Fake(id: String, val result: () -> MaterialResult) : Producer {
        override val descriptor = BuiltInProducers.FALLBACK_TEXT.copy(id = id)
        var asked: MaterialRequest? = null
        override suspend fun produce(request: MaterialRequest): MaterialResult {
            asked = request
            return result()
        }
    }

    private fun text(id: String, s: String) = Fake(id) { MaterialResult.Ready(Material(MaterialType.TEXT, Content.Text(s), id)) }

    private fun producers(vararg p: Producer) = Producers(p.toList(), log = { _, _ -> })

    private fun MaterialResult.text() = ((this as MaterialResult.Ready).material.content as Content.Text).text

    @Test
    fun `the first that produces wins and later ones aren't asked`() = runBlocking {
        val second = text("b", "second")
        val third = text("c", "third")
        val all = producers(Fake("a") { MaterialResult.None }, second, third)
        assertEquals("second", all.first(listOf("a", "b", "c"), request).text())
        assertNull(third.asked)
    }

    @Test
    fun `a fallback hears why the one before it couldn't produce`() = runBlocking {
        val fallback = text("b", "fixed")
        val all = producers(Fake("a") { MaterialResult.Unavailable(Reason.NO_KEY) }, fallback)
        all.first(listOf("a", "b"), request)
        assertEquals(Reason.NO_KEY, fallback.asked?.context?.after?.reason)
    }

    @Test
    fun `a producer that throws counts as failed`() = runBlocking {
        val fallback = text("b", "fixed")
        val all = producers(Fake("a") { error("boom") }, fallback)
        assertEquals("fixed", all.first(listOf("a", "b"), request).text())
        assertEquals(Reason.FAILED, fallback.asked?.context?.after?.reason)
        assertEquals("boom", fallback.asked?.context?.after?.detail)
    }

    @Test
    fun `unknown ids are skipped and nothing answering gives the last result`() = runBlocking {
        val all = producers(Fake("a") { MaterialResult.Unavailable(Reason.METERED) })
        assertEquals(MaterialResult.Unavailable(Reason.METERED), all.first(listOf("builtin:newer", "a"), request))
        assertEquals(MaterialResult.None, all.first(listOf("builtin:newer"), request))
    }

    @Test
    fun `a reviewed producer that would fetch on demand is refused`() {
        val onDemand = object : Producer {
            override val descriptor = ProducerDescriptor(
                "collection:x", 1, setOf(MaterialType.AUDIO), setOf(SubjectKind.SPOT),
                Trust.REVIEWED, Fetch.ON_DEMAND, Exposure.NONE, generated = false,
            )
            override suspend fun produce(request: MaterialRequest) = MaterialResult.None
        }
        val failure = runCatching { producers(onDemand) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
    }
}
