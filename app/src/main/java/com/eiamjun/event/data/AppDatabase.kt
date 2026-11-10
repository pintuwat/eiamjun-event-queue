package com.eiamjun.event.data

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Version 2, and there is no `fallbackToDestructiveMigration()`.
 *
 * The shop's till learned this the expensive way: once a database holds money that has been taken,
 * a missing migration has to fail loudly at startup rather than quietly delete the day. An event's
 * orders are smaller but they are still the only record of what has been paid for and not yet
 * handed over — losing them mid-service means every customer holding a ticket is unserveable.
 *
 * **Every schema change from here needs a Migration.** They live in [Migrations].
 */
@Database(
    entities = [
        MenuItemEntity::class,
        OptionGroupEntity::class,
        OptionEntity::class,
        OrderEntity::class,
        OrderLineEntity::class,
        OutboxEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun menuDao(): MenuDao
    abstract fun orderDao(): OrderDao
    abstract fun outboxDao(): OutboxDao

    companion object {
        const val NAME = "eiamjun-event.db"
    }
}
