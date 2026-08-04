package com.dtyan.spendtracker.export

import com.dtyan.spendtracker.domain.model.ExpenseRecord
import java.time.format.DateTimeFormatter

/**
 * Экспорт трат в CSV.
 *
 * Разделитель — точка с запятой: так файл корректно открывается в Excel с русской локалью
 * (там `;` — стандартный разделитель списка, а запятая занята под десятичный разделитель).
 * BOM здесь НЕ добавляется — его дописывает [ExportManager] при записи файла.
 */
object CsvExporter {

    private const val SEPARATOR = ';'
    private const val LINE_BREAK = "\r\n"

    private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")

    private val HEADER = listOf(
        "Дата",
        "Сумма",
        "Валюта",
        "Категория",
        "Подкатегория",
        "Способ оплаты",
        "Комментарий",
        "ID",
    )

    /** Порядок записей сохраняется как есть — пересортировки нет. */
    fun export(records: List<ExpenseRecord>): String {
        val lines = ArrayList<String>(records.size + 1)
        lines += HEADER.joinToString(SEPARATOR.toString()) { escape(it) }
        for (record in records) {
            lines += listOf(
                record.date.format(DATE_FORMAT),
                formatAmount(record.amountMinor),
                record.currency,
                record.categoryName,
                record.subcategoryName.orEmpty(),
                record.paymentMethod.title,
                record.note,
                record.id.toString(),
            ).joinToString(SEPARATOR.toString()) { escape(it) }
        }
        return lines.joinToString(LINE_BREAK)
    }

    /** Копейки → рубли с двумя знаками и запятой: 123456 → "1234,56". Без разделителей разрядов. */
    private fun formatAmount(minor: Long): String {
        val negative = minor < 0
        val abs = if (negative) -minor else minor
        val rubles = abs / 100
        val kopeks = (abs % 100).toInt()
        val sb = StringBuilder()
        if (negative) sb.append('-')
        sb.append(rubles).append(',').append(kopeks.toString().padStart(2, '0'))
        return sb.toString()
    }

    /** Экранирование по RFC 4180 с поправкой на разделитель `;`. */
    private fun escape(field: String): String {
        val needsQuotes = field.any { it == SEPARATOR || it == '"' || it == '\n' || it == '\r' }
        if (!needsQuotes) return field
        return "\"" + field.replace("\"", "\"\"") + "\""
    }
}
