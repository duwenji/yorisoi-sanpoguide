package io.github.duwenji.yorisoi.sanpoguide.walk

import io.github.duwenji.yorisoi.sanpoguide.companion.WalkPlan
import java.io.File
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ActiveWalkPlanStoreTest {
    @Rule
    @JvmField
    val temp = TemporaryFolder()

    @Test
    fun `saved plan is available after store recreation and disappears after clear`() {
        val file = File(temp.root, "active_walk_plan.json")
        val plan = WalkPlan(themeCategory = "公園", durationMinutes = 60)

        ActiveWalkPlanStore(file).save(plan)
        assertEquals(plan, ActiveWalkPlanStore(file).load())

        ActiveWalkPlanStore(file).clear()
        assertNull(ActiveWalkPlanStore(file).load())
    }

    @Test
    fun `malformed active plan is reported instead of becoming an empty plan`() {
        val file = File(temp.root, "active_walk_plan.json").apply { writeText("{") }

        assertThrows(JSONException::class.java) { ActiveWalkPlanStore(file).load() }
    }
}
