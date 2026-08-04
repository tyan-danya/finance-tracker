package com.dtyan.spendtracker.importer

import com.dtyan.spendtracker.data.ImportEntry
import com.dtyan.spendtracker.domain.model.EntryType
import java.time.ZoneOffset

/**
 * Преобразует разобранные операции выписки в нейтральные [ImportEntry] для репозитория.
 * Здесь же — подсказка подкатегории для доходов (парсер их не проставляет).
 */
object StatementImporter {

    fun toEntry(op: ParsedOperation): ImportEntry {
        val type = if (op.kind.isIncome) EntryType.INCOME else EntryType.EXPENSE
        val category: String?
        val subcategory: String?
        if (type == EntryType.INCOME) {
            category = "Пополнения"
            subcategory = incomeSubcategory(op.bankCategory, op.merchant)
        } else {
            category = op.suggestedCategoryName
            subcategory = op.suggestedSubcategoryName
        }
        return ImportEntry(
            amountMinor = op.amountMinor,
            type = type,
            date = op.date,
            note = op.merchant,
            suggestedCategory = category,
            suggestedSubcategory = subcategory,
            externalId = op.externalId,
            mcc = op.mcc,
            merchantRaw = op.merchant,
            operationTimeMillis = op.dateTime.toInstant(ZoneOffset.UTC).toEpochMilli(),
            rawText = op.rawLine,
            currency = op.currency,
        )
    }

    /** Только те операции, которые пользователь оставил для импорта. */
    fun toEntries(operations: List<ParsedOperation>): List<ImportEntry> =
        operations.map { toEntry(it) }

    private fun incomeSubcategory(bankCategory: String, merchant: String): String {
        val t = (bankCategory + " " + merchant).lowercase()
        return when {
            t.contains("аванс") -> "Аванс"
            t.contains("зарплат") || t.contains("заработная") -> "Зарплата"
            t.contains("преми") -> "Премия"
            t.contains("кэшбэк") || t.contains("кешбэк") -> "Кэшбэк"
            t.contains("процент") -> "Проценты"
            t.contains("возврат") -> "Возврат"
            t.contains("перевод") -> "Перевод"
            else -> "Прочее"
        }
    }
}
