package com.eiamjun.event.printer

/**
 * Renders a ticket to plain text instead of sending it to hardware, so the whole
 * order → kitchen ticket → receipt flow can be tested on an emulator or a phone with no
 * printer attached. The app falls back to this whenever no real printer is configured.
 */
class PreviewPrinter(
    private val onTicketRendered: (String) -> Unit,
    override val name: String = "ตัวอย่างใบพิมพ์ (ไม่ใช้เครื่องพิมพ์จริง)"
) : Printer {

    override suspend fun connect(): Result<Unit> = Result.success(Unit)

    override suspend fun print(ticket: Ticket): Result<Unit> = runCatching {
        onTicketRendered(asPlainText(ticket))
    }

    override fun isConnected(): Boolean = true

    companion object {
        /** Same layout logic as the ESC/POS renderer, just as text — handy for previews and tests. */
        fun asPlainText(ticket: Ticket, charsPerLine: Int = 32): String = buildString {
            ticket.lines.forEach { line ->
                when (line) {
                    is TicketLine.Text ->
                        appendLine(
                            if (line.center) TicketText.center(line.text, charsPerLine) else line.text
                        )
                    // Wrapped by column count, which is only an approximation of what the Sunmi
                    // measures — close enough for a preview, and the alternative is a preview
                    // that cannot show a wrap at all.
                    is TicketLine.Wrapped ->
                        TicketText.wrapWords(line.text, charsPerLine).forEach { appendLine(it) }
                    is TicketLine.KeyValue ->
                        appendLine(
                            TicketText.padKeyValue(line.left + line.leftTail, line.right, line.columns ?: charsPerLine)
                        )
                    is TicketLine.QrCode -> {
                        appendLine(TicketText.center("[ QR CODE ]", charsPerLine))
                        appendLine(line.data.take(charsPerLine))
                        if (line.data.length > charsPerLine) appendLine(line.data.drop(charsPerLine))
                    }
                    // A preview is text, so "as large as the paper allows" can only be said
                    // rather than shown. Spaced out so it still reads as the loud thing it is.
                    is TicketLine.Banner ->
                        appendLine(TicketText.center(line.text.toCharArray().joinToString(" "), charsPerLine))
                    TicketLine.Rule -> appendLine("-".repeat(charsPerLine))
                    TicketLine.Blank -> appendLine()
                    TicketLine.FeedAndCut -> Unit
                }
            }
        }

    }
}
