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
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
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
 * - เช็ค GitHub Release ล่าสุดของ repo แอป (BuildConfig.UPDATE_REPO) ตรงๆ ไม่ต้องตั้ง Webhook URL หรือ token
 *   ออกเวอร์ชันด้วย ./release.sh — tag vX.Y.Z แนบไฟล์ .apk (GitHub คำนวณ sha256 ให้ใน digest)
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

    fun check(): UpdateCheck {
        if (!supported) return UpdateCheck.Failed("ตัว debug อัปเดตในแอปไม่ได้")
        val url = "https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest".toHttpUrl()

        return try {
            http.newCall(request(url, "application/vnd.github+json")).execute().use { resp ->
                when {
                    // ยังไม่เคยออก release
                    resp.code == 404 -> UpdateCheck.UpToDate
                    // GitHub จำกัด 60 ครั้ง/ชม. ต่อ IP (เน็ตเดียวกันใช้ร่วมกัน)
                    resp.code == 403 || resp.code == 429 -> UpdateCheck.Failed("เช็คถี่เกินไป ลองใหม่ภายหลัง")
                    !resp.isSuccessful -> UpdateCheck.Failed("GitHub ตอบ HTTP ${resp.code}")
                    else -> parseRelease(JSONObject(resp.body?.string().orEmpty()))
                        ?.let { if (it.versionCode > BuildConfig.VERSION_CODE) UpdateCheck.Available(it) else UpdateCheck.UpToDate }
                        ?: UpdateCheck.Failed("ข้อมูลเวอร์ชันล่าสุดไม่ครบ")
                }
            }
        } catch (e: IOException) {
            UpdateCheck.Failed("เชื่อมต่ออินเทอร์เน็ตไม่ได้ (${e.javaClass.simpleName})")
        } catch (e: org.json.JSONException) {
            UpdateCheck.Failed("ข้อมูลเวอร์ชันล่าสุดไม่ถูกต้อง")
        }
    }

    /** tag "v0.3.1" + ไฟล์ .apk ที่มี digest sha256 — versionCode คิดแบบเดียวกับ app/build.gradle.kts */
    private fun parseRelease(json: JSONObject): AppRelease? {
        val versionName = json.optString("tag_name").removePrefix("v")
        val parts = versionName.split(".").mapNotNull { it.toIntOrNull() }
        if (parts.size != 3 || parts.any { it !in 0..99 }) return null
        val assets = json.optJSONArray("assets") ?: return null
        val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
            .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) } ?: return null
        val sha256 = apk.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:") ?: return null
        return AppRelease(
            versionName = versionName,
            versionCode = parts[0] * 10000 + parts[1] * 100 + parts[2],
            sha256 = sha256.lowercase(),
            size = apk.optLong("size"),
            notes = json.optString("body").trim(),
            downloadUrl = apk.optString("browser_download_url").toHttpUrlOrNull() ?: return null,
        )
    }

    /** โหลดและตรวจ APK — คืนไฟล์ที่พร้อมติดตั้ง หรือโยน IOException พร้อมเหตุผลที่อ่านเข้าใจ */
    fun download(context: Context, release: AppRelease, onProgress: (Float) -> Unit): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "NotifyHook-v${release.versionName}.apk")
        val digest = MessageDigest.getInstance("SHA-256")

        http.newCall(request(release.downloadUrl, "application/octet-stream")).execute().use { resp ->
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

    // GitHub ต้องมี User-Agent ทุก request
    private fun request(url: HttpUrl, accept: String): Request =
        Request.Builder()
            .url(url)
            .header("Accept", accept)
            .header("User-Agent", WebhookClient.USER_AGENT)
            .build()
}
