package com.dtyan.spendtracker.export

import com.dtyan.spendtracker.domain.MoneyFormat
import com.dtyan.spendtracker.domain.model.ExpenseRecord
import com.dtyan.spendtracker.domain.model.Period
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Готовый markdown-отчёт, который пользователь отдаёт языковой модели с просьбой
 * оптимизировать траты.
 *
 * Отчёт самодостаточный (в нём есть и инструкция для ИИ, и все нужные агрегаты)
 * и компактный: сырые транзакции целиком не выгружаются, только сводные таблицы
 * и топы. Все агрегаты считаются здесь же, без внешних зависимостей, чтобы модуль
 * не ломался от изменений в статистике.
 */
object AnalysisBundle {

    private val RU = Locale("ru")
    private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")

    /** Сколько месяцев показывать в помесячной таблице (иначе отчёт разрастается). */
    private const val MAX_MONTH_ROWS = 24
    private const val TOP_SUBCATEGORIES = 15
    private const val TOP_EXPENSES = 20

    /** Шаг округления суммы при поиске регулярных платежей — 100 ₽ в копейках. */
    private const val RECURRING_BUCKET_MINOR = 10_000L

    /** В скольких разных месяцах должна встретиться группа, чтобы считаться регулярной. */
    private const val RECURRING_MIN_MONTHS = 3

    private const val NO_SUBCATEGORY = "(без подкатегории)"

    fun build(input: List<ExpenseRecord>, today: LocalDate): String {
        // Отчёт об оптимизации — только про расходы; пополнения (доходы) исключаем.
        val all = input.filter { it.type == com.dtyan.spendtracker.domain.model.EntryType.EXPENSE }
        if (all.isEmpty()) return emptyReport(today)

        val sorted = all.sortedBy { it.date }
        val first = sorted.first().date
        val last = sorted.last().date
        val total = all.sumOf { it.amountMinor }

        val sb = StringBuilder()
        appendIntro(sb, today)
        appendSummary(sb, all, first, last, total)
        appendMonthly(sb, all)
        appendCategories(sb, all, total)
        appendSubcategories(sb, all, total)
        appendLastMonthsByCategory(sb, all, today)
        appendTopExpenses(sb, all)
        appendRecurring(sb, all)
        appendPaymentMethods(sb, all, total)
        appendWeekdays(sb, all, total)
        return sb.toString()
    }

    // ------------------------------------------------------------------ пусто

    private fun emptyReport(today: LocalDate): String = buildString {
        appendLine("# Отчёт по личным тратам")
        appendLine()
        appendLine("Дата формирования: ${today.format(DAY_FORMAT)}")
        appendLine()
        appendLine("## Сводка")
        appendLine()
        appendLine("Данных нет: за всё время не записано ни одной траты.")
        appendLine("Анализировать нечего — сначала нужно внести расходы в приложение.")
    }

    // ------------------------------------------------------------- инструкция

    private fun appendIntro(sb: StringBuilder, today: LocalDate) {
        sb.appendLine("# Отчёт по личным тратам")
        sb.appendLine()
        sb.appendLine("Дата формирования отчёта: ${today.format(DAY_FORMAT)}.")
        sb.appendLine()
        sb.appendLine("## Задача для ИИ")
        sb.appendLine()
        sb.appendLine(
            "Ниже — выгрузка личных расходов одного человека из приложения учёта трат. " +
                "Все суммы указаны в рублях (₽), это фактические траты, а не доходы и не бюджеты. " +
                "Данные уже агрегированы: отдельные транзакции приведены только в топах."
        )
        sb.appendLine()
        sb.appendLine("Проанализируй эти данные и дай развёрнутый ответ по пунктам:")
        sb.appendLine()
        sb.appendLine("1. Структура трат: на что реально уходят деньги, как распределение менялось по месяцам.")
        sb.appendLine("2. Аномалии: разовые всплески, нетипично дорогие месяцы или категории, подозрительные повторы.")
        sb.appendLine("3. Регулярные и подписочные платежи: что похоже на постоянные списания, сколько они стоят в год.")
        sb.appendLine("4. Категории с потенциалом экономии: где расходы выше разумного и что именно можно урезать.")
        sb.appendLine(
            "5. Конкретный план сокращения расходов: список действий с оценкой экономии в рублях в месяц " +
                "и итоговой суммой экономии. Отдели «безболезненные» меры от тех, что заметно меняют образ жизни."
        )
        sb.appendLine()
        sb.appendLine(
            "Опирайся только на приведённые цифры, не выдумывай транзакций. " +
                "Если данных для вывода не хватает — так и скажи."
        )
        sb.appendLine()
    }

    // ------------------------------------------------------------------ сводка

    private fun appendSummary(
        sb: StringBuilder,
        all: List<ExpenseRecord>,
        first: LocalDate,
        last: LocalDate,
        total: Long,
    ) {
        val monthsSpan = monthsBetweenInclusive(YearMonth.from(first), YearMonth.from(last))
        val averageCheck = if (all.isEmpty()) 0L else total / all.size
        val averagePerMonth = if (monthsSpan == 0) 0L else total / monthsSpan

        sb.appendLine("## Сводка")
        sb.appendLine()
        sb.appendLine("- Период данных: ${first.format(DAY_FORMAT)} — ${last.format(DAY_FORMAT)} (месяцев: $monthsSpan)")
        sb.appendLine("- Количество трат: ${all.size}")
        sb.appendLine("- Общая сумма: ${MoneyFormat.format(total)}")
        sb.appendLine("- Средний чек: ${MoneyFormat.format(averageCheck)}")
        sb.appendLine("- Средние траты в месяц: ${MoneyFormat.format(averagePerMonth)}")
        sb.appendLine()
    }

    // -------------------------------------------------------------- помесячно

    private fun appendMonthly(sb: StringBuilder, all: List<ExpenseRecord>) {
        val totals = monthTotals(all)
        val counts = monthCounts(all)
        val months = continuousMonths(all)
        val shown = if (months.size > MAX_MONTH_ROWS) months.takeLast(MAX_MONTH_ROWS) else months

        sb.appendLine("## Помесячно")
        sb.appendLine()
        if (months.size > shown.size) {
            sb.appendLine("_Показаны последние ${shown.size} мес. из ${months.size}._")
            sb.appendLine()
        }
        sb.appendLine("| Месяц | Сумма | Трат | Изменение к пред. месяцу |")
        sb.appendLine("|---|---:|---:|---:|")
        for (month in shown) {
            val current = totals[month] ?: 0L
            val previous = totals[month.minusMonths(1)] ?: 0L
            val delta = when {
                previous == 0L && current == 0L -> "—"
                previous == 0L -> "новый месяц (+${MoneyFormat.format(current)})"
                else -> {
                    val diff = current - previous
                    val ratio = diff.toDouble() / previous
                    val sign = if (diff > 0) "+" else ""
                    "$sign${MoneyFormat.formatPercent(ratio)} ($sign${MoneyFormat.format(diff)})"
                }
            }
            sb.appendLine(
                "| ${monthTitle(month)} | ${MoneyFormat.format(current)} | ${counts[month] ?: 0} | $delta |"
            )
        }
        sb.appendLine()
    }

    // ------------------------------------------------------------- категории

    private fun appendCategories(sb: StringBuilder, all: List<ExpenseRecord>, total: Long) {
        sb.appendLine("## По категориям за всё время")
        sb.appendLine()
        sb.appendLine("| Категория | Сумма | Доля | Трат | Средний чек |")
        sb.appendLine("|---|---:|---:|---:|---:|")
        all.groupBy { it.categoryName }
            .map { (name, items) -> Triple(name, items.sumOf { it.amountMinor }, items.size) }
            .sortedWith(compareByDescending<Triple<String, Long, Int>> { it.second }.thenBy { it.first })
            .forEach { (name, sum, count) ->
                val share = if (total == 0L) 0.0 else sum.toDouble() / total
                val average = if (count == 0) 0L else sum / count
                sb.appendLine(
                    "| ${cell(name)} | ${MoneyFormat.format(sum)} | ${MoneyFormat.formatPercent(share)} " +
                        "| $count | ${MoneyFormat.format(average)} |"
                )
            }
        sb.appendLine()
    }

    private fun appendSubcategories(sb: StringBuilder, all: List<ExpenseRecord>, total: Long) {
        sb.appendLine("## Топ-$TOP_SUBCATEGORIES подкатегорий")
        sb.appendLine()
        sb.appendLine("| Категория / подкатегория | Сумма | Доля | Трат |")
        sb.appendLine("|---|---:|---:|---:|")
        all.groupBy { it.categoryName to (it.subcategoryName ?: NO_SUBCATEGORY) }
            .map { (key, items) -> Triple(key, items.sumOf { it.amountMinor }, items.size) }
            .sortedWith(
                compareByDescending<Triple<Pair<String, String>, Long, Int>> { it.second }
                    .thenBy { it.first.first }
                    .thenBy { it.first.second }
            )
            .take(TOP_SUBCATEGORIES)
            .forEach { (key, sum, count) ->
                val share = if (total == 0L) 0.0 else sum.toDouble() / total
                sb.appendLine(
                    "| ${cell(key.first)} / ${cell(key.second)} | ${MoneyFormat.format(sum)} " +
                        "| ${MoneyFormat.formatPercent(share)} | $count |"
                )
            }
        sb.appendLine()
    }

    // -------------------------------- последние 3 полных месяца по категориям

    private fun appendLastMonthsByCategory(sb: StringBuilder, all: List<ExpenseRecord>, today: LocalDate) {
        sb.appendLine("## Последние 3 полных месяца по категориям")
        sb.appendLine()

        val currentMonth = YearMonth.from(today)
        val candidates = (3 downTo 1).map { currentMonth.minusMonths(it.toLong()) }
        val dataMonths = all.map { YearMonth.from(it.date) }.distinct().sorted()
        val months = if (candidates.any { it in dataMonths }) {
            candidates
        } else {
            // Данных за последние полные месяцы нет — показываем три последних месяца с данными.
            dataMonths.takeLast(3)
        }

        if (months.isEmpty()) {
            sb.appendLine("Недостаточно данных.")
            sb.appendLine()
            return
        }

        val byCategoryMonth = HashMap<Pair<String, YearMonth>, Long>()
        for (record in all) {
            val key = record.categoryName to YearMonth.from(record.date)
            byCategoryMonth[key] = (byCategoryMonth[key] ?: 0L) + record.amountMinor
        }
        val categories = all.map { it.categoryName }.distinct()
            .filter { category -> months.any { (byCategoryMonth[category to it] ?: 0L) > 0L } }
            .sortedWith(
                compareByDescending<String> { category ->
                    months.sumOf { byCategoryMonth[category to it] ?: 0L }
                }.thenBy { it }
            )

        if (categories.isEmpty()) {
            sb.appendLine("За эти месяцы трат нет.")
            sb.appendLine()
            return
        }

        sb.append("| Категория |")
        months.forEach { sb.append(" ${monthTitle(it)} |") }
        sb.appendLine()
        sb.append("|---|")
        months.forEach { _ -> sb.append("---:|") }
        sb.appendLine()

        for (category in categories) {
            sb.append("| ${cell(category)} |")
            months.forEach { month ->
                sb.append(" ${MoneyFormat.format(byCategoryMonth[category to month] ?: 0L)} |")
            }
            sb.appendLine()
        }
        sb.append("| **Итого** |")
        months.forEach { month ->
            val sum = categories.sumOf { byCategoryMonth[it to month] ?: 0L }
            sb.append(" **${MoneyFormat.format(sum)}** |")
        }
        sb.appendLine()
        sb.appendLine()
    }

    // ---------------------------------------------------------- крупные траты

    private fun appendTopExpenses(sb: StringBuilder, all: List<ExpenseRecord>) {
        sb.appendLine("## Топ-$TOP_EXPENSES самых крупных трат")
        sb.appendLine()
        sb.appendLine("| Дата | Сумма | Категория / подкатегория | Комментарий |")
        sb.appendLine("|---|---:|---|---|")
        all.sortedWith(compareByDescending<ExpenseRecord> { it.amountMinor }.thenBy { it.date }.thenBy { it.id })
            .take(TOP_EXPENSES)
            .forEach { record ->
                sb.appendLine(
                    "| ${record.date.format(DAY_FORMAT)} | ${MoneyFormat.format(record.amountMinor)} " +
                        "| ${cell(record.categoryName)} / ${cell(record.subcategoryName ?: NO_SUBCATEGORY)} " +
                        "| ${cell(record.note)} |"
                )
            }
        sb.appendLine()
    }

    // ------------------------------------------------------ регулярные платежи

    private fun appendRecurring(sb: StringBuilder, all: List<ExpenseRecord>) {
        sb.appendLine("## Возможные регулярные платежи")
        sb.appendLine()
        sb.appendLine(
            "_Эвристика: траты сгруппированы по категории, подкатегории и сумме, округлённой до 100 ₽. " +
                "Показаны группы, встречающиеся в $RECURRING_MIN_MONTHS и более разных месяцах — " +
                "это кандидаты в подписки и постоянные списания._"
        )
        sb.appendLine()

        data class Group(
            val category: String,
            val subcategory: String,
            val bucketMinor: Long,
            val records: MutableList<ExpenseRecord> = mutableListOf(),
        )

        val groups = LinkedHashMap<Triple<String, String, Long>, Group>()
        for (record in all) {
            val bucket = roundToBucket(record.amountMinor)
            val key = Triple(record.categoryName, record.subcategoryName ?: NO_SUBCATEGORY, bucket)
            groups.getOrPut(key) { Group(key.first, key.second, key.third) }.records += record
        }

        val candidates = groups.values
            .map { group -> group to group.records.map { YearMonth.from(it.date) }.distinct() }
            .filter { (_, months) -> months.size >= RECURRING_MIN_MONTHS }
            .sortedWith(
                compareByDescending<Pair<Group, List<YearMonth>>> { it.first.records.sumOf { r -> r.amountMinor } }
                    .thenBy { it.first.category }
                    .thenBy { it.first.subcategory }
            )

        if (candidates.isEmpty()) {
            sb.appendLine("Регулярных платежей не обнаружено.")
            sb.appendLine()
            return
        }

        sb.appendLine("| Категория / подкатегория | Типичная сумма | Платежей | Месяцев | Всего | Первый | Последний |")
        sb.appendLine("|---|---:|---:|---:|---:|---|---|")
        for ((group, months) in candidates) {
            val sum = group.records.sumOf { it.amountMinor }
            val typical = sum / group.records.size
            val dates = group.records.map { it.date }.sorted()
            sb.appendLine(
                "| ${cell(group.category)} / ${cell(group.subcategory)} | ${MoneyFormat.format(typical)} " +
                    "| ${group.records.size} | ${months.size} | ${MoneyFormat.format(sum)} " +
                    "| ${dates.first().format(DAY_FORMAT)} | ${dates.last().format(DAY_FORMAT)} |"
            )
        }
        sb.appendLine()
    }

    /** Округление суммы до ближайших 100 ₽ — чтобы «почти одинаковые» списания попадали в одну группу. */
    private fun roundToBucket(minor: Long): Long =
        Math.round(minor.toDouble() / RECURRING_BUCKET_MINOR) * RECURRING_BUCKET_MINOR

    // --------------------------------------------------- оплата и дни недели

    private fun appendPaymentMethods(sb: StringBuilder, all: List<ExpenseRecord>, total: Long) {
        sb.appendLine("## Распределение по способам оплаты")
        sb.appendLine()
        sb.appendLine("| Способ оплаты | Сумма | Доля | Трат |")
        sb.appendLine("|---|---:|---:|---:|")
        all.groupBy { it.paymentMethod }
            .map { (method, items) -> Triple(method, items.sumOf { it.amountMinor }, items.size) }
            .sortedByDescending { it.second }
            .forEach { (method, sum, count) ->
                val share = if (total == 0L) 0.0 else sum.toDouble() / total
                sb.appendLine(
                    "| ${method.title} | ${MoneyFormat.format(sum)} " +
                        "| ${MoneyFormat.formatPercent(share)} | $count |"
                )
            }
        sb.appendLine()
    }

    private fun appendWeekdays(sb: StringBuilder, all: List<ExpenseRecord>, total: Long) {
        sb.appendLine("## Распределение по дням недели")
        sb.appendLine()
        sb.appendLine("| День недели | Сумма | Доля | Трат |")
        sb.appendLine("|---|---:|---:|---:|")
        val grouped = all.groupBy { it.date.dayOfWeek }
        for (day in DayOfWeek.values()) {
            val items = grouped[day].orEmpty()
            val sum = items.sumOf { it.amountMinor }
            val share = if (total == 0L) 0.0 else sum.toDouble() / total
            sb.appendLine(
                "| ${weekdayTitle(day)} | ${MoneyFormat.format(sum)} " +
                    "| ${MoneyFormat.formatPercent(share)} | ${items.size} |"
            )
        }
        sb.appendLine()
    }

    // ----------------------------------------------------------- вспомогательное

    private fun monthTotals(all: List<ExpenseRecord>): Map<YearMonth, Long> {
        val result = HashMap<YearMonth, Long>()
        for (record in all) {
            val month = YearMonth.from(record.date)
            result[month] = (result[month] ?: 0L) + record.amountMinor
        }
        return result
    }

    private fun monthCounts(all: List<ExpenseRecord>): Map<YearMonth, Int> {
        val result = HashMap<YearMonth, Int>()
        for (record in all) {
            val month = YearMonth.from(record.date)
            result[month] = (result[month] ?: 0) + 1
        }
        return result
    }

    /** Непрерывный список месяцев от первого до последнего месяца с данными (включая пустые). */
    private fun continuousMonths(all: List<ExpenseRecord>): List<YearMonth> {
        if (all.isEmpty()) return emptyList()
        val months = all.map { YearMonth.from(it.date) }
        var cursor = months.min()
        val last = months.max()
        val result = ArrayList<YearMonth>()
        while (!cursor.isAfter(last)) {
            result += cursor
            cursor = cursor.plusMonths(1)
        }
        return result
    }

    private fun monthsBetweenInclusive(from: YearMonth, to: YearMonth): Int =
        ((to.year - from.year) * 12 + (to.monthValue - from.monthValue) + 1).coerceAtLeast(1)

    private fun monthTitle(month: YearMonth): String = Period.Month(month).title

    private fun weekdayTitle(day: DayOfWeek): String =
        day.getDisplayName(TextStyle.FULL_STANDALONE, RU).replaceFirstChar { it.titlecase(RU) }

    /** Готовит произвольный текст к вставке в ячейку markdown-таблицы. */
    private fun cell(raw: String?): String {
        val cleaned = raw.orEmpty()
            .replace("\r\n", " ")
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replace("|", "\\|")
            .trim()
        if (cleaned.isEmpty()) return "—"
        return if (cleaned.length > 60) cleaned.take(59) + "…" else cleaned
    }
}
