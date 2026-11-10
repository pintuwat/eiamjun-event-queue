package com.eiamjun.event.printer

/**
 * How big the printed text is.
 *
 * Only *height* is ever doubled. Doubling width would cut a 58mm line from 32 characters to 16 and
 * wrap every menu name, so it is never offered — taller text costs only paper.
 */
enum class ReceiptTextSize {
    /** Everything at the printer's normal 12 × 24 dot cell. */
    NORMAL,

    /**
     * Prices and totals doubled in height, menu names left alone.
     *
     * The right-hand column of a [TicketLine.KeyValue] is what carries every number on a receipt:
     * the line totals, the bill total, the bill number. Those are what a customer checks and what
     * staff read back across a counter, and they are short, so making them tall cannot push
     * anything onto another line.
     */
    NUMBERS,

    /** Every line doubled in height. Easiest to read, uses the most paper. */
    ALL;


    val allTall: Boolean get() = this == ALL
    val numbersTall: Boolean get() = this == NUMBERS || this == ALL

    companion object {
        fun from(stored: String?): ReceiptTextSize =
            entries.firstOrNull { it.name.equals(stored, ignoreCase = true) } ?: NORMAL
    }
}

/**
 * How small the printed font is, and how many characters actually fit on a line.
 *
 * **These column counts are measured on the shop's V2 Pro, not calculated.** The obvious arithmetic
 * — 384-dot paper, bitmap fonts advancing half their height, so 16 dots gives 48 columns — is wrong
 * on this hardware: at 16 dots only about 22 characters fit, so the advance is nearer 17 dots than
 * 8. Padding to a calculated 45 overflowed and wrapped, which is what made receipts look too long.
 *
 * So the number beside each size is what the printer does, and the way to change it is to print one
 * and count. The size and the column count travel together for that reason: they are a matched pair
 * per printer, and changing one without the other drifts every price off the right edge.
 */
enum class ReceiptFont(val dots: Int, val charsPerLine: Int, val label: String) {
    SMALL(16, 22, "เล็ก"),
    /**
     * The receipt, two dots above SMALL at the shop's request.
     *
     * ⚠️ 20 columns is **interpolated** between the measured 16→22 and 20→18, and unlike
     * [KITCHEN] it matters: the receipt's price rows are drawn by `ReceiptRowBitmap`, which
     * calibrates itself so this many Thai letters span the paper. If the real count at 18 dots is
     * not 20, a drawn row will sit a shade larger or smaller than the printed lines around it.
     * Print one and count it, then correct this number.
     */
    RECEIPT(18, 20, "ใบเสร็จ"),
    /**
     * The kitchen slip: eight dots above the receipt, at the shop's request, arrived at in two
     * goes (+3, then +5).
     *
     * 24 is the one place the second bump could land without guesswork — it is [LARGE]'s size,
     * and 15 columns is a pair somebody counted on paper. The 19 this passed through on the way
     * was interpolated, and would have had to be printed and counted before anything was padded
     * at it. Keep this at a measured size.
     */
    KITCHEN(24, 15, "ใบครัว"),
    MEDIUM(20, 18, "กลาง"),
    LARGE(24, 15, "ใหญ่");

    companion object {
        fun from(stored: String?): ReceiptFont =
            entries.firstOrNull { it.name.equals(stored, ignoreCase = true) } ?: SMALL
    }
}
