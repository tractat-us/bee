package us.tractat.bee.host

import java.nio.file.Path

/** An obviously fake owner key for tests: 43 characters of base64url, as a real one is ([OwnerKey]). */
internal const val FAKE_OWNER_KEY = "fake-owner-key-for-tests-only-0000000000000"

/**
 * A public host at `https://bee.tractat.us/`, owned by [FAKE_OWNER_KEY], started as the
 * `Dockerfile` starts it behind Fly's proxy (`--client-ip-header Fly-Client-IP`), with its data in
 * [dataDir], on an ephemeral port, and any [more] flags.
 */
internal fun fakePublicHost(dataDir: Path, vararg more: String): HostConfig = HostConfig.fromArgs(
    listOf("--public-url", "https://bee.tractat.us/", "--client-ip-header", "Fly-Client-IP", "--port", "0", "--data-dir", dataDir.toString()) + more,
    home = Path.of("/nonexistent"),
    environment = { name -> FAKE_OWNER_KEY.takeIf { name == OwnerKey.ENV } },
)
