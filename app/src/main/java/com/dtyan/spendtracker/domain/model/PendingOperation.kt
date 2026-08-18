package com.dtyan.spendtracker.domain.model

import java.time.LocalDate
import java.time.LocalDateTime

/** Состояние операции, пришедшей из уведомления. */
enum class PendingStatus {
    /** Сумма и тип распознаны — не хватает только решения пользователя. */
    PENDING,

    /** Текст банковский, но разобрать его не удалось: нужен ручной ввод по сырому тексту. */
    UNPARSED;

    companion object {
        fun fromName(raw: String?): PendingStatus =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: PENDING
    }
}

/** Откуда взялась предложенная категория — показывается пользователю честно. */
enum class SuggestionSource(val title: String) {
    /** Такой же мерчант уже встречался в подтверждённых тратах. */
    HISTORY("по вашим прошлым тратам"),

    /** Известная сеть из встроенного словаря. */
    DICTIONARY("по названию магазина");

    companion object {
        fun fromName(raw: String?): SuggestionSource? =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
    }
}

/**
 * Операция из банковского уведомления, ожидающая подтверждения.
 *
 * В статистику, список трат и экспорт не попадает: пока пользователь не подтвердил —
 * это не трата, а предложение (docs/bank-integration.md, §15.5).
 */
data class PendingOperation(
    val id: Long,
    val bankCode: String,
    val bankTitle: String,
    val amountMinor: Long,
    val currency: String,
    val type: EntryType,
    val merchant: String?,
    val cardMask: String?,
    val dateTime: LocalDateTime,
    val status: PendingStatus,
    val categoryId: Long?,
    val categoryName: String?,
    val categoryIcon: String?,
    val categoryColorArgb: Int?,
    val subcategoryId: Long?,
    val subcategoryName: String?,
    val suggestionSource: SuggestionSource?,
    val notificationTitle: String?,
    val rawText: String,
) {
    val date: LocalDate get() = dateTime.toLocalDate()

    val isIncome: Boolean get() = type == EntryType.INCOME

    /** Готова к подтверждению одним нажатием: сумма разобрана и категория выбрана. */
    val isReadyToConfirm: Boolean
        get() = status == PendingStatus.PENDING && amountMinor > 0 && categoryId != null

    /** Заголовок карточки: мерчант, если он есть, иначе банк. */
    val displayTitle: String
        get() = merchant?.takeIf { it.isNotBlank() } ?: bankTitle
}
