package com.mm.notifyhook.ui

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.mm.notifyhook.service.NotificationCaptureService

/** เปิดหน้าตั้งค่าของระบบ/ผู้ผลิต — ลองทีละ intent จนกว่าจะเปิดได้ */
object DeviceSettings {

    val isXiaomi: Boolean =
        Build.MANUFACTURER.lowercase() in setOf("xiaomi", "redmi", "poco")

    fun isIgnoringBatteryOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    fun openNotificationAccess(context: Context) = start(
        context,
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
    )

    fun requestIgnoreBatteryOptimizations(context: Context) = start(
        context,
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri(context)),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        appDetails(context),
    )

    fun openXiaomiAutostart(context: Context) = start(
        context,
        Intent().setComponent(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
        ),
        appDetails(context),
    )

    fun openXiaomiBatterySaver(context: Context) = start(
        context,
        Intent().setComponent(
            ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
        )
            .putExtra("package_name", context.packageName)
            .putExtra("package_label", "NotifyHook"),
        appDetails(context),
    )

    fun openAppDetails(context: Context) = start(context, appDetails(context))

    private fun packageUri(context: Context) = Uri.parse("package:${context.packageName}")

    private fun appDetails(context: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context))

    private fun start(context: Context, vararg intents: Intent) {
        for (intent in intents) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
    }
}

data class SetupItem(
    val id: String,
    val title: String,
    val description: String,
    val done: Boolean,
    /** true = แอปตรวจสถานะเองได้, false = ผู้ใช้ต้องกดยืนยันเองว่าทำแล้ว */
    val autoDetected: Boolean,
    val action: ((Context) -> Unit)?,
)

@Stable
class SetupState(private val context: Context) {

    private val prefs = context.getSharedPreferences("setup", Context.MODE_PRIVATE)

    var notificationAccess by mutableStateOf(NotificationCaptureService.isPermissionGranted(context))
        private set
    private var batteryUnrestricted by mutableStateOf(DeviceSettings.isIgnoringBatteryOptimizations(context))
    private var manualDone by mutableStateOf(prefs.getStringSet(KEY_MANUAL, emptySet())!!.toSet())

    fun refresh() {
        notificationAccess = NotificationCaptureService.isPermissionGranted(context)
        batteryUnrestricted = DeviceSettings.isIgnoringBatteryOptimizations(context)
        // ถ้าแอปเคยถูกระบบหรือผู้ใช้ force-stop ตัว listener จะหลุดและไม่กลับมาเอง → ขอให้ระบบ bind ใหม่
        NotificationCaptureService.ensureConnected(context)
    }

    fun setManualDone(id: String, done: Boolean) {
        manualDone = if (done) manualDone + id else manualDone - id
        prefs.edit().putStringSet(KEY_MANUAL, manualDone).apply()
    }

    val items: List<SetupItem>
        get() = buildList {
            add(
                SetupItem(
                    id = "notification_access",
                    title = "อนุญาตให้อ่านการแจ้งเตือน",
                    description = "เปิด Notification access ให้ NotifyHook — ถ้าไม่เปิด แอปจะดึงแจ้งเตือนไม่ได้เลย",
                    done = notificationAccess,
                    autoDetected = true,
                    action = DeviceSettings::openNotificationAccess,
                )
            )
            add(
                SetupItem(
                    id = "battery_optimization",
                    title = "ไม่จำกัดการใช้แบตเตอรี่",
                    description = "กด \"อนุญาต\" เพื่อไม่ให้ระบบหยุดแอปเมื่อปิดหน้าจอ",
                    done = batteryUnrestricted,
                    autoDetected = true,
                    action = DeviceSettings::requestIgnoreBatteryOptimizations,
                )
            )
            if (DeviceSettings.isXiaomi) {
                add(
                    SetupItem(
                        id = "xiaomi_autostart",
                        title = "เปิด Autostart",
                        description = "หา NotifyHook ในรายการแล้วเปิดสวิตช์ เพื่อให้แอปกลับมาทำงานเองหลังรีสตาร์ทเครื่อง",
                        done = "xiaomi_autostart" in manualDone,
                        autoDetected = false,
                        action = DeviceSettings::openXiaomiAutostart,
                    )
                )
                add(
                    SetupItem(
                        id = "xiaomi_battery_saver",
                        title = "Battery saver → No restrictions",
                        description = "ในหน้าที่เปิดขึ้น เลือก \"No restrictions\" (ถ้าไม่เจอ: Battery saver ในหน้าข้อมูลแอป)",
                        done = "xiaomi_battery_saver" in manualDone,
                        autoDetected = false,
                        action = DeviceSettings::openXiaomiBatterySaver,
                    )
                )
            } else {
                add(
                    SetupItem(
                        id = "vendor_battery",
                        title = "ตั้งค่าแบตเตอรี่ของแอป",
                        description = "ในหน้าข้อมูลแอป เข้าเมนูแบตเตอรี่ แล้วเลือก \"ไม่จำกัด\" / \"Unrestricted\"",
                        done = "vendor_battery" in manualDone,
                        autoDetected = false,
                        action = DeviceSettings::openAppDetails,
                    )
                )
            }
            add(
                SetupItem(
                    id = "lock_recents",
                    title = "ล็อกแอปในหน้าแอปล่าสุด",
                    description = "เปิดหน้าแอปล่าสุด (Recents) → กดค้างที่การ์ด NotifyHook → กดรูปกุญแจ 🔒 " +
                        "จะได้ไม่ถูกปิดเมื่อกด \"ล้างทั้งหมด\"",
                    done = "lock_recents" in manualDone,
                    autoDetected = false,
                    action = null,
                )
            )
        }

    private companion object {
        const val KEY_MANUAL = "manual_done"
    }
}

/** สร้าง SetupState และตรวจสถานะใหม่ทุกครั้งที่กลับมาหน้าแอป (เช่น หลังไปหน้าตั้งค่า) */
@Composable
fun rememberSetupState(): SetupState {
    val context = LocalContext.current.applicationContext
    val state = remember { SetupState(context) }
    LifecycleResumeEffect(Unit) {
        state.refresh()
        onPauseOrDispose { }
    }
    return state
}
