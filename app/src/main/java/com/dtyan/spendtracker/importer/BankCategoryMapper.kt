package com.dtyan.spendtracker.importer

/**
 * Сопоставляет категорию из банковской выписки и MCC-код со встроенной таксономией
 * приложения (17 категорий из [com.dtyan.spendtracker.data.DefaultCategories]).
 *
 * Приоритет сигналов: MCC (языконезависим и точен) → текстовая категория банка →
 * ничего (пусть пользователь выберет сам). Возвращаемые имена ДОЛЖНЫ совпадать с
 * названиями в DefaultCategories, иначе резолвер id при импорте их не найдёт.
 */
object BankCategoryMapper {

    /** Категория + (опционально) подкатегория приложения. */
    data class Suggestion(val category: String, val subcategory: String?)

    /** По MCC. Коды подобраны под массовые бытовые траты. */
    private val byMcc: Map<Int, Suggestion> = buildMap {
        // Продукты
        listOf(5411, 5412, 5422, 5451, 5462, 5499).forEach { put(it, Suggestion("Продукты", "Супермаркет")) }
        put(5921, Suggestion("Продукты", "Напитки"))
        // Транспорт
        listOf(5541, 5542, 5983).forEach { put(it, Suggestion("Транспорт", "Бензин")) }
        put(4121, Suggestion("Транспорт", "Такси"))
        listOf(4111, 4112, 4131).forEach { put(it, Suggestion("Транспорт", "Общественный транспорт")) }
        listOf(7523, 7512).forEach { put(it, Suggestion("Транспорт", "Парковка")) }
        listOf(5533, 5532, 7538, 7549).forEach { put(it, Suggestion("Транспорт", "Обслуживание авто")) }
        // Кафе и рестораны
        listOf(5812, 5811).forEach { put(it, Suggestion("Кафе и рестораны", "Обед")) }
        put(5814, Suggestion("Кафе и рестораны", "Фастфуд"))
        put(5813, Suggestion("Кафе и рестораны", "Бар"))
        // Здоровье
        listOf(5912, 5122).forEach { put(it, Suggestion("Здоровье", "Аптека")) }
        listOf(8011, 8021, 8031, 8042, 8062, 8071, 8099).forEach { put(it, Suggestion("Здоровье", "Врач")) }
        // Одежда и обувь
        listOf(5611, 5621, 5631, 5641, 5651, 5661, 5691, 5699, 5948).forEach { put(it, Suggestion("Одежда и обувь", "Одежда")) }
        // Развлечения / цифровые товары
        listOf(5815, 5816, 5817, 5818, 7994).forEach { put(it, Suggestion("Развлечения", "Игры")) }
        listOf(7832, 7922, 7929, 7996, 7998).forEach { put(it, Suggestion("Развлечения", "Кино")) }
        put(5942, Suggestion("Развлечения", "Книги"))
        // Подписки и связь
        listOf(4814, 4815, 4816, 4899, 4812).forEach { put(it, Suggestion("Подписки и сервисы", "Мобильная связь")) }
        listOf(4899).forEach { put(it, Suggestion("Подписки и сервисы", "Стриминг")) }
        // Красота
        put(5977, Suggestion("Красота и уход", "Косметика"))
        listOf(7230, 7298).forEach { put(it, Suggestion("Красота и уход", "Парикмахерская")) }
        // Спорт
        listOf(7997, 7991, 7941, 5941).forEach { put(it, Suggestion("Спорт", "Абонемент")) }
        // Жильё
        listOf(4900).forEach { put(it, Suggestion("Жильё", "Коммунальные услуги")) }
        listOf(5200, 5211, 5251, 5712).forEach { put(it, Suggestion("Жильё", "Техника для дома")) }
        // Путешествия
        listOf(3000, 4511, 4722).forEach { put(it, Suggestion("Путешествия", "Авиабилеты")) }
        listOf(3500, 7011).forEach { put(it, Suggestion("Путешествия", "Отели")) }
        put(4112, Suggestion("Путешествия", "Ж/д билеты"))
        // Питомцы
        put(5995, Suggestion("Питомцы", "Корм"))
        // Дети
        put(5945, Suggestion("Дети", "Игрушки"))
    }

    /** По текстовой категории банка (нормализованной к нижнему регистру). */
    private val byBankCategory: Map<String, Suggestion> = mapOf(
        "супермаркеты" to Suggestion("Продукты", "Супермаркет"),
        "продукты" to Suggestion("Продукты", "Супермаркет"),
        "рынки" to Suggestion("Продукты", "Рынок"),
        "фастфуд" to Suggestion("Кафе и рестораны", "Фастфуд"),
        "рестораны" to Suggestion("Кафе и рестораны", "Обед"),
        "кафе" to Suggestion("Кафе и рестораны", "Обед"),
        "кофе" to Suggestion("Кафе и рестораны", "Кофе"),
        "такси" to Suggestion("Транспорт", "Такси"),
        "транспорт" to Suggestion("Транспорт", "Общественный транспорт"),
        "заправки" to Suggestion("Транспорт", "Бензин"),
        "топливо" to Suggestion("Транспорт", "Бензин"),
        "автоуслуги" to Suggestion("Транспорт", "Обслуживание авто"),
        "каршеринг" to Suggestion("Транспорт", "Каршеринг"),
        "связь" to Suggestion("Подписки и сервисы", "Мобильная связь"),
        "интернет" to Suggestion("Жильё", "Интернет"),
        "цифровые товары" to Suggestion("Развлечения", "Игры"),
        "развлечения" to Suggestion("Развлечения", "Кино"),
        "кино" to Suggestion("Развлечения", "Кино"),
        "аптеки" to Suggestion("Здоровье", "Аптека"),
        "здоровье" to Suggestion("Здоровье", "Врач"),
        "медицинские услуги" to Suggestion("Здоровье", "Врач"),
        "красота" to Suggestion("Красота и уход", "Парикмахерская"),
        "одежда и обувь" to Suggestion("Одежда и обувь", "Одежда"),
        "спорттовары" to Suggestion("Спорт", "Инвентарь"),
        "спорт" to Suggestion("Спорт", "Абонемент"),
        "фитнес" to Suggestion("Спорт", "Абонемент"),
        "образование" to Suggestion("Образование", "Курсы"),
        "дети" to Suggestion("Дети", "Игрушки"),
        "питомцы" to Suggestion("Питомцы", "Корм"),
        "зоотовары" to Suggestion("Питомцы", "Корм"),
        "жкх" to Suggestion("Жильё", "Коммунальные услуги"),
        "коммунальные платежи" to Suggestion("Жильё", "Коммунальные услуги"),
        "дом и ремонт" to Suggestion("Жильё", "Ремонт"),
        "путешествия" to Suggestion("Путешествия", "Отели"),
        "авиабилеты" to Suggestion("Путешествия", "Авиабилеты"),
        "отели" to Suggestion("Путешествия", "Отели"),
        "подарки" to Suggestion("Подарки", "Праздники"),
        "маркетплейсы" to Suggestion("Прочее", "Без категории"),
        "различные услуги" to Suggestion("Прочее", "Без категории"),
    )

    /**
     * @return предложенные категория/подкатегория или null, если сопоставить не удалось
     *         (тогда пользователь выбирает вручную — «Прочее» молча не подставляем).
     */
    fun suggest(bankCategory: String?, mcc: Int?): Suggestion? {
        mcc?.let { byMcc[it] }?.let { return it }
        val key = bankCategory?.trim()?.lowercase()
        if (!key.isNullOrEmpty()) byBankCategory[key]?.let { return it }
        return null
    }
}
