package com.dtyan.spendtracker

import android.content.Context
import com.dtyan.spendtracker.data.DiagnosticsLog
import com.dtyan.spendtracker.data.ExpenseRepository
import com.dtyan.spendtracker.data.SettingsStore
import com.dtyan.spendtracker.data.db.AppDatabase
import com.dtyan.spendtracker.notifications.NotificationIntake
import com.dtyan.spendtracker.notifications.Reminders

/**
 * Минималистичный ручной DI: одна зависимость на всё приложение.
 * Hilt здесь избыточен — компонентов мало, а сборка быстрее.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val database = AppDatabase.get(context)

    val repository = ExpenseRepository(
        database.categoryDao(),
        database.expenseDao(),
        database.importBatchDao(),
        database.pendingOperationDao(),
    )

    /** Настройки автоучёта: их читает и UI, и сервис уведомлений. */
    val settings = SettingsStore(appContext)

    /** Журнал диагностики: пишут сервис, приём уведомлений и экраны, читает экран «Журнал». */
    val diagnosticsLog = DiagnosticsLog(database.diagLogDao(), settings)

    /** Приём уведомлений: разбор + постановка в очередь подтверждения. */
    val notificationIntake = NotificationIntake(repository, settings, diagnosticsLog)

    /** Напоминания: переполнение очереди и «давно не заходили». */
    val reminders = Reminders(appContext, repository, settings, diagnosticsLog)
}
