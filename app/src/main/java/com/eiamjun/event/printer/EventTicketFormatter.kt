package com.eiamjun.event.printer

import com.eiamjun.event.data.EventDay
import com.eiamjun.event.data.OrderLineEntity

/**
 * The event prints two documents, and they answer different questions.
 *
 * - **บัตรคิว** is handed over the moment the money is taken. It carries a number and nothing
 *   else: the customer walks away with it, comes back when the number is called, and the menu is
 *   deliberately not on it — the kitchen reads the order off an iPad, and a ticket that lists the
 *   food invites the customer to check it at the counter, which is the queue this app exists to
 *   shorten.
 * - **ใบเสร็จ** is printed only if the customer wants one, when they collect. That one does carry
 *   the items and the prices, because it is the thing somebody keeps.
 *
 * The same lines build both, and neither is ever sent at the other's size — the size rides on the
 * [Ticket], the way the shop's till settled it, so a screen cannot print one as the other.
 */
object EventTicketFormatter {

    /**
     * The header row is padded to fewer columns than the ticket's own width.
     *
     * Measured on the shop's till rather than reasoned: a row of Thai with several tone marks runs
     * past the right edge at the full count, because the head advances for some marks that
     * [TicketText.printWidth] counts as zero. Two columns of slack cost nothing and keep the
     * number on the same line as its label.
     */
    private const val HEADER_COLUMNS = 18

    /**
     * The receipt's lines are not all one size, because its two readers are not the same person.
     *
     * Since 0.5.0 this slip goes to the kitchen, who read it at arm's length off a spike while
     * cooking; the customer's copy of the same paper is read held in a hand. So the words the
     * kitchen acts on — the dish, the count, the options, the note — go **up** three dots from
     * [ReceiptFont.MEDIUM]'s 20, and the date goes **down** three, because it is the one line on
     * the slip that nobody does anything with: it is there to settle an argument later, not to be
     * read now.
     *
     * Dots rather than another [ReceiptFont], for the reason the enum carries: its entries hold
     * column counts somebody counted on paper, and 17 and 23 are not among them. That is safe
     * here and would not be on a padded row — every line these touch is measured in the font that
     * draws it, against the paper's own dots.
     *
     * ⚠️ **The item row is trimmed, not wrapped**, so three dots up costs it about two columns of
     * name: `ก๋วยเตี๋ยวคลุก x2` already measured 18 of 18 at 20 dots. The count is protected by
     * `leftTail` and the price keeps the right edge, so what a long name loses is its tail. The
     * shop chose legibility over the last characters of a name on a one-dish menu; check this on
     * paper before changing the sizes, never by counting.
     */
    private const val ITEM_DOTS = 3
    private const val DATE_DOTS = -3

    /**
     * The customer's queue ticket.
     *
     * Four things, in the order they are read: whose stall this is, the number, when it was taken,
     * and what to do with the paper. The number is a [TicketLine.Banner] — drawn, not a doubled
     * font — because it is the only thing on the ticket that matters and it has to be readable
     * held up at arm's length.
     *
     * @param dotsOffset ตั้งค่า's adjustment to the number's height. ⚠️ No one has printed one of
     *   these yet, so the default is a chosen size and not a measured one; this is the control that
     *   settles it on paper without a build.
     */
    fun queueTicket(
        shopName: String,
        queueNo: Int,
        itemCount: Int,
        at: Long = System.currentTimeMillis(),
        dotsOffset: Int = 0
    ): Ticket = Ticket(
        buildList {
            // Every line on this ticket is [Wrapped], not [Text]: it is centred end to end, and a
            // centred Text goes to `printText`, which breaks by character in a font with no fixed
            // cell. That is what cut "ก๋วยเตี๋ยวคลุกเอี๋ยม" from its "จุ๊น" on the shop's first
            // real ticket. Measured here in the font that draws it, the same conclusion the price
            // column and the shop's kitchen slip both reached.
            add(TicketLine.Wrapped(shopName, bold = true, center = true))
            add(TicketLine.Rule)
            add(TicketLine.Wrapped("คิวที่", center = true))
            add(TicketLine.Banner(queueNo.toString(), heightDots = bannerDots(dotsOffset)))
            add(TicketLine.Rule)
            // How many bowls, never which ones. The count is what stops a two-order customer
            // walking off with one bag, and it gives away nothing the queue has to stop for.
            add(TicketLine.KeyValue("$itemCount รายการ", EventDay.clock(at), columns = HEADER_COLUMNS))
            add(TicketLine.Blank)
            add(TicketLine.Wrapped("กรุณาเก็บบัตรนี้ไว้", bold = true, center = true))
            // ⚠️ The space is load-bearing, and it is the only thing here that can break this
            // line. Thai writes none between words, so "รอเรียกคิวแล้วมารับอาหาร" is one unbroken
            // token twenty columns wide: measuring it tells you honestly that it does not fit and
            // then splits it mid-word anyway, which is "มารั" over "บอาหาร" again. A phrase space
            // where a Thai writer would put one gives the wrap somewhere to land. Any wording
            // added to this ticket needs the same treatment.
            add(TicketLine.Wrapped("รอเรียกคิว แล้วมารับอาหาร", center = true))
            add(TicketLine.FeedAndCut)
        },
        // One measured step down from the largest size, at the shop's word after the first real
        // ticket: "ขนาดตัวอักษรใหญ่ไปหน่อย". 20 dots is 18 columns and 24 was 15 — both counted on
        // paper, which is the only kind of pair this printer may be given (risk 2 in the shop's
        // own handoff). 18 is what lets the shop's name sit on one line at all: it measures 16
        // columns, so at 15 it could only ever wrap.
        //
        // The queue number is unaffected — a [TicketLine.Banner] is drawn to a height in dots and
        // has no font to be sized by, which is why that one is the setting and this one is not.
        font = ReceiptFont.MEDIUM
    )

    /**
     * The receipt — and, from 0.5.0, the slip the kitchen matches food to a queue with.
     *
     * That second job is what the layout now serves, at the shop's word after a service: the
     * number is a [TicketLine.Banner], the same drawn digit the customer's card carries and
     * moved by the same control, because the question being asked of this paper is "which queue
     * is this bowl for" and it is answered from a pile at arm's length. It used to be
     * `คิว 42` in an ordinary row, sized for someone holding the slip — which is what it was for
     * when the kitchen read its orders off an iPad.
     *
     * Three things came off to pay for it, all at the shop's request:
     *
     * - **The subtitle.** `ก๋วยเตี๋ยวคลุก · แคปป๊อป` under the shop's name listed the whole menu
     *   to somebody who had just bought from it. The setting went with the line — a field in
     *   ตั้งค่า that changes nothing on paper is worse than no field.
     * - **The payment QR.** The customer pays at the counter against the screen before this is
     *   ever printed, so the code was reprinting an answered question at about 15mm of paper.
     *   ⚠️ [paymentQr] stays and is still what the payment sheet draws — this removes the
     *   printed copy only. A receipt handed over **before** payment would now carry no way to
     *   pay, which the order flow does not do and would have to be rethought if it ever did.
     * - **The duplicated time.** `คิว 42 · 13:19` and then `13/11/2569 13:19` under it. The full
     *   date says both and is what makes the paper a receipt.
     *
     * The shop's name is [TicketLine.Wrapped] rather than `big`, which is the same correction the
     * queue ticket needed: `big` halves the columns, so at this size the name had nine to sit in
     * and `printText` would have broken it by character — "เอี๋ยม" from its "จุ๊น". Measured and
     * centred it fits on one line, and the biggest thing on the paper is the number, which is the
     * point of the change.
     *
     * The options **are** printed, unlike on the shop's own receipt, and they get **a line of
     * their own**. Both halves of that are forced:
     *
     * The shop leaves options off because its customer chose them a minute ago and twenty columns
     * is barely enough for the dish and the price. Here there is one dish, so the options are the
     * only thing distinguishing two lines on a bill — "ก๋วยเตี๋ยวคลุก" twice at different prices
     * is unreadable. And now the kitchen reads this slip, which makes them the operative words on
     * it rather than a courtesy.
     *
     * And they cannot share the dish's row. A [TicketLine.KeyValue] is **trimmed from the end** to
     * keep the price on the paper's right edge, and "ก๋วยเตี๋ยวคลุก เส้นหมี่ · เผ็ดน้อย ·
     * เพิ่มแคปป๊อป (ไม่ใส่ถั่ว)" is three times twenty columns — so everything that matters got cut
     * and the row read as a plain bowl at an odd price.
     *
     * @param dotsOffset ตั้งค่า's queue-number control, shared with [queueTicket]. One lever moves
     *   both numbers: the shop has one idea of how big a queue number should be, and two controls
     *   would be a state nobody could read off the settings screen. Split them if the kitchen's
     *   slip and the customer's card ever genuinely want different sizes.
     */
    fun receipt(
        shopName: String,
        queueNo: Int,
        lines: List<OrderLineEntity>,
        dotsOffset: Int = 0,
        printedAt: Long = System.currentTimeMillis()
    ): Ticket {
        val total = lines.sumOf { it.unitPrice * it.qty }
        return Ticket(
            buildList {
                add(TicketLine.Wrapped(shopName, bold = true, center = true))
                add(TicketLine.Rule)
                add(TicketLine.Wrapped("คิวที่", center = true))
                add(TicketLine.Banner(queueNo.toString(), heightDots = bannerDots(dotsOffset)))
                add(TicketLine.Rule)
                add(TicketLine.Text(EventDay.thaiDateTime(printedAt), dots = DATE_DOTS))

                // Grouped by everything that will be printed, the price included. Two rows that
                // read identically at the same price look like the till having rung it up twice;
                // keying on the price is what makes merging them safe, because every line behind a
                // row then costs the same and the row's total is the sum.
                lines.groupBy { RowKey(it.itemName, it.optionsSummary, it.note, it.unitPrice) }
                    .forEach { (key, same) ->
                        val qty = same.sumOf { it.qty }
                        add(
                            TicketLine.KeyValue(
                                left = key.name,
                                // Kept out of the trim: a shortened dish name still reads, a
                                // dropped count makes the bill impossible to check against the cash.
                                leftTail = if (qty > 1) " x$qty" else "",
                                right = "${key.unitPrice * qty}.-",
                                dots = ITEM_DOTS
                            )
                        )
                        // [Wrapped], not Text: on the Sunmi this is measured and broken in the font
                        // that draws it. Breaking it by column count is what stranded a lone "ก" on
                        // its own line on the shop's kitchen slip — there is no cell to count.
                        if (key.options.isNotBlank()) {
                            add(TicketLine.Wrapped("  ${key.options}", dots = ITEM_DOTS))
                        }
                        if (key.note.isNotBlank()) {
                            add(TicketLine.Wrapped("  * ${key.note}", dots = ITEM_DOTS))
                        }
                    }
                add(TicketLine.Rule)
                add(TicketLine.KeyValue("รวม", "$total.-", bold = true))
                add(TicketLine.Text("ขอบคุณที่อุดหนุน", center = true))
                add(TicketLine.FeedAndCut)
            },
            // Two dots up, which is the shop's "+2 scale" and lands on a pair somebody counted:
            // 20 dots is 18 columns. The driver's own default is [ReceiptFont.RECEIPT] at 18
            // dots, whose 20 columns is **interpolated** — and the item rows here are drawn by
            // `ReceiptRowBitmap`, which calibrates against that count, so staying on a counted
            // pair is not tidiness. MEDIUM is also what the queue ticket settled on after the
            // first real print, so the two documents now read at one size.
            font = ReceiptFont.MEDIUM
        )
    }

    /**
     * What makes two order lines one row on the receipt.
     *
     * The price is in the key and has to be: it is what makes the merge safe, since every line
     * behind a row then costs the same. A promotion or a hand-typed price stays its own row.
     */
    private data class RowKey(
        val name: String,
        val options: String,
        val note: String,
        val unitPrice: Int
    )

    /**
     * Which payment code to print, if any.
     *
     * A configured merchant QR wins and is **edited, never rebuilt**: ถุงเงิน's payload carries a
     * merchant reference that is not a PromptPay number and routes to schemes this code knows
     * nothing about, so [PromptPayQr.withAmount] copies every tag through and only inserts the
     * amount. When it cannot be edited safely the static code is printed unchanged and the receipt
     * asks the customer to type the amount — one extra tap beats a guessed QR by a wide margin.
     */
    fun paymentQr(merchantQr: String, promptPayId: String, total: Int): ScreenQr? {
        if (total <= 0) return null
        if (merchantQr.isNotBlank()) {
            PromptPayQr.withAmount(merchantQr, total)?.let { return ScreenQr(it, carriesAmount = true) }
            return ScreenQr(merchantQr, carriesAmount = false)
        }
        if (promptPayId.isNotBlank()) {
            return ScreenQr(PromptPayQr.payload(promptPayId, total), carriesAmount = true)
        }
        return null
    }

    /**
     * How tall the queue number is drawn, for whichever document is asking.
     *
     * Both the card and the receipt come through here, so ตั้งค่า cannot move one and leave the
     * other behind — which is the shape of bug this repo keeps recording, two copies of one
     * number drifting apart.
     */
    private fun bannerDots(dotsOffset: Int): Int =
        (DEFAULT_BANNER_DOTS + dotsOffset * BANNER_DOTS_PER_STEP).coerceAtLeast(40)

    /** One step of ตั้งค่า's queue-number control, in dots. */
    private const val BANNER_DOTS_PER_STEP = 20
}
