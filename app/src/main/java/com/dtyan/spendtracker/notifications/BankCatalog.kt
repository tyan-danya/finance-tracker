package com.dtyan.spendtracker.notifications

/**
 * Источник уведомлений — банк и пакеты его приложений.
 *
 * Слушаем **только** приложения из этого списка: доступ к уведомлениям выдаётся системой
 * ко всем сразу, поэтому фильтр по пакетам — единственная гарантия, что чужие уведомления
 * (мессенджеры, почта) не читаются вовсе.
 */
data class BankSource(
    /** Код банка, он же ключ настройки и значение `bank` в базе. */
    val code: String,
    val title: String,
    val packages: Set<String>,
    /** Включён ли источник по умолчанию при первом включении автоучёта. */
    val enabledByDefault: Boolean = true,
    /** Источник — приложение СМС: банк определяется по отправителю в заголовке. */
    val isSms: Boolean = false,
)

/**
 * Каталог поддерживаемых банков. Список намеренно избыточен: лишний пакет,
 * которого нет на устройстве, ничего не стоит, а отсутствующий — теряет операции.
 */
object BankCatalog {

    const val SMS_CODE = "SMS"

    val sources: List<BankSource> = listOf(
        BankSource(
            code = "TBANK",
            title = "Т-Банк",
            packages = setOf(
                "com.idamob.tinkoff.android",
                "ru.tinkoff.mobile",
                "com.tbank.mobile",
            ),
        ),
        BankSource(
            code = "SBER",
            title = "СберБанк",
            packages = setOf(
                "ru.sberbankmobile",
                "ru.sberbankmobile_alpha",
                "ru.sberbank.online",
            ),
        ),
        BankSource(
            code = "ALFA",
            title = "Альфа-Банк",
            packages = setOf(
                "ru.alfabank.mobile.android",
                "ru.alfabank.oavdo.amc",
            ),
        ),
        BankSource(
            code = "VTB",
            title = "ВТБ",
            packages = setOf(
                "ru.vtb24.mobilebanking.android",
                "ru.vtb.mobilebank",
            ),
        ),
        BankSource(
            code = "RAIFF",
            title = "Райффайзен Банк",
            packages = setOf(
                "ru.raiffeisennews",
                "ru.raiffeisen.mobile.new",
            ),
        ),
        BankSource(
            code = "GAZPROM",
            title = "Газпромбанк",
            packages = setOf("ru.gazprombank.android.mobilebank.app"),
        ),
        BankSource(
            code = "MTSBANK",
            title = "МТС Банк",
            packages = setOf("ru.mtsbank.mobile"),
        ),
        BankSource(
            code = "OZON",
            title = "Ozon Банк",
            packages = setOf("ru.ozon.app.android"),
        ),
        BankSource(
            code = "YANDEX",
            title = "Яндекс Пэй",
            packages = setOf("ru.yandex.bank", "com.yandex.bank"),
        ),
        BankSource(
            code = "RSHB",
            title = "Россельхозбанк",
            packages = setOf("ru.rshb.mbank"),
        ),
        BankSource(
            code = "PSB",
            title = "Промсвязьбанк",
            packages = setOf("logo.com.mobilebank.app"),
        ),
        BankSource(
            code = SMS_CODE,
            title = "СМС от банков",
            packages = setOf(
                "com.google.android.apps.messaging",
                "com.samsung.android.messaging",
                "com.android.messaging",
                "com.android.mms",
                "ru.mail.mailapp.messages",
                "com.miui.smsextra",
                "com.xiaomi.mms",
            ),
            // По умолчанию выключено: в СМС-приложении лежит переписка, а не только банк.
            // Даже с фильтром по отправителю включать это должен пользователь осознанно.
            enabledByDefault = false,
            isSms = true,
        ),
    )

    private val byPackage: Map<String, BankSource> =
        sources.flatMap { source -> source.packages.map { it to source } }.toMap()

    private val byCode: Map<String, BankSource> = sources.associateBy { it.code }

    fun byPackage(packageName: String?): BankSource? = packageName?.let { byPackage[it] }

    fun byCode(code: String?): BankSource? = code?.let { byCode[it] }

    /** Человекочитаемое имя банка по коду; неизвестный код показываем как есть. */
    fun title(code: String): String = byCode[code]?.title ?: code

    /** Коды источников, включаемые при первом включении автоучёта. */
    val defaultEnabledCodes: Set<String> =
        sources.filter { it.enabledByDefault }.map { it.code }.toSet()

    /** Известные отправители банковских СМС: по ним определяем банк, остальные игнорируем. */
    private val smsSenders: List<Pair<Regex, String>> = listOf(
        Regex("""^900$""") to "SBER",
        Regex("""sber""", RegexOption.IGNORE_CASE) to "SBER",
        Regex("""t[- ]?bank|tinkoff|т[- ]?банк|тинькофф""", RegexOption.IGNORE_CASE) to "TBANK",
        Regex("""alfa|альфа""", RegexOption.IGNORE_CASE) to "ALFA",
        Regex("""vtb|втб""", RegexOption.IGNORE_CASE) to "VTB",
        Regex("""raiff|райф""", RegexOption.IGNORE_CASE) to "RAIFF",
        Regex("""gazprom|газпромбанк""", RegexOption.IGNORE_CASE) to "GAZPROM",
        Regex("""mtsbank|мтс[- ]?банк""", RegexOption.IGNORE_CASE) to "MTSBANK",
        Regex("""ozon""", RegexOption.IGNORE_CASE) to "OZON",
        Regex("""^rshb|россельхоз""", RegexOption.IGNORE_CASE) to "RSHB",
        Regex("""psbank|промсвязь""", RegexOption.IGNORE_CASE) to "PSB",
    )

    /**
     * Определяет банк по отправителю СМС (заголовку уведомления).
     * @return код банка или null, если отправитель не банковский — такое уведомление не читаем.
     */
    fun bankBySmsSender(sender: String?): String? {
        val cleaned = sender?.trim().orEmpty()
        if (cleaned.isEmpty()) return null
        return smsSenders.firstOrNull { (pattern, _) -> pattern.containsMatchIn(cleaned) }?.second
    }
}
