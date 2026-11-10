package com.eiamjun.event.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

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
 * `commit` rather than `apply`: the number is about to be printed on paper, and a process death
 * before an async write would hand the next customer a duplicate.
 */
@Singleton
class QueueCounter @Inject constructor(@ApplicationContext context: Context) {

    private val prefs = context.getSharedPreferences("eiamjun-event-queue", Context.MODE_PRIVATE)

    /** Takes the next number, rolling over to 1 when the event day changes. */
    @Synchronized
    fun next(now: Long = System.currentTimeMillis()): Int {
        val today = EventDay.of(now)
        val next = if (prefs.getString(KEY_DAY, null) == today) prefs.getInt(KEY_SEQUENCE, 0) + 1 else 1
        prefs.edit().putString(KEY_DAY, today).putInt(KEY_SEQUENCE, next).commit()
        return next
    }

    /** What the next ticket would be, without taking it — for showing on screen. */
    fun peek(now: Long = System.currentTimeMillis()): Int =
        if (prefs.getString(KEY_DAY, null) == EventDay.of(now)) prefs.getInt(KEY_SEQUENCE, 0) + 1 else 1

    /**
     * Starts the numbering again at 1, from ตั้งค่า.
     *
     * An event is not a shop: two sessions can run on one calendar day, and the staff may want the
     * afternoon to start from 1. There is no automatic rule that could know, so it is a button —
     * and it is behind a confirmation, because numbers already on paper in customers' hands are
     * what it invalidates.
     */
    @Synchronized
    fun reset(now: Long = System.currentTimeMillis()) {
        prefs.edit().putString(KEY_DAY, EventDay.of(now)).putInt(KEY_SEQUENCE, 0).commit()
    }

    companion object {
        private const val KEY_DAY = "queue_day"
        private const val KEY_SEQUENCE = "queue_sequence"
    }
}
