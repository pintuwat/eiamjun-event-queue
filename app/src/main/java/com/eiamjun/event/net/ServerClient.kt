package com.eiamjun.event.net

import com.eiamjun.event.data.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The only thing in this app that speaks HTTP.
 *
 * `HttpURLConnection` and nothing else: the APK is side-loaded onto a 1GB handheld, and a client
 * library would be the largest dependency in the build to make three calls.
 *
 * **Basic auth, never a cookie.** The kitchen board's browser logs in and gets a session; the
 * terminal sends a header on every request and never sees a login page — the same split the shop's
 * server already draws, and the reason that server sends no `WWW-Authenticate`.
 */
@Singleton
class ServerClient @Inject constructor(private val settings: AppSettings) {

    /** Blank server address means "this terminal does not push" — not an error. */
    val configured: Boolean get() = settings.serverUrl.isNotBlank()

    suspend fun post(path: String, body: String): Result<JSONObject> = request("POST", path, body)

    suspend fun get(path: String): Result<JSONObject> = request("GET", path, null)

    private suspend fun request(
        method: String,
        path: String,
        body: String?
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        val base = settings.serverUrl
        if (base.isBlank()) return@withContext Result.failure(NotConfigured())
        runCatching {
            val connection = (URL(base + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("accept", "application/json")
                credentials()?.let { setRequestProperty("authorization", it) }
                if (body != null) {
                    doOutput = true
                    setRequestProperty("content-type", "application/json; charset=utf-8")
                }
            }
            try {
                body?.let { connection.outputStream.use { out -> out.write(it.toByteArray()) } }
                val code = connection.responseCode
                val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()?.use(BufferedReader::readText)
                    .orEmpty()
                if (code !in 200..299) {
                    // The server's own Thai message when it sent one — "ต้องเข้าสู่ระบบ" tells the
                    // staff to check the password, where "HTTP 401" tells them nothing.
                    val message = runCatching { JSONObject(text).optString("error") }.getOrNull()
                    throw ServerError(code, message?.takeIf { it.isNotBlank() } ?: "HTTP $code")
                }
                if (text.isBlank()) JSONObject() else JSONObject(text)
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun credentials(): String? {
        val user = settings.authUser
        if (user.isBlank()) return null
        val raw = "$user:${settings.authPassword}".toByteArray()
        // Base64 without padding-free wrapping; API 23 has android.util.Base64, and java.util's
        // encoder arrived in API 26 — so the Android one is the portable choice here.
        return "Basic " + android.util.Base64.encodeToString(raw, android.util.Base64.NO_WRAP)
    }

    class NotConfigured : Exception("ยังไม่ได้ตั้งที่อยู่เซิร์ฟเวอร์")
    class ServerError(val code: Int, override val message: String) : Exception(message)

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 8_000
    }
}
