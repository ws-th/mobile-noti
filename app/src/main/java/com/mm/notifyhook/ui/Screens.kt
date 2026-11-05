package com.mm.notifyhook.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import com.mm.notifyhook.webhook.BankParser
import com.mm.notifyhook.webhook.WebhookClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mm.notifyhook.data.AppFilterEntity
import com.mm.notifyhook.data.NotificationEntity
import com.mm.notifyhook.data.WebhookStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun NotificationsScreen(
    vm: MainViewModel,
    setup: SetupState,
    onOpenSetup: () -> Unit,
    onRevealTrash: () -> Unit,
    onSwipeDelete: (NotificationEntity) -> Unit,
    onSwipeResend: (NotificationEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    val items by vm.notifications.collectAsStateWithLifecycle()
    val query by vm.notificationQuery.collectAsState()
    val appOptions by vm.notificationAppOptions.collectAsStateWithLifecycle()
    val selectedApp by vm.selectedAppFilter.collectAsState()
    val isFiltering = query.isNotBlank() || selectedApp != null
    var viewing by remember { mutableStateOf<NotificationEntity?>(null) }

    val filters by vm.appFilters.collectAsStateWithLifecycle()
    viewing?.let { PayloadDialog(it, filters[it.packageName], onDismiss = { viewing = null }) }

    Column(modifier.fillMaxSize()) {
        SetupBanner(setup, onOpenSetup)

        OutlinedTextField(
            value = query,
            onValueChange = { vm.notificationQuery.value = it },
            label = { Text("ค้นหาแจ้งเตือน") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        if (appOptions.isNotEmpty()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            ) {
                items(appOptions, key = { it.first }) { (pkg, name) ->
                    FilterChip(
                        selected = selectedApp == pkg,
                        onClick = { vm.selectedAppFilter.value = if (selectedApp == pkg) null else pkg },
                        label = { Text(name) },
                    )
                }
            }
        }

        PullDownAndHoldToReveal(onReveal = onRevealTrash, modifier = Modifier.fillMaxSize()) {
            // ใช้ LazyColumn เสมอ (แม้ไม่มีรายการ) เพื่อให้ท่าดึงลงค้างไว้ใช้ได้ตลอด
            LazyColumn(Modifier.fillMaxSize()) {
                if (items.isEmpty()) {
                    item {
                        Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                if (isFiltering) {
                                    "ไม่พบแจ้งเตือนที่ตรงกับคำค้นหา"
                                } else {
                                    "ยังไม่มีแจ้งเตือน\nไปที่แท็บ \"เลือกแอป\" เพื่อเลือกแอปที่ต้องการดึง\n\n" +
                                        "ดึงหน้าจอลงแล้วค้างไว้ เพื่อดูรายการที่ล้างไป"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(32.dp),
                            )
                        }
                    }
                } else {
                    items(items, key = { it.id }) { n ->
                        SwipeRow(
                            onDelete = { onSwipeDelete(n) },
                            onResend = { onSwipeResend(n) },
                            modifier = Modifier.animateItem()) {
                            NotificationRow(n, onClick = { viewing = n })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationRow(n: NotificationEntity, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        NotificationContent(n)
    }
}

/** ปัดซ้าย (จากขวาไปซ้าย) เพื่อลบ · ปัดขวา (จากซ้ายไปขวา) เพื่อส่ง webhook ซ้ำ */
@Composable
private fun SwipeRow(
    onDelete: () -> Unit,
    onResend: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val currentOnResend by rememberUpdatedState(onResend)
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = {
            // ส่งซ้ำแล้วให้แถวเด้งกลับที่เดิม (ไม่ปัดทิ้ง)
            if (it == SwipeToDismissBoxValue.StartToEnd) {
                currentOnResend()
                false
            } else {
                true
            }
        },
    )
    val currentOnDelete by rememberUpdatedState(onDelete)
    LaunchedEffect(state.currentValue) {
        if (state.currentValue == SwipeToDismissBoxValue.EndToStart) currentOnDelete()
    }
    Column(modifier) {
        SwipeToDismissBox(
            state = state,
            backgroundContent = {
                if (state.dismissDirection == SwipeToDismissBoxValue.StartToEnd) {
                    Row(
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .padding(horizontal = 24.dp),
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text("ส่งซ้ำ", color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(start = 8.dp))
                    }
                    return@SwipeToDismissBox
                }
                Row(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("ลบ", color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(end = 8.dp))
                    Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                }
            },
        ) { content() }
        HorizontalDivider()
    }
}

/** เนื้อหาของแจ้งเตือน 1 รายการ — ใช้ร่วมกันทั้งหน้าหลักและถังขยะ */
@Composable
fun NotificationContent(n: NotificationEntity) {
    val time = remember(n.postedAt) {
        SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date(n.postedAt))
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        AppIcon(n.packageName, size = 32.dp, modifier = Modifier.padding(end = 12.dp))
        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(n.appName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(4.dp))
            n.title?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
            n.text?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            if (n.webhookStatus != WebhookStatus.SKIPPED) {
                val (label, color) = when {
                    n.webhookStatus == WebhookStatus.SENT && n.webhookError != null ->
                        "✓ ส่ง webhook แล้ว (หลังลองใหม่)" to MaterialTheme.colorScheme.primary
                    n.webhookStatus == WebhookStatus.SENT -> "✓ ส่ง webhook แล้ว" to MaterialTheme.colorScheme.primary
                    n.webhookStatus == WebhookStatus.FAILED -> "✗ ส่ง webhook ไม่สำเร็จ · กดดูสาเหตุ" to MaterialTheme.colorScheme.error
                    n.webhookError != null -> "⏳ รอส่งใหม่ · ครั้งก่อนไม่สำเร็จ" to MaterialTheme.colorScheme.error
                    else -> "⏳ รอส่ง webhook" to MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(label, style = MaterialTheme.typography.labelSmall, color = color, modifier = Modifier.padding(top = 4.dp))
                if (n.webhookStatus != WebhookStatus.SENT) {
                    n.webhookError?.let {
                        Text(
                            it.lineSequence().first(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** แสดง JSON ที่ส่ง (หรือจะส่ง) ไป webhook สำหรับแจ้งเตือนนี้ */
@Composable
private fun PayloadDialog(n: NotificationEntity, account: AppFilterEntity?, onDismiss: () -> Unit) {
    val tx = remember(n.id, account) { WebhookClient.bankTransaction(n, account) }
    val converted = tx != null
    val supportedApp = remember(n.packageName) { BankParser.isSupportedApp(n.packageName) }
    // org.json บน Android ใส่ \ หน้า / (ยังเป็น JSON ที่ถูกต้อง) — ถอดออกเฉพาะตอนแสดงให้อ่านง่าย
    val json = remember(n.id, account) { WebhookClient.payloadFor(n, account).toString(2).replace("\\/", "/") }
    val clipboard = LocalClipboardManager.current
    val status = when (n.webhookStatus) {
        WebhookStatus.SENT -> "ส่งแล้ว"
        WebhookStatus.PENDING -> "รอส่ง"
        WebhookStatus.FAILED -> "ส่งไม่สำเร็จ"
        WebhookStatus.SKIPPED -> "ไม่ได้ส่ง (ตอนนั้นปิด webhook อยู่) — ข้อมูลด้านล่างคือสิ่งที่จะถูกส่งถ้าเปิดไว้"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ข้อมูลที่ส่งไป webhook") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    when {
                        tx?.partial == true ->
                            "✓ แปลงเป็นรายการธนาคารแบบทั่วไป — ได้แค่ยอดเงิน ช่องที่อ่านไม่ได้เป็น null"
                        converted -> "✓ แปลงเป็นรายการธนาคาร"
                        // "รองรับ" หมายถึงแอปที่รับแจ้งเตือนนี้ ไม่ใช่ธนาคารต้นทางที่โอนมา
                        !supportedApp && account?.accountNo == null ->
                            "ข้อมูลดิบ — กรอกบัญชีรับเงินของ ${n.appName} ที่แท็บ \"เลือกแอป\" เพื่อส่งเป็นรายการธนาคาร"
                        !supportedApp -> "ข้อมูลดิบ — ไม่พบยอดเงินในข้อความนี้"
                        else -> "ข้อมูลดิบ — แอป ${n.appName} รองรับ แต่ข้อความนี้ไม่ตรงรูปแบบที่รู้จัก (เช่นไม่ใช่แจ้งเงินเข้า)"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (converted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("สถานะ: $status", style = MaterialTheme.typography.labelSmall)
                n.webhookError?.let { error ->
                    val sent = n.webhookStatus == WebhookStatus.SENT
                    val at = n.webhookAttemptAt?.let {
                        SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()).format(Date(it))
                    }
                    Text(
                        if (sent) "ส่งสำเร็จเมื่อ ${at.orEmpty()} แต่ครั้งก่อนหน้าไม่สำเร็จเพราะ"
                        else "สาเหตุที่ส่งไม่สำเร็จ${at?.let { " (ลองล่าสุด $it)" }.orEmpty()}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    SelectionContainer {
                        Text(
                            error,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.errorContainer, MaterialTheme.shapes.small)
                                .padding(8.dp),
                        )
                    }
                    if (!sent) Text("ปัดรายการไปทางขวาเพื่อส่งซ้ำ", style = MaterialTheme.typography.labelSmall)
                }
                Text("ข้อมูลที่ส่ง", style = MaterialTheme.typography.labelMedium)
                if (converted && account?.accountNo == null) {
                    Text(
                        "ยังไม่ได้กรอกบัญชีรับเงินของแอปนี้ — accountName / accountNo จะเป็น null (กรอกได้ที่แท็บ \"เลือกแอป\")",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                SelectionContainer {
                    Text(json, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("ปิด") } },
        dismissButton = { TextButton(onClick = { clipboard.setText(AnnotatedString(json)) }) { Text("คัดลอก") } },
    )
}

/** กรอก/แก้ไขบัญชีรับเงินของแอปธนาคาร */
@Composable
private fun AccountDialog(app: InstalledApp, onSave: (String, String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(app.accountName.orEmpty()) }
    var no by rememberSaveable { mutableStateOf(app.accountNo.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("บัญชีรับเงิน · ${app.appName}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "ส่งไปกับทุกรายการของแอปนี้ในช่อง accountName / accountNo",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("ชื่อบัญชี") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = no,
                    onValueChange = { no = it },
                    label = { Text("เลขบัญชี") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = { Text("เก็บเฉพาะตัวเลข (ขีดจะถูกตัดออก)") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, no) }) { Text("บันทึก") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ยกเลิก") } },
    )
}

@Composable
fun AppsScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val apps by vm.apps.collectAsStateWithLifecycle()
    val query by vm.appQuery.collectAsState()
    var editingAccount by remember { mutableStateOf<InstalledApp?>(null) }

    editingAccount?.let { app ->
        AccountDialog(
            app = app,
            onSave = { name, no -> vm.setAppAccount(app, name, no); editingAccount = null },
            onDismiss = { editingAccount = null },
        )
    }

    Column(modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { vm.appQuery.value = it },
            label = { Text("ค้นหาแอป") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
        val list = apps
        if (list == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.packageName }) { app ->
                    ListItem(
                        // เปิด/ปิดแล้วรายการย้ายขึ้นลง — ให้เลื่อนแบบมีแอนิเมชัน ผู้ใช้จะได้เห็นว่าไปอยู่ตรงไหน
                        modifier = Modifier.animateItem(),
                        leadingContent = { AppIcon(app.packageName) },
                        headlineContent = { Text(app.appName) },
                        supportingContent = {
                            Column {
                                Text(app.packageName, style = MaterialTheme.typography.bodySmall)
                                // กรอกบัญชีได้ทุกแอปที่เปิดดึงแจ้งเตือน — แอปที่มีบัญชีจะถูกส่งเป็นรายการธนาคาร
                                if (app.isSupportedBank || app.enabled || app.accountNo != null) {
                                    val hasAccount = app.accountNo != null
                                    Text(
                                        when {
                                            hasAccount -> "บัญชี: ${app.accountName ?: "-"} · ${app.accountNo}"
                                            app.isSupportedBank -> "ยังไม่ได้กรอกบัญชีรับเงิน"
                                            else -> "ถ้าเป็นแอปธนาคาร กรอกบัญชีเพื่อส่งเป็นรายการเงิน"
                                        },
                                        style = MaterialTheme.typography.labelMedium,
                                        color = when {
                                            hasAccount -> MaterialTheme.colorScheme.primary
                                            app.isSupportedBank -> MaterialTheme.colorScheme.error
                                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        modifier = Modifier.padding(top = 2.dp),
                                    )
                                    TextButton(
                                        onClick = { editingAccount = app },
                                        contentPadding = PaddingValues(0.dp),
                                    ) { Text(if (hasAccount) "แก้ไขบัญชี" else "กรอกบัญชี") }
                                }
                            }
                        },
                        trailingContent = {
                            Switch(checked = app.enabled, onCheckedChange = { vm.setAppEnabled(app, it) })
                        },
                    )
                }
            }
        }
    }
}
