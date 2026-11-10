package com.eiamjun.event.printer

/**
 * One interface, two real transports (see BluetoothEscPosPrinter / SunmiPrinterAdapter) —
 * screens and view models depend only on this, never on a specific driver.
 */
interface Printer {
    val name: String
    suspend fun connect(): Result<Unit>
    suspend fun print(ticket: Ticket): Result<Unit>
    fun isConnected(): Boolean
}
