package com.dtyan.spendtracker.notifications

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Работа с системным разрешением «Доступ к уведомлениям».
 *
 * Его **нельзя запросить диалогом**: пользователь включает переключатель сам в системных
 * настройках. Поэтому приложение умеет только проверить состояние и открыть нужный экран.
 */
object NotificationAccess {

    /** Выдан ли доступ к уведомлениям нашему сервису. */
    fun isGranted(context: Context): Boolean {
        val enabled = runCatching {
            Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        }.getOrNull().orEmpty()
        if (enabled.isEmpty()) return false
        val ourComponent = ComponentName(context, BankNotificationListener::class.java)
        return enabled.split(':')
            .mapNotNull { ComponentName.unflattenFromString(it) }
            .any { it.packageName == context.packageName && it.className == ourComponent.className }
    }

    /**
     * Интент на системный экран «Доступ к уведомлениям».
     * На части прошивок точечный экран приложения недоступен — тогда откроется общий список.
     */
    fun settingsIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Установлено ли на устройстве хоть одно приложение источника — для подсказки в настройках. */
    fun isInstalled(context: Context, source: BankSource): Boolean =
        source.packages.any { packageName ->
            runCatching {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, 0)
            }.isSuccess
        }
}
