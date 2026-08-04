package com.dtyan.spendtracker.domain.stats

import com.google.common.truth.Truth.assertThat
import com.dtyan.spendtracker.domain.model.DateRange
import com.dtyan.spendtracker.domain.model.ExpenseRecord
import com.dtyan.spendtracker.domain.model.PaymentMethod
import com.dtyan.spendtracker.domain.model.Period
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * Тесты расчёта статистики. Все даты зафиксированы — никакого LocalDate.now().
 */
class StatsCalculatorTest {

    // --- хелперы ---

    private var nextId = 1L

    private fun rec(
        amount: Long,
        date: LocalDate,
        cat: Long = 1L,
        catName: String = "Еда",
        sub: Long? = null,
        subName: String? = null,
        method: PaymentMethod = PaymentMethod.CARD,
        id: Long = nextId++,
        createdAt: Long = id,
    ): ExpenseRecord = ExpenseRecord(
        id = id,
        amountMinor = amount,
        currency = "RUB",
        categoryId = cat,
        categoryName = catName,
        subcategoryId = sub,
        subcategoryName = subName,
        date = date,
        note = "",
        paymentMethod = method,
        createdAt = createdAt,
    )

    private fun d(year: Int, month: Int, day: Int): LocalDate = LocalDate.of(year, month, day)

    private val jan2025 = Period.Month(YearMonth.of(2025, 1))

    // --- пустой список ---

    @Test
    fun `пустой список — Month даёт пустую статистику с непустым диапазоном`() {
        val stats = StatsCalculator.compute(emptyList(), jan2025)

        assertThat(stats.isEmpty).isTrue()
        assertThat(stats.count).isEqualTo(0)
        assertThat(stats.totalMinor).isEqualTo(0L)
        assertThat(stats.averagePerTransactionMinor).isEqualTo(0L)
        assertThat(stats.averagePerDayMinor).isEqualTo(0L)
        assertThat(stats.medianTransactionMinor).isEqualTo(0L)
        assertThat(stats.maxSingle).isNull()
        assertThat(stats.topCategory).isNull()
        assertThat(stats.byCategory).isEmpty()
        assertThat(stats.bySubcategory).isEmpty()
        assertThat(stats.byPaymentMethod).isEmpty()
        assertThat(stats.records).isEmpty()
        // Диапазон известен и без данных — ряды zero-filled.
        assertThat(stats.range).isEqualTo(DateRange(d(2025, 1, 1), d(2025, 1, 31)))
        assertThat(stats.dailySeries).hasSize(31)
        assertThat(stats.dailySeries.map { it.totalMinor }.toSet()).containsExactly(0L)
        assertThat(stats.byWeekday).hasSize(7)
    }

    @Test
    fun `пустой список — Day, Year и Custom тоже пустые`() {
        val day = StatsCalculator.compute(emptyList(), Period.Day(d(2025, 3, 10)))
        assertThat(day.isEmpty).isTrue()
        assertThat(day.range).isEqualTo(DateRange(d(2025, 3, 10), d(2025, 3, 10)))
        assertThat(day.dailySeries).hasSize(1)

        val year = StatsCalculator.compute(emptyList(), Period.Year(2025))
        assertThat(year.isEmpty).isTrue()
        assertThat(year.totalMinor).isEqualTo(0L)
        assertThat(year.dailySeries).hasSize(365)
        assertThat(year.monthlySeries).hasSize(12)

        val custom = StatsCalculator.compute(emptyList(), Period.Custom(d(2025, 1, 1), d(2025, 1, 10)))
        assertThat(custom.isEmpty).isTrue()
        assertThat(custom.dailySeries).hasSize(10)
    }

    @Test
    fun `AllTime без данных — range null и всё пусто`() {
        val stats = StatsCalculator.compute(emptyList(), Period.AllTime)

        assertThat(stats.range).isNull()
        assertThat(stats.isEmpty).isTrue()
        assertThat(stats.totalMinor).isEqualTo(0L)
        assertThat(stats.count).isEqualTo(0)
        assertThat(stats.records).isEmpty()
        assertThat(stats.dailySeries).isEmpty()
        assertThat(stats.monthlySeries).isEmpty()
        assertThat(stats.byCategory).isEmpty()
        assertThat(stats.bySubcategory).isEmpty()
        assertThat(stats.byPaymentMethod).isEmpty()
        assertThat(stats.byWeekday).isEmpty()
        assertThat(stats.maxSingle).isNull()
        assertThat(stats.period).isEqualTo(Period.AllTime)
    }

    @Test
    fun `AllTime с данными — диапазон охватывает min и max даты`() {
        val all = listOf(
            rec(100_00, d(2024, 5, 17)),
            rec(200_00, d(2025, 2, 3)),
            rec(300_00, d(2024, 12, 31)),
        )

        val stats = StatsCalculator.compute(all, Period.AllTime)

        assertThat(stats.range).isEqualTo(DateRange(d(2024, 5, 17), d(2025, 2, 3)))
        assertThat(stats.count).isEqualTo(3)
        assertThat(stats.totalMinor).isEqualTo(600_00L)
        assertThat(stats.dailySeries).hasSize(stats.range!!.days)
    }

    // --- фильтрация по периодам ---

    @Test
    fun `Month — траты соседних месяцев не попадают`() {
        val all = listOf(
            rec(100_00, d(2024, 12, 31)),
            rec(200_00, d(2025, 1, 15)),
            rec(300_00, d(2025, 2, 1)),
        )

        val stats = StatsCalculator.compute(all, jan2025)

        assertThat(stats.count).isEqualTo(1)
        assertThat(stats.totalMinor).isEqualTo(200_00L)
        assertThat(stats.records.single().date).isEqualTo(d(2025, 1, 15))
    }

    @Test
    fun `Month — первое и последнее число месяца включаются`() {
        val all = listOf(
            rec(100_00, d(2025, 1, 1)),
            rec(700_00, d(2025, 1, 31)),
            rec(999_00, d(2024, 12, 31)),
            rec(999_00, d(2025, 2, 1)),
        )

        val stats = StatsCalculator.compute(all, jan2025)

        assertThat(stats.count).isEqualTo(2)
        assertThat(stats.totalMinor).isEqualTo(800_00L)
        assertThat(stats.records.map { it.date })
            .containsExactly(d(2025, 1, 31), d(2025, 1, 1)).inOrder()
    }

    @Test
    fun `Month — февраль високосного года включает 29 число`() {
        val all = listOf(
            rec(500_00, d(2024, 2, 29)),
            rec(100_00, d(2024, 3, 1)),
        )

        val stats = StatsCalculator.compute(all, Period.Month(YearMonth.of(2024, 2)))

        assertThat(stats.count).isEqualTo(1)
        assertThat(stats.totalMinor).isEqualTo(500_00L)
        assertThat(stats.dailySeries).hasSize(29)
    }

    @Test
    fun `Day — только эта дата`() {
        val all = listOf(
            rec(100_00, d(2025, 1, 9)),
            rec(200_00, d(2025, 1, 10)),
            rec(400_00, d(2025, 1, 10)),
            rec(300_00, d(2025, 1, 11)),
        )

        val stats = StatsCalculator.compute(all, Period.Day(d(2025, 1, 10)))

        assertThat(stats.count).isEqualTo(2)
        assertThat(stats.totalMinor).isEqualTo(600_00L)
        assertThat(stats.range!!.days).isEqualTo(1)
        assertThat(stats.dailySeries).hasSize(1)
        assertThat(stats.dailySeries.single().totalMinor).isEqualTo(600_00L)
        assertThat(stats.averagePerDayMinor).isEqualTo(600_00L)
    }

    @Test
    fun `Custom — обе границы включительны`() {
        val all = listOf(
            rec(10_00, d(2025, 1, 4)),
            rec(20_00, d(2025, 1, 5)),   // нижняя граница
            rec(30_00, d(2025, 1, 7)),
            rec(40_00, d(2025, 1, 9)),   // верхняя граница
            rec(50_00, d(2025, 1, 10)),
        )

        val stats = StatsCalculator.compute(all, Period.Custom(d(2025, 1, 5), d(2025, 1, 9)))

        assertThat(stats.count).isEqualTo(3)
        assertThat(stats.totalMinor).isEqualTo(90_00L)
        assertThat(stats.records.map { it.date })
            .containsExactly(d(2025, 1, 9), d(2025, 1, 7), d(2025, 1, 5)).inOrder()
    }

    @Test
    fun `Year — 1 января и 31 декабря включаются, соседние годы нет`() {
        val all = listOf(
            rec(100_00, d(2024, 12, 31)),
            rec(200_00, d(2025, 1, 1)),
            rec(300_00, d(2025, 12, 31)),
            rec(400_00, d(2026, 1, 1)),
        )

        val stats = StatsCalculator.compute(all, Period.Year(2025))

        assertThat(stats.count).isEqualTo(2)
        assertThat(stats.totalMinor).isEqualTo(500_00L)
        assertThat(stats.range).isEqualTo(DateRange(d(2025, 1, 1), d(2025, 12, 31)))
        assertThat(stats.monthlySeries).hasSize(12)
    }

    // --- суммы и средние ---

    @Test
    fun `сумма, количество и среднее на транзакцию`() {
        val all = listOf(
            rec(2000_00, d(2025, 1, 5)),
            rec(1100_00, d(2025, 1, 20)),
        )

        val stats = StatsCalculator.compute(all, jan2025)

        assertThat(stats.totalMinor).isEqualTo(3100_00L)
        assertThat(stats.count).isEqualTo(2)
        assertThat(stats.averagePerTransactionMinor).isEqualTo(1550_00L)
    }

    @Test
    fun `среднее в день делится на длину диапазона, а не на число дней с тратами`() {
        val all = listOf(
            rec(2000_00, d(2025, 1, 5)),
            rec(1100_00, d(2025, 1, 20)),
        )

        // 310000 копеек / 31 день = 10000 копеек в день
        val stats = StatsCalculator.compute(all, jan2025)
        assertThat(stats.range!!.days).isEqualTo(31)
        assertThat(stats.averagePerDayMinor).isEqualTo(100_00L)
    }

    @Test
    fun `среднее на транзакцию — целочисленное деление без потери копеек в сумме`() {
        val all = listOf(
            rec(100_01, d(2025, 1, 5)),
            rec(100_01, d(2025, 1, 6)),
            rec(100_01, d(2025, 1, 7)),
        )

        val stats = StatsCalculator.compute(all, jan2025)

        assertThat(stats.totalMinor).isEqualTo(300_03L)
        assertThat(stats.averagePerTransactionMinor).isEqualTo(100_01L)
    }

    // --- медиана ---

    @Test
    fun `медиана для нечётного количества`() {
        val all = listOf(
            rec(100_00, d(2025, 1, 5)),
            rec(300_00, d(2025, 1, 6)),
            rec(200_00, d(2025, 1, 7)),
        )

        assertThat(StatsCalculator.compute(all, jan2025).medianTransactionMinor).isEqualTo(200_00L)
    }

    @Test
    fun `медиана для чётного количества — среднее двух центральных`() {
        val all = listOf(
            rec(500_00, d(2025, 1, 5)),
            rec(100_00, d(2025, 1, 6)),
            rec(300_00, d(2025, 1, 7)),
            rec(200_00, d(2025, 1, 8)),
        )

        // отсортировано: 100, 200, 300, 500 → (200 + 300) / 2 = 250
        assertThat(StatsCalculator.compute(all, jan2025).medianTransactionMinor).isEqualTo(250_00L)
    }

    @Test
    fun `медиана для одного элемента и для пустого множества`() {
        val one = listOf(rec(777_77, d(2025, 1, 5)))
        assertThat(StatsCalculator.compute(one, jan2025).medianTransactionMinor).isEqualTo(777_77L)

        // чётное количество с нечётной суммой центральных — деление вниз
        val two = listOf(rec(1_01, d(2025, 1, 5)), rec(1_02, d(2025, 1, 6)))
        assertThat(StatsCalculator.compute(two, jan2025).medianTransactionMinor).isEqualTo(1_01L)

        assertThat(StatsCalculator.compute(emptyList(), jan2025).medianTransactionMinor).isEqualTo(0L)
    }

    // --- разрезы ---

    @Test
    fun `byCategory — сортировка по убыванию суммы, доли складываются в единицу`() {
        val all = listOf(
            rec(300_00, d(2025, 1, 5), cat = 1, catName = "Еда"),
            rec(500_00, d(2025, 1, 6), cat = 2, catName = "Дом"),
            rec(200_00, d(2025, 1, 7), cat = 3, catName = "Авто"),
        )

        val byCategory = StatsCalculator.compute(all, jan2025).byCategory

        assertThat(byCategory.map { it.categoryName }).containsExactly("Дом", "Еда", "Авто").inOrder()
        assertThat(byCategory.map { it.totalMinor }).containsExactly(500_00L, 300_00L, 200_00L).inOrder()
        assertThat(byCategory[0].share).isWithin(1e-9).of(0.5)
        assertThat(byCategory[1].share).isWithin(1e-9).of(0.3)
        assertThat(byCategory[2].share).isWithin(1e-9).of(0.2)
        assertThat(byCategory.sumOf { it.share }).isWithin(1e-9).of(1.0)
        assertThat(byCategory.map { it.count }).containsExactly(1, 1, 1)
    }

    @Test
    fun `byCategory — при равных суммах порядок по имени, счётчики суммируются`() {
        val all = listOf(
            rec(100_00, d(2025, 1, 5), cat = 2, catName = "Бета"),
            rec(60_00, d(2025, 1, 6), cat = 1, catName = "Альфа"),
            rec(40_00, d(2025, 1, 7), cat = 1, catName = "Альфа"),
        )

        val byCategory = StatsCalculator.compute(all, jan2025).byCategory

        assertThat(byCategory).hasSize(2)
        assertThat(byCategory.map { it.categoryName }).containsExactly("Альфа", "Бета").inOrder()
        assertThat(byCategory[0].totalMinor).isEqualTo(100_00L)
        assertThat(byCategory[0].count).isEqualTo(2)
    }

    @Test
    fun `byCategory — share равен нулю, когда общая сумма ноль`() {
        val all = listOf(
            rec(0L, d(2025, 1, 5), cat = 1, catName = "Еда"),
            rec(0L, d(2025, 1, 6), cat = 2, catName = "Дом"),
        )

        val stats = StatsCalculator.compute(all, jan2025)

        assertThat(stats.totalMinor).isEqualTo(0L)
        assertThat(stats.count).isEqualTo(2)
        assertThat(stats.byCategory).hasSize(2)
        assertThat(stats.byCategory.map { it.share }).containsExactly(0.0, 0.0)
        assertThat(stats.bySubcategory.map { it.share }).containsExactly(0.0, 0.0)
        assertThat(stats.byPaymentMethod.single().share).isEqualTo(0.0)
        assertThat(stats.averagePerTransactionMinor).isEqualTo(0L)
    }

    @Test
    fun `bySubcategory — для null подставляется «Без подкатегории»`() {
        val all = listOf(
            rec(300_00, d(2025, 1, 5), cat = 1, catName = "Еда", sub = null),
            rec(500_00, d(2025, 1, 6), cat = 1, catName = "Еда", sub = 10, subName = "Кафе"),
        )

        val bySub = StatsCalculator.compute(all, jan2025).bySubcategory

        assertThat(bySub).hasSize(2)
        assertThat(bySub[0].subcategoryName).isEqualTo("Кафе")
        assertThat(bySub[0].subcategoryId).isEqualTo(10L)
        assertThat(bySub[1].subcategoryName).isEqualTo("Без подкатегории")
        assertThat(bySub[1].subcategoryId).isNull()
        assertThat(bySub[1].totalMinor).isEqualTo(300_00L)
        assertThat(bySub.map { it.categoryName }.toSet()).containsExactly("Еда")
    }

    @Test
    fun `bySubcategory — группировка по паре категория плюс подкатегория, доли от общей суммы`() {
        val all = listOf(
            rec(100_00, d(2025, 1, 5), cat = 1, catName = "Еда", sub = 10, subName = "Кафе"),
            rec(300_00, d(2025, 1, 6), cat = 1, catName = "Еда", sub = 10, subName = "Кафе"),
            rec(600_00, d(2025, 1, 7), cat = 2, catName = "Дом", sub = 10, subName = "Кафе"),
        )

        val bySub = StatsCalculator.compute(all, jan2025).bySubcategory

        // одинаковый subcategoryId, но разные категории → две строки
        assertThat(bySub).hasSize(2)
        assertThat(bySub[0].categoryName).isEqualTo("Дом")
        assertThat(bySub[0].totalMinor).isEqualTo(600_00L)
        assertThat(bySub[0].share).isWithin(1e-9).of(0.6)
        assertThat(bySub[1].categoryName).isEqualTo("Еда")
        assertThat(bySub[1].totalMinor).isEqualTo(400_00L)
        assertThat(bySub[1].count).isEqualTo(2)
        assertThat(bySub[1].share).isWithin(1e-9).of(0.4)
    }

    @Test
    fun `byPaymentMethod — только встретившиеся методы, по убыванию суммы`() {
        val all = listOf(
            rec(100_00, d(2025, 1, 5), method = PaymentMethod.CASH),
            rec(200_00, d(2025, 1, 6), method = PaymentMethod.CASH),
            rec(1000_00, d(2025, 1, 7), method = PaymentMethod.CARD),
            rec(50_00, d(2025, 1, 8), method = PaymentMethod.ONLINE),
        )

        val byMethod = StatsCalculator.compute(all, jan2025).byPaymentMethod

        assertThat(byMethod.map { it.method })
            .containsExactly(PaymentMethod.CARD, PaymentMethod.CASH, PaymentMethod.ONLINE).inOrder()
        assertThat(byMethod.map { it.totalMinor }).containsExactly(1000_00L, 300_00L, 50_00L).inOrder()
        assertThat(byMethod.map { it.count }).containsExactly(1, 2, 1).inOrder()
        assertThat(byMethod.sumOf { it.share }).isWithin(1e-9).of(1.0)
        // TRANSFER и OTHER не встречались
        assertThat(byMethod.map { it.method }).doesNotContain(PaymentMethod.TRANSFER)
    }

    @Test
    fun `byWeekday — ровно 7 элементов от понедельника до воскресенья`() {
        // 06.01.2025 — понедельник
        val all = listOf(
            rec(100_00, d(2025, 1, 6)),   // ПН
            rec(200_00, d(2025, 1, 13)),  // ПН
            rec(300_00, d(2025, 1, 12)),  // ВС
        )

        val byWeekday = StatsCalculator.compute(all, jan2025).byWeekday

        assertThat(byWeekday).hasSize(7)
        assertThat(byWeekday.map { it.dayOfWeek }).containsExactly(
            DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY,
            DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY,
        ).inOrder()
        assertThat(byWeekday[0].totalMinor).isEqualTo(300_00L)
        assertThat(byWeekday[0].count).isEqualTo(2)
        assertThat(byWeekday[6].totalMinor).isEqualTo(300_00L)
        assertThat(byWeekday[6].count).isEqualTo(1)
    }

    @Test
    fun `byWeekday — нули для дней без трат, сумма совпадает с общей`() {
        val all = listOf(rec(555_00, d(2025, 1, 8))) // среда

        val byWeekday = StatsCalculator.compute(all, jan2025).byWeekday

        assertThat(byWeekday).hasSize(7)
        assertThat(byWeekday[2].dayOfWeek).isEqualTo(DayOfWeek.WEDNESDAY)
        assertThat(byWeekday[2].totalMinor).isEqualTo(555_00L)
        val empty = byWeekday.filter { it.dayOfWeek != DayOfWeek.WEDNESDAY }
        assertThat(empty).hasSize(6)
        assertThat(empty.map { it.totalMinor }.toSet()).containsExactly(0L)
        assertThat(empty.map { it.count }.toSet()).containsExactly(0)
        assertThat(byWeekday.sumOf { it.totalMinor }).isEqualTo(555_00L)
    }

    // --- ряды ---

    @Test
    fun `dailySeries — по точке на каждый день, zero-fill в пустых днях`() {
        val all = listOf(
            rec(100_00, d(2025, 1, 1)),
            rec(200_00, d(2025, 1, 5)),
            rec(50_00, d(2025, 1, 5)),
        )

        val series = StatsCalculator.compute(all, Period.Custom(d(2025, 1, 1), d(2025, 1, 5))).dailySeries

        assertThat(series).hasSize(5)
        assertThat(series.map { it.date }).containsExactly(
            d(2025, 1, 1), d(2025, 1, 2), d(2025, 1, 3), d(2025, 1, 4), d(2025, 1, 5),
        ).inOrder()
        assertThat(series.map { it.totalMinor })
            .containsExactly(100_00L, 0L, 0L, 0L, 250_00L).inOrder()
        assertThat(series.map { it.count }).containsExactly(1, 0, 0, 0, 2).inOrder()
        assertThat(series.map { it.label })
            .containsExactly("01.01", "02.01", "03.01", "04.01", "05.01").inOrder()
    }

    @Test
    fun `dailySeries — длина равна числу дней диапазона, сумма совпадает с итогом`() {
        val all = listOf(
            rec(123_45, d(2025, 1, 3)),
            rec(678_90, d(2025, 1, 17)),
            rec(1_00, d(2025, 1, 31)),
            rec(999_00, d(2025, 2, 2)), // вне периода
        )

        val stats = StatsCalculator.compute(all, jan2025)

        assertThat(stats.dailySeries).hasSize(stats.range!!.days)
        assertThat(stats.dailySeries).hasSize(31)
        assertThat(stats.dailySeries.sumOf { it.totalMinor }).isEqualTo(stats.totalMinor)
        assertThat(stats.dailySeries.sumOf { it.count }).isEqualTo(stats.count)
        assertThat(stats.totalMinor).isEqualTo(803_35L)
    }

    @Test
    fun `monthlySeries — по точке на каждый задетый месяц с первым числом и русской подписью`() {
        val all = listOf(
            rec(100_00, d(2024, 11, 20)),
            rec(200_00, d(2025, 1, 3)),
        )

        val series = StatsCalculator.compute(
            all,
            Period.Custom(d(2024, 11, 15), d(2025, 2, 3)),
        ).monthlySeries

        assertThat(series).hasSize(4)
        assertThat(series.map { it.date }).containsExactly(
            d(2024, 11, 1), d(2024, 12, 1), d(2025, 1, 1), d(2025, 2, 1),
        ).inOrder()
        assertThat(series.map { it.label })
            .containsExactly("ноя 24", "дек 24", "янв 25", "фев 25").inOrder()
        assertThat(series.map { it.totalMinor })
            .containsExactly(100_00L, 0L, 200_00L, 0L).inOrder()
    }

    @Test
    fun `monthlySeries — для одного месяца одна точка, для года двенадцать`() {
        val all = listOf(rec(100_00, d(2025, 1, 10)))

        assertThat(StatsCalculator.compute(all, jan2025).monthlySeries).hasSize(1)

        val year = StatsCalculator.compute(all, Period.Year(2025)).monthlySeries
        assertThat(year).hasSize(12)
        assertThat(year.first().label).isEqualTo("янв 25")
        assertThat(year.last().label).isEqualTo("дек 25")
        assertThat(year.sumOf { it.totalMinor }).isEqualTo(100_00L)
    }

    // --- максимум и порядок записей ---

    @Test
    fun `maxSingle — трата с наибольшей суммой внутри периода`() {
        val all = listOf(
            rec(100_00, d(2025, 1, 5)),
            rec(9000_00, d(2025, 1, 6), id = 42L),
            rec(300_00, d(2025, 1, 7)),
            rec(99999_00, d(2025, 2, 1)), // вне периода
        )

        val stats = StatsCalculator.compute(all, jan2025)

        assertThat(stats.maxSingle).isNotNull()
        assertThat(stats.maxSingle!!.id).isEqualTo(42L)
        assertThat(stats.maxSingle!!.amountMinor).isEqualTo(9000_00L)
    }

    @Test
    fun `records — сортировка по дате DESC, затем по createdAt DESC`() {
        val all = listOf(
            rec(10_00, d(2025, 1, 5), id = 1L, createdAt = 100L),
            rec(20_00, d(2025, 1, 5), id = 2L, createdAt = 300L),
            rec(30_00, d(2025, 1, 5), id = 3L, createdAt = 200L),
            rec(40_00, d(2025, 1, 20), id = 4L, createdAt = 1L),
        )

        val records = StatsCalculator.compute(all, jan2025).records

        assertThat(records.map { it.id }).containsExactly(4L, 2L, 3L, 1L).inOrder()
    }

    // --- сравнение периодов ---

    @Test
    fun `compare — рост относительно прошлого месяца`() {
        val all = listOf(
            rec(1000_00, d(2025, 1, 10)),
            rec(1500_00, d(2025, 2, 10)),
            rec(500_00, d(2025, 2, 20)),
        )

        val cmp = StatsCalculator.compare(all, Period.Month(YearMonth.of(2025, 2)))

        assertThat(cmp.currentMinor).isEqualTo(2000_00L)
        assertThat(cmp.previousMinor).isEqualTo(1000_00L)
        assertThat(cmp.deltaMinor).isEqualTo(1000_00L)
        assertThat(cmp.deltaRatio).isNotNull()
        assertThat(cmp.deltaRatio!!).isWithin(1e-9).of(1.0)
    }

    @Test
    fun `compare — падение относительно прошлого месяца`() {
        val all = listOf(
            rec(1000_00, d(2025, 1, 10)),
            rec(250_00, d(2025, 2, 10)),
        )

        val cmp = StatsCalculator.compare(all, Period.Month(YearMonth.of(2025, 2)))

        assertThat(cmp.currentMinor).isEqualTo(250_00L)
        assertThat(cmp.previousMinor).isEqualTo(1000_00L)
        assertThat(cmp.deltaMinor).isEqualTo(-750_00L)
        assertThat(cmp.deltaRatio!!).isWithin(1e-9).of(-0.75)
    }

    @Test
    fun `compare — пустой прошлый период даёт deltaRatio null`() {
        val all = listOf(rec(1000_00, d(2025, 2, 10)))

        val cmp = StatsCalculator.compare(all, Period.Month(YearMonth.of(2025, 2)))

        assertThat(cmp.currentMinor).isEqualTo(1000_00L)
        assertThat(cmp.previousMinor).isEqualTo(0L)
        assertThat(cmp.deltaMinor).isEqualTo(1000_00L)
        assertThat(cmp.deltaRatio).isNull()
    }

    @Test
    fun `compare — для AllTime previousMinor равен нулю`() {
        val all = listOf(
            rec(1000_00, d(2024, 1, 10)),
            rec(500_00, d(2025, 2, 10)),
        )

        val cmp = StatsCalculator.compare(all, Period.AllTime)

        assertThat(cmp.currentMinor).isEqualTo(1500_00L)
        assertThat(cmp.previousMinor).isEqualTo(0L)
        assertThat(cmp.deltaRatio).isNull()
    }

    @Test
    fun `compare — Custom сравнивается с равным по длине предыдущим окном`() {
        val all = listOf(
            rec(100_00, d(2025, 1, 7)),  // предыдущее окно 07..09
            rec(200_00, d(2025, 1, 9)),
            rec(400_00, d(2025, 1, 10)), // текущее окно 10..12
            rec(50_00, d(2025, 1, 6)),   // раньше обоих окон
        )

        val cmp = StatsCalculator.compare(all, Period.Custom(d(2025, 1, 10), d(2025, 1, 12)))

        assertThat(cmp.currentMinor).isEqualTo(400_00L)
        assertThat(cmp.previousMinor).isEqualTo(300_00L)
    }

    // --- границы данных и доступные месяцы ---

    @Test
    fun `dataBounds — min и max даты, null для пустого списка`() {
        assertThat(StatsCalculator.dataBounds(emptyList())).isNull()

        val all = listOf(
            rec(1_00, d(2025, 3, 4)),
            rec(1_00, d(2023, 12, 1)),
            rec(1_00, d(2024, 6, 30)),
        )
        assertThat(StatsCalculator.dataBounds(all))
            .isEqualTo(DateRange(d(2023, 12, 1), d(2025, 3, 4)))

        val one = listOf(rec(1_00, d(2025, 3, 4)))
        assertThat(StatsCalculator.dataBounds(one))
            .isEqualTo(DateRange(d(2025, 3, 4), d(2025, 3, 4)))
    }

    @Test
    fun `availableMonths — по убыванию, без дубликатов, только месяцы с тратами`() {
        val all = listOf(
            rec(1_00, d(2025, 1, 5)),
            rec(1_00, d(2025, 1, 25)),
            rec(1_00, d(2024, 12, 31)),
            rec(1_00, d(2025, 3, 1)),
        )

        val months = StatsCalculator.availableMonths(all)

        assertThat(months).containsExactly(
            YearMonth.of(2025, 3),
            YearMonth.of(2025, 1),
            YearMonth.of(2024, 12),
        ).inOrder()
        // февраль 2025 пропущен — трат не было
        assertThat(months).doesNotContain(YearMonth.of(2025, 2))
        assertThat(StatsCalculator.availableMonths(emptyList())).isEmpty()
    }

    // --- крупные суммы ---

    @Test
    fun `крупные суммы — нет переполнения и потери копеек`() {
        val all = (1..12).map { rec(99_999_99, d(2025, 1, it)) }

        val stats = StatsCalculator.compute(all, jan2025)

        assertThat(stats.totalMinor).isEqualTo(12L * 99_999_99L)
        assertThat(stats.totalMinor).isEqualTo(119_999_988L)
        assertThat(stats.count).isEqualTo(12)
        assertThat(stats.averagePerTransactionMinor).isEqualTo(99_999_99L)
        assertThat(stats.medianTransactionMinor).isEqualTo(99_999_99L)
        assertThat(stats.byCategory.single().totalMinor).isEqualTo(119_999_988L)
        assertThat(stats.dailySeries.sumOf { it.totalMinor }).isEqualTo(119_999_988L)
    }

    @Test
    fun `очень большие суммы не переполняют Long`() {
        val huge = 1_000_000_000_00L // 1 млрд рублей
        val all = (0 until 100).map { rec(huge, d(2025, 1, 1).plusDays(it.toLong())) }

        val stats = StatsCalculator.compute(all, Period.AllTime)

        assertThat(stats.totalMinor).isEqualTo(100L * huge)
        assertThat(stats.totalMinor).isGreaterThan(0L)
        assertThat(stats.averagePerTransactionMinor).isEqualTo(huge)
    }

    @Test
    fun `период сохраняется в результате как есть`() {
        val period = Period.Custom(d(2025, 1, 1), d(2025, 1, 2))
        val stats = StatsCalculator.compute(listOf(rec(1_00, d(2025, 1, 1))), period)

        assertThat(stats.period).isEqualTo(period)
        assertThat(stats.range).isEqualTo(DateRange(d(2025, 1, 1), d(2025, 1, 2)))
    }
}
