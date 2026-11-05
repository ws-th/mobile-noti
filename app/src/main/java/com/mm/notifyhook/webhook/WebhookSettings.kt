package com.mm.notifyhook.webhook

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

data class WebhookSettings(
    val enabled: Boolean = false,
    val url: String = "",
    val token: String = "",
    /** ชื่อ header ที่ใช้ส่ง token เช่น Authorization, X-API-Key */
    val authHeaderName: String = "Authorization",
    /** คำนำหน้า token เช่น Bearer (เว้นว่าง = ส่ง token อย่างเดียว) */
    val authScheme: String = "Bearer",
    /** header เพิ่มเติม บรรทัดละ 1 รายการ รูปแบบ "Name: value" */
    val customHeaders: String = "",
    /** secret สำหรับเซ็น HMAC-SHA256 ต่อ payload — แยกจาก token เพื่อไม่ให้ secret เดียวกันหลุดสองทาง */
    val hmacSecret: String = "",
) {
    val isUsable: Boolean get() = enabled && url.isNotBlank()
    val isHttpNotHttps: Boolean get() = url.trim().startsWith("http://", ignoreCase = true)
}

private val Context.webhookDataStore by preferencesDataStore("webhook_settings")

class WebhookSettingsRepository(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // token และ hmacSecret เป็นความลับ จึงเก็บแยกจากค่าตั้งค่าอื่นด้วย EncryptedSharedPreferences
    // (เข้ารหัสด้วยกุญแจที่อยู่ใน Android Keystore) แทนที่จะเก็บเป็นข้อความธรรมดาใน DataStore
    private val securePrefs by lazy {
        val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context,
            "webhook_secrets",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** เพิ่มค่านี้ทุกครั้งที่แก้ securePrefs เพื่อให้ [settings] อ่านค่าใหม่ (SharedPreferences ไม่ใช่ Flow โดยตรง) */
    private val secureVersion = MutableStateFlow(0)

    init {
        migrateTokenFromDataStoreIfNeeded()
    }

    val settings: Flow<WebhookSettings> = combine(context.webhookDataStore.data, secureVersion) { p, _ ->
        WebhookSettings(
            enabled = p[ENABLED] ?: false,
            url = p[URL] ?: "",
            token = securePrefs.getString(KEY_TOKEN, "") ?: "",
            authHeaderName = p[AUTH_HEADER] ?: "Authorization",
            authScheme = p[AUTH_SCHEME] ?: "Bearer",
            customHeaders = p[CUSTOM_HEADERS] ?: "",
            hmacSecret = securePrefs.getString(KEY_HMAC_SECRET, "") ?: "",
        )
    }

    /** ผลการส่งครั้งล่าสุด ไว้แสดงในหน้าตั้งค่า */
    val lastResult: Flow<String?> = context.webhookDataStore.data.map { it[LAST_RESULT] }

    suspend fun save(s: WebhookSettings) {
        securePrefs.edit()
            .putString(KEY_TOKEN, s.token.trim())
            .putString(KEY_HMAC_SECRET, s.hmacSecret.trim())
            .apply()
        secureVersion.value += 1

        context.webhookDataStore.edit { p ->
            p[ENABLED] = s.enabled
            p[URL] = s.url.trim()
            p[AUTH_HEADER] = s.authHeaderName.trim()
            p[AUTH_SCHEME] = s.authScheme.trim()
            p[CUSTOM_HEADERS] = s.customHeaders
        }
    }

    suspend fun setLastResult(message: String) {
        context.webhookDataStore.edit { it[LAST_RESULT] = message }
    }

    /** ย้าย token เดิมที่เคยเก็บเป็นข้อความธรรมดาใน DataStore มาไว้ใน EncryptedSharedPreferences ครั้งเดียว */
    private fun migrateTokenFromDataStoreIfNeeded() {
        if (securePrefs.contains(KEY_TOKEN)) return
        scope.launch {
            val old = context.webhookDataStore.data.first()[LEGACY_TOKEN]
            if (!old.isNullOrBlank()) {
                securePrefs.edit().putString(KEY_TOKEN, old).apply()
                secureVersion.value += 1
            }
            context.webhookDataStore.edit { it.remove(LEGACY_TOKEN) }
        }
    }

    private companion object {
        val ENABLED = booleanPreferencesKey("enabled")
        val URL = stringPreferencesKey("url")
        val LEGACY_TOKEN = stringPreferencesKey("token")
        val AUTH_HEADER = stringPreferencesKey("auth_header")
        val AUTH_SCHEME = stringPreferencesKey("auth_scheme")
        val CUSTOM_HEADERS = stringPreferencesKey("custom_headers")
        val LAST_RESULT = stringPreferencesKey("last_result")

        const val KEY_TOKEN = "token"
        const val KEY_HMAC_SECRET = "hmac_secret"
    }
}

/** แปลงข้อความ "Name: value" หลายบรรทัดเป็นคู่ header — บรรทัดว่างหรือขึ้นต้นด้วย # จะถูกข้าม */
fun parseCustomHeaders(raw: String): List<Pair<String, String>> =
    raw.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { line ->
            val idx = line.indexOf(':')
            if (idx <= 0) null else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
        }
        .toList()
