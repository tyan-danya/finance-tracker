package com.dtyan.spendtracker.export

import com.dtyan.spendtracker.domain.model.ExpenseRecord
import com.dtyan.spendtracker.domain.model.PaymentMethod
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

class CsvExporterTest {

    private val header = "Дата;Сумма;Валюта;Категория;Подкатегория;Способ оплаты;Комментарий;ID"

    private fun record(
        id: Long = 1L,
        amountMinor: Long = 10_000L,
        date: LocalDate = LocalDate.of(2026, 7, 1),
        category: String = "Продукты",
        subcategory: String? = "Супермаркет",
        note: String = "",
        paymentMethod: PaymentMethod = PaymentMethod.CARD,
        currency: String = "RUB",
    ) = ExpenseRecord(
        id = id,
        amountMinor = amountMinor,
        currency = currency,
        categoryId = 1L,
        categoryName = category,
        subcategoryId = subcategory?.let { 10L },
        subcategoryName = subcategory,
        date = date,
        note = note,
        paymentMethod = paymentMethod,
        createdAt = 0L,
    )

    private fun lines(csv: String): List<String> = csv.split("\r\n")

    @Test
    fun `первая строка — заголовок`() {
        val csv = CsvExporter.export(listOf(record()))
        assertThat(lines(csv).first()).isEqualTo(header)
    }

    @Test
    fun `пустой список даёт только заголовок`() {
        val csv = CsvExporter.export(emptyList())
        assertThat(csv).isEqualTo(header)
    }

    @Test
    fun `дата и сумма форматируются по-русски`() {
        val csv = CsvExporter.export(
            listOf(record(id = 7, amountMinor = 123_456, date = LocalDate.of(2026, 1, 9)))
        )
        assertThat(lines(csv)[1])
            .isEqualTo("09.01.2026;1234,56;RUB;Продукты;Супермаркет;Карта;;7")
    }

    @Test
    fun `сумма с нулевыми копейками сохраняет два знака`() {
        val csv = CsvExporter.export(listOf(record(amountMinor = 100_000)))
        assertThat(lines(csv)[1].split(";")[1]).isEqualTo("1000,00")
    }

    @Test
    fun `копейки без рублей форматируются как ноль запятая`() {
        val csv = CsvExporter.export(listOf(record(amountMinor = 5)))
        assertThat(lines(csv)[1].split(";")[1]).isEqualTo("0,05")
    }

    @Test
    fun `большая сумма выводится без разделителей разрядов`() {
        val csv = CsvExporter.export(listOf(record(amountMinor = 123_456_789_012L)))
        assertThat(lines(csv)[1].split(";")[1]).isEqualTo("1234567890,12")
    }

    @Test
    fun `отсутствующая подкатегория даёт пустое поле`() {
        val csv = CsvExporter.export(listOf(record(subcategory = null)))
        assertThat(lines(csv)[1].split(";")[4]).isEmpty()
        assertThat(lines(csv)[1]).contains(";Продукты;;Карта;")
    }

    @Test
    fun `комментарий с точкой с запятой оборачивается в кавычки`() {
        val csv = CsvExporter.export(listOf(record(note = "Кофе; булка")))
        assertThat(lines(csv)[1]).contains("\"Кофе; булка\"")
    }

    @Test
    fun `кавычки внутри комментария удваиваются`() {
        val csv = CsvExporter.export(listOf(record(note = "Скидка \"20%\"")))
        assertThat(lines(csv)[1]).contains("\"Скидка \"\"20%\"\"\"")
    }

    @Test
    fun `перевод строки в комментарии не ломает поле`() {
        val csv = CsvExporter.export(listOf(record(note = "первая\nвторая")))
        // Поле в кавычках, поэтому строк по \r\n по-прежнему две: заголовок и запись.
        assertThat(lines(csv)).hasSize(2)
        assertThat(lines(csv)[1]).contains("\"первая\nвторая\"")
    }

    @Test
    fun `возврат каретки в комментарии тоже экранируется`() {
        val csv = CsvExporter.export(listOf(record(note = "a\rb")))
        assertThat(csv).contains("\"a\rb\"")
    }

    @Test
    fun `порядок записей сохраняется`() {
        val records = listOf(
            record(id = 3, date = LocalDate.of(2026, 3, 3)),
            record(id = 1, date = LocalDate.of(2026, 1, 1)),
            record(id = 2, date = LocalDate.of(2026, 2, 2)),
        )
        val ids = lines(CsvExporter.export(records)).drop(1).map { it.substringAfterLast(';') }
        assertThat(ids).containsExactly("3", "1", "2").inOrder()
    }

    @Test
    fun `строки разделяются CRLF и завершающего перевода строки нет`() {
        val csv = CsvExporter.export(listOf(record(id = 1), record(id = 2)))
        assertThat(csv).contains("\r\n")
        assertThat(csv.endsWith("\r\n")).isFalse()
        assertThat(lines(csv)).hasSize(3)
    }

    @Test
    fun `способ оплаты выводится человекочитаемо`() {
        val csv = CsvExporter.export(listOf(record(paymentMethod = PaymentMethod.CASH)))
        assertThat(lines(csv)[1].split(";")[5]).isEqualTo("Наличные")
    }

    @Test
    fun `BOM в строку не добавляется`() {
        val csv = CsvExporter.export(listOf(record()))
        assertThat(csv.startsWith("\uFEFF")).isFalse()
    }
}
