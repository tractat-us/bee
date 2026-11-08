package us.tractat.bee.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import us.tractat.kuilt.crdt.Dot
import kotlin.jvm.JvmInline
import kotlin.time.Instant

/** An action record's identity: the dot its insert minted in the board's `records` sequence. */
@JvmInline
value class RecordId(val dot: Dot) {
    /** The claim target for this record: `record:<author>:<seq>`. */
    val claimTarget: ClaimTarget get() = ClaimTarget("record:${dot.replica.value}:${dot.seq}")
}

/**
 * What a person or agent did on a surface (§6.2). Immutable: correcting one means appending
 * another, with a new identity.
 *
 * @property basis what the actor's own replica had delivered when they acted, captured then
 * @property context the action's payload, as the renderer reported it
 */
@Serializable
data class ActionRecord(
    val surfaceId: String,
    val componentId: String,
    @Serializable(with = PeerIdentityAsString::class) val actor: PeerIdentity,
    val name: String,
    @Serializable(with = JsonObjectAsString::class) val context: JsonObject,
    val basis: Basis,
    val at: Instant,
)

/** [PeerIdentity] in the form its `toString` writes, so it travels in any format. */
internal object PeerIdentityAsString : KSerializer<PeerIdentity> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("us.tractat.bee.core.PeerIdentity", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: PeerIdentity) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): PeerIdentity = PeerIdentity.parse(decoder.decodeString())
}

/**
 * A [JsonObject] as its JSON text. The board travels as CBOR (the `Quilter` default), and
 * kotlinx's `JsonObject` serializer works only in the JSON format.
 */
internal object JsonObjectAsString : KSerializer<JsonObject> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("us.tractat.bee.core.JsonObjectText", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: JsonObject) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): JsonObject = Json.parseToJsonElement(decoder.decodeString()).jsonObject
}
