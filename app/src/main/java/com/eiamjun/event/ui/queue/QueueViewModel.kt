package com.eiamjun.event.ui.queue

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eiamjun.event.data.AppSettings
import com.eiamjun.event.data.OrderRepository
import com.eiamjun.event.data.OrderStatus
import com.eiamjun.event.data.OrderWithLines
import com.eiamjun.event.data.Payment
import com.eiamjun.event.net.KitchenSync
import com.eiamjun.event.printer.EventTicketFormatter
import com.eiamjun.event.printer.PrinterRouter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class QueueUiState(
    /** Null until a poll has been attempted; false means the board could not be reached. */
    val serverReachable: Boolean? = null,
    val preview: String? = null
)

@HiltViewModel
class QueueViewModel @Inject constructor(
    private val repo: OrderRepository,
    private val settings: AppSettings,
    private val printers: PrinterRouter,
    private val sync: KitchenSync
) : ViewModel() {

    private val _state = MutableStateFlow(QueueUiState())
    val state: StateFlow<QueueUiState> = _state

    val orders: StateFlow<List<OrderWithLines>> = repo.observeOpenQueue()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val takings: StateFlow<Int> = repo.observeTakings()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val orderCount: StateFlow<Int> = repo.observeOrderCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /**
     * One round of "which numbers has the kitchen finished", plus a drain of the outbox.
     *
     * Called on a timer by the screen rather than by a service, and only while that screen is
     * open: this is a board somebody is watching, not a live feed, and a handheld asleep in an
     * apron pocket has nothing to poll for.
     *
     * A failure sets [QueueUiState.serverReachable] and writes nothing to the log — at an event
     * with patchy wifi this runs every few seconds, and a red line each time would bury the one
     * that matters.
     */
    fun poll() {
        viewModelScope.launch {
            // Send before reading, always. The kitchen's list is the whole truth for the ready
            // flag, so anything this terminal decided itself — a พร้อม tapped here while the board
            // was unreachable — has to be up there before the list is believed, or the poll would
            // simply undo it.
            runCatching { sync.flush() }
            val ready = sync.pollReady()
            _state.value = _state.value.copy(serverReachable = ready.isSuccess)
        }
    }

    fun markPicked(order: OrderWithLines) {
        viewModelScope.launch { repo.markPicked(order.order) }
    }

    fun cancel(order: OrderWithLines) {
        viewModelScope.launch { repo.cancel(order.order) }
    }

    fun printReceipt(order: OrderWithLines) {
        viewModelScope.launch {
            print(
                EventTicketFormatter.receipt(
                    shopName = settings.shopName,
                    shopSubtitle = settings.shopSubtitle,
                    queueNo = order.order.queueNo,
                    lines = order.lines,
                    merchantQr = settings.merchantQr,
                    promptPayId = settings.promptPayId,
                    qrDotSize = settings.qrDotSize,
                    paidInCash = order.order.payment == Payment.CASH
                )
            )
        }
    }

    /** For a ticket that jammed or was lost. Writes nothing and re-pushes nothing. */
    fun reprintTicket(order: OrderWithLines) {
        viewModelScope.launch {
            print(
                EventTicketFormatter.queueTicket(
                    shopName = settings.shopName,
                    queueNo = order.order.queueNo,
                    itemCount = order.lines.sumOf { it.qty },
                    at = order.order.createdAt,
                    dotsOffset = settings.queueNumberDots
                )
            )
        }
    }

    /**
     * Marks an order ready from the terminal, for when the kitchen cannot reach its board.
     *
     * Normally this transition is the kitchen's and only the kitchen's — that is what keeps the two
     * sides from ever having to be reconciled. This is the manual override for the evening the
     * iPad's wifi is down and somebody is shouting numbers across the stall instead, and it is
     * deliberately on the row rather than anywhere prominent.
     */
    fun markReadyHere(order: OrderWithLines) {
        viewModelScope.launch { repo.markReadyLocally(order.order) }
    }

    fun readyCount(orders: List<OrderWithLines>): Int =
        orders.count { it.order.status == OrderStatus.READY }

    private suspend fun print(ticket: com.eiamjun.event.printer.Ticket) {
        var preview: String? = null
        val printer = printers.create { preview = it }
        printer.print(ticket)
        if (preview != null) _state.value = _state.value.copy(preview = preview)
    }

    fun dismissPreview() {
        _state.value = _state.value.copy(preview = null)
    }
}
