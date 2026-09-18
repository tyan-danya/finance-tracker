package com.dtyan.spendtracker.notifications

import com.dtyan.spendtracker.data.DiagnosticsLog
import com.dtyan.spendtracker.data.ExpenseRepository
import com.dtyan.spendtracker.data.IngestOutcome
import com.dtyan.spendtracker.data.LogLevel
import com.dtyan.spendtracker.data.LogStage
import com.dtyan.spendtracker.data.PendingEntry
import com.dtyan.spendtracker.data.SettingsStore
import com.dtyan.spendtracker.domain.MoneyFormat
import java.time.ZoneId

/**
 * Приём банковского уведомления: настройки → разбор → очередь подтверждения.
 *
 * Вынесено из сервиса, чтобы логика приёма не зависела от Android-классов уведомлений
 * и проверялась тестами: сервис только достаёт текст и передаёт его сюда.
 *
 * Каждый шаг пишется в журнал диагностики — по нему потом видно, на каком именно
 * этапе уведомление потерялось и почему категория подобралась именно так.
 */
class NotificationIntake(
    private val repository: ExpenseRepository,
    private val settings: SettingsStore,
    private val log: DiagnosticsLog? = null,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    /**
     * @return id созданной записи очереди или null, если уведомление не наше,
     *         автоучёт выключен, банк отключён или это повтор.
     */
    suspend fun handle(
        packageName: String,
        title: String?,
        text: String?,
        postedAtMillis: Long,
    ): Long? {
        val settingsSnapshot = settings.current()
        val source = BankCatalog.byPackage(packageName)
        val bankTitle = source?.title ?: packageName

        // Пока автоучёт выключен, тексты чужих уведомлений в журнал не пишем вовсе:
        // причина «не появилось» тут и так очевидна.
        if (!settingsSnapshot.enabled) {
            log?.log(
                stage = LogStage.NOTIFICATION,
                message = "Уведомление $bankTitle пропущено: автоучёт выключен в настройках",
                level = LogLevel.WARN,
            )
            return null
        }
        if (source == null) {
            log?.log(LogStage.PARSE, "Пропущено: $packageName не в списке банковских приложений")
            return null
        }

        // Дальше событие фиксируем целиком: даже отброшенное уведомление должно быть
        // видно в журнале вместе с исходным текстом — иначе разбирать нечего.
        log?.log(
            stage = LogStage.NOTIFICATION,
            message = "Пришло уведомление: $bankTitle",
            details = buildString {
                appendLine("пакет: $packageName")
                appendLine("заголовок: ${title.orEmpty()}")
                appendLine("текст: ${text.orEmpty()}")
            },
        )
        if (source.code !in settingsSnapshot.enabledBanks) {
            log?.log(
                stage = LogStage.PARSE,
                message = "Пропущено: источник «${source.title}» отключён в настройках",
                level = LogLevel.WARN,
            )
            return null
        }

        val outcome = NotificationParser.analyze(packageName, title, text, postedAtMillis)
        val parsed = when (outcome) {
            is ParseOutcome.Ignored -> {
                log?.log(LogStage.PARSE, "Не операция: ${outcome.reason}")
                return null
            }

            is ParseOutcome.Parsed -> outcome.notification
        }

        log?.log(
            stage = LogStage.PARSE,
            message = "Разобрано: ${parsed.kind.title} ${MoneyFormat.format(parsed.amountMinor)}",
            details = buildString {
                appendLine("банк: ${BankCatalog.title(parsed.bank)}")
                appendLine("тип операции: ${parsed.kind.name} (${parsed.kind.title})")
                appendLine("сумма: ${parsed.amountMinor} коп. ${parsed.currency}")
                appendLine("мерчант: ${parsed.merchant ?: "— не найден"}")
                appendLine("ключ мерчанта: ${MerchantNormalizer.key(parsed.merchant).ifEmpty { "—" }}")
                appendLine("карта: ${parsed.cardMask ?: "—"}")
                appendLine("дата операции: ${parsed.date(zone)}")
                appendLine("распознано полностью: ${if (parsed.isRecognized) "да" else "нет, уйдёт на ручной разбор"}")
                appendLine("ключ дедупликации: ${parsed.dedupKey}")
            },
        )

        val entry = parsed.toEntry(zone)
        return when (val result = repository.addPendingOperation(entry)) {
            is IngestOutcome.Created -> {
                log?.log(
                    stage = LogStage.QUEUE,
                    message = "В очередь добавлена операция #${result.id}",
                    details = buildString {
                        appendLine("мерчант: ${entry.merchant ?: "—"}")
                        appendLine("сумма: ${MoneyFormat.format(entry.amountMinor)}")
                        append("категория: ")
                        if (result.categoryName == null) {
                            appendLine("не подобрана — пользователь выберет сам")
                        } else {
                            appendLine(
                                listOfNotNull(result.categoryName, result.subcategoryName).joinToString(" / ") +
                                    " (" + (result.suggestionSource?.title ?: "источник неизвестен") + ")"
                            )
                        }
                        appendLine("словарь предложил: ${entry.suggestedCategoryName ?: "—"}")
                    },
                )
                result.id
            }

            is IngestOutcome.Duplicate -> {
                log?.log(LogStage.QUEUE, "Повтор, в очередь не добавлено: ${result.reason}")
                null
            }

            IngestOutcome.Unavailable -> {
                log?.log(LogStage.QUEUE, "Очередь недоступна", level = LogLevel.ERROR)
                null
            }
        }
    }
}

/** Превращает разобранное уведомление в запись очереди с подсказкой категории из словаря. */
fun ParsedNotification.toEntry(zone: ZoneId = ZoneId.systemDefault()): PendingEntry {
    val suggestion = MerchantDictionary.suggest(merchant)
    return PendingEntry(
        dedupKey = dedupKey,
        packageName = packageName,
        bank = bank,
        amountMinor = amountMinor,
        currency = currency,
        type = kind.entryType,
        merchant = merchant?.let { MerchantNormalizer.display(it) },
        cardMask = cardMask,
        postedAtMillis = postedAtMillis,
        date = date(zone),
        recognized = isRecognized,
        title = title,
        rawText = rawText,
        paymentMethod = kind.paymentMethod,
        suggestedCategoryName = suggestion?.category,
        suggestedSubcategoryName = suggestion?.subcategory,
    )
}
