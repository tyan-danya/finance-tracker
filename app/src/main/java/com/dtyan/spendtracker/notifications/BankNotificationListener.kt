package com.dtyan.spendtracker.notifications

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.dtyan.spendtracker.SpendApp
import com.dtyan.spendtracker.data.LogLevel
import com.dtyan.spendtracker.data.LogStage
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

    override fun onListenerConnected() {
        super.onListenerConnected()
        val container = app?.container ?: return
        scope.launch {
            container.diagnosticsLog.log(
                stage = LogStage.SERVICE,
                message = "Слушатель уведомлений подключён системой",
                details = "автоучёт: ${if (container.settings.current().enabled) "включён" else "выключен"}",
            )
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        val container = app?.container ?: return
        scope.launch {
            container.diagnosticsLog.log(
                stage = LogStage.SERVICE,
                message = "Слушатель уведомлений отключён системой",
                level = LogLevel.WARN,
            )
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn?.notification ?: return
        val packageName = sbn.packageName ?: return

        // Чужие приложения не читаем вовсе — даже в журнал они не попадают.
        if (BankCatalog.byPackage(packageName) == null) return

        val container = app?.container ?: return

        // Сводки групп и «висящие» служебные уведомления операциями не бывают.
        val skipReason = when {
            notification.flags and Notification.FLAG_GROUP_SUMMARY != 0 -> "сводка группы уведомлений"
            notification.flags and Notification.FLAG_ONGOING_EVENT != 0 -> "постоянное (ongoing) уведомление"
            else -> null
        }
        if (skipReason != null) {
            scope.launch {
                container.diagnosticsLog.log(
                    stage = LogStage.NOTIFICATION,
                    message = "Пропущено служебное уведомление $packageName: $skipReason",
                )
            }
            return
        }

        val extras = notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = listOfNotNull(
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
        ).distinct().joinToString(". ")
        if (title.isNullOrBlank() && text.isBlank()) return

        val postedAt = sbn.postTime.takeIf { it > 0 } ?: System.currentTimeMillis()

        scope.launch {
            runCatching {
                val pendingId = container.notificationIntake
                    .handle(packageName, title, text, postedAt) ?: return@runCatching

                val notifier = PendingNotifier(applicationContext)
                if (container.settings.current().notifyOnCapture) {
                    val operation = container.repository.getPendingOperation(pendingId)
                    if (operation != null) {
                        notifier.notifyPending(operation)
                        container.diagnosticsLog.log(
                            stage = LogStage.SERVICE,
                            message = "Показано своё уведомление по операции #$pendingId",
                        )
                    }
                } else {
                    container.diagnosticsLog.log(
                        stage = LogStage.SERVICE,
                        message = "Своё уведомление не показано: отключено в настройках",
                    )
                }

                // Очередь могла переполниться этой операцией — напоминаем разобрать.
                container.reminders.notifyIfQueueOverflowed()
            }.onFailure { error ->
                container.diagnosticsLog.log(
                    stage = LogStage.SERVICE,
                    message = "Сбой при обработке уведомления: ${error.javaClass.simpleName}",
                    details = error.message,
                    level = LogLevel.ERROR,
                )
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
