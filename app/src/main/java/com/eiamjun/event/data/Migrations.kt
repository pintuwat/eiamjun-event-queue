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

    /**
     * Takes ความเผ็ด off the menu, and splits แคปป๊อป into เล็ก and ใหญ่.
     *
     * Both are things แก้ไขเมนู on the device cannot do — it changes names, prices and what is on
     * sale, and adds or removes options *within* a group, but it creates and deletes neither a
     * group nor a dish. So the stall's two menu changes have to arrive as a migration, and the
     * rows have to be found by the names they were seeded under.
     *
     * **The names are written out here rather than read from [SeedData].** A migration is a record
     * of what happened to a database on one particular day; one that reads today's constants is a
     * record of nothing, and the shop's till has a migration that quietly started renaming a table
     * again years later for exactly that reason.
     *
     * The options are deleted before their group rather than left to `ON DELETE CASCADE`, which is
     * off in parts of Room's migration path and would leave four orphan rows pointing at a group
     * that no longer exists.
     *
     * ⚠️ **Orders already taken are not touched.** A line copied its name, its options and its
     * price at the moment it was ordered (see [OrderLineEntity]), so a ticket printed yesterday
     * still reads "เผ็ดน้อย" and still says "แคปป๊อป" — which is what was sold, and what the
     * customer was charged for.
     */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "DELETE FROM options WHERE groupId IN " +
                    "(SELECT id FROM option_groups WHERE name = 'ความเผ็ด')"
            )
            db.execSQL("DELETE FROM option_groups WHERE name = 'ความเผ็ด'")

            // The existing row is renamed rather than replaced, so its id survives and the small
            // bag stays the key the staff already know.
            db.execSQL("UPDATE menu_items SET name = 'แคปป๊อป เล็ก' WHERE name = 'แคปป๊อป'")
            // The new row does not depend on that rename having matched anything — the name on
            // the device may have been edited in แก้ไขเมนู since it was seeded, and a stall left
            // with no ใหญ่ key because of a spelling is the failure worth designing out. Last on
            // the pad, 20 Baht, and `NOT EXISTS` so re-running it cannot double the key.
            //
            // Every column named, not only the ones this build needs: the shop's till was taken
            // down twice by an INSERT that left a column with no default to a terminal whose table
            // had been created at a different version.
            db.execSQL(
                "INSERT INTO menu_items (name, price, sortOrder, active) " +
                    "SELECT 'แคปป๊อป ใหญ่', 20, " +
                    "COALESCE((SELECT MAX(sortOrder) FROM menu_items), 0) + 1, 1 " +
                    "WHERE NOT EXISTS (SELECT 1 FROM menu_items WHERE name = 'แคปป๊อป ใหญ่')"
            )
        }
    }

    val ALL = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
}
