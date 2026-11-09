package us.tractat.bee.core

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The names a person joining by invite may take (bee#215): judged by code point, not by UTF-16 unit. */
class InviteWireTest {
    @Test
    fun anInvisibleCodePointAnywhereInANameIsRefused() {
        val refused = mapOf(
            "a control character" to "line\nbreak",
            "a bidi override" to "ad\u202Emin",
            "a zero-width joiner" to "a\u200Db",
            "a line separator" to "a\u2028b",
            "a paragraph separator" to "a\u2029b",
            "a tag character, outside the BMP" to "alex\uDB40\uDC41",
            "a supplementary variation selector" to "alex\uDB40\uDD00",
            "a musical format character" to "alex\uD834\uDD73",
            "a Hangul choseong filler" to "a\u115Fb",
            "a Hangul jungseong filler" to "a\u1160b",
            "a Hangul filler" to "a\u3164b",
            "a halfwidth Hangul filler" to "a\uFFA0b",
            "a variation selector" to "alex\uFE0F",
            "a Mongolian variation selector" to "a\u180Bb",
            "a lone high surrogate" to "a\uD800b",
            "a lone low surrogate" to "a\uDC00b",
        )
        assertAll(*refused.map { (what, name) -> { assertNull(InviteWire.nameOrNull(name), what) } }.toTypedArray())
    }

    @Test
    fun aNameOfVisibleLettersPassesTrimmed() {
        assertAll(
            { assertEquals("alex", InviteWire.nameOrNull("  alex ")) },
            { assertEquals("Zo\u00EB", InviteWire.nameOrNull("Zo\u00EB")) },
            { assertEquals("\uBBFC\uC900", InviteWire.nameOrNull("\uBBFC\uC900"), "Hangul syllables are letters, not fillers") },
            { assertEquals("sam \uD83D\uDC1D", InviteWire.nameOrNull("sam \uD83D\uDC1D"), "an emoji outside the BMP is visible") },
        )
    }

    @Test
    fun aNameThatCantBeAnIdentityIsRefused() {
        assertAll(
            { assertNull(InviteWire.nameOrNull("")) },
            { assertNull(InviteWire.nameOrNull("   ")) },
            { assertNull(InviteWire.nameOrNull("a:b")) },
            { assertNull(InviteWire.nameOrNull("a#b")) },
            { assertNull(InviteWire.nameOrNull("x".repeat(InviteWire.MAX_NAME + 1))) },
            { assertEquals("x".repeat(InviteWire.MAX_NAME), InviteWire.nameOrNull("x".repeat(InviteWire.MAX_NAME)), "rig: the longest passes") },
        )
    }
}
