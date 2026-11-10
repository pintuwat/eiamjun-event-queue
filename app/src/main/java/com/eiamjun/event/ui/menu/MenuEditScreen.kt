package com.eiamjun.event.ui.menu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eiamjun.event.data.GroupWithOptions
import com.eiamjun.event.data.MenuItemWithOptions
import com.eiamjun.event.data.OptionEntity
import com.eiamjun.event.ui.theme.Alert
import com.eiamjun.event.ui.theme.Gold
import com.eiamjun.event.ui.theme.InkTextDim
import com.eiamjun.event.ui.theme.Wood

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MenuEditScreen(
    onBack: () -> Unit,
    viewModel: MenuEditViewModel = hiltViewModel()
) {
    val menu by viewModel.menu.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Wood,
                    titleContentColor = Gold,
                    navigationIconContentColor = Gold
                ),
                title = { Text("แก้ไขเมนู", fontWeight = FontWeight.Bold) },
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
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "ราคาที่มากับแอปเป็นค่าตั้งต้น ยังไม่ใช่ราคาจริง — แก้ให้ตรงก่อนเปิดขาย",
                color = Alert
            )
            // Said on the screen rather than only in the code: "will this wipe my takings?" is
            // what anyone editing a till's menu is actually asking.
            Text(
                "แก้ราคาแล้วไม่กระทบออเดอร์ที่ขายไปแล้ว — แต่ละบรรทัดเก็บชื่อและราคาตอนที่สั่งไว้แล้ว",
                color = InkTextDim
            )
            menu.forEach { dish -> DishCard(dish, viewModel) }
        }
    }
}

@Composable
private fun DishCard(dish: MenuItemWithOptions, viewModel: MenuEditViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    dish.item.name,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f)
                )
                Text(if (dish.item.active) "ขาย" else "ปิดขาย", color = InkTextDim)
                Switch(
                    checked = dish.item.active,
                    onCheckedChange = { viewModel.setActive(dish.item, it) }
                )
            }
            EditableText(
                label = "ชื่อเมนู",
                value = dish.item.name,
                onCommit = { viewModel.rename(dish.item, it) }
            )
            EditableNumber(
                label = "ราคา",
                value = dish.item.price,
                onCommit = { viewModel.reprice(dish.item, it) }
            )
            dish.groups.sortedBy { it.group.sortOrder }.forEach { group ->
                Divider(Modifier.padding(top = 6.dp))
                GroupBlock(group, viewModel)
            }
        }
    }
}

@Composable
private fun GroupBlock(group: GroupWithOptions, viewModel: MenuEditViewModel) {
    var newName by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            group.group.name +
                if (group.group.multiSelect) " · เลือกได้หลายอย่าง" else " · เลือกได้อย่างเดียว",
            style = MaterialTheme.typography.titleMedium
        )
        group.options.sortedBy { it.sortOrder }.forEach { option ->
            OptionRow(option, viewModel)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                label = { Text("เพิ่มตัวเลือก") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = {
                    viewModel.addOption(
                        groupId = group.group.id,
                        name = newName,
                        delta = 0,
                        after = group.options.maxOfOrNull { it.sortOrder } ?: 0
                    )
                    newName = ""
                },
                enabled = newName.isNotBlank(),
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text("เพิ่ม") }
        }
    }
}

@Composable
private fun OptionRow(option: OptionEntity, viewModel: MenuEditViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        EditableText(
            label = "ตัวเลือก",
            value = option.name,
            onCommit = { viewModel.renameOption(option, it) },
            modifier = Modifier.weight(1f)
        )
        EditableNumber(
            label = "+/−",
            value = option.priceDelta,
            onCommit = { viewModel.setOptionDelta(option, it) },
            allowNegative = true,
            modifier = Modifier.width(112.dp).padding(start = 8.dp)
        )
        TextButton(onClick = { viewModel.deleteOption(option) }) { Text("ลบ", color = Alert) }
    }
}

/**
 * A field that writes its value when it **loses focus**, not on every keystroke.
 *
 * Committing per keystroke would write a half-typed name into the menu, and since the pad reads
 * the same rows the staff would watch a key rename itself letter by letter.
 */
@Composable
private fun EditableText(
    label: String,
    value: String,
    onCommit: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var text by remember(value) { mutableStateOf(value) }
    var hadFocus by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(label) },
        singleLine = true,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focus ->
                if (focus.isFocused) {
                    hadFocus = true
                } else if (hadFocus) {
                    hadFocus = false
                    if (text.isNotBlank() && text != value) onCommit(text.trim())
                }
            }
    )
}

@Composable
private fun EditableNumber(
    label: String,
    value: Int,
    onCommit: (Int) -> Unit,
    allowNegative: Boolean = false,
    modifier: Modifier = Modifier
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    var hadFocus by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = text,
        onValueChange = { typed ->
            // A lone "−" is kept while it waits for digits. Requiring a digit to hold it meant the
            // minus was deleted the instant it was typed and a discount could not be entered at
            // all — the shop's till hit exactly this.
            text = typed.filterIndexed { index, c ->
                c.isDigit() || (allowNegative && index == 0 && (c == '-' || c == '−'))
            }
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier.onFocusChanged { focus ->
            if (focus.isFocused) {
                hadFocus = true
            } else if (hadFocus) {
                hadFocus = false
                val parsed = text.replace('−', '-').toIntOrNull()
                if (parsed != null && parsed != value) onCommit(parsed)
            }
        }
    )
}
