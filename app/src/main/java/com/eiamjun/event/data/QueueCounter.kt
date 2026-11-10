package com.eiamjun.event.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One ticket's identity: which numbering round it belongs to, and the number printed on it.
 *
 * The round is what makes the number unique. See [QueueNumbering].
 */
data class QueueTicket(val session: Int, val number: Int)

/**
 * The numbering rule, as a pure function of what is stored and what day it is.
 *
 * Separated from [QueueCounter] so it can be tested without a device: the collision this rule
 * exists to prevent reached a real counter once, and an arithmetic rule that only runs on hardware
 * is one nobody checks.
 */
object QueueNumbering {

    /** Where a terminal that has never issued a ticket starts. */
    const val FIRST_SESSION = 1

    /** What the next ticket would be, without taking it. */
    fun peek(storedDay: String?, storedSession: Int, storedSequence: Int, today: String): QueueTicket =
        if (storedDay != today) QueueTicket(FIRST_SESSION, 1)
        else QueueTicket(storedSession.coerceAtLeast(FIRST_SESSION), storedSequence + 1)

    /**
     * The round a reset opens.
     *
     * On a day this terminal has not sold on yet there is nothing to step past, so it stays at the
     * first round — a reset then costs nothing and claims nothing. Otherwise it moves on, because
     * the day already holds a #1 and the next one has to be a different ticket from it.
     */
    fun sessionAfterReset(storedDay: String?, storedSession: Int, today: String): Int =
        if (storedDay != today) FIRST_SESSION else storedSession.coerceAtLeast(FIRST_SESSION) + 1
}

/**
 * The number printed on the customer's queue ticket: 1, 2, 3… restarting each event day.
 *
 * **Issued on the terminal, not by the server.** The ticket is printed with the customer standing
 * at the counter and cannot wait on a round trip to learn its own number — the event's wifi is not
 * their problem, and the whole point of the ticket is that it comes out immediately. The number
 * goes *up* with the order; the server never assigns one.
 *
 * That is only safe because this event runs **one** terminal. A second till would issue the same
 * numbers, and two customers holding ticket 14 is the one failure this app cannot recover from —
 * so a second terminal needs a prefix or a range, not another copy of this class.
 *
 * ⚠️ **A number alone is not an identity; the number and its round are.** เริ่มนับคิวใหม่ used to
 * zero the sequence and nothing else, so the first order of an afternoon session collided with the
 * morning's #1 — `UNIQUE constraint failed: orders.day, orders.queueNo`, which means no ticket, no
 * sale and a customer standing there. On the server, where the write would have succeeded, it was
 * worse: the replace-on-conflict that makes the outbox safe to retry would have **overwritten the
 * morning's order**. So a reset opens a new [QueueTicket.session], the session travels with every
 * order to the kitchen, and both unique indexes carry it.
 *
 * `commit` rather than `apply`: the number is about to be printed on paper, and a process death
 * before an async write would hand the next customer a duplicate.
 */
@Singleton
class QueueCounter @Inject constructor(@ApplicationContext context: Context) {

    private val prefs = context.getSharedPreferences("eiamjun-event-queue", Context.MODE_PRIVATE)

    private val storedDay: String? get() = prefs.getString(KEY_DAY, null)
    private val storedSession: Int get() = prefs.getInt(KEY_SESSION, QueueNumbering.FIRST_SESSION)
    private val storedSequence: Int get() = prefs.getInt(KEY_SEQUENCE, 0)

    /** Takes the next ticket, rolling over to the first round at 1 when the event day changes. */
    @Synchronized
    fun next(now: Long = System.currentTimeMillis()): QueueTicket {
        val today = EventDay.of(now)
        val ticket = QueueNumbering.peek(storedDay, storedSession, storedSequence, today)
        prefs.edit()
            .putString(KEY_DAY, today)
            .putInt(KEY_SESSION, ticket.session)
            .putInt(KEY_SEQUENCE, ticket.number)
            .commit()
        return ticket
    }

    /** What the next ticket would be, without taking it — for showing on screen. */
    fun peek(now: Long = System.currentTimeMillis()): QueueTicket =
        QueueNumbering.peek(storedDay, storedSession, storedSequence, EventDay.of(now))

    /**
     * Starts the numbering again at 1, from ตั้งค่า.
     *
     * An event is not a shop: two sessions can run on one calendar day, and the staff may want the
     * afternoon to start from 1. There is no automatic rule that could know, so it is a button —
     * and it is behind a confirmation, because numbers already on paper in customers' hands are
     * what it invalidates.
     *
     * It steps the round as well as zeroing the sequence, which is what keeps the afternoon's #1 a
     * different order from the morning's rather than a failed insert. See [QueueNumbering].
     */
    @Synchronized
    fun reset(now: Long = System.currentTimeMillis()) {
        val today = EventDay.of(now)
        prefs.edit()
            .putString(KEY_DAY, today)
            .putInt(KEY_SESSION, QueueNumbering.sessionAfterReset(storedDay, storedSession, today))
            .putInt(KEY_SEQUENCE, 0)
            .commit()
    }

    companion object {
        private const val KEY_DAY = "queue_day"
        private const val KEY_SEQUENCE = "queue_sequence"
        private const val KEY_SESSION = "queue_session"
    }
}
