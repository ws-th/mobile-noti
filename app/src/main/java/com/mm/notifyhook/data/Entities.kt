package com.mm.notifyhook.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** สถานะการส่งไป webhook — SKIPPED = บันทึกตอนที่ปิด webhook อยู่ จะไม่ถูกส่ง */
enum class WebhookStatus { PENDING, SENT, FAILED, SKIPPED }

data class StatusCount(val status: WebhookStatus, val count: Int)

@Entity(
    tableName = "notifications",
    indices = [Index("packageName"), Index("postedAt"), Index("webhookStatus"), Index("deletedAt")],
)
data class NotificationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val appName: String,
    val title: String?,
    val text: String?,
    val subText: String?,
    val postedAt: Long,
    val notificationKey: String,
    val webhookStatus: WebhookStatus = WebhookStatus.PENDING,
    /** สาเหตุที่ส่งไม่สำเร็จครั้งล่าสุด (null = ยังไม่เคยพลาด หรือส่งสำเร็จแล้ว) */
    val webhookError: String? = null,
    /** เวลาที่ลองส่งครั้งล่าสุด */
    val webhookAttemptAt: Long? = null,
    /** เวลาที่ผู้ใช้กดล้าง (null = ยังแสดงอยู่) — เก็บไว้ในถังขยะ TRASH_RETENTION_DAYS วันแล้วลบถาวร */
    val deletedAt: Long? = null,
)

const val TRASH_RETENTION_DAYS = 7
const val TRASH_RETENTION_MS = TRASH_RETENTION_DAYS * 24L * 60 * 60 * 1000

/** แอปที่ผู้ใช้เลือกให้ดึงแจ้งเตือน (whitelist) */
@Entity(tableName = "app_filters")
data class AppFilterEntity(
    @PrimaryKey val packageName: String,
    val appName: String,
    val enabled: Boolean,
    /** บัญชีรับเงินของแอปธนาคารนี้ — ส่งเป็น accountName / accountNo ใน payload รายการธนาคาร */
    val accountName: String? = null,
    val accountNo: String? = null,
)
