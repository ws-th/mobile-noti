package com.mm.notifyhook.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mm.notifyhook.data.NotificationEntity
import com.mm.notifyhook.data.TRASH_RETENTION_DAYS
import com.mm.notifyhook.data.TRASH_RETENTION_MS
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

private const val HOLD_MILLIS = 800

/**
 * ดึงรายการลงจากด้านบนจนสุดระยะ แล้วค้างไว้ HOLD_MILLIS จึงเรียก [onReveal]
 * ปล่อยนิ้วก่อนครบเวลา = ยกเลิก
 */
@Composable
fun PullDownAndHoldToReveal(
    onReveal: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val threshold = with(LocalDensity.current) { 96.dp.toPx() }
    val maxPull = threshold * 1.3f
    var pull by remember { mutableFloatStateOf(0f) }
    val hold = remember { Animatable(0f) }
    val haptic = LocalHapticFeedback.current
    val currentOnReveal by rememberUpdatedState(onReveal)
    val armed = pull >= threshold

    val connection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // กำลังดึงค้างอยู่แล้วเลื่อนนิ้วกลับขึ้น → หดตัวบอกก่อน ค่อยให้ list เลื่อน
                if (source == NestedScrollSource.UserInput && available.y < 0f && pull > 0f) {
                    val used = maxOf(available.y, -pull)
                    pull += used
                    return Offset(0f, used)
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                // list อยู่บนสุดแล้วแต่ยังดึงลงต่อ
                if (source == NestedScrollSource.UserInput && available.y > 0f) {
                    pull = (pull + available.y * 0.5f).coerceAtMost(maxPull)
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                // ปล่อยนิ้ว
                val wasPulling = pull > 0f
                pull = 0f
                return if (wasPulling) available else Velocity.Zero
            }
        }
    }

    LaunchedEffect(armed) {
        hold.snapTo(0f)
        if (armed) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            hold.animateTo(1f, tween(HOLD_MILLIS, easing = LinearEasing))
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            pull = 0f
            currentOnReveal()
        }
    }

    Column(modifier.nestedScroll(connection)) {
        val heightDp = with(LocalDensity.current) { pull.toDp() }
        Box(
            Modifier.fillMaxWidth().height(heightDp).clipToBounds(),
            contentAlignment = Alignment.Center,
        ) {
            if (pull > 0f) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(
                        progress = { if (armed) hold.value else (pull / threshold).coerceIn(0f, 1f) * 0.25f },
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                    Text(
                        if (armed) "ค้างไว้… เพื่อเปิดรายการที่ล้าง" else "ดึงลงต่อ แล้วค้างไว้",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        content()
    }
}

@Composable
fun TrashScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val items by vm.trash.collectAsStateWithLifecycle()

    Column(modifier.fillMaxSize()) {
        Text(
            "รายการที่ล้างจะถูกเก็บไว้ $TRASH_RETENTION_DAYS วัน แล้วลบถาวรอัตโนมัติ",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        HorizontalDivider()
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("ไม่มีรายการที่ล้าง", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(items, key = { it.id }) { n ->
                    TrashRow(n, onRestore = { vm.restore(n.id) }, onDelete = { vm.deleteForever(n.id) })
                }
            }
        }
    }
}

@Composable
private fun TrashRow(n: NotificationEntity, onRestore: () -> Unit, onDelete: () -> Unit) {
    val deletedAt = n.deletedAt ?: return
    val info = remember(deletedAt) {
        val daysLeft = ceil((deletedAt + TRASH_RETENTION_MS - System.currentTimeMillis()) / 86_400_000.0).toInt()
        val whenCleared = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(deletedAt))
        "ล้างเมื่อ $whenCleared · " + if (daysLeft <= 1) "ลบถาวรภายในวันนี้/พรุ่งนี้" else "ลบถาวรในอีก $daysLeft วัน"
    }
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp)) {
        NotificationContent(n)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                info,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRestore) { Text("กู้คืน") }
            TextButton(onClick = onDelete) { Text("ลบถาวร", color = MaterialTheme.colorScheme.error) }
        }
    }
    HorizontalDivider()
}
