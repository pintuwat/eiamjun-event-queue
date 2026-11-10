package com.eiamjun.event.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eiamjun.event.data.AppSettings
import com.eiamjun.event.data.EventLog
import com.eiamjun.event.data.QueueCounter
import com.eiamjun.event.net.KitchenSync
import com.eiamjun.event.printer.EventTicketFormatter
import com.eiamjun.event.printer.PrinterRouter
import com.eiamjun.event.printer.SunmiPrinterAdapter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val deviceName: String = "",
    val serverUrl: String = "",
    val authUser: String = "",
    val authPassword: String = "",
    val shopName: String = "",
    val shopSubtitle: String = "",
    val merchantQr: String = "",
    val promptPayId: String = "",
    val qrDotSize: Int = 3,
    val queueNumberDots: Int = 0,
    val useSunmiPrinter: Boolean = true,
    val nextQueueNo: Int = 1,
    /** Which numbering round เริ่มนับคิวใหม่ has put the day on. Shown only past the first. */
    val queueSession: Int = 1,
    val connectionResult: String? = null,
    val printerStatus: String? = null,
    val preview: String? = null
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: AppSettings,
    private val sync: KitchenSync,
    private val queueCounter: QueueCounter,
    private val printers: PrinterRouter,
    private val log: EventLog
) : ViewModel() {

    private val _state = MutableStateFlow(read())
    val state: StateFlow<SettingsUiState> = _state

    val logEntries = log.entries

    private fun read() = SettingsUiState(
        deviceName = settings.deviceName,
        serverUrl = settings.serverUrl,
        authUser = settings.authUser,
        authPassword = settings.authPassword,
        shopName = settings.shopName,
        shopSubtitle = settings.shopSubtitle,
        merchantQr = settings.merchantQr,
        promptPayId = settings.promptPayId,
        qrDotSize = settings.qrDotSize,
        queueNumberDots = settings.queueNumberDots,
        useSunmiPrinter = settings.printerKind == AppSettings.PRINTER_SUNMI,
        nextQueueNo = queueCounter.peek().number,
        queueSession = queueCounter.peek().session
    )

    /**
     * Renaming the terminal.
     *
     * ⚠️ It is half of an order's identity on the board — `(day, source, queueNo)` — so renaming
     * mid-service orphans the orders already up there under the old name: the board keeps showing
     * them, and this till's พร้อมส่ง poll stops matching them. Rename before a service, not during
     * one. (Orders already sitting in the outbox keep the name they were rung up under, which is
     * right: the ticket in the customer's hand was printed under it.)
     */
    fun setDeviceName(value: String) {
        settings.deviceName = value
        _state.value = _state.value.copy(deviceName = settings.deviceName)
    }

    fun setServerUrl(value: String) {
        settings.serverUrl = value
        _state.value = _state.value.copy(serverUrl = value)
    }

    fun setAuthUser(value: String) {
        settings.authUser = value
        _state.value = _state.value.copy(authUser = value)
    }

    fun setAuthPassword(value: String) {
        settings.authPassword = value
        _state.value = _state.value.copy(authPassword = value)
    }

    fun setShopName(value: String) {
        settings.shopName = value
        _state.value = _state.value.copy(shopName = value)
    }

    fun setShopSubtitle(value: String) {
        settings.shopSubtitle = value
        _state.value = _state.value.copy(shopSubtitle = value)
    }

    /**
     * Pasting the shop's payment payload.
     *
     * ⚠️ The one field in this app that decides where money lands. It is **not** validated beyond
     * being non-blank on purpose — a validator that rejected a scheme it had not seen would be
     * worse than none — so the rule is the shop's own: paste it, print one receipt, and scan it
     * with a real banking app before taking a single baht. The shop's till learned that a QR can
     * look perfect and resolve to nothing.
     */
    fun setMerchantQr(value: String) {
        settings.merchantQr = value
        _state.value = _state.value.copy(merchantQr = value)
    }

    fun setPromptPayId(value: String) {
        settings.promptPayId = value
        _state.value = _state.value.copy(promptPayId = value)
    }

    fun setQrDotSize(value: Int) {
        settings.qrDotSize = value
        _state.value = _state.value.copy(qrDotSize = settings.qrDotSize)
    }

    fun setQueueNumberDots(value: Int) {
        settings.queueNumberDots = value
        _state.value = _state.value.copy(queueNumberDots = settings.queueNumberDots)
    }

    fun setUseSunmiPrinter(value: Boolean) {
        settings.printerKind = if (value) AppSettings.PRINTER_SUNMI else AppSettings.PRINTER_PREVIEW
        _state.value = _state.value.copy(useSunmiPrinter = value)
    }

    fun testConnection() {
        viewModelScope.launch {
            val result = sync.checkConnection()
            _state.value = _state.value.copy(
                connectionResult = result.getOrElse { "เชื่อมต่อไม่ได้: ${it.message}" }
            )
        }
    }

    /** Sends whatever is still waiting, and says how much went. */
    fun flushOutbox() {
        viewModelScope.launch {
            val sent = runCatching { sync.flush() }.getOrDefault(0)
            _state.value = _state.value.copy(connectionResult = "ส่งขึ้นเซิร์ฟเวอร์ $sent รายการ")
        }
    }

    /**
     * Starts the queue at 1 again.
     *
     * Behind a confirmation on the screen, because what it invalidates is numbers already on paper
     * in customers' hands — and an event can genuinely want it, since two sessions may run on one
     * calendar day and no automatic rule could know which.
     */
    fun resetQueue() {
        queueCounter.reset()
        val ticket = queueCounter.peek()
        _state.value = _state.value.copy(nextQueueNo = ticket.number, queueSession = ticket.session)
        log.info("เริ่มนับคิวใหม่ (รอบที่ ${ticket.session})")
    }

    fun readPrinterStatus() {
        viewModelScope.launch {
            val adapter = printers.create {} as? SunmiPrinterAdapter
            _state.value = _state.value.copy(
                printerStatus = adapter?.status() ?: "ตั้งไว้เป็นโชว์จอ ไม่ได้ใช้เครื่องพิมพ์"
            )
        }
    }

    /**
     * Prints one sample of each document, so the layout can be judged on paper.
     *
     * The queue number's size is the open question — nobody has met this paper yet — and this is
     * what settles it alongside ขนาดเลขคิว rather than a build.
     */
    fun printSamples() {
        viewModelScope.launch {
            var preview: String? = null
            val printer = printers.create { preview = (preview.orEmpty() + "\n" + it) }
            printer.print(
                EventTicketFormatter.queueTicket(
                    shopName = settings.shopName,
                    queueNo = 88,
                    itemCount = 2,
                    dotsOffset = settings.queueNumberDots
                )
            )
            if (preview != null) _state.value = _state.value.copy(preview = preview)
        }
    }

    fun dismissPreview() {
        _state.value = _state.value.copy(preview = null)
    }

    fun dismissMessages() {
        _state.value = _state.value.copy(connectionResult = null, printerStatus = null)
    }
}
