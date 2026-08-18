package com.dtyan.spendtracker.notifications

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.dtyan.spendtracker.SpendApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Слушатель уведомлений банковских приложений.
 *
 * Правила, которые здесь соблюдаются жёстко:
 *  - читаем **только** пакеты из [BankCatalog] и только те, что включил пользователь;
 *  - в `onNotificationPosted` не делаем ничего тяжёлого: достали текст и ушли на IO;
 *  - ничего не пишем в траты — только в очередь подтверждения;
 *  - текст уведомления никуда не отправляется: у приложения нет разрешения на интернет.
 */
class BankNotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val app: SpendApp? get() = application as? SpendApp

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn?.notification ?: return
        val packageName = sbn.packageName ?: return

        // Сводки групп и «висящие» служебные уведомления операциями не бывают.
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        if (BankCatalog.byPackage(packageName) == null) return

        val extras = notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = listOfNotNull(
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
        ).distinct().joinToString(". ")
        if (title.isNullOrBlank() && text.isBlank()) return

        val postedAt = sbn.postTime.takeIf { it > 0 } ?: System.currentTimeMillis()
        val container = app?.container ?: return

        scope.launch {
            runCatching {
                val pendingId = container.notificationIntake
                    .handle(packageName, title, text, postedAt) ?: return@runCatching
                if (!container.settings.current().notifyOnCapture) return@runCatching
                val operation = container.repository.getPendingOperation(pendingId) ?: return@runCatching
                PendingNotifier(applicationContext).notifyPending(operation)
            }
        }
    }

    /** Удаление уведомления пользователем нас не касается: операция уже в очереди. */
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = Unit

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
