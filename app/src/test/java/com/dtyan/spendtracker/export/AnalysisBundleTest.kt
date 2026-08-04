package com.dtyan.spendtracker.export

import com.dtyan.spendtracker.domain.MoneyFormat
import com.dtyan.spendtracker.domain.model.ExpenseRecord
import com.dtyan.spendtracker.domain.model.PaymentMethod
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

class AnalysisBundleTest {

    private val today = LocalDate.of(2026, 7, 28)

    private var nextId = 1L

    private fun record(
        amountMinor: Long,
        date: LocalDate,
        category: String = "Продукты",
        subcategory: String? = "Супермаркет",
        note: String = "",
        paymentMethod: PaymentMethod = PaymentMethod.CARD,
    ) = ExpenseRecord(
        id = nextId++,
        amountMinor = amountMinor,
        currency = "RUB",
        categoryId = category.hashCode().toLong(),
        categoryName = category,
        subcategoryId = subcategory?.hashCode()?.toLong(),
        subcategoryName = subcategory,
        date = date,
        note = note,
        paymentMethod = paymentMethod,
        createdAt = 0L,
    )

    /** Небольшой, но разнообразный набор трат за апрель—июль 2026. */
    private fun sample(): List<ExpenseRecord> = listOf(
        record(150_000, LocalDate.of(2026, 4, 5), "Продукты", "Супермаркет", "Пятёрочка"),
        record(50_000, LocalDate.of(2026, 4, 12), "Транспорт", "Такси", "До вокзала", PaymentMethod.ONLINE),
        record(300_000, LocalDate.of(2026, 5, 3), "Жильё", "Аренда", "Май", PaymentMethod.TRANSFER),
        record(120_000, LocalDate.of(2026, 5, 20), "Продукты", "Супермаркет"),
        record(300_000, LocalDate.of(2026, 6, 3), "Жильё", "Аренда", "Июнь", PaymentMethod.TRANSFER),
        record(80_000, LocalDate.of(2026, 6, 15), "Кафе", null, "Обед", PaymentMethod.CASH),
        record(300_000, LocalDate.of(2026, 7, 3), "Жильё", "Аренда", "Июль", PaymentMethod.TRANSFER),
        record(25_000, LocalDate.of(2026, 7, 10), "Продукты", "Супермаркет"),
    )

    private fun section(markdown: String, heading: String): String {
        val start = markdown.indexOf(heading)
        assertThat(start).isAtLeast(0)
        val next = markdown.indexOf("\n## ", start + heading.length)
        return if (next < 0) markdown.substring(start) else markdown.substring(start, next)
    }

    @Test
    fun `пустой список не падает и объясняет что данных нет`() {
        val md = AnalysisBundle.build(emptyList(), today)
        assertThat(md).contains("# Отчёт по личным тратам")
        assertThat(md).contains("Данных нет")
        assertThat(md.length).isLessThan(1000)
    }

    @Test
    fun `есть все ключевые секции`() {
        val md = AnalysisBundle.build(sample(), today)
        assertThat(md).contains("# Отчёт по личным тратам")
        assertThat(md).contains("## Задача для ИИ")
        assertThat(md).contains("## Сводка")
        assertThat(md).contains("## Помесячно")
        assertThat(md).contains("## По категориям за всё время")
        assertThat(md).contains("## Топ-15 подкатегорий")
        assertThat(md).contains("## Последние 3 полных месяца по категориям")
        assertThat(md).contains("## Топ-20 самых крупных трат")
        assertThat(md).contains("## Возможные регулярные платежи")
        assertThat(md).contains("## Распределение по способам оплаты")
        assertThat(md).contains("## Распределение по дням недели")
    }

    @Test
    fun `инструкция для ИИ содержит просьбу об экономии и указание на рубли`() {
        val md = AnalysisBundle.build(sample(), today)
        val intro = section(md, "## Задача для ИИ")
        assertThat(intro).contains("рублях")
        assertThat(intro).contains("экономии")
        assertThat(intro).contains("личных расходов одного человека")
    }

    @Test
    fun `сводка считает итог, количество и период верно`() {
        val records = sample()
        val md = AnalysisBundle.build(records, today)
        val total = records.sumOf { it.amountMinor }
        assertThat(total).isEqualTo(1_325_000L)

        val summary = section(md, "## Сводка")
        assertThat(summary).contains("- Общая сумма: ${MoneyFormat.format(total)}")
        assertThat(summary).contains("- Количество трат: ${records.size}")
        assertThat(summary).contains("- Средний чек: ${MoneyFormat.format(total / records.size)}")
        assertThat(summary).contains("05.04.2026 — 10.07.2026")
    }

    @Test
    fun `помесячная таблица содержит все месяцы диапазона`() {
        val md = AnalysisBundle.build(sample(), today)
        val monthly = section(md, "## Помесячно")
        assertThat(monthly).contains("Апрель 2026")
        assertThat(monthly).contains("Май 2026")
        assertThat(monthly).contains("Июнь 2026")
        assertThat(monthly).contains("Июль 2026")
        // Апрель: 150 000 + 50 000 копеек = 2000 ₽
        assertThat(monthly).contains(MoneyFormat.format(200_000L))
    }

    @Test
    fun `категории посчитаны и отсортированы по убыванию`() {
        val md = AnalysisBundle.build(sample(), today)
        val categories = section(md, "## По категориям за всё время")
        // Жильё 9000 ₽ > Продукты 2950 ₽ > Кафе 800 ₽ > Транспорт 500 ₽
        assertThat(categories).contains(MoneyFormat.format(900_000L))
        assertThat(categories).contains(MoneyFormat.format(295_000L))
        assertThat(categories.indexOf("Жильё")).isLessThan(categories.indexOf("Продукты"))
        assertThat(categories.indexOf("Продукты")).isLessThan(categories.indexOf("Транспорт"))
    }

    @Test
    fun `трата без подкатегории не ломает таблицу подкатегорий`() {
        val md = AnalysisBundle.build(sample(), today)
        val subs = section(md, "## Топ-15 подкатегорий")
        assertThat(subs).contains("Кафе / (без подкатегории)")
    }

    @Test
    fun `крупные траты отсортированы по убыванию суммы`() {
        val records = listOf(
            record(10_000, LocalDate.of(2026, 5, 1), note = "самая мелкая"),
            record(900_000, LocalDate.of(2026, 5, 2), note = "самая крупная"),
            record(300_000, LocalDate.of(2026, 5, 3), note = "средняя"),
        )
        val top = section(AnalysisBundle.build(records, today), "## Топ-20 самых крупных трат")
        val biggest = top.indexOf("самая крупная")
        val middle = top.indexOf("средняя")
        val smallest = top.indexOf("самая мелкая")
        assertThat(biggest).isAtLeast(0)
        assertThat(biggest).isLessThan(middle)
        assertThat(middle).isLessThan(smallest)
    }

    @Test
    fun `регулярный платёж в четырёх месяцах попадает в кандидаты`() {
        val records = listOf(
            record(59_900, LocalDate.of(2026, 3, 5), "Подписки", "Кинотеатр", "Списание"),
            record(59_900, LocalDate.of(2026, 4, 5), "Подписки", "Кинотеатр", "Списание"),
            record(59_900, LocalDate.of(2026, 5, 5), "Подписки", "Кинотеатр", "Списание"),
            record(59_900, LocalDate.of(2026, 6, 5), "Подписки", "Кинотеатр", "Списание"),
            record(123_400, LocalDate.of(2026, 6, 9), "Продукты", "Супермаркет"),
        )
        val recurring = section(
            AnalysisBundle.build(records, today),
            "## Возможные регулярные платежи",
        )
        assertThat(recurring).contains("Подписки / Кинотеатр")
        assertThat(recurring).contains(MoneyFormat.format(59_900L))
        // Разовая трата в подписки не попадает.
        assertThat(recurring).doesNotContain("Продукты / Супермаркет")
    }

    @Test
    fun `близкие по величине платежи склеиваются в одну регулярную группу`() {
        // 510, 540 и 520 рублей округляются к одной и той же сотне (500) — это один платёж.
        val records = listOf(
            record(51_000, LocalDate.of(2026, 3, 5), "Связь", "Мобильный"),
            record(54_000, LocalDate.of(2026, 4, 5), "Связь", "Мобильный"),
            record(52_000, LocalDate.of(2026, 5, 5), "Связь", "Мобильный"),
        )
        val recurring = section(
            AnalysisBundle.build(records, today),
            "## Возможные регулярные платежи",
        )
        assertThat(recurring).contains("Связь / Мобильный")
    }

    @Test
    fun `при отсутствии повторов секция регулярных платежей не пуста по смыслу`() {
        val records = listOf(
            record(10_000, LocalDate.of(2026, 5, 1), "Кафе", null),
            record(20_000, LocalDate.of(2026, 6, 1), "Транспорт", "Метро"),
        )
        val recurring = section(
            AnalysisBundle.build(records, today),
            "## Возможные регулярные платежи",
        )
        assertThat(recurring).contains("Регулярных платежей не обнаружено")
    }

    @Test
    fun `способы оплаты и дни недели перечислены`() {
        val md = AnalysisBundle.build(sample(), today)
        val methods = section(md, "## Распределение по способам оплаты")
        assertThat(methods).contains("Карта")
        assertThat(methods).contains("Перевод")
        assertThat(methods).contains("Наличные")

        val weekdays = section(md, "## Распределение по дням недели")
        assertThat(weekdays).contains("Понедельник")
        assertThat(weekdays).contains("Воскресенье")
    }

    @Test
    fun `сравнение последних полных месяцев показывает три колонки`() {
        val md = AnalysisBundle.build(sample(), today)
        val comparison = section(md, "## Последние 3 полных месяца по категориям")
        // today = 28.07.2026, значит полные месяцы — апрель, май, июнь.
        assertThat(comparison).contains("Апрель 2026")
        assertThat(comparison).contains("Май 2026")
        assertThat(comparison).contains("Июнь 2026")
        assertThat(comparison).contains("Жильё")
        assertThat(comparison).contains("**Итого**")
    }

    @Test
    fun `одна трата обрабатывается без ошибок`() {
        val md = AnalysisBundle.build(
            listOf(record(1, LocalDate.of(2026, 7, 1), "Прочее", null)),
            today,
        )
        assertThat(md).contains("- Количество трат: 1")
        assertThat(md).contains("## Топ-20 самых крупных трат")
    }

    @Test
    fun `комментарий с вертикальной чертой экранируется`() {
        val md = AnalysisBundle.build(
            listOf(record(500_000, LocalDate.of(2026, 7, 1), note = "а | б")),
            today,
        )
        assertThat(md).contains("а \\| б")
    }
}
