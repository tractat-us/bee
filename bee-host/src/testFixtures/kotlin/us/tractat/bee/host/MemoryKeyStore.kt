package us.tractat.bee.host

import java.security.SecureRandom

/** A [KeyStore] in memory: a test's host keys, for a rig that never restarts or reads its file. */
class MemoryKeyStore : KeyStore {
    @Volatile
    private var kept: StoredKeys? = null

    override fun load(): StoredKeys? = kept

    override fun save(keys: StoredKeys) {
        kept = keys
    }
}

/** Fresh [HostKeys] in a [MemoryKeyStore], their join key from [secrets]. */
fun memoryKeys(secrets: PairingSecrets = SecureSecrets(SecureRandom())): HostKeys =
    HostKeys.boot(MemoryKeyStore(), secrets, KeyReset.Keep, JoinLink.Admits)
