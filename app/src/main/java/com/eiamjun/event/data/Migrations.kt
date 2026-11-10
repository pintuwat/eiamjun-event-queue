package com.eiamjun.event.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema changes, one object per step.
 *
 * There is no `fallbackToDestructiveMigration()` — see [AppDatabase]. A database that holds orders
 * already paid for has to fail loudly on a missing migration rather than quietly delete the
 * service, so **every schema change from here needs one of these.**
 */
object Migrations {

    /**
     * Adds `orders.session` and moves the unique index onto it.
     *
     * The old index was `(day, queueNo)`, which made the number alone an order's identity within a
     * day. เริ่มนับคิวใหม่ starts the numbers again — the button exists because two services can
     * run on one calendar day — so the afternoon's first order collided with the morning's #1 and
     * `charge()` threw: no ticket, no sale, a customer waiting. The index is the right place for
     * the rule; what was wrong is what it was counting.
     *
     * Everything already recorded belongs to the first round, which is what the default says. That
     * is also why the index can be rebuilt without checking for duplicates: one value cannot
     * collide with itself any more than `(day, queueNo)` already did.
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE orders ADD COLUMN session INTEGER NOT NULL DEFAULT " +
                    QueueNumbering.FIRST_SESSION
            )
            // The names are Room's own, and it validates them on open: `index_<table>_<columns>`.
            db.execSQL("DROP INDEX IF EXISTS index_orders_day_queueNo")
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_orders_day_session_queueNo " +
                    "ON orders (day, session, queueNo)"
            )
        }
    }

    val ALL = arrayOf(MIGRATION_1_2)
}
