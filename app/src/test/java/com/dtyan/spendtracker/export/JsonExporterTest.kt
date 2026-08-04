package com.dtyan.spendtracker.export

import com.dtyan.spendtracker.domain.model.ExpenseRecord
import com.dtyan.spendtracker.domain.model.PaymentMethod
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class JsonExporterTest {

    private val generatedAt = Instant.parse("2026-07-28T12:34:56Z").toEpochMilli()

    private fun record(
        id: Long = 1L,
        amountMinor: Long = 10_000L,
        date: LocalDate = LocalDate.of(2026, 7, 1),
        category: String = "Продукты",
        subcategory: String? = "Супермаркет",
        note: String = "",
        paymentMethod: PaymentMethod = PaymentMethod.CARD,
    ) = ExpenseRecord(
        id = id,
        amountMinor = amountMinor,
        currency = "RUB",
        categoryId = 1L,
        categoryName = category,
        subcategoryId = subcategory?.let { 10L },
        subcategoryName = subcategory,
        date = date,
        note = note,
        paymentMethod = paymentMethod,
        createdAt = 0L,
    )

    private fun parse(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `вывод — валидный JSON`() {
        val json = JsonExporter.export(listOf(record()), generatedAt, "1.0")
        val root = parse(json)
        assertThat(root.keys).containsAtLeast(
            "schemaVersion", "app", "appVersion", "generatedAt",
            "currency", "amountUnit", "count", "totalMinor", "expenses",
        )
    }

    @Test
    fun `метаполя заполнены`() {
        val json = JsonExporter.export(listOf(record()), generatedAt, "1.2.3")
        val root = parse(json)
        assertThat(root.getValue("schemaVersion").jsonPrimitive.int).isEqualTo(1)
        assertThat(root.getValue("app").jsonPrimitive.content).isEqualTo("SpendTracker")
        assertThat(root.getValue("appVersion").jsonPrimitive.content).isEqualTo("1.2.3")
        assertThat(root.getValue("currency").jsonPrimitive.content).isEqualTo("RUB")
        assertThat(root.getValue("amountUnit").jsonPrimitive.content).isEqualTo("minor")
    }

    @Test
    fun `count и totalMinor совпадают с данными`() {
        val records = listOf(
            record(id = 1, amountMinor = 10_000),
            record(id = 2, amountMinor = 25_050),
            record(id = 3, amountMinor = 1),
        )
        val root = parse(JsonExporter.export(records, generatedAt, "1.0"))
        assertThat(root.getValue("count").jsonPrimitive.int).isEqualTo(3)
        assertThat(root.getValue("totalMinor").jsonPrimitive.content).isEqualTo("35051")
        assertThat(root.getValue("expenses").jsonArray).hasSize(3)
    }

    @Test
    fun `дата в формате ISO`() {
        val json = JsonExporter.export(
            listOf(record(date = LocalDate.of(2026, 1, 9))),
            generatedAt,
            "1.0",
        )
        val expense = parse(json).getValue("expenses").jsonArray.first().jsonObject
        assertThat(expense.getValue("date").jsonPrimitive.content).isEqualTo("2026-01-09")
    }

    @Test
    fun `поля траты соответствуют записи`() {
        val json = JsonExporter.export(
            listOf(
                record(
                    id = 42,
                    amountMinor = 99_900,
                    category = "Транспорт",
                    subcategory = "Такси",
                    note = "Аэропорт",
                    paymentMethod = PaymentMethod.ONLINE,
                )
            ),
            generatedAt,
            "1.0",
        )
        val expense = parse(json).getValue("expenses").jsonArray.first().jsonObject
        assertThat(expense.getValue("id").jsonPrimitive.content).isEqualTo("42")
        assertThat(expense.getValue("amountMinor").jsonPrimitive.content).isEqualTo("99900")
        assertThat(expense.getValue("currency").jsonPrimitive.content).isEqualTo("RUB")
        assertThat(expense.getValue("category").jsonPrimitive.content).isEqualTo("Транспорт")
        assertThat(expense.getValue("subcategory").jsonPrimitive.content).isEqualTo("Такси")
        assertThat(expense.getValue("paymentMethod").jsonPrimitive.content).isEqualTo("ONLINE")
        assertThat(expense.getValue("note").jsonPrimitive.content).isEqualTo("Аэропорт")
    }

    @Test
    fun `отсутствующая подкатегория сериализуется как null`() {
        val json = JsonExporter.export(listOf(record(subcategory = null)), generatedAt, "1.0")
        val expense = parse(json).getValue("expenses").jsonArray.first().jsonObject
        assertThat(expense.getValue("subcategory")).isEqualTo(JsonNull)
    }

    @Test
    fun `пустой список даёт валидный JSON с нулями`() {
        val root = parse(JsonExporter.export(emptyList(), generatedAt, "1.0"))
        assertThat(root.getValue("count").jsonPrimitive.int).isEqualTo(0)
        assertThat(root.getValue("totalMinor").jsonPrimitive.content).isEqualTo("0")
        assertThat(root.getValue("expenses").jsonArray).isEmpty()
    }

    @Test
    fun `вывод детерминирован`() {
        val records = listOf(record(id = 1), record(id = 2, amountMinor = 777))
        val first = JsonExporter.export(records, generatedAt, "1.0")
        val second = JsonExporter.export(records, generatedAt, "1.0")
        assertThat(second).isEqualTo(first)
    }

    @Test
    fun `таймстемп в UTC по ISO-8601`() {
        val root = parse(JsonExporter.export(emptyList(), generatedAt, "1.0"))
        val stamp = root.getValue("generatedAt").jsonPrimitive.content
        assertThat(stamp).isEqualTo("2026-07-28T12:34:56Z")
        // И парсится обратно ровно в ту же точку времени.
        assertThat(Instant.parse(stamp).toEpochMilli()).isEqualTo(generatedAt)
    }

    @Test
    fun `миллисекунды в таймстемпе отбрасываются`() {
        val withMillis = Instant.parse("2026-07-28T12:34:56.789Z").toEpochMilli()
        val root = parse(JsonExporter.export(emptyList(), withMillis, "1.0"))
        assertThat(root.getValue("generatedAt").jsonPrimitive.content)
            .isEqualTo("2026-07-28T12:34:56Z")
    }

    @Test
    fun `порядок трат сохраняется`() {
        val records = listOf(record(id = 5), record(id = 3), record(id = 9))
        val ids = parse(JsonExporter.export(records, generatedAt, "1.0"))
            .getValue("expenses").jsonArray
            .map { it.jsonObject.getValue("id").jsonPrimitive.content }
        assertThat(ids).containsExactly("5", "3", "9").inOrder()
    }
}
