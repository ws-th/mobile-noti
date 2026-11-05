package com.mm.notifyhook.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mm.notifyhook.BuildConfig
import com.mm.notifyhook.update.AppRelease
import com.mm.notifyhook.update.AppUpdater
import com.mm.notifyhook.update.UpdateState
import com.mm.notifyhook.update.UpdateViewModel

/** กล่องอัปเดต: มีเวอร์ชันใหม่ → โหลด (แสดง %) → ติดตั้ง */
@Composable
fun UpdateDialog(vm: UpdateViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val open by vm.dialogOpen.collectAsStateWithLifecycle()
    val context = LocalContext.current
    if (!open) return

    when (val s = state) {
        is UpdateState.Available -> AlertDialog(
            onDismissRequest = vm::dismiss,
            title = { Text("มีเวอร์ชันใหม่ v${s.release.versionName}") },
            text = { ReleaseInfo(s.release) },
            confirmButton = { TextButton(onClick = vm::download) { Text("อัปเดตเลย") } },
            dismissButton = { TextButton(onClick = vm::dismiss) { Text("ภายหลัง") } },
        )
        is UpdateState.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("กำลังโหลด v${s.release.versionName}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                    Text("${(s.progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {},
        )
        is UpdateState.ReadyToInstall -> {
            var allowed by remember { mutableStateOf(AppUpdater.canInstall(context)) }
            LifecycleResumeEffect(Unit) {
                allowed = AppUpdater.canInstall(context)
                onPauseOrDispose {}
            }
            AlertDialog(
                onDismissRequest = vm::dismiss,
                title = { Text("พร้อมติดตั้ง v${s.release.versionName}") },
                text = {
                    Text(
                        if (allowed) "กด \"ติดตั้ง\" แล้วกดยืนยันในหน้าติดตั้งของ Android " +
                            "ข้อมูลและการตั้งค่าในแอปยังอยู่ครบ"
                        else "ครั้งแรกต้องอนุญาตให้ NotifyHook ติดตั้งแอปได้ก่อน " +
                            "กด \"ไปอนุญาต\" แล้วเปิดสวิตช์ \"อนุญาตจากแหล่งที่มานี้\" จากนั้นกลับมากด \"ติดตั้ง\""
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        context.startActivity(
                            if (AppUpdater.canInstall(context)) AppUpdater.installIntent(context, s.file)
                            else AppUpdater.allowInstallIntent(context)
                        )
                    }) { Text(if (allowed) "ติดตั้ง" else "ไปอนุญาต") }
                },
                dismissButton = { TextButton(onClick = vm::dismiss) { Text("ภายหลัง") } },
            )
        }
        is UpdateState.Failed -> AlertDialog(
            onDismissRequest = vm::dismiss,
            title = { Text("อัปเดตไม่สำเร็จ") },
            text = { Text(s.message) },
            confirmButton = { TextButton(onClick = vm::download) { Text("ลองใหม่") } },
            dismissButton = { TextButton(onClick = vm::dismiss) { Text("ปิด") } },
        )
        else -> Unit
    }
}

@Composable
private fun ReleaseInfo(release: AppRelease) {
    Column(
        Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "เวอร์ชันที่ใช้อยู่ ${BuildConfig.VERSION_NAME}" +
                if (release.size > 0) " · ขนาด ${"%.1f".format(release.size / 1_048_576.0)} MB" else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (release.notes.isNotBlank()) Text(release.notes)
    }
}

/** แถวท้ายหน้า Webhook: เวอร์ชันปัจจุบัน + ปุ่มตรวจสอบอัปเดต */
@Composable
fun UpdateStatusRow(vm: UpdateViewModel, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()

    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "NotifyHook เวอร์ชัน ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!AppUpdater.supported) return@Column
        when (val s = state) {
            UpdateState.Checking -> Text("กำลังตรวจสอบ…", style = MaterialTheme.typography.labelSmall)
            UpdateState.UpToDate -> Text(
                "เป็นเวอร์ชันล่าสุดแล้ว",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            is UpdateState.Available -> TextButton(onClick = { vm.dialogOpen.value = true }) {
                Text("มีเวอร์ชันใหม่ v${s.release.versionName} — อัปเดต")
            }
            is UpdateState.Downloading, is UpdateState.ReadyToInstall ->
                TextButton(onClick = { vm.dialogOpen.value = true }) { Text("ดูการอัปเดต") }
            is UpdateState.Failed -> Text(
                s.message,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
            UpdateState.Idle -> Unit
        }
        if (state !is UpdateState.Checking && state !is UpdateState.Available &&
            state !is UpdateState.Downloading && state !is UpdateState.ReadyToInstall
        ) {
            TextButton(onClick = { vm.check() }) { Text("ตรวจสอบอัปเดต") }
        }
    }
}
