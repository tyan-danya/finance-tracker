package com.dtyan.spendtracker.importer

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.nio.charset.Charset

class TinkoffStatementParserTest {

    // Синтетическая выписка в формате Т-Банка (никаких реальных данных).
    // '~' заменяет двойную кавычку, чтобы не конфликтовать с raw-строками Kotlin
    // (в CSV встречаются экранированные кавычки "" — три подряд ломали бы литерал).
    private val sample = listOf(
        "~Дата операции~;~Дата платежа~;~Номер карты~;~Статус~;~Сумма операции~;~Валюта операции~;~Сумма платежа~;~Валюта платежа~;~Кэшбэк~;~Категория~;~MCC~;~Описание~;~Бонусы (включая кэшбэк)~;~Округление на инвесткопилку~;~Сумма операции с округлением~",
        "~05.07.2026 12:27:59~;~05.07.2026~;~*1234~;~OK~;~-422,00~;~RUB~;~-422,00~;~RUB~;~~;~Супермаркеты~;~5411~;~Магазин~;~0,00~;~28,00~;~-450,00~",
        "~06.07.2026 14:49:25~;~06.07.2026~;~*1234~;~OK~;~-70000,00~;~RUB~;~-70000,00~;~RUB~;~~;~Переводы~;~~;~Между своими счетами~;~0,00~;~0,00~;~-70000,00~",
        "~08.07.2026 13:20:40~;~08.07.2026~;~*1234~;~OK~;~-600,00~;~RUB~;~-600,00~;~RUB~;~~;~Переводы~;~~;~Иван И.~;~0,00~;~0,00~;~-600,00~",
        "~03.07.2026 12:28:29~;~03.07.2026~;~*1234~;~OK~;~57618,90~;~RUB~;~57618,90~;~RUB~;~~;~Зарплата~;~~;~Пополнение. Зарплата. ООО ~~РОГА И КОПЫТА~~~;~0,00~;~0,00~;~57618,90~",
        "~15.07.2026 22:31:45~;~~;~~;~OK~;~175,01~;~RUB~;~175,01~;~RUB~;~~;~Бонусы~;~~;~Зачисление кэшбэка~;~0,00~;~0,00~;~175,01~",
        "~24.07.2026 14:02:54~;~24.07.2026~;~*1234~;~OK~;~-17790,00~;~RUB~;~-17790,00~;~RUB~;~~;~Кредиты~;~9999~;~Досрочное погашение~;~0,00~;~0,00~;~-17790,00~",
        "~11.07.2026 15:56:48~;~11.07.2026~;~*1234~;~OK~;~-499,00~;~RUB~;~-499,00~;~RUB~;~4,00~;~Фастфуд~;~5814~;~Котофей~;~4,00~;~1,00~;~-500,00~",
        "~12.07.2026 10:00:00~;~12.07.2026~;~*1234~;~FAILED~;~-100,00~;~RUB~;~-100,00~;~RUB~;~~;~Разное~;~~;~Отклонено~;~0,00~;~0,00~;~-100,00~",
        "~20.07.2026 14:59:48~;~20.07.2026~;~*1234~;~OK~;~-235,00~;~RUB~;~-235,00~;~RUB~;~2,00~;~Такси~;~3990~;~Яндекс Такси~;~2,00~;~15,00~;~-250,00~",
        "~21.07.2026 09:00:00~;~21.07.2026~;~*1234~;~OK~;~-300,00~;~RUB~;~-300,00~;~RUB~;~~;~Различные услуги~;~7299~;~Оплата; спасибо~;~0,00~;~0,00~;~-300,00~",
        "~22.07.2026 10:32:16~;~22.07.2026~;~~;~OK~;~1000,00~;~RUB~;~1000,00~;~RUB~;~~;~Переводы~;~9999~;~Перевод средств из Кубышки~;~0,00~;~0,00~;~1000,00~",
    ).joinToString("\r\n").replace('~', '"')

    private fun ParseResult.byMerchant(fragment: String): ParsedOperation? =
        operations.firstOrNull { it.merchant.contains(fragment, ignoreCase = true) }

    @Test
    fun `парсит все строки кроме FAILED`() {
        val r = TinkoffStatementParser.parse(sample)
        assertThat(r.operations).hasSize(10) // 11 строк данных, одна FAILED пропущена
        assertThat(r.warnings.any { it.contains("FAILED") }).isTrue()
        assertThat(r.bank).isEqualTo("TBANK")
    }

    @Test
    fun `покупка в супермаркете распознаётся как расход и категоризируется`() {
        val op = TinkoffStatementParser.parse(sample).byMerchant("Магазин")!!
        assertThat(op.kind).isEqualTo(OperationKind.EXPENSE)
        assertThat(op.isOutflow).isTrue()
        assertThat(op.amountMinor).isEqualTo(42200L)
        assertThat(op.suggestedCategoryName).isEqualTo("Продукты")
        assertThat(op.suggestedSubcategoryName).isEqualTo("Супермаркет")
    }

    @Test
    fun `сумма берётся без округления на инвесткопилку`() {
        val op = TinkoffStatementParser.parse(sample).byMerchant("Магазин")!!
        assertThat(op.amountMinor).isEqualTo(42200L)
        assertThat(op.amountMinor).isNotEqualTo(45000L)
    }

    @Test
    fun `перевод между своими счетами - TRANSFER_SELF и не импортируется по умолчанию`() {
        val op = TinkoffStatementParser.parse(sample).byMerchant("Между своими")!!
        assertThat(op.kind).isEqualTo(OperationKind.TRANSFER_SELF)
        assertThat(op.kind.includedByDefault).isFalse()
    }

    @Test
    fun `Кубышка распознаётся как перевод себе несмотря на плюс`() {
        val op = TinkoffStatementParser.parse(sample).byMerchant("Кубышки")!!
        assertThat(op.kind).isEqualTo(OperationKind.TRANSFER_SELF)
    }

    @Test
    fun `перевод человеку - TRANSFER_PEER`() {
        val op = TinkoffStatementParser.parse(sample).byMerchant("Иван")!!
        assertThat(op.kind).isEqualTo(OperationKind.TRANSFER_PEER)
        assertThat(op.kind.includedByDefault).isFalse()
    }

    @Test
    fun `зарплата - доход, импортируется по умолчанию, кавычки развёрнуты`() {
        val op = TinkoffStatementParser.parse(sample).byMerchant("РОГА И КОПЫТА")!!
        assertThat(op.kind).isEqualTo(OperationKind.INCOME)
        assertThat(op.isOutflow).isFalse()
        assertThat(op.amountMinor).isEqualTo(5761890L)
        assertThat(op.kind.includedByDefault).isTrue()
        assertThat(op.merchant).contains("ООО \"РОГА И КОПЫТА\"")
    }

    @Test
    fun `кэшбэк - CASHBACK`() {
        val op = TinkoffStatementParser.parse(sample).byMerchant("кэшбэка")!!
        assertThat(op.kind).isEqualTo(OperationKind.CASHBACK)
    }

    @Test
    fun `погашение кредита - LOAN с категорией Финансы`() {
        val op = TinkoffStatementParser.parse(sample).byMerchant("Досрочное")!!
        assertThat(op.kind).isEqualTo(OperationKind.LOAN)
        assertThat(op.suggestedCategoryName).isEqualTo("Финансы")
    }

    @Test
    fun `фастфуд категоризируется по MCC`() {
        val op = TinkoffStatementParser.parse(sample).byMerchant("Котофей")!!
        assertThat(op.suggestedCategoryName).isEqualTo("Кафе и рестораны")
        assertThat(op.suggestedSubcategoryName).isEqualTo("Фастфуд")
    }

    @Test
    fun `неизвестный MCC такси откатывается на категорию банка`() {
        val op = TinkoffStatementParser.parse(sample).byMerchant("Яндекс Такси")!!
        assertThat(op.suggestedCategoryName).isEqualTo("Транспорт")
        assertThat(op.suggestedSubcategoryName).isEqualTo("Такси")
    }

    @Test
    fun `точка с запятой внутри описания не ломает разбор`() {
        val op = TinkoffStatementParser.parse(sample).byMerchant("Оплата; спасибо")!!
        assertThat(op.merchant).isEqualTo("Оплата; спасибо")
        assertThat(op.amountMinor).isEqualTo(30000L)
    }

    @Test
    fun `externalId детерминирован и различает операции`() {
        val a = TinkoffStatementParser.parse(sample)
        val b = TinkoffStatementParser.parse(sample)
        val idsA = a.operations.map { it.externalId }
        val idsB = b.operations.map { it.externalId }
        assertThat(idsA).isEqualTo(idsB)
        assertThat(idsA.toSet()).hasSize(idsA.size)
    }

    @Test
    fun `нет обязательных колонок - осмысленное предупреждение`() {
        val r = TinkoffStatementParser.parse("foo;bar\n1;2")
        assertThat(r.operations).isEmpty()
        assertThat(r.warnings.any { it.contains("колонк", ignoreCase = true) }).isTrue()
    }

    @Test
    fun `parseSignedMinor - разные форматы`() {
        assertThat(TinkoffStatementParser.parseSignedMinor("-1 234,56")).isEqualTo(-123456L)
        assertThat(TinkoffStatementParser.parseSignedMinor("1234.5")).isEqualTo(123450L)
        assertThat(TinkoffStatementParser.parseSignedMinor("57618,90")).isEqualTo(5761890L)
        assertThat(TinkoffStatementParser.parseSignedMinor("(123,45)")).isEqualTo(-12345L)
        assertThat(TinkoffStatementParser.parseSignedMinor("1000,00 ₽")).isEqualTo(100000L)
        assertThat(TinkoffStatementParser.parseSignedMinor("")).isNull()
        assertThat(TinkoffStatementParser.parseSignedMinor("abc")).isNull()
    }

    @Test
    fun `decode - windows-1251`() {
        val text = "Привет; тест кириллица"
        val bytes = text.toByteArray(Charset.forName("windows-1251"))
        assertThat(TinkoffStatementParser.decode(bytes)).isEqualTo(text)
    }

    @Test
    fun `decode - UTF-8 с BOM`() {
        val payload = "Привет"
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            payload.toByteArray(Charsets.UTF_8)
        assertThat(TinkoffStatementParser.decode(bytes)).isEqualTo(payload)
    }
}
