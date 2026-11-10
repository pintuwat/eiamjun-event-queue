package com.eiamjun.event.printer

/**
 * Column arithmetic shared by every renderer, so the preview on screen, the ESC/POS bytes and the
 * Sunmi output can't disagree about where a price sits.
 *
 * Thai writes tone marks and some vowels *above and below* the consonant — สระอิ, ไม้เอก, ไม้โท and
 * friends. They occupy no column on a fixed-width printer, so counting them (as `String.length`
 * does) pushes the right-hand column left by one space per mark, and a receipt full of
 * "ก๋วยเตี๋ยว" drifts badly.
 */
object TicketText {

    /** U+0E31, U+0E34–U+0E3A, U+0E47–U+0E4E — the marks that stack rather than advance. */
    private fun isCombining(c: Char): Boolean =
        c == 'ั' || c in 'ิ'..'ฺ' || c in '็'..'๎'

    /** Columns a string actually occupies when printed. */
    fun printWidth(s: String): Int = s.count { !isCombining(it) }

    /**
     * Breaks a line at spaces so a word is never split across two lines.
     *
     * The printer wraps by character, which on Thai leaves a single leading consonant stranded —
     * "ไ" on one line and "ม่พริก" on the next, which is not a word and reads as a fault. The
     * words here are already separated by spaces (count, dish, each option), so splitting at them
     * is enough; a single token longer than the line still has to break mid-word, because the
     * alternative is dropping it.
     *
     * Measured with [printWidth], so tone marks do not count against the width they do not take.
     */
    fun wrapWords(text: String, width: Int): List<String> {
        if (width <= 0 || printWidth(text) <= width) return listOf(text)
        val lines = mutableListOf<String>()
        var current = StringBuilder()
        for (word in text.split(" ").filter { it.isNotEmpty() }) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            when {
                printWidth(candidate) <= width -> current = StringBuilder(candidate)
                current.isEmpty() -> {
                    // One word wider than the whole line. Let the printer do what it must.
                    lines.add(word)
                }
                else -> {
                    lines.add(current.toString())
                    current = StringBuilder(word)
                }
            }
        }
        if (current.isNotEmpty()) lines.add(current.toString())
        return lines
    }

    /**
     * Lays a row out as `left ......... right`, never wider than [width].
     *
     * At 22 columns a name like "ก๋วยเตี๋ยวหมู พิเศษ x2" fills the line almost exactly, so anything
     * longer used to spill onto a second line and land the price under the item. The left side is
     * trimmed instead: a shortened menu name still reads, a price in the wrong place does not.
     */
    fun padKeyValue(left: String, right: String, width: Int): String {
        val rightWidth = printWidth(right)
        val room = (width - rightWidth - 1).coerceAtLeast(0)
        val trimmed = truncate(left, room)
        val gap = (width - printWidth(trimmed) - rightWidth).coerceAtLeast(1)
        return trimmed + " ".repeat(gap) + right
    }

    /** Cuts a string to [maxWidth] printed columns, keeping each tone mark with its own letter. */
    fun truncate(s: String, maxWidth: Int): String {
        if (printWidth(s) <= maxWidth) return s
        val out = StringBuilder()
        var used = 0
        for (c in s) {
            if (isCombining(c)) {
                // A mark belongs to the letter already taken; it costs no column of its own.
                if (out.isNotEmpty()) out.append(c)
                continue
            }
            if (used + 1 > maxWidth) break
            out.append(c)
            used++
        }
        return out.toString()
    }

    // There was a measured-width variant here for a while, because the printed price column kept
    // drifting and a proportional font was the obvious suspect. It was the wrong fix for the right
    // observation: the drift is real, but it comes from the printer being set to a font with no
    // Thai in it, so Thai falls back to a proportional system face. Pointed at the monospaced font
    // the service ships (Ubuntu Mono, 500 units for every Thai letter and 0 for every mark — that
    // is this file's model, exactly), character counting is correct and needs no measurement.
    // SunmiPrinterAdapter now names that font rather than taking whatever the device is set to.

    fun center(s: String, width: Int): String {
        val pad = (width - printWidth(s)) / 2
        return if (pad > 0) " ".repeat(pad) + s else s
    }
}
