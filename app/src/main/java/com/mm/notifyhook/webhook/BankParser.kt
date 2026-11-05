package com.mm.notifyhook.webhook

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/** ข้อมูลธนาคารตามตารางอ้างอิง — xCode คือค่าที่ส่งเป็น fromBankCode */
data class BankInfo(val nameTh: String, val shortCode: String, val code: String, val xCode: String?)

/** รายการเงินที่แยกได้จากข้อความแจ้งเตือนของแอปธนาคาร */
data class BankTransaction(
    val id: String,
    /** deposit / withdraw — null ถ้าแยกไม่ออกจากข้อความ (แปลงแบบทั่วไป) */
    val type: String?,
    val amount: Double,
    val fromBankCode: String?,
    val fromAccountNo: String?,
    /** ISO 8601 เวลา UTC เช่น 2026-09-28T16:59:00.000Z */
    val dateTime: String,
    val detail: String,
    /** true = ไม่รู้จักรูปแบบข้อความ ได้มาแค่ยอดเงิน ช่องอื่นเป็น null */
    val partial: Boolean = false,
)

object BankParser {

    /** แอปธนาคารที่รองรับการแปลง (package ตรวจจากเครื่องจริง) */
    val SUPPORTED_PACKAGES = setOf(
        "ktbcs.netbank",            // กรุงไทย NEXT
        "com.TMBTOUCH.PRODUCTION",  // ttb touch
        "com.clicxbank.clicx",      // CLICX
    )

    val BANKS = listOf(
        BankInfo("ธนาคารกสิกรไทย", "KBANKS", "004", "X001"),
        BankInfo("ธนาคารกรุงเทพ", "BBL", "002", "X002"),
        BankInfo("ธนาคารกรุงศรีอยุธยา", "BAY", "025", "X004"),
        BankInfo("ธนาคารไทยพาณิชย์", "SCB", "014", "X007"),
        BankInfo("ธนาคารกรุงไทย", "KTB", "006", "X003"),
        BankInfo("ธนาคารนครหลวงไทย", "SCIB", "017", null),
        BankInfo("ธนาคารยูโอบี", "UOB", "024", "X008"),
        BankInfo("ธนาคารทหารไทยธนชาต", "TTB", "011", "X006"),
        BankInfo("ธนาคารทิสโก้", "TISCO", "067", "X018"),
        BankInfo("ธนาคารไอซีบีซี (ไทย)", "ICBC", "070", "X019"),
        BankInfo("ธนาคารเกียรตินาคิน", "Kiatnakin", "069", "X012"),
        BankInfo("ธนาคารสแตนดาร์ดชาร์เตอร์ดไทย", "SCBI", "020", "X010"),
        BankInfo("ธนาคารอาคารสงเคราะห์", "GHB", "033", "X014"),
        BankInfo("ธนาคารแลนด์ แอนด์ เฮาส์", "LHBANKS", "073", "X009"),
        BankInfo("ธนาคารออมสิน", "GSB", "030", "X011"),
        BankInfo("ธนาคารเพื่อการเกษตรและสหกรณ์การเกษตร (ธกส)", "BAAC", "034", "X015"),
        BankInfo("ธนาคารอิสลามแห่งประเทศไทย", "iBanks", "066", "X017"),
        BankInfo("ธนาคารซีไอเอ็มบีไทย", "CIMB", "022", "X005"),
        BankInfo("ทรูวอลเล็ท", "TRUE", "999", "X999"),
        BankInfo("เกทเวย์", "PGW", "888", "X888"),
        BankInfo("ธนาคารคลิกซ์", "CLCX", "088", "X028"),
    )

    /** ชื่อ/ตัวย่อที่แต่ละแอปธนาคารใช้เรียกธนาคารต้นทาง → shortCode ในตาราง */
    private val ALIASES: Map<String, String> = buildMap {
        fun add(shortCode: String, vararg names: String) = names.forEach { put(it.uppercase(), shortCode) }
        add("KBANKS", "KBANKS", "KBANK", "KBNK", "กสิกรไทย", "กสิกร")
        add("BBL", "BBL", "กรุงเทพ")
        add("BAY", "BAY", "กรุงศรีอยุธยา", "กรุงศรี")
        add("SCB", "SCB", "ไทยพาณิชย์")
        add("KTB", "KTB", "กรุงไทย")
        add("SCIB", "SCIB", "นครหลวงไทย")
        add("UOB", "UOB", "UOBT", "ยูโอบี")
        add("TTB", "TTB", "TMB", "TBANK", "ทหารไทยธนชาต", "ทหารไทย", "ทีทีบี", "ธนชาต")
        add("TISCO", "TISCO", "ทิสโก้")
        add("ICBC", "ICBC", "ไอซีบีซี")
        add("Kiatnakin", "KIATNAKIN", "KKP", "เกียรตินาคิน")
        add("SCBI", "SCBI", "SCBT", "สแตนดาร์ดชาร์เตอร์ด")
        add("GHB", "GHB", "ธอส", "อาคารสงเคราะห์")
        add("LHBANKS", "LHBANKS", "LHBANK", "LHB", "แลนด์ แอนด์ เฮาส์", "แลนด์แอนด์เฮาส์")
        add("GSB", "GSB", "ออมสิน")
        add("BAAC", "BAAC", "ธ.ก.ส.", "ธกส")
        add("iBanks", "IBANKS", "IBANK", "อิสลาม")
        add("CIMB", "CIMB", "CIMBT", "ซีไอเอ็มบี")
        add("TRUE", "TRUE", "TRUEMONEY", "ทรูมันนี่", "ทรูวอลเล็ท")
        add("PGW", "PGW")
        add("CLCX", "CLCX", "CLICX", "คลิกซ์")
    }
    private val ALIASES_LONGEST_FIRST = ALIASES.keys.sortedByDescending { it.length }

    private val BANGKOK: ZoneId = ZoneId.of("Asia/Bangkok")
    private val OUTPUT_TIME: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    private const val AMOUNT = """([\d,]+\.\d{2})"""

    // NEXT / CLICX: "ได้รับ +1.00 บาท เข้าบัญชี XXX-X-XX997-1 จากบัญชี กรุงศรี XXX-X-XX249-1"
    // รับผ่านพร้อมเพย์:  "ได้รับ +1.00 บาท เข้าพร้อมเพย์ จากบัญชี กสิกรไทย XXX-X-XX440-4"
    private val RECEIVED = Regex("""ได้รับ\s*\+?$AMOUNT\s*บาท\s*เข้า(?:บัญชี|พร้อมเพย์).*?จาก(?:บัญชี)?\s*(.+)$""")

    // ttb touch: "มีเงิน1.00บ.โอนเข้าบ/ชxx9076 จาก BAY X2491 NORASET SOMMA เหลือ20.53บ.28/09/26@22:12"
    private val TTB = Regex("""มีเงิน\s*$AMOUNT\s*บ\.?\s*โอนเข้า.*?จาก\s*(.+?)(?:\s*เหลือ.*)?$""")
    private val TTB_TIME = Regex("""(\d{2})/(\d{2})/(\d{2})@(\d{2}):(\d{2})""")

    // รูปแบบย่อ: "28/09@23:59 30.00 จากKTB/x897689เข้าx268275 ใช้ได้57,781.35บ"
    private val SHORT = Regex("""(\d{2})/(\d{2})@(\d{2}):(\d{2})\s+$AMOUNT\s*จาก([^/\s]+)/\s*(\S+?)เข้า""")

    fun isSupportedApp(packageName: String) = packageName in SUPPORTED_PACKAGES

    fun bankByShortCode(shortCode: String): BankInfo? = BANKS.firstOrNull { it.shortCode == shortCode }

    /**
     * แปลงแจ้งเตือนเป็นรายการเงิน
     * - แอปที่รองรับ + ข้อความตรงรูปแบบที่รู้จัก → ได้ข้อมูลครบ
     * - [allowGeneric] (แอปที่ผู้ใช้กรอกบัญชีรับเงินไว้) → ถ้าไม่รู้จักรูปแบบ ดึงแค่ยอดเงิน ช่องอื่นเป็น null
     * คืน null ถ้าหายอดเงินไม่เจอ (ผู้เรียกจะส่งข้อมูลดิบแทน)
     */
    fun parse(
        packageName: String,
        notificationId: Long,
        notificationKey: String,
        text: String?,
        postedAt: Long,
        title: String? = null,
        allowGeneric: Boolean = false,
    ): BankTransaction? {
        // รูปแบบเดียวกับ id ในระบบปลายทาง (TXN + hex 32 ตัว) แต่ใช้ prefix MBN — คงเดิมทุกครั้งที่ส่งซ้ำ
        val id = "MBN" + UUID.nameUUIDFromBytes("$notificationId|$notificationKey|$postedAt".toByteArray())
            .toString().replace("-", "").uppercase()
        if (packageName in SUPPORTED_PACKAGES && !text.isNullOrBlank()) {
            parseKnown(id, text.trim(), postedAt)?.let { return it }
        }
        return if (allowGeneric) parseGeneric(id, title, text, postedAt) else null
    }

    private fun parseKnown(id: String, clean: String, postedAt: Long): BankTransaction? {

        SHORT.find(clean)?.let { m ->
            val (day, month, hour, minute, amount, bank, account) = m.destructured
            val year = Instant.ofEpochMilli(postedAt).atZone(BANGKOK).year
            val time = bangkokToUtc(year, month.toInt(), day.toInt(), hour.toInt(), minute.toInt()) ?: utc(postedAt)
            return deposit(id, amount, matchBank(bank), account, time, clean)
        }

        RECEIVED.find(clean)?.let { m ->
            val (amount, rest) = m.destructured
            val bank = matchBankPrefix(rest)
            // ส่วนที่เหลือหลังชื่อธนาคารคือเลขบัญชีที่ถูกบังไว้ทั้งหมด
            val account = bank?.let { rest.trim().substring(it.second) } ?: rest
            return deposit(id, amount, bank?.first, account, utc(postedAt), clean)
        }

        TTB.find(clean)?.let { m ->
            val (amount, rest) = m.destructured
            val bank = matchBankPrefix(rest)
            // ttb ตามด้วยชื่อผู้โอน จึงเอาเฉพาะคำแรกหลังชื่อธนาคารเป็นเลขบัญชี
            val account = bank?.let { rest.trim().substring(it.second).trim().substringBefore(' ') }
            val time = TTB_TIME.find(clean)?.destructured?.let { (d, mo, y, h, mi) ->
                bangkokToUtc(2000 + y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt())
            } ?: utc(postedAt)
            return deposit(id, amount, bank?.first, account, time, clean)
        }

        return null
    }

    // ยอดเงินที่มีหน่วยกำกับ: "1,234.50 บาท", "500บ.", "฿1,000", "THB 20.00"
    private const val NUM = """(\d{1,3}(?:,\d{3})+(?:\.\d{1,2})?|\d+(?:\.\d{1,2})?)"""
    private val AMOUNT_WITH_UNIT = Regex("""(?:฿|THB)\s*$NUM|$NUM\s*(?:บาท|บ\.|บ(?![ก-๙])|THB|฿)""", RegexOption.IGNORE_CASE)
    // ไม่มีหน่วย แต่มีทศนิยม 2 ตำแหน่ง เช่น "ยอด 30.00" — ตัดเวลาแบบ "10.47 น." ทิ้ง
    private val AMOUNT_DECIMAL = Regex("""(?<![\d.,])(\d{1,3}(?:,\d{3})+\.\d{2}|\d+\.\d{2})(?![\d.]|\s*น\.)""")
    // ตัวเลขที่ตามหลังคำเหล่านี้คือยอดคงเหลือ ไม่ใช่ยอดของรายการ
    private val BALANCE_BEFORE = Regex("""(?:คงเหลือ|เหลือ|ใช้ได้|ยอดเงินในบัญชี|balance|available|bal\.?)\s*:?\s*[+]?\s*(?:฿|THB)?\s*$""", RegexOption.IGNORE_CASE)

    private val INCOMING = Regex("""เงินเข้า|โอนเข้า|เข้าบัญชี|เข้าพร้อมเพย์|ได้รับ|รับโอน|รับเงิน|received|deposit|incoming|credited""", RegexOption.IGNORE_CASE)
    private val OUTGOING = Regex("""เงินออก|โอนออก|โอนเงินไป|ถอน|จ่าย|ชำระ|หักบัญชี|ตัดบัญชี|withdraw|debited|paid|payment|transfer to""", RegexOption.IGNORE_CASE)

    /**
     * ไม่รู้จักรูปแบบข้อความ — ดึงแค่ยอดเงิน ส่วนธนาคาร/บัญชีต้นทางเป็น null
     * ห้ามหยิบเลขบัญชีในข้อความมาใส่ fromAccountNo: บางแอป (เช่น MyMo) แสดงเลขบัญชีปลายทาง (บัญชีเรา) ไม่ใช่ต้นทาง
     */
    private fun parseGeneric(id: String, title: String?, text: String?, postedAt: Long): BankTransaction? {
        val full = listOfNotNull(title?.trim(), text?.trim()).filter { it.isNotEmpty() }.joinToString(" ")
        // หาในเนื้อหาก่อน (ยอดของรายการมักอยู่ในนั้น) แล้วค่อยรวมหัวข้อ
        val amount = listOfNotNull(text, full).firstNotNullOfOrNull { findAmount(it) } ?: return null
        val incoming = INCOMING.containsMatchIn(full)
        val outgoing = OUTGOING.containsMatchIn(full)
        return BankTransaction(
            id = id,
            // บอกชนิดรายการเฉพาะเมื่อข้อความชี้ชัดทางเดียว ไม่เดาให้
            type = when {
                incoming && !outgoing -> "deposit"
                outgoing && !incoming -> "withdraw"
                else -> null
            },
            amount = amount,
            fromBankCode = null,
            fromAccountNo = null,
            dateTime = utc(postedAt),
            detail = (text?.trim()?.ifEmpty { null } ?: full),
            partial = true,
        )
    }

    private fun findAmount(s: String): Double? {
        for (re in listOf(AMOUNT_WITH_UNIT, AMOUNT_DECIMAL)) {
            for (m in re.findAll(s)) {
                if (BALANCE_BEFORE.containsMatchIn(s.substring(0, m.range.first))) continue
                val raw = m.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: continue
                val value = raw.replace(",", "").toDoubleOrNull() ?: continue
                if (value > 0) return value
            }
        }
        return null
    }

    private fun deposit(id: String, amount: String, bankShortCode: String?, account: String?, time: String, detail: String) =
        BankTransaction(
            id = id,
            type = "deposit",
            amount = amount.replace(",", "").toDouble(),
            fromBankCode = bankShortCode?.let { bankByShortCode(it)?.xCode },
            fromAccountNo = account?.filter { it.isDigit() }?.ifEmpty { null },
            dateTime = time,
            detail = detail,
        )

    /** ตัวย่อทั้งคำ เช่น "KTB" */
    private fun matchBank(token: String): String? = ALIASES[token.trim().uppercase()]

    /** หาชื่อธนาคารที่ขึ้นต้นข้อความ — คืน (shortCode, ความยาวที่ใช้ไป) */
    private fun matchBankPrefix(rest: String): Pair<String, Int>? {
        val s = rest.trim()
        val upper = s.uppercase()
        for (alias in ALIASES_LONGEST_FIRST) {
            if (!upper.startsWith(alias)) continue
            // ตัวย่อภาษาอังกฤษต้องจบคำ กัน "SCB" ไปจับ "SCBT"
            val next = upper.getOrNull(alias.length)
            if (alias.first().isLatinLetter() && next != null && next.isLatinLetter()) continue
            return ALIASES.getValue(alias) to alias.length
        }
        return null
    }

    private fun Char.isLatinLetter() = this in 'A'..'Z' || this in 'a'..'z'

    private fun utc(millis: Long): String = OUTPUT_TIME.format(Instant.ofEpochMilli(millis))

    private fun bangkokToUtc(year: Int, month: Int, day: Int, hour: Int, minute: Int): String? = try {
        OUTPUT_TIME.format(LocalDateTime.of(year, month, day, hour, minute).atZone(BANGKOK).toInstant())
    } catch (_: java.time.DateTimeException) {
        null
    }
}
