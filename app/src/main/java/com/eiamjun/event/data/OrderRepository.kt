package com.eiamjun.event.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** How a line's chosen options read to a person: "เส้นหมี่ · เผ็ดน้อย · เพิ่มแคปป๊อป". */
object OptionJoin {
    const val SEPARATOR = " · "
}

/** A line sitting on the pad, before any money has been taken. */
data class CartLine(
    val itemId: Long,
    val itemName: String,
    val optionsSummary: String,
    val note: String,
    val qty: Int,
    val unitPrice: Int
) {
    val total: Int get() = unitPrice * qty

    /**
     * What makes two taps the same line. The note is part of it, so a bowl with ไม่ใส่ถั่ว never
     * merges into a plain one — they are different food, and the kitchen has to see both.
     */
    val mergeKey: Triple<Long, String, String> get() = Triple(itemId, optionsSummary, note)
}

@Singleton
class OrderRepository @Inject constructor(
    private val db: AppDatabase,
    private val queueCounter: QueueCounter,
    private val settings: AppSettings
) {
    private val orders = db.orderDao()
    private val outbox = db.outboxDao()

    fun observeMenu(): Flow<List<MenuItemWithOptions>> = db.menuDao().observeActive()
    fun observeOpenQueue(day: String = EventDay.of()): Flow<List<OrderWithLines>> =
        orders.observeOpen(day)
    fun observeDay(day: String = EventDay.of()): Flow<List<OrderWithLines>> = orders.observeDay(day)
    fun observeTakings(day: String = EventDay.of()): Flow<Int> = orders.observeTakings(day)
    fun observeOrderCount(day: String = EventDay.of()): Flow<Int> = orders.observeOrderCount(day)
    fun observeOutboxDepth(): Flow<Int> = outbox.observeDepth()

    /** What the next ticket will say, for the pad to show before anything is charged. */
    fun peekQueueNo(): Int = queueCounter.peek().number

    /** The round the numbering is on, for ตั้งค่า to show that a reset took effect. */
    fun peekQueueSession(): Int = queueCounter.peek().session

    /**
     * Takes the money, takes a queue number, and hands back the order to print.
     *
     * **Recorded first, printed second**, in one transaction — the same ordering the shop's till
     * settled on. A printer out of paper must not be able to erase a sale that has already
     * happened; the caller prints afterwards and, if that fails, shows the ticket on screen and
     * reprints from the queue list. The reverse order loses the order entirely.
     *
     * The queue number is taken **inside** the transaction so two taps in the same instant cannot
     * be handed the same one, and the outbox row is written here rather than by the pusher, so an
     * order that is recorded is always also queued to the kitchen.
     */
    suspend fun charge(lines: List<CartLine>, payment: String): OrderWithLines {
        require(lines.isNotEmpty()) { "ไม่มีรายการ" }
        return db.withTransaction {
            val now = System.currentTimeMillis()
            val ticket = queueCounter.next(now)
            val order = OrderEntity(
                queueNo = ticket.number,
                session = ticket.session,
                day = EventDay.of(now),
                total = lines.sumOf { it.total },
                payment = payment,
                createdAt = now
            )
            val orderId = orders.insertOrder(order)
            val rows = lines.map {
                OrderLineEntity(
                    orderId = orderId,
                    itemId = it.itemId,
                    itemName = it.itemName,
                    optionsSummary = it.optionsSummary,
                    note = it.note,
                    qty = it.qty,
                    unitPrice = it.unitPrice
                )
            }
            orders.insertLines(rows)
            val saved = order.copy(id = orderId)
            outbox.add(OutboxEntity(kind = OutboxKind.ORDER, payload = orderPayload(saved, rows)))
            OrderWithLines(saved, rows)
        }
    }

    /**
     * The customer collected it.
     *
     * Local first and queued second, like everything else: the staff handing over a bowl cannot
     * wait for the wifi, and the kitchen board dropping the card a minute late costs nothing.
     */
    suspend fun markPicked(order: OrderEntity) {
        db.withTransaction {
            orders.markPicked(order.id)
            outbox.add(
                OutboxEntity(
                    kind = OutboxKind.PICKED,
                    payload = JSONObject()
                        .put("queueNo", order.queueNo)
                        .put("session", order.session)
                        .put("day", order.day)
                        .put("source", settings.deviceName)
                        .put("at", System.currentTimeMillis())
                        .toString()
                )
            )
        }
    }

    suspend fun cancel(order: OrderEntity) {
        db.withTransaction {
            orders.markCancelled(order.id)
            outbox.add(
                OutboxEntity(
                    kind = OutboxKind.CANCELLED,
                    payload = JSONObject()
                        .put("queueNo", order.queueNo)
                        .put("session", order.session)
                        .put("day", order.day)
                        .put("source", settings.deviceName)
                        .toString()
                )
            )
        }
    }

    /**
     * Takes the kitchen's ready list as **the whole truth** for this day's open orders.
     *
     * Not "mark these ready" but "these and only these are ready", which is what makes the board's
     * ยังไม่พร้อม undo reach the till: a number taken off the list goes back to กำลังทำ here too.
     * It is safe to be this absolute precisely because the kitchen owns this one flag and nothing
     * else — the terminal owns an order's contents, its number and whether it has been collected,
     * so there is no field both sides write and nothing to reconcile.
     *
     * A collected order is not in [OrderDao.openIdentities] and cannot be dragged back.
     *
     * @return how many orders actually changed state.
     */
    suspend fun applyReadySet(day: String, ready: Collection<QueueKey>): Int {
        var changed = 0
        orders.openIdentities(day).forEach { open ->
            // Matched on the round as well as the number, or a reset would make the morning's #3
            // and the afternoon's #3 the same card: marking one ready would call both.
            val isReady = ready.any { it.matches(open) }
            // Both calls are guarded by the status they come from, so this writes nothing when the
            // two sides already agree — which, poll after poll, is almost always.
            if (isReady) orders.markReady(open.id) else orders.markWaiting(open.id)
            changed++
        }
        return changed
    }

    /**
     * Marked ready here rather than by the board — the override for an unreachable kitchen.
     *
     * Written locally **and** queued, because the kitchen's list is the whole truth for this flag
     * (see [applyReadySet]): without the queued message the next successful poll would undo it.
     * The flush runs before the poll, so the server learns of it first and the two then agree.
     */
    suspend fun markReadyLocally(order: OrderEntity) {
        db.withTransaction {
            orders.markReady(order.id)
            outbox.add(
                OutboxEntity(
                    kind = OutboxKind.READY,
                    payload = JSONObject()
                        .put("queueNo", order.queueNo)
                        .put("session", order.session)
                        .put("day", order.day)
                        .put("source", settings.deviceName)
                        .toString()
                )
            )
        }
    }

    suspend fun find(id: Long): OrderWithLines? = orders.find(id)

    suspend fun markPushed(id: Long) = orders.markPushed(id)

    /**
     * The JSON the kitchen board is sent, built now rather than at send time.
     *
     * An order may sit in the outbox for an hour. Rebuilding this when the wifi returns would read
     * today's menu for an order taken before a price was edited, which is the shape of bug that
     * once sent the shop's till fifty bills under a device name that had since changed.
     */
    private fun orderPayload(order: OrderEntity, lines: List<OrderLineEntity>): String =
        JSONObject()
            .put("queueNo", order.queueNo)
            // The round the number was issued in. Part of the identity the server replaces on, so
            // a retried push lands on its own row instead of overwriting the morning's #1.
            .put("session", order.session)
            .put("day", order.day)
            // The name this terminal had when the order was rung up, written into the payload now
            // and sent whole later. Rebuilding it at send time would rename orders still waiting
            // in the outbox — which is how the shop's till once sent fifty bills under a name that
            // had already changed.
            .put("source", settings.deviceName)
            .put("total", order.total)
            .put("payment", order.payment)
            .put("orderedAt", order.createdAt)
            .put("localId", order.id)
            .put(
                "lines",
                JSONArray().apply {
                    lines.forEach {
                        put(
                            JSONObject()
                                .put("name", it.itemName)
                                .put("options", it.optionsSummary)
                                .put("note", it.note)
                                .put("qty", it.qty)
                                .put("unitPrice", it.unitPrice)
                        )
                    }
                }
            )
            .toString()
}
