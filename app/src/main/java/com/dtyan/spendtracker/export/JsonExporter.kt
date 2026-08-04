package com.dtyan.spendtracker.export

import com.dtyan.spendtracker.domain.model.ExpenseRecord
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Экспорт трат в JSON — полный, машиночитаемый дамп для переноса или внешнего анализа.
 * Суммы остаются в минорных единицах (копейках), об этом говорит поле `amountUnit`.
 */
object JsonExporter {

    private const val SCHEMA_VERSION = 1
    private const val APP_NAME = "SpendTracker"
    private const val DEFAULT_CURRENCY = "RUB"

    private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val TIMESTAMP_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    @Serializable
    private data class ExpenseDto(
        val id: Long,
        val date: String,
        val amountMinor: Long,
        val currency: String,
        val category: String,
        val subcategory: String?,
        val paymentMethod: String,
        val note: String,
    )

    @Serializable
    private data class ExportDto(
        val schemaVersion: Int,
        val app: String,
        val appVersion: String,
        val generatedAt: String,
        val currency: String,
        val amountUnit: String,
        val count: Int,
        val totalMinor: Long,
        val expenses: List<ExpenseDto>,
    )

    fun export(
        records: List<ExpenseRecord>,
        generatedAtEpochMillis: Long,
        appVersion: String,
    ): String {
        val dto = ExportDto(
            schemaVersion = SCHEMA_VERSION,
            app = APP_NAME,
            appVersion = appVersion,
            generatedAt = formatTimestamp(generatedAtEpochMillis),
            currency = DEFAULT_CURRENCY,
            amountUnit = "minor",
            count = records.size,
            totalMinor = records.sumOf { it.amountMinor },
            expenses = records.map { it.toDto() },
        )
        return json.encodeToString(dto)
    }

    private fun ExpenseRecord.toDto() = ExpenseDto(
        id = id,
        date = date.format(DATE_FORMAT),
        amountMinor = amountMinor,
        currency = currency,
        category = categoryName,
        subcategory = subcategoryName,
        paymentMethod = paymentMethod.name,
        note = note,
    )

    /** ISO-8601 в UTC с точностью до секунды: "2026-07-28T12:34:56Z". */
    private fun formatTimestamp(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis).atOffset(ZoneOffset.UTC).format(TIMESTAMP_FORMAT)
}
