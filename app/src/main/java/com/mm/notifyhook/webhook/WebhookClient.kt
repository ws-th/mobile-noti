package com.mm.notifyhook.webhook

import android.os.Build
import com.mm.notifyhook.BuildConfig
import com.mm.notifyhook.data.AppFilterEntity
import com.mm.notifyhook.data.NotificationEntity
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

sealed interface SendResult {
    data class Success(val code: Int) : SendResult
    data class HttpError(val code: Int, val body: String, val retryable: Boolean) : SendResult
    data class NetworkError(val message: String) : SendResult
    data class ConfigError(val message: String) : SendResult
}

fun SendResult.describe(): String = when (this) {
    is SendResult.Success -> "สำเร็จ (HTTP $code)"
    is SendResult.HttpError -> "HTTP $code ${ResponseExplainer.summary(code, body)}"
    is SendResult.NetworkError -> "เชื่อมต่อไม่ได้: $message"
    is SendResult.ConfigError -> "ตั้งค่าไม่ถูกต้อง: $message"
}

/** รายละเอียดเต็มสำหรับเก็บไว้ดูทีหลังในรายการ (describe() ตัดสั้นไว้แสดงบรรทัดเดียว) */
fun SendResult.detail(): String = when (this) {
    is SendResult.HttpError -> buildString {
        append("HTTP $code ${ResponseExplainer.headline(code)}")
        if (retryable) append(" (ลองใหม่ได้)")
        ResponseExplainer.points(code, body).forEach { append("\n• $it") }
        if (body.isNotBlank()) append("\n\nข้อมูลดิบ:\n${body.take(2000)}")
    }
    else -> describe()
}

object WebhookClient {

    private const val CONNECT_TIMEOUT_S = 15L
    private const val READ_TIMEOUT_S = 45L
    private const val CALL_TIMEOUT_S = 60L

    val USER_AGENT = "NotifyHook/${BuildConfig.VERSION_NAME} (Android)"
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val http = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        // เซิร์ฟเวอร์บางตัวประมวลผลรายการฝากก่อนตอบ จึงเผื่อเวลารอคำตอบไว้นานกว่าเวลาเชื่อมต่อ
        .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_S, TimeUnit.SECONDS)
        .dns(SecureDns)
        .build()

    fun notificationPayload(n: NotificationEntity, event: String = "notification.posted"): JSONObject =
        JSONObject()
            .put("event", event)
            .put("id", n.id)
            .put("app", JSONObject().put("packageName", n.packageName).put("name", n.appName))
            .put("title", n.title ?: JSONObject.NULL)
            .put("text", n.text ?: JSONObject.NULL)
            .put("subText", n.subText ?: JSONObject.NULL)
            .put("postedAt", OffsetDateTime.ofInstant(Instant.ofEpochMilli(n.postedAt), ZoneId.systemDefault()).toString())
            .put("postedAtMillis", n.postedAt)
            .put("notificationKey", n.notificationKey)
            .put(
                "device",
                JSONObject()
                    .put("manufacturer", Build.MANUFACTURER)
                    .put("model", Build.MODEL)
                    .put("androidVersion", Build.VERSION.RELEASE),
            )

    /** แอปที่กรอกบัญชีรับเงินไว้ถือเป็นแอปธนาคาร — แปลงได้ทุกแอป อย่างน้อยก็ได้ยอดเงิน */
    fun bankTransaction(n: NotificationEntity, account: AppFilterEntity?): BankTransaction? =
        BankParser.parse(
            n.packageName, n.id, n.notificationKey, n.text, n.postedAt,
            title = n.title,
            allowGeneric = account?.accountNo != null,
        )

    /**
     * payload ที่ส่งจริงสำหรับแจ้งเตือนนี้ — แอปธนาคารที่แปลงได้จะส่งในรูป
     * { accountName, accountNo, txn: [ ... ] } โดยบัญชีมาจากที่กรอกไว้ในหน้า "เลือกแอป"
     * รูปแบบเดียวกันทุกแอป ต่างกันแค่ช่องที่อ่านจากข้อความไม่ได้จะเป็น null
     * ถ้าหายอดเงินไม่เจอ (เช่นแจ้งเตือนโปรโมชั่น) ส่งข้อมูลดิบตามเดิม
     */
    fun payloadFor(n: NotificationEntity, account: AppFilterEntity?): JSONObject {
        val tx = bankTransaction(n, account) ?: return notificationPayload(n)
        return JSONObject()
            .put("accountName", account?.accountName ?: JSONObject.NULL)
            .put("accountNo", account?.accountNo ?: JSONObject.NULL)
            .put("txn", JSONArray().put(tx.toJson()))
    }

    fun BankTransaction.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("type", type ?: JSONObject.NULL)
        .put("amount", amount)
        .put("fromBankCode", fromBankCode ?: JSONObject.NULL)
        .put("fromAccountNo", fromAccountNo ?: JSONObject.NULL)
        .put("dateTime", dateTime)
        .put("detail", detail)

    fun testPayload(): JSONObject = notificationPayload(
        NotificationEntity(
            id = 0,
            packageName = "com.mm.notifyhook",
            appName = "NotifyHook",
            title = "ทดสอบ webhook",
            text = "ถ้าเห็นข้อความนี้ แปลว่าตั้งค่าถูกต้อง",
            subText = null,
            postedAt = System.currentTimeMillis(),
            notificationKey = "test",
        ),
        event = "notification.test",
    )

    private fun buildRequest(settings: WebhookSettings, payload: JSONObject, deliveryId: String): Request {
        val url = settings.url.trim().toHttpUrlOrNull()
            ?: throw IllegalArgumentException("URL ต้องขึ้นต้นด้วย http:// หรือ https://")

        val body = payload.toString()

        return Request.Builder()
            .url(url)
            .post(body.toRequestBody(JSON))
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            // payload แบบรายการธนาคารไม่มี field "event" จึงใช้ชื่อ event ตามชนิดรายการแทน
            .header("X-NotifyHook-Event", eventName(payload))
            .header("X-NotifyHook-Delivery", deliveryId)
            .header("X-NotifyHook-Timestamp", (System.currentTimeMillis() / 1000).toString())
            .apply {
                if (settings.token.isNotBlank()) {
                    val name = settings.authHeaderName.ifBlank { "Authorization" }
                    val value = if (settings.authScheme.isBlank()) settings.token
                    else "${settings.authScheme} ${settings.token}"
                    header(name, value)
                }
                // เซ็น payload ด้วย HMAC-SHA256 แยก secret จาก token เพื่อให้ฝั่งรับตรวจสอบได้ว่า
                // ข้อมูลมาจากแอปนี้จริงและไม่ถูกแก้ไขระหว่างทาง โดยไม่ต้องรู้ token ที่ใช้ยืนยันตัวตน
                if (settings.hmacSecret.isNotBlank()) {
                    header("X-NotifyHook-Signature", "sha256=${hmacSha256Hex(settings.hmacSecret, body)}")
                }
                // header ที่ผู้ใช้กำหนดเอง ใส่ทีหลังสุดเพื่อให้ override ค่าข้างบนได้
                parseCustomHeaders(settings.customHeaders).forEach { (k, v) -> header(k, v) }
            }
            .build()
    }

    /** เรียกจาก background thread เท่านั้น (blocking) */
    fun send(settings: WebhookSettings, payload: JSONObject, deliveryId: String): SendResult {
        val request = try {
            buildRequest(settings, payload, deliveryId)
        } catch (e: IllegalArgumentException) {
            return SendResult.ConfigError(e.message ?: "header ไม่ถูกต้อง")
        }

        val start = System.nanoTime()
        return try {
            http.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) {
                    SendResult.Success(resp.code)
                } else {
                    val retryable = resp.code == 408 || resp.code == 429 || resp.code >= 500
                    SendResult.HttpError(resp.code, resp.body?.string().orEmpty(), retryable)
                }
            }
        } catch (e: IOException) {
            SendResult.NetworkError(explain(e, request.url.host, (System.nanoTime() - start) / 1_000_000))
        }
    }

    /** แปลง exception ของ OkHttp เป็นข้อความที่บอกได้ว่าติดขั้นไหน (DNS / เชื่อมต่อ / รอคำตอบ) */
    private fun explain(e: IOException, host: String, ms: Long): String {
        val reason = when {
            e is UnknownHostException -> "หา IP ของเซิร์ฟเวอร์ไม่ได้ (DNS)"
            e is SocketTimeoutException && e.message.orEmpty().contains("connect", ignoreCase = true) ->
                "เชื่อมต่อเซิร์ฟเวอร์ไม่ทันใน $CONNECT_TIMEOUT_S วินาที (connect timeout)"
            e is SocketTimeoutException ->
                "เซิร์ฟเวอร์ไม่ตอบภายใน $READ_TIMEOUT_S วินาที (read timeout) — เซิร์ฟเวอร์อาจได้รับข้อมูลไปแล้ว"
            // callTimeout ของ OkHttp โยน InterruptedIOException("timeout")
            e is InterruptedIOException ->
                "ใช้เวลารวมเกิน $CALL_TIMEOUT_S วินาที (call timeout) — เซิร์ฟเวอร์อาจได้รับข้อมูลไปแล้ว"
            e is ConnectException -> "เซิร์ฟเวอร์ปฏิเสธการเชื่อมต่อ"
            e is SSLException -> "ตรวจสอบ SSL/HTTPS ไม่ผ่าน"
            else -> e.javaClass.simpleName
        }
        val dns = SecureDns.describe(host)?.let { "\nDNS: $it" }.orEmpty()
        return "$reason หลัง ${"%.1f".format(ms / 1000.0)} วินาที\n${e.javaClass.simpleName}: ${e.message ?: "-"}$dns"
    }

    /**
     * ส่งทดสอบและคืนรายงาน: สรุปผลที่คนทั่วไปอ่านเข้าใจ พร้อมข้อมูลดิบ (ปลายทาง, header ที่ส่ง
     * โดยปิดบังค่าลับ, payload, สถานะ/header/เนื้อหาที่ตอบกลับ, เวลาที่ใช้) ไว้ให้นักพัฒนาดู
     * เรียกจาก background thread เท่านั้น (blocking)
     */
    fun sendDetailed(settings: WebhookSettings, payload: JSONObject, deliveryId: String): FriendlyReport {
        val request = try {
            buildRequest(settings, payload, deliveryId)
        } catch (e: IllegalArgumentException) {
            val msg = e.message ?: "header ไม่ถูกต้อง"
            return FriendlyReport(false, "ตั้งค่าไม่ถูกต้อง", listOf(msg), msg)
        }

        val sensitive = setOfNotNull(
            "authorization", "x-notifyhook-signature",
            settings.authHeaderName.takeIf { it.isNotBlank() }?.lowercase(),
        )
        val out = StringBuilder()
        out.appendLine("▶ คำขอ")
        out.appendLine("POST ${request.url}")
        request.headers.forEach { (k, v) ->
            out.appendLine("$k: ${if (k.lowercase() in sensitive) mask(v) else v}")
        }
        out.appendLine()
        out.appendLine(runCatching { payload.toString(2) }.getOrDefault(payload.toString()))
        out.appendLine()

        val start = System.nanoTime()
        fun elapsed() = (System.nanoTime() - start) / 1_000_000
        return try {
            http.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val ms = elapsed()
                out.appendLine("◀ ผลตอบกลับ")
                SecureDns.describe(request.url.host)?.let { out.appendLine("DNS: $it") }
                out.appendLine(
                    (if (resp.isSuccessful) "สำเร็จ" else "ไม่สำเร็จ") +
                        " — HTTP ${resp.code} ${resp.message} · ${resp.protocol} · ${ms} ms",
                )
                resp.headers.forEach { (k, v) -> out.appendLine("$k: $v") }
                out.appendLine()
                out.append(if (text.isBlank()) "(ไม่มีเนื้อหา)" else text.take(2000))
                if (text.length > 2000) out.append("\n… (ตัดเหลือ 2000 จาก ${text.length} ตัวอักษร)")
                FriendlyReport(
                    ok = resp.isSuccessful,
                    headline = ResponseExplainer.headline(resp.code),
                    points = ResponseExplainer.points(resp.code, text) +
                        "รหัสตอบกลับ HTTP ${resp.code} · ใช้เวลา ${"%.1f".format(ms / 1000.0)} วินาที",
                    technical = out.toString(),
                )
            }
        } catch (e: IOException) {
            val reason = explain(e, request.url.host, elapsed())
            out.appendLine("◀ ผลตอบกลับ")
            out.append("เชื่อมต่อไม่ได้: $reason")
            FriendlyReport(
                ok = false,
                headline = "เชื่อมต่อเซิร์ฟเวอร์ไม่ได้",
                // บรรทัดแรกของ explain() เป็นภาษาไทยที่อ่านเข้าใจ ที่เหลือเป็นชื่อ exception
                points = listOf(reason.lineSequence().first(), "ตรวจอินเทอร์เน็ตของมือถือ และ URL ว่าถูกต้อง"),
                technical = out.toString(),
            )
        }
    }

    private fun mask(value: String): String =
        if (value.length <= 12) "••••" else value.take(8) + "••••" + value.takeLast(4)

    private fun eventName(payload: JSONObject): String =
        payload.optString("event").ifEmpty {
            // type เป็น null ได้ (แปลงแบบทั่วไป) — optString จะคืน "null" จึงเช็ก isNull ก่อน
            val tx = payload.optJSONArray("txn")?.optJSONObject(0)
            val type = if (tx == null || tx.isNull("type")) "" else tx.optString("type")
            if (type.isEmpty()) "bank.transaction" else "bank.$type"
        }

    /** ค่า hex ของ HMAC-SHA256(secret, message) — ให้ฝั่งเซิร์ฟเวอร์คำนวณด้วยวิธีเดียวกันเพื่อเทียบกับ header ที่ส่งไป */
    private fun hmacSha256Hex(secret: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
