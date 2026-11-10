package com.eiamjun.event.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The event's menu. Two dishes, and the second has nothing to ask.
 *
 * **One name per thing, not two.** The shop's till carries a customer name and a kitchen shorthand
 * on every item and option, because its cook reads a 15-column paper slip at arm's length. This
 * kitchen reads an iPad, where there is room for the words the customer said — so a second set of
 * names would be a second thing to keep in step for no gain. If the kitchen ever asks for shorter
 * wording, that is a `kitchenName` column and a migration, not a workaround.
 */
@Entity(tableName = "menu_items")
data class MenuItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Whole Baht. Event food, no satang — the same call as the shop's till. */
    val price: Int,
    val sortOrder: Int = 0,
    /** Off the pad without being deleted — "ของหมด" during a service. */
    val active: Boolean = true
)

/**
 * One question the order sheet asks about a dish.
 *
 * [multiSelect] is the column that makes เส้น and หมายเหตุ different things. เส้น is one-of — a
 * bowl is หมี่ or เล็ก, never both — while หมายเหตุ is not, and "ไม่ใส่ถั่ว" plus "ไม่เผ็ด" is an
 * ordinary pair. A one-of group with a single option can never be switched off, so an optional
 * extra like แคปป๊อป has to be `multiSelect = true`.
 */
@Entity(
    tableName = "option_groups",
    foreignKeys = [
        ForeignKey(
            entity = MenuItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["itemId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("itemId")]
)
data class OptionGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itemId: Long,
    val name: String,
    val multiSelect: Boolean = false,
    val sortOrder: Int = 0
)

/** @param priceDelta may be negative — that is how a smaller portion takes money off. */
@Entity(
    tableName = "options",
    foreignKeys = [
        ForeignKey(
            entity = OptionGroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("groupId")]
)
data class OptionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val groupId: Long,
    val name: String,
    val priceDelta: Int = 0,
    val sortOrder: Int = 0,
    /** Where a one-of group opens. The first by [sortOrder] when nothing is marked. */
    val isDefault: Boolean = false
)

/**
 * One customer's order, already paid for.
 *
 * There is no unpaid state: money is taken at the counter before the ticket prints, so an order
 * row exists only because somebody has paid. That is why [status] starts at [OrderStatus.WAITING]
 * and never at "open" — this app has no table to leave an order sitting on.
 */
@Entity(
    tableName = "orders",
    indices = [Index(value = ["day", "session", "queueNo"], unique = true)]
)
data class OrderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** What is printed on the customer's ticket. Restarts at 1 each event day — see QueueCounter. */
    val queueNo: Int,
    val day: String,
    /**
     * Which numbering round within the day this ticket belongs to, from 1.
     *
     * Part of the order's identity, not a label: เริ่มนับคิวใหม่ starts the numbers again, so one
     * day can hold two #1s and the number alone cannot tell them apart. Without this column the
     * unique index rejected the afternoon's first order outright — see [QueueNumbering].
     */
    val session: Int = QueueNumbering.FIRST_SESSION,
    val total: Int,
    val status: String = OrderStatus.WAITING,
    val payment: String = Payment.QR,
    val createdAt: Long = System.currentTimeMillis(),
    /** When the kitchen said it was ready. Null until it does. */
    val readyAt: Long? = null,
    val pickedAt: Long? = null,
    /** Set once this order has reached the server. Null means it is still in the outbox. */
    val pushedAt: Long? = null
)

object OrderStatus {
    /** Paid, printed, cooking. */
    const val WAITING = "waiting"
    /** The kitchen tapped พร้อมส่ง. The number is being called. */
    const val READY = "ready"
    /** Handed over. Off the boards on both sides. */
    const val PICKED = "picked"
    const val CANCELLED = "cancelled"
}

object Payment {
    const val QR = "qr"
    const val CASH = "cash"
}

/**
 * A line on an order, with its options already **flattened to text**.
 *
 * The same call the shop's till made (decision 8 there): the summary is what gets printed and what
 * the kitchen reads, and a relational answer to "how many มาม่า today" is not worth a join table on
 * an event that runs for a weekend. The names and the price are **copied here at order time**, so
 * editing the menu afterwards cannot rewrite what a customer was charged or what the kitchen was
 * told.
 */
@Entity(
    tableName = "order_lines",
    foreignKeys = [
        ForeignKey(
            entity = OrderEntity::class,
            parentColumns = ["id"],
            childColumns = ["orderId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("orderId")]
)
data class OrderLineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val orderId: Long,
    /** 0 means "not from the menu" — there is no foreign key, deliberately, for the same reason. */
    val itemId: Long = 0,
    val itemName: String,
    /** "เส้นหมี่ · เผ็ดน้อย · เพิ่มแคปป๊อป", or blank. */
    val optionsSummary: String = "",
    val note: String = "",
    val qty: Int = 1,
    val unitPrice: Int
)

/**
 * The outbox.
 *
 * The event's wifi is expected to drop — that is the normal case at an outdoor stall, not the
 * exception — and the till has to keep taking money and printing tickets through it. So every
 * message bound for the kitchen board is written here first and sent afterwards, in order, and a
 * failure leaves the row for the next attempt.
 *
 * [payload] is the finished JSON, built at the moment the thing happened. Rebuilding it at send
 * time would read today's menu and today's device name for an order taken an hour ago, which is
 * how the shop's till once sent fifty bills under a name that had since changed.
 */
@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val payload: String,
    val createdAt: Long = System.currentTimeMillis(),
    val attempts: Int = 0,
    val lastError: String = ""
)

object OutboxKind {
    /** A new paid order, with its lines. */
    const val ORDER = "order"
    /**
     * Marked ready on the terminal rather than by the board — the override for a kitchen whose
     * iPad cannot be reached.
     *
     * It goes through the outbox like everything else, which is what makes the override survive
     * the poll: the kitchen's list is the whole truth for an order's ready flag, so a local-only
     * ready would be undone by the next successful poll. Queued here, it reaches the server first
     * — the flush runs before the poll — and the list then agrees with the till.
     */
    const val READY = "ready"

    /** The customer collected it. Tells the kitchen board to drop the card. */
    const val PICKED = "picked"
    const val CANCELLED = "cancelled"
}
