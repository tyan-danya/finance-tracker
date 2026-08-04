package com.dtyan.spendtracker.domain.stats

import com.dtyan.spendtracker.domain.model.DateRange
import com.dtyan.spendtracker.domain.model.ExpenseRecord
import com.dtyan.spendtracker.domain.model.PaymentMethod
import com.dtyan.spendtracker.domain.model.Period
import java.time.DayOfWeek
import java.time.LocalDate

data class CategoryStat(
    val categoryId: Long,
    val categoryName: String,
    val totalMinor: Long,
    val count: Int,
    /** Доля от общей суммы периода, 0.0..1.0. */
    val share: Double,
)

data class SubcategoryStat(
    val subcategoryId: Long?,
    val subcategoryName: String,
    val categoryId: Long,
    val categoryName: String,
    val totalMinor: Long,
    val count: Int,
    val share: Double,
)

data class PaymentMethodStat(
    val method: PaymentMethod,
    val totalMinor: Long,
    val count: Int,
    val share: Double,
)

data class WeekdayStat(
    val dayOfWeek: DayOfWeek,
    val totalMinor: Long,
    val count: Int,
)

/** Точка временного ряда. [date] — начало интервала (день или первое число месяца). */
data class SeriesPoint(
    val date: LocalDate,
    val label: String,
    val totalMinor: Long,
    val count: Int,
)

/** Сравнение с предыдущим сопоставимым периодом. */
data class Comparison(
    val currentMinor: Long,
    val previousMinor: Long,
) {
    val deltaMinor: Long get() = currentMinor - previousMinor
    /** null, если в прошлом периоде трат не было (деление на ноль). */
    val deltaRatio: Double? get() = if (previousMinor == 0L) null else deltaMinor.toDouble() / previousMinor
}

data class PeriodStats(
    val period: Period,
    val range: DateRange?,
    /** Сумма расходов за период (доходы сюда НЕ входят). */
    val totalMinor: Long,
    /** Число расходных операций. */
    val count: Int,
    /** Сумма пополнений (доходов) за период. */
    val incomeMinor: Long = 0,
    /** Баланс за период: пополнения минус расходы. */
    val balanceMinor: Long = 0,
    val averagePerTransactionMinor: Long,
    val averagePerDayMinor: Long,
    val medianTransactionMinor: Long,
    val maxSingle: ExpenseRecord?,
    val byCategory: List<CategoryStat>,
    val bySubcategory: List<SubcategoryStat>,
    val byPaymentMethod: List<PaymentMethodStat>,
    val byWeekday: List<WeekdayStat>,
    val dailySeries: List<SeriesPoint>,
    val monthlySeries: List<SeriesPoint>,
    val records: List<ExpenseRecord>,
) {
    val isEmpty: Boolean get() = count == 0
    /** Категория, на которую ушло больше всего. */
    val topCategory: CategoryStat? get() = byCategory.firstOrNull()
}
