package com.mm.notifyhook.webhook

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mm.notifyhook.NotifyHookApp
import com.mm.notifyhook.data.WebhookStatus
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** ส่งแจ้งเตือนที่สถานะ PENDING ไป webhook ทีละรายการ จนกว่าคิวจะว่าง */
class WebhookWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as NotifyHookApp
        val settings = app.webhookSettings.settings.first()
        if (!settings.isUsable) return Result.success()

        val dao = app.database.notificationDao()
        val filterDao = app.database.appFilterDao()
        while (true) {
            val batch = dao.getByStatus(WebhookStatus.PENDING, BATCH_SIZE)
            if (batch.isEmpty()) return Result.success()

            for (n in batch) {
                val result = WebhookClient.send(settings, WebhookClient.payloadFor(n, filterDao.get(n.packageName)), "n-${n.id}")
                val at = System.currentTimeMillis()
                app.webhookSettings.setLastResult("${now()} · ${n.appName} · ${result.describe()}")

                val retryable = when (result) {
                    is SendResult.Success -> {
                        // เก็บสาเหตุของครั้งที่พลาดก่อนหน้าไว้ (ถ้ามี) เพื่อให้ย้อนดูได้ว่าเคยติดอะไร
                        dao.updateResult(n.id, WebhookStatus.SENT, n.webhookError, at)
                        continue
                    }
                    is SendResult.HttpError -> result.retryable
                    is SendResult.NetworkError -> true
                    // ตั้งค่าผิด: ไม่ต้องลองใหม่ รอผู้ใช้แก้แล้วกดส่งใหม่ (รายการยังเป็น PENDING)
                    is SendResult.ConfigError -> {
                        dao.updateResult(n.id, WebhookStatus.PENDING, "${now()} · ${result.detail()}", at)
                        return Result.failure()
                    }
                }
                Log.w(TAG, "Send failed for #${n.id}: ${result.describe()}")

                if (retryable && runAttemptCount < MAX_ATTEMPTS) {
                    // ยังรอลองใหม่ แต่เก็บสาเหตุไว้ให้กดดูได้ระหว่างรอ
                    dao.updateResult(n.id, WebhookStatus.PENDING, "${now()} · ${result.detail()}", at)
                    return Result.retry()
                }
                // ส่งไม่ผ่านถาวร หรือลองครบแล้ว → ทำเครื่องหมาย FAILED แล้วไปรายการถัดไป
                dao.updateResult(n.id, WebhookStatus.FAILED, "${now()} · ${result.detail()}", at)
            }
        }
    }

    private fun now() = SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()).format(Date())

    companion object {
        private const val TAG = "WebhookWorker"
        private const val WORK_NAME = "webhook-send"
        private const val BATCH_SIZE = 20
        private const val MAX_ATTEMPTS = 8

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<WebhookWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            // APPEND_OR_REPLACE: ถ้ามีงานกำลังรัน ให้ต่อคิวอีกรอบ เพื่อไม่ให้รายการที่เพิ่งเข้ามาตกหล่น
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
