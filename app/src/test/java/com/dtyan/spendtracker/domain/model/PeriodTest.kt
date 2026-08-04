package com.dtyan.spendtracker.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/**
 * Тесты периодов и диапазонов дат. Все даты фиксированные.
 */
class PeriodTest {

    private fun d(year: Int, month: Int, day: Int): LocalDate = LocalDate.of(year, month, day)

    // --- DateRange ---

    @Test
    fun `DateRange days — один день считается за единицу`() {
        assertThat(DateRange(d(2025, 1, 10), d(2025, 1, 10)).days).isEqualTo(1)
    }

    @Test
    fun `DateRange days — обе границы включаются`() {
        assertThat(DateRange(d(2025, 1, 1), d(2025, 1, 31)).days).isEqualTo(31)
        assertThat(DateRange(d(2025, 1, 1), d(2025, 12, 31)).days).isEqualTo(365)
        assertThat(DateRange(d(2024, 1, 1), d(2024, 12, 31)).days).isEqualTo(366)
        assertThat(DateRange(d(2024, 2, 1), d(2024, 2, 29)).days).isEqualTo(29)
    }

    @Test
    fun `DateRange contains — границы внутри, соседние даты снаружи`() {
        val range = DateRange(d(2025, 1, 10), d(2025, 1, 20))

        assertThat(d(2025, 1, 10) in range).isTrue()
        assertThat(d(2025, 1, 15) in range).isTrue()
        assertThat(d(2025, 1, 20) in range).isTrue()
        assertThat(d(2025, 1, 9) in range).isFalse()
        assertThat(d(2025, 1, 21) in range).isFalse()
        assertThat(d(2024, 1, 15) in range).isFalse()
        assertThat(d(2026, 1, 15) in range).isFalse()
    }

    @Test
    fun `DateRange — конец раньше начала запрещён`() {
        assertThrows(IllegalArgumentException::class.java) {
            DateRange(d(2025, 1, 20), d(2025, 1, 10))
        }
    }

    // --- Month ---

    @Test
    fun `Month title — название месяца с большой буквы и год`() {
        val title = Period.Month(YearMonth.of(2025, 1)).title

        assertThat(title).endsWith("2025")
        assertThat(title.first().isUpperCase()).isTrue()
        // Название может быть «Январь» или «Января» в зависимости от данных локали JDK.
        assertThat(title.lowercase()).contains("январ")
    }

    @Test
    fun `Month range — с первого по последнее число, високосный февраль`() {
        assertThat(Period.Month(YearMonth.of(2025, 1)).range(null))
            .isEqualTo(DateRange(d(2025, 1, 1), d(2025, 1, 31)))

        assertThat(Period.Month(YearMonth.of(2025, 2)).range(null))
            .isEqualTo(DateRange(d(2025, 2, 1), d(2025, 2, 28)))

        val leap = Period.Month(YearMonth.of(2024, 2)).range(null)!!
        assertThat(leap).isEqualTo(DateRange(d(2024, 2, 1), d(2024, 2, 29)))
        assertThat(leap.days).isEqualTo(29)

        assertThat(Period.Month(YearMonth.of(2025, 4)).range(null)!!.days).isEqualTo(30)
    }

    @Test
    fun `Month range игнорирует dataBounds`() {
        val bounds = DateRange(d(2000, 1, 1), d(2030, 1, 1))
        assertThat(Period.Month(YearMonth.of(2025, 1)).range(bounds))
            .isEqualTo(Period.Month(YearMonth.of(2025, 1)).range(null))
    }

    @Test
    fun `Month previous — январь даёт декабрь прошлого года`() {
        assertThat(Period.Month(YearMonth.of(2025, 1)).previous())
            .isEqualTo(Period.Month(YearMonth.of(2024, 12)))
    }

    @Test
    fun `Month previous — март високосного года даёт февраль с 29 днями`() {
        val prev = Period.Month(YearMonth.of(2024, 3)).previous()

        assertThat(prev).isEqualTo(Period.Month(YearMonth.of(2024, 2)))
        assertThat(prev!!.range(null)!!.days).isEqualTo(29)
    }

    @Test
    fun `Month previous — обычный переход внутри года`() {
        assertThat(Period.Month(YearMonth.of(2025, 7)).previous())
            .isEqualTo(Period.Month(YearMonth.of(2025, 6)))
    }

    // --- Day ---

    @Test
    fun `Day title и range`() {
        val period = Period.Day(d(2025, 1, 5))

        assertThat(period.title).isEqualTo("05.01.2025")
        assertThat(period.range(null)).isEqualTo(DateRange(d(2025, 1, 5), d(2025, 1, 5)))
        assertThat(period.range(null)!!.days).isEqualTo(1)
    }

    @Test
    fun `Day previous — переход через границу года и через 29 февраля`() {
        assertThat(Period.Day(d(2025, 1, 1)).previous())
            .isEqualTo(Period.Day(d(2024, 12, 31)))

        assertThat(Period.Day(d(2024, 3, 1)).previous())
            .isEqualTo(Period.Day(d(2024, 2, 29)))

        assertThat(Period.Day(d(2025, 3, 1)).previous())
            .isEqualTo(Period.Day(d(2025, 2, 28)))
    }

    // --- Year ---

    @Test
    fun `Year title и range`() {
        val period = Period.Year(2025)

        assertThat(period.title).isEqualTo("2025 год")
        assertThat(period.range(null)).isEqualTo(DateRange(d(2025, 1, 1), d(2025, 12, 31)))
        assertThat(period.range(null)!!.days).isEqualTo(365)
        assertThat(Period.Year(2024).range(null)!!.days).isEqualTo(366)
    }

    @Test
    fun `Year previous — предыдущий год`() {
        assertThat(Period.Year(2025).previous()).isEqualTo(Period.Year(2024))
        assertThat(Period.Year(2000).previous()).isEqualTo(Period.Year(1999))
    }

    // --- Custom ---

    @Test
    fun `Custom title и range`() {
        val period = Period.Custom(d(2025, 1, 1), d(2025, 1, 31))

        assertThat(period.title).isEqualTo("01.01.2025 — 31.01.2025")
        assertThat(period.range(null)).isEqualTo(DateRange(d(2025, 1, 1), d(2025, 1, 31)))
    }

    @Test
    fun `Custom previous — диапазон той же длины непосредственно перед текущим`() {
        val period = Period.Custom(d(2025, 1, 10), d(2025, 1, 12))
        val prev = period.previous()!!

        assertThat(prev).isEqualTo(Period.Custom(d(2025, 1, 7), d(2025, 1, 9)))

        val prevRange = prev.range(null)!!
        val currentRange = period.range(null)!!
        assertThat(prevRange.days).isEqualTo(currentRange.days)
        // предыдущий диапазон заканчивается ровно за день до текущего
        assertThat(prevRange.endInclusive).isEqualTo(currentRange.start.minusDays(1))
    }

    @Test
    fun `Custom previous — один день превращается в предыдущий день`() {
        val period = Period.Custom(d(2025, 1, 1), d(2025, 1, 1))
        val prev = period.previous()!!

        assertThat(prev).isEqualTo(Period.Custom(d(2024, 12, 31), d(2024, 12, 31)))
        assertThat(prev.range(null)!!.days).isEqualTo(1)
    }

    @Test
    fun `Custom previous — длинный диапазон через границу года`() {
        val period = Period.Custom(d(2025, 1, 1), d(2025, 1, 31)) // 31 день
        val prev = period.previous()!!

        assertThat(prev).isEqualTo(Period.Custom(d(2024, 12, 1), d(2024, 12, 31)))
        assertThat(prev.range(null)!!.days).isEqualTo(31)
    }

    // --- AllTime ---

    @Test
    fun `AllTime title, range и previous`() {
        assertThat(Period.AllTime.title).isEqualTo("За всё время")
        assertThat(Period.AllTime.previous()).isNull()
        assertThat(Period.AllTime.range(null)).isNull()

        val bounds = DateRange(d(2023, 5, 1), d(2025, 8, 9))
        assertThat(Period.AllTime.range(bounds)).isEqualTo(bounds)
    }

    // --- companion ---

    @Test
    fun `currentMonth собирает Month из переданной даты`() {
        assertThat(Period.currentMonth(d(2025, 7, 15)))
            .isEqualTo(Period.Month(YearMonth.of(2025, 7)))

        assertThat(Period.currentMonth(d(2024, 12, 31)))
            .isEqualTo(Period.Month(YearMonth.of(2024, 12)))
    }

    @Test
    fun `DAY_FORMAT — формат dd MM yyyy с ведущими нулями`() {
        assertThat(d(2025, 1, 5).format(Period.DAY_FORMAT)).isEqualTo("05.01.2025")
        assertThat(d(2025, 12, 31).format(Period.DAY_FORMAT)).isEqualTo("31.12.2025")
    }

    @Test
    fun `все периоды кроме AllTime возвращают непустой range без dataBounds`() {
        val periods = listOf(
            Period.Month(YearMonth.of(2025, 1)),
            Period.Day(d(2025, 1, 1)),
            Period.Year(2025),
            Period.Custom(d(2025, 1, 1), d(2025, 1, 2)),
        )

        for (p in periods) {
            assertThat(p.range(null)).isNotNull()
            assertThat(p.previous()).isNotNull()
            assertThat(p.title).isNotEmpty()
        }
    }
}
