package com.eiamjun.event.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything about *this terminal*, in SharedPreferences.
 *
 * None of it is ever pushed to or pulled from the server. The shop's till learned why the hard
 * way: a server address, a password and a device name describe one device, and a password that
 * travels leaks. The event app has no sync for them at all, so there is nothing to get wrong.
 */
@Singleton
class AppSettings @Inject constructor(@ApplicationContext context: Context) {

    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * What this terminal calls itself on the board, and half of an order's identity there —
     * `(day, source, queueNo)`.
     *
     * ⚠️ The default is the literal "pos", **never `Build.MODEL`**. Every Sunmi unit the shop owns
     * reports the same model string, so a model-derived default means a second terminal introduces
     * itself under a name that already means the first one — and since the queue number is issued
     * locally, that collapses two tills' orders onto one set of rows. The shop's POS has exactly
     * this trap written up as a known risk.
     *
     * One terminal needs no change. A second one is a name typed in here before its first launch,
     * which is also what keeps its queue numbers apart.
     */
    var deviceName: String
        get() = prefs.getString(KEY_DEVICE_NAME, DEFAULT_DEVICE_NAME).orEmpty()
        set(value) = prefs.edit().putString(KEY_DEVICE_NAME, value.trim().ifBlank { DEFAULT_DEVICE_NAME }).apply()

    /** Where the kitchen board lives. Blank means "don't push" — the till still works. */
    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value.trim().trimEnd('/')).apply()

    var authUser: String
        get() = prefs.getString(KEY_AUTH_USER, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_AUTH_USER, value.trim()).apply()

    var authPassword: String
        get() = prefs.getString(KEY_AUTH_PASSWORD, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_AUTH_PASSWORD, value).apply()

    /** Printed at the top of both the queue ticket and the receipt. */
    var shopName: String
        get() = prefs.getString(KEY_SHOP_NAME, DEFAULT_SHOP_NAME).orEmpty()
        set(value) = prefs.edit().putString(KEY_SHOP_NAME, value).apply()

    var shopSubtitle: String
        get() = prefs.getString(KEY_SHOP_SUBTITLE, DEFAULT_SUBTITLE).orEmpty()
        set(value) = prefs.edit().putString(KEY_SHOP_SUBTITLE, value).apply()

    /**
     * The shop's own counter-sign payload (ถุงเงิน / any Thai QR Payment merchant QR).
     *
     * ⚠️ Deliberately blank out of the box, and there is no default. It is the string that decides
     * where the money lands, so a wrong one is worse than none — the till simply shows no code
     * until somebody pastes the real one in and scans it once with a banking app.
     */
    var merchantQr: String
        get() = prefs.getString(KEY_MERCHANT_QR, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_MERCHANT_QR, value.trim()).apply()

    /** Fallback when no merchant QR is set. Also blank by default, for the same reason. */
    var promptPayId: String
        get() = prefs.getString(KEY_PROMPTPAY_ID, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_PROMPTPAY_ID, value.trim()).apply()

    /** Dots per QR module on the printed receipt — see TicketLine.QrCode. */
    var qrDotSize: Int
        get() = prefs.getInt(KEY_QR_DOTS, com.eiamjun.event.printer.DEFAULT_QR_DOTS)
        set(value) = prefs.edit().putInt(KEY_QR_DOTS, value.coerceIn(1, 8)).apply()

    /** Sunmi built-in head, or render to the screen for a phone with no printer. */
    var printerKind: String
        get() = prefs.getString(KEY_PRINTER, PRINTER_SUNMI).orEmpty()
        set(value) = prefs.edit().putString(KEY_PRINTER, value).apply()

    /** +0/+1/+2 sp on every size in the app. Screen only — never the printed layout. */
    var textScaleBump: Int
        get() = prefs.getInt(KEY_TEXT_BUMP, 0)
        set(value) = prefs.edit().putInt(KEY_TEXT_BUMP, value.coerceIn(0, 2)).apply()

    /**
     * Dots above or below the queue number's own size on paper, from ตั้งค่า.
     *
     * The one printed size that is a setting, and for the same reason the shop's kitchen slip has
     * one: it is read across a counter by somebody holding it up, nobody has met this paper yet,
     * and the alternative to a control is a build on the morning of the event.
     */
    var queueNumberDots: Int
        get() = prefs.getInt(KEY_QUEUE_DOTS, 0)
        set(value) = prefs.edit().putInt(KEY_QUEUE_DOTS, value.coerceIn(-3, 6)).apply()

    companion object {
        private const val FILE = "eiamjun-event-settings"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_AUTH_USER = "auth_user"
        private const val KEY_AUTH_PASSWORD = "auth_password"
        private const val KEY_SHOP_NAME = "shop_name"
        private const val KEY_SHOP_SUBTITLE = "shop_subtitle"
        private const val KEY_MERCHANT_QR = "merchant_qr"
        private const val KEY_PROMPTPAY_ID = "promptpay_id"
        private const val KEY_QR_DOTS = "qr_dots"
        private const val KEY_PRINTER = "printer_kind"
        private const val KEY_TEXT_BUMP = "text_bump"
        private const val KEY_QUEUE_DOTS = "queue_dots"

        const val PRINTER_SUNMI = "sunmi"
        const val PRINTER_PREVIEW = "preview"

        /** Literal, not Build.MODEL — see [deviceName]. */
        const val DEFAULT_DEVICE_NAME = "pos"

        private const val DEFAULT_SHOP_NAME = "ก๋วยเตี๋ยวคลุกเอี้ยมจุ๊น"
        private const val DEFAULT_SUBTITLE = "ก๋วยเตี๋ยวคลุก · แคปป๊อป"
    }
}
