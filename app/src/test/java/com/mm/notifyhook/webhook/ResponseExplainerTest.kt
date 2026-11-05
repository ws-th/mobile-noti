package com.mm.notifyhook.webhook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseExplainerTest {

    /** เนื้อหาที่ได้จริงจากเซิร์ฟเวอร์ปลายทางตอนส่งทดสอบ */
    private val laravel422 = """{"state":"invalid","errors":{"credit":["validation.required"],"datetime":["validation.required"],""" +
        """"ref_id":["validation.required"],"type":["validation.required"],"bankweb_id":["validation.required"]}}"""

    private val allFields = setOf("credit", "datetime", "ref_id", "type", "bankweb_id")

    @Test
    fun validation_errors_are_grouped_and_translated() {
        val points = ResponseExplainer.points(422, laravel422)
        // org.json บน Android คงลำดับ field ตามที่เซิร์ฟเวอร์ส่ง แต่ตัวที่ใช้รัน test ไม่คง จึงเทียบแบบไม่สนลำดับ
        val (reason, fields) = points.first().split(": ")
        assertEquals("ขาดข้อมูลที่ต้องส่ง", reason)
        assertEquals(allFields, fields.split(", ").toSet())
        assertTrue(points.none { it.contains("validation.") })
    }

    @Test
    fun summary_lists_problem_fields() {
        val summary = ResponseExplainer.summary(422, laravel422)
        assertTrue(summary.startsWith("ข้อมูลที่ส่งไม่ครบหรือไม่ตรงกับที่เซิร์ฟเวอร์ต้องการ ("))
        assertEquals(allFields, summary.substringAfter("(").removeSuffix(")").split(", ").toSet())
    }

    @Test
    fun message_and_html_bodies() {
        assertTrue(ResponseExplainer.points(401, """{"message":"Unauthenticated."}""").first().contains("token"))
        assertTrue(ResponseExplainer.points(403, "<!DOCTYPE html><html>").first().contains("หน้าเว็บ"))
        assertEquals(emptyList<String>(), ResponseExplainer.points(200, """{"ok":true}"""))
    }
}
