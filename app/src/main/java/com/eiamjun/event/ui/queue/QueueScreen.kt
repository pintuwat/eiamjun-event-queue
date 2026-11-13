package com.eiamjun.event.ui.queue

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eiamjun.event.data.EventDay
import com.eiamjun.event.data.OrderStatus
import com.eiamjun.event.data.OrderWithLines
import com.eiamjun.event.data.QueueNumbering
import com.eiamjun.event.ui.components.OrderDetailSheet
import com.eiamjun.event.ui.components.PreviewDialog
import com.eiamjun.event.ui.theme.Alert
import com.eiamjun.event.ui.theme.Basil
import com.eiamjun.event.ui.theme.Gold
import com.eiamjun.event.ui.theme.InkTextDim
import com.eiamjun.event.ui.theme.Paper
import com.eiamjun.event.ui.theme.PriceOrange
import com.eiamjun.event.ui.theme.Wood
import kotlinx.coroutines.delay

/**
 * Everything still owed to a customer, พร้อมส่ง at the top.
 *
 * Ordered by status and then by queue number — not by time. A list that disagrees with the numbers
 * on the tickets in people's hands is a list nobody can work from at a counter, and the ready ones
 * go first because that is the only part of it anybody is acting on right now.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(
    onBack: () -> Unit,
    viewModel: QueueViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val orders by viewModel.orders.collectAsStateWithLifecycle()
    val takings by viewModel.takings.collectAsStateWithLifecycle()
    val orderCount by viewModel.orderCount.collectAsStateWithLifecycle()
    var opened by remember { mutableStateOf<Long?>(null) }

    // Polled from the screen, so it stops the moment the screen is closed. Five seconds is the
    // same cadence the shop's web floor settled on: this is a guard against a number being called
    // late, not a live feed, and nothing is lost by a board five seconds old.
    LaunchedEffect(Unit) {
        while (true) {
            viewModel.poll()
            delay(POLL_MS)
        }
    }

    val sorted = remember(orders) {
        orders.sortedWith(
            compareBy<OrderWithLines> { if (it.order.status == OrderStatus.READY) 0 else 1 }
                // The round before the number: after เริ่มนับคิวใหม่ the numbers start again, so
                // the morning's leftovers belong above the afternoon rather than interleaved with
                // it by a number that no longer counts up.
                .thenBy { it.order.session }
                .thenBy { it.order.queueNo }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Wood,
                    titleContentColor = Gold,
                    navigationIconContentColor = Gold,
                    actionIconContentColor = Gold
                ),
                title = { Text("คิว", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("กลับ", color = Gold, fontSize = 18.sp) }
                }
            )
        },
        bottomBar = {
            Surface(color = Color.White, shadowElevation = 8.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("ยอดวันนี้", color = InkTextDim)
                        Text("$takings.-", style = MaterialTheme.typography.displaySmall, color = PriceOrange)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("$orderCount ออเดอร์", style = MaterialTheme.typography.titleMedium)
                        // What the board knows, said plainly. An unreachable server is the failure
                        // that otherwise looks exactly like a kitchen with nothing finished.
                        Text(
                            when (state.serverReachable) {
                                true -> "ต่อครัวได้"
                                false -> "ต่อครัวไม่ได้"
                                null -> "กำลังเชื่อมต่อ…"
                            },
                            color = if (state.serverReachable == false) Alert else InkTextDim
                        )
                    }
                }
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().background(Paper)) {
            if (sorted.isEmpty()) {
                Text(
                    "ไม่มีคิวค้าง",
                    style = MaterialTheme.typography.titleLarge,
                    color = InkTextDim,
                    modifier = Modifier.padding(24.dp)
                )
            }
            LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(sorted, key = { it.order.id }) { order ->
                    QueueCard(
                        order = order,
                        onClick = { opened = order.order.id },
                        onMarkReadyHere = { viewModel.markReadyHere(order) },
                        onPicked = { viewModel.markPicked(order) }
                    )
                }
            }
        }
    }

    sorted.firstOrNull { it.order.id == opened }?.let { order ->
        OrderDetailSheet(
            order = order,
            onPrintReceipt = { viewModel.printReceipt(order) },
            onReprintTicket = { viewModel.reprintTicket(order) },
            onPicked = {
                viewModel.markPicked(order)
                opened = null
            },
            onCancel = {
                viewModel.cancel(order)
                opened = null
            },
            onDismiss = { opened = null }
        )
    }

    state.preview?.let { PreviewDialog(it, onDismiss = viewModel::dismissPreview) }
}

/**
 * One queue number, as large as a row can carry it, with what to do about it underneath.
 *
 * The number is what the staff match against a ticket held up over the counter, so it gets the
 * room; everything else on the card is there to confirm the right order once they have found it.
 *
 * **ส่งของแล้ว is on the card, not only in the sheet.** Handing food over is the one thing that
 * happens to every order, every time, and it was costing a tap into a dialog to reach — with a
 * queue waiting, that is the tap the staff pay most often. ยกเลิก stays behind the dialog for
 * exactly the reason this one came out of it: it is the act that must never be one mis-tap away
 * on a list read at speed.
 */
@Composable
private fun QueueCard(
    order: OrderWithLines,
    onClick: () -> Unit,
    onMarkReadyHere: () -> Unit,
    onPicked: () -> Unit
) {
    val ready = order.order.status == OrderStatus.READY
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (ready) Basil.copy(alpha = 0.16f) else Color.White
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    Modifier.padding(end = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "${order.order.queueNo}",
                        fontSize = 44.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (ready) Basil else PriceOrange
                    )
                    // Drawn only once a reset has happened, which is the only time the list can
                    // hold two cards reading the same number — and the only time it tells anyone
                    // anything.
                    if (order.order.session > QueueNumbering.FIRST_SESSION) {
                        Text(
                            "รอบ ${order.order.session}",
                            fontSize = 13.sp,
                            color = InkTextDim
                        )
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        if (ready) "พร้อมส่ง" else "กำลังทำ",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (ready) Basil else InkTextDim
                    )
                    Text(
                        order.lines.joinToString(", ") { "${it.qty}×${it.itemName}" },
                        color = InkTextDim,
                        maxLines = 2
                    )
                    Text(
                        "${EventDay.clock(order.order.createdAt)} · ${order.order.total}.-",
                        color = InkTextDim
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // ⚠️ **"พร้อม" read as the kitchen's word, and this button is not that.** The
                // staff took it for the board reporting a dish finished, when it is this terminal
                // saying so — which is a different claim, made by a different person, and the one
                // evening it matters is the evening the board cannot be reached. The wording is
                // an instruction now, and carries เอง, because what the staff have to understand
                // before pressing it is who is doing the declaring.
                if (!ready) {
                    OutlinedButton(
                        onClick = onMarkReadyHere,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                        modifier = Modifier.weight(1f).heightIn(min = 56.dp)
                    ) { Text("กดพร้อมเอง", fontSize = 18.sp, color = Basil, softWrap = false) }
                }
                // Loud on a ready card and quiet on one still cooking. Both can be handed over —
                // the kitchen may simply never have marked it — but the ready card is the one
                // somebody is walking towards with a bowl, and it is sorted to the top for the
                // same reason.
                if (ready) {
                    Button(
                        onClick = onPicked,
                        colors = ButtonDefaults.buttonColors(containerColor = Basil),
                        modifier = Modifier.weight(1f).heightIn(min = 60.dp)
                    ) {
                        Text(
                            "ส่งของแล้ว",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            softWrap = false
                        )
                    }
                } else {
                    OutlinedButton(
                        onClick = onPicked,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                        modifier = Modifier.weight(1f).heightIn(min = 56.dp)
                    ) { Text("ส่งของแล้ว", fontSize = 18.sp, color = Basil, softWrap = false) }
                }
            }
        }
    }
}

private const val POLL_MS = 5_000L
