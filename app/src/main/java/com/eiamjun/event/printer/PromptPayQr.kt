package com.eiamjun.event.printer

import java.util.Locale

/**
 * Builds a Thai PromptPay (EMVCo merchant-presented) QR payload.
 *
 * When an amount is passed the QR is *dynamic*: the customer's banking app opens with the bill
 * total already filled in, so they can't mistype it and nobody has to check the number. Without an
 * amount it's the ordinary static "scan and type the amount yourself" QR.
 *
 * Payload layout (EMVCo TLV, tags in ascending order):
 * ```
 *   00  payload format        "01"
 *   01  point-of-initiation   "11" static / "12" dynamic (amount present)
 *   29  merchant account      00 = AID "A000000677010111", then one of:
 *                             01 = phone (13 digits, 0xxxxxxxxx -> 0066xxxxxxxxx)
 *                             02 = national / tax ID (13-14 digits)
 *                             03 = e-wallet ID (15+ digits)
 *   53  currency             "764" (THB)
 *   54  amount               two decimals, only when dynamic
 *   58  country              "TH"
 *   63  CRC16/XMODEM over everything including the "6304" header, 4 uppercase hex digits
 * ```
 *
 * Verified against the reference implementations linked in the project docs; see
 * PromptPayQrTest for the fixtures.
 */
object PromptPayQr {

    private const val AID = "A000000677010111"

    /**
     * @param target PromptPay ID — a phone number, national/tax ID, or e-wallet ID. Any
     *   punctuation is ignored, so "081-234-5678" is fine.
     * @param amountBaht bill total in whole Baht, or null for a static QR.
     */
    fun payload(target: String, amountBaht: Int? = null): String {
        val (normalized, subTag) = normalizeTarget(target)

        val body = buildString {
            append(tlv("00", "01"))
            append(tlv("01", if (amountBaht != null) "12" else "11"))
            append(tlv("29", tlv("00", AID) + tlv(subTag, normalized)))
            append(tlv("53", "764"))
            if (amountBaht != null) {
                append(tlv("54", String.format(Locale.US, "%.2f", amountBaht.toDouble())))
            }
            append(tlv("58", "TH"))
            append("6304") // CRC tag + length, included in the checksum itself
        }
        return body + String.format(Locale.US, "%04X", crc16(body))
    }

    /**
     * Takes a shop's existing Thai QR Payment payload — the static one a bank prints on a
     * counter-top sign — and returns the same QR with [amountBaht] filled in.
     *
     * This exists because a merchant QR is not something to rebuild from parts. ถุงเงิน's sign
     * carries a merchant reference that is not a PromptPay number at all — nineteen characters of
     * letters and digits — and routes to Alipay, WeChat and cross-border schemes besides.
     * Reconstructing that would mean guessing at the tags that decide where money lands.
     *
     * Editing it instead needs no understanding of the merchant's own tags. EMVCo QR is a flat
     * list of tag-length-value triples, so this walks the top level, flips tag 01 from static to
     * dynamic, inserts tag 54 with the amount, drops the old checksum and recomputes it. Every
     * other tag — whoever's and whatever for — is copied through untouched.
     *
     * @return the edited payload, or null when the input is not a QR payload this can safely edit.
     *   Null means "print the static QR unchanged", never "guess".
     */
    fun withAmount(staticPayload: String, amountBaht: Int): String? {
        val fields = parse(staticPayload.trim()) ?: return null
        if (fields.none { it.first == "29" || it.first == "30" || it.first == "26" }) return null

        val rebuilt = StringBuilder()
        var amountWritten = false
        for ((id, value) in fields) {
            when {
                // 63 is the checksum over everything before it; it is recomputed at the end.
                id == "63" -> continue
                // 11 = static (customer types the amount), 12 = dynamic (amount is in the code).
                id == "01" -> rebuilt.append(tlv("01", "12"))
                else -> {
                    // 54 belongs after the currency and before the country code. Writing it as soon
                    // as a later tag appears keeps the order valid whatever else the payload holds.
                    if (!amountWritten && id.toIntOrNull()?.let { it > 54 } == true) {
                        rebuilt.append(tlv("54", String.format(Locale.US, "%.2f", amountBaht.toDouble())))
                        amountWritten = true
                    }
                    if (id == "54") continue // replace any amount already present
                    rebuilt.append(tlv(id, value))
                }
            }
        }
        if (!amountWritten) {
            rebuilt.append(tlv("54", String.format(Locale.US, "%.2f", amountBaht.toDouble())))
        }
        rebuilt.append("6304")
        return rebuilt.toString() + String.format(Locale.US, "%04X", crc16(rebuilt.toString()))
    }

    /**
     * Splits an EMVCo payload into its top-level id/value pairs, or null if it is malformed.
     * Nested templates (tag 29's own sub-tags, for instance) are left as opaque strings — this only
     * ever needs to reorder the top level.
     */
    internal fun parse(payload: String): List<Pair<String, String>>? {
        val fields = mutableListOf<Pair<String, String>>()
        var i = 0
        while (i < payload.length) {
            if (i + 4 > payload.length) return null
            val id = payload.substring(i, i + 2)
            val length = payload.substring(i + 2, i + 4).toIntOrNull() ?: return null
            val end = i + 4 + length
            if (end > payload.length) return null
            fields += id to payload.substring(i + 4, end)
            i = end
        }
        return fields.takeIf { it.isNotEmpty() }
    }

    /**
     * Returns the digits to embed plus the tag-29 sub-tag identifying what kind of ID it is.
     * The *raw* digit count decides the type, before any padding: a phone is shorter than 13
     * digits, a national/tax ID is 13-14, an e-wallet ID is 15 or more.
     */
    internal fun normalizeTarget(raw: String): Pair<String, String> {
        val digits = raw.filter { it.isDigit() }
        return when {
            digits.length >= 15 -> digits to "03"
            digits.length >= 13 -> digits to "02"
            else -> {
                val international = if (digits.startsWith("0")) "66" + digits.drop(1) else digits
                international.padStart(13, '0') to "01"
            }
        }
    }

    private fun tlv(id: String, value: String): String =
        id + String.format(Locale.US, "%02d", value.length) + value

    /** CRC16/XMODEM — polynomial 0x1021, init 0xFFFF, no final XOR. */
    internal fun crc16(input: String): Int {
        var crc = 0xFFFF
        for (char in input) {
            crc = crc xor ((char.code and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1
                crc = crc and 0xFFFF
            }
        }
        return crc
    }
}
