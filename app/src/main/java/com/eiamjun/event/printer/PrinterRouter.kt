package com.eiamjun.event.printer

import android.content.Context
import com.eiamjun.event.data.AppSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hands out the printer this device is set up for, so view models never name a driver.
 *
 * Created fresh per use rather than held: the SDK's service binding is process-wide, so a new
 * instance costs nothing, and it keeps a stale handle from outliving a settings change.
 */
@Singleton
class PrinterRouter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: AppSettings
) {
    /**
     * @param onPreview receives the rendered ticket when this device has no real printer — on a
     *   phone or an emulator, where the whole flow still has to be walkable.
     */
    fun create(onPreview: (String) -> Unit): Printer = when (settings.printerKind) {
        AppSettings.PRINTER_SUNMI -> SunmiPrinterAdapter(context)
        else -> PreviewPrinter(onTicketRendered = onPreview)
    }
}
