package com.eiamjun.event.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stall's one bundle deal: น้ำซอสก๋วยเตี๋ยว, 39 a bottle, 3 for 100.
 *
 * Pinned here rather than trusted to the screen because it is arithmetic about money that only
 * shows itself at the third tap — and the figure the staff read off the pad is the figure the
 * customer is charged, so the two cannot be allowed to come from different calculations.
 */
class MenuPromoTest {

    private val sauce = MenuItemEntity(id = 7, name = "น้ำซอสก๋วยเตี๋ยว", price = 39, promoQty = 3, promoPrice = 100)
    private val plain = MenuItemEntity(id = 8, name = "แคปป๊อป เล็ก", price = 39)

    private fun cart(qty: Int, item: MenuItemEntity = sauce, unitPrice: Int = item.price) = CartLine(
        itemId = item.id,
        itemName = item.name,
        optionsSummary = "",
        note = "",
        qty = qty,
        unitPrice = unitPrice,
        promoQty = item.promoQty,
        promoPrice = item.promoPrice
    )

    @Test
    fun `below the bundle size nothing changes`() {
        assertEquals(39, cart(1).total)
        assertEquals(78, cart(2).total)
        assertEquals(0, cart(2).discount)
    }

    @Test
    fun `three bottles are the bundle price`() {
        assertEquals(100, cart(3).total)
        assertEquals(17, cart(3).discount)
        assertEquals(1, cart(3).bundles)
    }

    /** The remainder is charged normally — 100 + 39, never 139 rounded to something tidier. */
    @Test
    fun `the remainder is charged at the ordinary price`() {
        assertEquals(139, cart(4).total)
        assertEquals(178, cart(5).total)
        assertEquals(200, cart(6).total)
        assertEquals(239, cart(7).total)
    }

    @Test
    fun `a dish with no deal is unchanged at every quantity`() {
        (1..7).forEach { qty -> assertEquals(39 * qty, cart(qty, plain).total) }
    }

    /**
     * A bundle dearer than buying singly is a typo, and must cost the customer nothing. The till
     * being confidently wrong in the shop's favour is the one failure nobody would report.
     */
    @Test
    fun `a bundle priced above buying singly is ignored`() {
        val silly = MenuItemEntity(id = 9, name = "x", price = 10, promoQty = 3, promoPrice = 40)
        assertEquals(30, cart(3, silly).total)
        assertEquals(0, cart(3, silly).discount)
        assertEquals(30, silly.priceFor(3))
    }

    /** [MenuItemEntity.priceFor] and [CartLine.total] are the same arithmetic, or the pad lies. */
    @Test
    fun `the entity and the cart line agree at every quantity`() {
        (1..12).forEach { qty -> assertEquals(sauce.priceFor(qty), cart(qty).total) }
    }

    @Test
    fun `hasPromo needs both halves`() {
        assertTrue(sauce.hasPromo)
        assertTrue(!plain.hasPromo)
        assertTrue(!sauce.copy(promoQty = 1).hasPromo)
        assertTrue(!sauce.copy(promoPrice = 0).hasPromo)
    }

    /**
     * The discount line's own figures: per bundle, so `qty × unitPrice` is the whole saving and the
     * row reads as "two bundles were discounted" in the report.
     */
    @Test
    fun `the discount splits evenly across bundles`() {
        val six = cart(6)
        assertEquals(2, six.bundles)
        assertEquals(34, six.discount)
        assertEquals(17, six.discount / six.bundles)
        // What the order ends up carrying: the dish at the menu's price, less the deal.
        assertEquals(6 * 39 - 34, six.total)
    }

    @Test
    fun `the deal is named per dish so two deals cannot share a report row`() {
        assertEquals("ส่วนลด น้ำซอสก๋วยเตี๋ยว", Promo.lineName(sauce.name))
        assertEquals("โปรฯ 3 ชิ้น 100.-", Promo.lineOptions(3, 100))
        assertEquals("3 ชิ้น 100.-", Promo.keyNote(3, 100))
    }
}
