package com.eiamjun.event.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eiamjun.event.data.EventDay
import com.eiamjun.event.ui.components.PreviewDialog
import com.eiamjun.event.ui.theme.Alert
import com.eiamjun.event.ui.theme.Basil
import com.eiamjun.event.ui.theme.Gold
import com.eiamjun.event.ui.theme.InkTextDim
import com.eiamjun.event.ui.theme.Wood

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenMenuEditor: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val logEntries by viewModel.logEntries.collectAsStateWithLifecycle()
    var confirmingReset by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Wood,
                    titleContentColor = Gold,
                    navigationIconContentColor = Gold
                ),
                title = { Text("ตั้งค่า", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("กลับ", color = Gold, fontSize = 18.sp) }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SectionTitle("เมนู")
            OutlinedButton(
                onClick = onOpenMenuEditor,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
            ) { Text("แก้ไขเมนูและราคา") }
            Text(
                "ราคาที่ติดมากับแอปเป็นค่าตั้งต้น ยังไม่ใช่ราคาจริงของงาน — แก้ที่นี่ได้เลย",
                color = Alert
            )

            Divider()
            SectionTitle("เซิร์ฟเวอร์ครัว")
            OutlinedTextField(
                value = state.deviceName,
                onValueChange = viewModel::setDeviceName,
                label = { Text("ชื่อเครื่อง (ใช้แยกคิวเมื่อมีหลายเครื่อง)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "มีเครื่องเดียวไม่ต้องแก้ — ถ้าเพิ่มเครื่องที่สอง ต้องตั้งชื่อให้ต่างกันก่อนเปิดใช้ ไม่ใช่ตอนขาย",
                color = InkTextDim
            )
            OutlinedTextField(
                value = state.serverUrl,
                onValueChange = viewModel::setServerUrl,
                label = { Text("ที่อยู่เซิร์ฟเวอร์ (เช่น http://192.168.1.20:8080)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = state.authUser,
                onValueChange = viewModel::setAuthUser,
                label = { Text("ชื่อผู้ใช้ (ถ้ามี)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = state.authPassword,
                onValueChange = viewModel::setAuthPassword,
                label = { Text("รหัสผ่าน (ถ้ามี)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = viewModel::testConnection,
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp)
                ) { Text("ทดสอบเชื่อมต่อ") }
                OutlinedButton(
                    onClick = viewModel::flushOutbox,
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp)
                ) { Text("ส่งที่ค้างอยู่") }
            }
            // The address can be left blank and the till still works — it prints and records, and
            // the orders queue up. Worth saying, because a blank field reads like a mistake.
            Text(
                "เว้นว่างได้ — เครื่องยังรับออเดอร์และพิมพ์บัตรคิวได้ ออเดอร์จะค้างไว้ส่งทีหลัง",
                color = InkTextDim
            )

            Divider()
            SectionTitle("หัวใบพิมพ์")
            OutlinedTextField(
                value = state.shopName,
                onValueChange = viewModel::setShopName,
                label = { Text("ชื่อร้าน") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = state.shopSubtitle,
                onValueChange = viewModel::setShopSubtitle,
                label = { Text("บรรทัดรอง (บนใบเสร็จ)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Divider()
            SectionTitle("รับเงิน")
            OutlinedTextField(
                value = state.merchantQr,
                onValueChange = viewModel::setMerchantQr,
                label = { Text("QR ร้านค้า (ถุงเงิน / Thai QR)") },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                if (state.merchantQr.isBlank()) {
                    "ยังไม่ได้ตั้ง — ยังไม่มี QR ขึ้นจอและไม่พิมพ์ลงใบเสร็จ"
                } else {
                    "ตั้งแล้ว (${state.merchantQr.length} ตัวอักษร) · ⚠️ ต้องสแกนทดสอบด้วยแอปธนาคารจริงก่อนใช้งาน"
                },
                color = if (state.merchantQr.isBlank()) Alert else Basil
            )
            OutlinedTextField(
                value = state.promptPayId,
                onValueChange = viewModel::setPromptPayId,
                label = { Text("เบอร์พร้อมเพย์ (ใช้เมื่อไม่มี QR ร้านค้า)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Stepper(
                label = "ขนาด QR บนใบเสร็จ",
                value = state.qrDotSize,
                onChange = viewModel::setQrDotSize,
                suffix = " จุด/ช่อง"
            )

            Divider()
            SectionTitle("เครื่องพิมพ์")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("ใช้เครื่องพิมพ์ในตัว (Sunmi)", style = MaterialTheme.typography.bodyLarge)
                    Text("ปิด = โชว์ใบพิมพ์บนจอ สำหรับเครื่องที่ไม่มีหัวพิมพ์", color = InkTextDim)
                }
                Switch(checked = state.useSunmiPrinter, onCheckedChange = viewModel::setUseSunmiPrinter)
            }
            Stepper(
                label = "ขนาดเลขคิวบนบัตร",
                value = state.queueNumberDots,
                onChange = viewModel::setQueueNumberDots,
                suffix = " ระดับ"
            )
            Text(
                "⚠️ ขนาดเลขคิวยังไม่เคยพิมพ์จริง — พิมพ์ตัวอย่างแล้วปรับจนพอดีกระดาษ",
                color = Alert
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = viewModel::printSamples,
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp)
                ) { Text("พิมพ์บัตรตัวอย่าง") }
                OutlinedButton(
                    onClick = viewModel::readPrinterStatus,
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp)
                ) { Text("สถานะเครื่องพิมพ์") }
            }

            Divider()
            SectionTitle("คิว")
            // The round is named only once there is one, so the ordinary day says nothing extra —
            // but after a reset it is the only thing on screen that shows the button did anything,
            // since the number goes back to where it already was.
            Text(
                if (state.queueSession > 1) "คิวถัดไปคือ ${state.nextQueueNo} (รอบที่ ${state.queueSession})"
                else "คิวถัดไปคือ ${state.nextQueueNo}",
                style = MaterialTheme.typography.titleMedium
            )
            OutlinedButton(
                onClick = { confirmingReset = true },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Alert),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
            ) { Text("เริ่มนับคิวใหม่จาก 1") }

            Divider()
            SectionTitle("ตรวจสอบระบบ")
            // In memory and gone on the next launch — see EventLog. Said here so nobody goes
            // looking for yesterday's.
            Text("บันทึกเหตุการณ์ (หายเมื่อปิดแอป)", color = InkTextDim)
            logEntries.take(30).forEach { entry ->
                Text(
                    "${EventDay.clock(entry.at)}  ${entry.text}",
                    color = if (entry.bad) Alert else InkTextDim,
                    fontSize = 14.sp
                )
            }
        }
    }

    if (confirmingReset) {
        AlertDialog(
            onDismissRequest = { confirmingReset = false },
            title = { Text("เริ่มนับคิวใหม่?") },
            text = {
                Text(
                    "คิวถัดไปจะกลับไปเป็น 1 — ถ้ายังมีลูกค้าถือบัตรคิวเดิมอยู่ เลขจะซ้ำกัน " +
                        "ออเดอร์ที่ยังไม่ได้รับของจะยังอยู่ในคิวและยอดขายวันนี้ตามเดิม"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.resetQueue()
                        confirmingReset = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Alert)
                ) { Text("เริ่มใหม่") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingReset = false }) { Text("ไม่ใช่ตอนนี้") }
            }
        )
    }

    val message = state.connectionResult ?: state.printerStatus
    if (message != null) {
        AlertDialog(
            onDismissRequest = viewModel::dismissMessages,
            text = { Text(message, style = MaterialTheme.typography.bodyLarge) },
            confirmButton = { Button(onClick = viewModel::dismissMessages) { Text("ปิด") } }
        )
    }

    state.preview?.let { PreviewDialog(it, onDismiss = viewModel::dismissPreview) }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun Stepper(label: String, value: Int, onChange: (Int) -> Unit, suffix: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = { onChange(value - 1) }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("−", fontSize = 20.sp)
        }
        Text(
            "$value$suffix",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 10.dp)
        )
        OutlinedButton(onClick = { onChange(value + 1) }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("+", fontSize = 20.sp)
        }
    }
}
