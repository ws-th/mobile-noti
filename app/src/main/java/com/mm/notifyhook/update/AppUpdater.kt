package com.mm.notifyhook.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.mm.notifyhook.BuildConfig
import com.mm.notifyhook.webhook.SecureDns
import com.mm.notifyhook.webhook.WebhookClient
import com.mm.notifyhook.webhook.WebhookSettings
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class AppRelease(
    val versionName: String,
    val versionCode: Int,
    val sha256: String,
    val size: Long,
    val notes: String,
    val downloadUrl: HttpUrl,
)

sealed interface UpdateCheck {
    data class Available(val release: AppRelease) : UpdateCheck
    data object UpToDate : UpdateCheck
    data class Failed(val message: String) : UpdateCheck
}

/**
 * อัปเดตแอปจากเซิร์ฟเวอร์เดียวกับที่ส่ง webhook (ไม่ได้อยู่บน Play Store)
 *
 * - เช็คที่ <webhook URL>/../app/latest เช่น https://x/api/notifyhook/transection → https://x/api/notifyhook/app/latest
 *   ยืนยันตัวด้วย token เดียวกับที่ใช้ส่งรายการ
 * - โหลด APK มาไว้ใน cache แล้วตรวจ sha256, ชื่อแพ็กเกจ และเวอร์ชัน ก่อนเปิดตัวติดตั้งของ Android
 *   ส่วนลายเซ็น Android ตรวจเอง — APK ต้องเซ็นด้วยกุญแจเดียวกับตัวที่ติดตั้งอยู่ ไม่อย่างนั้นติดตั้งทับไม่ได้
 * เรียกจาก background thread เท่านั้น (blocking)
 */
object AppUpdater {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .dns(SecureDns)
        .build()

    /** ตัว debug เป็นอีกแพ็กเกจ (.debug) ติดตั้ง APK ตัวจริงทับไม่ได้ */
    val supported: Boolean get() = !BuildConfig.DEBUG

    fun check(settings: WebhookSettings): UpdateCheck {
        if (!supported) return UpdateCheck.Failed("ตัว debug อัปเดตในแอปไม่ได้")
        val url = settings.url.trim().toHttpUrlOrNull()?.resolve("app/latest")
            ?: return UpdateCheck.Failed("ยังไม่ได้ตั้ง Webhook URL")
        if (settings.token.isBlank()) return UpdateCheck.Failed("ยังไม่ได้ใส่ token")

        return try {
            http.newCall(request(settings, url)).execute().use { resp ->
                when {
                    resp.code == 204 -> UpdateCheck.UpToDate
                    !resp.isSuccessful -> UpdateCheck.Failed("เซิร์ฟเวอร์ตอบ HTTP ${resp.code}")
                    else -> {
                        val json = JSONObject(resp.body?.string().orEmpty())
                        val release = AppRelease(
                            versionName = json.getString("versionName"),
                            versionCode = json.getInt("versionCode"),
                            sha256 = json.getString("sha256").lowercase(),
                            size = json.optLong("size"),
                            notes = json.optString("notes"),
                            // ใช้ URL ที่เซิร์ฟเวอร์บอก ถ้าเป็น path สั้นๆ ก็อิงจาก URL ที่เช็ค
                            downloadUrl = url.resolve(json.getString("downloadUrl"))
                                ?: return UpdateCheck.Failed("downloadUrl ไม่ถูกต้อง"),
                        )
                        if (release.versionCode > BuildConfig.VERSION_CODE) UpdateCheck.Available(release)
                        else UpdateCheck.UpToDate
                    }
                }
            }
        } catch (e: IOException) {
            UpdateCheck.Failed("เชื่อมต่อเซิร์ฟเวอร์ไม่ได้ (${e.javaClass.simpleName})")
        } catch (e: org.json.JSONException) {
            UpdateCheck.Failed("ข้อมูลเวอร์ชันจากเซิร์ฟเวอร์ไม่ถูกต้อง")
        }
    }

    /** โหลดและตรวจ APK — คืนไฟล์ที่พร้อมติดตั้ง หรือโยน IOException พร้อมเหตุผลที่อ่านเข้าใจ */
    fun download(context: Context, settings: WebhookSettings, release: AppRelease, onProgress: (Float) -> Unit): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "NotifyHook-v${release.versionName}.apk")
        val digest = MessageDigest.getInstance("SHA-256")

        http.newCall(request(settings, release.downloadUrl)).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("โหลดไฟล์ไม่สำเร็จ (HTTP ${resp.code})")
            val body = resp.body ?: throw IOException("เซิร์ฟเวอร์ไม่ได้ส่งไฟล์มา")
            val total = body.contentLength().takeIf { it > 0 } ?: release.size
            body.byteStream().use { input ->
                file.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                        val pct = if (total > 0) (done * 100 / total).toInt().coerceAtMost(100) else -1
                        if (pct != lastPct) { lastPct = pct; onProgress(pct / 100f) }
                    }
                }
            }
        }

        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        if (sha != release.sha256) {
            file.delete()
            throw IOException("ไฟล์ที่โหลดมาไม่ครบหรือถูกแก้ไข (sha256 ไม่ตรง) ลองใหม่อีกครั้ง")
        }

        val info = context.packageManager.getPackageArchiveInfo(file.path, 0)
        if (info == null || info.packageName != context.packageName) {
            file.delete()
            throw IOException("ไฟล์นี้ไม่ใช่แอป NotifyHook")
        }
        if (PackageInfoCompat.getLongVersionCode(info) <= BuildConfig.VERSION_CODE) {
            file.delete()
            throw IOException("ไฟล์นี้ไม่ใช่เวอร์ชันที่ใหม่กว่า")
        }
        return file
    }

    /** Android 8+ ต้องให้ผู้ใช้อนุญาต "ติดตั้งแอปที่ไม่รู้จัก" ให้แอปนี้ก่อนครั้งแรก */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun allowInstallIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    fun installIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun request(settings: WebhookSettings, url: HttpUrl): Request =
        Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", WebhookClient.USER_AGENT)
            .header(
                settings.authHeaderName.ifBlank { "Authorization" },
                if (settings.authScheme.isBlank()) settings.token else "${settings.authScheme} ${settings.token}",
            )
            .build()
}
