package com.dtyan.spendtracker.domain.model

import java.time.LocalDate

/**
 * Тип операции: расход или пополнение (доход). Хранится в БД как имя константы.
 * По умолчанию — расход, чтобы не ломать существующие данные и код.
 */
enum class EntryType(val title: String) {
    EXPENSE("Расход"),
    INCOME("Пополнение");

    companion object {
        fun fromName(raw: String?): EntryType =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: EXPENSE
    }
}

/**
 * Способ оплаты. Хранится в БД как имя константы.
 */
enum class PaymentMethod(val title: String) {
    CARD("Карта"),
    CASH("Наличные"),
    TRANSFER("Перевод"),
    ONLINE("Онлайн"),
    OTHER("Другое");

    companion object {
        fun fromName(raw: String?): PaymentMethod =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: OTHER
    }
}

/**
 * Денормализованная трата с уже подставленными именами категорий.
 * Это основная модель, с которой работают статистика, экспорт и UI.
 *
 * Суммы везде хранятся в минорных единицах (копейках), чтобы не терять точность.
 */
data class ExpenseRecord(
    val id: Long,
    val amountMinor: Long,
    val currency: String,
    val categoryId: Long,
    val categoryName: String,
    val subcategoryId: Long?,
    val subcategoryName: String?,
    val date: LocalDate,
    val note: String,
    val paymentMethod: PaymentMethod,
    val createdAt: Long,
    val type: EntryType = EntryType.EXPENSE,
) {
    val amount: Double get() = amountMinor / 100.0
    val isIncome: Boolean get() = type == EntryType.INCOME
}

data class Category(
    val id: Long,
    val name: String,
    val icon: String,
    val colorArgb: Int,
    val isBuiltIn: Boolean,
    val sortOrder: Int,
    val archived: Boolean,
    /** Категория предназначена для пополнений (доходов), а не расходов. */
    val isIncome: Boolean = false,
)

data class Subcategory(
    val id: Long,
    val categoryId: Long,
    val name: String,
    val isBuiltIn: Boolean,
    val sortOrder: Int,
    val archived: Boolean,
)

data class CategoryTree(
    val category: Category,
    val subcategories: List<Subcategory>,
)

/** Черновик траты/пополнения для создания/редактирования. */
data class ExpenseDraft(
    val id: Long? = null,
    val amountMinor: Long,
    val categoryId: Long,
    val subcategoryId: Long? = null,
    val date: LocalDate,
    val note: String = "",
    val paymentMethod: PaymentMethod = PaymentMethod.CARD,
    val currency: String = "RUB",
    val type: EntryType = EntryType.EXPENSE,
)
