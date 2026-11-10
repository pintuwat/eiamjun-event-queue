package com.eiamjun.event.data

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * The event's trading day, which is not the calendar day.
 *
 * A food event can still be serving after midnight, so an order taken at 00:30 belongs to the
 * evening that is still going on. Winding the clock back [DAY_STARTS_AT_HOUR] hours before taking
 * the date does that, and **queue numbers restart on the same boundary** — which is the whole
 * reason this exists here: a queue ticket reading 412 on the second morning is a number nobody can
 * call across a tent.
 *
 * The timezone is fixed rather than read from the device, for the same reason the shop's till fixes
 * it: a terminal with an auto-set clock zone must not be able to file a day under the wrong date.
 *
 * `java.util.Calendar`, not `java.time`: minSdk is 23 (the Sunmi V1s floor) and `java.time` would
 * need core library desugaring for no gain at this size.
 */
object EventDay {

    const val DAY_STARTS_AT_HOUR = 7
    private const val TIMEZONE = "Asia/Bangkok"

    /** @return the trading day a moment belongs to, as `YYYY-MM-DD`. */
    fun of(at: Long = System.currentTimeMillis()): String {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone(TIMEZONE))
        calendar.timeInMillis = at - DAY_STARTS_AT_HOUR * 3_600_000L
        return String.format(
            Locale.US,
            "%04d-%02d-%02d",
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH)
        )
    }

    /** "14:05" in Bangkok, for the queue ticket and the on-screen list. */
    fun clock(at: Long = System.currentTimeMillis()): String {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone(TIMEZONE))
        calendar.timeInMillis = at
        return String.format(
            Locale.US, "%02d:%02d",
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE)
        )
    }

    /** "10/11/2569 14:05" — Buddhist-era year, the way a Thai receipt reads. */
    fun thaiDateTime(at: Long = System.currentTimeMillis()): String {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone(TIMEZONE))
        calendar.timeInMillis = at
        return String.format(
            Locale.US, "%02d/%02d/%04d %02d:%02d",
            calendar.get(Calendar.DAY_OF_MONTH),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.YEAR) + 543,
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE)
        )
    }
}
