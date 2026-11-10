package com.eiamjun.event.printer

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/**
 * Renders a [Ticket] to raw ESC/POS command bytes for the generic Bluetooth driver.
 *
 * Thai text risk (see research/loyverse-features-and-sunmi-hardware.md): most ESC/POS thermal
 * printers don't ship a Thai codepage by default. This tries TIS-620 / Windows-874 encoding plus
 * an `ESC t` codepage-select command, but the actual codepage index is printer-specific — TEST ON
 * REAL HARDWARE before relying on it. If Thai text prints as garbage/blank, the real fix is
 * rendering the Thai lines as a bitmap (GS v 0) instead of as text — not implemented here yet.
 */
object EscPos {

    /** The Thai code table confirmed on the shop's 80mm network printer. */
    const val THAI_CODEPAGE = 21


    private const val ESC = 0x1B
    private const val GS = 0x1D

    private val thaiCharset: Charset by lazy {
        runCatching { Charset.forName("TIS-620") }
            .recoverCatching { Charset.forName("windows-874") }
            .getOrDefault(Charsets.UTF_8)
    }

    /**
     * @param textSize which lines print at double height. Height only — the width bits would halve
     *   the characters per line and wrap the menu names.
     */
    fun render(
        ticket: Ticket,
        charsPerLine: Int = 32,
        textSize: ReceiptTextSize = ReceiptTextSize.NUMBERS,
        /**
         * `ESC t n` — the character code table. 21 is the Thai page confirmed on the shop's 80mm
         * network printer; the Sunmi built-in head is a different firmware and may well index it
         * elsewhere, which is why this is a parameter and not a constant.
         */
        codepage: Int = THAI_CODEPAGE,
        cut: Boolean = true
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(ESC.toByte(), 0x40.toByte())) // ESC @ — initialize
        out.write(byteArrayOf(ESC.toByte(), 0x74.toByte(), codepage.toByte()))

        if (textSize.allTall) out.write(byteArrayOf(GS.toByte(), 0x21.toByte(), 0x01))

        ticket.lines.forEach { line -> writeLine(out, line, charsPerLine, textSize) }

        out.write(byteArrayOf(0x0A, 0x0A, 0x0A)) // feed before cut so the last line isn't sliced off
        // The V1s/V2/V2 Pro have no cutter, so a probe printed mid-roll asks for no cut.
        if (cut) out.write(byteArrayOf(GS.toByte(), 0x56.toByte(), 0x00)) // GS V 0 — full cut
        return out.toByteArray()
    }

    private fun writeLine(
        out: ByteArrayOutputStream,
        line: TicketLine,
        charsPerLine: Int,
        textSize: ReceiptTextSize
    ) {
        when (line) {
            is TicketLine.Text -> {
                align(out, center = line.center)
                bold(out, line.bold)
                bigFont(out, line.big, textSize.allTall)
                text(out, line.text)
                bigFont(out, false, textSize.allTall)
                bold(out, false)
                align(out, center = false)
                out.write(0x0A)
            }
            // The firmware font *is* a fixed cell, so counting columns is right here — this is
            // the one renderer where the Sunmi's measured wrapping would be the wrong answer.
            is TicketLine.Wrapped -> {
                bold(out, line.bold)
                TicketText.wrapWords(line.text, charsPerLine).forEach {
                    text(out, it)
                    out.write(0x0A)
                }
                bold(out, false)
            }
            is TicketLine.KeyValue -> {
                bold(out, line.bold)
                // Split the padded row so the number can be taller than the name. Height
                // magnification does not advance the head any further horizontally, so the columns
                // stay exactly where padKeyValue put them.
                val padded = padKeyValue(line.left + line.leftTail, line.right, line.columns ?: charsPerLine)
                val split = padded.length - line.right.length
                bigFont(out, false, textSize.allTall)
                text(out, padded.substring(0, split))
                bigFont(out, false, textSize.numbersTall)
                text(out, padded.substring(split))
                bigFont(out, false, textSize.allTall)
                bold(out, false)
                out.write(0x0A)
            }
            // No measured size to aim at: a firmware font has one fixed cell, so double width and
            // height is the largest a banner can be here. Nothing printed through this path today —
            // the event terminal prints through LineApi — but the branch has to exist and has to be
            // the biggest thing this renderer can say.
            is TicketLine.Banner -> {
                align(out, center = true)
                bold(out, true)
                bigFont(out, on = true, tallText = textSize.allTall)
                text(out, line.text)
                bigFont(out, on = false, tallText = textSize.allTall)
                bold(out, false)
                align(out, center = false)
                out.write(0x0A)
            }
            is TicketLine.QrCode -> {
                align(out, center = true)
                qrCode(out, line.data, line.moduleSize)
                align(out, center = false)
                out.write(0x0A)
            }
            TicketLine.Rule -> {
                text(out, "-".repeat(charsPerLine))
                out.write(0x0A)
            }
            TicketLine.Blank -> out.write(0x0A)
            TicketLine.FeedAndCut -> Unit // handled once at the end of render()
        }
    }

    private fun padKeyValue(left: String, right: String, width: Int): String =
        TicketText.padKeyValue(left, right, width)

    private fun align(out: ByteArrayOutputStream, center: Boolean) {
        out.write(byteArrayOf(ESC.toByte(), 0x61.toByte(), if (center) 1 else 0))
    }

    private fun bold(out: ByteArrayOutputStream, on: Boolean) {
        out.write(byteArrayOf(ESC.toByte(), 0x45.toByte(), if (on) 1 else 0))
    }

    /**
     * `GS ! n` — bits 0-2 are height magnification, bits 4-6 width. 0x11 doubles both (the shop
     * name), 0x01 doubles height alone, 0x00 is normal. Turning a big line "off" returns to the
     * tall baseline rather than to normal, or the rest of the ticket would shrink mid-receipt.
     */
    private fun bigFont(out: ByteArrayOutputStream, on: Boolean, tallText: Boolean) {
        val n: Byte = when {
            on -> 0x11
            tallText -> 0x01
            else -> 0x00
        }
        out.write(byteArrayOf(GS.toByte(), 0x21.toByte(), n))
    }

    /**
     * Characters that reach the printer through menu text but have no TIS-620 byte, mapped to
     * ASCII instead of the "?" the charset encoder would produce. The separator "·" in
     * "น้ำตก · เส้นเล็ก" is on nearly every noodle line, so this is not an edge case.
     */
    private val fallbacks = mapOf(
        '\u00B7' to "/", '\u00D7' to "x", '\u2014' to "-", '\u2013' to "-",
        '\u2022' to "*", '\u2026' to "...", '\u2018' to "'", '\u2019' to "'",
        '\u201C' to "\"", '\u201D' to "\"", '\u00A0' to " "
    )

    private fun text(out: ByteArrayOutputStream, s: String) {
        val substituted = buildString {
            s.forEach { char -> append(fallbacks[char] ?: char.toString()) }
        }
        out.write(substituted.toByteArray(thaiCharset))
    }

    /**
     * QR via the printer's native `GS ( k` commands — much crisper and far fewer bytes than
     * rasterising a bitmap, and supported by essentially every modern ESC/POS printer.
     *
     * QR payloads are ASCII, so they are NOT run through the Thai charset above.
     */
    private fun qrCode(out: ByteArrayOutputStream, data: String, moduleSize: Int) {
        val bytes = data.toByteArray(Charsets.US_ASCII)

        // fn 165 — select model 2
        out.write(byteArrayOf(GS.toByte(), 0x28, 0x6B, 0x04, 0x00, 0x31, 0x41, 0x32, 0x00))
        // fn 167 — module size (dot width per module)
        out.write(byteArrayOf(GS.toByte(), 0x28, 0x6B, 0x03, 0x00, 0x31, 0x43, moduleSize.coerceIn(1, 16).toByte()))
        // fn 169 — error correction level M (0x31). PromptPay payloads are short; M is plenty.
        out.write(byteArrayOf(GS.toByte(), 0x28, 0x6B, 0x03, 0x00, 0x31, 0x45, 0x31))
        // fn 180 — store the payload. (pL + pH * 256) = data length + 3
        val len = bytes.size + 3
        out.write(byteArrayOf(GS.toByte(), 0x28, 0x6B, (len and 0xFF).toByte(), ((len shr 8) and 0xFF).toByte(), 0x31, 0x50, 0x30))
        out.write(bytes)
        // fn 181 — print what's in the symbol storage area
        out.write(byteArrayOf(GS.toByte(), 0x28, 0x6B, 0x03, 0x00, 0x31, 0x51, 0x30))
    }
}
