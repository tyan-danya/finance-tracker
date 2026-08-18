package com.dtyan.spendtracker.notifications

import com.dtyan.spendtracker.importer.BankCategoryMapper.Suggestion

/**
 * Встроенный словарь «мерчант → категория» для операций из уведомлений.
 *
 * В уведомлении нет ни MCC, ни категории банка — единственный сигнал — это имя мерчанта.
 * Поэтому здесь список массовых сетей; всё остальное пользователь размечает сам,
 * и его выбор становится сильнее словаря (см. историю подтверждений в репозитории).
 *
 * Ключи — нормализованные [MerchantNormalizer.key] значения (латиница, верхний регистр),
 * поэтому «ПЯТЁРОЧКА», «Пятерочка» и «PYATEROCHKA» находятся одинаково.
 * Имена категорий обязаны совпадать с [com.dtyan.spendtracker.data.DefaultCategories].
 */
object MerchantDictionary {

    private val entries: Map<String, Suggestion> = buildMap {
        // --- Продукты ---
        listOf("PYATEROCHKA", "MAGNIT", "PEREKRESTOK", "PEREKRYOSTOK", "LENTA", "DIXY", "DIKSI",
            "AUCHAN", "ASHAN", "METRO CC", "OKEY", "SPAR", "VERNYY", "MONETKA", "YARCHE",
            "MIRATORG", "GLOBUS", "BILLA", "KARUSEL", "MAGNOLIA", "AZBUKA VKUSA")
            .forEach { put(it, Suggestion("Продукты", "Супермаркет")) }
        listOf("VKUSVILL", "SAMOKAT", "YANDEX LAVKA", "LAVKA", "SBERMARKET", "KUPER")
            .forEach { put(it, Suggestion("Продукты", "Супермаркет")) }
        listOf("KRASNOE BELOE", "KB", "BRISTOL", "VINLAB", "AROMATNYY MIR")
            .forEach { put(it, Suggestion("Продукты", "Напитки")) }

        // --- Кафе и рестораны ---
        listOf("VKUSNO I TOCHKA", "MCDONALDS", "KFC", "ROSTICS", "BURGER KING", "SUBWAY",
            "DODO PIZZA", "PAPA JOHNS", "TEREMOK", "CINNABON")
            .forEach { put(it, Suggestion("Кафе и рестораны", "Фастфуд")) }
        listOf("SHOKOLADNITSA", "KOFE HAUZ", "STARBUCKS", "SURF COFFEE", "COFFEE LIKE",
            "ONE PRICE COFFEE", "COFIX", "SKURATOV", "COFFEE")
            .forEach { put(it, Suggestion("Кафе и рестораны", "Кофе")) }
        listOf("YANDEX EDA", "DELIVERY CLUB", "DELIVERI", "KUHNYA NA RAYONE")
            .forEach { put(it, Suggestion("Кафе и рестораны", "Доставка еды")) }

        // --- Транспорт ---
        listOf("YANDEX GO", "YANDEX TAXI", "TAXI", "CITYMOBIL", "UBER", "MAXIM")
            .forEach { put(it, Suggestion("Транспорт", "Такси")) }
        listOf("DELIMOBIL", "YANDEX DRIVE", "BELKACAR", "CITYDRIVE")
            .forEach { put(it, Suggestion("Транспорт", "Каршеринг")) }
        listOf("WHOOSH", "URENT", "SAMOKAT SHARING")
            .forEach { put(it, Suggestion("Транспорт", "Самокат")) }
        listOf("LUKOIL", "GAZPROMNEFT", "ROSNEFT", "TATNEFT", "SHELL", "NEFTMAGISTRAL", "IRBIS", "AZS")
            .forEach { put(it, Suggestion("Транспорт", "Бензин")) }
        listOf("MOSMETRO", "METRO MOSKVY", "TROIKA", "PODOROZHNIK", "MOSGORTRANS", "MOSTRANSAVTO")
            .forEach { put(it, Suggestion("Транспорт", "Общественный транспорт")) }
        listOf("RZD", "RZHD")
            .forEach { put(it, Suggestion("Путешествия", "Ж/д билеты")) }
        listOf("PARKING", "PARKOVKA", "MOSPARKING")
            .forEach { put(it, Suggestion("Транспорт", "Парковка")) }

        // --- Здоровье ---
        listOf("APTEKA", "RIGLA", "GORZDRAV", "ASNA", "PLANETA ZDOROVYA", "STOLICHKI", "OZERKI", "366")
            .forEach { put(it, Suggestion("Здоровье", "Аптека")) }
        listOf("INVITRO", "GEMOTEST", "HELIX", "KDL")
            .forEach { put(it, Suggestion("Здоровье", "Анализы")) }

        // --- Подписки и связь ---
        listOf("MTS", "MEGAFON", "BEELINE", "TELE2", "YOTA", "TINKOFF MOBILE", "T MOBILE")
            .forEach { put(it, Suggestion("Подписки и сервисы", "Мобильная связь")) }
        listOf("YANDEX PLUS", "KINOPOISK", "IVI", "OKKO", "WINK", "PREMIER", "NETFLIX", "START")
            .forEach { put(it, Suggestion("Подписки и сервисы", "Стриминг")) }
        listOf("SPOTIFY", "VK MUSIC", "ZVUK", "APPLE MUSIC", "YANDEX MUSIC")
            .forEach { put(it, Suggestion("Подписки и сервисы", "Музыка")) }
        listOf("GOOGLE", "APPLE COM BILL", "ITUNES", "MICROSOFT", "ADOBE", "JETBRAINS", "GITHUB", "OPENAI", "ANTHROPIC")
            .forEach { put(it, Suggestion("Подписки и сервисы", "ПО и приложения")) }

        // --- Маркетплейсы и техника ---
        listOf("DNS", "MVIDEO", "ELDORADO", "CITILINK", "RESTORE", "SVYAZNOY")
            .forEach { put(it, Suggestion("Жильё", "Техника для дома")) }
        listOf("LEROY MERLIN", "OBI", "PETROVICH", "HOFF", "IKEA", "VSEINSTRUMENTI")
            .forEach { put(it, Suggestion("Жильё", "Ремонт")) }

        // --- Одежда, красота, спорт ---
        listOf("LAMODA", "SPORTMASTER", "DECATHLON", "HM", "ZARA", "UNIQLO", "GLORIA JEANS",
            "OSTIN", "BEFREE", "LOVE REPUBLIC", "KARI", "RENDEZ VOUS")
            .forEach { put(it, Suggestion("Одежда и обувь", "Одежда")) }
        listOf("LETUAL", "LETOILE", "RIVE GAUCHE", "ZOLOTOE YABLOKO", "PODRUZHKA", "MAGNIT KOSMETIK")
            .forEach { put(it, Suggestion("Красота и уход", "Косметика")) }
        listOf("WORLD CLASS", "FITNESS HOUSE", "ALEX FITNESS", "SPIRIT FITNESS", "DDX FITNESS", "FITMOST")
            .forEach { put(it, Suggestion("Спорт", "Абонемент")) }

        // --- Дети и питомцы ---
        listOf("DETSKIY MIR", "DETMIR")
            .forEach { put(it, Suggestion("Дети", "Игрушки")) }
        listOf("PETSHOP", "CHETYRE LAPY", "BETHOVEN", "ZOOZAVR")
            .forEach { put(it, Suggestion("Питомцы", "Корм")) }

        // --- Путешествия ---
        listOf("AEROFLOT", "POBEDA", "S7", "URAL AIRLINES", "AVIASALES", "TUTU")
            .forEach { put(it, Suggestion("Путешествия", "Авиабилеты")) }
        listOf("OSTROVOK", "BRONIRUEM", "BOOKING", "YANDEX PUTESHESTVIYA")
            .forEach { put(it, Suggestion("Путешествия", "Отели")) }

        // --- Развлечения ---
        listOf("STEAM", "PLAYSTATION", "XBOX", "VK PLAY", "GOG")
            .forEach { put(it, Suggestion("Развлечения", "Игры")) }
        listOf("KARO", "FORMULA KINO", "KINOMAX", "CINEMA PARK", "KULT")
            .forEach { put(it, Suggestion("Развлечения", "Кино")) }
        listOf("LITRES", "CHITAY GOROD", "BUKVOED", "MIF")
            .forEach { put(it, Suggestion("Развлечения", "Книги")) }
    }

    /** Ключи длиной меньше этого не ищем внутри строки — слишком много ложных совпадений. */
    private const val MIN_CONTAINS_LENGTH = 4

    /**
     * @param merchant сырое имя мерчанта из уведомления.
     * @return предложение категории или null, если сеть неизвестна.
     *         «Прочее» молча не подставляем — пусть пользователь выберет сам.
     */
    fun suggest(merchant: String?): Suggestion? {
        val key = MerchantNormalizer.key(merchant)
        if (key.isEmpty()) return null
        entries[key]?.let { return it }
        // Частичное совпадение: «PYATEROCHKA 5643 SPB» → «PYATEROCHKA».
        return entries.entries
            .filter { it.key.length >= MIN_CONTAINS_LENGTH && key.contains(it.key) }
            // Из нескольких совпадений берём самое длинное — оно конкретнее.
            .maxByOrNull { it.key.length }
            ?.value
    }
}
