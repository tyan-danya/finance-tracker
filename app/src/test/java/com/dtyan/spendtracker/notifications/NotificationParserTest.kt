package com.dtyan.spendtracker.notifications

import com.dtyan.spendtracker.domain.model.EntryType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Корпус текстов банковских уведомлений. Именно этот тест фиксирует поведение парсера:
 * когда банк изменит формулировку, сюда добавляется новый случай, и правило чинится
 * без запуска приложения.
 */
class NotificationParserTest {

    private val TBANK = "com.idamob.tinkoff.android"
    private val SBER = "ru.sberbankmobile"
    private val ALFA = "ru.alfabank.mobile.android"
    private val VTB = "ru.vtb24.mobilebanking.android"
    private val OZON = "ru.ozon.app.android"
    private val SMS = "com.google.android.apps.messaging"

    private val time = 1_754_000_000_000L

    private fun parse(pkg: String, title: String?, text: String) =
        NotificationParser.parse(pkg, title, text, time)

    @Test
    fun `Т-Банк покупка с мерчантом и остатком`() {
        val result = parse(TBANK, "Покупка", "Покупка, карта *1234. 1 500 ₽. Пятёрочка. Доступно 12 345 ₽")

        assertThat(result).isNotNull()
        assertThat(result!!.bank).isEqualTo("TBANK")
        assertThat(result.kind).isEqualTo(NotificationKind.PURCHASE)
        assertThat(result.amountMinor).isEqualTo(150_000)
        assertThat(result.currency).isEqualTo("RUB")
        assertThat(result.merchant).isEqualTo("Пятёрочка")
        assertThat(result.cardMask).isEqualTo("1234")
        assertThat(result.isRecognized).isTrue()
    }

    @Test
    fun `сумма берётся операции, а не остатка по счёту`() {
        val result = parse(SBER, "СберБанк", "Покупка 1 234,56 ₽ MAGNIT. Баланс: 10 000 ₽")

        assertThat(result!!.amountMinor).isEqualTo(123_456)
        assertThat(result.merchant).isEqualTo("MAGNIT")
    }

    @Test
    fun `кэшбэк внутри уведомления о покупке не путается с суммой`() {
        val result = parse(
            TBANK,
            "Покупка",
            "Покупка, карта *1234. 500 ₽. ПЯТЁРОЧКА. Кэшбэк 5 ₽. Доступно 10 000 ₽",
        )

        assertThat(result!!.amountMinor).isEqualTo(50_000)
        assertThat(result.merchant).isEqualTo("ПЯТЁРОЧКА")
    }

    @Test
    fun `Альфа-Банк покупка с двоеточиями`() {
        val result = parse(ALFA, "Альфа-Банк", "Покупка: 3 500,00 ₽. Карта *5678. LENTA. Доступно: 24 000,00 ₽")

        assertThat(result!!.amountMinor).isEqualTo(350_000)
        assertThat(result.merchant).isEqualTo("LENTA")
        assertThat(result.cardMask).isEqualTo("5678")
    }

    @Test
    fun `ВТБ без разделителей между частями`() {
        val result = parse(VTB, "ВТБ Онлайн", "Покупка 500р Карта*1234 SPAR Доступно 1000р")

        assertThat(result!!.amountMinor).isEqualTo(50_000)
        assertThat(result.merchant).isEqualTo("SPAR")
        assertThat(result.cardMask).isEqualTo("1234")
    }

    @Test
    fun `Ozon с предлогом перед мерчантом`() {
        val result = parse(OZON, "Ozon Банк", "Покупка на 1 234 ₽ в OZON")

        assertThat(result!!.amountMinor).isEqualTo(123_400)
        assertThat(result.merchant).isEqualTo("OZON")
    }

    @Test
    fun `пополнение распознаётся как доход с отправителем`() {
        val result = parse(TBANK, "Пополнение", "Пополнение, счет RUB. 5 000 ₽. Иван И.")

        assertThat(result!!.kind).isEqualTo(NotificationKind.INCOME)
        assertThat(result.kind.entryType).isEqualTo(EntryType.INCOME)
        assertThat(result.amountMinor).isEqualTo(500_000)
        assertThat(result.merchant).isEqualTo("Иван И")
    }

    @Test
    fun `возврат покупки — это доход`() {
        val result = parse(TBANK, "Возврат", "Возврат покупки 1 200 ₽. WILDBERRIES")

        assertThat(result!!.kind).isEqualTo(NotificationKind.REFUND)
        assertThat(result.kind.entryType).isEqualTo(EntryType.INCOME)
        assertThat(result.amountMinor).isEqualTo(120_000)
    }

    @Test
    fun `снятие наличных помечается наличным способом оплаты`() {
        val result = parse(TBANK, "Снятие", "Снятие наличных, карта *1234. 5 000 ₽. Банкомат Т-Банка")

        assertThat(result!!.kind).isEqualTo(NotificationKind.WITHDRAWAL)
        assertThat(result.kind.paymentMethod.name).isEqualTo("CASH")
        assertThat(result.amountMinor).isEqualTo(500_000)
    }

    @Test
    fun `СМС от банковского отправителя разбирается`() {
        val result = parse(SMS, "900", "VISA1234 15:04 покупка 500р TAXI. Баланс: 1000р")

        assertThat(result!!.bank).isEqualTo("SBER")
        assertThat(result.amountMinor).isEqualTo(50_000)
        assertThat(result.merchant).isEqualTo("TAXI")
    }

    @Test
    fun `СМС от небанковского отправителя игнорируется`() {
        assertThat(parse(SMS, "Мама", "Переведи 500 руб пожалуйста")).isNull()
    }

    @Test
    fun `уведомление не из банковского приложения игнорируется`() {
        assertThat(parse("com.whatsapp", "Друг", "Покупка 500 ₽ Пятёрочка")).isNull()
    }

    @Test
    fun `код подтверждения не операция`() {
        assertThat(parse(TBANK, "Т-Банк", "Код подтверждения: 1234. Никому не сообщайте его")).isNull()
    }

    @Test
    fun `рекламное предложение кредита не операция`() {
        assertThat(parse(TBANK, "Т-Банк", "Вам одобрен кредит 300 000 ₽ под 15%")).isNull()
    }

    @Test
    fun `напоминание о будущем списании не операция`() {
        assertThat(parse(SBER, "СберБанк", "Напоминаем: 25 000 ₽ спишутся 5 числа за ипотеку")).isNull()
    }

    @Test
    fun `отклонённая операция не создаёт трату`() {
        assertThat(parse(ALFA, "Альфа-Банк", "Покупка 500 ₽ отклонена: недостаточно средств")).isNull()
    }

    @Test
    fun `текст без суммы игнорируется`() {
        assertThat(parse(TBANK, "Т-Банк", "Ваша карта готова к получению")).isNull()
    }

    @Test
    fun `непонятный тип операции с суммой уходит на ручной разбор`() {
        val result = parse(TBANK, "Т-Банк", "Абонентская плата 199 ₽ за тариф")

        assertThat(result).isNotNull()
        assertThat(result!!.kind).isEqualTo(NotificationKind.UNKNOWN)
        assertThat(result.amountMinor).isEqualTo(19_900)
        assertThat(result.isRecognized).isFalse()
    }

    @Test
    fun `валюта распознаётся по символу`() {
        val result = parse(TBANK, "Покупка", "Покупка, карта *1234. 25.99 USD. STEAM")

        assertThat(result!!.currency).isEqualTo("USD")
        assertThat(result.amountMinor).isEqualTo(2_599)
    }

    @Test
    fun `повтор того же уведомления даёт тот же ключ дедупликации`() {
        val first = parse(TBANK, "Покупка", "Покупка, карта *1234. 500 ₽. ПЯТЁРОЧКА")
        val repeat = NotificationParser.parse(
            TBANK,
            "Покупка",
            "Покупка, карта *1234. 500 ₽. ПЯТЁРОЧКА",
            time + 30_000,
        )

        assertThat(repeat!!.dedupKey).isEqualTo(first!!.dedupKey)
    }

    @Test
    fun `разные покупки на одну сумму не схлопываются`() {
        val first = parse(TBANK, "Покупка", "Покупка, карта *1234. 500 ₽. ПЯТЁРОЧКА")
        val second = parse(TBANK, "Покупка", "Покупка, карта *1234. 500 ₽. МАГНИТ")

        assertThat(second!!.dedupKey).isNotEqualTo(first!!.dedupKey)
    }
}
