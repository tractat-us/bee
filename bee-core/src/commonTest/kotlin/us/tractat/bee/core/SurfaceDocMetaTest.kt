package us.tractat.bee.core

import us.tractat.bee.a2ui.SurfaceFormat
import us.tractat.bee.core.BoardFixtures.A
import us.tractat.kuilt.crdt.JsonCrdt
import us.tractat.kuilt.crdt.JsonNode
import us.tractat.kuilt.crdt.JsonValue
import us.tractat.kuilt.crdt.MVRegister
import us.tractat.kuilt.crdt.piece
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SurfaceDocMetaTest {
    @Test
    fun missingMetaIsNotYetOpenButWrongTypedMetaThrows() {
        val leaf = JsonNode.Leaf(MVRegister.empty<JsonValue>().set(A, JsonValue.Str("oops")))
        val wrongTyped = JsonCrdt.empty(A).piece { it.set(SurfaceFormat.META, leaf) }
        assertNull(SurfaceDoc.meta(JsonCrdt.empty(A)))
        assertFailsWith<ClassCastException> { SurfaceDoc.meta(wrongTyped) }
    }
}
