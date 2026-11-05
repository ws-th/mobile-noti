package com.mm.notifyhook.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.mm.notifyhook.R
import com.mm.notifyhook.update.UpdateCheckWorker
import com.mm.notifyhook.update.UpdateViewModel
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        UpdateCheckWorker.schedule(this)
        val fromUpdateNotification = intent.getBooleanExtra(EXTRA_CHECK_UPDATE, false)
        setContent { AppTheme { MainScreen(fromUpdateNotification = fromUpdateNotification) } }
    }

    companion object {
        /** เปิดจากแจ้งเตือน "มีเวอร์ชันใหม่" — เช็คอัปเดตทันทีแม้เคยเช็คไปแล้วรอบนี้ */
        const val EXTRA_CHECK_UPDATE = "check_update"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(
    fromUpdateNotification: Boolean = false,
    vm: MainViewModel = viewModel(),
    updates: UpdateViewModel = viewModel(),
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showTrash by rememberSaveable { mutableStateOf(false) }
    var confirmEmptyTrash by remember { mutableStateOf(false) }
    val setup = rememberSetupState()
    val trash by vm.trash.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val inTrash = tab == 0 && showTrash

    // Android 13+ ต้องขออนุญาตก่อนถึงจะขึ้นแจ้งเตือน "มีเวอร์ชันใหม่" จากการเช็คเบื้องหลังได้
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val context = LocalContext.current
    var askedNotificationPermission by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (fromUpdateNotification) updates.check(manual = false) else updates.autoCheck()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !askedNotificationPermission &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askedNotificationPermission = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    UpdateDialog(updates)

    BackHandler(enabled = inTrash) { showTrash = false }
    LaunchedEffect(tab) { if (tab != 0) showTrash = false }

    if (confirmEmptyTrash) {
        AlertDialog(
            onDismissRequest = { confirmEmptyTrash = false },
            title = { Text("ลบถาวรทั้งหมด?") },
            text = { Text("รายการที่ล้างทั้งหมด ${trash.size} รายการจะถูกลบทิ้ง และกู้คืนไม่ได้") },
            confirmButton = {
                TextButton(onClick = { vm.emptyTrash(); confirmEmptyTrash = false }) {
                    Text("ลบถาวร", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmEmptyTrash = false }) { Text("ยกเลิก") } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (inTrash) {
                        IconButton(onClick = { showTrash = false }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "กลับ")
                        }
                    } else Image(
                        painter = painterResource(R.drawable.logo),
                        contentDescription = "NotifyHook",
                        modifier = Modifier.padding(start = 12.dp, end = 4.dp).size(36.dp),
                    )
                },
                title = {
                    Text(
                        when (tab) {
                            0 -> if (inTrash) "รายการที่ล้าง" else "แจ้งเตือนที่บันทึก"
                            1 -> "เลือกแอปที่จะดึง"
                            2 -> "ตั้งค่า Webhook"
                            else -> "ให้แอปทำงานตลอด"
                        }
                    )
                },
                actions = {
                    if (inTrash) {
                        TextButton(onClick = { confirmEmptyTrash = true }, enabled = trash.isNotEmpty()) {
                            Text("ลบถาวรทั้งหมด", color = MaterialTheme.colorScheme.error)
                        }
                    } else if (tab == 0) {
                        IconButton(onClick = {
                            scope.launch {
                                val clearedAt = vm.clearNotifications() ?: return@launch
                                snackbar.currentSnackbarData?.dismiss()
                                val result = snackbar.showSnackbar(
                                    message = "ล้างแล้ว · ดึงหน้าจอลงค้างไว้เพื่อดูย้อนหลัง",
                                    actionLabel = "เลิกทำ",
                                    duration = SnackbarDuration.Long,
                                )
                                if (result == SnackbarResult.ActionPerformed) vm.undoClear(clearedAt)
                            }
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "ล้างทั้งหมด")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Default.Notifications, null) },
                    label = { Text("แจ้งเตือน") },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Default.Settings, null) },
                    label = { Text("เลือกแอป") },
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Icon(Icons.Default.Share, null) },
                    label = { Text("Webhook") },
                )
                NavigationBarItem(
                    selected = tab == 3,
                    onClick = { tab = 3 },
                    icon = {
                        BadgedBox(badge = { if (setup.items.any { !it.done }) Badge() }) {
                            Icon(Icons.Default.CheckCircle, null)
                        }
                    },
                    label = { Text("ทำงานตลอด") },
                )
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (tab) {
            0 -> if (showTrash) {
                TrashScreen(vm, modifier)
            } else {
                NotificationsScreen(
                    vm,
                    setup,
                    onOpenSetup = { tab = 3 },
                    onRevealTrash = { showTrash = true },
                    onSwipeDelete = { n ->
                        vm.moveToTrash(n.id)
                        scope.launch {
                            snackbar.currentSnackbarData?.dismiss()
                            val result = snackbar.showSnackbar(
                                message = "ลบ \"${n.title ?: n.appName}\" แล้ว",
                                actionLabel = "เลิกทำ",
                                duration = SnackbarDuration.Short,
                            )
                            if (result == SnackbarResult.ActionPerformed) vm.restore(n.id)
                        }
                    },
                    onSwipeResend = { n ->
                        val queued = vm.resend(n.id)
                        scope.launch {
                            snackbar.currentSnackbarData?.dismiss()
                            snackbar.showSnackbar(
                                message = if (queued) "กำลังส่ง \"${n.title ?: n.appName}\" ซ้ำ"
                                else "ยังไม่ได้ตั้งค่า webhook",
                                duration = SnackbarDuration.Short,
                            )
                        }
                    },
                    modifier = modifier,
                )
            }
            1 -> AppsScreen(vm, modifier)
            2 -> WebhookScreen(vm, modifier)
            else -> SetupScreen(setup, modifier)
        }
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}
