package us.tractat.bee.a2ui

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhotoTest {
    /** A data URL of [type] whose whole length is [chars]. */
    private fun dataUrl(chars: Int, type: String = "image/jpeg"): String {
        val prefix = "data:$type;base64,"
        return prefix + "A".repeat(chars - prefix.length)
    }

    private val jpeg = "data:image/jpeg;base64,/9j/4AAQSkZJRg=="

    @Test
    fun aPhotoAtTheBudgetIsTakenAndOneCharacterOverIsRefused() {
        assertAll(
            { assertEquals("image/jpeg", Photo.parse(dataUrl(Photo.MAX_IMAGE_CHARS)).mimeType) },
            {
                val refused = assertFailsWith<IllegalArgumentException> { Photo.parse(dataUrl(Photo.MAX_IMAGE_CHARS + 1)) }
                assertTrue("over the board's limit" in refused.message.orEmpty(), refused.message)
            },
        )
    }

    @Test
    fun theBudgetLeavesRoomForAboutOneHundredSeventyPhotosInAFullState() {
        val fullStateBound = 64L * 1024 * 1024
        assertEquals(170L, fullStateBound / Photo.MAX_IMAGE_CHARS)
    }

    @Test
    fun onlyJpegAndWebpAreTaken() {
        assertAll(
            { assertEquals("image/webp", Photo.parse(dataUrl(100, "image/webp")).mimeType) },
            { assertFailsWith<IllegalArgumentException> { Photo.parse(dataUrl(100, "image/svg+xml")) } },
            { assertFailsWith<IllegalArgumentException> { Photo.parse(dataUrl(100, "image/png")) } },
            { assertFailsWith<IllegalArgumentException> { Photo.parse("data:image/jpeg,<svg onload=alert(1)>") } },
            { assertFailsWith<IllegalArgumentException> { Photo.parse("https://example.com/a.jpg") } },
        )
    }

    @Test
    fun anImagesSizeIsItsDecodedBytes() {
        assertAll(
            { assertEquals(10, Photo.parse(jpeg).bytes) },
            { assertEquals(3, Photo.parse("data:image/jpeg;base64,AAAA").bytes) },
        )
    }

    @Test
    fun takingAPhotoWritesTheImageAndWhoTookItAtTheBinding() {
        assertEquals(
            NotionEdit("/shot", buildJsonObject { put("image", jpeg); put("by", "human:sam") }),
            NotionActions.edit(photo(), take(jpeg), "human:sam"),
        )
    }

    @Test
    fun takingAPhotoOverTheBudgetIsRefusedAndWritesNothing() {
        assertFailsWith<IllegalArgumentException> {
            NotionActions.edit(photo(), take(dataUrl(Photo.MAX_IMAGE_CHARS + 1)), "human:sam")
        }
    }

    @Test
    fun aPhotosRecordKeepsItsTypeAndSizeButNotItsBytes() {
        assertEquals(
            buildJsonObject { put("type", "image/jpeg"); put("bytes", 10) },
            NotionActions.recordedContext(photo(), take(jpeg)),
        )
    }

    @Test
    fun anyOtherRecordKeepsItsWholeContext() {
        val press = A2uiAction("press", "s", "photo", "2026-11-08T12:00:00Z", buildJsonObject { put("image", jpeg) })
        assertEquals(press.context, NotionActions.recordedContext(photo(), press))
    }

    @Test
    fun theShownPhotoIsWhatTheBindingHolds() {
        val taken = photo(data = buildJsonObject { putJsonObject("shot") { put("image", jpeg); put("by", "human:sam") } })
        assertAll(
            { assertEquals(Photo.Image("image/jpeg", "/9j/4AAQSkZJRg=="), Photo.shown(taken, "photo")) },
            { assertNull(Photo.shown(photo(), "photo")) },
            { assertNull(Photo.shown(taken, "nothing")) },
        )
    }

    @Test
    fun elidingReplacesEveryImageDataUrlWithANoteAndLeavesTheRestAlone() {
        val data = buildJsonObject {
            putJsonObject("shot") { put("image", jpeg); put("by", "human:sam") }
            put("list", JsonArray(listOf(JsonPrimitive(jpeg), JsonPrimitive("plain"))))
        }
        val elided = Photo.elided(data) as JsonObject
        val note = "[photo: image/jpeg, 10 bytes; bee_wait_action returns it as an image]"
        assertAll(
            { assertEquals(JsonPrimitive(note), (elided["shot"] as JsonObject)["image"]) },
            { assertEquals(JsonPrimitive("human:sam"), (elided["shot"] as JsonObject)["by"]) },
            { assertEquals(JsonArray(listOf(JsonPrimitive(note), JsonPrimitive("plain"))), elided["list"]) },
        )
    }

    @Test
    fun aPhotoNeedsItsBinding() {
        val unbound = photo(component = buildJsonObject { put("id", "photo"); put("component", "Photo"); put("photo", "/shot") })
        assertAll(
            { assertEquals(emptyList(), SurfaceValidator.errors(photo())) },
            {
                assertEquals(
                    listOf("Component \"photo\" is a Photo whose photo isn't a data binding ({\"path\": \"/…\"})."),
                    SurfaceValidator.errors(unbound),
                )
            },
        )
    }

    private fun photo(
        component: JsonObject = buildJsonObject {
            put("id", "photo")
            put("component", SurfaceValidator.PHOTO)
            put("prompt", "The whiteboard")
            putJsonObject("photo") { put("path", "/shot") }
        },
        data: JsonObject = JsonObject(emptyMap()),
    ) = SurfaceView(
        id = "s",
        catalogId = NOTIONS_CATALOG_ID,
        components = mapOf(
            "root" to buildJsonObject { put("id", "root"); put("component", "Column"); put("children", JsonArray(listOf(JsonPrimitive("photo")))) },
            "photo" to component,
        ),
        data = data,
        conflicts = emptyMap(),
        appended = emptySet(),
    )

    private fun take(image: String) = A2uiAction(
        name = Photo.TAKE,
        surfaceId = "s",
        sourceComponentId = "photo",
        timestamp = "2026-11-08T12:00:00Z",
        context = buildJsonObject { put("image", image) },
    )
}
