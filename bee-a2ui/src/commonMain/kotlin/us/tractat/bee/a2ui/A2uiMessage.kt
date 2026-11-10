package us.tractat.bee.a2ui

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The Notions catalog: the basic catalog's components plus `Sketch`, `Board` and `Poll`. */
const val NOTIONS_CATALOG_ID = "https://tractat.us/bee/catalogs/notions/v1.json"

/** The A2UI protocol bee speaks: v1.0, whose envelope says `"1.0"`. */
private const val PROTOCOL_VERSION = "1.0"

/** An A2UI v1.0 agent-to-renderer message, as bee's driver sends it to the renderer. */
sealed interface A2uiMessage {
    val surfaceId: String

    data class CreateSurface(
        override val surfaceId: String,
        val catalogId: String,
        val sendDataModel: Boolean = false,
    ) : A2uiMessage

    data class UpdateComponents(override val surfaceId: String, val components: List<JsonObject>) : A2uiMessage

    /**
     * Sets the value at [path], or deletes it when [value] is null (v1.0 sends `"value": null`).
     * Kotlin `null` is the one spelling of delete: a [JsonNull] value is refused.
     */
    data class UpdateDataModel(override val surfaceId: String, val path: String, val value: JsonElement?) : A2uiMessage {
        init {
            require(value !is JsonNull) { "UpdateDataModel at $path: pass null to delete, not JsonNull." }
        }
    }

    data class DeleteSurface(override val surfaceId: String) : A2uiMessage
}

/** A user action, as the renderer reports it. */
@Serializable
data class A2uiAction(
    val name: String,
    val surfaceId: String,
    val sourceComponentId: String,
    val timestamp: String,
    val context: JsonObject,
)

/** The wire form of [A2uiMessage] and [A2uiAction]. */
object A2uiJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(messages: List<A2uiMessage>): String = JsonArray(messages.map(::envelope)).toString()

    fun decodeAction(json: String): A2uiAction = this.json.decodeFromString(A2uiAction.serializer(), json)

    private fun envelope(message: A2uiMessage): JsonObject = buildJsonObject {
        put("version", PROTOCOL_VERSION)
        when (message) {
            is A2uiMessage.CreateSurface -> put(
                "createSurface",
                buildJsonObject {
                    put("surfaceId", message.surfaceId)
                    put("catalogId", message.catalogId)
                    put("sendDataModel", message.sendDataModel)
                },
            )
            is A2uiMessage.UpdateComponents -> put(
                "updateComponents",
                buildJsonObject {
                    put("surfaceId", message.surfaceId)
                    put("components", JsonArray(message.components))
                },
            )
            is A2uiMessage.UpdateDataModel -> put(
                "updateDataModel",
                buildJsonObject {
                    put("surfaceId", message.surfaceId)
                    put("path", JsonPrimitive(message.path))
                    put("value", message.value ?: JsonNull)
                },
            )
            is A2uiMessage.DeleteSurface -> put("deleteSurface", buildJsonObject { put("surfaceId", message.surfaceId) })
        }
    }
}
