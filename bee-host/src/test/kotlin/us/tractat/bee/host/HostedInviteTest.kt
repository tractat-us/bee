package us.tractat.bee.host

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import us.tractat.bee.core.BoardId
import us.tractat.bee.host.Handshakes.Companion.FORBIDDEN
import us.tractat.bee.host.Handshakes.Companion.OK
import us.tractat.bee.host.Handshakes.Companion.SWITCHING
import us.tractat.kuilt.test.assertAll
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A hosted board's invite (bee#215), on a real host started as the `Dockerfile` starts it, over
 * raw HTTP ([Handshakes]) with the headers Fly's proxy forwards ([fly]): an admitted page mints a
 * link, `https://bee.tractat.us/#i=<secret>`, and whoever opens it redeems the secret for a device
 * key of their own, which the socket gate then admits. [InvitesTest] holds the rules (expiry, the
 * cap, the failure limit) on an injected clock; this holds them at the door.
 */
class HostedInviteTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dataDir = Files.createTempDirectory("w215-hosted-invite-")
    private val config = HostConfig.fromArgs(ARGS + dataDir.toString(), home = Path.of("/nonexistent"), environment = OWNER_ENV)
    private var host = runBlocking { BeeHost.start(scope, config) }
    private var calls = Handshakes(host.port)

    private val board get() = "${BoardRelay.PATH}?peer=probe&${BoardId.PARAM}=${host.boardId}"

    @AfterTest
    fun stop() {
        host.close()
        scope.cancel()
        dataDir.toFile().deleteRecursively()
    }

    private fun mint(key: String = FAKE_OWNER_KEY, client: String = OWNER_CLIENT) =
        calls.post("/invite?k=$key", PUBLIC_HOST, "", SAME_ORIGIN, *fly(client))

    /** The secret in a minted link's fragment. */
    private fun secretOf(minted: Pair<String, String>): String =
        Json.parseToJsonElement(minted.second).jsonObject.getValue("link").jsonPrimitive.content.substringAfter("#i=")

    private fun redeem(secret: String, name: String = "sam", client: String = JOINER_CLIENT) =
        calls.post("/invite/redeem", PUBLIC_HOST, """{"invite":"$secret","name":"$name"}""", SAME_ORIGIN, "Content-Type: application/json", *fly(client))

    private fun keyOf(redeemed: Pair<String, String>): String =
        (Json.parseToJsonElement(redeemed.second) as JsonObject).getValue("key").jsonPrimitive.content

    @Test
    fun anAdmittedPageMintsALinkWhoseSecretRedeemsForAKeyTheSocketGateAdmits() {
        val minted = mint()
        val link = Json.parseToJsonElement(minted.second).jsonObject.getValue("link").jsonPrimitive.content
        val secret = secretOf(minted)
        val beforeRedeeming = calls.upgrade("$board&k=$secret", PUBLIC_HOST, PUBLIC_ORIGIN, *fly(JOINER_CLIENT))
        val redeemed = redeem(secret)
        val key = keyOf(redeemed)
        assertAll(
            { assertEquals(OK, minted.first) },
            { assertTrue(link.startsWith("$PUBLIC_URL#i="), "the secret rides in the fragment: $link") },
            { assertTrue(Regex("[A-Za-z0-9_-]{43}").matches(secret), "256 bits, base64url") },
            { assertEquals(FORBIDDEN, beforeRedeeming, "the secret itself is no key") },
            { assertEquals(OK, redeemed.first) },
            { assertFalse(secret in redeemed.second, "the redemption doesn't echo the secret") },
            { assertEquals(SWITCHING, calls.upgrade("$board&k=$key", PUBLIC_HOST, PUBLIC_ORIGIN, *fly(JOINER_CLIENT)), "the key admits the board socket") },
            { assertTrue(host.pairing.admits(key), "a device key, held like a paired one") },
        )
    }

    @Test
    fun theLinkIsReusableEachRedemptionGetsItsOwnKey() {
        val secret = secretOf(mint())
        val first = keyOf(redeem(secret, "sam", "203.0.113.31"))
        val second = keyOf(redeem(secret, "alex", "203.0.113.32"))
        assertAll(
            { assertFalse(first == second, "each device its own key, so one can be evicted alone") },
            { assertTrue(host.pairing.admits(first)) },
            { assertTrue(host.pairing.admits(second)) },
        )
    }

    @Test
    fun onlyAnAdmittedCallerMints() {
        assertAll(
            { assertEquals(FORBIDDEN, mint(key = "").first, "no key") },
            { assertEquals(FORBIDDEN, mint(key = host.pairing.joinKey.value).first, "the join key, which admits nothing on a hosted board") },
            { assertEquals(FORBIDDEN, calls.post("/invite", "127.0.0.1:${host.port}", "").first, "loopback grants nothing") },
            { assertEquals(OK, mint().first, "rig: the owner key mints") },
        )
    }

    @Test
    fun anUnknownSecretIsRefusedAndGivesNoKey() {
        val refused = redeem("A".repeat(43))
        assertAll(
            { assertEquals("404", refused.first) },
            { assertTrue("unknown" in refused.second.lowercase(), refused.second) },
            { assertFalse("\"key\"" in refused.second) },
        )
    }

    @Test
    fun aBodyPastTheBoundIsRefusedUnread() {
        val refused = calls.post("/invite/redeem", PUBLIC_HOST, "x".repeat(4096), SAME_ORIGIN, *fly(JOINER_CLIENT))
        assertEquals("413", refused.first)
    }

    /** A chunked body says no length up front, so the host stops reading at its bound, not at the length. */
    @Test
    fun aChunkedBodyWithNoLengthIsBoundedToo() {
        val refused = calls.postChunked("/invite/redeem", PUBLIC_HOST, List(64) { "x".repeat(128) }, SAME_ORIGIN, *fly(JOINER_CLIENT))
        val small = calls.postChunked("/invite/redeem", PUBLIC_HOST, listOf("""{"invite":"${"A".repeat(43)}",""", """"name":"sam"}"""), SAME_ORIGIN, *fly(JOINER_CLIENT))
        assertAll(
            { assertEquals("413", refused.first) },
            { assertEquals("404", small.first, "rig: a chunked body under the bound is read, and its secret is unknown") },
        )
    }

    /** `--forget-devices` cuts off a key an invite gave, for minting as for the board. */
    @Test
    fun aKeyForgottenByForgetDevicesCantMint() {
        val key = keyOf(redeem(secretOf(mint())))
        val before = mint(key)
        host.close()
        host = runBlocking { BeeHost.start(scope, HostConfig.fromArgs(ARGS + dataDir.toString() + "--forget-devices", home = Path.of("/nonexistent"), environment = OWNER_ENV)) }
        calls = Handshakes(host.port)
        assertAll(
            { assertEquals(OK, before.first, "rig: the invited key minted before") },
            { assertEquals(FORBIDDEN, mint(key).first, "forgotten") },
            { assertEquals(OK, mint().first, "the owner key, held in memory, still mints") },
        )
    }

    @Test
    fun aLiveInviteOutlivesARestart() {
        val secret = secretOf(mint())
        host.close()
        host = runBlocking { BeeHost.start(scope, config) }
        calls = Handshakes(host.port)
        val redeemed = redeem(secret)
        assertAll(
            { assertEquals(OK, redeemed.first) },
            { assertEquals(SWITCHING, calls.upgrade("$board&k=${keyOf(redeemed)}", PUBLIC_HOST, PUBLIC_ORIGIN, *fly(JOINER_CLIENT))) },
            { assertFalse(secret in Files.readString(dataDir.resolve("invites")), "the host keeps the secret's hash, not the secret") },
        )
    }

    @Test
    fun theSecretAppearsInNoLogAndInNoAnswerButTheMint() {
        val logged = ListAppender<ILoggingEvent>().apply { start() }
        val root = LoggerFactory.getLogger("us.tractat.bee") as Logger
        root.addAppender(logged)
        try {
            val minted = mint()
            val secret = secretOf(minted)
            val answers = listOf(
                redeem(secret).second,
                redeem(secret, client = "203.0.113.40").second,
                calls.fetch("/invite?k=$FAKE_OWNER_KEY", PUBLIC_HOST, SAME_ORIGIN, *fly(OWNER_CLIENT)).second,
                calls.post("/invite/redeem", PUBLIC_HOST, """{"invite":"$secret","name":"bad:name"}""", SAME_ORIGIN, *fly(JOINER_CLIENT)).second,
            )
            val lines = logged.list.map { it.formattedMessage + (it.throwableProxy?.message ?: "") }
            assertAll(
                { assertTrue(lines.isNotEmpty(), "rig: the host logged the invite's mint and redemptions") },
                { assertEquals(emptyList(), lines.filter { secret in it }, "no log line holds the secret") },
                { assertEquals(emptyList(), answers.filter { secret in it }, "no answer but the mint's holds it") },
            )
        } finally {
            root.detachAppender(logged)
        }
    }

    private companion object {
        const val PUBLIC_URL = "https://bee.tractat.us/"
        const val PUBLIC_HOST = "bee.tractat.us"
        const val PUBLIC_ORIGIN = "https://bee.tractat.us"
        const val APP = "192.0.2.1"
        const val OWNER_CLIENT = "203.0.113.10"
        const val JOINER_CLIENT = "203.0.113.30"
        const val SAME_ORIGIN = "Sec-Fetch-Site: same-origin"

        /** The `Dockerfile`'s command line, up to the data dir. */
        val ARGS = listOf("--public-url", PUBLIC_URL, "--client-ip-header", "Fly-Client-IP", "--port", "0", "--data-dir")
        val OWNER_ENV = { name: String -> FAKE_OWNER_KEY.takeIf { name == OwnerKey.ENV } }

        /** The headers Fly's proxy forwards a call from [client] with. */
        fun fly(client: String): Array<String> = arrayOf("X-Forwarded-For: $client, $APP", "Fly-Client-IP: $client")
    }
}
