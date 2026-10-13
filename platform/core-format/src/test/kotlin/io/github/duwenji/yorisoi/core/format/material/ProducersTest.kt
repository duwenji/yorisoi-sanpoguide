package io.github.duwenji.yorisoi.core.format.material

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** API-004「組み込みの制作元」and 方針 P-1. */
class ProducersTest {
    @Test
    fun builtInProducersAreAllowed() {
        BuiltInProducers.all.forEach { assertNull(it.id, ProducerPolicy.violation(it)) }
    }

    @Test
    fun builtInIdsAreUniqueAndPrefixed() {
        val ids = BuiltInProducers.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { it.startsWith(BuiltInProducers.PREFIX) })
        ids.forEach { assertEquals(it, BuiltInProducers.byId(it)?.id) }
        assertNull(BuiltInProducers.byId("builtin:unknown"))
    }

    @Test
    fun reviewedProducerMayOnlyPrefetchFixedMaterialWithoutExposingTheSubject() {
        val collection = ProducerDescriptor(
            "collection:kamakura-audio-guide", 2, setOf(MaterialType.AUDIO), setOf(SubjectKind.SPOT),
            Trust.REVIEWED, Fetch.PREFETCH, Exposure.NONE, generated = false,
        )
        assertNull(ProducerPolicy.violation(collection))
        assertNotNull(ProducerPolicy.violation(collection.copy(fetch = Fetch.ON_DEMAND)))
        assertNotNull(ProducerPolicy.violation(collection.copy(exposure = Exposure.SUBJECT)))
        assertNotNull(ProducerPolicy.violation(collection.copy(generated = true)))
        assertNotNull(ProducerPolicy.violation(collection.copy(trust = Trust.TRIAL, fetch = Fetch.ON_DEMAND)))
    }
}
