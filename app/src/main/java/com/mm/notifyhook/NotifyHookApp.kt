package com.mm.notifyhook

import android.app.Application
import com.mm.notifyhook.data.AppDatabase
import com.mm.notifyhook.webhook.WebhookSettingsRepository

class NotifyHookApp : Application() {
    val database: AppDatabase by lazy { AppDatabase.get(this) }
    val webhookSettings: WebhookSettingsRepository by lazy { WebhookSettingsRepository(this) }
}
