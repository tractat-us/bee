package us.tractat.bee.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OrphanDecisionTest {
    private val round1 = ClaimToken(1, 1)
    private val round2 = ClaimToken(1, 3)

    /**
     * An answer decides the orphaning the person saw only if it is still the one it is recorded
     * against (bee#83). A round landing between the click and the record refuses the answer, so it
     * is neither written to round 2's answers nor proposed for round 1. The control: both reads
     * naming round 1 decide round 1.
     */
    @Test
    fun anAnswerDecidesTheOrphaningBothReadsName() {
        assertAll(
            { assertEquals(round1, OrphanDecision.answered(seen = open(round1), current = open(round1)), "control: one orphaning in both reads") },
            { assertFailsWith<IllegalArgumentException>("a round landed after the click") { OrphanDecision.answered(seen = open(round1), current = open(round2)) } },
            { assertFailsWith<IllegalArgumentException>("the decision wasn't on screen") { OrphanDecision.answered(seen = null, current = open(round1)) } },
            { assertFailsWith<IllegalArgumentException>("the screen named no orphaning") { OrphanDecision.answered(seen = JsonObject(emptyMap()), current = open(round1)) } },
        )
    }

    private fun open(token: ClaimToken): JsonObject = buildJsonObject {
        put(OrphanDecision.TOKEN, token.text)
    }
}
