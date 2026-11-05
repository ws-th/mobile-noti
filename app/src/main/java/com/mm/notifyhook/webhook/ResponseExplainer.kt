package com.mm.notifyhook.webhook

import org.json.JSONArray
import org.json.JSONObject

/** ผลส่ง webhook ในรูปที่คนทั่วไปอ่านเข้าใจ — technical คือข้อมูลดิบไว้ให้นักพัฒนาดู */
data class FriendlyReport(
    val ok: Boolean,
    val headline: String,
    val points: List<String>,
    val technical: String,
)

/** แปลรหัส HTTP และเนื้อหาที่เซิร์ฟเวอร์ตอบกลับเป็นคำอธิบายภาษาไทย */
object ResponseExplainer {

    fun headline(code: Int): String = when (code) {
        in 200..299 -> "ส่งสำเร็จ เซิร์ฟเวอร์รับข้อมูลแล้ว"
        400 -> "เซิร์ฟเวอร์ไม่รับข้อมูล เพราะรูปแบบไม่ถูกต้อง"
        401 -> "ยืนยันตัวตนไม่ผ่าน token ผิดหรือไม่ได้ใส่"
        403 -> "เซิร์ฟเวอร์ไม่อนุญาตให้ส่ง"
        404 -> "ไม่พบ URL นี้บนเซิร์ฟเวอร์"
        405 -> "URL นี้ไม่รับการส่งแบบ POST"
        408 -> "เซิร์ฟเวอร์รอข้อมูลนานเกินไป"
        413 -> "ข้อมูลใหญ่เกินกว่าที่เซิร์ฟเวอร์รับได้"
        415 -> "เซิร์ฟเวอร์ไม่รับข้อมูลแบบ JSON"
        419 -> "เซิร์ฟเวอร์ต้องการ CSRF token"
        422 -> "ข้อมูลที่ส่งไม่ครบหรือไม่ตรงกับที่เซิร์ฟเวอร์ต้องการ"
        429 -> "ส่งถี่เกินไป เซิร์ฟเวอร์ขอให้รอสักครู่"
        500 -> "เซิร์ฟเวอร์ปลายทางเกิดข้อผิดพลาดภายใน"
        502, 504 -> "เซิร์ฟเวอร์ปลายทางไม่ตอบ"
        503 -> "เซิร์ฟเวอร์ปลายทางปิดปรับปรุงหรือรับงานไม่ไหว"
        in 520..530 -> "Cloudflare ติดต่อเซิร์ฟเวอร์ต้นทางไม่ได้"
        in 500..599 -> "เซิร์ฟเวอร์ปลายทางเกิดข้อผิดพลาด"
        else -> "ส่งไม่สำเร็จ"
    }

    /** สิ่งที่ผู้ใช้ควรตรวจหรือทำต่อ ตามรหัส HTTP */
    private fun hint(code: Int): String? = when (code) {
        401 -> "ตรวจ token, ชื่อ header และคำนำหน้า (เช่น Bearer) ให้ตรงกับที่เซิร์ฟเวอร์กำหนด"
        403 -> "token อาจไม่มีสิทธิ์ หรือเซิร์ฟเวอร์/Cloudflare บล็อกการเชื่อมต่อจากมือถือเครื่องนี้"
        404 -> "ตรวจ URL ว่าพิมพ์ถูกต้องครบทุกตัวอักษร"
        405 -> "URL อาจไม่ครบ หรือเซิร์ฟเวอร์เปิดรับเฉพาะ GET"
        419 -> "ฝั่งเซิร์ฟเวอร์ต้องย้าย URL นี้ไปอยู่ใน route แบบ API หรือยกเว้น CSRF ให้ URL นี้"
        422 -> "ต้องปรับรูปแบบข้อมูลที่ส่งให้ตรงกับที่เซิร์ฟเวอร์กำหนด (ดูรายการด้านบน)"
        429 -> "แอปจะลองส่งใหม่อัตโนมัติ"
        in 500..599 -> "ปัญหาอยู่ที่ฝั่งเซิร์ฟเวอร์ แอปจะลองส่งใหม่อัตโนมัติ ถ้ายังไม่หายให้แจ้งผู้ดูแลเซิร์ฟเวอร์"
        else -> null
    }

    /** คำอธิบายเป็นข้อ ๆ จากรหัส HTTP และเนื้อหาที่ตอบกลับ */
    fun points(code: Int, body: String): List<String> = buildList {
        val json = parseJsonObject(body)
        if (json != null) {
            json.optString("message").takeIf { it.isNotBlank() && it != "null" }?.let {
                add("ข้อความจากเซิร์ฟเวอร์: ${translateMessage(it)}")
            }
            json.optJSONObject("errors")?.let { addAll(fieldErrors(it)) }
        } else if (body.trimStart().startsWith("<")) {
            add("เซิร์ฟเวอร์ตอบกลับเป็นหน้าเว็บแทนที่จะเป็น JSON ปกติเกิดจากหน้าบล็อกของ Cloudflare หรือหน้า error ของเว็บ")
        } else if (body.isNotBlank() && code !in 200..299) {
            add("ข้อความจากเซิร์ฟเวอร์: ${body.trim().take(200)}")
        }
        hint(code)?.let { add(it) }
    }

    /** ข้อความสั้นบรรทัดเดียว เช่น "ข้อมูลไม่ครบ: ขาด credit, datetime" */
    fun summary(code: Int, body: String): String {
        val missing = parseJsonObject(body)?.optJSONObject("errors")?.let { errs ->
            errs.keys().asSequence().toList()
        }.orEmpty()
        return headline(code) + if (missing.isNotEmpty()) " (${missing.joinToString(", ")})" else ""
    }

    /** รูปแบบ error ของ Laravel/ทั่วไป: { "errors": { "field": ["validation.required", ...] } } */
    private fun fieldErrors(errors: JSONObject): List<String> {
        val byReason = linkedMapOf<String, MutableList<String>>()
        for (field in errors.keys()) {
            val reasons = when (val v = errors.opt(field)) {
                is JSONArray -> (0 until v.length()).map { v.optString(it) }
                else -> listOf(v.toString())
            }
            reasons.forEach { byReason.getOrPut(translateRule(it)) { mutableListOf() }.add(field) }
        }
        return byReason.map { (reason, fields) -> "$reason: ${fields.joinToString(", ")}" }
    }

    private fun translateRule(raw: String): String {
        val key = raw.removePrefix("validation.").substringBefore('.').lowercase()
        return when {
            raw.startsWith("validation.") -> RULES[key] ?: "ไม่ผ่านเงื่อนไข \"$key\""
            // บางเซิร์ฟเวอร์ส่งเป็นประโยคภาษาอังกฤษ เช่น "The credit field is required."
            raw.contains("is required", ignoreCase = true) -> RULES.getValue("required")
            else -> raw
        }
    }

    private fun translateMessage(msg: String): String = when {
        msg.startsWith("validation.") -> translateRule(msg)
        msg.equals("Unauthenticated.", ignoreCase = true) -> "ยังไม่ได้ยืนยันตัวตน (token ไม่ถูกต้อง)"
        msg.contains("CSRF token mismatch", ignoreCase = true) -> "CSRF token ไม่ตรง"
        msg.contains("Too Many Attempts", ignoreCase = true) -> "ส่งถี่เกินไป"
        else -> msg
    }

    private val RULES = mapOf(
        "required" to "ขาดข้อมูลที่ต้องส่ง",
        "numeric" to "ต้องเป็นตัวเลข",
        "integer" to "ต้องเป็นจำนวนเต็ม",
        "string" to "ต้องเป็นข้อความ",
        "boolean" to "ต้องเป็นจริง/เท็จ",
        "array" to "ต้องเป็นรายการ (array)",
        "date" to "ต้องเป็นวันที่",
        "date_format" to "รูปแบบวันที่ไม่ตรง",
        "in" to "ค่าไม่อยู่ในตัวเลือกที่ยอมรับ",
        "exists" to "ไม่พบค่านี้ในระบบปลายทาง",
        "unique" to "ค่านี้มีอยู่แล้ว (ส่งซ้ำ)",
        "min" to "ค่าน้อยหรือสั้นเกินไป",
        "max" to "ค่ามากหรือยาวเกินไป",
        "email" to "ต้องเป็นอีเมล",
        "regex" to "รูปแบบไม่ถูกต้อง",
    )

    private fun parseJsonObject(body: String): JSONObject? =
        body.trim().takeIf { it.startsWith("{") }?.let { runCatching { JSONObject(it) }.getOrNull() }
}
