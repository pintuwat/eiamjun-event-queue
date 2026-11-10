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
                shopSubtitle = "ก๋วยเตี๋ยวคลุก",
                queueNo = 42,
                lines = listOf(noodles, crisps)
            )
        )
        println(text)

        assertTrue(text.contains("คิว 42"))
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
                shopSubtitle = "",
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
    fun `a receipt settled in cash carries no payment code`() {
        val qrOnIt = render(
            EventTicketFormatter.receipt(
                shopName = "ร้าน", shopSubtitle = "", queueNo = 1,
                lines = listOf(crisps), promptPayId = "0812345678", paidInCash = false
            )
        )
        val cash = render(
            EventTicketFormatter.receipt(
                shopName = "ร้าน", shopSubtitle = "", queueNo = 1,
                lines = listOf(crisps), promptPayId = "0812345678", paidInCash = true
            )
        )

        assertTrue(qrOnIt.contains("สแกนจ่ายเงิน"))
        // The money is already in the drawer; a code on a paid receipt invites paying twice.
        assertFalse(cash.contains("สแกนจ่ายเงิน"))
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
    fun `an unusable merchant QR prints the static code and says to type the amount`() {
        val text = render(
            EventTicketFormatter.receipt(
                shopName = "ร้าน", shopSubtitle = "", queueNo = 1,
                lines = listOf(crisps),
                // Not an EMVCo payload at all, so it cannot be edited safely.
                merchantQr = "NOT-A-QR-PAYLOAD"
            )
        )
        println(text)

        // Null from withAmount means "print the static code unchanged", never "guess". One extra
        // tap for the customer beats a guessed QR by a wide margin.
        assertTrue(text.contains("NOT-A-QR-PAYLOAD"))
        assertTrue(text.contains("กรุณาใส่ยอด 20.- บาท"))
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
        val receipt = EventTicketFormatter.receipt("ร้าน", "", 1, listOf(crisps))

        assertEquals(ReceiptFont.LARGE, queue.font)
        // The receipt carries no font of its own: its layout is settled and the driver's default is
        // it. A ticket that named one could be printed at the other document's size by a screen
        // that forgot which it was sending.
        assertEquals(null, receipt.font)
    }
}
