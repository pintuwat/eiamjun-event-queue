package com.eiamjun.event.printer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixtures cross-checked against an independent reference implementation of the PromptPay spec.
 * If a bank app ever refuses a printed QR, run these first — a broken payload is the likeliest cause.
 */
class PromptPayQrTest {

    @Test
    fun `phone number with amount produces dynamic payload`() {
        assertEquals(
            "00020101021229370016A000000677010111011300668123456785303764540580.005802TH6304439C",
            PromptPayQr.payload("0812345678", 80)
        )
    }

    @Test
    fun `phone number without amount produces static payload`() {
        assertEquals(
            "00020101021129370016A0000006770101110113006681234567853037645802TH6304823E",
            PromptPayQr.payload("0812345678")
        )
    }

    @Test
    fun `national id uses sub-tag 02`() {
        assertEquals(
            "00020101021229370016A0000006770101110213123456789012353037645406100.005802TH6304F1A4",
            PromptPayQr.payload("1234567890123", 100)
        )
    }

    @Test
    fun `punctuation in the phone number is ignored`() {
        assertEquals(
            PromptPayQr.payload("0812345678", 80),
            PromptPayQr.payload("081-234-5678", 80)
        )
    }

    @Test
    fun `phone is converted to international form and padded to 13 digits`() {
        assertEquals("0066812345678" to "01", PromptPayQr.normalizeTarget("0812345678"))
    }
}

/**
 * ถุงเงิน and other merchant wallets identify the shop with a 15-digit e-wallet ID rather than a
 * phone number. The only difference in the payload is tag 29's sub-tag — 03 instead of 01 — and the
 * digits going in unpadded, but money follows this, so it is worth its own fixtures.
 */
class PromptPayEWalletTest {

    @Test
    fun `a 15-digit id is treated as an e-wallet, not padded like a phone`() {
        assertEquals("123456789012345" to "03", PromptPayQr.normalizeTarget("123456789012345"))
    }

    @Test
    fun `e-wallet payload carries the id and the amount`() {
        val payload = PromptPayQr.payload("123456789012345", 125)
        println("E-WALLET PAYLOAD: $payload")

        assertTrue("dynamic QR", payload.startsWith("000201010212"))
        assertTrue("tag 29 with the PromptPay AID", payload.contains("0016A000000677010111"))
        assertTrue("sub-tag 03 carrying 15 digits", payload.contains("0315123456789012345"))
        assertTrue("THB", payload.contains("5303764"))
        assertTrue("amount 125.00", payload.contains("5406125.00"))
        assertTrue("Thailand", payload.contains("5802TH"))
        assertTrue("CRC tag present", payload.contains("6304"))
    }

    @Test
    fun `a 16-digit id is still an e-wallet`() {
        val (digits, tag) = PromptPayQr.normalizeTarget("0123456789012345")
        assertEquals("03", tag)
        assertEquals("0123456789012345", digits)
    }
}

/**
 * Editing a shop's existing merchant QR to carry an amount. The payloads here are synthetic but
 * structured like the real thing — a merchant id that is not a phone number, and extra tags for
 * other payment networks that must survive the edit untouched.
 */
class MerchantQrAmountTest {

    private fun tlv(id: String, value: String) = id + "%02d".format(value.length) + value

    /**
     * Shaped like a ถุงเงิน counter sign: PromptPay plus a second payment network, no amount.
     * Built rather than written out, because hand-counting TLV lengths is exactly the mistake this
     * code exists to avoid making.
     */
    private val staticMerchantQr: String = run {
        val body = buildString {
            append(tlv("00", "01"))
            append(tlv("01", "11"))
            append(tlv("26", tlv("00", "A000000677010112") + tlv("01", "EXAMPLE00REF000000X")))
            append(tlv("29", tlv("00", "A000000677010111") + tlv("03", "123456789012345")))
            append(tlv("53", "764"))
            append(tlv("58", "TH"))
            append("6304")
        }
        body + "%04X".format(PromptPayQr.crc16(body))
    }

    @Test
    fun `an amount is inserted and the checksum recomputed`() {
        val dynamic = PromptPayQr.withAmount(staticMerchantQr, 125) ?: error("should have edited")

        assertTrue("now a dynamic QR", dynamic.contains("010212"))
        assertTrue("carries the amount", dynamic.contains("5406125.00"))

        // The checksum must cover everything up to and including "6304".
        val body = dynamic.substring(0, dynamic.length - 4)
        val stated = dynamic.takeLast(4)
        assertEquals(String.format("%04X", PromptPayQr.crc16(body)), stated)
    }

    @Test
    fun `every other tag survives the edit untouched`() {
        val dynamic = PromptPayQr.withAmount(staticMerchantQr, 50)!!
        val before = PromptPayQr.parse(staticMerchantQr)!!.filterNot { it.first in setOf("01", "63") }
        val after = PromptPayQr.parse(dynamic)!!.filterNot { it.first in setOf("01", "54", "63") }
        assertEquals("the merchant's own tags must be copied through", before, after)
    }

    @Test
    fun `the amount lands before the country code, as the spec orders it`() {
        val dynamic = PromptPayQr.withAmount(staticMerchantQr, 50)!!
        val ids = PromptPayQr.parse(dynamic)!!.map { it.first }
        assertTrue("54 before 58", ids.indexOf("54") < ids.indexOf("58"))
        assertTrue("53 before 54", ids.indexOf("53") < ids.indexOf("54"))
    }

    @Test
    fun `nonsense input is refused rather than guessed at`() {
        assertEquals(null, PromptPayQr.withAmount("not a qr payload", 50))
        assertEquals(null, PromptPayQr.withAmount("", 50))
        // Well-formed TLV, but no merchant account tag — nothing we should be editing.
        assertEquals(null, PromptPayQr.withAmount(tlv("00", "01") + tlv("53", "764"), 50))
    }

    @Test
    fun `re-pricing a QR that already has an amount replaces it`() {
        val first = PromptPayQr.withAmount(staticMerchantQr, 50)!!
        val second = PromptPayQr.withAmount(first, 75)!!
        // "75.00" is five characters, so the tag is 5405 — the length is part of the encoding.
        assertTrue("re-priced", second.contains("540575.00"))
        assertTrue("no stale amount left behind", !second.contains("540550.00"))
    }
}

/**
 * A ถุงเงิน counter-sign payload, in exactly the shape a real one has.
 *
 * ⚠️ The biller id and merchant reference are **made up**. A merchant reference is the string
 * that decides where money lands, and this repository is public — the shop's own stays in the
 * shop's own repository. Every tag and every length matches a real counter sign, so this
 * exercises precisely what the real payload did.
 *
 * It is a PromptPay **Bill Payment** QR (tag 30, AID A000000677010112) — a Biller ID plus the
 * merchant reference — not the e-wallet (tag 29) this code can build from scratch. That is the
 * whole reason [PromptPayQr.withAmount] exists: the merchant's own tags are copied through and only
 * the amount is added.
 *
 * The expected strings were produced by an independent implementation of the same rule, so this
 * compares two separate workings rather than asserting that the code agrees with itself.
 */
class BillPaymentQrTest {

    private val staticQr =
        "00020101021130750016A00000067701011201150099000000000010219EXAMPLE00REF000000X" +
            "0309TUNGNGERN53037645802TH62080704000063049C84"

    @Test
    fun `a counter sign parses as a Bill Payment QR with no amount`() {
        val fields = PromptPayQr.parse(staticQr)!!.toMap()
        assertEquals("static — the customer types the amount", "11", fields["01"])
        assertEquals(null, fields["54"])
        assertTrue("tag 30 Bill Payment", fields["30"]!!.contains("A000000677010112"))
        assertTrue("the merchant reference", fields["30"]!!.contains("EXAMPLE00REF000000X"))
    }

    @Test
    fun `one baht`() {
        assertEquals(
            "00020101021230750016A00000067701011201150099000000000010219EXAMPLE00REF000000X" +
                "0309TUNGNGERN530376454041.005802TH62080704000063049C51",
            PromptPayQr.withAmount(staticQr, 1)
        )
    }

    @Test
    fun `fifty-five baht`() {
        assertEquals(
            "00020101021230750016A00000067701011201150099000000000010219EXAMPLE00REF000000X" +
                "0309TUNGNGERN5303764540555.005802TH62080704000063043EE8",
            PromptPayQr.withAmount(staticQr, 55)
        )
    }

    @Test
    fun `the merchant's own tags are untouched`() {
        val before = PromptPayQr.parse(staticQr)!!.toMap()
        val after = PromptPayQr.parse(PromptPayQr.withAmount(staticQr, 99)!!)!!.toMap()
        assertEquals("the part that decides where money goes", before["30"], after["30"])
        assertEquals(before["53"], after["53"])
        assertEquals(before["58"], after["58"])
        assertEquals(before["62"], after["62"])
    }
}
