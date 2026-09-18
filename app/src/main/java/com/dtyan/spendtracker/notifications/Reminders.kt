package com.dtyan.spendtracker.notifications

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.dtyan.spendtracker.MainActivity
import com.dtyan.spendtracker.R
import com.dtyan.spendtracker.SpendApp
import com.dtyan.spendtracker.data.DiagnosticsLog
import com.dtyan.spendtracker.data.ExpenseRepository
import com.dtyan.spendtracker.data.LogLevel
import com.dtyan.spendtracker.data.LogStage
import com.dtyan.spendtracker.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Напоминания разобрать очередь.
 *
 * Два повода:
 *  1. **Очередь переполнилась** — накопилось больше [QUEUE_THRESHOLD] неразобранных операций.
 *     Проверяется сразу при приёме новой операции и в ежедневной проверке.
 *  2. **Давно не заходили** — приложение не открывали дольше суток. Это напоминание
 *     приходит со звуком: тихое напоминание, которое не заметили, бессмысленно.
 *
 * Оба напоминания повторяются не чаще раза в сутки, чтобы не превратиться в спам.
 * Планировщик — `AlarmManager` с неточным повтором: точный будильник требует отдельного
 * разрешения, а для напоминания «вечером» точность до минуты не нужна.
 */
class Reminders(
    private val context: Context,
    private val repository: ExpenseRepository,
    private val settings: SettingsStore,
    private val log: DiagnosticsLog,
) {

    private val manager = NotificationManagerCompat.from(context)

    /** Создаёт канал напоминаний (со звуком) — отдельно от канала новых операций. */
    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Напоминания",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Накопились неразобранные операции или вы давно не заходили"
            enableVibration(true)
        }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    /**
     * Ставит ежедневную проверку напоминаний. Идемпотентно: повторный вызов
     * заменяет уже запланированный будильник.
     */
    fun scheduleDailyCheck() {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = PendingIntent.getBroadcast(
            context,
            REQUEST_DAILY,
            Intent(context, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_DAILY_CHECK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        runCatching {
            alarmManager.setInexactRepeating(
                AlarmManager.RTC_WAKEUP,
                nextCheckTimeMillis(),
                AlarmManager.INTERVAL_DAY,
                intent,
            )
        }
    }

    /** Ближайшие [CHECK_HOUR] часов вечера по локальному времени. */
    private fun nextCheckTimeMillis(): Long {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, CHECK_HOUR)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (calendar.timeInMillis <= System.currentTimeMillis()) {
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }
        return calendar.timeInMillis
    }

    /** Напоминает о переполнении очереди, если порог превышен и недавно не напоминали. */
    suspend fun notifyIfQueueOverflowed() {
        val snapshot = settings.current()
        if (!snapshot.remindQueueOverflow) return

        val count = repository.pendingCount()
        if (count <= QUEUE_THRESHOLD) return

        val since = System.currentTimeMillis() - settings.lastOverflowReminderAt()
        if (since < TimeUnit.HOURS.toMillis(COOLDOWN_HOURS)) return

        settings.setLastOverflowReminderAt(System.currentTimeMillis())
        show(
            id = ID_OVERFLOW,
            title = "Накопилось $count неразобранных операций",
            text = "Загляните в «Черновики»: подтвердите нужные и отклоните лишние, " +
                "пока статистика не отстала от жизни.",
            withSound = false,
        )
        log.log(
            stage = LogStage.SERVICE,
            message = "Напоминание: очередь переполнена ($count операций)",
        )
    }

    /**
     * Ежедневная проверка: переполнение очереди и «давно не заходили».
     * Вызывается из [ReminderReceiver].
     */
    suspend fun runDailyCheck() {
        notifyIfQueueOverflowed()

        val snapshot = settings.current()
        if (!snapshot.remindInactivity) return

        val sinceOpen = System.currentTimeMillis() - settings.lastOpenedAt()
        if (sinceOpen < TimeUnit.DAYS.toMillis(1)) {
            log.log(
                stage = LogStage.SERVICE,
                message = "Ежедневная проверка: приложение открывали меньше суток назад, напоминание не нужно",
            )
            return
        }

        val sinceReminder = System.currentTimeMillis() - settings.lastInactivityReminderAt()
        if (sinceReminder < TimeUnit.HOURS.toMillis(COOLDOWN_HOURS)) return

        settings.setLastInactivityReminderAt(System.currentTimeMillis())
        val pending = repository.pendingCount()
        show(
            id = ID_INACTIVITY,
            title = "Вы не заходили в «Траты» больше суток",
            text = if (pending > 0) {
                "В «Черновиках» ждут $pending операций. Разберите их за пару минут."
            } else {
                "Загляните и внесите траты, пока они не забылись."
            },
            withSound = true,
        )
        log.log(
            stage = LogStage.SERVICE,
            message = "Напоминание: не заходили больше суток (в очереди $pending)",
        )
    }

    private fun show(id: Int, title: String, text: String, withSound: Boolean) {
        if (!manager.areNotificationsEnabled()) return
        ensureChannel()

        val openIntent = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .putExtra(MainActivity.EXTRA_OPEN_PENDING, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(if (withSound) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
        // На Android ниже 8 звук и приоритет задаёт само уведомление, а не канал.
        if (!withSound) builder.setSilent(true)

        runCatching { manager.notify(id, builder.build()) }
    }

    companion object {
        /** Больше этого числа неразобранных операций — пора напомнить. */
        const val QUEUE_THRESHOLD = 10

        private const val CHANNEL_ID = "reminders"
        private const val CHECK_HOUR = 20
        private const val COOLDOWN_HOURS = 20L
        private const val REQUEST_DAILY = 9001
        private const val ID_OVERFLOW = 10
        private const val ID_INACTIVITY = 11
    }
}

/**
 * Будильник напоминаний и восстановление расписания после перезагрузки телефона:
 * после `BOOT_COMPLETED` система сбрасывает все будильники приложения.
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? SpendApp ?: return
        val container = app.container
        val result = goAsync()

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                        container.reminders.scheduleDailyCheck()
                        container.diagnosticsLog.log(
                            stage = LogStage.SERVICE,
                            message = "Расписание напоминаний восстановлено после ${intent.action}",
                        )
                    }

                    ACTION_DAILY_CHECK -> container.reminders.runDailyCheck()
                }
            } catch (error: Throwable) {
                container.diagnosticsLog.log(
                    stage = LogStage.SERVICE,
                    message = "Сбой напоминаний: ${error.javaClass.simpleName}",
                    details = error.message,
                    level = LogLevel.ERROR,
                )
            } finally {
                result.finish()
            }
        }
    }

    companion object {
        const val ACTION_DAILY_CHECK = "com.dtyan.spendtracker.action.DAILY_CHECK"
    }
}
