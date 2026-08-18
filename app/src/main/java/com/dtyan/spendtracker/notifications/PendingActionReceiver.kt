package com.dtyan.spendtracker.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dtyan.spendtracker.SpendApp
import com.dtyan.spendtracker.domain.model.ExpenseDraft
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Кнопки «Подтвердить» / «Отклонить» в уведомлении о распознанной операции.
 *
 * Приёмник не экспортирован: интенты создаёт только само приложение
 * ([PendingNotifier]), снаружи вызвать подтверждение траты нельзя.
 */
class PendingActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pendingId = intent.getLongExtra(EXTRA_PENDING_ID, -1L)
        if (pendingId <= 0) return
        val action = intent.action ?: return

        val app = context.applicationContext as? SpendApp ?: return
        val repository = app.container.repository
        val notifier = PendingNotifier(context.applicationContext)

        // Работа с БД асинхронна, поэтому держим приёмник живым до конца операции.
        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (action) {
                    ACTION_CONFIRM -> {
                        val operation = repository.getPendingOperation(pendingId)
                        val categoryId = operation?.categoryId
                        // Без категории подтверждать нечего — открывать экран из приёмника нельзя,
                        // поэтому просто оставляем операцию в очереди.
                        if (operation != null && categoryId != null) {
                            repository.confirmPendingOperation(
                                id = pendingId,
                                draft = ExpenseDraft(
                                    amountMinor = operation.amountMinor,
                                    categoryId = categoryId,
                                    subcategoryId = operation.subcategoryId,
                                    date = operation.date,
                                    note = operation.merchant.orEmpty(),
                                    paymentMethod = defaultPaymentMethod(operation.type),
                                    currency = operation.currency,
                                    type = operation.type,
                                ),
                            )
                            notifier.cancel(pendingId)
                        }
                    }

                    ACTION_REJECT -> {
                        repository.rejectPendingOperation(pendingId)
                        notifier.cancel(pendingId)
                    }
                }
            } finally {
                result.finish()
            }
        }
    }

    private fun defaultPaymentMethod(type: com.dtyan.spendtracker.domain.model.EntryType) =
        if (type == com.dtyan.spendtracker.domain.model.EntryType.INCOME) {
            com.dtyan.spendtracker.domain.model.PaymentMethod.TRANSFER
        } else {
            com.dtyan.spendtracker.domain.model.PaymentMethod.CARD
        }

    companion object {
        const val ACTION_CONFIRM = "com.dtyan.spendtracker.action.CONFIRM_PENDING"
        const val ACTION_REJECT = "com.dtyan.spendtracker.action.REJECT_PENDING"
        const val EXTRA_PENDING_ID = "pending_id"
    }
}
