package com.dtyan.spendtracker.notifications

import com.dtyan.spendtracker.domain.MoneyFormat

/**
 * Разбор текста банковского уведомления в операцию.
 *
 * Устройство движка (см. docs/bank-integration.md, §7.3):
 *  - это **чистая функция** `(packageName, title, text, time) -> ParsedNotification?`,
 *    поэтому вся логика покрывается unit-тестами на корпусе реальных текстов без Android;
 *  - банк определяется строго по пакету приложения (или по отправителю для СМС);
 *  - не-операции (коды входа, реклама, напоминания, отказы) отсеиваются явными правилами;
 *  - если сумма найдена, но тип операции не понятен — возвращаем [NotificationKind.UNKNOWN]:
 *    операция всё равно попадёт в очередь подтверждения, но с пустыми полями, чтобы
 *    пользователь ничего не потерял и мог дозаполнить руками.
 *
 * Тексты банков меняются, поэтому парсер намеренно не привязан к точным шаблонам конкретного
 * банка: он ищет знакомые признаки (ключевое слово операции, денежный токен, маску карты,
 * имя мерчанта) в любом порядке.
 */
object NotificationParser {

    /** Пробельные символы, которые банки используют как разделитель разрядов. */
    private const val SP = "[ \\u00A0\\u202F\\u2009]"

    /**
     * Денежный токен: «1 234,56 ₽», «349р.», «500р», «1234.56 RUB», «$25».
     * Порядок вариантов валюты важен: «руб» проверяется раньше одиночной «р».
     */
    private val MONEY = Regex(
        """(\d{1,3}(?:$SP\d{3})+(?:[.,]\d{1,2})?|\d+(?:[.,]\d{1,2})?)$SP?(₽|рублей|руб\.?|р\.?|rub|rur|usd|\$|eur|€)""",
        RegexOption.IGNORE_CASE,
    )

    /** Маска карты/счёта: «карта *1234», «••1234», «счёт 1234». */
    private val CARD_MASKED = Regex(
        """(?:\*|•|·|•|х|x){1,4}$SP?(\d{4})\b""",
        RegexOption.IGNORE_CASE,
    )
    private val CARD_WORD = Regex(
        """(?:карт[аыуе]?|счет|счёт|card)$SP?№?$SP?(\d{4})\b""",
        RegexOption.IGNORE_CASE,
    )

    /** Время и дата внутри текста — мусор для извлечения мерчанта. */
    private val TIME = Regex("""\b\d{1,2}:\d{2}(:\d{2})?\b""")
    private val DATE = Regex("""\b\d{1,2}[./]\d{1,2}([./]\d{2,4})?\b""")

    /**
     * Слова, после которых идёт НЕ сумма операции, а остаток/бонус.
     * Проверяются в окне перед денежным токеном.
     */
    private val balanceMarkers = listOf(
        "баланс", "остаток", "доступно", "на счете", "на счёте", "лимит",
        "кэшбэк", "кешбэк", "бонус", "накоплено", "начислено",
    )

    /**
     * Признаки, при которых уведомление игнорируется всегда — даже если в нём есть
     * и ключевое слово операции, и сумма. Это отказы, напоминания и предложения:
     * денег по ним не двигалось.
     */
    private val hardIgnoreMarkers = listOf(
        "код подтверждения", "код для входа", "никому не сообщайте", "никому не сообщай",
        "одобрен", "предодобрен", "оплатите", "напоминаем", "напоминание",
        "задолженност", "просроч", "будет списан", "спишем", "запланирован",
        "не удалось", "отклонен", "отклонён", "отказ", "заблокирован",
        "подозрительн", "мошенник", "истекает", "продлите", "пополните",
        "курс валют", "заявка", "рассрочк", "кредитный лимит", "промокод",
        "вход в приложение", "новое устройство",
    )

    /**
     * Ключевые слова типа операции. Порядок важен: «перевод от» — это доход,
     * поэтому доходные признаки проверяются раньше расходных.
     */
    private val incomeMarkers = listOf(
        "зачислен", "пополнение", "пополнен", "поступлен", "перевод от", "получен перевод",
        "вам перевел", "вам перевёл", "вам перечислил", "зарплата", "аванс",
    )
    private val refundMarkers = listOf("возврат", "отмена операции", "отмена покупки")
    private val withdrawalMarkers = listOf("снятие", "выдача наличных", "получение наличных", "внесение наличных")
    private val purchaseMarkers = listOf("покупка", "оплата", "оплачено", "списание", "списан", "потрачено", "платеж", "платёж")
    private val transferMarkers = listOf("перевод", "перечислен", "отправлен")

    /** Куски, которые сами по себе мерчантом быть не могут. */
    private val merchantStopWords = setOf(
        "карта", "карты", "карте", "карту", "картой", "счет", "счёт", "счета", "счёта",
        "счету", "счёту", "покупка", "оплата", "оплачено", "списание", "списан", "списано",
        "перевод", "переводом", "пополнение", "зачисление", "снятие", "возврат",
        "руб", "руб.", "р", "р.", "рублей", "мир", "visa", "mastercard", "maestro", "mir",
        "успешно", "выполнен", "выполнена", "операция", "сбп", "sbp", "наличных", "наличными",
    )

    /** Предлоги и служебные приставки, которые срезаются с начала имени мерчанта. */
    private val merchantPrefixes = listOf(
        "перевод от ", "перевод ", "покупка ", "оплата ", "оплачено ", "списание ", "снятие ",
        "возврат ", "зачисление ", "пополнение ", "поступление ", "в ", "во ", "на ", "у ",
        "от ", "по ", "из ", "для ", "через ",
    )

    /**
     * @param packageName пакет приложения, приславшего уведомление.
     * @param title заголовок уведомления (для СМС — отправитель).
     * @param text текст уведомления (склеенный `EXTRA_TEXT` / `EXTRA_BIG_TEXT`).
     * @return операция или null, если уведомление не банковское либо не про движение денег.
     */
    fun parse(
        packageName: String,
        title: String?,
        text: String?,
        postedAtMillis: Long,
    ): ParsedNotification? {
        val source = BankCatalog.byPackage(packageName) ?: return null
        val bank = if (source.isSms) {
            // Из СМС-приложения читаем только сообщения известных банковских отправителей.
            BankCatalog.bankBySmsSender(title) ?: return null
        } else {
            source.code
        }

        // Для СМС заголовок — это отправитель, он в тексте операции не участвует.
        val body = if (source.isSms) text.orEmpty() else listOfNotNull(
            title?.takeIf { it.isNotBlank() },
            text?.takeIf { it.isNotBlank() },
        ).joinToString(". ")
        val raw = body.replace(Regex("""\s+"""), " ").trim()
        if (raw.isEmpty()) return null

        val lower = raw.lowercase()
        if (hardIgnoreMarkers.any { it in lower }) return null

        val kind = detectKind(lower)
        val money = findAmount(raw) ?: return null

        // Нет ни одного признака операции — берём только если текст короткий и похож
        // на операционный (есть сумма). Такое уходит в «не распознано» на ручной разбор.
        if (kind == NotificationKind.UNKNOWN && raw.length > 160) return null

        val amountMinor = MoneyFormat.parseToMinor(money.value.groupValues[1]) ?: return null
        if (amountMinor <= 0) return null

        return ParsedNotification(
            bank = bank,
            packageName = packageName,
            kind = kind,
            amountMinor = amountMinor,
            currency = currencyOf(money.value.groupValues[2]),
            merchant = extractMerchant(raw, money.range)?.takeIf { it.isNotBlank() },
            cardMask = extractCardMask(raw),
            postedAtMillis = postedAtMillis,
            title = title,
            rawText = raw,
        )
    }

    // --- составные части разбора ---

    private fun detectKind(lower: String): NotificationKind = when {
        refundMarkers.any { it in lower } -> NotificationKind.REFUND
        incomeMarkers.any { it in lower } -> NotificationKind.INCOME
        withdrawalMarkers.any { it in lower } -> NotificationKind.WITHDRAWAL
        purchaseMarkers.any { it in lower } -> NotificationKind.PURCHASE
        transferMarkers.any { it in lower } -> NotificationKind.TRANSFER_OUT
        else -> NotificationKind.UNKNOWN
    }

    private data class Money(val value: MatchResult, val range: IntRange)

    /**
     * Первый денежный токен, перед которым нет слова «баланс» / «доступно» / «кэшбэк».
     * Именно он — сумма операции: банки ставят её раньше остатка.
     */
    private fun findAmount(raw: String): Money? {
        val lower = raw.lowercase()
        for (match in MONEY.findAll(raw)) {
            val from = (match.range.first - 24).coerceAtLeast(0)
            val prefix = lower.substring(from, match.range.first)
            if (balanceMarkers.any { it in prefix }) continue
            return Money(match, match.range)
        }
        return null
    }

    private fun currencyOf(token: String): String = when (token.lowercase().trim('.', ' ')) {
        "$", "usd" -> "USD"
        "€", "eur" -> "EUR"
        else -> "RUB"
    }

    private fun extractCardMask(raw: String): String? =
        CARD_MASKED.find(raw)?.groupValues?.get(1)
            ?: CARD_WORD.find(raw)?.groupValues?.get(1)

    /**
     * Имя мерчанта (или отправителя перевода). Сначала ищем справа от суммы — там оно
     * стоит у большинства банков, затем слева. Хвост с остатком по счёту отбрасывается.
     */
    private fun extractMerchant(raw: String, amountRange: IntRange): String? {
        val head = cutBalanceTail(raw)
        val afterStart = (amountRange.last + 1).coerceAtMost(head.length)
        val after = head.substring(afterStart)
        val before = head.substring(0, amountRange.first.coerceAtMost(head.length))

        return pickMerchant(after, fromEnd = false) ?: pickMerchant(before, fromEnd = true)
    }

    /** Отрезает всё начиная со слов про остаток/бонусы — это уже не про операцию. */
    private fun cutBalanceTail(raw: String): String {
        val lower = raw.lowercase()
        val cut = balanceMarkers.mapNotNull { marker ->
            lower.indexOf(marker).takeIf { it >= 0 }
        }.minOrNull() ?: return raw
        return raw.substring(0, cut)
    }

    /**
     * Выбирает из фрагмента кусок, похожий на имя мерчанта.
     * @param fromEnd просматривать куски с конца (для фрагмента слева от суммы).
     */
    private fun pickMerchant(fragment: String, fromEnd: Boolean): String? {
        val chunks = fragment
            .split('.', ',', ';', '|', '\n', '(', ')')
            .map { cleanChunk(it) }
            .filter { it.isNotEmpty() }
        val ordered = if (fromEnd) chunks.asReversed() else chunks
        return ordered.firstOrNull { isMerchantLike(it) }
    }

    private fun cleanChunk(chunk: String): String {
        var s = chunk
        s = MONEY.replace(s, " ")
        s = CARD_MASKED.replace(s, " ")
        s = CARD_WORD.replace(s, " ")
        s = TIME.replace(s, " ")
        s = DATE.replace(s, " ")
        s = s.replace(Regex("""\s+"""), " ").trim().trim('"', '\'', '«', '»', '-', ':', '№')
        // Срезаем служебные приставки, пока они есть: «Покупка в PYATEROCHKA» → «PYATEROCHKA».
        var changed = true
        while (changed) {
            changed = false
            val lower = s.lowercase()
            for (prefix in merchantPrefixes) {
                if (lower.startsWith(prefix)) {
                    s = s.substring(prefix.length).trim()
                    changed = true
                    break
                }
            }
        }
        return trimStopWords(s)
    }

    /**
     * Срезает служебные слова по краям: «Карта SPAR» → «SPAR».
     * Внутри имени такие слова не трогаем — там они могут быть частью названия.
     */
    private fun trimStopWords(value: String): String {
        var words = value.split(' ').filter { it.isNotBlank() }
        while (words.isNotEmpty() && words.first().lowercase() in merchantStopWords) {
            words = words.drop(1)
        }
        while (words.isNotEmpty() && words.last().lowercase() in merchantStopWords) {
            words = words.dropLast(1)
        }
        return words.joinToString(" ")
    }

    private fun isMerchantLike(chunk: String): Boolean {
        val letters = chunk.count { it.isLetter() }
        if (letters < 2) return false
        val lower = chunk.lowercase()
        if (lower in merchantStopWords) return false
        // Строка из одних служебных слов мерчантом не является.
        val words = lower.split(' ').filter { it.isNotBlank() }
        return words.any { it !in merchantStopWords }
    }
}
