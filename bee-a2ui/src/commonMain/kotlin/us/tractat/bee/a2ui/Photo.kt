package us.tractat.bee.a2ui

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The `Photo` Notion: a person's phone camera, one shot, shared on the board for people and
 * agents (`bee-notions/src/photo.js`).
 *
 * The photo binds `photo`, a data path holding `{image, by}`: `image` a data URL the page made
 * by drawing the shot on a canvas and encoding it as JPEG, and `by` the peer who took it. The
 * canvas re-encode is what strips EXIF, location included, since a canvas holds only pixels.
 * Taking one is the action [TAKE], with context `{image}`, which writes `photo` from the
 * register just read, as a Poll answer writes `answers/<peer>`. The record keeps only
 * [recordedContext], so a retake overwrites one leaf and the board's permanent record sequence
 * never holds image bytes.
 */
object Photo {
    /** Taking or retaking the photo: context `{image}`, a data URL. */
    const val TAKE = "photo"

    /**
     * The longest data URL a Photo holds, in characters (each one byte on the wire). 384 KiB fits a
     * 1600-pixel JPEG of a whiteboard or a page of paper, which is what the page aims for. A board
     * `FullState` is bounded at 64 MiB (`MAX_FRAME_BYTES`) and carries closed surfaces too, so a
     * board holds about 170 photos over its life before it stops syncing. That ceiling is why the
     * budget is this tight.
     */
    const val MAX_IMAGE_CHARS: Int = 384 * 1024

    /** What a Photo shows: no SVG, which can carry script, and no PNG, which a camera shot bloats. */
    val TYPES: Set<String> = setOf("image/jpeg", "image/webp")

    private val DATA_URL = Regex("^data:(image/[a-z+.-]+);base64,([A-Za-z0-9+/]+={0,2})$")

    /** A photo's bytes, as base64, and their type. */
    data class Image(val mimeType: String, val base64: String) {
        /** The decoded size, in bytes. */
        val bytes: Int get() = base64.length / 4 * 3 - base64.takeLast(2).count { it == '=' }
    }

    /**
     * The image [dataUrl] holds.
     *
     * @throws IllegalArgumentException if it's over [MAX_IMAGE_CHARS], not a base64 data URL, or
     *   not one of [TYPES]
     */
    fun parse(dataUrl: String): Image {
        require(dataUrl.length <= MAX_IMAGE_CHARS) {
            "The photo is ${dataUrl.length} characters, over the board's limit of $MAX_IMAGE_CHARS."
        }
        val match = requireNotNull(DATA_URL.matchEntire(dataUrl)) { "The photo isn't a base64 image data URL." }
        val (type, base64) = match.destructured
        require(type in TYPES) { "The photo is $type; a Photo holds ${TYPES.sorted().joinToString(" or ")}." }
        return Image(type, base64)
    }

    /** The image the Photo [componentId] on [view] shows now, or null if none has been taken. */
    fun shown(view: SurfaceView, componentId: String): Image? {
        val component = view.components[componentId] ?: return null
        if ((component["component"] as? JsonPrimitive)?.content != SurfaceValidator.PHOTO) return null
        val path = ((component["photo"] as? JsonObject)?.get("path") as? JsonPrimitive)?.content ?: return null
        val held = SurfaceFormat.segments(path).fold(view.data as JsonElement?) { node, key -> (node as? JsonObject)?.get(key) }
        val image = ((held as? JsonObject)?.get("image") as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        return runCatching { parse(image) }.getOrNull()
    }

    /** The data a [TAKE] by [actor] writes at the Photo's binding. */
    internal fun taken(action: A2uiAction, actor: String): JsonObject {
        val image = requireNotNull((action.context["image"] as? JsonPrimitive)?.takeIf { it.isString }?.content) {
            "A $TAKE on \"${action.sourceComponentId}\" has no image."
        }
        parse(image)
        return buildJsonObject {
            put("image", image)
            put("by", actor)
        }
    }

    /** What a [TAKE]'s record keeps: the type and size, never the bytes, which live in the Photo's data. */
    internal fun recordedContext(action: A2uiAction): JsonObject {
        val image = (action.context["image"] as? JsonPrimitive)?.content.orEmpty()
        val parsed = parse(image)
        return buildJsonObject {
            put("type", parsed.mimeType)
            put("bytes", parsed.bytes)
        }
    }

    /**
     * [element] with every image data URL replaced by a short note naming its type and size: what
     * an agent reads in `bee_board`, where a photo's base64 would fill its context as text.
     */
    fun elided(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.mapValues { (_, v) -> elided(v) })
        is JsonArray -> JsonArray(element.map(::elided))
        is JsonPrimitive -> if (element.isString && element.content.startsWith("data:image/")) {
            val image = runCatching { parse(element.content) }.getOrNull()
            JsonPrimitive(
                image?.let { "[photo: ${it.mimeType}, ${it.bytes} bytes; bee_wait_action returns it as an image]" }
                    ?: "[an image data URL a Photo can't show]",
            )
        } else {
            element
        }
    }
}
