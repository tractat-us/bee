package us.tractat.bee.web

import us.tractat.kuilt.test.assertAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * [QrCode] against a reference encoder's output ([QrVectors], from segno), module for module: a
 * version 1, 5, 7 (the first with version information) and 10 (the first with a 16-bit count)
 * symbol, under masks that differ, so a wrong table entry, block split, mask or format bit shows
 * as a mismatch. A symbol that matches a reference module for module scans as the reference does.
 */
class QrCodeTest {
    private fun QrCode.rows(): List<String> = (0 until size).map { row -> (0 until size).joinToString("") { if (dark(row, it)) "1" else "0" } }

    @Test
    fun underTheSameMaskEverySymbolMatchesTheReference() {
        assertAll(
            *QrVectors.ALL.map { vector ->
                {
                    val code = QrCode.withMask(vector.text, vector.mask)
                    assertEquals(vector.version, code.version, "version for ${vector.text.length} bytes")
                    assertEquals(vector.rows, code.rows(), "modules for ${vector.text.length} bytes under mask ${vector.mask}")
                }
            }.toTypedArray(),
        )
    }

    @Test
    fun theMaskChosenIsTheOneTheReferenceEncoderChooses() {
        assertAll(
            *QrVectors.ALL.map { vector ->
                { assertEquals(vector.chosenMask, QrCode.of(vector.text).mask, "mask for ${vector.text.length} bytes") }
            }.toTypedArray(),
        )
    }

    @Test
    fun aJoinLinkFitsAndATextPastVersionTenIsRefused() {
        assertAll(
            { assertEquals(5, QrCode.of("http://192.168.1.23:8233/?k=Zk3v_9QmT2xLr8bNcY4wHs1aPdE6uJgKoV0iRtXyBfM").version) },
            { assertEquals(10, QrCode.of("x".repeat(213)).version) },
            { assertFailsWith<IllegalArgumentException> { QrCode.of("x".repeat(214)) } },
        )
    }
}
