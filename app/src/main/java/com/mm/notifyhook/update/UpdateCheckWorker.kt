package com.mm.notifyhook.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mm.notifyhook.NotifyHookApp
import com.mm.notifyhook.ui.MainActivity
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * เช็คอัปเดตเบื้องหลังวันละครั้ง — แอปนี้ทำงานอยู่เบื้องหลังเป็นหลัก คนไม่ค่อยเปิดหน้าแอป
 * เจอเวอร์ชันใหม่ก็ขึ้นแจ้งเตือน กดแล้วเปิดแอปซึ่งจะเช็คซ้ำและถามว่าจะอัปเดตไหม
 * แจ้งเตือนเวอร์ชันเดิมครั้งเดียว ไม่เตือนซ้ำทุกวัน
 */
class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settings = (applicationContext as NotifyHookApp).webhookSettings.settings.first()
        val release = (AppUpdater.check(settings) as? UpdateCheck.Available)?.release ?: return Result.success()

        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_NOTIFIED_CODE, 0) >= release.versionCode) return Result.success()

        if (notify(release)) prefs.edit().putInt(KEY_NOTIFIED_CODE, release.versionCode).apply()
        return Result.success()
    }

    private fun notify(release: AppRelease): Boolean {
        val ctx = applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false

        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "อัปเดตแอป", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_CHECK_UPDATE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("มี NotifyHook เวอร์ชันใหม่ v${release.versionName}")
            .setContentText(release.notes.ifBlank { "แตะเพื่ออัปเดต" })
            .setStyle(NotificationCompat.BigTextStyle().bigText(release.notes.ifBlank { "แตะเพื่ออัปเดต" }))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        return runCatching { NotificationManagerCompat.from(ctx).notify(NOTIFICATION_ID, notification) }.isSuccess
    }

    companion object {
        private const val WORK_NAME = "app-update-check"
        private const val CHANNEL_ID = "app_update"
        private const val NOTIFICATION_ID = 7001
        private const val PREFS = "app_update"
        private const val KEY_NOTIFIED_CODE = "notified_version_code"

        fun schedule(context: Context) {
            if (!AppUpdater.supported) return
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            // KEEP: ไม่รีเซ็ตรอบทุกครั้งที่เปิดแอป
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
