package com.mm.notifyhook.service

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.mm.notifyhook.NotifyHookApp
import com.mm.notifyhook.data.NotificationEntity
import com.mm.notifyhook.data.TRASH_RETENTION_MS
import com.mm.notifyhook.data.WebhookStatus
import com.mm.notifyhook.webhook.WebhookWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class NotificationCaptureService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val app by lazy { application as NotifyHookApp }
    private val db by lazy { app.database }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        if (pkg == packageName) return

        val notification = sbn.notification
        // ข้าม group summary (มักซ้ำกับแจ้งเตือนย่อย) และแจ้งเตือนค้าง เช่น เล่นเพลง/ดาวน์โหลด
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return

        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        if (title.isNullOrBlank() && text.isNullOrBlank()) return

        scope.launch {
            try {
                if (!db.appFilterDao().isEnabled(pkg)) return@launch
                val sendToWebhook = app.webhookSettings.settings.first().isUsable
                db.notificationDao().insert(
                    NotificationEntity(
                        packageName = pkg,
                        appName = appLabel(pkg),
                        title = title,
                        text = text,
                        subText = subText,
                        postedAt = sbn.postTime,
                        notificationKey = sbn.key,
                        webhookStatus = if (sendToWebhook) WebhookStatus.PENDING else WebhookStatus.SKIPPED,
                    )
                )
                if (sendToWebhook) WebhookWorker.enqueue(this@NotificationCaptureService)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save notification from $pkg", e)
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isConnected = true
        scope.launch {
            db.notificationDao().purgeTrashOlderThan(System.currentTimeMillis() - TRASH_RETENTION_MS)
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isConnected = false
        // ขอให้ระบบ bind ใหม่ ถ้าถูกตัดการเชื่อมต่อ
        requestRebind(this)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        pkg
    }

    companion object {
        private const val TAG = "NotificationCapture"

        /** true เมื่อระบบ bind service อยู่ (ใช้ได้ภายใน process เดียวกันเท่านั้น) */
        @Volatile
        var isConnected = false
            private set

        fun requestRebind(context: Context) {
            NotificationListenerService.requestRebind(
                ComponentName(context, NotificationCaptureService::class.java)
            )
        }

        /**
         * ถ้ามีสิทธิ์แต่ listener ไม่ได้เชื่อมต่อ (เช่น หลังอัปเดตแอปหรือถูก force-stop)
         * ให้ปิด-เปิด component เพื่อบังคับให้ระบบ bind ใหม่ — requestRebind อย่างเดียวบางทีไม่พอบน MIUI/HyperOS
         */
        fun ensureConnected(context: Context) {
            if (isConnected || !isPermissionGranted(context)) return
            val component = ComponentName(context, NotificationCaptureService::class.java)
            val pm = context.packageManager
            pm.setComponentEnabledSetting(
                component, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP
            )
            pm.setComponentEnabledSetting(
                component, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP
            )
            requestRebind(context)
        }

        fun isPermissionGranted(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }
}
