package com.mm.notifyhook.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mm.notifyhook.NotifyHookApp
import com.mm.notifyhook.data.AppFilterEntity
import com.mm.notifyhook.data.NotificationEntity
import com.mm.notifyhook.data.TRASH_RETENTION_MS
import com.mm.notifyhook.data.WebhookStatus
import com.mm.notifyhook.webhook.BankParser
import com.mm.notifyhook.webhook.FriendlyReport
import com.mm.notifyhook.webhook.WebhookClient
import com.mm.notifyhook.webhook.WebhookSettings
import com.mm.notifyhook.webhook.WebhookWorker
import com.mm.notifyhook.webhook.describe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class InstalledApp(
    val packageName: String,
    val appName: String,
    val enabled: Boolean,
    val accountName: String? = null,
    val accountNo: String? = null,
) {
    val isSupportedBank: Boolean get() = packageName in BankParser.SUPPORTED_PACKAGES
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val db = (app as NotifyHookApp).database

    private val rawNotifications: StateFlow<List<NotificationEntity>> = db.notificationDao()
        .observeLatest()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val notificationQuery = MutableStateFlow("")

    /** null = ไม่กรอง (แสดงทุกแอป) */
    val selectedAppFilter = MutableStateFlow<String?>(null)

    val notifications: StateFlow<List<NotificationEntity>> = combine(
        rawNotifications,
        notificationQuery,
        selectedAppFilter,
    ) { list, query, pkg ->
        list.filter { n ->
            (pkg == null || n.packageName == pkg) &&
                (query.isBlank() ||
                    n.appName.contains(query, ignoreCase = true) ||
                    n.title?.contains(query, ignoreCase = true) == true ||
                    n.text?.contains(query, ignoreCase = true) == true)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** แอปที่มีแจ้งเตือนอยู่จริง ใช้โชว์เป็น chip ตัวกรอง */
    val notificationAppOptions: StateFlow<List<Pair<String, String>>> = rawNotifications
        .map { list -> list.distinctBy { it.packageName }.map { it.packageName to it.appName } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val installedApps = MutableStateFlow<List<Pair<String, String>>?>(null)
    val appQuery = MutableStateFlow("")

    /** null = ยังโหลดรายชื่อแอปไม่เสร็จ */
    val apps: StateFlow<List<InstalledApp>?> = combine(
        installedApps,
        db.appFilterDao().observeAll(),
        appQuery,
    ) { installed, filters, query ->
        installed ?: return@combine null
        val byPkg = filters.associateBy { it.packageName }
        installed
            .filter { (pkg, name) ->
                query.isBlank() || name.contains(query, ignoreCase = true) || pkg.contains(query, ignoreCase = true)
            }
            .map { (pkg, name) ->
                val f = byPkg[pkg]
                InstalledApp(pkg, name, f?.enabled == true, f?.accountName, f?.accountNo)
            }
            // แอปที่เปิดรับแจ้งเตือนอยู่บนสุด ถัดมาคือแอปธนาคารที่รองรับการแปลง
            // ที่เหลือเรียงตามชื่อเหมือนเดิม (sortedWith เป็น stable sort)
            .sortedWith(compareBy({ !it.enabled }, { !it.isSupportedBank }))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch(Dispatchers.IO) { installedApps.value = loadLaunchableApps() }
        viewModelScope.launch(Dispatchers.IO) {
            db.notificationDao().purgeTrashOlderThan(System.currentTimeMillis() - TRASH_RETENTION_MS)
        }
    }

    fun setAppEnabled(app: InstalledApp, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val dao = db.appFilterDao()
            // copy จากแถวเดิม เพื่อไม่ให้ข้อมูลบัญชีที่กรอกไว้หายตอนเปิด/ปิดสวิตช์
            val current = dao.get(app.packageName) ?: AppFilterEntity(app.packageName, app.appName, enabled)
            dao.upsert(current.copy(appName = app.appName, enabled = enabled))
        }
    }

    fun setAppAccount(app: InstalledApp, accountName: String, accountNo: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val dao = db.appFilterDao()
            val current = dao.get(app.packageName) ?: AppFilterEntity(app.packageName, app.appName, app.enabled)
            dao.upsert(
                current.copy(
                    accountName = accountName.trim().ifEmpty { null },
                    // เก็บเฉพาะตัวเลข เผื่อพิมพ์มีขีด เช่น 554-4-26827-5
                    accountNo = accountNo.filter { it.isDigit() }.ifEmpty { null },
                )
            )
        }
    }

    /** ข้อมูลบัญชีของแต่ละแอป ใช้ตอนแสดง payload */
    val appFilters: StateFlow<Map<String, AppFilterEntity>> = db.appFilterDao().observeAll()
        .map { list -> list.associateBy { it.packageName } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val trash: StateFlow<List<NotificationEntity>> = db.notificationDao()
        .observeTrash()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** ย้ายทุกรายการไปถังขยะ — คืนค่าเวลาที่ใช้ทำเครื่องหมาย (ไว้กดเลิกทำ) หรือ null ถ้าไม่มีอะไรให้ล้าง */
    suspend fun clearNotifications(): Long? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (db.notificationDao().moveAllToTrash(now) > 0) now else null
    }

    fun undoClear(clearedAt: Long) {
        viewModelScope.launch(Dispatchers.IO) { db.notificationDao().restoreCleared(clearedAt) }
    }

    fun moveToTrash(id: Long) {
        viewModelScope.launch(Dispatchers.IO) { db.notificationDao().moveToTrash(id, System.currentTimeMillis()) }
    }

    fun restore(id: Long) {
        viewModelScope.launch(Dispatchers.IO) { db.notificationDao().restore(id) }
    }

    fun deleteForever(id: Long) {
        viewModelScope.launch(Dispatchers.IO) { db.notificationDao().deleteForever(id) }
    }

    fun emptyTrash() {
        viewModelScope.launch(Dispatchers.IO) { db.notificationDao().emptyTrash() }
    }

    // ---------- Webhook ----------

    private val webhookRepo = (app as NotifyHookApp).webhookSettings

    /** null = ยังโหลดค่าไม่เสร็จ */
    val webhookSettings: StateFlow<WebhookSettings?> = webhookRepo.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val webhookLastResult: StateFlow<String?> = webhookRepo.lastResult
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val statusCounts: StateFlow<Map<WebhookStatus, Int>> = db.notificationDao().observeStatusCounts()
        .map { list -> list.associate { it.status to it.count } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _testResult = MutableStateFlow<FriendlyReport?>(null)
    val testResult: StateFlow<FriendlyReport?> = _testResult

    private val _testing = MutableStateFlow(false)
    val testing: StateFlow<Boolean> = _testing

    fun saveWebhookSettings(settings: WebhookSettings) {
        viewModelScope.launch {
            webhookRepo.save(settings)
            // เปิดใช้งานแล้ว → ส่งรายการที่ค้างอยู่
            if (settings.isUsable) WebhookWorker.enqueue(getApplication())
        }
    }

    fun sendTest(settings: WebhookSettings) {
        if (_testing.value) return
        _testing.value = true
        _testResult.value = null
        viewModelScope.launch(Dispatchers.IO) {
            _testResult.value = WebhookClient.sendDetailed(settings, WebhookClient.testPayload(), "test-${System.currentTimeMillis()}")
            _testing.value = false
        }
    }

    fun retryFailed() {
        viewModelScope.launch(Dispatchers.IO) {
            db.notificationDao().requeueFailed()
            WebhookWorker.enqueue(getApplication())
        }
    }

    fun sendPendingNow() = WebhookWorker.enqueue(getApplication())

    /** ส่งแจ้งเตือนรายการนี้ไป webhook อีกครั้ง — คืน false ถ้ายังไม่ได้ตั้งค่า webhook */
    fun resend(id: Long): Boolean {
        if (webhookSettings.value?.isUsable != true) return false
        viewModelScope.launch(Dispatchers.IO) {
            db.notificationDao().updateStatus(listOf(id), WebhookStatus.PENDING)
            WebhookWorker.enqueue(getApplication())
        }
        return true
    }

    private fun loadLaunchableApps(): List<Pair<String, String>> {
        val context = getApplication<Application>()
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != context.packageName }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
    }
}
