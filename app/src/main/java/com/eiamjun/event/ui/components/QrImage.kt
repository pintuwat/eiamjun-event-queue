package com.eiamjun.event.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.eiamjun.event.printer.QrMatrix

/**
 * Draws a QR straight onto a canvas rather than building a bitmap.
 *
 * One rectangle per dark module, sized from the measured canvas, so it stays crisp at whatever size
 * the layout gives it — a bitmap scaled to fit would blur exactly the edges a scanner reads.
 *
 * ⚠️ The encoder behind this is the project's own ([QrMatrix]), and three bugs got through it that
 * each produced a code which looked right and scanned as nothing. Its tests came across with it.
 * **Verify a payment code with a real banking app, never with your own reader.**
 */
@Composable
fun QrImage(payload: String, modifier: Modifier = Modifier) {
    val modules = remember(payload) { QrMatrix.encode(payload) }
    val quiet = 4
    Canvas(modifier = modifier.background(Color.White)) {
        val span = modules.size + quiet * 2
        val cell = size.minDimension / span
        for (r in modules.indices) {
            for (c in modules[r].indices) {
                if (!modules[r][c]) continue
                drawRect(
                    color = Color.Black,
                    topLeft = Offset((c + quiet) * cell, (r + quiet) * cell),
                    size = Size(cell, cell)
                )
            }
        }
    }
}
