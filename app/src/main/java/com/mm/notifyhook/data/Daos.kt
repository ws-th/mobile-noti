package com.mm.notifyhook.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface NotificationDao {
    @Insert
    suspend fun insert(notification: NotificationEntity): Long

    @Query("SELECT * FROM notifications WHERE deletedAt IS NULL ORDER BY postedAt DESC LIMIT :limit")
    fun observeLatest(limit: Int = 500): Flow<List<NotificationEntity>>

    @Query("SELECT * FROM notifications WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC, postedAt DESC")
    fun observeTrash(): Flow<List<NotificationEntity>>

    /** ใช้ในเฟส webhook: ดึงรายการที่ยังไม่ได้ส่ง */
    @Query("SELECT * FROM notifications WHERE webhookStatus = :status ORDER BY postedAt ASC LIMIT :limit")
    suspend fun getByStatus(status: WebhookStatus, limit: Int): List<NotificationEntity>

    @Query("UPDATE notifications SET webhookStatus = :status WHERE id IN (:ids)")
    suspend fun updateStatus(ids: List<Long>, status: WebhookStatus)

    @Query("UPDATE notifications SET webhookStatus = :status, webhookError = :error, webhookAttemptAt = :at WHERE id = :id")
    suspend fun updateResult(id: Long, status: WebhookStatus, error: String?, at: Long)

    @Query("SELECT webhookStatus AS status, COUNT(*) AS count FROM notifications GROUP BY webhookStatus")
    fun observeStatusCounts(): Flow<List<StatusCount>>

    @Query("UPDATE notifications SET webhookStatus = 'PENDING' WHERE webhookStatus = 'FAILED'")
    suspend fun requeueFailed(): Int

    /** ล้างรายการ = ย้ายไปถังขยะ (ยังไม่ลบจริง) */
    @Query("UPDATE notifications SET deletedAt = :now WHERE deletedAt IS NULL")
    suspend fun moveAllToTrash(now: Long): Int

    @Query("UPDATE notifications SET deletedAt = :now WHERE id = :id")
    suspend fun moveToTrash(id: Long, now: Long)

    @Query("UPDATE notifications SET deletedAt = NULL WHERE id = :id")
    suspend fun restore(id: Long)

    /** เลิกทำการล้างครั้งล่าสุด (ทุกรายการที่ถูกล้างพร้อมกันจะมี deletedAt เท่ากัน) */
    @Query("UPDATE notifications SET deletedAt = NULL WHERE deletedAt = :clearedAt")
    suspend fun restoreCleared(clearedAt: Long)

    @Query("DELETE FROM notifications WHERE id = :id AND deletedAt IS NOT NULL")
    suspend fun deleteForever(id: Long)

    @Query("DELETE FROM notifications WHERE deletedAt IS NOT NULL")
    suspend fun emptyTrash()

    @Query("DELETE FROM notifications WHERE deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun purgeTrashOlderThan(cutoff: Long): Int
}

@Dao
interface AppFilterDao {
    @Query("SELECT * FROM app_filters")
    fun observeAll(): Flow<List<AppFilterEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM app_filters WHERE packageName = :packageName AND enabled = 1)")
    suspend fun isEnabled(packageName: String): Boolean

    @Query("SELECT * FROM app_filters WHERE packageName = :packageName")
    suspend fun get(packageName: String): AppFilterEntity?

    @Upsert
    suspend fun upsert(filter: AppFilterEntity)
}
