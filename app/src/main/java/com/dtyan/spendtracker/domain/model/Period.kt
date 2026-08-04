package com.dtyan.spendtracker.domain.model

import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val RU = Locale("ru")

data class DateRange(val start: LocalDate, val endInclusive: LocalDate) {
    init {
        require(!endInclusive.isBefore(start)) { "endInclusive ($endInclusive) < start ($start)" }
    }

    operator fun contains(date: LocalDate): Boolean =
        !date.isBefore(start) && !date.isAfter(endInclusive)

    /** Количество дней в диапазоне, включая обе границы. */
    val days: Int get() = (endInclusive.toEpochDay() - start.toEpochDay()).toInt() + 1
}

/**
 * Период, по которому фильтруется статистика.
 *
 * [range] возвращает конкретные границы. Для [AllTime] границы берутся из данных
 * ([dataBounds]); если данных нет — возвращается null и статистика считается пустой.
 */
sealed interface Period {
    val title: String

    fun range(dataBounds: DateRange?): DateRange?

    /** Предыдущий сопоставимый период — для сравнения «стало/было». Null, если сравнение бессмысленно. */
    fun previous(): Period?

    data class Month(val yearMonth: YearMonth) : Period {
        override val title: String
            get() = yearMonth.month.getDisplayName(TextStyle.FULL_STANDALONE, RU)
                .replaceFirstChar { it.titlecase(RU) } + " " + yearMonth.year

        override fun range(dataBounds: DateRange?) =
            DateRange(yearMonth.atDay(1), yearMonth.atEndOfMonth())

        override fun previous() = Month(yearMonth.minusMonths(1))
    }

    data class Day(val date: LocalDate) : Period {
        override val title: String get() = date.format(DAY_FORMAT)
        override fun range(dataBounds: DateRange?) = DateRange(date, date)
        override fun previous() = Day(date.minusDays(1))
    }

    data class Year(val year: Int) : Period {
        override val title: String get() = "$year год"
        override fun range(dataBounds: DateRange?) =
            DateRange(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31))

        override fun previous() = Year(year - 1)
    }

    data class Custom(val from: LocalDate, val to: LocalDate) : Period {
        override val title: String get() = "${from.format(DAY_FORMAT)} — ${to.format(DAY_FORMAT)}"
        override fun range(dataBounds: DateRange?) = DateRange(from, to)

        override fun previous(): Period {
            val length = DateRange(from, to).days.toLong()
            return Custom(from.minusDays(length), from.minusDays(1))
        }
    }

    data object AllTime : Period {
        override val title: String get() = "За всё время"
        override fun range(dataBounds: DateRange?) = dataBounds
        override fun previous(): Period? = null
    }

    companion object {
        val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")

        fun currentMonth(today: LocalDate): Period = Month(YearMonth.from(today))
    }
}
