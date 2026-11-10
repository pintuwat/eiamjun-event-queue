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
            add(TicketLine.Text(shopName, bold = true, center = true))
            add(TicketLine.Rule)
            add(TicketLine.Text("คิวที่", center = true))
            add(
                TicketLine.Banner(
                    queueNo.toString(),
                    heightDots = (DEFAULT_BANNER_DOTS + dotsOffset * BANNER_DOTS_PER_STEP)
                        .coerceAtLeast(40)
                )
            )
            add(TicketLine.Rule)
            // How many bowls, never which ones. The count is what stops a two-order customer
            // walking off with one bag, and it gives away nothing the queue has to stop for.
            add(TicketLine.KeyValue("$itemCount รายการ", EventDay.clock(at), columns = HEADER_COLUMNS))
            add(TicketLine.Blank)
            add(TicketLine.Text("กรุณาเก็บบัตรนี้ไว้", bold = true, center = true))
            add(TicketLine.Text("รอเรียกคิวแล้วมารับอาหาร", center = true))
            add(TicketLine.FeedAndCut)
        },
        // The largest font this printer has a measured column count for. The banner is drawn and
        // ignores it; the few printed lines around it are short, and large suits a ticket that is
        // read at arm's length.
        font = ReceiptFont.LARGE
    )

    /**
     * The receipt, printed when the customer collects and asks for one.
     *
     * The queue number is on it at the ordinary size — it is how a slip is matched back to a bag,
     * not the point of the paper, and the owner asked for it that way in those words.
     *
     * The options **are** printed here, unlike on the shop's own receipt, and they get **a line of
     * their own**. Both halves of that are forced:
     *
     * The shop leaves options off because its customer chose them a minute ago and twenty columns
     * is barely enough for the dish and the price. Here there is one dish, so the options are the
     * only thing distinguishing two lines on a bill — "ก๋วยเตี๋ยวคลุก" twice at different prices
     * is unreadable.
     *
     * And they cannot share the dish's row. A [TicketLine.KeyValue] is **trimmed from the end** to
     * keep the price on the paper's right edge, and "ก๋วยเตี๋ยวคลุก เส้นหมี่ · เผ็ดน้อย ·
     * เพิ่มแคปป๊อป (ไม่ใส่ถั่ว)" is three times twenty columns — so everything that matters got cut
     * and the row read as a plain bowl at an odd price. The shop's rule against a second line was
     * about *its* receipt, where the kitchen slip carries the options instead; this event prints
     * no kitchen slip at all.
     */
    fun receipt(
        shopName: String,
        shopSubtitle: String,
        queueNo: Int,
        lines: List<OrderLineEntity>,
        merchantQr: String = "",
        promptPayId: String = "",
        qrDotSize: Int = DEFAULT_QR_DOTS,
        paidInCash: Boolean = false,
        printedAt: Long = System.currentTimeMillis()
    ): Ticket {
        val total = lines.sumOf { it.unitPrice * it.qty }
        return Ticket(
            buildList {
                add(TicketLine.Text(shopName, bold = true, center = true, big = true))
                if (shopSubtitle.isNotBlank()) add(TicketLine.Text(shopSubtitle, center = true))
                add(TicketLine.KeyValue("คิว $queueNo", EventDay.clock(printedAt), columns = HEADER_COLUMNS))
                add(TicketLine.Text(EventDay.thaiDateTime(printedAt)))
                add(TicketLine.Rule)

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
                                right = "${key.unitPrice * qty}.-"
                            )
                        )
                        // [Wrapped], not Text: on the Sunmi this is measured and broken in the font
                        // that draws it. Breaking it by column count is what stranded a lone "ก" on
                        // its own line on the shop's kitchen slip — there is no cell to count.
                        if (key.options.isNotBlank()) add(TicketLine.Wrapped("  ${key.options}"))
                        if (key.note.isNotBlank()) add(TicketLine.Wrapped("  * ${key.note}"))
                    }
                add(TicketLine.Rule)
                add(TicketLine.KeyValue("รวม", "$total.-", bold = true))

                // No QR on a bill already settled in cash: the money is in the drawer, and a
                // payment code on a paid receipt is an invitation to pay twice.
                if (!paidInCash) {
                    val qr = paymentQr(merchantQr, promptPayId, total)
                    if (qr != null) {
                        add(TicketLine.Text("สแกนจ่ายเงิน", bold = true, center = true))
                        add(TicketLine.QrCode(qr.payload, moduleSize = qrDotSize))
                        // Only when the code could not be given the amount. With the amount in it
                        // the phone fills the number in and รวม is three lines up.
                        if (!qr.carriesAmount) {
                            add(TicketLine.Text("กรุณาใส่ยอด $total.- บาท", center = true))
                        }
                    }
                }
                add(TicketLine.Text("ขอบคุณที่อุดหนุน", center = true))
                add(TicketLine.FeedAndCut)
            }
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

    /** One step of ตั้งค่า's queue-number control, in dots. */
    private const val BANNER_DOTS_PER_STEP = 20
}
