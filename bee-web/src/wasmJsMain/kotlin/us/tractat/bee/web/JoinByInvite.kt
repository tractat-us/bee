package us.tractat.bee.web

import kotlinx.browser.document
import kotlinx.browser.sessionStorage
import kotlinx.browser.window
import kotlinx.serialization.json.Json
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import org.w3c.dom.url.URLSearchParams
import us.tractat.bee.core.InviteEnd
import us.tractat.bee.core.InviteWire
import us.tractat.bee.core.Redeemed
import us.tractat.bee.core.Redemption

/**
 * Joining a hosted board by its invite (bee#215): the page was opened as `…/#i=<secret>`, and the
 * secret has already left the address bar for this tab's storage ([INVITE_STORAGE]).
 *
 * Full screen, in the pairing screen's style, with one field and a Join button: the name the
 * board shows, filled in from `?name=` ([prefill]). Join `POST`s the secret and the name to
 * [InviteWire.REDEEM_PATH], in the body and never the query, and gets a device key like the one
 * pairing gives, kept where pairing keeps it ([KEY_STORAGE]). The name goes into the page's
 * `?name=`, so a reload is the same person, and the page joins as `human:<name>` ([joined]).
 *
 * An invite the board refuses (unknown, lapsed, used up) is said plainly, its secret is dropped,
 * and the page offers pairing by code instead ([pairByCode]).
 *
 * DOM hooks: `#join`, `#join-prompt`, `#join-name`, `#join-submit`, `#join-problem`, `#join-pair`.
 */
internal class JoinByInvite(
    private val secret: String,
    prefill: String?,
    private val pairByCode: (admitted: (String) -> Unit) -> Unit,
    private val joined: (name: String?, key: String) -> Unit,
) {
    private val panel = document.body!!.appendElement("section", "join").apply {
        setAttribute("style", "position:fixed;inset:0;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:12px;font-family:system-ui,sans-serif;background:var(--bg);color:var(--ink);padding:16px")
    }
    private val prompt = panel.appendElement("label", "join-prompt").apply {
        textContent = "Your name on the board"
        setAttribute("for", "join-name")
    }
    private val form = panel.appendElement("form", "join-form").apply {
        setAttribute("style", "display:flex;gap:8px;max-width:100%")
    }
    private val name = (form.appendElement("input", "join-name") as HTMLInputElement).apply {
        type = "text"
        value = prefill?.trim().orEmpty()
        maxLength = InviteWire.MAX_NAME
        autocomplete = "name"
        setAttribute("style", "font:inherit;font-size:1.2em;padding:6px 10px;min-width:0;border:1px solid var(--line);border-radius:6px;background:var(--bg);color:var(--ink)")
    }
    private val submit = form.appendElement("button", "join-submit").apply {
        textContent = "Join"
        setAttribute("type", "submit")
        setAttribute("style", "font:inherit;font-size:1.2em;padding:6px 16px;border:1px solid var(--accent);border-radius:6px;background:var(--bg);color:var(--accent)")
    }
    private val problem = panel.appendElement("p", "join-problem").apply {
        setAttribute("role", "alert")
        setAttribute("style", "margin:0;max-width:32em;text-align:center")
    }
    private val pair = panel.appendElement("button", "join-pair").apply {
        textContent = "Pair with a code instead"
        setAttribute("type", "button")
        setAttribute("hidden", "")
    }

    /** Shows the name prompt and waits for Join. */
    fun ask() {
        form.addEventListener("submit", { event: Event ->
            event.preventDefault()
            join()
        })
        pair.addEventListener("click", { pairInstead() })
        name.focus()
    }

    private fun join() {
        val person = InviteWire.nameOrNull(name.value)
            ?: return say("A name is 1 to ${InviteWire.MAX_NAME} characters, with no : or #.")
        submit.setAttribute("disabled", "")
        say("Joining…")
        val body = Json.encodeToString(Redemption.serializer(), Redemption(secret, person))
        postText(InviteWire.REDEEM_PATH, body, REQUEST_TIMEOUT_MS) { _, text ->
            submit.removeAttribute("disabled")
            when (val redeemed = text?.let { decodeOrNull(Redeemed.serializer(), it) }) {
                is Redeemed.Key -> admitted(person, redeemed.key)
                is Redeemed.Refused -> refused(redeemed.reason)
                null -> say("Couldn't reach the board. Try again.")
            }
        }
    }

    private fun admitted(person: String, key: String) {
        sessionStorage.setItem(KEY_STORAGE, key)
        sessionStorage.removeItem(INVITE_STORAGE)
        nameInAddress(person)
        panel.remove()
        joined(person, key)
    }

    private fun refused(reason: InviteEnd) {
        say(refusalText(reason))
        if (reason in DEAD) {
            // The invite will never admit: drop it, and offer the way in that needs no link.
            sessionStorage.removeItem(INVITE_STORAGE)
            form.setAttribute("hidden", "")
            prompt.setAttribute("hidden", "")
            pair.removeAttribute("hidden")
        }
    }

    private fun pairInstead() {
        val person = InviteWire.nameOrNull(name.value)
        person?.let(::nameInAddress)
        panel.remove()
        pairByCode { key -> joined(person, key) }
    }

    private fun say(text: String) {
        problem.textContent = text
    }

    private companion object {
        /** Refusals no retry fixes. */
        val DEAD = setOf(InviteEnd.Unknown, InviteEnd.Lapsed, InviteEnd.Used)

        fun refusalText(reason: InviteEnd): String = when (reason) {
            InviteEnd.Unknown -> "This invite isn't one this board made."
            InviteEnd.Lapsed -> "This invite has lapsed: an invite lasts a day."
            InviteEnd.Used -> "As many people as this invite admits have joined by it."
            InviteEnd.TooManyTries -> "Too many tries from here. Wait a few minutes, then try again."
            InviteEnd.BadName -> "A name is 1 to ${InviteWire.MAX_NAME} characters, with no : or #."
        }

        /** Puts [person] in the page's `?name=`, keeping the rest, so a reload joins as the same person. */
        fun nameInAddress(person: String) {
            val params = URLSearchParams(window.location.search.toJsString()).apply { set("name", person) }
            window.history.replaceState(null, "", "${window.location.pathname}?$params${window.location.hash}")
        }
    }
}
