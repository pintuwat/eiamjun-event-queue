package com.eiamjun.event.printer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.ceil

/**
 * Draws a `name ........ price` row as an image, so the price lands on the paper's right edge
 * exactly.
 *
 * **Why an image.** The receipt's one hard requirement is that prices line up down the page, and
 * four attempts to pad them there with spaces all failed for the same underlying reason: there is
 * no fixed cell to pad in. `LineApi` renders through Android, and none of the typefaces the Sunmi
 * print service offers contains Thai — `gh.ttf` (Ubuntu Mono) does carry it at a flat 500 units a
 * letter, but naming that font changed nothing on paper, so Thai is coming from a proportional
 * system face no matter what is asked for. A row mixing Thai at one width and latin at another
 * cannot be squared with space padding, whatever the arithmetic.
 *
 * Raw ESC/POS was the other way out, and the firmware answered it: probes at code pages 20, 21, 26
 * and 255 printed their latin label and ruler and dropped every Thai line on the floor. This head
 * has no Thai table at all.
 *
 * So the app does the typesetting. Measure both pieces of the row in the font that will draw them,
 * put the name at the left margin and the price at `paper - width(price)`, and hand the printer a
 * picture. There is no arithmetic left to be wrong about.
 *
 * **Size.** The text size is calibrated rather than chosen: whatever makes [columns] Thai letters
 * span the paper is the size [LineApi] is already printing the ticket's other lines at, so a drawn
 * row sits among them without looking bigger or smaller.
 */
class ReceiptRowBitmap(
    private val paperDots: Int = PAPER_DOTS_58MM,
    columns: Int = 22,
    /**
     * Drawn a touch larger than the lines around it, because these are the lines a customer
     * actually reads. Only the drawn rows move: the header and the footer still go through
     * `printText` at the font's own size, so this is a deliberate half-step, not a mismatch.
     *
     * It costs room — a longer name is trimmed a character or so sooner — and nothing else. The
     * geometry below is all measured at this size, so there is no layout to recompute.
     */
    extraPx: Float = ITEM_ROW_EXTRA_PX,
    /**
     * Multiplies the calibrated size, for a ticket printing at other than its font's own dots.
     *
     * The kitchen slip's size is ตั้งค่า (±2 dots), and the first version followed it by handing
     * this class a **re-derived column count** — `charsPerLine * fontDots / dots`. Integer
     * division ate the small steps: at −1 dot, 15 × 24 ÷ 23 is still 15, so the drawn rows came
     * out identical while only the headings moved. Those rows are most of the slip, so the shop
     * changed the setting and nothing happened.
     *
     * A ratio has no such floor. At offset 0 it is exactly 1 and the calibration is untouched.
     */
    scale: Float = 1f
) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT
        color = Color.BLACK
        // Linear in textSize, so one measurement gives the size that fills the paper.
        textSize = 32f
        textSize *= paperDots / measureText("ก".repeat(columns))
        textSize += extraPx
        textSize *= scale
    }

    private val metrics = paint.fontMetrics

    /** Tall enough for marks that sit above the ascent and below the descent — Thai uses both. */
    private val rowHeight = ceil(metrics.bottom - metrics.top).toInt() + 2

    private val baseline = -metrics.top + 1f

    /**
     * @param tall double height, the way the thermal font's own `GS !` would do it — the same
     *   glyphs stretched, not a larger face, so the row still holds the same text.
     */
    fun row(
        left: String,
        right: String,
        bold: Boolean = false,
        tall: Boolean = false,
        /**
         * Part of the left side that must survive the trim — the receipt passes the quantity.
         *
         * [left] is cut from its end when the row is too long, which is right for a menu name: a
         * shortened name still reads. It is wrong for " x2", because a bill that silently drops a
         * quantity is a bill nobody can check against the money in the drawer. Since the shop
         * asked for พิเศษ back on the line, a long name plus พิเศษ plus a count plus the price
         * is close enough to the paper's width that this stopped being hypothetical.
         */
        leftTail: String = ""
    ): Bitmap {
        paint.isFakeBoldText = bold
        val rightWidth = paint.measureText(right)
        val gap = paint.measureText(" ")
        val tailWidth = paint.measureText(leftTail)
        val trimmed = fit(left, paperDots - rightWidth - gap - tailWidth) + leftTail

        val bitmap = Bitmap.createBitmap(paperDots, rowHeight, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            drawText(trimmed, 0f, baseline, paint)
            drawText(right, paperDots - rightWidth, baseline, paint)
        }
        return if (tall) {
            Bitmap.createScaledBitmap(bitmap, paperDots, rowHeight * 2, false)
        } else {
            bitmap
        }
    }

    /**
     * Typesets a left-aligned line, breaking it at spaces by **measured** width.
     *
     * This is the kitchen slip's item line, and it exists because `printText` broke those lines
     * itself, by character, in a font with no fixed cell: "ไม่ผั" printed with a lone "ก"
     * underneath. Counting columns could not have fixed it in either direction — a line
     * [TicketText] judged to fit still overflowed, and one with room to spare still got broken.
     * Measuring the string that will actually be drawn leaves nothing to be wrong about, which is
     * the same conclusion [row] reached about the price column.
     *
     * Words are separated by [WORD_GAP] of a space rather than a whole one. The shop asked for
     * the slip tighter, and at the kitchen's size a proportional space is wide enough on its own
     * to push the last word of "1 x หมู เล็ก ตก พิ" past the paper's edge.
     *
     * A single word wider than the paper is split by measured characters rather than trimmed:
     * dropping a letter is the fault this method was written to remove.
     *
     * @return one bitmap per printed line, in order.
     */
    fun wrappedLeft(text: String, bold: Boolean = false, tall: Boolean = false): List<Bitmap> {
        paint.isFakeBoldText = bold
        val gap = paint.measureText(" ") * WORD_GAP
        val words = text.split(" ").filter { it.isNotEmpty() }.flatMap { splitToFit(it) }
        if (words.isEmpty()) return emptyList()

        val lines = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        var used = 0f
        for (word in words) {
            val width = paint.measureText(word)
            val needed = if (current.isEmpty()) width else used + gap + width
            if (current.isNotEmpty() && needed > paperDots) {
                lines.add(current)
                current = mutableListOf(word)
                used = width
            } else {
                current.add(word)
                used = needed
            }
        }
        if (current.isNotEmpty()) lines.add(current)

        // isFakeBoldText is set on the shared paint above, so draw while it still holds.
        return lines.map { draw(it, gap, tall) }
    }

    private fun draw(words: List<String>, gap: Float, tall: Boolean): Bitmap {
        val bitmap = Bitmap.createBitmap(paperDots, rowHeight, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            var x = 0f
            for (word in words) {
                drawText(word, x, baseline, paint)
                x += paint.measureText(word) + gap
            }
        }
        return if (tall) Bitmap.createScaledBitmap(bitmap, paperDots, rowHeight * 2, false) else bitmap
    }

    /**
     * Cuts one unbroken word into pieces that each fit the paper, keeping every tone mark with the
     * letter it sits on. Returns the word untouched when it already fits, which is almost always.
     */
    private fun splitToFit(word: String): List<String> {
        if (paint.measureText(word) <= paperDots) return listOf(word)
        val pieces = mutableListOf<String>()
        var rest = word
        while (paint.measureText(rest) > paperDots) {
            val head = fit(rest, paperDots.toFloat())
            if (head.isEmpty()) break
            pieces.add(head)
            rest = rest.substring(head.length)
        }
        if (rest.isNotEmpty()) pieces.add(rest)
        return pieces
    }

    /**
     * Draws one short string centred and [heightDots] tall — the queue number.
     *
     * A paint of its own rather than the calibrated [paint]: this is the opposite requirement from
     * every other row here. Those are sized so a drawn row sits among the printed lines unnoticed;
     * this one is meant to be the largest thing on the paper, so its size is asked for in dots and
     * the calibration has nothing to say about it.
     *
     * The size is solved for the **cap height of the string that will be drawn**, not for the
     * font's metrics, because a line of digits has no descender and sizing it by the font's
     * ascent-to-descent would leave a third of the band empty. If it is still wider than the
     * paper it is shrunk to fit — a number that runs off the edge is worse than a smaller one.
     */
    fun banner(text: String, heightDots: Int, bold: Boolean = true): Bitmap {
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT
            color = Color.BLACK
            isFakeBoldText = bold
            textSize = 100f
        }
        val bounds = android.graphics.Rect()
        ink.getTextBounds(text, 0, text.length, bounds)
        if (bounds.height() <= 0) return rule()
        ink.textSize *= heightDots.toFloat() / bounds.height()
        // Re-measure: the glyphs are only approximately linear in textSize once hinting is in play.
        ink.getTextBounds(text, 0, text.length, bounds)
        if (bounds.width() > paperDots) {
            ink.textSize *= paperDots.toFloat() / bounds.width()
            ink.getTextBounds(text, 0, text.length, bounds)
        }

        val pad = 6
        val bitmap = Bitmap.createBitmap(paperDots, bounds.height() + pad * 2, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            // bounds.left/top are offsets from the drawing origin, so subtracting them puts the
            // ink itself where it was asked for rather than the font's notional box.
            drawText(
                text,
                (paperDots - bounds.width()) / 2f - bounds.left,
                pad - bounds.top.toFloat(),
                ink
            )
        }
        return bitmap
    }

    /** A dividing line the full width of the paper, so a rule and a row end together. */
    fun rule(): Bitmap {
        val bitmap = Bitmap.createBitmap(paperDots, rowHeight, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            val stroke = Paint().apply {
                color = Color.BLACK
                strokeWidth = 2f
            }
            val y = baseline - paint.textSize / 3f
            var x = 0f
            // Dashes rather than a solid rule: the shop's approved receipt has dashed dividers.
            while (x < paperDots) {
                drawLine(x, y, minOf(x + DASH, paperDots.toFloat()), y, stroke)
                x += DASH * 2
            }
        }
        return bitmap
    }

    /**
     * Drops whole characters off the end until the string fits, measuring each candidate rather
     * than summing character widths: a font renders a Thai cluster narrower than its parts, so only
     * the width of the string that will actually be drawn can be trusted.
     */
    private fun fit(s: String, maxWidth: Float): String {
        if (paint.measureText(s) <= maxWidth) return s
        var end = s.length
        while (end > 0) {
            end--
            while (end > 0 && isCombining(s[end])) end--
            val candidate = s.substring(0, end)
            if (paint.measureText(candidate) <= maxWidth) return candidate
        }
        return ""
    }

    private fun isCombining(c: Char): Boolean =
        c == 'ั' || c in 'ิ'..'ฺ' || c in '็'..'๎'

    companion object {
        /** 58mm head, the width every Sunmi handheld in this shop has. */
        const val PAPER_DOTS_58MM = 384

        /** The shop asked for the item lines a little bigger than the rest of the receipt. */
        const val ITEM_ROW_EXTRA_PX = 1f

        /**
         * How much of a space sits between two words on a drawn line.
         *
         * The shop's words: the kitchen slip is "ห่างเกินไป", join them up if that is what it
         * takes. Just under half keeps "หมู เล็ก" two words rather than one and still buys back
         * most of a column per gap, on a line that has four or five of them.
         */
        const val WORD_GAP = 0.45f
        private const val DASH = 8f
    }
}
