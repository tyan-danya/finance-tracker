package com.dtyan.spendtracker.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.dtyan.spendtracker.MainActivity
import com.dtyan.spendtracker.R
import com.dtyan.spendtracker.domain.MoneyFormat
import com.dtyan.spendtracker.domain.model.PendingOperation
import com.dtyan.spendtracker.domain.model.PendingStatus

/**
 * Своё уведомление о том, что приложение распознало новую операцию.
 *
 * Смысл — не «оповестить о трате» (об этом уже сказал банк), а дать одно нажатие:
 * подтвердить прямо из шторки, если категория подобралась, либо открыть очередь и разобрать.
 * Ничего не подтверждается само — только по действию пользователя.
 */
class PendingNotifier(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Новые операции",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Траты, распознанные из банковских уведомлений и ожидающие подтверждения"
            setShowBadge(true)
        }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    /** Показывает уведомление об одной распознанной операции. Тихо выходит, если уведомления запрещены. */
    fun notifyPending(operation: PendingOperation) {
        if (!manager.areNotificationsEnabled()) return
        ensureChannel()

        val amount = (if (operation.isIncome) "+" else "") + MoneyFormat.format(operation.amountMinor)
        val title = if (operation.status == PendingStatus.UNPARSED) {
            "Не распознанная операция"
        } else {
            "$amount · ${operation.displayTitle}"
        }
        val category = operation.categoryName?.let { name ->
            operation.subcategoryName?.let { "$name / $it" } ?: name
        }
        val text = when {
            operation.status == PendingStatus.UNPARSED -> "Нажмите, чтобы разобрать вручную"
            category != null -> "$category · ${operation.bankTitle} · нажмите, чтобы проверить"
            else -> "${operation.bankTitle} · нужно выбрать категорию"
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text + "\n" + operation.rawText))
            .setContentIntent(openPendingIntent(operation.id))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        // «Подтвердить» показываем, только если подтверждать нечего доуточнять:
        // сумма распознана и категория подобрана. Иначе — только разбор в приложении.
        if (operation.isReadyToConfirm) {
            builder.addAction(
                0,
                "Подтвердить",
                actionIntent(PendingActionReceiver.ACTION_CONFIRM, operation.id),
            )
        }
        builder.addAction(
            0,
            "Отклонить",
            actionIntent(PendingActionReceiver.ACTION_REJECT, operation.id),
        )

        runCatching { manager.notify(notificationId(operation.id), builder.build()) }
    }

    /** Убирает уведомление конкретной операции (после подтверждения/отклонения). */
    fun cancel(pendingId: Long) {
        runCatching { manager.cancel(notificationId(pendingId)) }
    }

    private fun openPendingIntent(pendingId: Long): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .putExtra(MainActivity.EXTRA_OPEN_PENDING, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            requestCode(pendingId, OPEN),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun actionIntent(action: String, pendingId: Long): PendingIntent {
        val intent = Intent(context, PendingActionReceiver::class.java)
            .setAction(action)
            .putExtra(PendingActionReceiver.EXTRA_PENDING_ID, pendingId)
        return PendingIntent.getBroadcast(
            context,
            requestCode(pendingId, if (action == PendingActionReceiver.ACTION_CONFIRM) CONFIRM else REJECT),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun notificationId(pendingId: Long): Int = (BASE_ID + pendingId).toInt()

    private fun requestCode(pendingId: Long, slot: Int): Int = (pendingId * 10 + slot).toInt()

    private companion object {
        const val CHANNEL_ID = "pending_operations"
        const val BASE_ID = 1_000L
        const val OPEN = 0
        const val CONFIRM = 1
        const val REJECT = 2
    }
}
