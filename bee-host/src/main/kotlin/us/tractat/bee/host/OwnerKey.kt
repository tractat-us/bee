package us.tractat.bee.host

/**
 * The key a hosted board's owner holds ([Reach.Public], bee#145): on a host behind a proxy nobody
 * is on loopback, so the owner proves themselves with this. The operator supplies it as
 * [ENV]; the host admits it as it admits a paired device's key, keeps it in memory alone (never in
 * the data dir, never in a log), and so it survives `--forget-devices`.
 *
 * Only [parse] makes one, so a key too short to be unguessable never admits anyone.
 */
@JvmInline
value class OwnerKey private constructor(val key: DeviceKey) {
    override fun toString(): String = "OwnerKey(…)"

    companion object {
        /** The environment variable the owner key comes from. */
        const val ENV: String = "BEE_OWNER_KEY"

        /** 256 bits in base64url, unpadded: what [SecureSecrets.key] mints. */
        const val MIN_LENGTH: Int = 43

        private val BASE64URL = Regex("[A-Za-z0-9_-]+")

        /**
         * [text] as an owner key: at least [MIN_LENGTH] characters of base64url, unpadded.
         *
         * @throws IllegalArgumentException naming [ENV] when there is none, or it isn't one. The
         *   message never holds the value.
         */
        fun parse(text: String?): OwnerKey {
            require(!text.isNullOrEmpty()) { "A host with --public-url needs its owner's key in $ENV: none is set." }
            require(text.length >= MIN_LENGTH && BASE64URL.matches(text)) {
                "$ENV must be at least $MIN_LENGTH characters of base64url (A-Z, a-z, 0-9, - and _), such as 32 random bytes encoded: " +
                    "the one set has ${text.length} characters${if (BASE64URL.matches(text)) "" else ", not all base64url"}."
            }
            return OwnerKey(DeviceKey(text))
        }
    }
}
