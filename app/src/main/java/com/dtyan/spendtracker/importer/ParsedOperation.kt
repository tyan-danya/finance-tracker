package com.dtyan.spendtracker.importer

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Как операция классифицирована при разборе выписки. Определяет поведение по умолчанию
 * на экране предпросмотра.
 */
enum class OperationKind(val title: String) {
    /** Реальная покупка (расход у мерчанта). Импортируется по умолчанию. */
    EXPENSE("Покупки"),

    /** Поступление (зарплата, пополнение, зачисление). Модель v1 доходы не хранит — пропускается. */
    INCOME("Доходы"),

    /** Перевод между своими счетами / вкладами (Кубышка и т.п.). Не трата — исключается. */
    TRANSFER_SELF("Между своими счетами"),

    /** Перевод другому человеку. Неоднозначно — по умолчанию выключен. */
    TRANSFER_PEER("Переводы людям"),

    /** Погашение кредита/займа. По умолчанию выключено. */
    LOAN("Кредиты и займы"),

    /** Кэшбэк, бонусы. Пропускается. */
    CASHBACK("Кэшбэк и бонусы"),

    /** Не удалось однозначно распознать назначение. Уходит на ручное подтверждение. */
    UNKNOWN("Не распознано");

    /** Импортировать ли операции этого типа по умолчанию (галочка в предпросмотре). */
    val includedByDefault: Boolean
        get() = this == EXPENSE || this == INCOME

    /** Пополнение (доход) — противоположность расходу. */
    val isIncome: Boolean
        get() = this == INCOME
}

/**
 * Одна разобранная строка выписки — до записи в БД.
 * Суммы в копейках (Long). [amountMinor] всегда положительное (модуль); знак отражён в [kind].
 */
data class ParsedOperation(
    val dateTime: LocalDateTime,
    /** Модуль суммы операции в копейках. */
    val amountMinor: Long,
    /** true — расход (списание), false — поступление. */
    val isOutflow: Boolean,
    val currency: String,
    val kind: OperationKind,
    /** Категория из выписки банка (как есть). */
    val bankCategory: String,
    val mcc: Int?,
    /** Описание/мерчант из выписки. */
    val merchant: String,
    val cardMask: String?,
    /** Предложенные приложением категория и подкатегория (id проставляются на этапе импорта). */
    val suggestedCategoryName: String?,
    val suggestedSubcategoryName: String?,
    /** Исходная строка выписки целиком — для дедупликации и переразбора. */
    val rawLine: String,
) {
    val date: LocalDate get() = dateTime.toLocalDate()

    /**
     * Детерминированный ключ операции для дедупликации между повторными импортами.
     * Банк не даёт стабильного ID, поэтому собираем его из даты-времени, суммы, знака и мерчанта.
     */
    val externalId: String
        get() = buildString {
            append(dateTime.toString()); append('|')
            append(if (isOutflow) '-' else '+'); append(amountMinor); append('|')
            append(merchant.trim().lowercase())
        }
}

/** Итог разбора файла: операции + сведения о формате для показа пользователю. */
data class ParseResult(
    val operations: List<ParsedOperation>,
    val bank: String,
    /** Диагностические предупреждения (нераспознанные строки, пропущенные заголовки и т.п.). */
    val warnings: List<String>,
)
