package com.mm.notifyhook.webhook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ข้อความตัวอย่างทั้งหมดมาจากแจ้งเตือนจริงบนเครื่อง (โอน 1 บาทจากบัญชีกรุงศรีปลายเลข 2491) */
class BankParserTest {

    private fun parse(pkg: String, text: String, postedAt: Long, id: Long = 1) =
        BankParser.parse(pkg, id, "key-$id", text, postedAt)

    @Test
    fun next_ktb() {
        val t = parse(
            "ktbcs.netbank",
            "ได้รับ +1.00 บาท เข้าบัญชี XXX-X-XX997-1 จากบัญชี กรุงศรี XXX-X-XX249-1",
            1790648977103,
        )!!
        assertEquals("deposit", t.type)
        assertEquals(1.0, t.amount, 0.0)
        assertEquals("X004", t.fromBankCode)
        assertEquals("2491", t.fromAccountNo)
        assertEquals("2026-09-29T02:29:37.103Z", t.dateTime)
    }

    @Test
    fun next_promptpay() {
        val t = parse(
            "ktbcs.netbank",
            "ได้รับ +1.00 บาท เข้าพร้อมเพย์ จากบัญชี กสิกรไทย XXX-X-XX440-4",
            1790648977103,
        )!!
        assertEquals(1.0, t.amount, 0.0)
        assertEquals("X001", t.fromBankCode)
        assertEquals("4404", t.fromAccountNo)
    }

    @Test
    fun ttb_touch() {
        val t = parse(
            "com.TMBTOUCH.PRODUCTION",
            "มีเงิน1.00บ.โอนเข้าบ/ชxx9076 จาก BAY X2491 NORASET SOMMA เหลือ20.53บ.28/09/26@22:12",
            1790608363023,
        )!!
        assertEquals(1.0, t.amount, 0.0)
        assertEquals("X004", t.fromBankCode)
        assertEquals("2491", t.fromAccountNo)
        // เวลาในข้อความ 22:12 น. ไทย = 15:12 UTC
        assertEquals("2026-09-28T15:12:00.000Z", t.dateTime)
    }

    @Test
    fun clicx() {
        val t = parse(
            "com.clicxbank.clicx",
            "ได้รับ +1.00 บาท เข้าบัญชี ••• • ••462 1 จาก กรุงศรีอยุธยา ••• • ••249 1",
            1790605577454,
        )!!
        assertEquals(1.0, t.amount, 0.0)
        assertEquals("X004", t.fromBankCode)
        assertEquals("2491", t.fromAccountNo)
    }

    @Test
    fun short_format_from_spec() {
        val t = parse(
            "ktbcs.netbank",
            "28/09@23:59 30.00 จากKTB/x897689เข้าx268275 ใช้ได้57,781.35บ",
            1790648977103,
        )!!
        assertEquals(30.0, t.amount, 0.0)
        assertEquals("X003", t.fromBankCode)
        assertEquals("897689", t.fromAccountNo)
        assertEquals("2026-09-28T16:59:00.000Z", t.dateTime)
        assertEquals("28/09@23:59 30.00 จากKTB/x897689เข้าx268275 ใช้ได้57,781.35บ", t.detail)
    }

    @Test
    fun amount_with_thousands() {
        val t = parse("ktbcs.netbank", "ได้รับ +12,345.50 บาท เข้าบัญชี XXX-X-XX997-1 จากบัญชี กสิกรไทย XXX-X-XX123-4", 0)!!
        assertEquals(12345.5, t.amount, 0.0)
        assertEquals("X001", t.fromBankCode)
    }

    @Test
    fun id_is_stable_and_prefixed() {
        val text = "ได้รับ +1.00 บาท เข้าบัญชี XXX-X-XX997-1 จากบัญชี กรุงศรี XXX-X-XX249-1"
        val a = parse("ktbcs.netbank", text, 1000, id = 5)!!
        val b = parse("ktbcs.netbank", text, 1000, id = 5)!!
        val c = parse("ktbcs.netbank", text, 1000, id = 6)!!
        assertTrue(a.id, Regex("^MBN[0-9A-F]{32}$").matches(a.id))
        // ส่งซ้ำตอน retry ต้องได้ id เดิม ปลายทางจะได้ตัดรายการซ้ำได้
        assertEquals(a.id, b.id)
        assertNotEquals(a.id, c.id)
    }

    @Test
    fun unknown_bank_gives_null_code_not_wrong_code() {
        val t = parse("ktbcs.netbank", "ได้รับ +1.00 บาท เข้าบัญชี XXX-X-XX997-1 จากบัญชี พร้อมเพย์ XXX-X-XX249-1", 0)!!
        assertNull(t.fromBankCode)
    }

    @Test
    fun unsupported_app_or_text_is_not_converted() {
        assertNull(parse("org.telegram.messenger", "ได้รับ +1.00 บาท เข้าบัญชี X จากบัญชี กรุงศรี X", 0))
        assertNull(parse("ktbcs.netbank", "โปรโมชั่นพิเศษ รับเครดิตเงินคืน", 0))
    }
}

/** แอปที่ไม่รู้จักรูปแบบ แต่ผู้ใช้กรอกบัญชีไว้ — ดึงแค่ยอดเงิน */
class BankParserGenericTest {

    private fun generic(text: String?, title: String? = null, pkg: String = "com.kasikorn.retail.mbanking.wap") =
        BankParser.parse(pkg, 1, "k", text, 1790740035417, title = title, allowGeneric = true)

    @Test
    fun amount_only_other_fields_null() {
        val t = generic("เงินเข้า 1,500.00 บาท บช X-1234 คงเหลือ 20,000.00 บาท")!!
        assertEquals(1500.0, t.amount, 0.0)
        assertEquals("deposit", t.type)
        assertNull(t.fromBankCode)
        assertNull(t.fromAccountNo)
        assertTrue(t.partial)
        assertEquals("2026-09-30T03:47:15.417Z", t.dateTime)
    }

    @Test
    fun skips_balance_and_time() {
        val t = generic("ยอดคงเหลือ 9,999.99 บ. รับโอน 250.50 เวลา 10.47 น.")!!
        assertEquals(250.5, t.amount, 0.0)
    }

    @Test
    fun various_units() {
        assertEquals(1000.0, generic("Received ฿1,000 from SCB")!!.amount, 0.0)
        assertEquals(20.0, generic("THB 20.00 credited")!!.amount, 0.0)
        assertEquals(500.0, generic("มีเงินโอนเข้า 500บ.")!!.amount, 0.0)
    }

    @Test
    fun amount_in_title_only() {
        assertEquals(300.0, generic("กดเพื่อดูรายละเอียด", title = "เงินเข้า 300.00 บาท")!!.amount, 0.0)
    }

    @Test
    fun direction_not_guessed() {
        assertEquals("withdraw", generic("โอนออก 100.00 บาท")!!.type)
        assertNull(generic("รายการ 100.00 บาท")!!.type)
    }

    @Test
    fun no_amount_or_not_allowed_gives_null() {
        assertNull(generic("อัปเดตแอปเวอร์ชันใหม่วันนี้"))
        assertNull(BankParser.parse("com.other", 1, "k", "เงินเข้า 100.00 บาท", 0))
    }

    @Test
    fun known_format_still_wins_over_generic() {
        val t = BankParser.parse(
            "ktbcs.netbank", 1, "k", "ได้รับ +1.00 บาท เข้าบัญชี XXX-X-XX997-1 จากบัญชี กรุงศรี XXX-X-XX249-1", 0,
            allowGeneric = true,
        )!!
        assertEquals("X004", t.fromBankCode)
        assertEquals(false, t.partial)
    }
}
