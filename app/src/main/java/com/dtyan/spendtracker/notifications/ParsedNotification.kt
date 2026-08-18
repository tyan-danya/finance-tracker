package com.dtyan.spendtracker.notifications

import com.dtyan.spendtracker.domain.model.EntryType
import com.dtyan.spendtracker.domain.model.PaymentMethod
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Что за операция пришла в уведомлении. */
enum class NotificationKind(val title: String) {
    PURCHASE("Покупка"),
    WITHDRAWAL("Снятие наличных"),
    TRANSFER_OUT("Перевод"),
    INCOME("Пополнение"),
    REFUND("Возврат"),

    /** Текст банковский и сумма в нём есть, но тип операции не распознан. */
    UNKNOWN("Не распознано");

    /** Расход или пополнение — определяет знак в приложении. */
    val entryType: EntryType
        get() = if (this == INCOME || this == REFUND) EntryType.INCOME else EntryType.EXPENSE

    /** Способ оплаты по умолчанию для такой операции. */
    val paymentMethod: PaymentMethod
        get() = when (this) {
            PURCHASE, REFUND, UNKNOWN -> PaymentMethod.CARD
            WITHDRAWAL -> PaymentMethod.CASH
            TRANSFER_OUT, INCOME -> PaymentMethod.TRANSFER
        }
}

/**
 * Результат разбора одного банковского уведомления — до записи в очередь подтверждения.
 * Суммы в копейках, [amountMinor] всегда положительна (знак определяется [kind]).
 */
data class ParsedNotification(
    val bank: String,
    val packageName: String,
    val kind: NotificationKind,
    val amountMinor: Long,
    val currency: String,
    val merchant: String?,
    val cardMask: String?,
    val postedAtMillis: Long,
    val title: String?,
    val rawText: String,
) {

    /** Распознано полностью: известны и сумма, и тип операции. */
    val isRecognized: Boolean get() = amountMinor > 0 && kind != NotificationKind.UNKNOWN

    fun date(zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        Instant.ofEpochMilli(postedAtMillis).atZone(zone).toLocalDate()

    /**
     * Ключ дедупликации. Одно и то же уведомление система присылает повторно при обновлении,
     * поэтому в ключ входит окно времени (2 минуты), а не точная метка. Текст участвует хешем,
     * чтобы две разные операции на одну сумму в одном окне не схлопнулись.
     *
     * Тот же ключ становится `externalId` подтверждённой траты — повторное подтверждение
     * той же операции отсекается уникальным индексом `(source, externalId)`.
     */
    val dedupKey: String
        get() = buildString {
            append(bank); append('|')
            append(kind.entryType.name); append('|')
            append(amountMinor); append('|')
            append(MerchantNormalizer.key(merchant)); append('|')
            append(rawText.trim().hashCode()); append('|')
            append(postedAtMillis / DEDUP_WINDOW_MILLIS)
        }

    companion object {
        /** Окно схлопывания повторов одного уведомления. */
        const val DEDUP_WINDOW_MILLIS = 2 * 60 * 1000L
    }
}
