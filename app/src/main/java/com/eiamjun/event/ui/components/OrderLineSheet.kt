package com.eiamjun.event.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eiamjun.event.data.CartLine
import com.eiamjun.event.data.MenuItemWithOptions
import com.eiamjun.event.data.OptionEntity
import com.eiamjun.event.data.OptionJoin

/**
 * What the pad asks before a bowl goes on the order.
 *
 * Everything on it comes out of the menu — the groups, their options, whether each is one-of, and
 * what each adds to the price. Nothing is hard-coded, which is the whole reason the owner can
 * change what the stall asks without a build.
 *
 * **จำนวน is pinned to the foot and does not scroll.** On a four-group sheet it would otherwise be
 * below the fold, and it is both the answer that gets misheard across a counter and the one that
 * gets read back to the customer. The shop asked for exactly this on its own till.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OrderLineSheet(
    dish: MenuItemWithOptions,
    onDismiss: () -> Unit,
    onConfirm: (CartLine) -> Unit
) {
    val groups = remember(dish) { dish.groups.sortedBy { it.group.sortOrder } }

    // Keyed by group id. A one-of group holds at most one id; a multi-select holds any number.
    val chosen = remember(dish) {
        mutableStateMapOf<Long, Set<Long>>().apply {
            groups.forEach { g ->
                if (g.group.multiSelect) {
                    put(g.group.id, emptySet())
                } else {
                    // Where a one-of group opens: what the menu marks default, else the first by
                    // sortOrder. Never "whatever sorted first by chance" — the shop's till found
                    // that rearranging a pad quietly changed what a new order defaulted to.
                    val options = g.options.sortedBy { it.sortOrder }
                    val default = options.firstOrNull { it.isDefault } ?: options.firstOrNull()
                    put(g.group.id, setOfNotNull(default?.id))
                }
            }
        }
    }
    var qty by remember(dish) { mutableStateOf(1) }
    var note by remember(dish) { mutableStateOf("") }

    val picked: List<OptionEntity> = groups.flatMap { g ->
        val ids = chosen[g.group.id].orEmpty()
        g.options.sortedBy { it.sortOrder }.filter { it.id in ids }
    }
    val unitPrice = dish.item.price + picked.sumOf { it.priceDelta }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(dish.item.name, style = MaterialTheme.typography.headlineMedium) },
        text = {
            Column {
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                ) {
                    groups.forEach { g ->
                        Text(
                            g.group.name,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            g.options.sortedBy { it.sortOrder }.forEach { option ->
                                val selected = option.id in chosen[g.group.id].orEmpty()
                                FilterChip(
                                    selected = selected,
                                    onClick = {
                                        val current = chosen[g.group.id].orEmpty()
                                        chosen[g.group.id] = when {
                                            // One-of: a tap replaces, and cannot clear. A bowl has
                                            // a noodle whatever else happens.
                                            !g.group.multiSelect -> setOf(option.id)
                                            selected -> current - option.id
                                            else -> current + option.id
                                        }
                                    },
                                    label = {
                                        Text(
                                            if (option.priceDelta == 0) option.name
                                            else "${option.name} ${signed(option.priceDelta)}",
                                            fontSize = 19.sp
                                        )
                                    },
                                    modifier = Modifier.heightIn(min = 48.dp)
                                )
                            }
                        }
                    }
                    // Chips first and a field last, on purpose: typing Thai with a customer waiting
                    // is why a note field on its own goes unused. This is for the thing the chips
                    // do not cover.
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        label = { Text("หมายเหตุอื่น") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                    )
                }
                Divider(Modifier.padding(vertical = 12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("จำนวน", style = MaterialTheme.typography.titleMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { if (qty > 1) qty-- },
                            modifier = Modifier.heightIn(min = 52.dp)
                        ) { Text("−", fontSize = 26.sp) }
                        Text(
                            "$qty",
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 20.dp)
                        )
                        OutlinedButton(
                            onClick = { qty++ },
                            modifier = Modifier.heightIn(min = 52.dp)
                        ) { Text("+", fontSize = 26.sp) }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(
                        CartLine(
                            itemId = dish.item.id,
                            itemName = dish.item.name,
                            optionsSummary = picked.joinToString(OptionJoin.SEPARATOR) { it.name },
                            note = note.trim(),
                            qty = qty,
                            unitPrice = unitPrice
                        )
                    )
                },
                modifier = Modifier.heightIn(min = 56.dp)
            ) { Text("เพิ่ม · ${unitPrice * qty}.-", fontSize = 20.sp) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ยกเลิก") } }
    )
}

private fun signed(delta: Int): String = if (delta > 0) "+$delta" else "$delta"
