package com.dtyan.spendtracker.importer

import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.charset.Charset
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Парсер CSV-выписки Т-Банка (личный кабинет → «Выгрузка операций» → CSV).
 *
 * Особенности формата, под которые заточен парсер:
 *  - разделитель `;`, поля в кавычках, кодировка windows-1251 (или UTF-8);
 *  - суммы с запятой в качестве десятичного разделителя, расход — со знаком минус;
 *  - две даты (операции и платежа) — для учёта берём дату операции;
 *  - служебная колонка «Сумма операции с округлением» (инвесткопилка) ИГНОРИРУЕТСЯ;
 *  - «Между своими счетами», Кубышка, погашение кредита, кэшбэк — не покупки.
 *
 * Чистый Kotlin: работает со строкой; декодирование байтов — отдельный хелпер [decode].
 */
object TinkoffStatementParser {

    const val BANK = "TBANK"

    private val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")
    private val DATE_ONLY: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")

    // Канонические имена колонок → возможные заголовки (на случай мелких отличий формата).
    private const val COL_DATE = "дата операции"
    private const val COL_STATUS = "статус"
    private const val COL_AMOUNT = "сумма операции"
    private const val COL_CURRENCY = "валюта операции"
    private const val COL_CATEGORY = "категория"
    private const val COL_MCC = "mcc"
    private const val COL_DESC = "описание"
    private const val COL_CARD = "номер карты"

    /**
     * Определяет кодировку по BOM/содержимому и декодирует байты в строку.
     * Т-Банк отдаёт CSV в windows-1251; но пользователь мог пересохранить в UTF-8.
     */
    fun decode(bytes: ByteArray): String {
        // BOM UTF-8
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        // Пробуем строгий UTF-8: если валиден и содержит кириллицу — это UTF-8.
        val utf8 = tryStrictUtf8(bytes)
        if (utf8 != null && utf8.any { it in 'А'..'я' || it == 'ё' || it == 'Ё' }) return utf8
        // Иначе — windows-1251.
        val cp1251 = Charset.forName("windows-1251")
        return String(bytes, cp1251)
    }

    private fun tryStrictUtf8(bytes: ByteArray): String? = try {
        val decoder = Charsets.UTF_8.newDecoder()
        decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    } catch (_: Exception) {
        null
    }

    fun parse(bytes: ByteArray): ParseResult = parse(decode(bytes))

    fun parse(text: String): ParseResult {
        val rows = CsvReader.parse(text, delimiter = ';')
        if (rows.isEmpty()) return ParseResult(emptyList(), BANK, listOf("Файл пуст"))

        val header = rows.first().map { it.trim().lowercase() }
        val idx = header.withIndex().associate { (i, name) -> name to i }
        val required = listOf(COL_DATE, COL_AMOUNT)
        val missing = required.filter { it !in idx }
        if (missing.isNotEmpty()) {
            return ParseResult(
                emptyList(), BANK,
                listOf("Не найдены обязательные колонки: ${missing.joinToString()}. Это выписка Т-Банка в CSV?"),
            )
        }

        val warnings = ArrayList<String>()
        val operations = ArrayList<ParsedOperation>()

        rows.drop(1).forEachIndexed { rowIndex, row ->
            fun cell(name: String): String = idx[name]?.let { row.getOrNull(it) }?.trim().orEmpty()

            val rawLine = row.joinToString(";")
            val status = cell(COL_STATUS)
            if (status.isNotEmpty() && !status.equals("OK", ignoreCase = true)) {
                warnings.add("Строка ${rowIndex + 2}: статус «$status» — пропущена")
                return@forEachIndexed
            }

            val dateTime = parseDateTime(cell(COL_DATE))
            if (dateTime == null) {
                warnings.add("Строка ${rowIndex + 2}: не распознана дата «${cell(COL_DATE)}» — пропущена")
                return@forEachIndexed
            }

            val signedMinor = parseSignedMinor(cell(COL_AMOUNT))
            if (signedMinor == null) {
                warnings.add("Строка ${rowIndex + 2}: не распознана сумма «${cell(COL_AMOUNT)}» — пропущена")
                return@forEachIndexed
            }

            val isOutflow = signedMinor < 0
            val absMinor = if (isOutflow) -signedMinor else signedMinor
            val bankCategory = cell(COL_CATEGORY)
            val mcc = cell(COL_MCC).toIntOrNull()
            val merchant = cell(COL_DESC)
            val currency = cell(COL_CURRENCY).ifEmpty { "RUB" }
            val card = cell(COL_CARD).ifEmpty { null }

            val kind = classify(isOutflow, bankCategory, merchant, mcc)
            val suggestion = if (kind == OperationKind.EXPENSE) {
                BankCategoryMapper.suggest(bankCategory, mcc)
            } else if (kind == OperationKind.LOAN) {
                BankCategoryMapper.Suggestion("Финансы", "Проценты по кредиту")
            } else {
                null
            }

            operations.add(
                ParsedOperation(
                    dateTime = dateTime,
                    amountMinor = absMinor,
                    isOutflow = isOutflow,
                    currency = currency,
                    kind = kind,
                    bankCategory = bankCategory,
                    mcc = mcc,
                    merchant = merchant,
                    cardMask = card,
                    suggestedCategoryName = suggestion?.category,
                    suggestedSubcategoryName = suggestion?.subcategory,
                    rawLine = rawLine,
                )
            )
        }

        return ParseResult(operations, BANK, warnings)
    }

    /**
     * Классификация назначения операции. Порядок проверок важен: «Между своими счетами»
     * и Кубышка имеют категорию «Переводы», поэтому проверяются до общего случая переводов.
     */
    private fun classify(isOutflow: Boolean, bankCategory: String, merchant: String, mcc: Int?): OperationKind {
        val cat = bankCategory.trim().lowercase()
        val desc = merchant.trim().lowercase()

        val isSelf = desc.contains("между своими") || desc.contains("кубышк") ||
            desc.contains("инвесткопилк")
        if (isSelf) return OperationKind.TRANSFER_SELF

        if (desc.contains("кэшбэк") || desc.contains("кешбэк") || cat == "бонусы") {
            return OperationKind.CASHBACK
        }

        if (cat == "кредиты" || desc.contains("погашение") && desc.contains("кредит") ||
            desc.contains("досрочное погашение")
        ) {
            return OperationKind.LOAN
        }

        if (cat == "переводы") return OperationKind.TRANSFER_PEER

        if (!isOutflow) {
            // Входящие: зарплата/пополнения/прочие зачисления — доход (модель v1 не хранит).
            return OperationKind.INCOME
        }

        return OperationKind.EXPENSE
    }

    private fun parseDateTime(raw: String): LocalDateTime? {
        if (raw.isEmpty()) return null
        return try {
            LocalDateTime.parse(raw, DATE_TIME)
        } catch (_: Exception) {
            try {
                LocalDate.parse(raw, DATE_ONLY).atStartOfDay()
            } catch (_: Exception) {
                // Иногда дата и время разделены не пробелом — берём первые два токена.
                val parts = raw.split(' ', '\t').filter { it.isNotBlank() }
                try {
                    val d = LocalDate.parse(parts[0], DATE_ONLY)
                    val t = if (parts.size > 1) LocalTime.parse(parts[1]) else LocalTime.MIN
                    LocalDateTime.of(d, t)
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    /**
     * Парсит сумму со знаком в копейки. Поддерживает `-1 234,56`, `1234.5`, неразрывные
     * пробелы, символ валюты, скобки как минус `(123,45)`. Возвращает null, если не число.
     */
    fun parseSignedMinor(raw: String): Long? {
        if (raw.isBlank()) return null
        var s = raw.trim()
        var negative = false
        if (s.startsWith("(") && s.endsWith(")")) { negative = true; s = s.substring(1, s.length - 1) }
        s = s.replace(" ", "")   // неразрывный пробел
            .replace(" ", "")    // узкий неразрывный пробел
            .replace(" ", "")
            .replace("₽", "")
            .replace("руб.", "", ignoreCase = true)
            .replace("руб", "", ignoreCase = true)
        if (s.startsWith("-")) { negative = true; s = s.substring(1) }
        if (s.startsWith("+")) s = s.substring(1)
        if (s.isEmpty()) return null

        // Десятичный разделитель — последний из , или .
        val lastComma = s.lastIndexOf(',')
        val lastDot = s.lastIndexOf('.')
        val sep = maxOf(lastComma, lastDot)
        val normalized = if (sep >= 0) {
            val intPart = s.substring(0, sep).replace(",", "").replace(".", "")
            val frac = s.substring(sep + 1)
            if (frac.length in 1..2 && frac.all { it.isDigit() }) "$intPart.$frac"
            else (intPart + frac)
        } else s

        if (normalized.isEmpty() || !normalized.all { it.isDigit() || it == '.' }) return null
        return try {
            val value = BigDecimal(normalized).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact()
            if (negative) -value else value
        } catch (_: Exception) {
            null
        }
    }
}
