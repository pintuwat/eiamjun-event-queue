package com.eiamjun.event.printer

/**
 * A print job, transport-agnostic. The same [Ticket] is rendered to bytes by [EscPos] for the
 * Bluetooth driver, or handed line-by-line to the Sunmi SDK by [SunmiPrinterAdapter] — screens
 * and view models never format raw printer commands themselves.
 */
/**
 * @param font the size this document wants, or null to use whatever the device is set to.
 *   The kitchen slip asks for its own: it is read at arm's length across a hot pass, while the
 *   receipt is read by someone holding it. Carried on the ticket rather than chosen by the
 *   caller of [Printer.print], so the two documents cannot be printed at each other's size by a
 *   screen that forgot to say which it was sending.
 */
/**
 * @param dotsOffset dots above or below [font] for this whole document, from ตั้งค่า. Only the
 *   kitchen slip uses it: the receipt's layout is approved and fixed (2c), while the slip is read
 *   across a pass by whoever is cooking and the shop has moved it three times. Carried on the
 *   ticket, like [font], so no screen can send one document at the other's size.
 *
 *   Drivers that print through a real fixed-cell firmware font ignore it — there the column
 *   count is the layout, and a size nobody counted columns for has nothing to pad against.
 */
data class Ticket(
    val lines: List<TicketLine>,
    val font: ReceiptFont? = null,
    val dotsOffset: Int = 0
)

sealed class TicketLine {
    /**
     * @param big double width **and** height. Doubling the width halves the columns, so it only
     *   suits a short line on a small font — at [ReceiptFont.KITCHEN] it leaves about seven.
     * @param tall double height only. Same emphasis down the page, none of the cost across it,
     *   which is what a station heading needs once the body text is already large.
     */
    data class Text(
        val text: String,
        val bold: Boolean = false,
        val center: Boolean = false,
        val big: Boolean = false,
        val tall: Boolean = false,
        /**
         * Dots above or below the ticket's own size, for one line.
         *
         * The slip's heading and its table number are tuned against each other by eye on real
         * paper — the station wants to be quieter than the dishes, the table louder — and that is
         * a number of dots, not a size anyone measured columns for. Only safe on a line that is
         * centred or left alone; a padded row has to stay at the ticket's size, because its
         * column count is what the padding iscalculated against.
         */
        val dots: Int = 0
    ) : TicketLine()
    /**
     * A left-aligned line that is **typeset rather than handed to the printer to break.**
     *
     * `printText` wraps by character in a proportional font, and on the kitchen slip that lost
     * the last letter of a word to the next line — "ไม้ผั" with a lone "ก" under it, read from the
     * counter as the printer dropping characters. A column count cannot fix it: [TicketText]
     * counts cells and the font has none, so a line judged to fit overflows and a line with room
     * to spare gets broken. The Sunmi driver measures this one in the font that draws it and
     * breaks it at a space, the same reasoning as [KeyValue] and for the same reason.
     *
     * The gap between words is drawn **narrower than a space** — the shop asked for the slip to
     * be tighter, and a proportional space at the kitchen's size is wide enough to push a word
     * off the paper on its own.
     *
     * Drivers with a real fixed cell (ESC/POS firmware fonts) wrap it by character count, which
     * is correct there.
     */
    data class Wrapped(val text: String, val bold: Boolean = false) : TicketLine()

    /**
     * @param columns pad this row to a narrower width than the rest of the ticket. Only needed
     *   where a line measures wider on paper than its printed-column count predicts — see the
     *   header row in [TicketFormatter]. Null means "use the ticket's width".
     */
    data class KeyValue(
        val left: String,
        val right: String,
        val bold: Boolean = false,
        val columns: Int? = null,
        /**
         * The part of [left] that must not be trimmed away — see `ReceiptRowBitmap.row`. The
         * receipt puts the quantity here, because a dropped "x2" is a bill that cannot be checked
         * against the drawer, while a shortened dish name still reads.
         */
        val leftTail: String = ""
    ) : TicketLine()

    /**
     * A QR code, printed with the printer's own QR command rather than as an image.
     *
     * [moduleSize] is dots per module, so the printed size is modules × moduleSize ÷ 8 mm on a
     * 203dpi head. The shop's payment payload is ~133 characters, which needs a 41-module symbol,
     * so it prints at roughly 1.0cm at 2 dots, 1.5cm at 3, 2.0cm at 4 and 2.5cm at 5.
     */
    data class QrCode(val data: String, val moduleSize: Int = DEFAULT_QR_DOTS) : TicketLine()

    /**
     * One short string drawn as large as the paper allows — the queue number, and nothing else.
     *
     * Why this is not [Text] with `big = true`: `big` doubles the printer's own cell, so at the
     * largest font a line still comes out around 48 dots tall. A queue ticket's number is the
     * whole reason the paper exists — it is held up, read across a counter and matched against a
     * shout — and it wants a third of the 58mm width per digit, which is five times that.
     *
     * So it is measured and drawn, for the same reason the price column is: the app typesets it to
     * [heightDots] and hands the printer a picture, and there is no font size to be wrong about.
     * Drivers with a real fixed cell fall back to double-everything, which is the biggest thing
     * they can do.
     *
     * Short strings only. It is centred and not wrapped, and a digit this size leaves room for
     * about four of them.
     */
    data class Banner(val text: String, val heightDots: Int = DEFAULT_BANNER_DOTS) : TicketLine()

    object Rule : TicketLine()
    object Blank : TicketLine()
    object FeedAndCut : TicketLine()
}

enum class TicketKind { KITCHEN, RECEIPT }

/**
 * How tall a [TicketLine.Banner] is drawn, in dots.
 *
 * 120 of the head's 203 dots per inch is about 15mm — a digit a little over a finger's width, which
 * is what makes a number readable held up at arm's length. ⚠️ Chosen rather than measured: nobody
 * has printed one yet. ตั้งค่า moves it (AppSettings.queueNumberDots), which is the honest way to
 * settle a size on paper that has not been met.
 */
const val DEFAULT_BANNER_DOTS = 120

/** Dots per QR module giving roughly 1.5cm for a payment QR — the size the shop asked for. */
const val DEFAULT_QR_DOTS = 3

/**
 * A payment code for the screen rather than the printer.
 *
 * [carriesAmount] is false when the shop's code could not be edited safely and the customer has to
 * type the amount — the screen says so, the same way the printed slip does.
 */
data class ScreenQr(val payload: String, val carriesAmount: Boolean)
