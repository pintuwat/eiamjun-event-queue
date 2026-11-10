package com.eiamjun.event.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The numbering rule, and the collision it exists to prevent.
 *
 * เริ่มนับคิวใหม่ used to zero the sequence and nothing else, so the afternoon's first order
 * carried the same `(day, queueNo)` as the morning's #1 — the unique index refused it, `charge()`
 * threw, and the customer standing there got no ticket and was not charged. The round is now part
 * of the identity on both sides, and these pin the terminal's half.
 */
class QueueNumberingTest {

    private val today = "2026-11-10"
    private val yesterday = "2026-11-09"

    @Test
    fun `a fresh terminal starts at round one, number one`() {
        assertEquals(
            QueueTicket(1, 1),
            QueueNumbering.peek(storedDay = null, storedSession = 1, storedSequence = 0, today = today)
        )
    }

    @Test
    fun `the numbers run on within a round`() {
        assertEquals(
            QueueTicket(1, 4),
            QueueNumbering.peek(today, storedSession = 1, storedSequence = 3, today = today)
        )
    }

    @Test
    fun `a new event day starts over at round one`() {
        // Not round 3 and not number 42: a ticket reading 412 on the second morning is a number
        // nobody can call across a tent, and the previous day's rounds are nothing to this one.
        assertEquals(
            QueueTicket(1, 1),
            QueueNumbering.peek(yesterday, storedSession = 3, storedSequence = 41, today = today)
        )
    }

    @Test
    fun `a reset mid-day opens the next round`() {
        val session = QueueNumbering.sessionAfterReset(today, storedSession = 1, today = today)
        assertEquals(2, session)

        // Which is the whole point: the number goes back to 1 for the customer, and the order it
        // belongs to is still a different order from the morning's #1.
        val ticket = QueueNumbering.peek(today, storedSession = session, storedSequence = 0, today = today)
        assertEquals(QueueTicket(2, 1), ticket)
        assertTrue(ticket.number == 1 && ticket.session != 1)
    }

    @Test
    fun `resets stack, so a third service is its own round again`() {
        var session = QueueNumbering.sessionAfterReset(today, 1, today)
        session = QueueNumbering.sessionAfterReset(today, session, today)
        assertEquals(3, session)
    }

    @Test
    fun `a reset on a day this terminal has not sold on claims nothing`() {
        // Nothing has been issued today, so there is no #1 to step past — and leaving the round at
        // 1 keeps the common case (open the app, reset out of habit, start selling) on the round
        // every stored order already uses.
        assertEquals(1, QueueNumbering.sessionAfterReset(yesterday, storedSession = 4, today = today))
        assertEquals(1, QueueNumbering.sessionAfterReset(null, storedSession = 1, today = today))
    }

    @Test
    fun `a round stored as zero reads as the first one`() {
        // What an install that predates the column has: `getInt` default aside, a 0 must never
        // reach the database, because the server treats a missing round as 1 and the two sides
        // have to agree about the same order.
        assertEquals(
            QueueTicket(1, 2),
            QueueNumbering.peek(today, storedSession = 0, storedSequence = 1, today = today)
        )
        assertEquals(2, QueueNumbering.sessionAfterReset(today, storedSession = 0, today = today))
    }

    @Test
    fun `a ready list matches on the round, not on the number alone`() {
        val morning = OrderIdentity(id = 11, session = 1, queueNo = 1)
        val afternoon = OrderIdentity(id = 12, session = 2, queueNo = 1)

        val readyAfternoon = QueueKey(session = 2, queueNo = 1)
        assertTrue(readyAfternoon.matches(afternoon))
        assertTrue(!readyAfternoon.matches(morning))

        // A server that predates the column cannot say which round it meant, so a null matches by
        // number — the shim for the minutes between deploying it and installing this.
        val unspecified = QueueKey(session = null, queueNo = 1)
        assertTrue(unspecified.matches(morning))
        assertTrue(unspecified.matches(afternoon))

        assertTrue(!QueueKey(session = 2, queueNo = 2).matches(afternoon))
    }
}
