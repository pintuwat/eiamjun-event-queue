package com.eiamjun.event.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eiamjun.event.ui.theme.CreamDim
import com.eiamjun.event.ui.theme.InkText
import com.eiamjun.event.ui.theme.PriceOrange

/** One key on the pad. [badge] is how many of this dish are already on the order. */
data class PadKey(
    val id: Long,
    val label: String,
    val price: Int,
    val badge: Int = 0
)

/**
 * The pad. Two across, because this menu has two dishes and a key the width of half a 5.5" screen
 * is a target nobody misses with a queue waiting.
 *
 * The label is 27sp and is **not** a setting. The shop's till had three sizes for this and the
 * owner's verdict was that two pads reading the same words at different sizes is worse than one
 * number chosen once — so there is one number here, shared by every key.
 *
 * `softWrap` is left on and the label broken by the layout: these names are short enough that
 * Compose's Thai dictionary has nothing to get wrong. If a longer name ever splits a syllable —
 * "วาฟเฟิ" over "ล", which is what the shop reported — the fix is choosing the break points here,
 * not shrinking the text.
 */
@Composable
fun MenuKeypad(
    keys: List<PadKey>,
    modifier: Modifier = Modifier,
    onKeyTap: (PadKey) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(keys, key = { it.id }) { key ->
            Card(
                onClick = { onKeyTap(key) },
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                modifier = Modifier.heightIn(min = 112.dp)
            ) {
                Box(Modifier.fillMaxWidth().padding(10.dp)) {
                    androidx.compose.foundation.layout.Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            key.label,
                            fontSize = KEY_LABEL_SP.sp,
                            lineHeight = (KEY_LABEL_SP + 4).sp,
                            fontWeight = FontWeight.Bold,
                            color = InkText,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            "${key.price}.-",
                            style = MaterialTheme.typography.titleMedium,
                            color = PriceOrange
                        )
                    }
                    if (key.badge > 0) {
                        Badge(
                            modifier = Modifier.align(Alignment.TopEnd),
                            containerColor = PriceOrange,
                            contentColor = Color.White
                        ) { Text("${key.badge}") }
                    }
                }
            }
        }
    }
}

/** Read at arm's length over a counter. The one size, shared by every pad in the app. */
const val KEY_LABEL_SP = 27
