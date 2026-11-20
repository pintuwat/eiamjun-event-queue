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

    /**
     * Splits the bowl by its meat: ก๋วยเตี๋ยวคลุก becomes **หมู**, and **เนื้อ** is cloned from it.
     *
     * Two menu rows rather than one dish with a เนื้อ/หมู question behind it. The pad is the whole
     * ordering interface here and a key is one tap; the meat is also the first thing the kitchen
     * reads off the slip, and a dish name in the large type says it without the cook going down to
     * the options line. (The shop's own till merges dishes that cost the same into one key — the
     * opposite call, and right there, because a till prints no kitchen slip and nobody is handed a
     * bowl labelled only ก๋วยเตี๋ยว.)
     *
     * **The clone is taken from the rows, not from [SeedData].** The terminal is the system of
     * record for the menu: by the time this runs, the stall had emptied a group by hand, replaced
     * both เพิ่มเติม options with one 39-Baht bucket and dropped a note. Cloning the code's idea of
     * the dish would have handed เนื้อ a menu หมู has not had for two events.
     *
     * Groups are matched to their copies **by name**, which is what carries the options across.
     * That holds because a dish's groups are the questions it asks and no dish asks two questions
     * with one name; if that ever stops being true, this join is where it breaks.
     *
     * Re-running changes nothing: the dish is guarded by `NOT EXISTS` on its name, and the groups
     * and options by the clone having none yet.
     */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val pork = "ก๋วยเตี๋ยวคลุกหมู"
            val beef = "ก๋วยเตี๋ยวคลุกเนื้อ"

            db.execSQL("UPDATE menu_items SET name = '$pork' WHERE name = 'ก๋วยเตี๋ยวคลุก'")

            // Room next to its sibling on the pad. The two bowls read as a pair and the snacks
            // move down, rather than เนื้อ landing after them at the end of the grid.
            db.execSQL(
                "UPDATE menu_items SET sortOrder = sortOrder + 1 " +
                    "WHERE sortOrder > (SELECT sortOrder FROM menu_items WHERE name = '$pork') " +
                    // Guarded like the insert below it, or a second run would shove the snacks
                    // down the pad again and leave a hole where เนื้อ already sits.
                    "AND NOT EXISTS (SELECT 1 FROM menu_items WHERE name = '$beef')"
            )
            db.execSQL(
                "INSERT INTO menu_items (name, price, sortOrder, active) " +
                    "SELECT '$beef', price, sortOrder + 1, active FROM menu_items " +
                    "WHERE name = '$pork' " +
                    "AND NOT EXISTS (SELECT 1 FROM menu_items WHERE name = '$beef')"
            )

            db.execSQL(
                "INSERT INTO option_groups (itemId, name, multiSelect, sortOrder) " +
                    "SELECT (SELECT id FROM menu_items WHERE name = '$beef'), " +
                    "g.name, g.multiSelect, g.sortOrder FROM option_groups g " +
                    "WHERE g.itemId = (SELECT id FROM menu_items WHERE name = '$pork') " +
                    "AND NOT EXISTS (SELECT 1 FROM option_groups x " +
                    "WHERE x.itemId = (SELECT id FROM menu_items WHERE name = '$beef'))"
            )
            db.execSQL(
                "INSERT INTO options (groupId, name, priceDelta, sortOrder, isDefault) " +
                    "SELECT ng.id, o.name, o.priceDelta, o.sortOrder, o.isDefault " +
                    "FROM options o " +
                    "JOIN option_groups og ON og.id = o.groupId " +
                    "AND og.itemId = (SELECT id FROM menu_items WHERE name = '$pork') " +
                    "JOIN option_groups ng ON ng.name = og.name " +
                    "AND ng.itemId = (SELECT id FROM menu_items WHERE name = '$beef') " +
                    "WHERE NOT EXISTS (SELECT 1 FROM options y JOIN option_groups z " +
                    "ON z.id = y.groupId " +
                    "WHERE z.itemId = (SELECT id FROM menu_items WHERE name = '$beef'))"
            )
        }
    }

    /**
     * Gives a dish a bundle price, and puts the stall's น้ำซอส on the pad with one.
     *
     * The columns are additive and default to 0, which is "no deal" — every dish already on the
     * terminal keeps the price it has. See [MenuItemEntity.priceFor] for the arithmetic and
     * [Promo] for why the saving reaches the books as a line of its own.
     *
     * The dish arrives here rather than in [SeedData] because the seed only runs on a database
     * that does not exist yet, and both terminals have been selling for weeks. แก้ไขเมนู cannot
     * create a dish either — it changes names, prices and what is on sale — so a new row on the
     * pad is a migration, exactly as แคปป๊อป ใหญ่ and ก๋วยเตี๋ยวคลุกเนื้อ were.
     *
     * **Every column is named, not only the ones this build cares about.** The shop's own till was
     * taken down twice by an INSERT that left a column with no default to a terminal whose table
     * had been created at a different version — two terminals of the same age are not the same
     * schema. `NOT EXISTS` on the name so a re-run cannot double the key, and last on the pad so
     * nothing the staff already reach for moves under their finger.
     *
     * Nothing to ask, so it gets no option groups: the key goes straight into the basket, the way
     * แคปป๊อป does.
     */
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE menu_items ADD COLUMN promoQty INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE menu_items ADD COLUMN promoPrice INTEGER NOT NULL DEFAULT 0")
            db.execSQL(
                "INSERT INTO menu_items " +
                    "(name, price, sortOrder, active, promoQty, promoPrice) " +
                    "SELECT 'น้ำซอสก๋วยเตี๋ยว', 39, " +
                    "COALESCE((SELECT MAX(sortOrder) FROM menu_items), 0) + 1, 1, 3, 100 " +
                    "WHERE NOT EXISTS " +
                    "(SELECT 1 FROM menu_items WHERE name = 'น้ำซอสก๋วยเตี๋ยว')"
            )
        }
    }

    val ALL = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
}
