package com.mm.notifyhook.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun SetupScreen(setup: SetupState, modifier: Modifier = Modifier) {
    val items = setup.items
    val doneCount = items.count { it.done }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("📌 เปิด NotifyHook ไว้ตลอด", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "เพื่อให้รับแจ้งเตือนและส่ง webhook ได้ตลอด 24 ชม. กรุณาตั้งค่าทุกข้อด้านล่าง " +
                        "และอย่าปัดปิดแอปจากหน้าแอปล่าสุด ไม่อย่างนั้นระบบอาจหยุดแอปเมื่อปิดหน้าจอ",
                    style = MaterialTheme.typography.bodyMedium,
                )
                LinearProgressIndicator(
                    progress = { doneCount.toFloat() / items.size },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
                Text("ตั้งค่าแล้ว $doneCount / ${items.size}", style = MaterialTheme.typography.labelMedium)
            }
        }

        items.forEachIndexed { index, item -> SetupItemCard(index + 1, item, setup) }
    }
}

@Composable
private fun SetupItemCard(number: Int, item: SetupItem, setup: SetupState) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (item.done) "✅" else "$number.",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text(item.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            }
            Text(
                item.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item.action?.let { action ->
                    if (item.done) {
                        OutlinedButton(onClick = { action(context) }) { Text("เปิดการตั้งค่า") }
                    } else {
                        Button(onClick = { action(context) }) { Text("เปิดการตั้งค่า") }
                    }
                }
                if (!item.autoDetected) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { setup.setManualDone(item.id, !item.done) },
                    ) {
                        Checkbox(checked = item.done, onCheckedChange = { setup.setManualDone(item.id, it) })
                        Text("ทำแล้ว")
                    }
                }
            }
        }
    }
}

/** แถบบนสุดของหน้าแจ้งเตือน: เตือนถ้าตั้งค่าไม่ครบ / ย้ำให้เปิดแอปไว้ถ้าครบแล้ว */
@Composable
fun SetupBanner(setup: SetupState, onOpenSetup: () -> Unit, modifier: Modifier = Modifier) {
    val items = setup.items
    val remaining = items.count { !it.done }
    if (remaining > 0) {
        val critical = !setup.notificationAccess
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (critical) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.tertiaryContainer
            ),
            modifier = modifier.fillMaxWidth().padding(16.dp),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (critical) "⚠️ ยังไม่ได้อนุญาตให้อ่านการแจ้งเตือน" else "⚠️ ตั้งค่ายังไม่ครบ (เหลือ $remaining ข้อ)",
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    if (critical) "แอปจะยังไม่บันทึกแจ้งเตือนจนกว่าจะเปิดสิทธิ์"
                    else "แอปอาจหยุดรับแจ้งเตือนเมื่อปิดหน้าจอหรือไม่ได้เปิดแอปนาน ๆ",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = onOpenSetup) { Text("ตั้งค่าให้ทำงานตลอด") }
            }
        }
    } else {
        Text(
            "✅ พร้อมทำงานตลอด — เปิดแอปทิ้งไว้ได้เลย อย่าปัดปิดจากหน้าแอปล่าสุด",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenSetup)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}
