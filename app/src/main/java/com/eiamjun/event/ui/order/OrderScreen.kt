package com.eiamjun.event.ui.order

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eiamjun.event.data.MenuItemWithOptions
import com.eiamjun.event.ui.components.MenuKeypad
import com.eiamjun.event.ui.components.OrderLineSheet
import com.eiamjun.event.ui.components.PadKey
import com.eiamjun.event.ui.components.PaymentSheet
import com.eiamjun.event.ui.components.PreviewDialog
import com.eiamjun.event.ui.components.TicketPrintedSheet
import com.eiamjun.event.ui.theme.Alert
import com.eiamjun.event.ui.theme.Gold
import com.eiamjun.event.ui.theme.InkText
import com.eiamjun.event.ui.theme.InkTextDim
import com.eiamjun.event.ui.theme.PriceOrange
import com.eiamjun.event.ui.theme.Wood

/**
 * The till, and the app's home screen.
 *
 * One screen takes the whole order: keys at the top, the basket at the foot, and คิดเงิน. There is
 * no table and no floor — an event has a counter and a queue, and the fastest thing this app can
 * do for that queue is never make the person at the counter navigate.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrderScreen(
    onOpenQueue: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: OrderViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val menu by viewModel.menu.collectAsStateWithLifecycle()
    val openCount by viewModel.openCount.collectAsStateWithLifecycle()
    val outboxDepth by viewModel.outboxDepth.collectAsStateWithLifecycle()

    // The queue counter lives outside this view model and ตั้งค่า can reset it, so the number is
    // re-read whenever this screen comes back to the front rather than once when it was built.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshQueueNo() }

    // The dish whose options are being asked about, or null. A dish with no groups never gets here.
    var asking by remember { mutableStateOf<MenuItemWithOptions?>(null) }
    var showWholeCart by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Wood,
                    titleContentColor = Gold,
                    actionIconContentColor = Gold
                ),
                title = {
                    Column {
                        Text("คิวถัดไป ${state.nextQueueNo}", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        // The one line of status worth the app bar's room. The outbox depth is on
                        // it because "nothing has reached the kitchen" is the failure that looks
                        // exactly like everything working.
                        Text(
                            buildString {
                                append("รออยู่ $openCount คิว")
                                if (outboxDepth > 0) append(" · ค้างส่ง $outboxDepth")
                            },
                            fontSize = 14.sp,
                            color = if (outboxDepth > 0) Alert else Gold
                        )
                    }
                },
                actions = {
                    TextButton(onClick = onOpenQueue) { Text("คิว", color = Gold, fontSize = 18.sp) }
                    TextButton(onClick = onOpenSettings) { Text("ตั้งค่า", color = Gold, fontSize = 18.sp) }
                }
            )
        },
        bottomBar = {
            CartBar(
                state = state,
                onOpenWholeCart = { showWholeCart = true },
                onIncrement = viewModel::increment,
                onDecrement = viewModel::decrement,
                onCharge = viewModel::askPayment
            )
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            MenuKeypad(
                keys = menu.map { dish ->
                    PadKey(
                        id = dish.item.id,
                        label = dish.item.name,
                        price = dish.item.price,
                        // How many of this dish are on the order. The question a badge answers is
                        // "did my tap land", so it counts every line of that dish whatever its
                        // options.
                        badge = state.cart.filter { it.itemId == dish.item.id }.sumOf { it.qty }
                    )
                },
                onKeyTap = { key ->
                    val dish = menu.firstOrNull { it.item.id == key.id } ?: return@MenuKeypad
                    // Nothing to ask means straight into the basket. Opening a dialog to confirm a
                    // dish with no choices is a tap spent on nothing, with a queue waiting.
                    if (dish.groups.isEmpty()) {
                        viewModel.add(
                            com.eiamjun.event.data.CartLine(
                                itemId = dish.item.id,
                                itemName = dish.item.name,
                                optionsSummary = "",
                                note = "",
                                qty = 1,
                                unitPrice = dish.item.price
                            )
                        )
                    } else {
                        asking = dish
                    }
                }
            )
        }
    }

    asking?.let { dish ->
        OrderLineSheet(
            dish = dish,
            onDismiss = { asking = null },
            onConfirm = { line ->
                viewModel.add(line)
                asking = null
            }
        )
    }

    if (showWholeCart) {
        WholeCartDialog(
            state = state,
            onIncrement = viewModel::increment,
            onDecrement = viewModel::decrement,
            onClear = {
                viewModel.clearCart()
                showWholeCart = false
            },
            onDismiss = { showWholeCart = false }
        )
    }

    if (state.askingPayment) {
        PaymentSheet(
            total = state.total,
            itemCount = state.itemCount,
            queueNo = state.nextQueueNo,
            qr = state.paymentQr,
            busy = state.busy,
            onPaid = viewModel::confirmPaid,
            onDismiss = viewModel::dismissPayment
        )
    }

    state.printed?.let { printed ->
        TicketPrintedSheet(
            queueNo = printed.order.order.queueNo,
            total = printed.order.order.total,
            printError = printed.printError,
            onReprintTicket = { viewModel.reprintTicket(printed.order) },
            onPrintReceipt = { viewModel.printReceipt(printed.order) },
            onDone = viewModel::dismissPrinted
        )
    }

    state.preview?.let { PreviewDialog(it, onDismiss = viewModel::dismissPreview) }
}

/**
 * The basket, pinned to the foot: two lines of it, then a button that opens the lot.
 *
 * Two rows because that is what fits without eating the pad it is being filled from, and the strip
 * is a glance rather than something to read — the moment anyone wants to read the order through is
 * the moment they notice a wrong count, and that is what ดูทั้งหมด is for.
 */
@Composable
private fun CartBar(
    state: OrderUiState,
    onOpenWholeCart: () -> Unit,
    onIncrement: (Int) -> Unit,
    onDecrement: (Int) -> Unit,
    onCharge: () -> Unit
) {
    Surface(color = Color.White, shadowElevation = 8.dp) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (state.cart.isEmpty()) {
                Text(
                    "กดเมนูเพื่อเริ่มออเดอร์",
                    color = InkTextDim,
                    modifier = Modifier.padding(vertical = 10.dp)
                )
            } else {
                state.cart.take(2).forEachIndexed { index, line ->
                    CartRow(
                        label = line.itemName + if (line.optionsSummary.isBlank()) "" else " ${line.optionsSummary}",
                        qty = line.qty,
                        total = line.total,
                        onIncrement = { onIncrement(index) },
                        onDecrement = { onDecrement(index) }
                    )
                }
                if (state.cart.size > 2) {
                    TextButton(onClick = onOpenWholeCart) {
                        Text("ดูทั้งหมด (${state.cart.size} รายการ)", color = PriceOrange)
                    }
                } else {
                    TextButton(onClick = onOpenWholeCart) { Text("ดูทั้งหมด", color = PriceOrange) }
                }
            }
            Button(
                onClick = onCharge,
                enabled = state.cart.isNotEmpty() && !state.busy,
                colors = ButtonDefaults.buttonColors(containerColor = PriceOrange),
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)
            ) {
                Text(
                    if (state.cart.isEmpty()) "คิดเงิน" else "คิดเงิน · ${state.total}.-",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
private fun CartRow(
    label: String,
    qty: Int,
    total: Int,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
    ) {
        // Two lines, not one. With a single dish on the menu the options are the whole content of
        // the line — "ก๋วยเตี๋ยวคลุก เส้น" cut at one line says nothing anybody needed to know,
        // and the question this strip answers is "did the tap I just made land as I meant it".
        Text(
            label,
            fontSize = 18.sp,
            lineHeight = 21.sp,
            color = InkText,
            maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        // − and + on the strip itself. Re-counting a line is the commonest correction there is,
        // and making it cost a dialog is what sends staff round the houses with a queue waiting.
        OutlinedButton(onClick = onDecrement, modifier = Modifier.heightIn(min = 44.dp)) {
            Text("−", fontSize = 20.sp)
        }
        Text(
            "$qty",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 10.dp)
        )
        OutlinedButton(onClick = onIncrement, modifier = Modifier.heightIn(min = 44.dp)) {
            Text("+", fontSize = 20.sp)
        }
        Text("$total.-", fontSize = 18.sp, modifier = Modifier.padding(start = 10.dp))
    }
}

/**
 * Every line, full screen and steppable.
 *
 * The rows here grow with their content while the strip's are cut to one line: the strip is a
 * glance, this is for reading an order back to a customer.
 */
@Composable
private fun WholeCartDialog(
    state: OrderUiState,
    onIncrement: (Int) -> Unit,
    onDecrement: (Int) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ออเดอร์นี้", style = MaterialTheme.typography.titleLarge) },
        text = {
            LazyColumn {
                itemsIndexed(state.cart) { index, line ->
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Row {
                            Text(line.itemName, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            Text("${line.total}.-", style = MaterialTheme.typography.bodyLarge)
                        }
                        if (line.optionsSummary.isNotBlank()) {
                            Text(line.optionsSummary, color = InkTextDim)
                        }
                        if (line.note.isNotBlank()) Text(line.note, color = Alert)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            OutlinedButton(
                                onClick = { onDecrement(index) },
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) { Text("−", fontSize = 22.sp) }
                            Text("${line.qty}", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                            OutlinedButton(
                                onClick = { onIncrement(index) },
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) { Text("+", fontSize = 22.sp) }
                        }
                        Divider(Modifier.padding(top = 6.dp))
                    }
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("กลับไปสั่ง") } },
        dismissButton = {
            TextButton(
                onClick = onClear,
                colors = ButtonDefaults.textButtonColors(contentColor = Alert)
            ) { Text("ล้างออเดอร์") }
        }
    )
}
