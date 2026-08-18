package com.dtyan.spendtracker.notifications

import com.dtyan.spendtracker.data.ExpenseRepository
import com.dtyan.spendtracker.data.PendingEntry
import com.dtyan.spendtracker.data.SettingsStore
import java.time.ZoneId

/**
 * Приём банковского уведомления: настройки → разбор → очередь подтверждения.
 *
 * Вынесено из сервиса, чтобы логика приёма не зависела от Android-классов уведомлений
 * и проверялась тестами: сервис только достаёт текст и передаёт его сюда.
 */
class NotificationIntake(
    private val repository: ExpenseRepository,
    private val settings: SettingsStore,
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
        if (!settingsSnapshot.enabled) return null

        // Пакет должен быть в каталоге, а его источник — включён пользователем.
        val source = BankCatalog.byPackage(packageName) ?: return null
        if (source.code !in settingsSnapshot.enabledBanks) return null

        val parsed = NotificationParser.parse(packageName, title, text, postedAtMillis) ?: return null
        return repository.addPendingOperation(parsed.toEntry(zone))
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
