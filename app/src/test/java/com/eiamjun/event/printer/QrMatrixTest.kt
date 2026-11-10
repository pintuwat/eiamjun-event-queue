package com.eiamjun.event.printer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [QrMatrix] against the JavaScript it was ported from.
 *
 * The fixtures are what `web/shared/qr.js` produces, and those codes were decoded with Apple's
 * Vision framework — the detector behind the iPhone camera — so this is a port held to an
 * implementation checked by a real scanner, not two copies of one belief.
 *
 * A QR that is subtly wrong scans as nothing rather than as something else. Two bugs got this far
 * precisely because a reader written from the same reading of the spec agreed with the encoder:
 * the format bits were transposed, and the version block that versions 7+ require was missing
 * altogether — so versions 1–6 were perfect and everything larger, including a real ถุงเงิน
 * code, was unreadable. Hence module-for-module fixtures, and the structural checks below.
 *
 * The version-8 fixture is a **synthetic** Bill Payment payload, not any shop's: a merchant
 * reference is the string that decides where money lands, and a public repository is not the
 * place for one. Same tags and the same 124 characters, so it is the same QR version and the
 * same coverage, and it was put through Vision like the others.
 */
class QrMatrixTest {

    private val PAYLOADS = mapOf(
        "short" to
            "HELLO",
        "promptpay" to
            "00020101021229370016A000000677010111011300668123456785303764540562.005802TH6304AB12",
        "tungngern" to
            "00020101021130750016A00000067701011201150099000000000010219EXAMPLE00REF000000X0309TUNGNGERN53037645802TH62080704000063049C84",
    )

    /** short: 21×21, produced by `web/shared/qr.js` and scan-checked with Vision. */
    private val SHORT = listOf(
            "111111100001001111111",
            "100000100010101000001",
            "101110101100001011101",
            "101110101010101011101",
            "101110101100101011101",
            "100000101111001000001",
            "111111101010101111111",
            "000000001100000000000",
            "101111100011001111100",
            "011011010111111001100",
            "001111101000101101110",
            "011010000111111001100",
            "010111111000100100101",
            "000000001010100101000",
            "111111100111010010110",
            "100000101010000111110",
            "101110101101010010110",
            "101110101101111101000",
            "101110101100101100100",
            "100000100111111011100",
            "111111101100100010110"
    )

    /** promptpay: 37×37, produced by `web/shared/qr.js` and scan-checked with Vision. */
    private val PROMPTPAY = listOf(
            "1111111000001011100101111010001111111",
            "1000001001010010101001001111101000001",
            "1011101011011101100111010010101011101",
            "1011101011110110011010010101101011101",
            "1011101011000110000101111010001011101",
            "1000001010010000011001001111101000001",
            "1111111010101010101010101010101111111",
            "0000000010010001000001001000000000000",
            "1011111000010100011010001101101111100",
            "0000000100111011100101111010010001110",
            "0101101111101000001001001001101011001",
            "1010000101111010100001011000001101001",
            "0010101011000100111110000101101110101",
            "0011000010001010000101111010010001010",
            "1010001110001011001011001011111101001",
            "0111100111010001001001001001010001000",
            "0100101010110011011000000100101110111",
            "0011010100010001000011111010010001010",
            "1001001100001101001001001001001100001",
            "1101110100001000000011010001000101001",
            "1100111101110101111010000101101110101",
            "1001000101010110000101111010010001010",
            "1000111100001011100001101011000001101",
            "1100100000001100001001101010001001000",
            "1000101101001011100010000101101110111",
            "1011100100100100001011111010010001010",
            "1001101011010001101000100001101001101",
            "1010000111101101001101000010011101010",
            "1001001000100111111010000101111110101",
            "0000000011011100000100111010100011010",
            "1111111000010011101011000010101011001",
            "1000001010111001000111001001100011001",
            "1011101011011101100010000101111110101",
            "1011101010010110001101111011101111011",
            "1011101011011011001000101111010000111",
            "1000001001011000000001101000111101001",
            "1111111010001101111010001011011000111"
    )

    /** tungngern: 49×49, produced by `web/shared/qr.js` and scan-checked with Vision. */
    private val TUNGNGERN = listOf(
            "1111111000000110011010111011101110111100101111111",
            "1000001011111010110100110001000100010111101000001",
            "1011101000000001111111111110101101110001101011101",
            "1011101001111000010000010110010100010101001011101",
            "1011101010010001001010111110101000100100001011101",
            "1000001001100101100000100010001000101010001000001",
            "1111111010101010101010101010101010101010101111111",
            "0000000000000111111101100011000101001101000000000",
            "1010101000011000101100111111111110101010100010010",
            "1101100000000111000101100101000001010101010101001",
            "0010011100101110001010101101011001001100110110101",
            "1100110110111101001101001000100010001000010010000",
            "0011001000100110100011001110101011101100111011101",
            "0101110110110001011010110101010001001000010101001",
            "1001001110011110001000001100110011011010010010101",
            "1010110111101100101010110111001100110010111011010",
            "0101001011001101001100111011000100010001011011001",
            "1110000000110111100101011101101110111011010001001",
            "1110001010111101111010101101001100110010110000101",
            "0111110000101001001001001010010011010101011011010",
            "0111111011100111010101001001101010111111011011001",
            "0110010010100000011100010011110011011101000100001",
            "0001111111001100100011111110110111001100111111101",
            "0111100010100110010101100011011011001101100011010",
            "1001101011101011111100101011101011101010101011001",
            "1110100011001011010101100011000001010101100011101",
            "1100111111100001001001111110101011001101111110101",
            "1001010110000110001101110100100101001010000101000",
            "0110011110101100000110101110111111001000100101001",
            "1011100000111010101101001011010111010001000100001",
            "0010011000111111110111100010110111001011111010001",
            "1101100010110101010101101010010001000101110101010",
            "1000111010111101100100110010111011101110001101010",
            "1100000110001011001101110100010001000101110101001",
            "1010011011011110110010100010111011101110001100101",
            "0011010110111101100101110010110011000100001101010",
            "1000011100101001100100111110101010111110101101001",
            "0011010101101100110101100111010011000101010101001",
            "0100011110011001000011101010110011011100001010101",
            "0111000110111110001101110010010011001100001101010",
            "1110001101101000011100111111111010101010111111001",
            "0000000010010001100101100010000111010100100011001",
            "1111111000001011110110101011001011001100101010101",
            "1000001001100101100101100010111011001001100011010",
            "1011101010011110010010111110101010101100111111001",
            "1011101000111110001010101101010001001000100011001",
            "1011101011100010111001101110110011001010101011011",
            "1000001001110010010010101101001100110010100111010",
            "1111111010100101100100111111000100010000111011011",
    )

    private fun render(modules: Array<BooleanArray>): List<String> =
        modules.map { row -> row.joinToString("") { if (it) "1" else "0" } }

    @Test
    fun `short matches the javascript encoder`() {
        assertEquals(SHORT, render(QrMatrix.encode(PAYLOADS["short"]!!)))
    }

    @Test
    fun `promptpay matches the javascript encoder`() {
        assertEquals(PROMPTPAY, render(QrMatrix.encode(PAYLOADS["promptpay"]!!)))
    }

    @Test
    fun `tungngern matches the javascript encoder`() {
        assertEquals(TUNGNGERN, render(QrMatrix.encode(PAYLOADS["tungngern"]!!)))
    }

    @Test
    fun `the shop's own code is version 8, with room to spare`() {
        val modules = QrMatrix.encode(PAYLOADS["tungngern"]!!)
        assertEquals(49, modules.size)
        assertTrue("every row is as long as the code is wide", modules.all { it.size == 49 })
    }

    @Test
    fun `both copies of the format bits say the same thing`() {
        // They are written in two places and a decoder may read either. Transposing one copy is
        // invisible to a reader that only consults the other, which is how it survived once.
        for (payload in PAYLOADS.values) {
            val m = QrMatrix.encode(payload)
            val size = m.size
            var first = 0
            var second = 0
            for (i in 0 until 15) {
                val a = when {
                    i < 6 -> m[i][8]
                    i == 6 -> m[7][8]
                    i == 7 -> m[8][8]
                    i == 8 -> m[8][7]
                    else -> m[8][14 - i]
                }
                val b = if (i < 8) m[8][size - 1 - i] else m[size - 15 + i][8]
                if (a) first = first or (1 shl i)
                if (b) second = second or (1 shl i)
            }
            assertEquals("format copies disagree", first, second)
        }
    }

    @Test
    fun `versions 7 and up carry a version block, and it decodes back to the version`() {
        // Its absence is why every code above version 6 was unreadable while the small ones were
        // fine: a decoder gives up on a large code before it looks at any data.
        for (length in intArrayOf(5, 90, 110, 130, 180, 320)) {
            val m = QrMatrix.encode("A".repeat(length))
            val size = m.size
            val version = (size - 17) / 4
            if (version < 7) continue

            var bits = 0
            for (i in 0 until 18) {
                if (m[i / 3][size - 11 + (i % 3)]) bits = bits or (1 shl i)
            }
            assertEquals("version block for v$version", version, bits ushr 12)

            // ...and the same bits appear in the mirrored patch by the bottom-left finder.
            for (i in 0 until 18) {
                assertEquals(
                    "version block copies differ at $i",
                    m[i / 3][size - 11 + (i % 3)],
                    m[size - 11 + (i % 3)][i / 3]
                )
            }
        }
    }
}
