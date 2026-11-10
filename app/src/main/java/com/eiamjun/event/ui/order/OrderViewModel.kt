package com.eiamjun.event.ui.order

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eiamjun.event.data.AppSettings
import com.eiamjun.event.data.CartLine
import com.eiamjun.event.data.EventLog
import com.eiamjun.event.data.MenuItemWithOptions
import com.eiamjun.event.data.OrderRepository
import com.eiamjun.event.data.OrderWithLines
import com.eiamjun.event.net.KitchenSync
import com.eiamjun.event.printer.EventTicketFormatter
import com.eiamjun.event.printer.PrinterRouter
import com.eiamjun.event.printer.ScreenQr
import com.eiamjun.event.printer.Ticket
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class OrderUiState(
    val cart: List<CartLine> = emptyList(),
    val nextQueueNo: Int = 1,
    /** Non-null while the payment dialog is up. */
    val paymentQr: ScreenQr? = null,
    val askingPayment: Boolean = false,
    val busy: Boolean = false,
    /** Set once a ticket has printed — the dialog that reads the number back. */
    val printed: PrintedTicket? = null,
    val preview: String? = null,
    val error: String? = null
) {
    val total: Int get() = cart.sumOf { it.total }
    val itemCount: Int get() = cart.sumOf { it.qty }
}

data class PrintedTicket(
    val order: OrderWithLines,
    val printError: String? = null
)

@HiltViewModel
class OrderViewModel @Inject constructor(
    private val repo: OrderRepository,
    private val settings: AppSettings,
    private val printers: PrinterRouter,
    private val sync: KitchenSync,
    private val log: EventLog
) : ViewModel() {

    private val _state = MutableStateFlow(OrderUiState(nextQueueNo = repo.peekQueueNo()))
    val state: StateFlow<OrderUiState> = _state

    val menu: StateFlow<List<MenuItemWithOptions>> = repo.observeMenu()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val openCount: StateFlow<Int> = repo.observeOpenQueue()
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val outboxDepth: StateFlow<Int> = repo.observeOutboxDepth()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /**
     * Adds a line, merging it into an identical one already on the pad.
     *
     * Identical means the same dish, the same options *and* the same note — a bowl with ไม่ใส่ถั่ว
     * never merges into a plain one, because they are different food and the kitchen has to be
     * told about both.
     */
    fun add(line: CartLine) {
        _state.value = _state.value.let { s ->
            val index = s.cart.indexOfFirst { it.mergeKey == line.mergeKey }
            val cart = if (index >= 0) {
                s.cart.toMutableList().also { it[index] = it[index].copy(qty = it[index].qty + line.qty) }
            } else {
                s.cart + line
            }
            s.copy(cart = cart)
        }
    }

    fun increment(index: Int) = changeQty(index, +1)

    /** −1 on a line of one removes it, which is what a stepper on a basket has to mean. */
    fun decrement(index: Int) = changeQty(index, -1)

    private fun changeQty(index: Int, delta: Int) {
        val cart = _state.value.cart.toMutableList()
        val line = cart.getOrNull(index) ?: return
        if (line.qty + delta <= 0) cart.removeAt(index) else cart[index] = line.copy(qty = line.qty + delta)
        _state.value = _state.value.copy(cart = cart)
    }

    fun clearCart() {
        _state.value = _state.value.copy(cart = emptyList())
    }

    /** Opens the payment dialog, with the code for this exact total already built. */
    fun askPayment() {
        val total = _state.value.total
        if (total <= 0) return
        _state.value = _state.value.copy(
            askingPayment = true,
            paymentQr = EventTicketFormatter.paymentQr(
                merchantQr = settings.merchantQr,
                promptPayId = settings.promptPayId,
                total = total
            )
        )
    }

    fun dismissPayment() {
        _state.value = _state.value.copy(askingPayment = false, paymentQr = null)
    }

    /**
     * Records the order, then prints the ticket, then pushes it up.
     *
     * **That order matters and is the same one the shop's till settled on.** A printer out of paper
     * must not be able to erase money that has already been taken, so the row is written first and
     * a print failure is reported as "it is recorded, the paper did not come out" — with the number
     * on screen to read back and a reprint one tap away.
     *
     * The push is last and its failure is not reported here at all: the outbox has the order, and a
     * kitchen board a minute behind costs nothing next to a queue that has stopped moving.
     */
    fun confirmPaid(payment: String) {
        val lines = _state.value.cart
        if (lines.isEmpty() || _state.value.busy) return
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch {
            val recorded = runCatching { repo.charge(lines, payment) }
            val order = recorded.getOrElse { failure ->
                log.error("บันทึกออเดอร์ไม่สำเร็จ: ${failure.message}")
                _state.value = _state.value.copy(busy = false, error = failure.message)
                return@launch
            }
            val printError = printTicket(order)
            _state.value = _state.value.copy(
                cart = emptyList(),
                askingPayment = false,
                paymentQr = null,
                busy = false,
                nextQueueNo = repo.peekQueueNo(),
                printed = PrintedTicket(order, printError)
            )
            // Last, and quietly. The outbox already holds it.
            runCatching { sync.flush() }
        }
    }

    /** @return the error to show, or null when it printed. */
    private suspend fun printTicket(order: OrderWithLines): String? {
        val ticket = EventTicketFormatter.queueTicket(
            shopName = settings.shopName,
            queueNo = order.order.queueNo,
            itemCount = order.lines.sumOf { it.qty },
            at = order.order.createdAt,
            dotsOffset = settings.queueNumberDots
        )
        return print(ticket)
    }

    fun reprintTicket(order: OrderWithLines) {
        viewModelScope.launch { printTicket(order) }
    }

    fun printReceipt(order: OrderWithLines) {
        viewModelScope.launch {
            val ticket = EventTicketFormatter.receipt(
                shopName = settings.shopName,
                shopSubtitle = settings.shopSubtitle,
                queueNo = order.order.queueNo,
                lines = order.lines,
                merchantQr = settings.merchantQr,
                promptPayId = settings.promptPayId,
                qrDotSize = settings.qrDotSize,
                paidInCash = order.order.payment == com.eiamjun.event.data.Payment.CASH
            )
            print(ticket)
        }
    }

    private suspend fun print(ticket: Ticket): String? {
        var preview: String? = null
        val printer = printers.create { preview = it }
        val result = printer.print(ticket)
        if (preview != null) _state.value = _state.value.copy(preview = preview)
        return result.exceptionOrNull()?.message?.also { log.error("พิมพ์ไม่สำเร็จ: $it") }
    }

    fun dismissPrinted() {
        _state.value = _state.value.copy(printed = null)
    }

    fun dismissPreview() {
        _state.value = _state.value.copy(preview = null)
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }
}
