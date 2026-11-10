package com.eiamjun.event.printer

import android.content.Context
import android.util.Log
import com.sunmi.printerx.PrinterSdk
import com.sunmi.printerx.enums.Align
import com.sunmi.printerx.enums.ErrorLevel
import com.sunmi.printerx.enums.ImageAlgorithm
import com.sunmi.printerx.enums.Status
import com.sunmi.printerx.style.BaseStyle
import com.sunmi.printerx.style.BitmapStyle
import com.sunmi.printerx.style.QrStyle
import com.sunmi.printerx.style.TextStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Driver for the printer built into Sunmi terminals (V1s / V2 / V2 Pro).
 *
 * The built-in printer is NOT a Bluetooth or network device — it is reached through Sunmi's own
 * printer service over AIDL, which is why Loyverse lists "SUNMI" as its own printer type. Nothing
 * outside the device can talk to it: no browser, no bridge, no server. Only native code on the
 * terminal itself.
 *
 * **Why LineApi rather than `commandApi().sendEscCommand()`.** The SDK can take raw ESC/POS bytes,
 * which would let us reuse [EscPos] as-is. We deliberately don't: raw bytes depend on the printer
 * firmware carrying a Thai codepage, which is the project's oldest unresolved risk. `LineApi`
 * renders text through the device's own Android font stack instead, and every Sunmi ships with Thai
 * fonts — so Thai should come out right without any codepage guessing. The trade-off is that the
 * font is the device's, not the printer's, so its column width has to be measured rather than
 * derived — see [ReceiptFont] and the calibration rows in `TicketFormatter.testTicket`.
 *
 * Docs: https://docs.sunmi.com/en/general-function-modules/printing-service/
 */
class SunmiPrinterAdapter(
    private val context: Context,
    private val textSize: ReceiptTextSize = ReceiptTextSize.NORMAL,
    private val font: ReceiptFont = ReceiptFont.RECEIPT,
    override val name: String = "เครื่องพิมพ์ในตัว (Sunmi)"
) : Printer {

    private var printer: PrinterSdk.Printer? = null

    /**
     * Binds the AIDL service and keeps the handle. Cheap to call again once bound; the service
     * binding is process-wide, so this is normally done once and the handle reused.
     */
    override suspend fun connect(): Result<Unit> = withContext(Dispatchers.IO) {
        if (printer != null) return@withContext Result.success(Unit)
        runCatching {
            val handle = awaitPrinter()
            checkNotNull(handle) { "ไม่พบเครื่องพิมพ์ในตัว — เครื่องนี้อาจไม่ใช่ Sunmi" }
            printer = handle
        }
    }

    /**
     * The SDK reports the printer through a listener with two callbacks, and on some models both
     * fire. [suspendCancellableCoroutine] only allows one resume, so the first answer wins and the
     * second is dropped.
     */
    private suspend fun awaitPrinter(): PrinterSdk.Printer? = suspendCancellableCoroutine { cont ->
        try {
            PrinterSdk.getInstance().getPrinter(context, object : PrinterSdk.PrinterListen {
                override fun onDefPrinter(defPrinter: PrinterSdk.Printer?) {
                    if (cont.isActive) cont.resume(defPrinter)
                }

                override fun onPrinters(printers: MutableList<PrinterSdk.Printer>?) {
                    if (cont.isActive) cont.resume(printers?.firstOrNull())
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "getPrinter failed", e)
            if (cont.isActive) cont.resume(null)
        }
    }

    override suspend fun print(ticket: Ticket): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (printer == null) connect().getOrThrow()
            val line = checkNotNull(printer).lineApi()

            line.initLine(BaseStyle.getStyle())
            // The ticket may ask for a size of its own — the kitchen slip does. Resolved here,
            // once, so every line and every drawn row in this document agrees about it.
            val ticketFont = ticket.font ?: font
            val dots = (ticketFont.dots + ticket.dotsOffset).coerceAtLeast(8)
            // The drawn rows follow the size as a **ratio** of the font's measured pair, never a
            // re-derived column count: `charsPerLine * fontDots / dots` is integer division, and
            // at −1 dot it rounded 15 straight back to 15, so the rows that are most of the slip
            // printed unchanged. At offset 0 the ratio is exactly 1 and the counted calibration
            // is what draws.
            val ticketRows = ReceiptRowBitmap(
                columns = ticketFont.charsPerLine,
                scale = dots.toFloat() / ticketFont.dots
            )
            ticket.lines.forEach { ticketLine ->
                writeLine(line, ticketLine, ticketRows, dots)
            }
            // V1s/V2/V2 Pro have no cutter — autoOut feeds the paper clear of the head so the
            // last line isn't left inside the mechanism for the operator to tear off blind.
            line.autoOut()
        }
    }

    private fun writeLine(
        line: com.sunmi.printerx.api.LineApi,
        ticketLine: TicketLine,
        rows: ReceiptRowBitmap,
        /** The ticket font's size with the ticket's own offset already applied. */
        ticketDots: Int
    ) {
        when (ticketLine) {
            // No trailing "\n": printText prints a whole line already, and appending one fed a
            // blank line after every row — which is what made the receipt look loosely spaced.
            is TicketLine.Text -> line.printText(
                ticketLine.text,
                style(
                    align = if (ticketLine.center) Align.CENTER else Align.LEFT,
                    bold = ticketLine.bold,
                    doubleWidth = ticketLine.big,
                    tall = ticketLine.big || ticketLine.tall || textSize.allTall,
                    // A number of dots, not another ReceiptFont: the enum's entries carry
                    // column counts somebody measured, and these per-line nudges have none.
                    // Safe because the lines that use them are centred, not padded.
                    dots = (ticketDots + ticketLine.dots).coerceAtLeast(8)
                )
            )

            // Drawn as well, and for the same reason one step further on: printText breaks a
            // line by character in a font with no fixed cell, which on the kitchen slip put a
            // lone "ก" under "ไม่ผั". Measured here in the font that draws it, broken at a
            // space, with a gap narrower than the proportional space the shop found too wide.
            is TicketLine.Wrapped -> rows.wrappedLeft(
                text = ticketLine.text,
                bold = ticketLine.bold,
                tall = textSize.allTall
            ).forEach { line.printBitmap(it, bitmapStyle()) }

            // Drawn, not padded. Four ways of padding a price to the right edge have been tried
            // on this hardware and all four failed, for one underlying reason: nothing here has a
            // fixed cell to pad. The full account is in ReceiptRowBitmap; the short version is that
            // no Sunmi font carries Thai, so Thai always comes from a proportional system face,
            // and raw ESC/POS — which would have had a fixed cell — can't spell Thai on this head
            // at all.
            //
            // So the row is typeset here and handed over as a picture, which also lets it use the
            // whole paper rather than stopping at the column count Thai happened to fit in.
            is TicketLine.KeyValue -> line.printBitmap(
                rows.row(
                    left = ticketLine.left,
                    right = ticketLine.right,
                    bold = ticketLine.bold,
                    leftTail = ticketLine.leftTail,
                    // A single row can't mix sizes, so "big numbers" becomes "the total stands
                    // out": the bold rows are the total and the bill number.
                    tall = textSize.allTall || (textSize.numbersTall && ticketLine.bold)
                ),
                bitmapStyle()
            )

            is TicketLine.QrCode -> line.printQrCode(
                ticketLine.data,
                QrStyle.getStyle()
                    .setDot(ticketLine.moduleSize.coerceIn(1, 16))
                    .setErrorLevel(ErrorLevel.M)
                    .setAlign(Align.CENTER)
            )

            // The queue number. Drawn for the same reason the price rows are — the app can
            // typeset it to a size the printer's own cell cannot reach — and centred by the
            // bitmap's own width, since the picture is already the full width of the paper.
            is TicketLine.Banner -> line.printBitmap(
                rows.banner(ticketLine.text, ticketLine.heightDots),
                bitmapStyle()
            )

            // Drawn too, so a divider and a row end in the same place — printing it as dashes
            // stopped short of the paper edge, which is what the shop noticed.
            TicketLine.Rule -> line.printBitmap(rows.rule(), bitmapStyle())
            TicketLine.Blank -> line.printText(" ", style(align = Align.LEFT, dots = ticketDots))
            // Handled once by autoOut() after the whole ticket, so a multi-part ticket doesn't
            // feed a hand of blank paper between sections.
            TicketLine.FeedAndCut -> Unit
        }
    }

    /**
     * Every style sets the font size explicitly. The SDK's default is larger than any of these,
     * which is what wrapped "ก๋วยเตี๋ยวเรือ สูตรโบราณ" onto two lines on the first real print — the
     * ticket is padded for [ReceiptFont.charsPerLine] columns, so the font has to be the one that
     * actually produces that many.
     */
    private fun style(
        align: Align,
        bold: Boolean = false,
        doubleWidth: Boolean = false,
        tall: Boolean = false,
        dots: Int = font.dots
    ): TextStyle = TextStyle.getStyle()
        .setTextSize(dots)
        .setAlign(align)
        .enableBold(bold)
        .setTextWidthRatio(if (doubleWidth) 2 else 1)
        .setTextHeightRatio(if (tall) 2 else 1)

    /**
     * Typesets the rows this adapter draws. Calibrated to [font] so a drawn row sits among the
     * printed lines at the same size.
     */
    // Built per ticket in print(), because a ticket may ask for a size of its own.

    private fun bitmapStyle(): BitmapStyle = BitmapStyle.getStyle()
        .setAlign(Align.LEFT)
        // Text, not a photograph: a threshold keeps the strokes solid where dithering would
        // speckle them on a 203dpi head.
        .setAlgorithm(ImageAlgorithm.BINARIZATION)

    /**
     * Sends a ticket as raw ESC/POS bytes instead of through [LineApi].
     *
     * The reason to want this: LineApi draws with Android fonts, and no font the print service
     * offers has Thai in it — Thai always falls back to a proportional system face, so a Thai name
     * and a latin one of the same character count are different widths and the price column cannot
     * be padded straight. The printer's own firmware font has no such problem, every cell being the
     * same width, and it fills the paper rather than stopping short of it.
     *
     * The reason it isn't already the default: raw bytes need the firmware to carry a Thai code
     * page, and which index that is on this head is unknown. `ESC t 21` is confirmed on the shop's
     * 80mm network printer and means nothing here. Hence [codepage] — this is for probing.
     */
    suspend fun printRaw(
        ticket: Ticket,
        charsPerLine: Int,
        codepage: Int
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (printer == null) connect().getOrThrow()
            checkNotNull(printer).commandApi().sendEscCommand(
                EscPos.render(
                    ticket,
                    charsPerLine = charsPerLine,
                    textSize = textSize,
                    codepage = codepage,
                    cut = false
                )
            )
        }
    }

    /**
     * Asks the printer how it is, in words a shop can act on. The SDK reports paper-out, an open
     * cover and overheating separately, which is exactly the difference between "add paper" and
     * "something is broken".
     */
    suspend fun status(): String = withContext(Dispatchers.IO) {
        if (printer == null) connect()
        val handle = printer ?: return@withContext "ยังเชื่อมต่อเครื่องพิมพ์ไม่ได้"
        runCatching {
            when (handle.queryApi().getStatus()) {
                Status.READY -> "พร้อมพิมพ์"
                Status.ERR_PAPER_OUT -> "กระดาษหมด"
                Status.WARN_THERMAL_PAPER, Status.WARN_STANDARD_PAPER, Status.WARN_SPECIAL_PAPER ->
                    "กระดาษใกล้หมด"
                Status.ERR_COVER, Status.ERR_COVER_INCOMPLETE -> "ฝาเครื่องพิมพ์ยังไม่ปิดสนิท"
                Status.ERR_PRINTER_HOT, Status.ERR_MOTOR_HOT -> "เครื่องพิมพ์ร้อนเกินไป รอสักครู่"
                Status.ERR_PAPER_JAM -> "กระดาษติด"
                Status.OFFLINE -> "เครื่องพิมพ์ออฟไลน์"
                else -> "สถานะ: ไม่ทราบ"
            }
        }.getOrElse { "อ่านสถานะไม่ได้: ${it.message}" }
    }

    override fun isConnected(): Boolean = printer != null

    companion object {
        private const val TAG = "SunmiPrinter"
    }
}
