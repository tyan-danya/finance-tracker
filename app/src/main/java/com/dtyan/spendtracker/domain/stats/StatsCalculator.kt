package com.dtyan.spendtracker.domain.stats

import com.dtyan.spendtracker.domain.model.DateRange
import com.dtyan.spendtracker.domain.model.EntryType
import com.dtyan.spendtracker.domain.model.ExpenseRecord
import com.dtyan.spendtracker.domain.model.Period
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * Расчёт агрегатов по тратам. Чистый Kotlin без Android-зависимостей —
 * покрывается обычными JVM-тестами.
 *
 * Все суммы в копейках (Long), деления — целочисленные, чтобы не терять копейки на Double.
 */
object StatsCalculator {

    /** Подпись для трат без подкатегории. */
    const val NO_SUBCATEGORY = "Без подкатегории"

    /**
     * Сокращения месяцев собраны вручную: `DateTimeFormatter` с русской локалью
     * даёт разные результаты на разных JDK/CLDR («янв.» / «января»), а подписи графика
     * должны быть детерминированными.
     */
    private val MONTHS_SHORT = arrayOf(
        "янв", "фев", "мар", "апр", "май", "июн",
        "июл", "авг", "сен", "окт", "ноя", "дек",
    )

    /** Порядок дней недели в статистике: понедельник → воскресенье. */
    private val WEEK_ORDER: List<DayOfWeek> = listOf(
        DayOfWeek.MONDAY,
        DayOfWeek.TUESDAY,
        DayOfWeek.WEDNESDAY,
        DayOfWeek.THURSDAY,
        DayOfWeek.FRIDAY,
        DayOfWeek.SATURDAY,
        DayOfWeek.SUNDAY,
    )

    fun compute(all: List<ExpenseRecord>, period: Period): PeriodStats {
        val range = period.range(dataBounds(all)) ?: return emptyStats(period)

        val inRange = all
            .filter { it.date in range }
            .sortedWith(
                compareByDescending<ExpenseRecord> { it.date }
                    .thenByDescending { it.createdAt }
                    .thenByDescending { it.id }
            )

        // Вся аналитика трат считается ТОЛЬКО по расходам; доходы учитываются отдельно.
        val expenses = inRange.filter { it.type == EntryType.EXPENSE }
        val incomes = inRange.filter { it.type == EntryType.INCOME }

        val totalMinor = expenses.sumOf { it.amountMinor }
        val incomeMinor = incomes.sumOf { it.amountMinor }
        val count = expenses.size
        val days = range.days

        return PeriodStats(
            period = period,
            range = range,
            totalMinor = totalMinor,
            count = count,
            incomeMinor = incomeMinor,
            balanceMinor = incomeMinor - totalMinor,
            averagePerTransactionMinor = if (count == 0) 0L else totalMinor / count,
            averagePerDayMinor = if (days <= 0) 0L else totalMinor / days,
            medianTransactionMinor = median(expenses),
            maxSingle = expenses.maxByOrNull { it.amountMinor },
            byCategory = byCategory(expenses, totalMinor),
            bySubcategory = bySubcategory(expenses, totalMinor),
            byPaymentMethod = byPaymentMethod(expenses, totalMinor),
            byWeekday = byWeekday(expenses),
            dailySeries = dailySeries(expenses, range),
            monthlySeries = monthlySeries(expenses, range),
            records = inRange,
        )
    }

    fun compare(all: List<ExpenseRecord>, period: Period): Comparison {
        val bounds = dataBounds(all)
        val current = sumIn(all, period.range(bounds))
        val previousPeriod = period.previous()
        val previous = if (previousPeriod == null) 0L else sumIn(all, previousPeriod.range(bounds))
        return Comparison(currentMinor = current, previousMinor = previous)
    }

    fun dataBounds(all: List<ExpenseRecord>): DateRange? {
        if (all.isEmpty()) return null
        var min = all[0].date
        var max = all[0].date
        for (r in all) {
            if (r.date.isBefore(min)) min = r.date
            if (r.date.isAfter(max)) max = r.date
        }
        return DateRange(min, max)
    }

    /** Месяцы, в которых есть хотя бы одна трата, по убыванию. */
    fun availableMonths(all: List<ExpenseRecord>): List<YearMonth> =
        all.map { YearMonth.from(it.date) }
            .distinct()
            .sortedDescending()

    // --- внутреннее ---

    private fun sumIn(all: List<ExpenseRecord>, range: DateRange?): Long {
        if (range == null) return 0L
        // Сравнение периодов — про расходы.
        return all.filter { it.date in range && it.type == EntryType.EXPENSE }
            .sumOf { it.amountMinor }
    }

    private fun emptyStats(period: Period) = PeriodStats(
        period = period,
        range = null,
        totalMinor = 0L,
        count = 0,
        averagePerTransactionMinor = 0L,
        averagePerDayMinor = 0L,
        medianTransactionMinor = 0L,
        maxSingle = null,
        byCategory = emptyList(),
        bySubcategory = emptyList(),
        byPaymentMethod = emptyList(),
        byWeekday = emptyList(),
        dailySeries = emptyList(),
        monthlySeries = emptyList(),
        records = emptyList(),
    )

    private fun median(records: List<ExpenseRecord>): Long {
        if (records.isEmpty()) return 0L
        val sorted = records.map { it.amountMinor }.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[mid]
        } else {
            // Среднее двух центральных, целочисленно.
            (sorted[mid - 1] + sorted[mid]) / 2
        }
    }

    private fun share(part: Long, total: Long): Double =
        if (total == 0L) 0.0 else part.toDouble() / total.toDouble()

    private fun byCategory(records: List<ExpenseRecord>, totalMinor: Long): List<CategoryStat> =
        records.groupBy { it.categoryId }
            .map { (categoryId, group) ->
                val sum = group.sumOf { it.amountMinor }
                CategoryStat(
                    categoryId = categoryId,
                    categoryName = group.first().categoryName,
                    totalMinor = sum,
                    count = group.size,
                    share = share(sum, totalMinor),
                )
            }
            .sortedWith(
                compareByDescending<CategoryStat> { it.totalMinor }
                    .thenBy { it.categoryName }
                    .thenBy { it.categoryId }
            )

    private fun bySubcategory(records: List<ExpenseRecord>, totalMinor: Long): List<SubcategoryStat> =
        records.groupBy { it.categoryId to it.subcategoryId }
            .map { (key, group) ->
                val sum = group.sumOf { it.amountMinor }
                val first = group.first()
                SubcategoryStat(
                    subcategoryId = key.second,
                    subcategoryName = if (key.second == null) {
                        NO_SUBCATEGORY
                    } else {
                        first.subcategoryName ?: NO_SUBCATEGORY
                    },
                    categoryId = key.first,
                    categoryName = first.categoryName,
                    totalMinor = sum,
                    count = group.size,
                    share = share(sum, totalMinor),
                )
            }
            .sortedWith(
                compareByDescending<SubcategoryStat> { it.totalMinor }
                    .thenBy { it.categoryName }
                    .thenBy { it.subcategoryName }
            )

    private fun byPaymentMethod(records: List<ExpenseRecord>, totalMinor: Long): List<PaymentMethodStat> =
        records.groupBy { it.paymentMethod }
            .map { (method, group) ->
                val sum = group.sumOf { it.amountMinor }
                PaymentMethodStat(
                    method = method,
                    totalMinor = sum,
                    count = group.size,
                    share = share(sum, totalMinor),
                )
            }
            .sortedWith(
                compareByDescending<PaymentMethodStat> { it.totalMinor }
                    .thenBy { it.method.ordinal }
            )

    /** Ровно 7 элементов, понедельник → воскресенье, с нулями для пустых дней. */
    private fun byWeekday(records: List<ExpenseRecord>): List<WeekdayStat> {
        val grouped = records.groupBy { it.date.dayOfWeek }
        return WEEK_ORDER.map { dow ->
            val group = grouped[dow].orEmpty()
            WeekdayStat(
                dayOfWeek = dow,
                totalMinor = group.sumOf { it.amountMinor },
                count = group.size,
            )
        }
    }

    /** По точке на каждый день диапазона включительно (zero-filled). */
    private fun dailySeries(records: List<ExpenseRecord>, range: DateRange): List<SeriesPoint> {
        val grouped = records.groupBy { it.date }
        val result = ArrayList<SeriesPoint>(range.days)
        var day = range.start
        while (!day.isAfter(range.endInclusive)) {
            val group = grouped[day].orEmpty()
            result += SeriesPoint(
                date = day,
                label = dayLabel(day),
                totalMinor = group.sumOf { it.amountMinor },
                count = group.size,
            )
            day = day.plusDays(1)
        }
        return result
    }

    /** По точке на каждый месяц, задетый диапазоном (zero-filled). */
    private fun monthlySeries(records: List<ExpenseRecord>, range: DateRange): List<SeriesPoint> {
        val grouped = records.groupBy { YearMonth.from(it.date) }
        val last = YearMonth.from(range.endInclusive)
        val result = ArrayList<SeriesPoint>()
        var month = YearMonth.from(range.start)
        while (!month.isAfter(last)) {
            val group = grouped[month].orEmpty()
            result += SeriesPoint(
                date = month.atDay(1),
                label = monthLabel(month),
                totalMinor = group.sumOf { it.amountMinor },
                count = group.size,
            )
            month = month.plusMonths(1)
        }
        return result
    }

    /** "dd.MM" — собираем вручную, чтобы не зависеть от локали. */
    private fun dayLabel(date: LocalDate): String =
        date.dayOfMonth.toString().padStart(2, '0') + "." +
            date.monthValue.toString().padStart(2, '0')

    /** "янв 25" */
    private fun monthLabel(month: YearMonth): String {
        val name = MONTHS_SHORT[month.monthValue - 1]
        val year = ((month.year % 100) + 100) % 100
        return name + " " + year.toString().padStart(2, '0')
    }
}
