package com.eiamjun.event.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eiamjun.event.data.EventDay
import com.eiamjun.event.data.OrderLineEntity
import com.eiamjun.event.data.OrderStatus
import com.eiamjun.event.data.OrderWithLines
import com.eiamjun.event.data.Payment
import com.eiamjun.event.printer.ScreenQr
import com.eiamjun.event.ui.theme.Alert
import com.eiamjun.event.ui.theme.Basil
import com.eiamjun.event.ui.theme.InkTextDim
import com.eiamjun.event.ui.theme.PriceOrange

/**
 * Taking the money, before anything is printed.
 *
 * The amount is the loudest thing on it and the QR is below it, because the customer is looking at
 * the screen over the counter and the number is what they check first. Nothing has been recorded
 * at this point — closing this dialog leaves the pad exactly as it was.
 *
 * **The cash button takes no tendered amount and works out no change.** The owner asked for QR
 * only; this is here because cash still happens at a stall, and all it changes is that the
 * receipt printed later carries no payment code — a QR on a bill already settled in cash is an
 * invitation to pay twice.
 */
@Composable
fun PaymentSheet(
    total: Int,
    itemCount: Int,
    queueNo: Int,
    qr: ScreenQr?,
    busy: Boolean,
    onPaid: (payment: String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("รับเงิน", style = MaterialTheme.typography.headlineMedium) },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Text(
                    "$total.-",
                    fontSize = 56.sp,
                    fontWeight = FontWeight.Bold,
                    color = PriceOrange
                )
                Text("$itemCount รายการ · บัตรคิวจะเป็นคิวที่ $queueNo", color = InkTextDim)
                if (qr != null) {
                    QrImage(
                        payload = qr.payload,
                        modifier = Modifier
                            .padding(top = 14.dp)
                            .fillMaxWidth(0.68f)
                            .aspectRatio(1f)
                    )
                    // Said out loud when the code could not be given the amount, because then the
                    // customer has to type it and nothing else on the screen tells them so.
                    if (!qr.carriesAmount) {
                        Text(
                            "กรุณาใส่ยอด $total.- บาทในแอปธนาคาร",
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                } else {
                    Text(
                        "ยังไม่ได้ตั้ง QR รับเงิน — ตั้งค่า → QR ร้านค้า",
                        color = Alert,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onPaid(Payment.QR) },
                enabled = !busy,
                modifier = Modifier.heightIn(min = 60.dp)
            ) { Text(if (busy) "กำลังพิมพ์…" else "รับเงิน · พิมพ์บัตรคิว", fontSize = 18.sp) }
        },
        // A row of two short buttons, not a column of two long ones. Stacked, the dialog's button
        // block plus the QR ran past the bottom of a 5.5" screen and ยกเลิก was clipped in half —
        // and the content above it scrolls while this block does not, so nothing could bring it
        // back. Short labels are what let both sit on one line.
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(
                    onClick = { onPaid(Payment.CASH) },
                    enabled = !busy,
                    modifier = Modifier.heightIn(min = 52.dp)
                ) { Text("เงินสด") }
                TextButton(
                    onClick = onDismiss,
                    enabled = !busy,
                    modifier = Modifier.heightIn(min = 52.dp)
                ) { Text("ยกเลิก") }
            }
        }
    )
}

/**
 * The ticket has been printed and the order is on the kitchen's board.
 *
 * It shows the number because the staff read it back to the customer as they hand the paper over,
 * and because when the printer has failed this screen is the only copy of it — which is why
 * พิมพ์บัตรคิวซ้ำ is here and not buried in the queue list.
 */
@Composable
fun TicketPrintedSheet(
    queueNo: Int,
    total: Int,
    printError: String?,
    onReprintTicket: () -> Unit,
    onPrintReceipt: () -> Unit,
    onDone: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("บัตรคิวพิมพ์แล้ว", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("คิวที่", color = InkTextDim)
                Text(
                    "$queueNo",
                    fontSize = 96.sp,
                    fontWeight = FontWeight.Bold,
                    color = PriceOrange
                )
                Text("$total.-", style = MaterialTheme.typography.titleLarge)
                if (printError != null) {
                    // Said plainly rather than hidden: the sale is already recorded, so the honest
                    // report is "it is on the books and the paper did not come out".
                    Text(
                        "พิมพ์ไม่ออก: $printError\nออเดอร์ถูกบันทึกแล้ว",
                        color = Alert,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                }
                Row(
                    modifier = Modifier.padding(top = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onReprintTicket,
                        modifier = Modifier.weight(1f).heightIn(min = 52.dp)
                    ) { Text("พิมพ์บัตรซ้ำ") }
                    OutlinedButton(
                        onClick = onPrintReceipt,
                        modifier = Modifier.weight(1f).heightIn(min = 52.dp)
                    ) { Text("ใบเสร็จ") }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDone, modifier = Modifier.heightIn(min = 56.dp)) {
                Text("รับออเดอร์ถัดไป", fontSize = 19.sp)
            }
        }
    )
}

/**
 * One order, opened from the queue list.
 *
 * This is where a bowl is handed over, and the three things that can happen to an order live here
 * rather than on the list row: the list is read at speed with a queue waiting, and ยกเลิก in
 * particular has no business being one mis-tap away on it.
 */
@Composable
fun OrderDetailSheet(
    order: OrderWithLines,
    onPrintReceipt: () -> Unit,
    onReprintTicket: () -> Unit,
    onPicked: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "คิว ${order.order.queueNo}",
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    color = PriceOrange
                )
                Text(
                    statusWord(order.order.status),
                    color = if (order.order.status == OrderStatus.READY) Basil else InkTextDim,
                    modifier = Modifier.padding(start = 12.dp)
                )
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "รับออเดอร์ ${EventDay.clock(order.order.createdAt)}" +
                        if (order.order.payment == Payment.CASH) " · เงินสด" else " · QR",
                    color = InkTextDim
                )
                Divider(Modifier.padding(vertical = 8.dp))
                order.lines.forEach { LineRow(it) }
                Divider(Modifier.padding(vertical = 8.dp))
                Row {
                    Text("รวม", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "${order.order.total}.-",
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.End,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Row(
                    modifier = Modifier.padding(top = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onPrintReceipt,
                        modifier = Modifier.weight(1f).heightIn(min = 52.dp)
                    ) { Text("พิมพ์ใบเสร็จ") }
                    OutlinedButton(
                        onClick = onReprintTicket,
                        modifier = Modifier.weight(1f).heightIn(min = 52.dp)
                    ) { Text("พิมพ์บัตรซ้ำ") }
                }
                TextButton(
                    onClick = onCancel,
                    colors = ButtonDefaults.textButtonColors(contentColor = Alert),
                    modifier = Modifier.padding(top = 4.dp)
                ) { Text("ยกเลิกออเดอร์นี้") }
            }
        },
        confirmButton = {
            Button(
                onClick = onPicked,
                colors = ButtonDefaults.buttonColors(containerColor = Basil),
                modifier = Modifier.heightIn(min = 56.dp)
            ) { Text("ส่งของแล้ว", fontSize = 19.sp, color = androidx.compose.ui.graphics.Color.White) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ปิด") } }
    )
}

@Composable
private fun LineRow(line: OrderLineEntity) {
    Row(Modifier.padding(vertical = 4.dp)) {
        Text(
            "${line.qty}",
            style = MaterialTheme.typography.titleLarge,
            color = PriceOrange,
            modifier = Modifier.padding(end = 10.dp)
        )
        Column(Modifier.weight(1f)) {
            Text(line.itemName, style = MaterialTheme.typography.bodyLarge)
            if (line.optionsSummary.isNotBlank()) {
                Text(line.optionsSummary, color = InkTextDim)
            }
            if (line.note.isNotBlank()) {
                Text(line.note, color = Alert)
            }
        }
        Text("${line.unitPrice * line.qty}.-", style = MaterialTheme.typography.bodyLarge)
    }
}

private fun statusWord(status: String): String = when (status) {
    OrderStatus.WAITING -> "กำลังทำ"
    OrderStatus.READY -> "พร้อมส่ง"
    OrderStatus.PICKED -> "ส่งแล้ว"
    else -> "ยกเลิก"
}

/**
 * What the preview "printer" produced, for a device with no printer head.
 *
 * Monospaced, because the whole point of looking at it is whether the columns line up.
 */
@Composable
fun PreviewDialog(text: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ตัวอย่างใบพิมพ์") },
        text = {
            Text(
                text,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                modifier = Modifier.verticalScroll(rememberScrollState())
            )
        },
        confirmButton = { Button(onClick = onDismiss) { Text("ปิด") } }
    )
}
