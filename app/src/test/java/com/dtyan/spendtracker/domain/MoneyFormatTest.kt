package com.dtyan.spendtracker.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Тесты форматирования и парсинга денег.
 * Внутри MoneyFormat используется неразрывный пробел (U+00A0) — в ожиданиях он явный.
 */
class MoneyFormatTest {

    private val nbsp = " "

    // --- format ---

    @Test
    fun `format — ноль`() {
        assertThat(MoneyFormat.format(0)).isEqualTo("0$nbsp₽")
        assertThat(MoneyFormat.format(0, withSymbol = false)).isEqualTo("0")
    }

    @Test
    fun `format — копейки показываются только когда они есть`() {
        assertThat(MoneyFormat.format(123456, withSymbol = false)).isEqualTo("1${nbsp}234,56")
        assertThat(MoneyFormat.format(100000, withSymbol = false)).isEqualTo("1${nbsp}000")
        assertThat(MoneyFormat.format(99900, withSymbol = false)).isEqualTo("999")
    }

    @Test
    fun `format — копейки меньше десяти дополняются нулём`() {
        assertThat(MoneyFormat.format(50, withSymbol = false)).isEqualTo("0,50")
        assertThat(MoneyFormat.format(5, withSymbol = false)).isEqualTo("0,05")
        assertThat(MoneyFormat.format(1, withSymbol = false)).isEqualTo("0,01")
        assertThat(MoneyFormat.format(99, withSymbol = false)).isEqualTo("0,99")
    }

    @Test
    fun `format — разряды разделяются неразрывным пробелом`() {
        assertThat(MoneyFormat.format(99999, withSymbol = false)).isEqualTo("999,99")
        assertThat(MoneyFormat.format(1234567890_12L, withSymbol = false))
            .isEqualTo("1${nbsp}234${nbsp}567${nbsp}890,12")
        assertThat(MoneyFormat.format(1_000_000_00L, withSymbol = false))
            .isEqualTo("1${nbsp}000${nbsp}000")
    }

    @Test
    fun `format — отрицательные суммы`() {
        assertThat(MoneyFormat.format(-123456, withSymbol = false)).isEqualTo("-1${nbsp}234,56")
        assertThat(MoneyFormat.format(-5000, withSymbol = false)).isEqualTo("-50")
        assertThat(MoneyFormat.format(-1)).isEqualTo("-0,01$nbsp₽")
    }

    @Test
    fun `format — символ рубля добавляется по умолчанию`() {
        assertThat(MoneyFormat.format(123456)).isEqualTo("1${nbsp}234,56$nbsp₽")
        assertThat(MoneyFormat.format(123456)).endsWith("₽")
        assertThat(MoneyFormat.format(123456, withSymbol = false)).doesNotContain("₽")
    }

    // --- formatCompact ---

    @Test
    fun `formatCompact — сотни без суффикса`() {
        assertThat(MoneyFormat.formatCompact(0)).isEqualTo("0")
        assertThat(MoneyFormat.formatCompact(12345)).isEqualTo("123")
        assertThat(MoneyFormat.formatCompact(99900)).isEqualTo("999")
    }

    @Test
    fun `formatCompact — тысячи`() {
        assertThat(MoneyFormat.formatCompact(100_000)).isEqualTo("1к")
        assertThat(MoneyFormat.formatCompact(150_000)).isEqualTo("1,5к")
        assertThat(MoneyFormat.formatCompact(123_400)).isEqualTo("1,2к")
        assertThat(MoneyFormat.formatCompact(12_500_00L)).isEqualTo("12,5к")
    }

    @Test
    fun `formatCompact — миллионы`() {
        assertThat(MoneyFormat.formatCompact(1_000_000_00L)).isEqualTo("1 млн")
        assertThat(MoneyFormat.formatCompact(3_400_000_00L)).isEqualTo("3,4 млн")
        assertThat(MoneyFormat.formatCompact(2_500_000_00L)).isEqualTo("2,5 млн")
    }

    @Test
    fun `formatCompact — отрицательные значения сохраняют знак`() {
        assertThat(MoneyFormat.formatCompact(-150_000)).isEqualTo("-1,5к")
        assertThat(MoneyFormat.formatCompact(-2_500_000_00L)).isEqualTo("-2,5 млн")
        assertThat(MoneyFormat.formatCompact(-12345)).isEqualTo("-123")
    }

    // --- formatPercent ---

    @Test
    fun `formatPercent — целые проценты без дробной части`() {
        assertThat(MoneyFormat.formatPercent(0.0)).isEqualTo("0$nbsp%")
        assertThat(MoneyFormat.formatPercent(0.25)).isEqualTo("25$nbsp%")
        assertThat(MoneyFormat.formatPercent(0.5)).isEqualTo("50$nbsp%")
        assertThat(MoneyFormat.formatPercent(1.0)).isEqualTo("100$nbsp%")
    }

    @Test
    fun `formatPercent — один знак после запятой`() {
        assertThat(MoneyFormat.formatPercent(0.125)).isEqualTo("12,5$nbsp%")
        assertThat(MoneyFormat.formatPercent(0.3333)).isEqualTo("33,3$nbsp%")
        assertThat(MoneyFormat.formatPercent(0.001)).isEqualTo("0,1$nbsp%")
    }

    @Test
    fun `formatPercent — очень малая доля округляется до нуля`() {
        assertThat(MoneyFormat.formatPercent(0.0001)).isEqualTo("0$nbsp%")
    }

    // --- parseToMinor ---

    @Test
    fun `parseToMinor — целые рубли`() {
        assertThat(MoneyFormat.parseToMinor("1234")).isEqualTo(123400L)
        assertThat(MoneyFormat.parseToMinor("0")).isEqualTo(0L)
        assertThat(MoneyFormat.parseToMinor("7")).isEqualTo(700L)
    }

    @Test
    fun `parseToMinor — запятая как десятичный разделитель`() {
        assertThat(MoneyFormat.parseToMinor("1234,56")).isEqualTo(123456L)
        assertThat(MoneyFormat.parseToMinor("0,01")).isEqualTo(1L)
        assertThat(MoneyFormat.parseToMinor("1,5")).isEqualTo(150L)
    }

    @Test
    fun `parseToMinor — точка как десятичный разделитель и пробелы-разряды`() {
        assertThat(MoneyFormat.parseToMinor("1 234.5")).isEqualTo(123450L)
        assertThat(MoneyFormat.parseToMinor("1234.56")).isEqualTo(123456L)
        assertThat(MoneyFormat.parseToMinor("1${nbsp}234,56")).isEqualTo(123456L)
        assertThat(MoneyFormat.parseToMinor("1.234,56")).isEqualTo(123456L)
    }

    @Test
    fun `parseToMinor — три цифры после разделителя трактуются как группировка тысяч`() {
        // "12,345" — это 12 345 рублей, а не 12 рублей 345 копеек
        assertThat(MoneyFormat.parseToMinor("12,345")).isEqualTo(1234500L)
        assertThat(MoneyFormat.parseToMinor("1,239")).isEqualTo(123900L)
        assertThat(MoneyFormat.parseToMinor("12,345,678")).isEqualTo(1234567800L)
    }

    @Test
    fun `parseToMinor — пустой и неразборчивый ввод дают null`() {
        assertThat(MoneyFormat.parseToMinor("")).isNull()
        assertThat(MoneyFormat.parseToMinor("   ")).isNull()
        assertThat(MoneyFormat.parseToMinor("abc")).isNull()
        assertThat(MoneyFormat.parseToMinor(",")).isNull()
        assertThat(MoneyFormat.parseToMinor(".")).isNull()
        assertThat(MoneyFormat.parseToMinor("1e5")).isNull()
        assertThat(MoneyFormat.parseToMinor("₽")).isNull()
    }

    @Test
    fun `parseToMinor — отрицательные суммы отвергаются`() {
        assertThat(MoneyFormat.parseToMinor("-5")).isNull()
        assertThat(MoneyFormat.parseToMinor("-1234,56")).isNull()
        assertThat(MoneyFormat.parseToMinor("- 5")).isNull()
    }

    @Test
    fun `parseToMinor — валютные суффиксы отбрасываются`() {
        assertThat(MoneyFormat.parseToMinor("100 ₽")).isEqualTo(10000L)
        assertThat(MoneyFormat.parseToMinor("1234 руб.")).isEqualTo(123400L)
        assertThat(MoneyFormat.parseToMinor(" 250,40 ₽ ")).isEqualTo(25040L)
    }

    @Test
    fun `parseToMinor — слишком большое число не ломает парсер`() {
        assertThat(MoneyFormat.parseToMinor("99999999999999999999")).isNull()
    }

    @Test
    fun `format и parseToMinor — круговой обход`() {
        for (minor in listOf(0L, 1L, 5L, 99L, 100L, 123456L, 100000L, 1_000_000_00L, 803_35L)) {
            val text = MoneyFormat.format(minor, withSymbol = false)
            assertThat(MoneyFormat.parseToMinor(text)).isEqualTo(minor)
        }
    }
}
