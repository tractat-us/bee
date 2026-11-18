package us.tractat.bee.a2ui

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class A2uiJsonTest {
    @Test
    fun createSurfaceEncodesTheEnvelope() {
        assertEquals(
            """[{"version":"1.0","createSurface":{"surfaceId":"s1",""" +
                """"catalogId":"https://tractat.us/bee/catalogs/notions/v1.json","sendDataModel":false}}]""",
            A2uiJson.encode(listOf(A2uiMessage.CreateSurface("s1", NOTIONS_CATALOG_ID))),
        )
    }

    @Test
    fun componentsAndDataEncodeInOrder() {
        val text = buildJsonObject {
            put("id", "root")
            put("component", "Text")
            put("text", "hi")
        }
        assertEquals(
            """[{"version":"1.0","updateComponents":{"surfaceId":"s1","components":""" +
                """[{"id":"root","component":"Text","text":"hi"}]}},""" +
                """{"version":"1.0","updateDataModel":{"surfaceId":"s1","path":"/n","value":3}}]""",
            A2uiJson.encode(
                listOf(
                    A2uiMessage.UpdateComponents("s1", listOf(text)),
                    A2uiMessage.UpdateDataModel("s1", "/n", JsonPrimitive(3)),
                ),
            ),
        )
    }

    @Test
    fun deleteEncodesInThePinnedForm() {
        // A2UI v1.0 requires `value`, and deletes the value at a path when it is null.
        assertAll(
            {
                assertEquals(
                    """[{"version":"1.0","updateDataModel":{"surfaceId":"s1","path":"/n","value":null}}]""",
                    A2uiJson.encode(listOf(A2uiMessage.UpdateDataModel("s1", "/n", null))),
                )
            },
            {
                assertEquals(
                    """[{"version":"1.0","deleteSurface":{"surfaceId":"s1"}}]""",
                    A2uiJson.encode(listOf(A2uiMessage.DeleteSurface("s1"))),
                )
            },
        )
    }

    @Test
    fun jsonNullIsNotASecondSpellingOfDelete() {
        assertFailsWith<IllegalArgumentException> { A2uiMessage.UpdateDataModel("s1", "/n", JsonNull) }
    }

    @Test
    fun actionDecodesFromTheRendererShape() {
        // The ActionPayload web_core's MessageProcessor hands its action listener, as
        // bee-notions' renderer host stringifies it. Extra fields are ignored.
        val action = A2uiJson.decodeAction(
            """{"name":"press","surfaceId":"spike-1","sourceComponentId":"press-button",""" +
                """"timestamp":"2026-11-06T20:00:00.000Z","context":{"n":1},""" +
                """"catalogId":"https://tractat.us/bee/catalogs/notions/v1.json"}""",
        )
        assertEquals(
            A2uiAction(
                name = "press",
                surfaceId = "spike-1",
                sourceComponentId = "press-button",
                timestamp = "2026-11-06T20:00:00.000Z",
                context = buildJsonObject { put("n", 1) },
            ),
            action,
        )
    }
}
