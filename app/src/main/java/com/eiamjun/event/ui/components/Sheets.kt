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
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
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
import com.eiamjun.event.data.QueueNumbering
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
 *
 * **It reads as two steps, and only one of them is loud at a time.** Since 0.5.0 the receipt is
 * the kitchen's slip, so an order is not finished until it has been printed — and this dialog had
 * พิมพ์ใบเสร็จ as one of two equal outlined buttons under the number with the filled one saying
 * รับออเดอร์ถัดไป, which is the shape of a screen whose loud button is the way out. So the
 * emphasis follows the work: พิมพ์ใบเสร็จ is the filled button until the receipt is out, then it
 * goes quiet and รับออเดอร์ถัดไป takes the colour.
 *
 * Quiet, never disabled. The sale is already recorded and the queue ticket is already in the
 * customer's hand, so a staff member who has to move on — a jammed printer, a customer who has
 * walked off — must be able to, and a dialog that traps them is worse than one they can leave
 * early. The colour says what to do next; it does not decide for them.
 */
@Composable
fun TicketPrintedSheet(
    queueNo: Int,
    total: Int,
    printError: String?,
    receiptPrinted: Boolean,
    receiptError: String?,
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
                        "พิมพ์บัตรคิวไม่ออก: $printError\nออเดอร์ถูกบันทึกแล้ว",
                        color = Alert,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                }
                if (receiptError != null) {
                    Text(
                        "พิมพ์ใบเสร็จไม่ออก: $receiptError",
                        color = Alert,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                }

                // The step, at the size of a step: full width and filled, so the eye lands on it
                // before the way out. Once the paper is out it drops to an outline and keeps the
                // word ซ้ำ, because by then the only reason to press it is a slip that went wrong.
                if (receiptPrinted) {
                    Text(
                        "✓ พิมพ์ใบเสร็จให้ครัวแล้ว",
                        color = Basil,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 14.dp)
                    )
                    OutlinedButton(
                        onClick = onPrintReceipt,
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                    ) { Text("พิมพ์ใบเสร็จซ้ำ") }
                } else {
                    Button(
                        onClick = onPrintReceipt,
                        modifier = Modifier
                            .padding(top = 14.dp)
                            .fillMaxWidth()
                            .heightIn(min = 60.dp)
                    ) { Text("พิมพ์ใบเสร็จ", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                }

                // Rare — only when the queue ticket itself jammed — so it is the quietest thing
                // here rather than half of a row shared with the step above it.
                TextButton(
                    onClick = onReprintTicket,
                    modifier = Modifier.padding(top = 2.dp).heightIn(min = 48.dp)
                ) { Text("พิมพ์บัตรคิวซ้ำ", color = InkTextDim) }
            }
        },
        confirmButton = {
            // Takes the colour only once the receipt is out, so the two buttons read in the order
            // the work happens. Outlined rather than disabled: see the note on this composable.
            if (receiptPrinted) {
                Button(onClick = onDone, modifier = Modifier.heightIn(min = 56.dp)) {
                    Text("รับออเดอร์ถัดไป", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                OutlinedButton(onClick = onDone, modifier = Modifier.heightIn(min = 56.dp)) {
                    Text("รับออเดอร์ถัดไป", fontSize = 19.sp, color = InkTextDim)
                }
            }
        }
    )
}

/**
 * One order, opened from the queue list.
 *
 * ส่งของแล้ว is on the list row as well, because it is what happens to every order and the tap
 * into here was being paid every time. It stays on this screen too: somebody who opened a card to
 * read the lines back should be able to finish from where they are rather than close the dialog to
 * press the same word one row behind it.
 *
 * ยกเลิก does **not** move out, and that is the line between them: it voids an order already paid
 * for, with no undo, and a list read at speed with a queue waiting is the last place it belongs.
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
    var confirmingCancel by remember { mutableStateOf(false) }

    if (confirmingCancel) {
        AlertDialog(
            onDismissRequest = { confirmingCancel = false },
            title = {
                Text(
                    if (order.order.session > QueueNumbering.FIRST_SESSION)
                        "ยกเลิกคิว ${order.order.queueNo} รอบ ${order.order.session}?"
                    else "ยกเลิกคิว ${order.order.queueNo}?"
                )
            },
            text = {
                Text(
                    "ออเดอร์นี้รับเงินไปแล้ว ${order.order.total}.- — ยกเลิกแล้วจะหายจากจอครัว " +
                        "และไม่ถูกนับในยอดวันนี้ ย้อนกลับไม่ได้"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmingCancel = false
                        onCancel()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Alert),
                    modifier = Modifier.heightIn(min = 56.dp)
                ) { Text("ยกเลิกออเดอร์", color = Color.White) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingCancel = false }) { Text("ไม่ใช่ตอนนี้") }
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    // This is the screen somebody reads to confirm they have the right order
                    // before handing food over, so after a reset it has to say which #1.
                    if (order.order.session > QueueNumbering.FIRST_SESSION)
                        "คิว ${order.order.queueNo} · รอบ ${order.order.session}"
                    else "คิว ${order.order.queueNo}",
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
                // Behind a confirmation that names the queue and the money. It voids an order
                // that has already been paid for, it sits a finger's width from พิมพ์ใบเสร็จ on
                // a screen tapped at speed with a customer waiting, and there is no undo — the
                // only way back is ringing the order up again under a new number, which is not
                // the number on the ticket in the customer's hand.
                TextButton(
                    onClick = { confirmingCancel = true },
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
