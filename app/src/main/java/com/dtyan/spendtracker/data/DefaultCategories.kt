package com.dtyan.spendtracker.data

/**
 * Дефолтное дерево категорий, которым заполняется база при первом запуске.
 * Пользователь может добавлять свои категории и подкатегории, а встроенные — архивировать.
 */
object DefaultCategories {

    data class Seed(
        val name: String,
        val icon: String,
        val colorArgb: Int,
        val subcategories: List<String>,
        /** true — категория для пополнений (доходов). */
        val isIncome: Boolean = false,
    )

    val tree: List<Seed> = listOf(
        Seed(
            name = "Продукты",
            icon = "🛒",
            colorArgb = 0xFF4CAF50.toInt(),
            subcategories = listOf(
                "Супермаркет", "Рынок", "Мясо и рыба", "Овощи и фрукты",
                "Хлеб и выпечка", "Напитки", "Сладости", "Бытовая химия",
            ),
        ),
        Seed(
            name = "Кафе и рестораны",
            icon = "🍽️",
            colorArgb = 0xFFFF7043.toInt(),
            subcategories = listOf(
                "Кофе", "Обед", "Ужин", "Доставка еды", "Бар", "Фастфуд",
            ),
        ),
        Seed(
            name = "Транспорт",
            icon = "🚇",
            colorArgb = 0xFF42A5F5.toInt(),
            subcategories = listOf(
                "Общественный транспорт", "Такси", "Каршеринг", "Самокат",
                "Бензин", "Парковка", "Обслуживание авто", "Штрафы", "ОСАГО/КАСКО",
            ),
        ),
        Seed(
            name = "Жильё",
            icon = "🏠",
            colorArgb = 0xFF8D6E63.toInt(),
            subcategories = listOf(
                "Аренда", "Ипотека", "Коммунальные услуги", "Электричество",
                "Интернет", "Ремонт", "Мебель", "Техника для дома",
            ),
        ),
        Seed(
            name = "Здоровье",
            icon = "💊",
            colorArgb = 0xFFEF5350.toInt(),
            subcategories = listOf(
                "Аптека", "Врач", "Анализы", "Стоматология", "Оптика", "ДМС", "Психотерапия",
            ),
        ),
        Seed(
            name = "Одежда и обувь",
            icon = "👕",
            colorArgb = 0xFFAB47BC.toInt(),
            subcategories = listOf(
                "Одежда", "Обувь", "Аксессуары", "Химчистка", "Ремонт одежды",
            ),
        ),
        Seed(
            name = "Развлечения",
            icon = "🎮",
            colorArgb = 0xFFEC407A.toInt(),
            subcategories = listOf(
                "Кино", "Концерты", "Игры", "Книги", "Хобби", "Бары и клубы", "Спортивные события",
            ),
        ),
        Seed(
            name = "Подписки и сервисы",
            icon = "📱",
            colorArgb = 0xFF26A69A.toInt(),
            subcategories = listOf(
                "Стриминг", "Музыка", "Облако", "Мобильная связь", "ПО и приложения", "ИИ-сервисы",
            ),
        ),
        Seed(
            name = "Спорт",
            icon = "🏃",
            colorArgb = 0xFF66BB6A.toInt(),
            subcategories = listOf(
                "Абонемент", "Тренер", "Инвентарь", "Спортпит", "Бассейн",
            ),
        ),
        Seed(
            name = "Образование",
            icon = "🎓",
            colorArgb = 0xFF5C6BC0.toInt(),
            subcategories = listOf(
                "Курсы", "Книги и учебники", "Языки", "Конференции", "Сертификации",
            ),
        ),
        Seed(
            name = "Красота и уход",
            icon = "💇",
            colorArgb = 0xFFF06292.toInt(),
            subcategories = listOf(
                "Парикмахерская", "Косметика", "Маникюр", "СПА и массаж",
            ),
        ),
        Seed(
            name = "Путешествия",
            icon = "✈️",
            colorArgb = 0xFF29B6F6.toInt(),
            subcategories = listOf(
                "Авиабилеты", "Ж/д билеты", "Отели", "Экскурсии", "Страховка", "Виза",
            ),
        ),
        Seed(
            name = "Дети",
            icon = "🧸",
            colorArgb = 0xFFFFCA28.toInt(),
            subcategories = listOf(
                "Садик и школа", "Кружки", "Игрушки", "Детская одежда", "Няня",
            ),
        ),
        Seed(
            name = "Питомцы",
            icon = "🐶",
            colorArgb = 0xFFA1887F.toInt(),
            subcategories = listOf(
                "Корм", "Ветеринар", "Груминг", "Игрушки и аксессуары",
            ),
        ),
        Seed(
            name = "Подарки",
            icon = "🎁",
            colorArgb = 0xFFD4E157.toInt(),
            subcategories = listOf(
                "Дни рождения", "Праздники", "Цветы", "Благотворительность",
            ),
        ),
        Seed(
            name = "Финансы",
            icon = "💳",
            colorArgb = 0xFF78909C.toInt(),
            subcategories = listOf(
                "Комиссии банка", "Проценты по кредиту", "Налоги", "Страхование", "Инвестиции",
            ),
        ),
        Seed(
            name = "Прочее",
            icon = "📦",
            colorArgb = 0xFF9E9E9E.toInt(),
            subcategories = listOf(
                "Без категории", "Наличные снятие", "Непредвиденное",
            ),
        ),
        // --- Доходы / пополнения ---
        Seed(
            name = "Пополнения",
            icon = "💰",
            colorArgb = 0xFF2E7D5B.toInt(),
            subcategories = listOf(
                "Зарплата", "Аванс", "Премия", "Перевод", "Возврат", "Кэшбэк", "Проценты", "Прочее",
            ),
            isIncome = true,
        ),
    )

    /** Палитра для новых пользовательских категорий. */
    val palette: List<Int> = listOf(
        0xFF4CAF50, 0xFFFF7043, 0xFF42A5F5, 0xFF8D6E63, 0xFFEF5350,
        0xFFAB47BC, 0xFFEC407A, 0xFF26A69A, 0xFF5C6BC0, 0xFF29B6F6,
        0xFFFFCA28, 0xFFA1887F, 0xFFD4E157, 0xFF78909C, 0xFF9E9E9E,
    ).map { it.toInt() }

    val iconChoices: List<String> = listOf(
        "🛒", "🍽️", "🚇", "🏠", "💊",
        "👕", "🎮", "📱", "🏃", "🎓",
        "💇", "✈️", "🧸", "🐶", "🎁",
        "💳", "📦", "☕", "🍻", "🔧",
        "💻", "🚗", "⛽", "🎨", "🎵",
    )
}
