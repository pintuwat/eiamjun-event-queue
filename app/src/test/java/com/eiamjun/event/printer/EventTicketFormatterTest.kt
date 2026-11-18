package com.eiamjun.event.printer

import com.eiamjun.event.data.OrderLineEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the two documents' shapes.
 *
 * Rendered through [PreviewPrinter], which is pure Kotlin — the real Sunmi path draws rows with
 * `android.graphics` and cannot run here. So these tests check **what is on each document and what
 * is deliberately not**, which is the part that keeps getting re-litigated, rather than where the
 * ink lands, which only paper can answer.
 */
class EventTicketFormatterTest {

    private val noodles = OrderLineEntity(
        orderId = 1,
        itemName = "ก๋วยเตี๋ยวคลุก",
        optionsSummary = "เส้นหมี่ · เผ็ดน้อย · เพิ่มแคปป๊อป",
        note = "ไม่ใส่ถั่ว",
        qty = 2,
        unitPrice = 60
    )

    private val crisps = OrderLineEntity(
        orderId = 1,
        itemName = "แคปป๊อป",
        qty = 1,
        unitPrice = 20
    )

    private fun render(ticket: Ticket) = PreviewPrinter.asPlainText(ticket)

    @Test
    fun `the queue ticket carries the number and no menu at all`() {
        val text = render(
            EventTicketFormatter.queueTicket(shopName = "เอี้ยมจุ๊น", queueNo = 42, itemCount = 3)
        )
        println(text)

        assertTrue("the number has to be on it", text.contains("4 2"))
        assertTrue("and said to be a queue", text.contains("คิวที่"))
        assertTrue("how many bags to expect", text.contains("3 รายการ"))
        // The point of this document: the customer walks away with a number, not a bill. Listing
        // the food invites them to check it at the counter, which is the queue this app shortens.
        assertFalse("no dish names", text.contains("ก๋วยเตี๋ยว"))
        assertFalse("no prices", text.contains(".-"))
    }

    @Test
    fun `the queue number is drawn, not a doubled font`() {
        val ticket = EventTicketFormatter.queueTicket("เอี้ยมจุ๊น", queueNo = 7, itemCount = 1)
        val banner = ticket.lines.filterIsInstance<TicketLine.Banner>()
        assertEquals("exactly one banner, and it is the number", 1, banner.size)
        assertEquals("7", banner.first().text)
        // A doubled printer cell tops out around 48 dots, which is a tenth of what a number read
        // across a counter needs. If this ever drops back to Text(big = true), the ticket is
        // unreadable at arm's length.
        assertTrue(banner.first().heightDots > 48)
    }

    @Test
    fun `every line of the queue ticket is measured, not handed to the printer to break`() {
        val ticket = EventTicketFormatter.queueTicket("ก๋วยเตี๋ยวคลุกเอี้ยมจุ๊น", 42, 2)

        // A centred Text goes through `printText`, which breaks by character in a font that has no
        // fixed cell — the shop's first real ticket came back with "เอี้ยม" split from "จุ๊น" and
        // "มารับอาหาร" split as "มารั" / "บอาหาร". There is no line on this document short enough
        // to be worth the risk, so the rule is all of them or none.
        assertTrue(
            "no TicketLine.Text on the queue ticket — it is the one that the printer breaks",
            ticket.lines.none { it is TicketLine.Text }
        )
        val wrapped = ticket.lines.filterIsInstance<TicketLine.Wrapped>()
        assertEquals("shop name, คิวที่, and the two footer lines", 4, wrapped.size)
        assertTrue("and the whole ticket is centred", wrapped.all { it.center })
    }

    @Test
    fun `the footer has somewhere to break, because Thai writes no spaces`() {
        val footer = EventTicketFormatter.queueTicket("ร้าน", 1, 1)
            .lines.filterIsInstance<TicketLine.Wrapped>()
            .last()

        // Measuring a line only tells you honestly whether it fits. "รอเรียกคิวแล้วมารับอาหาร" is
        // one token of twenty columns, so on an eighteen-column slip it is split by measured
        // characters — a mid-word break arrived at carefully. The space is the whole fix, and
        // removing it puts the fault straight back.
        assertTrue("the phrase still reads", footer.text.replace(" ", "") == "รอเรียกคิวแล้วมารับอาหาร")
        assertTrue("and carries a break opportunity", footer.text.contains(" "))
    }

    @Test
    fun `the queue number's size follows the setting, in both directions`() {
        val smaller = EventTicketFormatter.queueTicket("ร้าน", 1, 1, dotsOffset = -2)
            .lines.filterIsInstance<TicketLine.Banner>().first().heightDots
        val plain = EventTicketFormatter.queueTicket("ร้าน", 1, 1, dotsOffset = 0)
            .lines.filterIsInstance<TicketLine.Banner>().first().heightDots
        val bigger = EventTicketFormatter.queueTicket("ร้าน", 1, 1, dotsOffset = 3)
            .lines.filterIsInstance<TicketLine.Banner>().first().heightDots

        assertTrue("a step down has to actually move it", smaller < plain)
        assertTrue("and so does a step up", bigger > plain)
        assertEquals("offset 0 is the chosen default, untouched", DEFAULT_BANNER_DOTS, plain)
    }

    @Test
    fun `the receipt itemises with options, because one dish is all there is`() {
        val text = render(
            EventTicketFormatter.receipt(
                shopName = "เอี้ยมจุ๊น",
                queueNo = 42,
                lines = listOf(noodles, crisps)
            )
        )
        println(text)

        assertTrue(text.contains("ก๋วยเตี๋ยวคลุก"))
        // The shop's own receipt leaves options off; this one cannot. With a single dish on the
        // menu the options are the only thing telling two lines apart, and a bill reading the same
        // name twice at different prices is a bill nobody can check.
        assertTrue("the options distinguish the lines here", text.contains("เส้นหมี่"))
        assertTrue("and so does a note", text.contains("ไม่ใส่ถั่ว"))
        assertTrue("the count is on the line", text.contains("x2"))
        assertTrue("and the total", text.contains("140.-"))
    }

    @Test
    fun `two lines that print identically become one row with one total`() {
        val text = render(
            EventTicketFormatter.receipt(
                shopName = "ร้าน",
                queueNo = 1,
                // Same dish, same options, same price, entered as two taps.
                lines = listOf(crisps, crisps.copy(id = 2))
            )
        )
        println(text)

        // Two identical rows at the same price read as the till having rung it up twice. Keying on
        // the price as well as the wording is what makes merging them safe: every line behind the
        // row costs the same, so the row's total is the sum and no arithmetic is lost.
        assertEquals(1, text.lines().count { it.contains("แคปป๊อป") })
        assertTrue(text.contains("x2"))
        assertTrue(text.contains("40.-"))
    }

    @Test
    fun `the receipt carries no payment code at all, however the order was paid`() {
        val byQr = render(
            EventTicketFormatter.receipt(
                shopName = "ร้าน", queueNo = 1,
                lines = listOf(crisps)
            )
        )

        // The customer pays at the counter against the screen before this is ever printed, so a
        // printed code reprints an answered question at about 15mm of paper — and the shop wanted
        // that paper for the queue number. [paymentQr] still exists and still feeds the payment
        // sheet; only the printed copy is gone.
        assertFalse(byQr.contains("สแกนจ่ายเงิน"))
        assertFalse("and nothing is left asking for an amount", byQr.contains("กรุณาใส่ยอด"))
        assertTrue(
            "nor a QR line of any kind",
            EventTicketFormatter.receipt(shopName = "ร้าน", queueNo = 1, lines = listOf(crisps))
                .lines.none { it is TicketLine.QrCode }
        )
    }

    @Test
    fun `the receipt's queue number is drawn large, and the same control moves both documents`() {
        fun banner(dots: Int) = EventTicketFormatter
            .receipt(shopName = "ร้าน", queueNo = 7, lines = listOf(crisps), dotsOffset = dots)
            .lines.filterIsInstance<TicketLine.Banner>().single()

        // Drawn, not a doubled font: this is what the kitchen matches a bowl to a queue with,
        // read off a pile rather than held in the hand.
        assertEquals("7", banner(0).text)
        assertEquals(DEFAULT_BANNER_DOTS, banner(0).heightDots)
        assertTrue("a step down moves it", banner(-2).heightDots < banner(0).heightDots)

        // One lever, both documents — two would be a state nobody could read off ตั้งค่า.
        val card = EventTicketFormatter.queueTicket("ร้าน", 7, 1, dotsOffset = -2)
            .lines.filterIsInstance<TicketLine.Banner>().single()
        assertEquals(card.heightDots, banner(-2).heightDots)
    }

    @Test
    fun `the shop name is measured, not doubled, so it cannot break mid-word`() {
        val head = EventTicketFormatter
            .receipt(shopName = "ก๋วยเตี๋ยวคลุกเอี้ยมจุ๊น", queueNo = 1, lines = listOf(crisps))
            .lines.first()

        // `big` halves the columns, leaving nine at this size — and a centred [Text] goes to
        // `printText`, which breaks by character in a font with no fixed cell. That is how
        // "เอี๋ยม" lost its "จุ๊น" on the first real queue ticket.
        assertTrue("the head must be measured", head is TicketLine.Wrapped)
        assertTrue((head as TicketLine.Wrapped).center)
    }

    @Test
    fun `nothing under the shop name lists the menu back to the customer`() {
        val head = EventTicketFormatter
            .receipt(shopName = "เอี้ยมจุ๊น", queueNo = 1, lines = listOf(crisps))
            .lines

        // The subtitle was "ก๋วยเตี๋ยวคลุก · แคปป๊อป" — the whole menu, printed for somebody who
        // had just bought from it, in the space the queue number now occupies. The line straight
        // after the name is the rule, so there is nowhere for it to come back without being seen.
        assertEquals("เอี้ยมจุ๊น", (head[0] as TicketLine.Wrapped).text)
        assertEquals(TicketLine.Rule, head[1])
        // And the dish is named once, on its own row, rather than twice.
        assertEquals(1, render(Ticket(head)).lines().count { it.contains("แคปป๊อป") })
    }

    @Test
    fun `a merchant QR is edited to carry the amount, never rebuilt`() {
        // The shop's real ถุงเงิน payload shape: tag 30, a biller id, a merchant reference. None of
        // it could be reconstructed from a PromptPay number, so withAmount copies every tag through.
        val shopQr = PromptPayQr.payload("0812345678")
        val edited = EventTicketFormatter.paymentQr(merchantQr = shopQr, promptPayId = "", total = 75)

        requireNotNull(edited)
        assertTrue("the amount is in the code, so the customer types nothing", edited.carriesAmount)
        assertTrue(edited.payload.contains("540575.00"))
        // Still a valid payload: tag 01 flipped to dynamic and the checksum recomputed.
        assertTrue(edited.payload.contains("010212"))
        val fields = PromptPayQr.parse(edited.payload)
        requireNotNull(fields)
        assertEquals("63", fields.last().first)
    }

    @Test
    fun `an unusable merchant QR is handed to the screen as-is, never guessed at`() {
        // Null from withAmount means "use the static code unchanged", never "guess", and the
        // sheet says so by printing the amount beside it. One extra tap for the customer beats a
        // guessed QR by a wide margin.
        val answer = EventTicketFormatter.paymentQr("NOT-A-QR-PAYLOAD", "", 20)

        requireNotNull(answer)
        assertEquals("NOT-A-QR-PAYLOAD", answer.payload)
        assertFalse("so the screen has to ask for the amount", answer.carriesAmount)
    }

    @Test
    fun `with nothing configured there is no payment code rather than a wrong one`() {
        assertEquals(null, EventTicketFormatter.paymentQr("", "", 50))
        // And never one for nothing owed.
        assertEquals(null, EventTicketFormatter.paymentQr("", "0812345678", 0))
    }

    @Test
    fun `each document asks for its own size and cannot be sent at the other's`() {
        val queue = EventTicketFormatter.queueTicket("ร้าน", 1, 1)
        val receipt = EventTicketFormatter.receipt("ร้าน", 1, listOf(crisps))

        // Both land on MEDIUM, and both have to land on a size somebody counted on paper: 20
        // dots is 18 columns. The receipt's rows are drawn by `ReceiptRowBitmap`, which
        // calibrates against that count, so an interpolated pair here puts a drawn row at a
        // different size from the printed lines around it — risk 2 in the shop's own handoff.
        assertEquals(ReceiptFont.MEDIUM, queue.font)
        assertEquals(ReceiptFont.MEDIUM, receipt.font)
        listOf(queue, receipt).forEach {
            val counted = it.font!!
            assertTrue(
                "${counted.name} must be a measured pair, not an interpolated one",
                counted.dots to counted.charsPerLine in listOf(16 to 22, 20 to 18, 24 to 15)
            )
        }
    }

    @Test
    fun `the kitchen's words print larger and the date smaller, on the same slip`() {
        val ticket = EventTicketFormatter.receipt(
            shopName = "ก๋วยเตี๋ยวคลุกเอี๋ยมจุ๊น",
            queueNo = 7,
            lines = listOf(noodles)
        )

        // The lines the kitchen acts on. Up, because this slip is read off a spike while cooking.
        val item = ticket.lines.filterIsInstance<TicketLine.KeyValue>().first { it.left.contains("ก๋วยเตี๋ยว") }
        assertEquals(3, item.dots)
        val options = ticket.lines.filterIsInstance<TicketLine.Wrapped>().first { it.text.contains("เส้นหมี่") }
        assertEquals(3, options.dots)
        val note = ticket.lines.filterIsInstance<TicketLine.Wrapped>().first { it.text.contains("ไม่ใส่ถั่ว") }
        assertEquals(3, note.dots)

        // The one line nobody acts on. It settles an argument later; it is not read now.
        val date = ticket.lines.filterIsInstance<TicketLine.Text>().first { it.text.contains("2569") }
        assertEquals(-3, date.dots)
    }

    @Test
    fun `the lines a customer checks stay at the ticket's own size`() {
        val ticket = EventTicketFormatter.receipt(
            shopName = "ก๋วยเตี๋ยวคลุกเอี๋ยมจุ๊น",
            queueNo = 7,
            lines = listOf(noodles)
        )

        // รวม is what gets checked against the cash, and the header is the shop's own name: both
        // were approved on paper at the ticket's size, so a per-line nudge must not reach them.
        // The queue number is a Banner and has no font to be sized by at all.
        val total = ticket.lines.filterIsInstance<TicketLine.KeyValue>().first { it.left == "รวม" }
        assertEquals(0, total.dots)
        val header = ticket.lines.filterIsInstance<TicketLine.Wrapped>().first { it.bold }
        assertEquals(0, header.dots)
        val thanks = ticket.lines.filterIsInstance<TicketLine.Text>().first { it.center }
        assertEquals(0, thanks.dots)
    }
}
