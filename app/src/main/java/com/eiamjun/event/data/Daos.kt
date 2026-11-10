package com.eiamjun.event.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** A dish with everything the order sheet needs to ask about it, in one read. */
data class MenuItemWithOptions(
    @Embedded val item: MenuItemEntity,
    @Relation(entity = OptionGroupEntity::class, parentColumn = "id", entityColumn = "itemId")
    val groups: List<GroupWithOptions>
)

data class GroupWithOptions(
    @Embedded val group: OptionGroupEntity,
    @Relation(parentColumn = "id", entityColumn = "groupId")
    val options: List<OptionEntity>
)

@Dao
interface MenuDao {
    @Transaction
    @Query("SELECT * FROM menu_items ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<MenuItemWithOptions>>

    @Transaction
    @Query("SELECT * FROM menu_items WHERE active = 1 ORDER BY sortOrder, id")
    fun observeActive(): Flow<List<MenuItemWithOptions>>

    @Query("SELECT COUNT(*) FROM menu_items")
    suspend fun count(): Int

    @Insert
    suspend fun insertItem(item: MenuItemEntity): Long

    @Insert
    suspend fun insertGroup(group: OptionGroupEntity): Long

    @Insert
    suspend fun insertOption(option: OptionEntity): Long

    @Update
    suspend fun updateItem(item: MenuItemEntity)

    @Update
    suspend fun updateOption(option: OptionEntity)

    @Delete
    suspend fun deleteOption(option: OptionEntity)

    @Query("UPDATE menu_items SET active = :active WHERE id = :itemId")
    suspend fun setActive(itemId: Long, active: Boolean)
}

/** The pair that identifies an open order on both sides — see [OrderDao.openIdentities]. */
data class OrderIdentity(val id: Long, val queueNo: Int)

/** An order and its lines — what the kitchen push and the receipt are both built from. */
data class OrderWithLines(
    @Embedded val order: OrderEntity,
    @Relation(parentColumn = "id", entityColumn = "orderId")
    val lines: List<OrderLineEntity>
)

@Dao
interface OrderDao {
    @Insert
    suspend fun insertOrder(order: OrderEntity): Long

    @Insert
    suspend fun insertLines(lines: List<OrderLineEntity>)

    /**
     * The queue board: everything still owed to a customer, oldest first.
     *
     * Ordered by [OrderEntity.queueNo] rather than by time because the number is what the staff
     * and the customer are both holding — a list that disagrees with the numbers on the tickets
     * is a list nobody can use at a counter.
     */
    @Transaction
    @Query(
        "SELECT * FROM orders WHERE day = :day AND status IN ('waiting', 'ready') ORDER BY queueNo"
    )
    fun observeOpen(day: String): Flow<List<OrderWithLines>>

    @Transaction
    @Query("SELECT * FROM orders WHERE day = :day ORDER BY queueNo DESC")
    fun observeDay(day: String): Flow<List<OrderWithLines>>

    @Transaction
    @Query("SELECT * FROM orders WHERE id = :id")
    suspend fun find(id: Long): OrderWithLines?

    /**
     * Just enough of each open order to reconcile it against the kitchen's ready list: the local
     * id to write to, and the queue number the two sides agree on.
     */
    @Query("SELECT id, queueNo FROM orders WHERE day = :day AND status IN ('waiting', 'ready')")
    suspend fun openIdentities(day: String): List<OrderIdentity>

    /**
     * The two transitions the kitchen's list drives, each guarded by the status it comes **from**.
     *
     * The guard is in the `WHERE` clause rather than in a view model, the way the shop's till
     * learned to put them: a guard above the DAO is one careless call site away from being walked
     * around, and `status = 'picked'` here would mean a stale poll pulling a bag that has already
     * been handed over back onto the board. It also makes both calls idempotent, so the poll can
     * run every few seconds without writing anything it does not have to.
     */
    @Query("UPDATE orders SET status = 'ready', readyAt = :at WHERE id = :id AND status = 'waiting'")
    suspend fun markReady(id: Long, at: Long = System.currentTimeMillis())

    @Query("UPDATE orders SET status = 'waiting', readyAt = NULL WHERE id = :id AND status = 'ready'")
    suspend fun markWaiting(id: Long)

    @Query("UPDATE orders SET status = 'picked', pickedAt = :at WHERE id = :id")
    suspend fun markPicked(id: Long, at: Long = System.currentTimeMillis())

    @Query("UPDATE orders SET status = 'cancelled' WHERE id = :id")
    suspend fun markCancelled(id: Long)

    @Query("UPDATE orders SET pushedAt = :at WHERE id = :id")
    suspend fun markPushed(id: Long, at: Long = System.currentTimeMillis())

    /**
     * Today's takings, for the one figure the staff ask for at the end of an event.
     *
     * Cancelled orders are out; picked and waiting are both in, because the money was taken when
     * the ticket printed and a bowl still cooking has already been paid for.
     */
    @Query("SELECT COALESCE(SUM(total), 0) FROM orders WHERE day = :day AND status != 'cancelled'")
    fun observeTakings(day: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM orders WHERE day = :day AND status != 'cancelled'")
    fun observeOrderCount(day: String): Flow<Int>

}

@Dao
interface OutboxDao {
    @Insert
    suspend fun add(row: OutboxEntity): Long

    @Query("SELECT * FROM outbox ORDER BY id LIMIT :limit")
    suspend fun oldest(limit: Int = 50): List<OutboxEntity>

    @Query("SELECT COUNT(*) FROM outbox")
    fun observeDepth(): Flow<Int>

    @Query("DELETE FROM outbox WHERE id = :id")
    suspend fun remove(id: Long)

    @Query("UPDATE outbox SET attempts = attempts + 1, lastError = :error WHERE id = :id")
    suspend fun recordFailure(id: Long, error: String)
}
