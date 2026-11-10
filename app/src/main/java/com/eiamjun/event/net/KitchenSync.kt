package com.eiamjun.event.net

import com.eiamjun.event.data.AppDatabase
import com.eiamjun.event.data.AppSettings
import com.eiamjun.event.data.EventDay
import com.eiamjun.event.data.EventLog
import com.eiamjun.event.data.OrderRepository
import com.eiamjun.event.data.OutboxKind
import com.eiamjun.event.data.QueueKey
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the kitchen board in step with this terminal, in both directions.
 *
 * The two directions are deliberately asymmetric, and that asymmetry is what makes the whole thing
 * simple enough to trust during a service:
 *
 * - **Up:** the terminal owns the order — its contents, its queue number, whether it has been
 *   collected. Those go out through the outbox, so a dropped connection delays them and never
 *   loses them.
 * - **Down:** the kitchen owns exactly one transition, พร้อมส่ง. Nothing else comes down.
 *
 * Because no field is written by both sides, there is nothing to reconcile. A conflict would need
 * two writers, and there are none.
 */
@Singleton
class KitchenSync @Inject constructor(
    private val client: ServerClient,
    private val db: AppDatabase,
    private val orders: OrderRepository,
    private val settings: AppSettings,
    private val log: EventLog
) {
    private val flushing = Mutex()

    /**
     * Drains the outbox, oldest first, stopping at the first failure.
     *
     * Stopping rather than skipping is the point: an order and its later "collected" message have
     * to arrive in that order, or the board would be told to drop a card it has never been given.
     * A mutex because this is called from app start, after every charge and from the poll, and two
     * drains at once would send the same row twice.
     */
    suspend fun flush(): Int {
        if (!client.configured) return 0
        return flushing.withLock {
            var sent = 0
            val outbox = db.outboxDao()
            for (row in outbox.oldest()) {
                val path = when (row.kind) {
                    OutboxKind.ORDER -> "/api/orders"
                    OutboxKind.READY -> "/api/orders/ready"
                    OutboxKind.PICKED -> "/api/orders/picked"
                    OutboxKind.CANCELLED -> "/api/orders/cancelled"
                    else -> null
                }
                if (path == null) {
                    // Nothing can ever send it; leaving it would block every row behind it.
                    outbox.remove(row.id)
                    log.error("ทิ้งคิวส่งที่อ่านไม่ออก (${row.kind})")
                    continue
                }
                val result = client.post(path, row.payload)
                if (result.isSuccess) {
                    outbox.remove(row.id)
                    if (row.kind == OutboxKind.ORDER) {
                        runCatching { JSONObject(row.payload).optLong("localId") }
                            .getOrNull()
                            ?.takeIf { it > 0 }
                            ?.let { orders.markPushed(it) }
                    }
                    sent++
                } else {
                    val error = result.exceptionOrNull()
                    outbox.recordFailure(row.id, error?.message.orEmpty())
                    // Only worth a log line the first few times. A stall during a service with no
                    // wifi would otherwise fill the only screen the staff can read.
                    if (row.attempts < 3) log.error("ส่งครัวไม่สำเร็จ: ${error?.message}")
                    break
                }
            }
            if (sent > 0) log.info("ส่งขึ้นเซิร์ฟเวอร์แล้ว $sent รายการ")
            sent
        }
    }

    /**
     * Asks which queue numbers the kitchen has marked พร้อมส่ง, and marks them locally.
     *
     * Failing is **silent**. The poll runs every few seconds while the queue screen is open, and
     * an event with no wifi would otherwise write a red line into the log a dozen times a minute
     * and bury the one that matters. What the staff see instead is the queue screen saying the
     * board cannot be reached.
     */
    suspend fun pollReady(day: String = EventDay.of()): Result<Int> {
        if (!client.configured) return Result.failure(ServerClient.NotConfigured())
        val source = settings.deviceName
        return client.get("/api/orders/ready?day=$day&source=$source").mapCatching { payload ->
            // An empty array is a real answer — "nothing is ready" — and is **not** the same as no
            // answer at all. Collapsing the two would make the board's undo impossible: a list
            // that has just lost its only entry would be ignored. A failure throws instead, and
            // the caller leaves the till's picture alone.
            val array = payload.optJSONArray("ready")
                ?: throw ServerClient.ServerError(200, "เซิร์ฟเวอร์ตอบไม่ครบ")
            val ready = (0 until array.length()).map { i ->
                // `{session, queueNo}` is what this server sends. A bare number is what one that
                // predates the column sends, and it can only mean the first round — the shim is
                // for the minutes between deploying the server and installing the terminal, and a
                // null round matches by number alone rather than guessing.
                val entry = array.optJSONObject(i)
                if (entry != null) {
                    QueueKey(
                        session = entry.optInt("session").takeIf { it > 0 },
                        queueNo = entry.optInt("queueNo")
                    )
                } else {
                    QueueKey(session = null, queueNo = array.getInt(i))
                }
            }
            orders.applyReadySet(day, ready)
            ready.size
        }
    }

    /** ตั้งค่า → ทดสอบเชื่อมต่อ. The one place a failure is worth saying out loud. */
    suspend fun checkConnection(): Result<String> =
        client.get("/api/health").map { it.optString("name").ifBlank { "เชื่อมต่อได้" } }
}
