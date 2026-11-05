package com.mm.notifyhook.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mm.notifyhook.BuildConfig
import com.mm.notifyhook.data.WebhookStatus
import com.mm.notifyhook.webhook.FriendlyReport
import com.mm.notifyhook.webhook.WebhookClient
import com.mm.notifyhook.webhook.WebhookSettings
import com.mm.notifyhook.webhook.parseCustomHeaders

@Composable
fun WebhookScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val saved by vm.webhookSettings.collectAsStateWithLifecycle()
    val loaded = saved
    if (loaded == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    WebhookForm(vm, loaded, modifier)
}

@Composable
private fun WebhookForm(vm: MainViewModel, saved: WebhookSettings, modifier: Modifier) {
    val counts by vm.statusCounts.collectAsStateWithLifecycle()
    val lastResult by vm.webhookLastResult.collectAsStateWithLifecycle()
    val testResult by vm.testResult.collectAsStateWithLifecycle()
    val testing by vm.testing.collectAsStateWithLifecycle()

    var enabled by rememberSaveable { mutableStateOf(saved.enabled) }
    var url by rememberSaveable { mutableStateOf(saved.url) }
    var authHeader by rememberSaveable { mutableStateOf(saved.authHeaderName) }
    var authScheme by rememberSaveable { mutableStateOf(saved.authScheme) }
    var customHeaders by rememberSaveable { mutableStateOf(saved.customHeaders) }
    var showInfo by remember { mutableStateOf(false) }

    // token/HMAC secret เป็นค่าลับ — ไม่โหลดค่าที่บันทึกไว้เข้ามาแสดงหรือแก้ไขตรง ๆ
    // ผู้ใช้ทำได้แค่ "เปลี่ยนค่า" (แทนที่ทั้งหมดด้วยค่าใหม่) หรือปล่อยไว้เฉย ๆ (ค่าเดิมไม่เปลี่ยน)
    var tokenEditing by rememberSaveable { mutableStateOf(saved.token.isBlank()) }
    var tokenInput by rememberSaveable { mutableStateOf("") }
    var hmacEditing by rememberSaveable { mutableStateOf(saved.hmacSecret.isBlank()) }
    var hmacInput by rememberSaveable { mutableStateOf("") }

    val effectiveToken = if (tokenEditing) tokenInput.trim() else saved.token
    val effectiveHmacSecret = if (hmacEditing) hmacInput.trim() else saved.hmacSecret

    val draft = WebhookSettings(enabled, url, effectiveToken, authHeader, authScheme, customHeaders, effectiveHmacSecret)
    val dirty = draft.copy(
        url = url.trim(), authHeaderName = authHeader.trim(), authScheme = authScheme.trim(),
    ) != saved

    if (showInfo) WebhookInfoDialog(onDismiss = { showInfo = false })

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("ส่งแจ้งเตือนไป Webhook", style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { showInfo = true }, modifier = Modifier.size(28.dp).padding(start = 4.dp)) {
                        Icon(Icons.Default.Info, contentDescription = "วิธีรับข้อมูลฝั่งเซิร์ฟเวอร์", modifier = Modifier.size(18.dp))
                    }
                }
                Text(
                    "ส่งเป็น HTTP POST (JSON) ทุกครั้งที่มีแจ้งเตือนใหม่",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = { enabled = it })
        }

        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Webhook URL") },
            placeholder = { Text("https://example.com/webhook") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            supportingText = if (draft.isHttpNotHttps) {
                { Text("ใช้ http:// อยู่ — ข้อมูลจะถูกส่งแบบไม่เข้ารหัสผ่านเน็ต แนะนำให้เปลี่ยนเป็น https://") }
            } else null,
            isError = draft.isHttpNotHttps,
            modifier = Modifier.fillMaxWidth(),
        )
        if (draft.isHttpNotHttps) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Text(
                        "URL นี้ใช้ http:// ไม่ได้เข้ารหัส ใครที่อยู่ในวงเน็ตเดียวกันอาจดักอ่าน token และข้อมูลแจ้งเตือนได้ " +
                            "ใช้ http:// ได้เฉพาะตอนทดสอบในวง LAN ของตัวเอง ถ้าใช้งานจริงควรเปลี่ยนเป็น https://",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }

        SecretField(
            label = "Token (ไม่บังคับ)",
            hasSavedValue = saved.token.isNotBlank(),
            editing = tokenEditing,
            value = tokenInput,
            onValueChange = { tokenInput = it },
            onStartEditing = { tokenEditing = true; tokenInput = "" },
            onCancelEditing = { tokenEditing = false; tokenInput = "" },
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = authHeader,
                onValueChange = { authHeader = it },
                label = { Text("Header ของ token") },
                singleLine = true,
                modifier = Modifier.weight(1.3f),
            )
            OutlinedTextField(
                value = authScheme,
                onValueChange = { authScheme = it },
                label = { Text("คำนำหน้า") },
                placeholder = { Text("Bearer") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }

        OutlinedTextField(
            value = customHeaders,
            onValueChange = { customHeaders = it },
            label = { Text("Header เพิ่มเติม") },
            placeholder = { Text("X-Api-Key: abc123\nX-Source: my-phone") },
            supportingText = { Text("บรรทัดละ 1 รายการ รูปแบบ Name: value") },
            minLines = 3,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth(),
        )

        SecretField(
            label = "HMAC secret (ไม่บังคับ)",
            hasSavedValue = saved.hmacSecret.isNotBlank(),
            editing = hmacEditing,
            value = hmacInput,
            onValueChange = { hmacInput = it },
            onStartEditing = { hmacEditing = true; hmacInput = "" },
            onCancelEditing = { hmacEditing = false; hmacInput = "" },
            helpText = "ถ้าใส่ไว้ แอปจะเซ็นข้อมูลทุกครั้งด้วย HMAC-SHA256 ให้ฝั่งเซิร์ฟเวอร์ตรวจสอบได้ (แตะปุ่ม ⓘ ด้านบนดูวิธีตรวจ)",
        )

        HeaderPreview(draft)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    vm.saveWebhookSettings(draft)
                    // ล็อกกลับเป็นค่าลับที่ตั้งไว้แล้วทันทีหลังบันทึก ไม่ให้ค่าที่เพิ่งพิมพ์ค้างอยู่ในฟอร์ม
                    tokenEditing = draft.token.isBlank()
                    tokenInput = ""
                    hmacEditing = draft.hmacSecret.isBlank()
                    hmacInput = ""
                },
                enabled = dirty,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (dirty) "บันทึก" else "บันทึกแล้ว")
            }
            OutlinedButton(
                onClick = { vm.sendTest(draft) },
                enabled = !testing && url.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) {
                if (testing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("ส่งทดสอบ")
            }
        }
        testResult?.let { TestResultCard(it) }

        HorizontalDivider()

        Text("สถานะการส่ง", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            StatTile("รอส่ง", counts[WebhookStatus.PENDING] ?: 0, Modifier.weight(1f))
            StatTile("ส่งแล้ว", counts[WebhookStatus.SENT] ?: 0, Modifier.weight(1f))
            StatTile("ล้มเหลว", counts[WebhookStatus.FAILED] ?: 0, Modifier.weight(1f))
        }
        lastResult?.let {
            Text(
                "ล่าสุด: $it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = vm::sendPendingNow,
                enabled = saved.isUsable && (counts[WebhookStatus.PENDING] ?: 0) > 0,
                modifier = Modifier.weight(1f),
            ) { Text("ส่งที่ค้างตอนนี้") }
            OutlinedButton(
                onClick = vm::retryFailed,
                enabled = saved.isUsable && (counts[WebhookStatus.FAILED] ?: 0) > 0,
                modifier = Modifier.weight(1f),
            ) { Text("ส่งที่ล้มเหลวใหม่") }
        }
        Text(
            "NotifyHook เวอร์ชัน ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

/** ผลทดสอบ: สรุปที่อ่านเข้าใจก่อน ข้อมูลดิบ (header/JSON) ซ่อนไว้ให้กดดูเมื่อต้องการ */
@Composable
private fun TestResultCard(report: FriendlyReport) {
    var showTechnical by rememberSaveable(report) { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val container = if (report.ok) colors.primaryContainer else colors.errorContainer
    val content = if (report.ok) colors.onPrimaryContainer else colors.onErrorContainer
    Card(
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(if (report.ok) Icons.Default.CheckCircle else Icons.Default.Warning, contentDescription = null)
                Text(report.headline, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
            report.points.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
            TextButton(onClick = { showTechnical = !showTechnical }) {
                Text(if (showTechnical) "ซ่อนรายละเอียดทางเทคนิค" else "ดูรายละเอียดทางเทคนิค", color = content)
            }
        }
    }
    if (showTechnical) {
        SelectionContainer {
            Text(
                report.technical,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surfaceVariant, MaterialTheme.shapes.small)
                    .padding(12.dp),
            )
        }
    }
}

@Composable
private fun HeaderPreview(s: WebhookSettings) {
    val lines = buildList {
        add("POST ${s.url.ifBlank { "<url>" }}")
        add("Content-Type: application/json; charset=utf-8")
        add("User-Agent: ${WebhookClient.USER_AGENT}")
        add("X-NotifyHook-Event: notification.posted")
        add("X-NotifyHook-Delivery: n-<id>")
        add("X-NotifyHook-Timestamp: <unix seconds>")
        if (s.token.isNotBlank()) {
            val name = s.authHeaderName.ifBlank { "Authorization" }
            val scheme = s.authScheme.trim()
            add("$name: ${if (scheme.isEmpty()) "" else "$scheme "}••••••••")
        }
        if (s.hmacSecret.isNotBlank()) add("X-NotifyHook-Signature: sha256=<hex ของ HMAC-SHA256>")
        parseCustomHeaders(s.customHeaders).forEach { (k, v) -> add("$k: $v") }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("ตัวอย่าง request", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Text(
                lines.joinToString("\n"),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
        }
    }
}

/**
 * ช่องกรอกค่าลับแบบ "เขียนได้อย่างเดียว" — ถ้าเคยตั้งค่าไว้แล้ว จะไม่แสดงค่าเดิมให้ดูหรือแก้ไขตรง ๆ
 * ทำได้แค่กด "เปลี่ยนค่า" เพื่อพิมพ์ค่าใหม่ทับทั้งหมด หรือปล่อยไว้เฉย ๆ เพื่อคงค่าเดิม
 */
@Composable
private fun SecretField(
    label: String,
    hasSavedValue: Boolean,
    editing: Boolean,
    value: String,
    onValueChange: (String) -> Unit,
    onStartEditing: () -> Unit,
    onCancelEditing: () -> Unit,
    helpText: String? = null,
) {
    if (!editing) {
        OutlinedTextField(
            value = "••••••••",
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            singleLine = true,
            trailingIcon = { TextButton(onClick = onStartEditing) { Text("เปลี่ยนค่า") } },
            supportingText = { Text("ตั้งไว้แล้ว เก็บแบบเข้ารหัสในเครื่อง (Android Keystore) — ดูค่าเดิมไม่ได้ ต้องเปลี่ยนเป็นค่าใหม่เท่านั้น") },
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            trailingIcon = {
                if (hasSavedValue) TextButton(onClick = onCancelEditing) { Text("ยกเลิก") }
            },
            supportingText = { Text(helpText ?: "เก็บแบบเข้ารหัสในเครื่อง (Android Keystore) — เว้นว่างแล้วบันทึก เพื่อลบค่านี้") },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun StatTile(label: String, value: Int, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(value.toString(), style = MaterialTheme.typography.titleLarge)
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** อธิบายวิธีที่ฝั่งเซิร์ฟเวอร์ควรรับและตรวจสอบข้อมูลที่แอปส่งไป */
@Composable
private fun WebhookInfoDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("วิธีรับข้อมูลฝั่งเซิร์ฟเวอร์") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                InfoSection(
                    "รูปแบบข้อมูล",
                    "แอปส่งเป็น HTTP POST เนื้อหาแบบ JSON (Content-Type: application/json) " +
                        "ไปยัง URL ที่ตั้งไว้ ทุกครั้งที่มีแจ้งเตือนใหม่ หรือกดส่งทดสอบ",
                )
                InfoSection(
                    "โครงสร้าง JSON",
                    "event, id, app.packageName, app.name, title, text, subText, postedAt (ISO 8601), " +
                        "postedAtMillis, notificationKey, device.manufacturer/model/androidVersion",
                )
                InfoSection(
                    "แอปธนาคาร",
                    "แอปที่กรอกบัญชีรับเงินไว้ในแท็บ \"เลือกแอป\" (และแอป NEXT, ttb touch, CLICX) จะถูกแปลงก่อนส่ง แทน JSON ด้านบน เป็น " +
                        "{ accountName, accountNo, txn: [ ... ] } — accountName/accountNo คือบัญชีรับเงินที่กรอกไว้ในแท็บ " +
                        "\"เลือกแอป\" ส่วนแต่ละรายการใน txn มี: " +
                        "id (MBN + hex 32 ตัว คงเดิมเมื่อส่งซ้ำ), type (deposit / withdraw / null), amount (ตัวเลข), " +
                        "fromBankCode (รหัส X ของธนาคารต้นทาง เช่น X004 = กรุงศรี, null ถ้าไม่รู้จัก), " +
                        "fromAccountNo (ตัวเลขบัญชีต้นทางทั้งหมดที่ธนาคารแสดง ไม่รวมตัวที่บังไว้), dateTime (UTC), detail (ข้อความเดิม) " +
                        "— NEXT, ttb touch, CLICX อ่านได้ครบทุกช่อง ส่วนแอปอื่นได้แค่ยอดเงิน ช่องที่อ่านไม่ได้จะเป็น null " +
                        "ถ้าหายอดเงินในข้อความไม่เจอ จะส่ง JSON แบบเดิมแทน " +
                        "แตะแจ้งเตือนในรายการเพื่อดูข้อมูลที่ส่งจริงได้",
                )
                InfoSection(
                    "การยืนยันตัวตน (token)",
                    "ถ้าใส่ token ไว้ แอปจะแนบมาใน header ตามชื่อและคำนำหน้าที่ตั้งไว้ (ปกติคือ " +
                        "Authorization: Bearer <token>) ให้เซิร์ฟเวอร์ตรวจว่าตรงกับค่าที่ตั้งไว้ก่อนประมวลผล",
                )
                InfoSection(
                    "การตรวจลายเซ็น HMAC (ถ้าตั้ง HMAC secret ไว้)",
                    "แอปจะแนบ header \"X-NotifyHook-Signature: sha256=<hex>\" โดยคำนวณจาก " +
                        "HMAC-SHA256(secret, request_body_ดิบ) ให้เซิร์ฟเวอร์คำนวณด้วย secret เดียวกันจาก " +
                        "เนื้อ body ที่ได้รับตรง ๆ (อย่า parse แล้วค่อยแปลงกลับ เพราะลำดับ field อาจเปลี่ยน) " +
                        "แล้วเทียบกับค่าใน header แบบ constant-time — ถ้าไม่ตรง ให้ปฏิเสธ request นั้น",
                )
                InfoSection(
                    "ป้องกันข้อมูลซ้ำ",
                    "ใช้ header \"X-NotifyHook-Delivery\" (เช่น n-12) เป็น key กันประมวลผลรายการเดิมซ้ำ " +
                        "เผื่อแอปส่งซ้ำเวลาลองใหม่อัตโนมัติ",
                )
                InfoSection(
                    "ความปลอดภัยที่แนะนำ",
                    "ใช้ URL แบบ https:// เสมอเมื่อใช้งานจริง ตั้งทั้ง token และ HMAC secret ไว้ " +
                        "และเก็บ secret ทั้งสองฝั่งเซิร์ฟเวอร์ให้ปลอดภัยเท่าที่ทำได้",
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("เข้าใจแล้ว") } },
    )
}

@Composable
private fun InfoSection(title: String, body: String) {
    Column {
        Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        Text(body, style = MaterialTheme.typography.bodySmall)
    }
}
