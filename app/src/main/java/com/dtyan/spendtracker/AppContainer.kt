package com.dtyan.spendtracker

import android.content.Context
import com.dtyan.spendtracker.data.ExpenseRepository
import com.dtyan.spendtracker.data.db.AppDatabase

/**
 * Минималистичный ручной DI: одна зависимость на всё приложение.
 * Hilt здесь избыточен — компонентов мало, а сборка быстрее.
 */
class AppContainer(context: Context) {
    private val database = AppDatabase.get(context)
    val repository = ExpenseRepository(
        database.categoryDao(),
        database.expenseDao(),
        database.importBatchDao(),
    )
}
