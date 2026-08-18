package com.dtyan.spendtracker.notifications

/**
 * Приводит сырое имя мерчанта к стабильному ключу, по которому его можно сравнивать
 * и искать в словаре: `RUS MOSCOW PYATEROCHKA 5643 / ООО "АГРОТОРГ"` → `PYATEROCHKA`.
 *
 * Чистая функция без Android — покрывается обычными unit-тестами.
 */
object MerchantNormalizer {

    /** Служебные слова банковских описаний, не несущие смысла. */
    private val noiseWords = setOf(
        "RUS", "RU", "MOSCOW", "MOSKVA", "SPB", "ST", "PETERSBURG", "MSK",
        "OOO", "OAO", "ZAO", "AO", "IP", "PAO", "LLC", "LTD", "INC",
        "ООО", "ОАО", "ЗАО", "АО", "ИП", "ПАО",
        "MAGAZIN", "MAGAZIN№", "TORG", "SHOP", "STORE", "PAY", "PAYMENT",
        "КАРТА", "CARD", "OPLATA", "POKUPKA",
    )

    /** Кириллица → латиница: чтобы «ПЯТЁРОЧКА» и «PYATEROCHKA» давали один ключ. */
    private val translit: Map<Char, String> = mapOf(
        'А' to "A", 'Б' to "B", 'В' to "V", 'Г' to "G", 'Д' to "D", 'Е' to "E", 'Ё' to "E",
        'Ж' to "ZH", 'З' to "Z", 'И' to "I", 'Й' to "Y", 'К' to "K", 'Л' to "L", 'М' to "M",
        'Н' to "N", 'О' to "O", 'П' to "P", 'Р' to "R", 'С' to "S", 'Т' to "T", 'У' to "U",
        'Ф' to "F", 'Х' to "H", 'Ц' to "TS", 'Ч' to "CH", 'Ш' to "SH", 'Щ' to "SCH",
        'Ъ' to "", 'Ы' to "Y", 'Ь' to "", 'Э' to "E", 'Ю' to "YU", 'Я' to "YA",
    )

    /**
     * Человекочитаемое имя: схлопнутые пробелы, без хвостовых кодов и кавычек.
     * Показывается пользователю как есть — регистр не меняем.
     */
    fun display(raw: String?): String = raw
        ?.replace(' ', ' ')
        ?.replace(Regex("""\s+"""), " ")
        ?.trim()
        ?.trim('.', ',', ';', '"', '\'', '«', '»', '-')
        .orEmpty()

    /**
     * Ключ сопоставления: верхний регистр, транслитерация, без цифр, форм собственности
     * и городов. Пустая строка означает «мерчанта нет» — по такому ключу ничего не матчим.
     */
    fun key(raw: String?): String {
        val cleaned = display(raw).uppercase()
        if (cleaned.isEmpty()) return ""
        val latin = buildString {
            cleaned.forEach { ch ->
                when {
                    translit.containsKey(ch) -> append(translit[ch])
                    ch.isLetterOrDigit() -> append(ch)
                    else -> append(' ')
                }
            }
        }
        val words = latin.split(' ')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            // Числовые хвосты (номер точки, код города) и служебные слова выкидываем.
            .filter { word -> !word.all { it.isDigit() } }
            .filter { it !in noiseWords }
        return words.joinToString(" ").trim()
    }

    /**
     * Совпадают ли два мерчанта: точное равенство ключей или вхождение одного в другой
     * (`PYATEROCHKA` ↔ `PYATEROCHKA 5643`). Пустые ключи не совпадают ни с чем.
     */
    fun matches(a: String?, b: String?): Boolean {
        val ka = key(a)
        val kb = key(b)
        if (ka.isEmpty() || kb.isEmpty()) return false
        return ka == kb || ka.startsWith(kb) || kb.startsWith(ka)
    }
}
